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

/** Mês atual em diante; padrão de hoje em diante; seções Hoje → Próximos dias → Vão abrir → Dias anteriores (America/Sao_Paulo). */
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
        published: Long = at(1, 9),
        modality: Modality = Modality.PREGAO_ELETRONICO,
    ) = Opportunity(
        id = id, portal = Portal.PNCP, number = id, agency = "Órgão", objectDescription = "Objeto", modality = modality,
        segment = Segment.TI, uf = "MG", city = "BH", estimatedValue = 1.0, publishedAt = published, proposalDeadline = deadline,
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
    fun `do mes atual em diante, de hoje em diante em items e dias anteriores a parte`() {
        val list = listOf(
            opp("aberta", at(10, 9)), opp("futura", at(20, 9), opening = at(8, 8)), opp("encerrada-hoje", at(6, 9)),
            opp("ontem", at(5, 9)), opp("setembro", at(30, 9, mo = 9)),
            opp("sem-prazo-1"), opp("sem-prazo-2"), opp("dispensa-dia-1", noDispute = true),
            opp("dispensa-hoje", noDispute = true, published = at(6, 8)),
            // Sem encerramento, mas com sessão: a sessão é a data de proposta.
            opp("so-sessao", session = at(12, 9)),
        )
        val v = ProposalWindows.visible(list, now)
        assertEquals(listOf("aberta", "futura", "encerrada-hoje", "so-sessao"), v.items.map { it.id })
        assertEquals(listOf("ontem"), v.previous.map { it.id })
        assertEquals(4, v.hiddenNoDeadline)
        // Dispensas sem disputa (padrão da Busca): o dia delas é a publicação.
        val withNoDispute = ProposalWindows.visible(list, now, showNoDispute = true)
        assertEquals(listOf("aberta", "futura", "encerrada-hoje", "dispensa-hoje", "so-sessao"), withNoDispute.items.map { it.id })
        assertEquals(listOf("ontem", "dispensa-dia-1"), withNoDispute.previous.map { it.id })
        assertEquals(2, withNoDispute.hiddenNoDeadline)
    }

    @Test
    fun `inicio do dia e do mes no fuso de Brasilia`() {
        assertEquals(at(6, 0), ProposalWindows.dayStart(at(6, 23, 59)))
        assertEquals(at(6, 0), ProposalWindows.dayStart(at(6, 0)))
        assertEquals(at(1, 0), ProposalWindows.monthStart(now))
        // 22:00 em Brasília já é dia 7 em UTC: continua sendo "hoje" (dia 6) no fuso do app.
        val late = opp("tarde", at(6, 22))
        assertEquals(OpportunitySection.TODAY, ProposalWindows.sectionOf(late, at(6, 23)))
        assertFalse(ProposalWindows.isPreviousDay(late, at(6, 23)))
        // 1º do mês 00:00 entra no escopo; 30/09 23:59 não.
        assertEquals(OpportunitySection.PREVIOUS_DAYS, ProposalWindows.sectionOf(opp("dia1", at(1, 0)), now))
        assertNull(ProposalWindows.sectionOf(opp("set", at(30, 23, 59, mo = 9)), now))
        assertTrue(ProposalWindows.isBeforeMonth(opp("set", at(30, 23, 59, mo = 9)), now))
    }

    @Test
    fun `agrupa Hoje, Proximos dias, Vao abrir e Dias anteriores com ordem de cada secao`() {
        val items = listOf(
            scored(opp("prox-tarde", at(9, 18)), score = 40),
            scored(opp("vai-abrir-2", at(25, 9), opening = at(12, 8))),
            scored(opp("hoje-17h", at(6, 17))),
            scored(opp("prox-cedo-baixa", at(8, 9)), score = 30),
            scored(opp("prox-cedo-alta", at(8, 9)), score = 90),
            scored(opp("hoje-11h", at(6, 11))),
            scored(opp("vai-abrir-1", at(20, 9), opening = at(7, 8))),
            scored(opp("encerrada-hoje", at(6, 9)), score = 99),
            scored(opp("sem-prazo")),
            scored(opp("setembro", at(29, 9, mo = 9))),
            // Encerramento amanhã, mas sessão hoje às 15h: entra em "Hoje" pelo horário da sessão.
            scored(opp("sessao-hoje", at(7, 9), session = at(6, 15))),
            scored(opp("dia-2", at(2, 9)), score = 90),
            scored(opp("ontem-tarde", at(5, 18)), score = 10),
            scored(opp("ontem", at(5, 9))),
        )
        val sections = ProposalWindows.group(items, now)
        assertEquals(
            listOf(OpportunitySection.TODAY, OpportunitySection.NEXT_DAYS, OpportunitySection.UPCOMING, OpportunitySection.PREVIOUS_DAYS),
            sections.map { it.section },
        )
        // Encerradas hoje continuam em "Hoje", depois das abertas.
        assertEquals(listOf("hoje-11h", "sessao-hoje", "hoje-17h", "encerrada-hoje"), sections[0].items.map { it.opportunity.id })
        // Nota decrescente primeiro; o horário só desempata.
        assertEquals(listOf("prox-cedo-alta", "prox-tarde", "prox-cedo-baixa"), sections[1].items.map { it.opportunity.id })
        assertEquals(listOf("vai-abrir-1", "vai-abrir-2"), sections[2].items.map { it.opportunity.id })
        // Dias anteriores: da mais recente para a mais antiga.
        assertEquals(listOf("ontem-tarde", "ontem", "dia-2"), sections[3].items.map { it.opportunity.id })
    }

    @Test
    fun `virada do dia move as de ontem para Dias anteriores sozinhas`() {
        val items = listOf(scored(opp("a", at(6, 11))), scored(opp("b", at(7, 9))), scored(opp("c", at(6, 23, 59))))
        // 11:30 do mesmo dia: "a" encerrou mas continua em "Hoje" (no fim), "c" ainda aberta.
        val same = ProposalWindows.group(items, at(6, 11, 30))
        assertEquals(listOf("c", "a"), same.first { it.section == OpportunitySection.TODAY }.items.map { it.opportunity.id })
        assertTrue(ProposalWindows.isClosed(items[0].opportunity, at(6, 11, 30)))
        // Meia-noite em ponto do dia 7: "a" e "c" viram dia anterior; "b" passa para "Hoje".
        val midnight = at(7, 0)
        assertEquals(OpportunitySection.TODAY, ProposalWindows.sectionOf(items[1].opportunity, midnight))
        assertEquals(OpportunitySection.PREVIOUS_DAYS, ProposalWindows.sectionOf(items[0].opportunity, midnight))
        assertEquals(OpportunitySection.PREVIOUS_DAYS, ProposalWindows.sectionOf(items[2].opportunity, midnight))
        // Padrão da tela (sem "Mostrar dias anteriores"): só "b".
        val g = com.licitaia.domain.model.LowAdherence.group(items, midnight, threshold = null, show = false)
        assertEquals(listOf("b"), g.items.map { it.opportunity.id })
        assertEquals(2, g.previousCount)
        assertEquals("1 de hoje em diante · 2 de dias anteriores ocultos", g.windowSummary)
        // Virada do mês: as do mês anterior saem do escopo.
        assertTrue(ProposalWindows.group(items, at(1, 8, mo = 11)).isEmpty())
    }

    @Test
    fun `rotulos do card`() {
        assertEquals("Abre em 08/10 08:00", ProposalWindows.statusLabel(opp("c", at(20, 9), opening = at(8, 8)), now))
        assertEquals("Aberta · encerra em 10/10 09:00", ProposalWindows.statusLabel(opp("a", at(10, 9)), now))
        assertEquals("Encerrada em 06/10 09:00", ProposalWindows.statusLabel(opp("e", at(6, 9)), now))
        assertEquals("Dia anterior · encerrada em 05/10 09:00", ProposalWindows.statusLabel(opp("d", at(5, 9)), now))
        assertEquals("Dia anterior", ProposalWindows.statusLabel(opp("x", noDispute = true, published = at(3, 9)), now))
        assertNull(ProposalWindows.statusLabel(opp("y", noDispute = true, published = at(6, 9)), now))
        assertTrue(ProposalWindows.endsToday(opp("h", at(6, 23, 30)), now))
        assertFalse(ProposalWindows.endsToday(opp("t", at(7, 0, 30)), now))
    }
}