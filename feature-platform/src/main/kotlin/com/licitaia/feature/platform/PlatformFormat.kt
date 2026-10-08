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

    fun dateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return runCatching { dateFmt.format(Instant.parse(iso).atZone(zone)) }.getOrDefault("—")
    }
}
