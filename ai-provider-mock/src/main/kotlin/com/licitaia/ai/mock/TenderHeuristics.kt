package com.licitaia.ai.mock

import com.licitaia.ai.api.DocumentComparison
import com.licitaia.ai.api.MessageDraft
import com.licitaia.ai.api.ProposalDraft
import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.ChecklistItem
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.ExtractedEdital
import com.licitaia.domain.model.FitScore
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.PriceRange
import com.licitaia.domain.model.ProposalItem
import com.licitaia.domain.model.Recommendation
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.scoring.BrazilRegions
import com.licitaia.domain.scoring.SegmentAffinity
import com.licitaia.domain.scoring.TextMatch
import com.licitaia.domain.util.Formatters
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Heurísticas determinísticas de análise de licitações. Sem I/O e sem aleatoriedade:
 * a mesma entrada sempre produz a mesma saída. Usadas pelo provedor de demonstração e
 * como rede de segurança dos provedores reais quando a resposta do modelo é incompleta.
 */
object TenderHeuristics {

    private const val DAY = 24L * 60 * 60 * 1000

    private val baseDocuments = listOf(
        DocumentType.CONTRATO_SOCIAL, DocumentType.CNPJ, DocumentType.CERTIDAO_FEDERAL,
        DocumentType.CERTIDAO_ESTADUAL, DocumentType.CERTIDAO_MUNICIPAL, DocumentType.FGTS,
        DocumentType.TRABALHISTA, DocumentType.BALANCO,
    )

    /** Variação estável (-range..range) derivada do número do edital. */
    private fun jitter(seed: String, range: Int): Int {
        if (range <= 0) return 0
        return abs(seed.hashCode() % (2 * range + 1)) - range
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    // ------------------------------------------------------------------ extração

    fun extract(tender: Tender, editalText: String?): ExtractedEdital {
        val text = TextMatch.normalize(tender.objectDescription + " " + editalText.orEmpty())
        fun has(vararg terms: String) = terms.any { text.contains(it) }
        val high = tender.estimatedValue >= 500_000
        val dispensa = tender.modality == Modality.DISPENSA_ELETRONICA

        val requirements = mutableListOf<String>()
        val attest = mutableListOf<String>()
        val certs = mutableListOf<String>()
        val docs = baseDocuments.toMutableList()
        var installation: String
        var sla: String

        when (tender.segment) {
            Segment.TELECOM_ISP -> {
                installation = if (high) "Até 45 dias corridos após a assinatura do contrato" else "Até 30 dias corridos após a ordem de serviço"
                sla = "Disponibilidade mínima mensal de 99,5%, com reparo em até 4 horas e central 24x7"
                requirements += "Link dedicado em fibra óptica com banda simétrica e garantia de 100% da velocidade contratada"
                requirements += "Bloco de endereços IP fixos (IPv4) e suporte a IPv6"
                requirements += "Monitoramento proativo 24x7 com portal de acompanhamento e relatórios mensais"
                if (has("redund", "contingencia", "dupla abordagem")) requirements += "Rota redundante/dupla abordagem para os pontos críticos"
                if (has("wi-fi", "wifi", "ponto de acesso", "access point")) requirements += "Fornecimento e gerência de pontos de acesso Wi-Fi com controladora"
                if (has("escola", "unidades", "pontos")) requirements += "Atendimento a múltiplos pontos distribuídos no município, com cronograma de ativação"
                if (has("anti-ddos", "ddos", "firewall")) requirements += "Proteção anti-DDoS e firewall gerenciado"
                attest += "Atestado de capacidade técnica de fornecimento de link dedicado com banda igual ou superior a 50% da licitada"
                certs += "Autorização SCM vigente expedida pela Anatel"
                docs += DocumentType.SCM
                docs += DocumentType.ATESTADO
                if (high) {
                    docs += DocumentType.CREA_CRT
                    certs += "Registro da empresa e do responsável técnico no CREA/CRT"
                }
            }
            Segment.SOFTWARE -> {
                installation = "Implantação em até 60 dias, incluindo migração de dados e treinamento"
                sla = "Disponibilidade de 99,5% e atendimento a chamados críticos em até 2 horas úteis"
                requirements += "Solução web responsiva hospedada em nuvem, com backup diário"
                requirements += "Migração dos dados legados e integração com os sistemas do órgão"
                requirements += "Treinamento dos usuários e suporte técnico durante a vigência"
                requirements += "Conformidade com a LGPD e trilha de auditoria das operações"
                if (has("prova de conceito", "poc", "demonstracao")) requirements += "Prova de conceito (PoC) com roteiro definido no termo de referência"
                attest += "Atestado de implantação de sistema similar em órgão público ou empresa de porte equivalente"
                docs += DocumentType.ATESTADO
                if (high) {
                    certs += "Certificação ISO/IEC 27001 ou declaração de boas práticas de segurança da informação"
                    docs += DocumentType.CERTIFICADO
                }
            }
            Segment.TI -> {
                installation = "Início dos serviços em até 30 dias após a assinatura"
                sla = "Atendimento a incidentes críticos em até 2 horas e solução em até 8 horas"
                requirements += "Equipe técnica certificada alocada conforme o termo de referência"
                requirements += "Central de serviços com registro de chamados e indicadores mensais"
                requirements += "Gestão de infraestrutura (servidores, redes e backup) com monitoramento contínuo"
                if (has("firewall", "seguranca")) requirements += "Solução de segurança de perímetro com atualização de assinaturas"
                if (has("nuvem", "cloud")) requirements += "Serviços de computação em nuvem com faturamento por consumo"
                attest += "Atestado de prestação de serviços de TI compatíveis em quantidade e prazo"
                docs += DocumentType.ATESTADO
                if (high) {
                    certs += "Profissionais com certificação do fabricante/ITIL comprovada"
                    docs += DocumentType.CERTIFICADO
                }
            }
            Segment.EQUIPAMENTOS -> {
                installation = "Entrega em até 30 dias corridos após o recebimento da nota de empenho"
                sla = "Garantia on-site mínima de 36 meses, com atendimento no próximo dia útil"
                requirements += "Equipamentos novos, de primeiro uso e em linha de produção"
                requirements += "Garantia do fabricante com assistência técnica autorizada"
                requirements += "Catálogo/ficha técnica comprovando as especificações mínimas"
                if (has("instalacao", "configuracao")) requirements += "Instalação e configuração nos locais indicados pelo órgão"
                attest += "Atestado de fornecimento de equipamentos similares"
                certs += "Declaração do fabricante ou de distribuidor autorizado"
                docs += DocumentType.ATESTADO
                docs += DocumentType.DECLARACAO
            }
            Segment.SERVICOS, Segment.PERSONALIZADO -> {
                installation = "Início em até 15 dias após a ordem de serviço"
                sla = "Atendimento a chamados em até 24 horas úteis"
                requirements += "Execução conforme o termo de referência, com preposto designado"
                requirements += "Fornecimento de materiais, ferramentas e EPIs necessários"
                requirements += "Relatório mensal de execução para ateste da fiscalização"
                attest += "Atestado de capacidade técnica compatível com o objeto"
                docs += DocumentType.ATESTADO
            }
        }
        docs += DocumentType.DECLARACAO

        // Quando há texto de edital, aproveita prazos/SLA explícitos.
        if (editalText != null) {
            Regex("(\\d{2}[.,]\\d{1,2})\\s*%").find(editalText)?.let { m ->
                if (text.contains("disponibilidade")) sla = "Disponibilidade mínima de ${m.groupValues[1]}% conforme o edital"
            }
            Regex("(?:prazo|instala\\w+|entrega|implanta\\w+)[^.\\n]{0,60}?(\\d{1,3})\\s*dias", RegexOption.IGNORE_CASE)
                .find(editalText)?.let { m -> installation = "Até ${m.groupValues[1]} dias conforme o edital" }
        }

        val guarantees = buildList {
            if (high && !dispensa) add("Garantia de execução contratual de 5% do valor do contrato (art. 96 da Lei 14.133/2021)")
            if (tender.segment == Segment.EQUIPAMENTOS) add("Garantia dos equipamentos por no mínimo 36 meses")
            if (isEmpty()) add("Não há exigência de garantia de proposta ou de execução")
        }
        val penalties = buildList {
            add("Multa moratória de 0,33% por dia de atraso, limitada a 10% do valor do contrato")
            if (tender.segment == Segment.TELECOM_ISP || tender.segment == Segment.TI || tender.segment == Segment.SOFTWARE) {
                add("Glosa proporcional na fatura por descumprimento do SLA")
            }
            add("Multa compensatória de até 20% por inexecução total ou parcial")
            add("Impedimento de licitar e contratar por até 3 anos (art. 156 da Lei 14.133/2021)")
        }

        return ExtractedEdital(
            objectDescription = tender.objectDescription,
            agency = tender.agency,
            portal = tender.portal.displayName,
            number = tender.number,
            modality = tender.modality.label,
            estimatedValue = tender.estimatedValue,
            proposalDeadline = tender.proposalDeadline,
            openingAt = tender.sessionAt,
            sessionAt = tender.sessionAt,
            installationDeadline = installation,
            sla = sla,
            technicalRequirements = requirements,
            requiredDocuments = docs.distinct(),
            guarantees = guarantees,
            penalties = penalties,
            attestationRequirements = attest,
            certificationRequirements = certs,
        )
    }

    // ------------------------------------------------------------------ documentos

    fun bestStatus(type: DocumentType, documents: List<CompanyDocument>, now: Long): DocumentStatus {
        val statuses = documents.filter { it.type == type }.map { it.status(now) }
        return when {
            DocumentStatus.VALIDO in statuses -> DocumentStatus.VALIDO
            DocumentStatus.VENCE_EM_BREVE in statuses -> DocumentStatus.VENCE_EM_BREVE
            DocumentStatus.VENCIDO in statuses -> DocumentStatus.VENCIDO
            else -> DocumentStatus.AUSENTE
        }
    }

    fun compare(required: List<DocumentType>, available: List<CompanyDocument>, now: Long): DocumentComparison {
        val byStatus = required.distinct().groupBy { bestStatus(it, available, now) }
        return DocumentComparison(
            satisfied = byStatus[DocumentStatus.VALIDO].orEmpty(),
            expiring = byStatus[DocumentStatus.VENCE_EM_BREVE].orEmpty(),
            expired = byStatus[DocumentStatus.VENCIDO].orEmpty(),
            missing = byStatus[DocumentStatus.AUSENTE].orEmpty(),
        )
    }

    private fun documentaryScore(comparison: DocumentComparison): Int {
        val total = comparison.satisfied.size + comparison.expiring.size + comparison.expired.size + comparison.missing.size
        if (total == 0) return 100
        val points = comparison.satisfied.size * 1.0 + comparison.expiring.size * 0.8 + comparison.expired.size * 0.25
        return (points / total * 100).roundToInt().coerceIn(0, 100)
    }

    // ------------------------------------------------------------------ aderência

    fun overall(technical: Int, documentary: Int, financial: Int, geographic: Int, deadline: Int): Int =
        (technical * 0.30 + documentary * 0.25 + financial * 0.20 + geographic * 0.15 + deadline * 0.10)
            .roundToInt().coerceIn(0, 100)

    private fun baseMargin(segment: Segment): Double = when (segment) {
        Segment.TELECOM_ISP -> 24.0
        Segment.SOFTWARE -> 32.0
        Segment.TI -> 18.0
        Segment.EQUIPAMENTOS -> 11.0
        Segment.SERVICOS -> 15.0
        Segment.PERSONALIZADO -> 14.0
    }

    private fun typicalCompetitors(segment: Segment): Int = when (segment) {
        Segment.TELECOM_ISP -> 4
        Segment.SOFTWARE -> 5
        Segment.TI -> 7
        Segment.EQUIPAMENTOS -> 9
        Segment.SERVICOS -> 8
        Segment.PERSONALIZADO -> 6
    }

    private fun investmentFactor(segment: Segment, sameUf: Boolean): Double = when (segment) {
        Segment.TELECOM_ISP -> if (sameUf) 0.16 else 0.30
        Segment.SOFTWARE -> 0.05
        Segment.TI -> 0.12
        Segment.EQUIPAMENTOS -> 0.55
        Segment.SERVICOS -> 0.10
        Segment.PERSONALIZADO -> 0.12
    }

    fun fit(
        tender: Tender,
        extracted: ExtractedEdital,
        company: Company,
        documents: List<CompanyDocument>,
        now: Long,
    ): FitScore {
        val seed = tender.number + tender.portal.name
        val sameUf = company.uf.equals(tender.uf, true)
        val affinity = SegmentAffinity.of(company.segment, tender.segment)
        val comparison = compare(extracted.requiredDocuments, documents, now)
        val documentary = documentaryScore(comparison)
        val geographic = BrazilRegions.geographicFit(company.uf, company.city, tender.uf, tender.city)

        var technical = (affinity * 0.92).roundToInt() + jitter(seed, 4)
        val attestStatus = bestStatus(DocumentType.ATESTADO, documents, now)
        if (DocumentType.ATESTADO in extracted.requiredDocuments) {
            technical += if (attestStatus == DocumentStatus.VALIDO || attestStatus == DocumentStatus.VENCE_EM_BREVE) 4 else -12
        }
        val scmStatus = bestStatus(DocumentType.SCM, documents, now)
        val scmBlocked = DocumentType.SCM in extracted.requiredDocuments &&
            (scmStatus == DocumentStatus.AUSENTE || scmStatus == DocumentStatus.VENCIDO)
        if (scmBlocked) technical -= 22
        if (tender.estimatedValue > 2_000_000) technical -= 6
        if (tender.segment == Segment.TELECOM_ISP && !sameUf) technical -= 8
        technical = technical.coerceIn(5, 98)

        var margin = baseMargin(tender.segment) + jitter(seed + "m", 3)
        if (!sameUf) margin -= if (tender.segment == Segment.TELECOM_ISP) 7.0 else 3.0
        if (tender.modality == Modality.PREGAO_ELETRONICO) margin -= 2.0
        if (affinity < 60) margin -= 5.0
        margin = margin.coerceIn(2.0, 45.0)

        val investment = round2(tender.estimatedValue * investmentFactor(tender.segment, sameUf))
        var financial = (42 + margin * 2.0).roundToInt()
        if (tender.estimatedValue > 1_500_000) financial -= 8
        if (investment > tender.estimatedValue * 0.4) financial -= 10
        financial = financial.coerceIn(5, 97)

        val daysToDeadline = (tender.proposalDeadline - now).toDouble() / DAY
        val deadlineFit = when {
            daysToDeadline < 0 -> 5
            daysToDeadline < 2 -> 25
            daysToDeadline < 5 -> 52
            daysToDeadline < 10 -> 76
            else -> 94
        }

        val documentaryRisk = when {
            scmBlocked || comparison.missing.size >= 3 -> RiskLevel.CRITICO
            comparison.missing.isNotEmpty() || comparison.expired.size >= 2 -> RiskLevel.ALTO
            comparison.expired.isNotEmpty() || comparison.expiring.size >= 2 -> RiskLevel.MEDIO
            else -> RiskLevel.BAIXO
        }
        val operationalRisk = when {
            geographic < 40 && tender.segment == Segment.TELECOM_ISP -> RiskLevel.CRITICO
            geographic < 60 || technical < 50 -> RiskLevel.ALTO
            geographic < 90 || technical < 75 -> RiskLevel.MEDIO
            else -> RiskLevel.BAIXO
        }
        val contractualRisk = when {
            tender.estimatedValue >= 2_000_000 -> RiskLevel.ALTO
            tender.estimatedValue >= 500_000 -> RiskLevel.MEDIO
            tender.segment == Segment.TELECOM_ISP || tender.segment == Segment.SOFTWARE -> RiskLevel.MEDIO
            else -> RiskLevel.BAIXO
        }

        return FitScore(
            overall = overall(technical, documentary, financial, geographic, deadlineFit),
            technical = technical,
            documentary = documentary,
            financial = financial,
            estimatedMarginPct = round2(margin),
            operationalRisk = operationalRisk,
            documentaryRisk = documentaryRisk,
            contractualRisk = contractualRisk,
            deadlineFit = deadlineFit,
            historicalCompetitors = (typicalCompetitors(tender.segment) + jitter(seed + "c", 2)).coerceAtLeast(2),
            geographicFit = geographic,
            investmentNeeded = investment,
        )
    }

    fun recommend(fit: FitScore): Recommendation = when {
        fit.deadlineFit <= 5 -> Recommendation.NAO_PARTICIPAR
        fit.overall >= 72 && fit.documentaryRisk != RiskLevel.CRITICO &&
            fit.operationalRisk != RiskLevel.CRITICO && fit.deadlineFit > 25 -> Recommendation.PARTICIPAR
        fit.overall >= 50 -> Recommendation.AVALIAR
        else -> Recommendation.NAO_PARTICIPAR
    }

    // ------------------------------------------------------------------ preço

    fun priceRange(estimatedValue: Double, competitors: Int, marginPct: Double): PriceRange {
        if (estimatedValue <= 0.0) return PriceRange(0.0, 0.0, 0.0)
        val discount = (0.05 + competitors * 0.012).coerceIn(0.05, 0.22)
        val suggested = estimatedValue * (1 - discount)
        val cost = suggested * (1 - marginPct / 100.0)
        val min = (cost * 1.05).coerceAtMost(suggested * 0.97)
        val max = estimatedValue * 0.985
        return PriceRange(round2(min), round2(suggested), round2(max))
    }

    // ------------------------------------------------------------------ análise completa

    /**
     * Análise heurística completa. [extractedOverride] permite recalcular aderência, checklist e
     * pontos críticos a partir de exigências extraídas por um modelo de IA (documentos reais do edital).
     * O resultado é sempre heurístico ([TenderAnalysis.aiFields] vazio): quem mescla campos da IA rotula.
     */
    fun analyze(request: TenderAnalysisRequest, providerName: String, extractedOverride: ExtractedEdital? = null): TenderAnalysis {
        val tender = request.tender
        val company = request.company
        val now = request.now
        val extracted = extractedOverride ?: extract(tender, request.editalText)
        val fit = fit(tender, extracted, company, request.documents, now)
        val comparison = compare(extracted.requiredDocuments, request.documents, now)
        val recommendation = recommend(fit)
        val range = priceRange(tender.estimatedValue, fit.historicalCompetitors, fit.estimatedMarginPct)
        val sameUf = company.uf.equals(tender.uf, true)
        val daysToDeadline = ((tender.proposalDeadline - now) / DAY).toInt()

        val critical = buildList {
            comparison.missing.forEach { add("Documento ausente no cofre: ${it.label}") }
            comparison.expired.forEach { add("Documento vencido: ${it.label} — renovar antes do envio da proposta") }
            comparison.expiring.forEach { type ->
                val doc = request.documents.filter { it.type == type }.maxByOrNull { it.expiresAt ?: 0 }
                val days = doc?.daysToExpire(now)
                val beforeSession = doc?.expiresAt?.let { it < tender.sessionAt } ?: false
                if (beforeSession) {
                    add("${type.label} vence em $days dia(s), antes da sessão — providenciar renovação")
                } else {
                    add("${type.label} vence em $days dia(s) — acompanhar a validade até a habilitação")
                }
            }
            if (!sameUf) {
                if (tender.segment == Segment.TELECOM_ISP) {
                    add("Execução em ${tender.city}/${tender.uf}, fora da área de cobertura atual (${company.uf}) — exige rede de terceiros ou construção de rota")
                } else {
                    add("Órgão em ${tender.city}/${tender.uf}, fora do estado-sede (${company.uf}) — considerar custo de deslocamento e suporte")
                }
            }
            if (daysToDeadline in 0..4) add("Prazo curto: restam $daysToDeadline dia(s) para o envio da proposta")
            if (daysToDeadline < 0) add("O prazo de envio de propostas já se encerrou")
            if (extracted.guarantees.any { it.startsWith("Garantia de execução") }) {
                add("Exige garantia contratual de 5% (${Formatters.brl(tender.estimatedValue * 0.05)} sobre o valor estimado)")
            }
            if (fit.investmentNeeded > tender.estimatedValue * 0.25) {
                add("Investimento inicial estimado em ${Formatters.brl(fit.investmentNeeded)} antes do primeiro faturamento")
            }
            if (SegmentAffinity.of(company.segment, tender.segment) < 60) {
                add("Objeto (${tender.segment.label}) fora do segmento principal da empresa (${company.segment.label})")
            }
            if (fit.historicalCompetitors >= 8) add("Concorrência histórica elevada (média de ${fit.historicalCompetitors} licitantes) pressiona a margem")
        }

        val coverage = comparison.coveragePct
        val justification = buildString {
            append("Score geral ${fit.overall}/100: aderência técnica ${fit.technical}, documental ${fit.documentary} ")
            append("e financeira ${fit.financial}. ")
            append(
                when (SegmentAffinity.of(company.segment, tender.segment)) {
                    100 -> "O objeto está no segmento principal da ${company.tradeName} (${company.segment.label}). "
                    in 60..99 -> "O objeto (${tender.segment.label}) é correlato à atuação da ${company.tradeName}. "
                    else -> "O objeto (${tender.segment.label}) foge da atuação principal da ${company.tradeName}. "
                },
            )
            append("O cofre cobre $coverage% dos ${extracted.requiredDocuments.size} documentos exigidos")
            val pending = comparison.missing.size + comparison.expired.size
            append(if (pending > 0) ", com $pending pendência(s) impeditiva(s). " else ". ")
            append("Margem estimada de ${Formatters.percent(fit.estimatedMarginPct)} no preço sugerido de ${Formatters.brl(range.suggested)}")
            append(", com média histórica de ${fit.historicalCompetitors} concorrentes. ")
            append(
                when (recommendation) {
                    Recommendation.PARTICIPAR -> "Recomendação: PARTICIPAR — boa aderência e riscos controláveis."
                    Recommendation.AVALIAR -> "Recomendação: AVALIAR — há pontos críticos que precisam ser resolvidos antes de decidir."
                    Recommendation.NAO_PARTICIPAR -> "Recomendação: NÃO PARTICIPAR — a relação risco/retorno é desfavorável."
                },
            )
        }

        val summary = buildString {
            append("${tender.modality.label} nº ${tender.number} — ${tender.agency} (${tender.city}/${tender.uf}), ")
            append("via ${tender.portal.displayName}. Objeto: ${tender.objectDescription.trim().trimEnd('.')}. ")
            append("Valor estimado de ${Formatters.brl(tender.estimatedValue)}; propostas até ${Formatters.dateTime(tender.proposalDeadline)} ")
            append("e sessão em ${Formatters.dateTime(tender.sessionAt)}. ")
            append("Principais exigências: ${extracted.sla.replaceFirstChar { it.lowercase() }}; ${extracted.installationDeadline.replaceFirstChar { it.lowercase() }}.")
        }

        val checklist = buildList {
            extracted.requiredDocuments.forEach { type ->
                val status = bestStatus(type, request.documents, now)
                val doc = request.documents.filter { it.type == type }.maxByOrNull { it.expiresAt ?: Long.MAX_VALUE }
                val expiresBeforeSession = doc?.expiresAt?.let { it < tender.sessionAt } ?: false
                when (status) {
                    DocumentStatus.VALIDO -> add(ChecklistItem("${type.label} — válido no cofre", true, false, type))
                    DocumentStatus.VENCE_EM_BREVE -> add(
                        ChecklistItem(
                            "${type.label} — vence em ${doc?.daysToExpire(now)} dia(s)" +
                                if (expiresBeforeSession) ": renovar antes da sessão" else ": acompanhar validade",
                            !expiresBeforeSession, expiresBeforeSession, type,
                        ),
                    )
                    DocumentStatus.VENCIDO -> add(ChecklistItem("${type.label} — VENCIDO: emitir nova via", false, true, type))
                    DocumentStatus.AUSENTE -> add(ChecklistItem("${type.label} — ausente: providenciar e anexar ao cofre", false, true, type))
                }
            }
            add(ChecklistItem("Validar viabilidade técnica e cobertura em ${tender.city}/${tender.uf}", false, !sameUf))
            add(ChecklistItem("Definir custo, piso e margem mínima com o Financeiro", false, true))
            add(ChecklistItem("Elaborar a proposta comercial e gerar o PDF", false))
            add(ChecklistItem("Aprovar a proposta (Diretoria) antes do envio ao portal", false, true))
            add(ChecklistItem("Conferir credenciamento e sessão ativa no ${tender.portal.displayName}", false))
        }

        return TenderAnalysis(
            tenderId = tender.id,
            providerName = providerName,
            generatedAt = now,
            summary = summary,
            extracted = extracted,
            fit = fit,
            recommendation = recommendation,
            justification = justification,
            criticalPoints = critical.ifEmpty { listOf("Nenhum ponto crítico identificado nos dados disponíveis") },
            priceRange = range,
            checklist = checklist,
        )
    }

    // ------------------------------------------------------------------ proposta

    fun proposal(tender: Tender, analysis: TenderAnalysis?, company: Company): ProposalDraft {
        val target = analysis?.priceRange?.suggested?.takeIf { it > 0 } ?: (tender.estimatedValue * 0.9)
        fun monthly(share: Double, months: Int) = round2(target * share / months)
        fun single(share: Double) = round2(target * share)
        val shortObject = tender.objectDescription.trim().trimEnd('.').let { if (it.length > 110) it.take(107) + "..." else it }

        val items: List<ProposalItem>
        val delivery: Int
        when (tender.segment) {
            Segment.TELECOM_ISP -> {
                items = listOf(
                    ProposalItem("Serviço mensal — $shortObject", "mês", 12.0, monthly(0.86, 12)),
                    ProposalItem("Instalação, ativação e configuração dos circuitos", "serviço", 1.0, single(0.08)),
                    ProposalItem("Gerência proativa 24x7 e suporte técnico especializado", "mês", 12.0, monthly(0.06, 12)),
                )
                delivery = 30
            }
            Segment.SOFTWARE -> {
                items = listOf(
                    ProposalItem("Licenciamento/assinatura — $shortObject", "mês", 12.0, monthly(0.58, 12)),
                    ProposalItem("Implantação, parametrização e migração de dados", "serviço", 1.0, single(0.22)),
                    ProposalItem("Treinamento de usuários e administradores", "turma", 4.0, round2(target * 0.06 / 4)),
                    ProposalItem("Suporte técnico e manutenção evolutiva", "mês", 12.0, monthly(0.14, 12)),
                )
                delivery = 60
            }
            Segment.TI -> {
                items = listOf(
                    ProposalItem("Serviços continuados — $shortObject", "mês", 12.0, monthly(0.80, 12)),
                    ProposalItem("Transição, inventário e implantação da central de serviços", "serviço", 1.0, single(0.10)),
                    ProposalItem("Monitoramento e relatórios gerenciais de SLA", "mês", 12.0, monthly(0.10, 12)),
                )
                delivery = 30
            }
            Segment.EQUIPAMENTOS -> {
                val units = (target / 6_500.0).roundToInt().coerceIn(1, 500)
                items = listOf(
                    ProposalItem("Fornecimento — $shortObject", "un", units.toDouble(), round2(target * 0.88 / units)),
                    ProposalItem("Garantia on-site estendida (36 meses)", "un", units.toDouble(), round2(target * 0.08 / units)),
                    ProposalItem("Entrega, instalação e configuração", "serviço", 1.0, single(0.04)),
                )
                delivery = 30
            }
            Segment.SERVICOS, Segment.PERSONALIZADO -> {
                items = listOf(
                    ProposalItem("Execução dos serviços — $shortObject", "mês", 12.0, monthly(0.90, 12)),
                    ProposalItem("Mobilização e implantação", "serviço", 1.0, single(0.10)),
                )
                delivery = 15
            }
        }

        val notes = buildString {
            append("Proposta da ${company.name} (CNPJ ${Formatters.cnpj(company.cnpj)}) para o ")
            append("${tender.modality.label} nº ${tender.number} — ${tender.agency}. ")
            append("Preços em reais, incluídos todos os tributos, encargos, fretes e despesas necessários à execução. ")
            if (analysis != null) {
                append("Atendimento ao SLA: ${analysis.extracted.sla}. ")
                append("Prazo: ${analysis.extracted.installationDeadline}. ")
            }
            append("Declaramos pleno atendimento às condições do edital e seus anexos. ")
            append("Rascunho gerado por IA — revisar valores e condições antes da aprovação.")
        }
        return ProposalDraft(items = items, deliveryDays = delivery, validityDays = 60, notes = notes)
    }

    // ------------------------------------------------------------------ mensagens

    fun message(message: AuctioneerMessage, company: Company): MessageDraft {
        val text = TextMatch.normalize(message.body)
        fun has(vararg terms: String) = terms.any { text.contains(it) }
        val shortDeadline = message.responseDeadline?.let { it - message.receivedAt <= 2 * 60 * 60 * 1000L } ?: false
        val urgent = message.urgent || shortDeadline ||
            has("sob pena", "desclassific", "imediat", "minutos", "urgente", "convoc")
        val deadline = message.responseDeadline?.let { " Prazo de resposta: ${Formatters.dateTime(it)}." }.orEmpty()
        val ref = "${message.portal.shortName} ${message.tenderNumber}"

        val (topic, reply) = when {
            has("inexequ", "exequibilidade", "composicao de custos", "planilha de custos") ->
                "O pregoeiro solicita a comprovação da exequibilidade do preço ofertado (planilha de composição de custos)." to
                    "Sr(a). Pregoeiro(a), a ${company.name} confirma a exequibilidade do preço ofertado. Encaminharemos, dentro do prazo fixado, a planilha de composição de custos e os documentos comprobatórios (contratos vigentes e notas fiscais de insumos) que demonstram a viabilidade da proposta."
            has("proposta ajustada", "proposta readequada", "proposta adequada", "proposta atualizada", "readequ", "proposta final") ->
                "O pregoeiro convoca o envio da proposta readequada ao último lance." to
                    "Sr(a). Pregoeiro(a), a ${company.name} acusa o recebimento da convocação e enviará a proposta readequada ao valor do último lance, acompanhada dos anexos exigidos, dentro do prazo estabelecido."
            has("negocia", "desconto", "reduzir", "reducao", "melhor oferta", "contraproposta") ->
                "O pregoeiro abre negociação e pede redução do valor ofertado." to
                    "Sr(a). Pregoeiro(a), agradecemos a oportunidade de negociação. A ${company.name} está analisando internamente a possibilidade de redução, respeitada a exequibilidade da proposta, e retornará com sua melhor oferta dentro do prazo concedido."
            has("habilitac", "documento", "certidao", "anexar", "atestado", "balanco") ->
                "O pregoeiro solicita documentos de habilitação/complementares." to
                    "Sr(a). Pregoeiro(a), a ${company.name} informa que os documentos solicitados serão anexados ao sistema dentro do prazo fixado. Permanecemos à disposição para eventuais diligências."
            has("diligencia", "esclarec", "confirmar", "confirme", "informar", "informe") ->
                "O pregoeiro abre diligência e pede esclarecimentos sobre a proposta." to
                    "Sr(a). Pregoeiro(a), em atenção à diligência, a ${company.name} prestará os esclarecimentos solicitados, com a documentação de suporte, dentro do prazo estabelecido."
            has("suspens", "reabertura", "retomada", "sessao sera", "intervalo") ->
                "Aviso sobre suspensão/reabertura da sessão pública." to
                    "Sr(a). Pregoeiro(a), a ${company.name} registra ciência do comunicado e permanecerá conectada para a retomada da sessão no horário informado."
            has("recurso", "intencao de recorrer", "contrarraz") ->
                "Comunicado sobre a fase recursal (intenção de recurso/contrarrazões)." to
                    "Sr(a). Pregoeiro(a), a ${company.name} registra ciência da abertura do prazo recursal e se manifestará tempestivamente, nos termos do art. 165 da Lei 14.133/2021."
            else ->
                "Comunicado geral do pregoeiro no chat da sessão." to
                    "Sr(a). Pregoeiro(a), a ${company.name} registra ciência da mensagem e permanece à disposição."
        }
        val summary = "[$ref] $topic$deadline" + if (urgent) " Requer ação imediata." else ""
        return MessageDraft(summary = summary, suggestedReply = reply, urgent = urgent)
    }

    // ------------------------------------------------------------------ recurso

    fun appeal(tender: Tender, grounds: String, company: Company): String = buildString {
        appendLine("ILUSTRÍSSIMO(A) SENHOR(A) PREGOEIRO(A) — ${tender.agency.uppercase()}")
        appendLine()
        appendLine("Ref.: ${tender.modality.label} nº ${tender.number} — ${tender.portal.displayName}")
        appendLine("Objeto: ${tender.objectDescription.trim()}")
        appendLine()
        appendLine(
            "${company.name.uppercase()}, inscrita no CNPJ sob o nº ${Formatters.cnpj(company.cnpj)}, com sede em " +
                "${company.city}/${company.uf}, por seu representante legal, vem, tempestivamente, com fundamento no " +
                "art. 165, inciso I, da Lei nº 14.133/2021, interpor",
        )
        appendLine()
        appendLine("RECURSO ADMINISTRATIVO")
        appendLine()
        appendLine("I — DA TEMPESTIVIDADE")
        appendLine(
            "A intenção de recorrer foi manifestada na sessão pública e as presentes razões são apresentadas dentro " +
                "do prazo de 3 (três) dias úteis previsto no art. 165, § 1º, da Lei nº 14.133/2021.",
        )
        appendLine()
        appendLine("II — DOS FATOS E FUNDAMENTOS")
        appendLine(grounds.trim().ifBlank { "[Descrever os fatos e os fundamentos do recurso.]" })
        appendLine()
        appendLine(
            "A decisão recorrida contraria os princípios da vinculação ao edital, do julgamento objetivo e da " +
                "seleção da proposta mais vantajosa (art. 5º da Lei nº 14.133/2021), razão pela qual merece reforma.",
        )
        appendLine()
        appendLine("III — DO PEDIDO")
        appendLine(
            "Diante do exposto, requer o conhecimento e o provimento do presente recurso, com a reforma da decisão " +
                "recorrida; subsidiariamente, a remessa dos autos à autoridade superior, nos termos do art. 165, § 2º.",
        )
        appendLine()
        appendLine("Termos em que pede deferimento.")
        appendLine("${company.city}/${company.uf}, ____ de __________ de ______.")
        appendLine()
        appendLine(company.name)
        append("Minuta gerada por IA — revisão jurídica obrigatória antes do protocolo.")
    }

    // ------------------------------------------------------------------ resumo

    fun summarize(text: String, maxSentences: Int): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        val sentences = clean.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val limit = maxSentences.coerceAtLeast(1)
        if (sentences.size <= limit) return clean
        val weights = listOf(
            "prazo", "valor", "objeto", "exig", "sla", "garantia", "multa", "document", "sessao", "proposta",
            "habilit", "atestado", "desclassific",
        )
        val ranked = sentences.withIndex()
            .sortedByDescending { (index, sentence) ->
                val norm = TextMatch.normalize(sentence)
                weights.count { norm.contains(it) } * 2 + (if (index == 0) 3 else 0)
            }
            .take(limit)
            .sortedBy { it.index }
        return ranked.joinToString(" ") { it.value.trim() }
    }
}
