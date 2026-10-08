package com.licitaia.feature.platform

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatação pt-BR para a UI da plataforma. Decimais chegam como String; datas em ISO-8601 UTC. */
internal object PlatformFormat {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withLocale(Locale("pt", "BR"))

    fun currency(value: String?): String {
        if (value.isNullOrBlank()) return "—"
        val n = runCatching { BigDecimal(value) }.getOrNull() ?: return "—"
        return "R$ " + String.format(Locale("pt", "BR"), "%,.2f", n)
    }

    /** Como [currency], mas trata 0 (edital sigiloso/sem valor) como "—" em vez de "R$ 0,00". */
    fun currencyOrDash(value: String?): String {
        if (value.isNullOrBlank()) return "—"
        val n = runCatching { BigDecimal(value) }.getOrNull() ?: return "—"
        if (n.signum() == 0) return "—"
        return "R$ " + String.format(Locale("pt", "BR"), "%,.2f", n)
    }

    /** Número/percentual que vira "—" quando nulo, vazio ou zero (ex.: margem não informada). */
    fun numberOrDash(value: String?, suffix: String = ""): String {
        if (value.isNullOrBlank()) return "—"
        val n = runCatching { BigDecimal(value) }.getOrNull() ?: return value
        if (n.signum() == 0) return "—"
        val plain = if (n.stripTrailingZeros().scale() <= 0) n.toLong().toString() else n.stripTrailingZeros().toPlainString()
        return plain + suffix
    }

    /** Tamanho de arquivo legível (B/KB/MB). */
    fun fileSize(bytes: Long?): String? {
        val b = bytes ?: return null
        if (b <= 0) return null
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format(Locale("pt", "BR"), "%.0f KB", b / 1024.0)
            else -> String.format(Locale("pt", "BR"), "%.1f MB", b / (1024.0 * 1024.0))
        }
    }

    /** Só a data (dd/MM/yyyy) a partir de ISO; "—" quando ausente. */
    fun dateShort(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return runCatching { DateTimeFormatter.ofPattern("dd/MM/yyyy").withLocale(Locale("pt", "BR")).format(Instant.parse(iso).atZone(zone)) }.getOrDefault("—")
    }

    fun dateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return runCatching { dateFmt.format(Instant.parse(iso).atZone(zone)) }.getOrDefault("—")
    }
}
