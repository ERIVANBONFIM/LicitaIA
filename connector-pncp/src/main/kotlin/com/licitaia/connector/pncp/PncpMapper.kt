package com.licitaia.connector.pncp

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.NoDisputeRule
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.scoring.SegmentAffinity
import com.licitaia.domain.scoring.TextMatch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Número de controle PNCP: `<cnpj 14 dígitos>-1-<sequencial 6 dígitos>/<ano>`
 * (ex.: "20918579000183-1-000016/2025", observado na resposta real da API).
 * Ele identifica a contratação nos endpoints `/orgaos/{cnpj}/compras/{ano}/{sequencial}`.
 */
data class PncpControlNumber(val cnpj: String, val ano: Int, val sequencial: Int) {
    val raw: String get() = "$cnpj-1-${sequencial.toString().padStart(6, '0')}/$ano"
    val opportunityId: String get() = "${Portal.PNCP.name}:$raw"
    /** Página pública da contratação no portal web do PNCP. */
    val publicPageUrl: String get() = "https://pncp.gov.br/app/editais/$cnpj/$ano/$sequencial"

    companion object {
        private val PATTERN = Regex("""^(\d{14})-1-(\d{1,6})/(\d{4})$""")

        fun parse(raw: String?): PncpControlNumber? {
            val m = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            return PncpControlNumber(m.groupValues[1], m.groupValues[3].toInt(), m.groupValues[2].toInt())
        }

        /** Aceita tanto o número de controle puro quanto o id interno "PNCP:<número>". */
        fun fromOpportunityId(id: String): PncpControlNumber? = parse(id.removePrefix("${Portal.PNCP.name}:"))
    }
}

/**
 * Códigos de modalidade da API do PNCP. Confirmados por respostas reais de
 * `/v1/contratacoes/publicacao?codigoModalidadeContratacao=N` em 06/10/2026:
 * 4 "Concorrência - Eletrônica", 5 "Concorrência - Presencial", 6 "Pregão - Eletrônico",
 * 7 "Pregão - Presencial", 8 "Dispensa", 9 "Inexigibilidade", 12 "Credenciamento".
 * O OpenAPI não lista a tabela de domínio; códigos não observados não são usados.
 */
object PncpModalities {
    const val CONCORRENCIA_ELETRONICA = 4
    const val CONCORRENCIA_PRESENCIAL = 5
    const val PREGAO_ELETRONICO = 6
    const val PREGAO_PRESENCIAL = 7
    const val DISPENSA = 8
    const val INEXIGIBILIDADE = 9
    const val CREDENCIAMENTO = 12

    /** Código usado nas consultas para cada modalidade do app. */
    fun codeOf(modality: Modality): Int = when (modality) {
        Modality.PREGAO_ELETRONICO -> PREGAO_ELETRONICO
        Modality.DISPENSA_ELETRONICA -> DISPENSA
        Modality.CONCORRENCIA -> CONCORRENCIA_ELETRONICA
        Modality.CREDENCIAMENTO -> CREDENCIAMENTO
    }

    /** Códigos consultados quando o filtro não fixa modalidade (só os que o app representa). */
    val SEARCHED_CODES: List<Int> = listOf(PREGAO_ELETRONICO, DISPENSA, CONCORRENCIA_ELETRONICA, CREDENCIAMENTO)

    /** Modalidade do app para um código da API; null = não representada (presencial, inexigibilidade, leilão...). */
    fun modalityOf(code: Long?): Modality? = when (code?.toInt()) {
        PREGAO_ELETRONICO -> Modality.PREGAO_ELETRONICO
        DISPENSA -> Modality.DISPENSA_ELETRONICA
        CONCORRENCIA_ELETRONICA, CONCORRENCIA_PRESENCIAL -> Modality.CONCORRENCIA
        CREDENCIAMENTO -> Modality.CREDENCIAMENTO
        else -> null
    }
}

/** Conversão DTO → domínio. Lógica pura e testável. */
internal object PncpMapper {

    /** O PNCP publica datas sem fuso ("2025-09-12T15:15:04"); são horários de Brasília. */
    val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")
    val QUERY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun queryDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate().format(QUERY_DATE)

    fun parseDate(text: String?): Long? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(t.take(19)).atZone(ZONE).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(t.take(10)).atStartOfDay(ZONE).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * Devolve null quando a contratação não pode ser representada (sem número de controle válido
     * ou modalidade fora das suportadas pelo app). Nada é inventado: campos ausentes ficam vazios/0.
     */
    /** Situação oficial (nome) + datas de proposta da contratação. */
    fun toOfficialStatus(dto: PncpContratacao): com.licitaia.domain.model.OfficialStatus =
        com.licitaia.domain.model.OfficialStatus(
            situation = dto.situacaoCompraNome?.trim()?.takeIf { it.isNotEmpty() },
            proposalDeadline = parseDate(dto.dataEncerramentoProposta) ?: Opportunity.DEADLINE_UNKNOWN,
            proposalOpening = parseDate(dto.dataAberturaProposta),
        )

    fun toOpportunity(dto: PncpContratacao): Opportunity? {
        val ref = PncpControlNumber.parse(dto.numeroControlePNCP) ?: return null
        val modality = PncpModalities.modalityOf(dto.modalidadeId) ?: return null

        val published = parseDate(dto.dataPublicacaoPncp) ?: parseDate(dto.dataInclusao) ?: 0L
        // Prazo = somente o fim do recebimento de propostas; abertura/publicação nunca são prazo.
        val deadline = parseDate(dto.dataEncerramentoProposta) ?: Opportunity.DEADLINE_UNKNOWN

        val objeto = dto.objetoCompra?.trim().orEmpty()
        val unidade = dto.unidadeOrgao
        val agencyName = dto.orgaoEntidade?.razaoSocial?.trim().orEmpty().ifEmpty { "Órgão não informado" }
        val unitName = unidade?.nomeUnidade?.trim().orEmpty()
        val agency = if (unitName.isNotEmpty() && !unitName.contains("única", ignoreCase = true) && !agencyName.contains(unitName, true)) {
            "$agencyName — $unitName"
        } else {
            agencyName
        }

        val keywords = buildList {
            dto.modalidadeNome?.let(::add)
            dto.tipoInstrumentoConvocatorioNome?.let(::add)
            if (dto.srp == true) add("registro de preços")
            dto.amparoLegal?.nome?.let(::add)
            dto.processo?.takeIf { it.isNotBlank() }?.let { add("processo $it") }
            dto.informacaoComplementar?.takeIf { it.isNotBlank() }?.let { add(it.take(200)) }
            add(ref.raw)
        }.distinct()

        val platform = PncpPlatforms.classify(dto.usuarioNome, dto.linkSistemaOrigem)
        return Opportunity(
            // O id continua "PNCP:<número de controle>" (estável para cache, detalhe e licitações salvas);
            // só o campo portal reflete a plataforma de origem classificada por usuarioNome.
            id = ref.opportunityId,
            portal = platform,
            platformName = PncpPlatforms.displayName(dto.usuarioNome, platform),
            // Código da unidade do órgão: no Compras.gov.br é a UASG (6 dígitos).
            // "Divulgada no PNCP" = normal; "Revogada"/"Anulada"/"Suspensa" viram o selo colorido.
            officialSituation = com.licitaia.domain.model.OfficialSituation.fromText(dto.situacaoCompraNome),
            uasg = com.licitaia.domain.model.UasgCode.normalize(unidade?.codigoUnidade, comprasGov = platform == com.licitaia.domain.model.Portal.COMPRAS_GOV),
            number = dto.numeroCompra?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> dto.anoCompra?.let { "$n/$it" } ?: n } ?: ref.raw,
            agency = agency,
            objectDescription = objeto.ifEmpty { "Objeto não informado pelo órgão" },
            modality = modality,
            segment = inferSegment(objeto),
            uf = unidade?.ufSigla?.trim()?.uppercase().orEmpty(),
            city = unidade?.municipioNome?.trim().orEmpty(),
            estimatedValue = dto.valorTotalEstimado ?: 0.0,
            publishedAt = published,
            proposalDeadline = deadline,
            // O PNCP não publica data da sessão de disputa: usa-se o fim do recebimento de propostas.
            sessionAt = deadline,
            proposalOpening = parseDate(dto.dataAberturaProposta),
            requiresLocalSupport = false,
            keywords = keywords,
            editalUrl = ref.publicPageUrl,
            noDispute = NoDisputeRule.isNoDispute(
                modality = modality,
                modoDisputaId = dto.modoDisputaId?.toInt(),
                modoDisputaNome = dto.modoDisputaNome,
                tipoInstrumentoCodigo = dto.tipoInstrumentoConvocatorioCodigo?.toInt(),
                hasProposalDeadline = deadline > Opportunity.DEADLINE_UNKNOWN,
            ),
        )
    }

    /** Descrição legível de um item da contratação. */
    fun describeItem(item: PncpItem): String = buildString {
        append("Item ").append(item.numeroItem ?: "?")
        append(" — ").append(item.descricao?.trim()?.ifEmpty { "sem descrição" } ?: "sem descrição")
        val qty = item.quantidade
        if (qty != null) {
            append(" (").append(formatQuantity(qty))
            item.unidadeMedida?.takeIf { it.isNotBlank() }?.let { append(' ').append(it.trim()) }
            if (item.orcamentoSigiloso == true) append(", orçamento sigiloso")
            else item.valorUnitarioEstimado?.let { append(", R$ ").append(formatMoney(it)).append(" unit.") }
            append(')')
        }
    }

    /** Item oficial para a proposta; null sem número ou quando o item foi cancelado/deserto/fracassado. */
    fun toOfficialItem(item: PncpItem): com.licitaia.domain.proposal.OfficialTenderItem? {
        val number = item.numeroItem?.takeIf { it > 0 } ?: return null
        val situation = item.situacaoCompraItemNome.orEmpty().lowercase()
        if (listOf("cancelad", "desert", "fracassad", "anulad", "revogad").any { it in situation }) return null
        return com.licitaia.domain.proposal.OfficialTenderItem(
            number = number,
            description = item.descricao?.trim().orEmpty(),
            quantity = item.quantidade?.takeIf { it > 0 } ?: 1.0,
            unit = item.unidadeMedida?.trim().orEmpty().ifEmpty { "un" },
            estimatedUnitPrice = item.valorUnitarioEstimado?.takeIf { it > 0 && item.orcamentoSigiloso != true },
            estimatedTotal = item.valorTotal?.takeIf { it > 0 && item.orcamentoSigiloso != true },
            confidentialBudget = item.orcamentoSigiloso == true,
            materialOrService = item.materialOuServicoNome,
            judgingCriterion = item.criterioJulgamentoNome,
            benefit = item.tipoBeneficioNome,
            complementaryInfo = item.informacaoComplementar.clean(),
            situation = item.situacaoCompraItemNome.clean(),
            category = item.itemCategoriaNome.clean(),
            catalogName = item.catalogo.text("nome", "descricao") ?: item.categoriaItemCatalogo.text("nome", "descricao"),
            catalogCode = item.catalogoCodigoItem.text("codigo", "id"),
            ncmNbsCode = item.ncmNbsCodigo.text("codigo"),
            ncmNbsDescription = item.ncmNbsDescricao.clean(),
            preferenceMargin = com.licitaia.domain.proposal.OfficialItemFields.preferenceMargin(
                item.aplicabilidadeMargemPreferenciaNormal, item.percentualMargemPreferenciaNormal,
                item.aplicabilidadeMargemPreferenciaAdicional, item.percentualMargemPreferenciaAdicional,
                type = item.tipoMargemPreferencia.text("nome", "descricao"),
            ),
            productiveIncentive = item.incentivoProdutivoBasico,
            nationalContentRequired = item.exigenciaConteudoNacional,
            includedAt = item.dataInclusao.clean(),
            updatedAt = item.dataAtualizacao.clean(),
            hasResult = item.temResultado,
        )
    }

    /** Órgão/unidade compradora; null quando a contratação não traz nenhum dos dois. */
    fun toBuyer(compra: PncpContratacao): com.licitaia.domain.proposal.OfficialBuyer? {
        val orgao = compra.orgaoEntidade
        val unidade = compra.unidadeOrgao
        if (orgao == null && unidade == null) return null
        return com.licitaia.domain.proposal.OfficialBuyer(
            agencyName = orgao?.razaoSocial.clean(),
            agencyCnpj = orgao?.cnpj.clean(),
            unitCode = unidade?.codigoUnidade.clean(),
            unitName = unidade?.nomeUnidade.clean(),
            city = unidade?.municipioNome.clean(),
            uf = unidade?.ufSigla.clean(),
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    /** Texto de um campo de tipo incerto: primitivo (string/número) ou objeto com uma das [keys] (ex.: {"nome": "CATMAT"}). */
    private fun kotlinx.serialization.json.JsonElement?.text(vararg keys: String): String? = when (this) {
        null, kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> contentOrNullSafe().clean()
        is kotlinx.serialization.json.JsonObject -> keys.firstNotNullOfOrNull { key ->
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe().clean()
        }
        else -> null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private fun formatQuantity(q: Double): String =
        if (q % 1.0 == 0.0) q.toLong().toString() else String.format(java.util.Locale("pt", "BR"), "%.2f", q)

    private fun formatMoney(v: Double): String = String.format(java.util.Locale("pt", "BR"), "%,.2f", v)

    // ------------------------------------------------------------------ segmento (heurística declarada)

    /** Termos genéricos demais para indicar segmento (aparecem em objetos de qualquer área). */
    private val genericTerms = setOf("aquisicao", "rede", "infraestrutura", "sistema", "gestao", "implantacao", "equipamento", "aplicativo", "servico")

    private val specificVocabulary: Map<Segment, List<String>> = mapOf(
        Segment.TELECOM_ISP to SegmentAffinity.defaultKeywords(Segment.TELECOM_ISP) + listOf(
            "telefonia", "link de internet", "acesso a internet", "provedor", "fibra optica",
            // "radio" sozinho casava radiológico/radioisótopo/radiofármaco (diagnóstico real 06/10/2026).
            "radiocomunicacao", "radio comunicacao", "enlace de radio", "radio digital", "voip", "pabx",
            "comunicacao de dados", "satelite", "lan-to-lan", "mpls", "sip", "telecomunicacoes", "4g", "5g",
        ),
        Segment.SOFTWARE to SegmentAffinity.defaultKeywords(Segment.SOFTWARE) + listOf(
            "licenciamento", "licencas", "sistema de gestao", "erp", "aplicacao", "portal", "nuvem", "saas", "assinatura de software",
        ),
        Segment.EQUIPAMENTOS to SegmentAffinity.defaultKeywords(Segment.EQUIPAMENTOS) + listOf(
            "servidor", "storage", "camera", "cftv", "tablet", "projetor", "nobreak", "access point", "roteador",
            "microcomputador", "desktop", "scanner", "equipamentos de informatica", "material de informatica",
        ),
        Segment.TI to SegmentAffinity.defaultKeywords(Segment.TI) + listOf(
            "tecnologia da informacao", "data center", "seguranca da informacao", "service desk", "help desk",
            "virtualizacao", "monitoramento", "videomonitoramento", "cabeamento estruturado", "rede logica", "wi-fi", "wifi",
        ),
    ).mapValues { (_, words) -> words.filter { TextMatch.normalize(it) !in genericTerms }.distinct() }

    private val servicosVocabulary: List<String> =
        SegmentAffinity.defaultKeywords(Segment.SERVICOS) + listOf("prestacao de servico", "contratacao de empresa", "outsourcing")

    /**
     * Inferência por palavras-chave do objeto. É uma HEURÍSTICA: objetos fora do vocabulário
     * recebem [Segment.PERSONALIZADO] (o score trata como neutro), nunca um segmento inventado.
     */
    fun inferSegment(objeto: String): Segment {
        if (objeto.isBlank()) return Segment.PERSONALIZADO
        val text = " " + TextMatch.normalize(objeto) + " "
        val scores = specificVocabulary.mapValues { (_, words) -> words.count { TextMatch.containsTerm(text, it) } }
        val best = scores.maxByOrNull { it.value }
        if (best != null && best.value > 0) {
            // Desempate determinístico pela ordem do enum Segment (TELECOM_ISP, TI, SOFTWARE, EQUIPAMENTOS).
            return scores.entries.filter { it.value == best.value }.minByOrNull { it.key.ordinal }!!.key
        }
        if (servicosVocabulary.any { TextMatch.containsTerm(text, it) }) return Segment.SERVICOS
        return Segment.PERSONALIZADO
    }
}
