package com.licitaia.domain.competition

import com.licitaia.domain.model.PncpControlNumbers
import com.licitaia.domain.model.Segment
import com.licitaia.domain.scoring.SegmentAffinity
import com.licitaia.domain.scoring.TextMatch

/**
 * Concorrentes de UMA licitação a partir da base pública (resultados homologados do PNCP guardados na Concorrência):
 * - [agency]: vencedores em contratações do MESMO órgão (CNPJ do número de controle PNCP) com objeto semelhante;
 * - [segment]: vencedores no segmento da empresa (demais contratações semelhantes da base de mercado).
 * [publicCount] = concorrentes distintos (sem a própria empresa) do órgão, ou do segmento quando o órgão não tem dados;
 * null = sem dados públicos (a tela mostra o número estimado pela análise, rotulado como estimativa).
 */
data class TenderCompetitorsView(
    val agency: MarketSummary,
    val segment: MarketSummary,
    val agencyCnpj: String?,
) {
    val agencyCompetitors: List<CompetitorStat> get() = agency.ranking.filterNot { it.isUs }
    val segmentCompetitors: List<CompetitorStat> get() = segment.ranking.filterNot { it.isUs }

    val publicCount: Int?
        get() = when {
            agencyCompetitors.isNotEmpty() -> agencyCompetitors.size
            segmentCompetitors.isNotEmpty() -> segmentCompetitors.size
            else -> null
        }

    /** De onde veio [publicCount]. */
    val countFromAgency: Boolean get() = agencyCompetitors.isNotEmpty()

    val hasData: Boolean get() = publicCount != null
}

object TenderCompetitors {

    /** CNPJ do órgão (14 dígitos) a partir do id da oportunidade com número de controle PNCP; null sem ele. */
    fun agencyCnpj(opportunityId: String): String? = PncpControlNumbers.fromOpportunityId(opportunityId)?.take(14)

    private val STOPWORDS = setOf(
        "contratacao", "empresa", "especializada", "prestacao", "servico", "servicos", "aquisicao", "fornecimento",
        "registro", "precos", "preco", "eventual", "futura", "objeto", "para", "atender", "demanda", "demandas",
        "secretaria", "municipal", "municipio", "estadual", "federal", "conforme", "termo", "referencia", "edital",
        "anexo", "quantidades", "especificacoes", "condicoes", "estabelecidas", "visando", "atraves", "pelo", "pela",
        "com", "dos", "das", "nos", "nas", "uma", "um", "sob", "demanda", "material", "materiais", "diversos", "diversas",
        "prefeitura", "orgao", "unidade", "itens", "item", "lote", "lotes", "periodo", "meses", "valor", "global",
    )

    /** Palavras significativas do objeto (≥ 5 letras, sem termos genéricos de edital), na ordem em que aparecem. */
    fun objectKeywords(objectDescription: String, max: Int = 6): List<String> =
        TextMatch.normalize(objectDescription)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 5 && it !in STOPWORDS && !it.all(Char::isDigit) }
            .distinct()
            .take(max)

    /** Palavras do segmento (vocabulário padrão) usadas para filtrar a base de mercado. */
    fun segmentKeywords(segment: Segment): List<String> = SegmentAffinity.defaultKeywords(segment)

    private fun similar(r: PublicAwardResult, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        val text = " " + TextMatch.normalize(r.objectSummary + " " + r.itemDescription) + " "
        return keywords.any { TextMatch.containsTerm(text, it) }
    }

    /**
     * Monta as duas listas. Resultados do mesmo órgão (prefixo do número de controle = [agencyCnpj]) entram em
     * "órgão" quando o objeto é semelhante ([objectKeywords] ou, sem casamento, [segmentKeywords]); os demais entram em
     * "segmento" quando casam as palavras do objeto ou do segmento.
     */
    fun build(
        results: List<PublicAwardResult>,
        agencyCnpj: String?,
        objectKeywords: List<String>,
        segmentKeywords: List<String>,
        ourDocument: String?,
        limit: Int = 30,
    ): TenderCompetitorsView {
        val cnpj = CompetitorRanking.digits(agencyCnpj).takeIf { it.length == 14 }
        val similarWords = (objectKeywords + segmentKeywords).distinct()
        val (fromAgency, others) = results.partition { cnpj != null && it.controlNumber.startsWith(cnpj) }
        val agency = fromAgency.filter { similar(it, similarWords) }
        val segment = others.filter { similar(it, similarWords) && (similarWords.isNotEmpty()) }
        return TenderCompetitorsView(
            agency = CompetitorRanking.summarize(agency, ourDocument, limit),
            segment = CompetitorRanking.summarize(segment, ourDocument, limit),
            agencyCnpj = cnpj,
        )
    }

    /** Valor médio homologado por vitória do concorrente. */
    fun averageHomologated(stat: CompetitorStat): Double = if (stat.wins > 0) stat.totalHomologated / stat.wins else 0.0
}
