package com.licitaia.domain.proposal

import com.licitaia.domain.model.PriceRange
import com.licitaia.domain.model.ProposalItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Item OFICIAL de uma contratação, como publicado no PNCP (`/compras/{ano}/{seq}/itens`) ou nos dados abertos do
 * Compras.gov.br (`2.1_consultarItensContratacoes_PNCP_14133_Id`). Os valores são os do edital; nada é inventado.
 */
data class OfficialTenderItem(
    /** Número do item no edital (1, 2, ...). */
    val number: Int,
    val description: String,
    val quantity: Double,
    val unit: String,
    /** Valor unitário estimado pelo órgão; null quando não publicado. */
    val estimatedUnitPrice: Double?,
    val estimatedTotal: Double? = null,
    /** Orçamento sigiloso: o órgão não divulga o valor estimado. */
    val confidentialBudget: Boolean = false,
    /** "Material" / "Serviço". */
    val materialOrService: String? = null,
    val judgingCriterion: String? = null,
    /** Benefício ME/EPP ("Participação exclusiva para ME/EPP" etc.). */
    val benefit: String? = null,
    // ---- Detalhe completo do item (tela de detalhe da aba Itens). Todos opcionais: cada fonte publica um subconjunto.
    /** Informação complementar publicada para o item. */
    val complementaryInfo: String? = null,
    /** Situação do item ("Em andamento", "Homologado"...). */
    val situation: String? = null,
    /** Categoria do item ("Bens imóveis", "Informática (TIC)"...). */
    val category: String? = null,
    /** Catálogo de origem do código (CATMAT/CATSER/outro) e código do item no catálogo. */
    val catalogName: String? = null,
    val catalogCode: String? = null,
    /** NCM/NBS: código e descrição. */
    val ncmNbsCode: String? = null,
    val ncmNbsDescription: String? = null,
    /** Margem de preferência (normal/adicional com percentuais), já descrita. */
    val preferenceMargin: String? = null,
    /** Incentivo à inovação/produção nacional (PPB) e exigência de conteúdo nacional. */
    val productiveIncentive: Boolean? = null,
    val nationalContentRequired: Boolean? = null,
    /** Datas de inclusão/atualização na fonte (ISO "yyyy-MM-ddTHH:mm:ss"). */
    val includedAt: String? = null,
    val updatedAt: String? = null,
    /** O item já tem resultado publicado; [supplier] = fornecedor homologado quando a fonte informa. */
    val hasResult: Boolean? = null,
    val supplier: String? = null,
) {
    /** Total estimado do item (publicado ou unitário × quantidade). */
    val referenceTotal: Double?
        get() = estimatedTotal?.takeIf { it > 0 } ?: estimatedUnitPrice?.takeIf { it > 0 }?.let { it * quantity }
}

/**
 * Órgão e unidade compradora da contratação ("Órgão e local" no detalhe do item). [fromOfficialSource] = false quando
 * montado só com os dados do cadastro da licitação (sem consulta à fonte oficial).
 */
data class OfficialBuyer(
    val agencyName: String?,
    val agencyCnpj: String?,
    val unitCode: String?,
    val unitName: String?,
    val city: String?,
    val uf: String?,
    val fromOfficialSource: Boolean = true,
)

/** Montagem de campos descritivos do item a partir dos dados brutos das fontes (puro, compartilhado pelos conectores). */
object OfficialItemFields {
    /**
     * "Normal: 10% · Adicional: 5%"; "Não se aplica" quando a fonte diz explicitamente que não há margem; null quando a
     * fonte não informa nada.
     */
    fun preferenceMargin(normal: Boolean?, normalPct: Double?, additional: Boolean?, additionalPct: Double?, type: String? = null): String? {
        val parts = buildList {
            if (normal == true || (normalPct ?: 0.0) > 0) add("Normal" + (normalPct?.takeIf { it > 0 }?.let { ": ${pct(it)}" } ?: ""))
            if (additional == true || (additionalPct ?: 0.0) > 0) add("Adicional" + (additionalPct?.takeIf { it > 0 }?.let { ": ${pct(it)}" } ?: ""))
        }
        val typeText = type?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            parts.isNotEmpty() -> (typeText?.let { "$it — " } ?: "") + parts.joinToString(" · ")
            typeText != null -> typeText
            normal == false || additional == false -> "Não se aplica"
            else -> null
        }
    }

    private fun pct(value: Double): String =
        (if (value % 1.0 == 0.0) value.toLong().toString() else String.format(java.util.Locale("pt", "BR"), "%.2f", value)) + "%"

    /** "2025-09-12T15:15:04" → "12/09/2025 15:15"; texto fora do formato volta como veio. */
    fun formatDateTime(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2}))?").find(text) ?: return text
        val (y, m, d, hh, mm) = match.destructured
        return "$d/$m/$y" + if (hh.isNotEmpty()) " $hh:$mm" else ""
    }
}

/** Resultado da montagem: itens da proposta + avisos para o usuário. */
data class OfficialProposalDraft(
    val items: List<ProposalItem>,
    /** Números dos itens com orçamento sigiloso (preço a definir pelo usuário). */
    val confidentialItems: List<Int>,
    /** Fator aplicado sobre o estimado (ex.: 0,92 = 8% abaixo). */
    val priceFactor: Double,
    /** true = o fator veio da faixa de preço da análise da IA; false = desconto padrão. */
    val fromAnalysis: Boolean,
) {
    val hasPendingPrices: Boolean get() = confidentialItems.isNotEmpty()
}

/**
 * Monta os itens da proposta a partir dos itens oficiais da licitação. Regras (puras e testadas):
 * - nº do item, descrição (resumida quando enorme), unidade e quantidade vêm do edital;
 * - preço unitário = estimado × fator, onde o fator vem da faixa sugerida pela análise (sugerido ÷ estimado total)
 *   ou, sem análise utilizável, de um desconto padrão ([DEFAULT_DISCOUNT_PCT]);
 * - sempre arredondado a centavos e NUNCA acima do estimado (arredondamento para baixo quando necessário);
 * - orçamento sigiloso/sem estimado: preço 0 marcado "a definir".
 */
object OfficialProposalBuilder {
    /** Desconto padrão sobre o valor estimado quando a análise não dá uma faixa utilizável (0,5%). */
    const val DEFAULT_DISCOUNT_PCT = 0.5

    /** Fatores fora deste intervalo indicam faixa incoerente com os itens (ex.: análise de outro valor): usa o padrão. */
    private const val MIN_ANALYSIS_FACTOR = 0.30

    /** Descrição oficial acima disso é resumida (o texto completo continua no edital). */
    const val MAX_DESCRIPTION_CHARS = 400

    fun build(
        official: List<OfficialTenderItem>,
        priceRange: PriceRange? = null,
        /** Valor estimado total da licitação (fallback do denominador quando os itens não trazem valores). */
        tenderEstimatedValue: Double = 0.0,
        discountPct: Double = DEFAULT_DISCOUNT_PCT,
    ): OfficialProposalDraft {
        val sorted = official.sortedBy { it.number }
        val knownTotal = sorted.filterNot { it.confidentialBudget }.sumOf { it.referenceTotal ?: 0.0 }
        val denominator = knownTotal.takeIf { it > 0 } ?: tenderEstimatedValue.takeIf { it > 0 }
        val analysisFactor = priceRange?.suggested?.takeIf { it > 0 && denominator != null }?.let { it / denominator!! }
            ?.takeIf { it.isFinite() && it in MIN_ANALYSIS_FACTOR..1.0 }
        val discount = discountPct.coerceIn(0.0, 99.0)
        val factor = analysisFactor ?: (1.0 - discount / 100.0)

        val confidential = mutableListOf<Int>()
        val items = sorted.map { item ->
            val estimated = item.estimatedUnitPrice?.takeIf { it > 0 && it.isFinite() && !item.confidentialBudget }
            val price = if (estimated == null) {
                confidential += item.number
                0.0
            } else {
                unitPriceFor(estimated, factor)
            }
            ProposalItem(
                description = summarize(item.description).ifBlank { "Item ${item.number}" },
                unit = item.unit.trim().ifBlank { "un" },
                quantity = item.quantity.takeIf { it > 0 && it.isFinite() } ?: 1.0,
                unitPrice = price,
                itemNumber = item.number,
                estimatedUnitPrice = estimated,
                confidentialBudget = estimated == null,
            )
        }
        return OfficialProposalDraft(items, confidential, factor, analysisFactor != null)
    }

    /** Preço unitário: estimado × fator, em centavos, nunca acima do estimado (nem abaixo de R$ 0,01). */
    fun unitPriceFor(estimated: Double, factor: Double): Double {
        val ceiling = BigDecimal.valueOf(estimated).setScale(2, RoundingMode.FLOOR)
        if (ceiling.signum() <= 0) return 0.0
        val f = factor.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(1.0) ?: 1.0
        val proposed = BigDecimal.valueOf(estimated).multiply(BigDecimal.valueOf(f)).setScale(2, RoundingMode.HALF_UP)
        val capped = proposed.min(ceiling).max(BigDecimal("0.01"))
        return capped.toDouble()
    }

    /**
     * Resume descrições enormes mantendo o essencial: o começo (onde o edital nomeia o objeto), cortado no fim de
     * frase ou palavra mais próximo de [max], com reticências. Espaços repetidos são normalizados.
     */
    fun summarize(text: String, max: Int = MAX_DESCRIPTION_CHARS): String {
        val clean = text.replace(Regex("""[ \t\r\f]+"""), " ").replace(Regex("""\n{2,}"""), "\n").trim()
        if (clean.length <= max) return clean
        val window = clean.substring(0, max)
        val sentenceEnd = Regex("""[.;:](\s|$)""").findAll(window).lastOrNull()?.range?.first
        val cut = when {
            sentenceEnd != null && sentenceEnd >= max * 0.6 -> sentenceEnd + 1
            else -> window.lastIndexOf(' ').takeIf { it >= max * 0.6 } ?: max
        }
        val head = clean.substring(0, cut).trimEnd(' ', ',', ';', ':', '-')
        return if (head.endsWith('.')) "$head (…)" else "$head…"
    }
}
