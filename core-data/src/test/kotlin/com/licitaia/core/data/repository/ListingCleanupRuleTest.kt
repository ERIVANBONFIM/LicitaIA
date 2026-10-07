package com.licitaia.core.data.repository

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.sync.DailySyncSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Limpeza diária do cache local: o que apaga (retiradas, encerradas, antigas fechadas) e o que preserva. */
class ListingCleanupRuleTest {
    private fun brasilia(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    private val now = brasilia(2026, 10, 7, 5, 45)
    private val monthStart = DailySyncSchedule.monthStart(now)

    private fun opp(
        seq: Int,
        published: Long = brasilia(2026, 10, 2),
        deadline: Long = brasilia(2026, 10, 20),
        session: Long = deadline,
        portal: Portal = Portal.PNCP,
    ) = Opportunity(
        id = "${portal.name}:12345678000199-1-${seq.toString().padStart(6, '0')}/2026", portal = portal, number = "$seq/2026",
        agency = "Órgão", objectDescription = "Objeto $seq", modality = Modality.PREGAO_ELETRONICO, segment = Segment.entries.first(),
        uf = "SP", city = "São Paulo", estimatedValue = 1000.0, publishedAt = published, proposalDeadline = deadline, sessionAt = session,
    )

    private fun reason(o: Opportunity, withdrawn: Set<String> = emptySet(), protectedIds: Collection<String> = emptyList()) =
        ListingCleanupRule.reason(o, now, monthStart, withdrawn, ListingCleanupRule.protectedKeys(protectedIds))

    @Test
    fun `apaga canceladas revogadas e afins vistas pelas fontes`() {
        val o = opp(1)
        assertEquals(ListingCleanupRule.Reason.WITHDRAWN, reason(o, withdrawn = setOf(o.id)))
    }

    @Test
    fun `apaga encerradas so quando prazo e sessao ja passaram`() {
        assertEquals(ListingCleanupRule.Reason.ENDED, reason(opp(2, deadline = brasilia(2026, 10, 6), session = brasilia(2026, 10, 6, 14))))
        // Sessão não informada: o prazo vencido basta.
        assertEquals(ListingCleanupRule.Reason.ENDED, reason(opp(3, deadline = brasilia(2026, 10, 6), session = 0L)))
        // Propostas encerradas mas a sessão de disputa é amanhã: fica.
        assertNull(reason(opp(4, deadline = brasilia(2026, 10, 6), session = brasilia(2026, 10, 8))))
    }

    @Test
    fun `apaga as de meses anteriores que nao estao mais abertas e mantem as abertas`() {
        val september = brasilia(2026, 9, 20)
        // Publicada em setembro, prazo nunca confirmado (sem prazo): sai.
        assertEquals(ListingCleanupRule.Reason.OLD, reason(opp(5, published = september, deadline = 0L, session = 0L)))
        // Publicada em setembro e ainda aberta (prazo em 20/10): fica.
        assertNull(reason(opp(6, published = september)))
        // Do mês atual sem prazo informado: fica (ainda pode receber o prazo do PNCP).
        assertNull(reason(opp(7, published = brasilia(2026, 10, 1, 0, 1), deadline = 0L, session = 0L)))
        // Publicada no último minuto de setembro sem prazo: já é do mês anterior.
        assertEquals(ListingCleanupRule.Reason.OLD, reason(opp(8, published = brasilia(2026, 9, 30, 23, 59), deadline = 0L, session = 0L)))
    }

    @Test
    fun `nunca apaga as salvas pelo usuario mesmo canceladas encerradas ou antigas`() {
        val cancelled = opp(9)
        val ended = opp(10, deadline = brasilia(2026, 9, 1), session = brasilia(2026, 9, 1), published = brasilia(2026, 8, 20))
        val saved = listOf(cancelled.id, ended.id)
        assertNull(reason(cancelled, withdrawn = setOf(cancelled.id), protectedIds = saved))
        assertNull(reason(ended, protectedIds = saved))
    }

    @Test
    fun `protecao vale para a mesma contratacao vinda do outro conector`() {
        // Interesse marcado no registro do Compras.gov.br; o cache tem o mesmo número de controle pelo PNCP.
        val pncp = opp(11, deadline = brasilia(2026, 10, 1), session = 0L)
        val comprasGovId = pncp.id.replace("PNCP:", "COMPRAS_GOV:")
        assertNull(reason(pncp, protectedIds = listOf(comprasGovId)))
        assertEquals(ListingCleanupRule.Reason.ENDED, reason(pncp, protectedIds = listOf("COMPRAS_GOV:99999999000199-1-000011/2026")))
    }

    @Test
    fun `abertas e que vao abrir ficam`() {
        assertNull(reason(opp(12)))
        assertTrue(ListingCleanupRule.isOpen(opp(13), now))
        assertFalse(ListingCleanupRule.isOpen(opp(14, deadline = 0L), now))
    }

    @Test
    fun `editais orfaos sao os de licitacoes apagadas e com mais de 1 hora`() {
        val tenders = setOf(1L, 2L)
        val old = now - 2 * 60 * 60 * 1000
        assertTrue(ListingCleanupRule.isOrphanEdital("3.pdf", old, tenders, now))
        assertTrue(ListingCleanupRule.isOrphanEdital("3.anexo.pdf", old, tenders, now))
        assertTrue(ListingCleanupRule.isOrphanEdital("3.txt", old, tenders, now))
        assertFalse(ListingCleanupRule.isOrphanEdital("1.pdf", old, tenders, now))
        // Recém-criado (download em andamento antes de a licitação existir/ser lida): fica.
        assertFalse(ListingCleanupRule.isOrphanEdital("3.pdf.part", now - 60_000, tenders, now))
        // Nome fora do padrão: nunca apagado.
        assertFalse(ListingCleanupRule.isOrphanEdital("restaurado.pdf", old, tenders, now))
    }
}
