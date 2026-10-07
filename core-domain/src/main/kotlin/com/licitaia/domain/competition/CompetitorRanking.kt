package com.licitaia.domain.competition

/** Um concorrente no ranking: quantas vezes venceu itens, quanto foi homologado e com que desconto médio. */
data class CompetitorStat(
    val name: String,
    /** CNPJ/CPF só com dígitos (vazio quando não publicado). */
    val document: String,
    val wins: Int,
    /** Contratações distintas vencidas (um pregão pode ter vários itens). */
    val contracts: Int,
    val totalHomologated: Double,
    /** Desconto médio sobre o estimado (%) nos itens em que dá para calcular; null = sem base. */
    val avgDiscountPct: Double?,
    val lastWinAt: Long,
    /** É a própria empresa (mesmo CNPJ). */
    val isUs: Boolean,
)

/** Resumo do mercado/segmento a partir dos resultados públicos. */
data class MarketSummary(
    val results: Int,
    val contracts: Int,
    val avgHomologated: Double,
    val avgDiscountPct: Double?,
    /** Mais vitórias primeiro (desempate: maior valor homologado). */
    val ranking: List<CompetitorStat>,
) {
    companion object {
        val EMPTY = MarketSummary(0, 0, 0.0, null, emptyList())
    }
}

/** Agregação pura (testável) do ranking de concorrentes. */
object CompetitorRanking {

    fun digits(text: String?): String = text.orEmpty().filter { it.isDigit() }

    /**
     * Agrupa por documento do fornecedor (ou nome normalizado quando o documento não foi publicado), remove duplicatas
     * (mesma contratação + item + fornecedor) e ordena por vitórias. [ourDocument] marca a própria empresa.
     */
    fun summarize(results: List<PublicAwardResult>, ourDocument: String?, limit: Int = 20): MarketSummary {
        val unique = results.filter { it.homologatedValue > 0.0 && it.supplierName.isNotBlank() }.distinctBy { it.key }
        if (unique.isEmpty()) return MarketSummary.EMPTY
        val us = digits(ourDocument).takeIf { it.length >= 11 }
        val ranking = unique.groupBy { r -> digits(r.supplierDocument).ifEmpty { "nome:" + r.supplierName.trim().lowercase() } }
            .map { (key, list) ->
                val discounts = list.mapNotNull { it.discountPct }
                val doc = if (key.startsWith("nome:")) "" else key
                CompetitorStat(
                    // Nome mais recente publicado para o mesmo documento.
                    name = list.maxBy { it.resultDate }.supplierName.trim(),
                    document = doc,
                    wins = list.size,
                    contracts = list.map { it.controlNumber }.distinct().size,
                    totalHomologated = list.sumOf { it.homologatedValue },
                    avgDiscountPct = discounts.takeIf { it.isNotEmpty() }?.average(),
                    lastWinAt = list.maxOf { it.resultDate },
                    isUs = us != null && doc == us,
                )
            }
            .sortedWith(compareByDescending<CompetitorStat> { it.wins }.thenByDescending { it.totalHomologated }.thenBy { it.name })
        val discounts = unique.mapNotNull { it.discountPct }
        return MarketSummary(
            results = unique.size,
            contracts = unique.map { it.controlNumber }.distinct().size,
            avgHomologated = unique.map { it.homologatedValue }.average(),
            avgDiscountPct = discounts.takeIf { it.isNotEmpty() }?.average(),
            ranking = ranking.take(limit),
        )
    }

    /** "12.345.678/0001-90" para CNPJ (14 dígitos); CPF é mascarado (dado pessoal); outros, como vieram. */
    fun formatDocument(document: String): String {
        val d = digits(document)
        return when (d.length) {
            14 -> "${d.substring(0, 2)}.${d.substring(2, 5)}.${d.substring(5, 8)}/${d.substring(8, 12)}-${d.substring(12)}"
            11 -> "CPF ***.${d.substring(3, 6)}.***-**"
            else -> d
        }
    }
}
