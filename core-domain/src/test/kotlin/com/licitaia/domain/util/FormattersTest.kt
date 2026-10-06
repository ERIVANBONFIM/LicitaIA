package com.licitaia.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class FormattersTest {

    /** Normaliza espaços (NBSP/narrow NBSP do CLDR) para comparar moeda de forma estável. */
    private fun String.plainSpaces() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun `brl formata em reais pt-BR`() {
        assertEquals("R$ 1.234,56", Formatters.brl(1234.56).plainSpaces())
        assertEquals("R$ 0,00", Formatters.brl(0.0).plainSpaces())
        assertEquals("-R$ 10,00", Formatters.brl(-10.0).plainSpaces())
    }

    @Test
    fun `brl nulo mostra travessao`() {
        assertEquals("—", Formatters.brl(null))
    }

    @Test
    fun `brlCompact usa mil e mi`() {
        assertEquals("R$ 350 mil", Formatters.brlCompact(350_000.0))
        assertEquals("R$ 1,2 mi", Formatters.brlCompact(1_200_000.0))
        assertEquals("R$ 2 mi", Formatters.brlCompact(2_000_000.0))
        assertEquals("R$ 999,00", Formatters.brlCompact(999.0).plainSpaces())
    }

    @Test
    fun `percent usa virgula decimal`() {
        assertEquals("12,5%", Formatters.percent(12.5))
        assertEquals("3%", Formatters.percent(3.0, decimals = 0))
        assertEquals("0,00%", Formatters.percent(0.0, decimals = 2))
    }

    @Test
    fun `countdown em mm ss e nunca negativo`() {
        assertEquals("00:00", Formatters.countdown(0))
        assertEquals("00:00", Formatters.countdown(-5))
        assertEquals("01:05", Formatters.countdown(65))
        assertEquals("125:00", Formatters.countdown(7500))
        assertEquals("—", Formatters.countdown(null))
    }

    @Test
    fun `relative descreve o tempo decorrido`() {
        val now = 1_700_000_000_000L
        assertEquals("agora", Formatters.relative(now - 30_000, now))
        assertEquals("há 5 min", Formatters.relative(now - 5 * 60_000, now))
        assertEquals("há 2 h", Formatters.relative(now - 2 * 3_600_000, now))
        assertEquals("há 3 d", Formatters.relative(now - 3 * 86_400_000, now))
        assertEquals("agora", Formatters.relative(now + 60_000, now)) // futuro não vira negativo
    }

    @Test
    fun `cnpj mascara 14 digitos e devolve original caso contrario`() {
        assertEquals("12.345.678/0001-90", Formatters.cnpj("12345678000190"))
        assertEquals("12.345.678/0001-90", Formatters.cnpj("12.345.678/0001-90"))
        assertEquals("123", Formatters.cnpj("123"))
        assertEquals("", Formatters.cnpj(""))
    }

    @Test
    fun `date e dateTime seguem dd MM yyyy no fuso padrao`() {
        val cal = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.MARCH, 9, 14, 5, 7)
        }
        val millis = cal.timeInMillis
        assertEquals("09/03/2026", Formatters.date(millis))
        assertEquals("09/03/2026 14:05", Formatters.dateTime(millis))
        assertEquals("14:05:07", Formatters.time(millis))
        assertEquals("—", Formatters.date(null))
        assertEquals("—", Formatters.dateTime(null))
        assertEquals("—", Formatters.time(null))
    }
}
