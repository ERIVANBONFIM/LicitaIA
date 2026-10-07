package com.licitaia.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Horário da atualização diária (05:30 em Brasília), recuperação e textos da linha de fontes. */
class DailySyncScheduleTest {
    private val sp = ZoneId.of("America/Sao_Paulo")

    private fun brasilia(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(sp).toInstant().toEpochMilli()

    private val default = DailySyncSettings()

    @Test
    fun `antes das 05h30 o proximo horario e hoje`() {
        assertEquals(brasilia(2026, 10, 7, 5, 30), DailySyncSchedule.nextRun(brasilia(2026, 10, 7, 3, 0), 5, 30))
        assertEquals(brasilia(2026, 10, 7, 5, 30), DailySyncSchedule.nextRun(brasilia(2026, 10, 7, 5, 29, 59), 5, 30))
    }

    @Test
    fun `no horario ou depois o proximo e amanha`() {
        assertEquals(brasilia(2026, 10, 8, 5, 30), DailySyncSchedule.nextRun(brasilia(2026, 10, 7, 5, 30), 5, 30))
        assertEquals(brasilia(2026, 10, 8, 5, 30), DailySyncSchedule.nextRun(brasilia(2026, 10, 7, 8, 0), 5, 30))
        assertEquals(brasilia(2026, 10, 8, 5, 30), DailySyncSchedule.nextRun(brasilia(2026, 10, 7, 23, 59), 5, 30))
    }

    @Test
    fun `virada de mes e de ano`() {
        assertEquals(brasilia(2026, 11, 1, 5, 30), DailySyncSchedule.nextRun(brasilia(2026, 10, 31, 9, 0), 5, 30))
        assertEquals(brasilia(2027, 1, 1, 5, 30), DailySyncSchedule.nextRun(brasilia(2026, 12, 31, 6, 0), 5, 30))
        assertEquals(brasilia(2028, 2, 29, 5, 30), DailySyncSchedule.nextRun(brasilia(2028, 2, 28, 6, 0), 5, 30))
        assertEquals(brasilia(2026, 10, 1, 0, 0), DailySyncSchedule.monthStart(brasilia(2026, 10, 31, 23, 0)))
    }

    @Test
    fun `fuso de Brasilia mesmo com o relogio em UTC`() {
        // 07/10 08:00 UTC = 05:00 em Brasília (UTC-3): ainda hoje às 05:30 BRT = 08:30 UTC.
        val utc0800 = LocalDateTime.of(2026, 10, 7, 8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val next = DailySyncSchedule.nextRun(utc0800, 5, 30)
        assertEquals(LocalDateTime.of(2026, 10, 7, 8, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), next)
        // 07/10 02:00 UTC = 06/10 23:00 em Brasília: o "hoje" é 06/10, então o próximo é 07/10 05:30 BRT.
        val utc0200 = LocalDateTime.of(2026, 10, 7, 2, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(brasilia(2026, 10, 7, 5, 30), DailySyncSchedule.nextRun(utc0200, 5, 30))
    }

    @Test
    fun `horario configurado diferente`() {
        assertEquals(brasilia(2026, 10, 7, 22, 15), DailySyncSchedule.nextRun(brasilia(2026, 10, 7, 21, 0), 22, 15))
        assertEquals(brasilia(2026, 10, 8, 0, 0), DailySyncSchedule.nextRun(brasilia(2026, 10, 7, 21, 0), 0, 0))
    }

    @Test
    fun `recuperacao quando a das 05h30 de hoje nao rodou`() {
        val eight = brasilia(2026, 10, 7, 8, 0)
        // Nunca sincronizou: roda.
        assertTrue(DailySyncSchedule.isCatchUpDue(eight, null, default))
        // Última completa ontem (aparelho desligado às 05:30 de hoje): roda.
        assertTrue(DailySyncSchedule.isCatchUpDue(eight, brasilia(2026, 10, 6, 5, 31), default))
        // Rodou hoje às 05:31: não roda de novo.
        assertFalse(DailySyncSchedule.isCatchUpDue(eight, brasilia(2026, 10, 7, 5, 31), default))
        // Atualização manual às 04:00 de hoje é anterior ao horário: a das 05:30 ainda é devida.
        assertTrue(DailySyncSchedule.isCatchUpDue(eight, brasilia(2026, 10, 7, 4, 0), default))
        // Desligada: nunca.
        assertFalse(DailySyncSchedule.isCatchUpDue(eight, null, default.copy(enabled = false)))
    }

    @Test
    fun `recuperacao de madrugada considera o horario de ontem`() {
        val three = brasilia(2026, 10, 7, 3, 0)
        // Rodou ontem às 05:30: nada a recuperar antes das 05:30 de hoje.
        assertFalse(DailySyncSchedule.isCatchUpDue(three, brasilia(2026, 10, 6, 5, 30), default))
        // A de ontem não rodou (última anteontem): recupera já.
        assertTrue(DailySyncSchedule.isCatchUpDue(three, brasilia(2026, 10, 5, 5, 30), default))
        // Virada de mês: 01/11 03:00, última em 31/10 05:30 → em dia.
        assertFalse(DailySyncSchedule.isCatchUpDue(brasilia(2026, 11, 1, 3, 0), brasilia(2026, 10, 31, 5, 30), default))
    }

    @Test
    fun `textos da linha de fontes`() {
        val now = brasilia(2026, 10, 7, 8, 0)
        val status = DailySyncStatus(lastCompletedAt = brasilia(2026, 10, 7, 5, 30))
        assertEquals(
            "Atualizado hoje às 05:30 · Próxima atualização automática: amanhã 05:30",
            DailySyncSchedule.sourceLine(status, default, now),
        )
        assertEquals(
            "Atualizado ontem às 05:30 · Próxima atualização automática: hoje 05:30",
            DailySyncSchedule.sourceLine(DailySyncStatus(brasilia(2026, 10, 6, 5, 30)), default, brasilia(2026, 10, 7, 2, 0)),
        )
        assertEquals(
            "Atualizado hoje às 05:30 · Atualização automática desligada",
            DailySyncSchedule.sourceLine(status, default.copy(enabled = false), now),
        )
        assertEquals("05:30", default.timeLabel)
    }
}
