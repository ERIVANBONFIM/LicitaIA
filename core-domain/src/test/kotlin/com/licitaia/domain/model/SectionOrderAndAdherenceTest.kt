package com.licitaia.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Ordem dentro das seções (nota → horário) e filtro de baixa aderência da Busca. */
class SectionOrderAndAdherenceTest {
    private fun at(d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(2026, 10, d, h, mi).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    /** Agora: 06/10/2026 10:00 em Brasília. */
    private val now = at(6, 10)

    private fun opp(id: String, deadline: Long, opening: Long? = null) = Opportunity(
        id = id, portal = Portal.PNCP, number = id, agency = "Órgão", objectDescription = "Objeto",
        modality = Modality.PREGAO_ELETRONICO, segment = Segment.TI, uf = "MG", city = "BH", estimatedValue = 1.0,
        publishedAt = at(1, 9), proposalDeadline = deadline, sessionAt = deadline, proposalOpening = opening,
    )

    private fun scored(o: Opportunity, score: Int, ai: Boolean = false) = ScoredOpportunity(
        o, score, interested = false, scoreSource = if (ai) ScoreSource.AI else ScoreSource.HEURISTIC,
    )

    private fun ids(s: SectionedOpportunities) = s.items.map { it.opportunity.id }

    @Test
    fun `Hoje ordena por nota decrescente e depois pelo horario`() {
        val items = listOf(
            scored(opp("11h-ia-0", at(6, 11)), 0, ai = true),
            scored(opp("17h-80", at(6, 17)), 80),
            scored(opp("12h-80", at(6, 12)), 80),
            scored(opp("15h-ia-95", at(6, 15)), 95, ai = true),
        )
        val today = ProposalWindows.group(items, now).single()
        assertEquals(OpportunitySection.TODAY, today.section)
        assertEquals(listOf("15h-ia-95", "12h-80", "17h-80", "11h-ia-0"), ids(today))
    }

    @Test
    fun `Proximos dias e Vao abrir tambem ordenam por nota e depois horario`() {
        val items = listOf(
            scored(opp("prox-8-40", at(8, 9)), 40),
            scored(opp("prox-9-70", at(9, 9)), 70),
            scored(opp("prox-7-70", at(7, 9)), 70),
            scored(opp("abre-9-50", at(25, 9), opening = at(9, 8)), 50),
            scored(opp("abre-7-50", at(25, 9), opening = at(7, 8)), 50),
            scored(opp("abre-12-90", at(25, 9), opening = at(12, 8)), 90),
        )
        val sections = ProposalWindows.group(items, now)
        assertEquals(listOf(OpportunitySection.NEXT_DAYS, OpportunitySection.UPCOMING), sections.map { it.section })
        assertEquals(listOf("prox-7-70", "prox-9-70", "prox-8-40"), ids(sections[0]))
        assertEquals(listOf("abre-12-90", "abre-7-50", "abre-9-50"), ids(sections[1]))
    }

    @Test
    fun `baixa aderencia fica oculta por padrao e e contada`() {
        val items = listOf(
            scored(opp("ia-0", at(6, 11)), 0, ai = true),
            scored(opp("29", at(7, 9)), 29),
            scored(opp("30", at(8, 9)), 30),
            scored(opp("75", at(9, 9)), 75),
        )
        val split = LowAdherence.split(items, show = false)
        assertEquals(listOf("30", "75"), split.visible.map { it.opportunity.id })
        assertEquals(listOf("ia-0", "29"), split.hidden.map { it.opportunity.id })

        val shown = LowAdherence.split(items, show = true)
        assertEquals(4, shown.visible.size)
        assertTrue(shown.hidden.isEmpty())

        assertTrue(LowAdherence.isLow(items[1]))
        assertFalse(LowAdherence.isLow(items[2]))
        assertEquals("2 de baixa aderência ocultas", LowAdherence.hiddenLabel(2))
        assertEquals("1 de baixa aderência oculta", LowAdherence.hiddenLabel(1))
        assertNull(LowAdherence.hiddenLabel(0))
    }

    @Test
    fun `group aplica o corte, ordena e ignora encerradas e duplicadas na contagem`() {
        val items = listOf(
            scored(opp("hoje-ia-0", at(6, 11)), 0, ai = true),
            scored(opp("hoje-60", at(6, 16)), 60),
            scored(opp("hoje-60", at(6, 16)), 60),
            scored(opp("encerrada-baixa", at(6, 9)), 10),
            scored(opp("prox-40", at(8, 9)), 40),
        )
        val g = LowAdherence.group(items, now, LowAdherence.THRESHOLD, show = false)
        assertEquals(listOf("hoje-60", "prox-40"), g.items.map { it.opportunity.id })
        assertEquals("encerrada não conta como oculta", listOf("hoje-ia-0"), g.hiddenLow.map { it.opportunity.id })

        val all = LowAdherence.group(items, now, LowAdherence.THRESHOLD, show = true)
        assertEquals(listOf("hoje-60", "hoje-ia-0", "prox-40"), all.items.map { it.opportunity.id })
        assertTrue(all.hiddenLow.isEmpty())

        // Radar: sem corte de baixa aderência (usa o próprio score mínimo no repositório).
        val radar = LowAdherence.group(items, now, threshold = null, show = false)
        assertEquals(3, radar.items.size)
        assertTrue(radar.hiddenLow.isEmpty())
    }

    @Test
    fun `IA so na busca focada`() {
        assertFalse(OpportunityFilter().focused)
        assertFalse(OpportunityFilter(query = "   ", portals = setOf(Portal.PNCP), ufs = setOf("MG")).focused)
        assertTrue(OpportunityFilter(query = "hospitalar").focused)
        assertTrue(OpportunityFilter(segment = Segment.TI).focused)
    }
}
