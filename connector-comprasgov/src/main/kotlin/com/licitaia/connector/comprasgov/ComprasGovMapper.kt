package com.licitaia.connector.comprasgov

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
 * Referência de uma contratação da Lei 14.133 pelo número de controle PNCP
 * (`<cnpj 14 dígitos>-1-<sequencial 6 dígitos>/<ano>`, ex.: "05055128000176-1-000108/2026").
 *
 * O id da oportunidade é `COMPRAS_GOV:<número de controle>`: a mesma contratação, vinda do conector
 * PNCP, tem id `PNCP:<número de controle>` — esse sufixo comum é o que permite a deduplicação.
 */
data class ComprasGovPncpRef(val cnpj: String, val ano: Int, val sequencial: Int) {
    val raw: String get() = "$cnpj-1-${sequencial.toString().padStart(6, '0')}/$ano"
    val opportunityId: String get() = "${Portal.COMPRAS_GOV.name}:$raw"
    /**
     * Página pública da contratação. O payload do Compras.gov.br não traz URL oficial da compra,
     * então usa-se a página pública do PNCP do mesmo número de controle (documentada e estável).
     */
    val publicPageUrl: String get() = "https://pncp.gov.br/app/editais/$cnpj/$ano/$sequencial"

    companion object {
        private val PATTERN = Regex("""^(\d{14})-1-(\d{1,6})/(\d{4})$""")

        fun parse(raw: String?): ComprasGovPncpRef? {
            val m = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            return ComprasGovPncpRef(m.groupValues[1], m.groupValues[3].toInt(), m.groupValues[2].toInt())
        }

        fun fromOpportunityId(id: String): ComprasGovPncpRef? = parse(id.removePrefix("${Portal.COMPRAS_GOV.name}:"))
    }
}

/**
 * Referência de uma licitação do módulo legado (SIASG/Comprasnet): `<uasg 6>-<modalidade 2>-<número 5>/<ano>`.
 * Corresponde ao `id_compra` da API (`UASG(6)+modalidade(2)+número(5)+ano(4)`, ex.: "15200505000272023"),
 * observado nas respostas reais e usado pelos endpoints `*_Id?id_compra=`.
 */
data class ComprasGovLegacyRef(val uasg: Int, val modalidade: Int, val numero: Int, val ano: Int) {
    val idCompra: String
        get() = uasg.toString().padStart(6, '0') + modalidade.toString().padStart(2, '0') +
            numero.toString().padStart(5, '0') + ano.toString().padStart(4, '0')
    val raw: String
        get() = "${uasg.toString().padStart(6, '0')}-${modalidade.toString().padStart(2, '0')}-" +
            "${numero.toString().padStart(5, '0')}/$ano"
    val opportunityId: String get() = "${Portal.COMPRAS_GOV.name}:$raw"

    companion object {
        private val ID_COMPRA = Regex("""^(\d{6})(\d{2})(\d{5})(\d{4})$""")
        private val RAW = Regex("""^(\d{6})-(\d{2})-(\d{5})/(\d{4})$""")

        fun fromIdCompra(idCompra: String?): ComprasGovLegacyRef? {
            val m = ID_COMPRA.matchEntire(idCompra?.trim().orEmpty()) ?: return null
            return ComprasGovLegacyRef(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
        }

        /** `numero_aviso` real vem como número+ano concatenados (ex.: 762023 = 76/2023). */
        fun fromFields(uasg: Int?, modalidade: Int?, numeroAviso: Int?): ComprasGovLegacyRef? {
            if (uasg == null || modalidade == null || numeroAviso == null) return null
            val text = numeroAviso.toString()
            if (text.length < 5) return null
            val ano = text.takeLast(4).toIntOrNull() ?: return null
            val numero = text.dropLast(4).toIntOrNull() ?: return null
            return ComprasGovLegacyRef(uasg, modalidade, numero, ano)
        }

        fun parse(raw: String?): ComprasGovLegacyRef? {
            val m = RAW.matchEntire(raw?.trim().orEmpty()) ?: return null
            return ComprasGovLegacyRef(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
        }

        fun fromOpportunityId(id: String): ComprasGovLegacyRef? = parse(id.removePrefix("${Portal.COMPRAS_GOV.name}:"))
    }
}

/**
 * Códigos de modalidade do Compras.gov.br (parâmetro `codigoModalidade`), que NÃO coincidem com os do PNCP.
 * Confirmados por respostas reais de `1_consultarContratacoes_PNCP_14133` em 06/10/2026
 * (codigoModalidade → modalidadeIdPncp/modalidadeNome): 3 → 4 "Concorrência - Eletrônica",
 * 5 → 6 "Pregão - Eletrônico", 6 → 8 "Dispensa", 7 → 9 "Inexigibilidade".
 * Códigos 1, 2, 4, 8..14 devolveram 0 registros no período testado; Credenciamento não tem código observado.
 *
 * Módulo legado (`modalidade` de `1_consultarLicitacao`, Lei 8.666), também observado:
 * 1 CONVITE, 2 TOMADA DE PREÇOS, 3 CONCORRÊNCIA, 5 PREGÃO, 20 CONCURSO.
 */
object ComprasGovModalities {
    const val CONCORRENCIA_ELETRONICA = 3
    const val PREGAO_ELETRONICO = 5
    const val DISPENSA = 6
    const val INEXIGIBILIDADE = 7

    const val LEGADO_CONCORRENCIA = 3
    const val LEGADO_PREGAO = 5

    /** Código 14.133 para a modalidade do app; null = sem código conhecido (Credenciamento). */
    fun codeOf(modality: Modality): Int? = when (modality) {
        Modality.PREGAO_ELETRONICO -> PREGAO_ELETRONICO
        Modality.DISPENSA_ELETRONICA -> DISPENSA
        Modality.CONCORRENCIA -> CONCORRENCIA_ELETRONICA
        Modality.CREDENCIAMENTO -> null
    }

    /** Códigos consultados quando o filtro não fixa modalidade: Pregão Eletrônico + Dispensa + Concorrência. */
    val SEARCHED_CODES: List<Int> = listOf(PREGAO_ELETRONICO, DISPENSA, CONCORRENCIA_ELETRONICA)

    fun modalityOf(codigoModalidade: Int?): Modality? = when (codigoModalidade) {
        PREGAO_ELETRONICO -> Modality.PREGAO_ELETRONICO
        DISPENSA -> Modality.DISPENSA_ELETRONICA
        CONCORRENCIA_ELETRONICA -> Modality.CONCORRENCIA
        else -> null
    }

    /** Fallback pelo código do PNCP presente no mesmo payload (4, 6, 8, 12 — ver conector PNCP). */
    fun modalityOfPncp(modalidadeIdPncp: Int?): Modality? = when (modalidadeIdPncp) {
        6 -> Modality.PREGAO_ELETRONICO
        8 -> Modality.DISPENSA_ELETRONICA
        4, 5 -> Modality.CONCORRENCIA
        12 -> Modality.CREDENCIAMENTO
        else -> null
    }

    /** Código legado (Lei 8.666) compatível com a modalidade do app; null = legado não se aplica. */
    fun legacyCodeOf(modality: Modality): Int? = when (modality) {
        Modality.PREGAO_ELETRONICO -> LEGADO_PREGAO
        Modality.CONCORRENCIA -> LEGADO_CONCORRENCIA
        Modality.DISPENSA_ELETRONICA, Modality.CREDENCIAMENTO -> null
    }

    fun legacyModalityOf(modalidade: Int?, tipoPregao: String?): Modality? = when (modalidade) {
        LEGADO_PREGAO -> if (tipoPregao == null || tipoPregao.contains("eletr", ignoreCase = true)) Modality.PREGAO_ELETRONICO else null
        LEGADO_CONCORRENCIA -> Modality.CONCORRENCIA
        else -> null
    }
}

/** Conversão DTO → domínio. Lógica pura e testável. */
internal object ComprasGovMapper {

    /** Datas chegam sem fuso ("2026-09-01T07:26:01" ou "2023-11-01"); são horários de Brasília. */
    val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")
    val QUERY_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

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

    // ------------------------------------------------------------------ Lei 14.133

    /**
     * Devolve null quando a contratação não pode ser representada (excluída, sem identificador ou
     * modalidade fora das suportadas). Nada é inventado: campos ausentes ficam vazios/0.
     */
    fun toOpportunity(dto: ComprasGovContratacao): Opportunity? {
        if (dto.contratacaoExcluida == true) return null
        val modality = ComprasGovModalities.modalityOf(dto.codigoModalidade)
            ?: ComprasGovModalities.modalityOfPncp(dto.modalidadeIdPncp)
            ?: return null
        val pncpRef = ComprasGovPncpRef.parse(dto.numeroControlePNCP)
        val legacyRef = if (pncpRef == null) ComprasGovLegacyRef.fromIdCompra(dto.idCompra) else null
        val id = pncpRef?.opportunityId ?: legacyRef?.opportunityId ?: return null

        val published = parseDate(dto.dataPublicacaoPncp) ?: parseDate(dto.dataInclusaoPncp) ?: 0L
        // Prazo = SOMENTE o fim do recebimento de propostas. A abertura (início do recebimento) e a publicação
        // não são prazo: antes caíam aqui quando o encerramento vinha nulo e geravam "Propostas até <data passada>".
        // Sem encerramento → DEADLINE_UNKNOWN ("prazo não informado"); o repositório tenta a data do PNCP.
        val deadline = parseDate(dto.dataEncerramentoPropostaPncp) ?: Opportunity.DEADLINE_UNKNOWN

        val objeto = dto.objetoCompra?.trim().orEmpty()
        val agencyName = dto.orgaoEntidadeRazaoSocial?.trim().orEmpty().ifEmpty { "Órgão não informado" }
        val unitName = dto.unidadeOrgaoNomeUnidade?.trim().orEmpty()
        val agency = if (unitName.isNotEmpty() && !unitName.contains("única", ignoreCase = true) && !agencyName.contains(unitName, true)) {
            "$agencyName — $unitName"
        } else {
            agencyName
        }

        val keywords = buildList {
            dto.modalidadeNome?.let(::add)
            dto.tipoInstrumentoConvocatorioNome?.let(::add)
            dto.modoDisputaNomePncp?.let(::add)
            if (dto.srp == true) add("registro de preços")
            dto.amparoLegalNome?.let(::add)
            dto.processo?.takeIf { it.isNotBlank() }?.let { add("processo $it") }
            dto.unidadeOrgaoCodigoUnidade?.takeIf { it.isNotBlank() }?.let { add("UASG $it") }
            dto.informacaoComplementar?.takeIf { it.isNotBlank() }?.let { add(it.take(200)) }
            pncpRef?.let { add(it.raw) }
        }.distinct()

        return Opportunity(
            id = id,
            portal = Portal.COMPRAS_GOV,
            number = dto.numeroCompra?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> dto.anoCompraPncp?.let { "$n/$it" } ?: n }
                ?: pncpRef?.raw ?: legacyRef!!.raw,
            agency = agency,
            objectDescription = objeto.ifEmpty { "Objeto não informado pelo órgão" },
            modality = modality,
            segment = inferSegment(objeto),
            uf = dto.unidadeOrgaoUfSigla?.trim()?.uppercase().orEmpty(),
            city = dto.unidadeOrgaoMunicipioNome?.trim().orEmpty(),
            estimatedValue = dto.valorTotalEstimado ?: 0.0,
            publishedAt = published,
            proposalDeadline = deadline,
            // A API não publica data da sessão de disputa: usa-se o fim do recebimento de propostas.
            sessionAt = deadline,
            proposalOpening = parseDate(dto.dataAberturaPropostaPncp),
            requiresLocalSupport = false,
            keywords = keywords,
            editalUrl = pncpRef?.publicPageUrl ?: Portal.COMPRAS_GOV.publicUrl,
            noDispute = NoDisputeRule.isNoDispute(
                modality = modality,
                modoDisputaId = dto.modoDisputaIdPncp,
                modoDisputaNome = dto.modoDisputaNomePncp,
                tipoInstrumentoCodigo = dto.tipoInstrumentoConvocatorioCodigoPncp,
                hasProposalDeadline = deadline > Opportunity.DEADLINE_UNKNOWN,
            ),
        )
    }

    fun describeItem(item: ComprasGovItem): String = buildString {
        append("Item ").append(item.numeroItemCompra ?: item.numeroItemPncp ?: "?")
        val desc = item.descricaoResumida?.trim()?.takeIf { it.isNotEmpty() } ?: item.descricaodetalhada?.trim()?.takeIf { it.isNotEmpty() }
        append(" — ").append(desc ?: "sem descrição")
        val qty = item.quantidade
        if (qty != null) {
            append(" (").append(formatQuantity(qty))
            item.unidadeMedida?.trim()?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
            if (item.orcamentoSigiloso == true) append(", orçamento sigiloso")
            else item.valorUnitarioEstimado?.let { append(", R$ ").append(formatMoney(it)).append(" unit.") }
            append(')')
        }
    }

    /** Item oficial para a proposta (fallback do PNCP); null sem número ou quando o item foi cancelado/deserto/fracassado. */
    fun toOfficialItem(item: ComprasGovItem): com.licitaia.domain.proposal.OfficialTenderItem? {
        val number = (item.numeroItemPncp ?: item.numeroItemCompra)?.takeIf { it > 0 } ?: return null
        val situation = item.situacaoCompraItemNome.orEmpty().lowercase()
        if (listOf("cancelad", "desert", "fracassad", "anulad", "revogad").any { it in situation }) return null
        val sigiloso = item.orcamentoSigiloso == true
        return com.licitaia.domain.proposal.OfficialTenderItem(
            number = number,
            description = item.descricaodetalhada?.trim()?.takeIf { it.isNotEmpty() } ?: item.descricaoResumida?.trim().orEmpty(),
            quantity = item.quantidade?.takeIf { it > 0 } ?: 1.0,
            unit = item.unidadeMedida?.trim().orEmpty().ifEmpty { "un" },
            estimatedUnitPrice = item.valorUnitarioEstimado?.takeIf { it > 0 && !sigiloso },
            estimatedTotal = item.valorTotal?.takeIf { it > 0 && !sigiloso },
            confidentialBudget = sigiloso,
            materialOrService = item.materialOuServicoNome,
            judgingCriterion = item.criterioJulgamentoNome,
            benefit = item.tipoBeneficioNome?.trim()?.takeIf { it.isNotEmpty() },
            situation = item.situacaoCompraItemNome?.trim()?.takeIf { it.isNotEmpty() },
            category = item.itemCategoriaNome?.trim()?.takeIf { it.isNotEmpty() },
            catalogName = item.nomePdm?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { pdm -> "PDM" + (item.codigoPdm?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") + " — $pdm" },
            catalogCode = item.codItemCatalogo?.trim()?.takeIf { it.isNotEmpty() },
            ncmNbsCode = item.codigoNCM?.trim()?.takeIf { it.isNotEmpty() },
            ncmNbsDescription = item.descricaoNCM?.trim()?.takeIf { it.isNotEmpty() },
            preferenceMargin = com.licitaia.domain.proposal.OfficialItemFields.preferenceMargin(
                item.margemPreferenciaNormal, item.percentualMargemPreferenciaNormal,
                item.margemPreferenciaAdicional, item.percentualMargemPreferenciaAdicional,
            ),
            productiveIncentive = item.incentivoProdutivoBasico,
            includedAt = item.dataInclusaoPncp?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = item.dataAtualizacaoPncp?.trim()?.takeIf { it.isNotEmpty() },
            hasResult = item.temResultado,
            supplier = item.nomeFornecedor?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ------------------------------------------------------------------ legado (Lei 8.666)

    /**
     * Licitações do módulo legado. Registros com `pertence14133 = true` são descartados: são as mesmas
     * contratações do módulo 14.133 (sem número de controle PNCP, logo sem como deduplicar).
     * A API legada não informa UF, município nem nome do órgão (só o código da UASG).
     */
    fun toOpportunity(dto: ComprasGovLicitacaoLegado): Opportunity? {
        if (dto.pertence14133 == true) return null
        val modality = ComprasGovModalities.legacyModalityOf(dto.modalidade, dto.tipo_pregao) ?: return null
        val ref = ComprasGovLegacyRef.fromIdCompra(dto.id_compra)
            ?: ComprasGovLegacyRef.fromFields(dto.uasg, dto.modalidade, dto.numero_aviso)
            ?: return null

        val published = parseDate(dto.data_publicacao) ?: parseDate(dto.data_entrega_edital) ?: 0L
        // Lei 8.666: a "abertura das propostas" é a sessão, limite para entregar a proposta. Nunca a publicação.
        val opening = parseDate(dto.data_abertura_proposta)
        val deadline = opening ?: parseDate(dto.data_entrega_proposta) ?: Opportunity.DEADLINE_UNKNOWN
        val objeto = dto.objeto?.trim().orEmpty()

        val keywords = buildList {
            dto.nome_modalidade?.let(::add)
            dto.tipo_pregao?.let { add("pregão $it") }
            dto.situacao_aviso?.let(::add)
            dto.numero_processo?.takeIf { it.isNotBlank() }?.let { add("processo $it") }
            add("UASG ${ref.uasg}")
            add("Lei 8.666 (módulo legado)")
            dto.informacoes_gerais?.takeIf { it.isNotBlank() }?.let { add(it.take(200)) }
        }.distinct()

        return Opportunity(
            id = ref.opportunityId,
            portal = Portal.COMPRAS_GOV,
            number = "${ref.numero}/${ref.ano}",
            agency = "UASG ${ref.uasg.toString().padStart(6, '0')}",
            objectDescription = objeto.ifEmpty { "Objeto não informado pelo órgão" },
            modality = modality,
            segment = inferSegment(objeto),
            uf = "",
            city = "",
            estimatedValue = dto.valor_estimado_total ?: 0.0,
            publishedAt = published,
            proposalDeadline = deadline,
            sessionAt = opening ?: deadline,
            requiresLocalSupport = false,
            keywords = keywords,
            // O payload legado não traz URL da compra: aponta-se para a home oficial.
            editalUrl = Portal.COMPRAS_GOV.publicUrl,
        )
    }

    fun describeItem(item: ComprasGovItemLegado): String = buildString {
        append("Item ").append(item.numero_item_licitacao ?: "?")
        val desc = item.descricao_item?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.nome_servico?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.nome_material?.trim()?.takeIf { it.isNotEmpty() }
        append(" — ").append(desc ?: "sem descrição")
        val qty = item.quantidade
        if (qty != null) {
            append(" (").append(formatQuantity(qty))
            item.unidade?.trim()?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
            item.valor_estimado?.let { append(", R$ ").append(formatMoney(it)).append(" estimado") }
            append(')')
        }
    }

    private fun formatQuantity(q: Double): String =
        if (q % 1.0 == 0.0) q.toLong().toString() else String.format(java.util.Locale("pt", "BR"), "%.2f", q)

    private fun formatMoney(v: Double): String = String.format(java.util.Locale("pt", "BR"), "%,.2f", v)

    // ------------------------------------------------------------------ segmento (heurística declarada; mesma do PNCP)

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

    /** HEURÍSTICA por palavras do objeto; fora do vocabulário → [Segment.PERSONALIZADO] (neutro no score). */
    fun inferSegment(objeto: String): Segment {
        if (objeto.isBlank()) return Segment.PERSONALIZADO
        val text = " " + TextMatch.normalize(objeto) + " "
        val scores = specificVocabulary.mapValues { (_, words) -> words.count { TextMatch.containsTerm(text, it) } }
        val best = scores.maxByOrNull { it.value }
        if (best != null && best.value > 0) {
            return scores.entries.filter { it.value == best.value }.minByOrNull { it.key.ordinal }!!.key
        }
        if (servicosVocabulary.any { TextMatch.containsTerm(text, it) }) return Segment.SERVICOS
        return Segment.PERSONALIZADO
    }
}
