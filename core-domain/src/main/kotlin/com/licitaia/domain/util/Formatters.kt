package com.licitaia.domain.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatação pt-BR compartilhada por todas as telas. */
object Formatters {
    private val ptBr = Locale("pt", "BR")

    fun brl(value: Double?): String =
        if (value == null) "—" else NumberFormat.getCurrencyInstance(ptBr).format(value)

    /** Ex.: R$ 1,2 mi / R$ 350 mil — para cartões compactos. */
    fun brlCompact(value: Double): String = when {
        value >= 1_000_000 -> "R$ " + String.format(ptBr, "%.1f", value / 1_000_000).removeSuffix(",0") + " mi"
        value >= 1_000 -> "R$ " + String.format(ptBr, "%.0f", value / 1_000) + " mil"
        else -> brl(value)
    }

    fun percent(value: Double, decimals: Int = 1): String = String.format(ptBr, "%.${decimals}f%%", value)

    // 0 = data não informada pela fonte (ex.: prazo de proposta ausente no PNCP): nunca exibir 31/12/1969.
    fun date(millis: Long?): String =
        if (millis == null || millis <= 0L) "—" else SimpleDateFormat("dd/MM/yyyy", ptBr).format(Date(millis))

    fun dateTime(millis: Long?): String =
        if (millis == null || millis <= 0L) "—" else SimpleDateFormat("dd/MM/yyyy HH:mm", ptBr).format(Date(millis))

    fun time(millis: Long?): String =
        if (millis == null) "—" else SimpleDateFormat("HH:mm:ss", ptBr).format(Date(millis))

    /** mm:ss para cronômetros de sessão. */
    fun countdown(seconds: Int?): String {
        if (seconds == null) return "—"
        val s = seconds.coerceAtLeast(0)
        return String.format(ptBr, "%02d:%02d", s / 60, s % 60)
    }

    /** "há 5 min", "há 2 h", "há 3 d". */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - millis).coerceAtLeast(0)
        val min = diff / 60_000
        return when {
            min < 1 -> "agora"
            min < 60 -> "há $min min"
            min < 60 * 24 -> "há ${min / 60} h"
            else -> "há ${min / (60 * 24)} d"
        }
    }

    fun cnpj(raw: String): String {
        val d = raw.filter { it.isDigit() }
        if (d.length != 14) return raw
        return "${d.substring(0, 2)}.${d.substring(2, 5)}.${d.substring(5, 8)}/${d.substring(8, 12)}-${d.substring(12)}"
    }
}
