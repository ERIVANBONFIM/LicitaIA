package com.licitaia.core.ai

import com.licitaia.ai.api.AIProvider
import com.licitaia.ai.api.AiProviderException
import com.licitaia.ai.api.DocumentComparison
import com.licitaia.ai.api.EditalAnswer
import com.licitaia.ai.api.EditalQuestionRequest
import com.licitaia.ai.api.MessageDraft
import com.licitaia.ai.api.ProposalDraft
import com.licitaia.ai.api.RelevanceItem
import com.licitaia.ai.api.RelevanceScore
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.ai.mock.MockAIProvider
import com.licitaia.ai.mock.TenderHeuristics
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.ExtractedEdital
import com.licitaia.domain.model.FitScore
import com.licitaia.domain.model.PriceRange
import com.licitaia.domain.model.ProposalItem
import com.licitaia.domain.model.Recommendation
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.util.Formatters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * Base dos provedores reais. Cada operação pede ao modelo um JSON com os campos de julgamento
 * e mescla a resposta sobre a estrutura calculada pelas heurísticas locais: campos ausentes ou
 * inválidos mantêm o valor heurístico, de modo que a análise nunca fica incompleta.
 * Erros de rede/credencial sobem como [AiProviderException].
 */
abstract class LlmBackedProvider(
    protected val json: Json,
) : AIProvider {

    /** Envia [system] + [user] ao modelo e devolve o texto da resposta. */
    protected abstract suspend fun complete(system: String, user: String, expectJson: Boolean): String

    /**
     * Texto livre com temperatura baixa (respostas literais, sem "criatividade"), quando o provedor aceita amostragem
     * customizada. Padrão: igual a [complete] — os modelos de raciocínio atuais da OpenAI/Anthropic rejeitam `temperature`.
     */
    protected open suspend fun completePrecise(system: String, user: String): String = complete(system, user, expectJson = false)

    private suspend fun completeJson(user: String): JsonObject? =
        extractJsonObject(complete(SYSTEM_JSON, user, expectJson = true), json)

    // ------------------------------------------------------------------ análise

    override suspend fun analyzeTender(request: TenderAnalysisRequest): TenderAnalysis {
        val base = TenderHeuristics.analyze(request, displayName)
        val hasEdital = !request.editalText.isNullOrBlank()
        val prompt = buildString {
            appendLine("Analise a licitação abaixo para a empresa informada e devolva um objeto JSON com as chaves:")
            appendLine("summary (string, até 4 frases), justification (string objetiva), recommendation (\"PARTICIPAR\" | \"AVALIAR\" | \"NAO_PARTICIPAR\"),")
            appendLine("criticalPoints (array de strings: riscos e pontos de atenção concretos, citando a cláusula/item do edital quando houver),")
            appendLine("installationDeadline (string), sla (string),")
            appendLine("requiredDocuments (array de strings: TODOS os documentos de habilitação exigidos, com o nome como consta no edital),")
            appendLine("technicalRequirements, guarantees, penalties, attestationRequirements, certificationRequirements (arrays de strings),")
            appendLine("risks {operational, contractual} (\"BAIXO\" | \"MEDIO\" | \"ALTO\" | \"CRITICO\"),")
            appendLine("scores {technical (0-100), financial (0-100), estimatedMarginPct (número)},")
            appendLine("priceRange {min, suggested, max} em reais (números).")
            if (hasEdital) {
                appendLine("O TEXTO DO EDITAL abaixo é a fonte principal: extraia exigências, documentos, prazos, garantias e penalidades DELE.")
                appendLine("Não invente exigências ausentes do texto. Se um dado não constar, use null ou array vazio.")
            } else {
                appendLine("Não há texto do edital: use apenas os metadados; quando não houver base, mantenha a estimativa preliminar e diga isso em criticalPoints.")
            }
            appendLine()
            appendLine(describeTender(request.tender))
            appendLine(describeCompany(request.company))
            appendLine("DOCUMENTOS DA EMPRESA (situação hoje):")
            appendLine(describeDocuments(request.documents, request.now))
            appendLine("ESTIMATIVA PRELIMINAR (calculada localmente; ajuste se o edital justificar):")
            appendLine(
                "técnica=${base.fit.technical}, documental=${base.fit.documentary}, financeira=${base.fit.financial}, " +
                    "margem=${base.fit.estimatedMarginPct}%, concorrentes históricos=${base.fit.historicalCompetitors}, " +
                    "faixa de preço=${base.priceRange.min}/${base.priceRange.suggested}/${base.priceRange.max}",
            )
            appendLine("Documentos exigidos presumidos (substitua pelos do edital quando houver texto): ${base.extracted.requiredDocuments.joinToString { it.label }}")
            appendLine("Tipos de documento reconhecidos pelo cofre da empresa: ${DocumentType.entries.joinToString { it.label }}")
            append(editalSection(request.editalText))
        }
        val raw = complete(SYSTEM_JSON, prompt, expectJson = true)
        val obj = extractJsonObject(raw, json)
            ?: throw AiProviderException("$displayName devolveu uma resposta fora do formato esperado (JSON inválido). Tente novamente.")

        val aiFields = mutableSetOf<String>()
        val mergedExtracted = mergeExtracted(base.extracted, obj, aiFields)

        // Documentos exigidos vindos do edital real: substituem a presunção e recalculam cofre/checklist.
        val documentNames = obj.strList("requiredDocuments")
        val mapping = documentNames?.let { DocumentTypeMapping.map(it) }
        val extracted = if (mapping != null && mapping.types.isNotEmpty()) {
            aiFields += "requiredDocuments"
            mergedExtracted.copy(requiredDocuments = mapping.types)
        } else {
            mergedExtracted
        }
        // Reconstrói aderência documental, checklist e pontos críticos do cofre com as exigências reais.
        val rebuilt = TenderHeuristics.analyze(request, displayName, extracted)

        val scores = obj.obj("scores")
        val technical = scores?.num("technical")?.roundToInt()?.takeIf { it in 0..100 }?.also { aiFields += "technical" } ?: rebuilt.fit.technical
        val financial = scores?.num("financial")?.roundToInt()?.takeIf { it in 0..100 }?.also { aiFields += "financial" } ?: rebuilt.fit.financial
        val margin = scores?.num("estimatedMarginPct")?.takeIf { it in -50.0..90.0 }?.also { aiFields += "estimatedMarginPct" } ?: rebuilt.fit.estimatedMarginPct
        val risks = obj.obj("risks")
        val operationalRisk = parseRisk(risks?.str("operational"))?.also { aiFields += "operationalRisk" } ?: rebuilt.fit.operationalRisk
        val contractualRisk = parseRisk(risks?.str("contractual"))?.also { aiFields += "contractualRisk" } ?: rebuilt.fit.contractualRisk
        val fit = rebuilt.fit.copy(
            technical = technical,
            financial = financial,
            estimatedMarginPct = margin,
            operationalRisk = operationalRisk,
            contractualRisk = contractualRisk,
            overall = TenderHeuristics.overall(
                technical, rebuilt.fit.documentary, financial, rebuilt.fit.geographicFit, rebuilt.fit.deadlineFit,
            ),
        )

        val aiCritical = obj.strList("criticalPoints")?.also { aiFields += "criticalPoints" }
        val criticalPoints = buildList {
            if (aiCritical != null) {
                addAll(aiCritical)
                // Fatos do cofre (ausente/vencido) são aritmética local e continuam valendo.
                addAll(rebuilt.criticalPoints.filter { it.startsWith("Documento ") || it.contains(" vence em ") })
            } else {
                addAll(rebuilt.criticalPoints)
            }
            if (mapping != null && mapping.unmatched.isNotEmpty()) {
                add("Documentos exigidos sem tipo correspondente no cofre: ${mapping.unmatched.joinToString("; ")}")
            }
        }.distinct()

        val summary = obj.str("summary")?.also { aiFields += "summary" } ?: rebuilt.summary
        val justification = obj.str("justification")?.also { aiFields += "justification" } ?: rebuilt.justification
        val recommendation = parseRecommendation(obj.str("recommendation"))?.also { aiFields += "recommendation" } ?: TenderHeuristics.recommend(fit)
        val priceRange = parseRange(obj.obj("priceRange"), request.tender.estimatedValue)?.also { aiFields += "priceRange" } ?: rebuilt.priceRange

        return rebuilt.copy(
            summary = summary,
            extracted = extracted,
            fit = fit,
            recommendation = recommendation,
            justification = justification,
            criticalPoints = criticalPoints.ifEmpty { listOf("Nenhum ponto crítico identificado nos dados disponíveis") },
            priceRange = priceRange,
            aiFields = aiFields,
        )
    }

    override suspend fun summarize(text: String, maxSentences: Int): String {
        val answer = complete(
            SYSTEM_TEXT,
            "Resuma o texto a seguir em no máximo $maxSentences frase(s), em português do Brasil, " +
                "sem introdução nem marcadores:\n\n$text",
            expectJson = false,
        ).trim()
        return answer.ifEmpty { TenderHeuristics.summarize(text, maxSentences) }
    }

    override suspend fun extractRequirements(tender: Tender, editalText: String?): ExtractedEdital {
        val base = TenderHeuristics.extract(tender, editalText)
        // Sem o texto do edital não há o que extrair além dos metadados.
        if (editalText.isNullOrBlank()) return base
        val prompt = buildString {
            appendLine("Extraia do edital as exigências e devolva um objeto JSON com as chaves:")
            appendLine("installationDeadline (string), sla (string), requiredDocuments (array de strings com os documentos de habilitação),")
            appendLine("technicalRequirements, guarantees, penalties, attestationRequirements, certificationRequirements (arrays de strings).")
            appendLine("Não invente exigências que não estejam no texto.")
            appendLine()
            appendLine(describeTender(tender))
            append(editalSection(editalText))
        }
        val obj = completeJson(prompt) ?: return base
        val merged = mergeExtracted(base, obj)
        val mapped = obj.strList("requiredDocuments")?.let { DocumentTypeMapping.map(it) }
        return if (mapped != null && mapped.types.isNotEmpty()) merged.copy(requiredDocuments = mapped.types) else merged
    }

    override suspend fun scoreFit(
        extracted: ExtractedEdital,
        company: Company,
        documents: List<CompanyDocument>,
    ): FitScore {
        val now = System.currentTimeMillis()
        val base = TenderHeuristics.fit(MockAIProvider.tenderFrom(extracted, company), extracted, company, documents, now)
        val prompt = buildString {
            appendLine("Avalie a aderência da empresa ao edital e devolva um objeto JSON com as chaves:")
            appendLine("technical (0-100), financial (0-100), estimatedMarginPct (número),")
            appendLine("operationalRisk e contractualRisk (\"BAIXO\" | \"MEDIO\" | \"ALTO\" | \"CRITICO\").")
            appendLine()
            appendLine("OBJETO: ${extracted.objectDescription}")
            appendLine("ÓRGÃO: ${extracted.agency} | VALOR ESTIMADO: ${Formatters.brl(extracted.estimatedValue)}")
            appendLine("SLA: ${extracted.sla} | PRAZO: ${extracted.installationDeadline}")
            appendLine("EXIGÊNCIAS TÉCNICAS: ${extracted.technicalRequirements.joinToString("; ")}")
            appendLine("GARANTIAS: ${extracted.guarantees.joinToString("; ")}")
            appendLine("PENALIDADES: ${extracted.penalties.joinToString("; ")}")
            appendLine(describeCompany(company))
            appendLine("DOCUMENTOS DA EMPRESA:")
            appendLine(describeDocuments(documents, now))
            append("ESTIMATIVA PRELIMINAR: técnica=${base.technical}, financeira=${base.financial}, margem=${base.estimatedMarginPct}%")
        }
        val obj = completeJson(prompt) ?: return base
        val technical = obj.num("technical")?.roundToInt()?.takeIf { it in 0..100 } ?: base.technical
        val financial = obj.num("financial")?.roundToInt()?.takeIf { it in 0..100 } ?: base.financial
        return base.copy(
            technical = technical,
            financial = financial,
            estimatedMarginPct = obj.num("estimatedMarginPct")?.takeIf { it in -50.0..90.0 } ?: base.estimatedMarginPct,
            operationalRisk = parseRisk(obj.str("operationalRisk")) ?: base.operationalRisk,
            contractualRisk = parseRisk(obj.str("contractualRisk")) ?: base.contractualRisk,
            overall = TenderHeuristics.overall(technical, base.documentary, financial, base.geographicFit, base.deadlineFit),
        )
    }

    override suspend fun suggestProposalRange(tender: Tender, extracted: ExtractedEdital, company: Company): PriceRange {
        val fit = TenderHeuristics.fit(tender, extracted, company, emptyList(), System.currentTimeMillis())
        val base = TenderHeuristics.priceRange(tender.estimatedValue, fit.historicalCompetitors, fit.estimatedMarginPct)
        val prompt = buildString {
            appendLine("Sugira a faixa de preço da proposta e devolva um objeto JSON {min, suggested, max} em reais (números).")
            appendLine("min = piso que ainda preserva margem; suggested = preço competitivo; max = teto abaixo do valor estimado.")
            appendLine()
            appendLine(describeTender(tender))
            appendLine(describeCompany(company))
            appendLine("SLA: ${extracted.sla} | PRAZO: ${extracted.installationDeadline}")
            append("ESTIMATIVA PRELIMINAR: ${base.min}/${base.suggested}/${base.max}; margem típica ${fit.estimatedMarginPct}%")
        }
        return parseRange(completeJson(prompt), tender.estimatedValue) ?: base
    }

    override suspend fun draftProposal(tender: Tender, analysis: TenderAnalysis?, company: Company): ProposalDraft {
        val base = TenderHeuristics.proposal(tender, analysis, company)
        val target = base.items.sumOf { it.total }
        val prompt = buildString {
            appendLine("Monte o rascunho da proposta comercial e devolva um objeto JSON com as chaves:")
            appendLine("items (array de {description, unit, quantity, unitPrice}), deliveryDays (inteiro), validityDays (inteiro), notes (string).")
            appendLine("O total dos itens deve ficar próximo de ${Formatters.brl(target)} e nunca acima do valor estimado.")
            appendLine()
            appendLine(describeTender(tender))
            appendLine(describeCompany(company))
            if (analysis != null) {
                appendLine("SLA: ${analysis.extracted.sla} | PRAZO: ${analysis.extracted.installationDeadline}")
                appendLine("EXIGÊNCIAS: ${analysis.extracted.technicalRequirements.joinToString("; ")}")
                appendLine("FAIXA DE PREÇO: ${analysis.priceRange.min} / ${analysis.priceRange.suggested} / ${analysis.priceRange.max}")
            }
            append("ESTRUTURA DE REFERÊNCIA: ${base.items.joinToString("; ") { "${it.description} (${it.quantity} ${it.unit})" }}")
        }
        val obj = completeJson(prompt) ?: return base
        val items = obj.arr("items")?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val description = item.str("description") ?: return@mapNotNull null
            val quantity = item.num("quantity")?.takeIf { it > 0 } ?: return@mapNotNull null
            val unitPrice = item.num("unitPrice")?.takeIf { it > 0 } ?: return@mapNotNull null
            ProposalItem(description, item.str("unit") ?: "un", quantity, unitPrice)
        }.orEmpty()
        val total = items.sumOf { it.total }
        val ceiling = if (tender.estimatedValue > 0) tender.estimatedValue * 1.02 else Double.MAX_VALUE
        val validItems = items.takeIf { it.isNotEmpty() && total > 0 && total <= ceiling } ?: base.items
        return ProposalDraft(
            items = validItems,
            deliveryDays = obj.num("deliveryDays")?.roundToInt()?.takeIf { it in 1..720 } ?: base.deliveryDays,
            validityDays = obj.num("validityDays")?.roundToInt()?.takeIf { it in 30..365 } ?: base.validityDays,
            notes = obj.str("notes") ?: base.notes,
        )
    }

    override suspend fun draftMessage(message: AuctioneerMessage, company: Company): MessageDraft {
        val base = TenderHeuristics.message(message, company)
        val prompt = buildString {
            appendLine("Leia a mensagem do pregoeiro e devolva um objeto JSON com as chaves:")
            appendLine("summary (1-2 frases com o que foi pedido e o prazo), suggestedReply (resposta formal e curta, sem assumir compromissos de preço), urgent (boolean).")
            appendLine("A resposta será revisada e enviada por um humano.")
            appendLine()
            appendLine("PORTAL: ${message.portal.displayName} | PREGÃO: ${message.tenderNumber} | REMETENTE: ${message.sender}")
            appendLine("RECEBIDA EM: ${Formatters.dateTime(message.receivedAt)}")
            message.responseDeadline?.let { appendLine("PRAZO DE RESPOSTA: ${Formatters.dateTime(it)}") }
            appendLine(describeCompany(company))
            append("MENSAGEM:\n${message.body}")
        }
        val obj = completeJson(prompt) ?: return base
        return MessageDraft(
            summary = obj.str("summary") ?: base.summary,
            suggestedReply = obj.str("suggestedReply") ?: base.suggestedReply,
            urgent = (obj.bool("urgent") ?: false) || base.urgent,
        )
    }

    override suspend fun draftAppeal(tender: Tender, grounds: String, company: Company): String {
        val prompt = buildString {
            appendLine("Redija a minuta de um recurso administrativo (Lei nº 14.133/2021, art. 165) com as seções:")
            appendLine("tempestividade, fatos e fundamentos, pedido. Texto corrido, formal, sem markdown.")
            appendLine("Não invente fatos além dos fundamentos informados; deixe lacunas entre colchetes quando faltar dado.")
            appendLine()
            appendLine(describeTender(tender))
            appendLine(describeCompany(company))
            append("FUNDAMENTOS INFORMADOS:\n$grounds")
        }
        val answer = complete(SYSTEM_TEXT, prompt, expectJson = false).trim()
        return if (answer.length < 200) {
            TenderHeuristics.appeal(tender, grounds, company)
        } else {
            "$answer\n\nMinuta gerada por IA — revisão jurídica obrigatória antes do protocolo."
        }
    }

    /** Comparação de validade é aritmética de datas: feita localmente, sem enviar dados ao modelo. */
    override suspend fun compareDocuments(
        required: List<DocumentType>,
        available: List<CompanyDocument>,
        now: Long,
    ): DocumentComparison = TenderHeuristics.compare(required, available, now)

    /**
     * Nota de relevância em lote (até 25 objetos por chamada; o restante é ignorado). Resposta inválida → lança
     * [AiProviderException] para que quem chama mantenha a heurística marcada como tal (sem nota "inventada").
     */
    override suspend fun rateRelevance(company: Company, radarHint: String, items: List<RelevanceItem>): List<RelevanceScore> {
        val batch = items.take(AIProvider.MAX_RELEVANCE_BATCH)
        if (batch.isEmpty()) return emptyList()
        val raw = complete(RelevanceParsing.SYSTEM, RelevanceParsing.prompt(radarHint, batch), expectJson = true)
        val scores = RelevanceParsing.parse(raw, batch, json)
        if (scores.isEmpty()) {
            throw AiProviderException("$displayName devolveu notas fora do formato esperado. As notas continuam heurísticas.")
        }
        return scores
    }

    /** Texto livre (sem JSON): a resposta vem com as linhas "Fonte: ..." pedidas no prompt. Vazia → erro. */
    override suspend fun askEdital(request: EditalQuestionRequest): EditalAnswer {
        val answer = completePrecise(request.system, request.prompt).trim()
        if (answer.isEmpty()) throw AiProviderException("$displayName devolveu uma resposta vazia. Tente novamente.")
        return EditalAnswer(answer, modelName())
    }

    /** Modelo configurado (exibido no histórico de perguntas); null quando não for possível resolver. */
    protected open suspend fun modelName(): String? = null

    // ------------------------------------------------------------------ apoio

    /** Mescla campos textuais da IA sobre a base heurística, anotando em [aiFields] o que veio do modelo. */
    private fun mergeExtracted(base: ExtractedEdital, obj: JsonObject, aiFields: MutableSet<String> = mutableSetOf()): ExtractedEdital {
        fun str(key: String, fallback: String) = obj.str(key)?.also { aiFields += key } ?: fallback
        fun list(key: String, fallback: List<String>) = obj.strList(key)?.also { aiFields += key } ?: fallback
        return base.copy(
            installationDeadline = str("installationDeadline", base.installationDeadline),
            sla = str("sla", base.sla),
            technicalRequirements = list("technicalRequirements", base.technicalRequirements),
            guarantees = list("guarantees", base.guarantees),
            penalties = list("penalties", base.penalties),
            attestationRequirements = list("attestationRequirements", base.attestationRequirements),
            certificationRequirements = list("certificationRequirements", base.certificationRequirements),
        )
    }

    private fun parseRecommendation(value: String?): Recommendation? {
        val key = value?.uppercase()?.replace('Ã', 'A')?.replace(' ', '_') ?: return null
        return Recommendation.entries.firstOrNull { it.name == key }
    }

    private fun parseRisk(value: String?): RiskLevel? {
        val key = value?.uppercase()?.replace('É', 'E')?.replace('Í', 'I') ?: return null
        return RiskLevel.entries.firstOrNull { it.name == key }
    }

    private fun parseRange(obj: JsonObject?, estimatedValue: Double): PriceRange? {
        val min = obj?.num("min") ?: return null
        val suggested = obj.num("suggested") ?: return null
        val max = obj.num("max") ?: return null
        val ceiling = if (estimatedValue > 0) estimatedValue * 1.05 else Double.MAX_VALUE
        return PriceRange(min, suggested, max).takeIf { min > 0 && min <= suggested && suggested <= max && max <= ceiling }
    }

    private fun describeTender(tender: Tender): String = buildString {
        appendLine("LICITAÇÃO:")
        appendLine("- ${tender.modality.label} nº ${tender.number} — ${tender.agency} (${tender.city}/${tender.uf}), portal ${tender.portal.displayName}")
        appendLine("- Objeto: ${tender.objectDescription}")
        appendLine("- Segmento: ${tender.segment.label} | Valor estimado: ${Formatters.brl(tender.estimatedValue)}")
        append("- Propostas até ${Formatters.dateTime(tender.proposalDeadline)} | Sessão em ${Formatters.dateTime(tender.sessionAt)}")
    }

    private fun describeCompany(company: Company): String =
        "EMPRESA: ${company.name} — segmento ${company.segment.label}, sede em ${company.city}/${company.uf}"

    private fun describeDocuments(documents: List<CompanyDocument>, now: Long): String =
        if (documents.isEmpty()) {
            "- (nenhum documento cadastrado)"
        } else {
            documents.joinToString("\n") { doc ->
                val validity = doc.expiresAt?.let { " (validade ${Formatters.date(it)})" }.orEmpty()
                "- ${doc.type.label}: ${doc.status(now).label}$validity"
            }
        }

    private fun editalSection(editalText: String?): String {
        if (editalText.isNullOrBlank()) return "TEXTO DO EDITAL: não disponível — analise a partir dos metadados."
        val truncated = editalText.length > MAX_EDITAL_CHARS
        return buildString {
            appendLine(if (truncated) "TEXTO DO EDITAL (apenas o trecho inicial; o restante não foi enviado):" else "TEXTO DO EDITAL:")
            append(if (truncated) editalText.take(MAX_EDITAL_CHARS) else editalText)
        }
    }

    private companion object {
        const val MAX_EDITAL_CHARS = 120_000

        const val SYSTEM_JSON =
            "Você é um analista sênior de licitações públicas brasileiras (Lei nº 14.133/2021), objetivo e conservador. " +
                "Escreva em português do Brasil. Responda SOMENTE com um único objeto JSON válido, sem markdown, " +
                "sem comentários e sem texto fora do JSON."

        const val SYSTEM_TEXT =
            "Você é um analista sênior de licitações públicas brasileiras (Lei nº 14.133/2021). " +
                "Escreva em português do Brasil, de forma objetiva e formal."
    }
}
