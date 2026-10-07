package com.licitaia.domain.proposal

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Valor em reais POR EXTENSO (pt-BR), como exigido nas propostas comerciais:
 * 1.234,56 → "mil duzentos e trinta e quatro reais e cinquenta e seis centavos";
 * 1.000.000 → "um milhão de reais"; 0,01 → "um centavo".
 */
object MoneyInWords {
    private val UNITS = arrayOf(
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
        "dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
    )
    private val TENS = arrayOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val HUNDREDS = arrayOf("", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos")
    private val SCALES_SINGULAR = arrayOf("", "mil", "milhão", "bilhão", "trilhão")
    private val SCALES_PLURAL = arrayOf("", "mil", "milhões", "bilhões", "trilhões")

    fun brl(value: Double): String {
        require(value.isFinite()) { "Valor inválido" }
        val cents = BigDecimal.valueOf(kotlin.math.abs(value)).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
        val reais = cents / 100
        val centavos = (cents % 100).toInt()
        val negative = value < 0 && cents > 0
        val text = when {
            reais == 0L && centavos == 0 -> "zero real"
            reais == 0L -> centsText(centavos)
            else -> {
                val currency = when {
                    reais == 1L -> "real"
                    reais >= 1_000_000 && reais % 1_000_000 == 0L -> "de reais"
                    else -> "reais"
                }
                val base = "${integer(reais)} $currency"
                if (centavos == 0) base else "$base e ${centsText(centavos)}"
            }
        }
        return if (negative) "menos $text" else text
    }

    private fun centsText(c: Int): String = "${below1000(c)} ${if (c == 1) "centavo" else "centavos"}"

    /** Inteiro por extenso (0 a 999 trilhões). */
    fun integer(n: Long): String {
        require(n >= 0) { "Somente valores positivos" }
        if (n == 0L) return UNITS[0]
        val groups = mutableListOf<Int>()
        var rest = n
        while (rest > 0) { groups += (rest % 1000).toInt(); rest /= 1000 }
        require(groups.size <= SCALES_SINGULAR.size) { "Valor grande demais" }
        // Partes não nulas da maior para a menor escala: (escala, grupo).
        val parts = groups.withIndex().filter { it.value != 0 }.map { it.index to it.value }.reversed()
        val sb = StringBuilder()
        parts.forEachIndexed { i, (scale, g) ->
            val words = when {
                scale == 0 -> below1000(g)
                scale == 1 -> if (g == 1) "mil" else "${below1000(g)} mil"
                else -> "${below1000(g)} ${if (g == 1) SCALES_SINGULAR[scale] else SCALES_PLURAL[scale]}"
            }
            if (i > 0) {
                val last = i == parts.lastIndex
                sb.append(
                    when {
                        // "mil e duzentos", "um milhão e quinhentos mil", "dois mil e cinquenta"
                        last && (g < 100 || g % 100 == 0) -> " e "
                        // "mil duzentos e trinta"
                        parts[i - 1].first == 1 -> " "
                        else -> ", "
                    },
                )
            }
            sb.append(words)
        }
        return sb.toString()
    }

    private fun below1000(n: Int): String {
        require(n in 0..999)
        if (n == 100) return "cem"
        if (n < 20) return UNITS[n]
        val h = n / 100
        val rest = n % 100
        val parts = mutableListOf<String>()
        if (h > 0) parts += HUNDREDS[h]
        if (rest > 0) {
            if (rest < 20) parts += UNITS[rest]
            else {
                val t = rest / 10
                val u = rest % 10
                parts += if (u == 0) TENS[t] else "${TENS[t]} e ${UNITS[u]}"
            }
        }
        return parts.joinToString(" e ")
    }
}
