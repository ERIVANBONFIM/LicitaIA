package com.licitaia.feature.warroom

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WarRoomAgendaTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 9) = LocalDateTime.of(y, m, d, h, 0).atZone(WarRoomAgenda.ZONE).toInstant().toEpochMilli()

    /** Quarta-feira, 07/10/2026 09:00. */
    private val now = at(2026, 10, 7)

    private fun tender(id: Long, session: Long, status: TenderStatus = TenderStatus.INTERESSE, deadline: Long = session) = Tender(
        id = id, companyId = 1, opportunityId = "PNCP:$id", portal = Portal.COMPRAS_GOV, number = "$id/2026", agency = "Órgão",
        objectDescription = "Objeto", modality = Modality.PREGAO_ELETRONICO, segment = Segment.TI, uf = "MG", city = "BH",
        estimatedValue = 1.0, proposalDeadline = deadline, sessionAt = session, status = status,
    )

    @Test
    fun `proximas sessoes em 7 dias, ordenadas, sem encerradas`() {
        val list = WarRoomAgenda.upcomingSessions(
            listOf(
                tender(1, at(2026, 10, 12)),
                tender(2, at(2026, 10, 8), TenderStatus.ENVIADA_SIMULADA),
                tender(3, at(2026, 10, 20)),
                tender(4, at(2026, 10, 9), TenderStatus.PERDIDA),
                tender(5, at(2026, 10, 6)),
            ),
            emptyList(), now,
        )
        assertEquals(listOf(2L, 1L), list.map { it.tenderId })
        assertTrue(list.first().participating)
    }

    @Test
    fun `esclarecimento e impugnacao 3 dias uteis antes da abertura`() {
        // Abertura na segunda 19/10 → 3 dias úteis antes = quarta 14/10 (fim do dia).
        val limit = WarRoomAgenda.businessDaysBefore(at(2026, 10, 19), 3)
        assertEquals(at(2026, 10, 15, 0) - 1, limit)
        // Abertura na quinta 15/10 → limite segunda 12/10; proposta até 13/10.
        val deadlines = WarRoomAgenda.upcomingDeadlines(listOf(tender(1, at(2026, 10, 15), deadline = at(2026, 10, 13))), now)
        assertEquals(listOf(DeadlineKind.ESCLARECIMENTO, DeadlineKind.IMPUGNACAO, DeadlineKind.PROPOSTA), deadlines.map { it.kind })
        assertTrue(deadlines.first().estimated)
    }

    @Test
    fun `contagem regressiva`() {
        assertEquals("1d 02h", WarRoomAgenda.countdown(now + 26 * 3_600_000L, now))
        assertEquals("02h 05min", WarRoomAgenda.countdown(now + 125 * 60_000L, now))
        assertEquals("04:30", WarRoomAgenda.countdown(now + 270_000L, now))
        assertEquals("agora", WarRoomAgenda.countdown(now - 1, now))
    }
}
