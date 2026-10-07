package com.licitaia.core.data.repository

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunitySection
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ProposalWindow
import com.licitaia.domain.model.ProposalWindows
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Só abertas e as que vão abrir; seções Hoje → Próximos dias → Vão abrir (fuso America/Sao_Paulo). */
class ProposalWindowsTest {
    private fun at(d: Int, h: Int, mi: Int = 0, mo: Int = 10): Long =
        LocalDateTime.of(2026, mo, d, h, mi).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    /** Agora: 06/10/2026 10:00 em Brasília. */
    private val now = at(6, 10)

    private fun opp(
        id: String,
        deadline: Long = Opportunity.DEADLINE_UNKNOWN,
        opening: Long? = null,
        session: Long = deadline,
        noDispute: Boolean = false,
    ) = Opportunity(
        id = id, portal = Portal.PNCP, number = id, agency = "Órgão", objectDescription = "Objeto", modality = Modality.PREGAO_ELETRONICO,
        segment = Segment.TI, uf = "MG", city = "BH", estimatedValue = 1.0, publishedAt = at(1, 9), proposalDeadline = deadline,
        sessionAt = session, proposalOpening = opening, noDispute = noDispute,
    )

    private fun scored(o: Opportunity, score: Int = 50) = ScoredOpportunity(o, score, interested = false)

    @Test
    fun `classifica aberta, vai abrir, encerrada, disputada e sem prazo`() {
        assertEquals(ProposalWindow.OPEN, ProposalWindows.classify(opp("a", at(10, 9), opening = at(1, 8)), now))
        assertEquals("sem abertura informada e encerramento futuro = aberta", ProposalWindow.OPEN, ProposalWindows.classify(opp("b", at(10, 9)), now))
        assertEquals(ProposalWindow.UPCOMING, ProposalWindows.classify(opp("c", at(20, 9), opening = at(8, 8)), now))
        assertEquals(ProposalWindow.CLOSED, ProposalWindows.classify(opp("d", at(6, 9, 59)), now))
        assertEquals("encerramento exatamente agora = encerrada", ProposalWindow.CLOSED, ProposalWindows.classify(opp("e", now), now))
        assertEquals(
            "sessão de disputa já realizada",
            ProposalWindow.CLOSED, ProposalWindows.classify(opp("f", at(10, 9), session = at(5, 9)), now),
        )
        assertEquals(ProposalWindow.NO_DEADLINE, ProposalWindows.classify(opp("g"), now))
    }

    @Test
    fun `visiveis so abertas e as que vao abrir e sem prazo ficam ocultas e contadas`() {
        val list = listOf(
            opp("aberta", at(10, 9)), opp("futura", at(20, 9), opening = at(8, 8)), opp("encerrada", at(5, 9)),
            opp("sem-prazo-1"), opp("sem-prazo-2"), opp("dispensa", noDispute = true),
        )
        val v = ProposalWindows.visible(list, now)
        assertEquals(listOf("aberta", "futura"), v.items.map { it.id })
        assertEquals(3, v.hiddenNoDeadline)
        // Dispensas sem disputa só aparecem quando o usuário pediu para vê-las.
        val withNoDispute = ProposalWindows.visible(list, now, showNoDispute = true)
        assertEquals(listOf("aberta", "futura", "dispensa"), withNoDispute.items.map { it.id })
        assertEquals(2, withNoDispute.hiddenNoDeadline)
    }

    @Test
    fun `agrupa Hoje, Proximos dias e Vao abrir com ordem de cada secao`() {
        val items = listOf(
            scored(opp("prox-tarde", at(9, 18)), score = 40),
            scored(opp("vai-abrir-2", at(25, 9), opening = at(12, 8))),
            scored(opp("hoje-17h", at(6, 17))),
            scored(opp("prox-cedo-baixa", at(8, 9)), score = 30),
            scored(opp("prox-cedo-alta", at(8, 9)), score = 90),
            scored(opp("hoje-11h", at(6, 11))),
            scored(opp("vai-abrir-1", at(20, 9), opening = at(7, 8))),
            scored(opp("encerrada", at(6, 9))),
            scored(opp("sem-prazo")),
            // Encerramento amanhã, mas sessão hoje às 15h: entra em "Hoje" pelo horário da sessão.
            scored(opp("sessao-hoje", at(7, 9), session = at(6, 15))),
        )
        val sections = ProposalWindows.group(items, now)
        assertEquals(listOf(OpportunitySection.TODAY, OpportunitySection.NEXT_DAYS, OpportunitySection.UPCOMING), sections.map { it.section })
        assertEquals(listOf("hoje-11h", "sessao-hoje", "hoje-17h"), sections[0].items.map { it.opportunity.id })
        // Nota decrescente primeiro; o horário só desempata.
        assertEquals(listOf("prox-cedo-alta", "prox-tarde", "prox-cedo-baixa"), sections[1].items.map { it.opportunity.id })
        assertEquals(listOf("vai-abrir-1", "vai-abrir-2"), sections[2].items.map { it.opportunity.id })
    }

    @Test
    fun `virada do relogio tira as encerradas e move para Hoje`() {
        val items = listOf(scored(opp("a", at(6, 11))), scored(opp("b", at(7, 9))))
        // 11:30 do mesmo dia: "a" encerrou e some.
        assertEquals(listOf("b"), ProposalWindows.group(items, at(6, 11, 30)).flatMap { it.items }.map { it.opportunity.id })
        // Dia seguinte 08:00: "b" passa para "Hoje".
        val next = ProposalWindows.group(items, at(7, 8))
        assertEquals(OpportunitySection.TODAY, next.single().section)
        assertEquals(listOf("b"), next.single().items.map { it.opportunity.id })
        assertTrue(next.none { s -> s.items.any { it.opportunity.id == "a" } })
    }

    @Test
    fun `rotulos do card`() {
        assertEquals("Abre em 08/10 08:00", ProposalWindows.statusLabel(opp("c", at(20, 9), opening = at(8, 8)), now))
        assertEquals("Aberta · encerra em 10/10 09:00", ProposalWindows.statusLabel(opp("a", at(10, 9)), now))
        assertNull(ProposalWindows.statusLabel(opp("d", at(5, 9)), now))
        assertTrue(ProposalWindows.endsToday(opp("h", at(6, 23, 30)), now))
        assertFalse(ProposalWindows.endsToday(opp("t", at(7, 0, 30)), now))
    }
}
