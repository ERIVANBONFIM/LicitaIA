package com.licitaia.domain.model

import com.licitaia.domain.scoring.OpportunityFilterMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** UASG, descartadas, selo "Nova", filtro de período, situação oficial, ADIADA e mudança de fase. */
class OpportunityMarksTest {
    private fun at(d: Int, h: Int, mi: Int = 0, mo: Int = 10): Long =
        LocalDateTime.of(2026, mo, d, h, mi).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    /** Agora: 07/10/2026 10:00 em Brasília. */
    private val now = at(7, 10)

    private fun opp(
        id: String,
        deadline: Long = at(10, 9),
        portal: Portal = Portal.COMPRAS_GOV,
        uasg: String? = null,
        keywords: List<String> = emptyList(),
        published: Long = at(2, 9),
    ) = Opportunity(
        id = id, portal = portal, number = "$id/2026", agency = "Órgão", objectDescription = "Link de internet",
        modality = Modality.PREGAO_ELETRONICO, segment = Segment.TI, uf = "MG", city = "BH", estimatedValue = 1.0,
        publishedAt = published, proposalDeadline = deadline, sessionAt = deadline, keywords = keywords, uasg = uasg,
    )

    private fun scored(o: Opportunity) = ScoredOpportunity(o, 60, interested = false)

    @Test
    fun `UASG normalizada, lida das palavras-chave e rotulada por plataforma`() {
        assertEquals("092731", UasgCode.normalize("92731", comprasGov = true))
        assertEquals("92731", UasgCode.normalize("92731", comprasGov = false))
        assertNull(UasgCode.normalize("  ", comprasGov = true))
        assertEquals("927312", UasgCode.fromKeywords(listOf("Pregão", "UASG 927312")))
        assertEquals("927312", UasgCode.of(opp("a", keywords = listOf("UASG 927312"))))
        assertEquals("160123", UasgCode.of(opp("b", uasg = "160123", keywords = listOf("UASG 999999"))))
        assertEquals("UASG 160123", UasgCode.label(opp("c", uasg = "160123")))
        assertEquals("Cód. unidade 1234", UasgCode.label(opp("d", portal = Portal.PNCP, uasg = "1234")))
        assertNull(UasgCode.label(opp("e")))
    }

    @Test
    fun `digitar a UASG na busca acha as contratacoes daquela unidade`() {
        assertEquals("160123", UasgCode.fromQuery("160123"))
        assertEquals("60123", UasgCode.fromQuery(" 60123 "))
        assertEquals("160123", UasgCode.fromQuery("UASG 160123"))
        assertNull(UasgCode.fromQuery("1234"))
        assertNull(UasgCode.fromQuery("internet"))
        val mine = opp("x", uasg = "060123")
        val other = opp("y", uasg = "927312")
        assertTrue(OpportunityFilterMatcher.matches(OpportunityFilter(query = "60123"), mine))
        assertTrue(OpportunityFilterMatcher.matches(OpportunityFilter(query = "060123"), mine))
        assertFalse(OpportunityFilterMatcher.matches(OpportunityFilter(query = "60123"), other))
        // Só pelas palavras-chave (cache de linhas do Compras.gov.br).
        assertTrue(OpportunityFilterMatcher.matches(OpportunityFilter(query = "927312"), opp("z", keywords = listOf("UASG 927312"))))
        // Os demais filtros continuam valendo.
        assertFalse(OpportunityFilterMatcher.matches(OpportunityFilter(query = "60123", ufs = setOf("SP")), mine))
    }

    @Test
    fun `descartadas somem da lista, aparecem no chip e voltam ao restaurar`() {
        val items = listOf(scored(opp("1")), scored(opp("2")), scored(opp("3")))
        val marks = ListMarks(discarded = setOf("2"))
        val g = LowAdherence.group(items, now, threshold = null, show = false, marks = marks)
        assertEquals(listOf("1", "3"), g.items.map { it.opportunity.id }.sorted())
        assertEquals(1, g.discardedCount)
        val onlyDiscarded = LowAdherence.group(items, now, threshold = null, show = false, marks = marks.copy(showDiscarded = true))
        assertEquals(listOf("2"), onlyDiscarded.items.map { it.opportunity.id })
        val restored = LowAdherence.group(items, now, threshold = null, show = false, marks = ListMarks())
        assertEquals(3, restored.items.size)
        assertEquals(0, restored.discardedCount)
    }

    @Test
    fun `selo Nova - entrou depois da ultima abertura ou na ultima atualizacao diaria e nunca foi aberta`() {
        val lastOpen = at(6, 18)
        val daily = at(7, 5, 40)
        val since = Novelty.since(daily, lastOpen)
        assertEquals(lastOpen, since)
        assertEquals("abriu depois da sincronização: vale a sincronização", daily - Novelty.DAILY_SYNC_WINDOW_MS, Novelty.since(daily, at(7, 7)))
        assertNull(Novelty.since(null, null))
        val firstSeen = mapOf("velha" to at(5, 9), "nova" to at(7, 5, 35), "vista" to at(7, 5, 35))
        val flags = mapOf("vista" to OpportunityFlag(1, "vista", seenAt = at(7, 9)))
        assertEquals(setOf("nova"), Novelty.newIds(listOf("velha", "nova", "vista", "sem-registro"), firstSeen, flags, since))
        assertEquals("1 nova", Novelty.label(1))
        assertEquals("8 novas", Novelty.label(8))
        assertNull(Novelty.label(0))

        val items = listOf(scored(opp("nova")), scored(opp("velha")))
        val g = LowAdherence.group(items, now, threshold = null, show = false, marks = ListMarks(newIds = setOf("nova")))
        assertEquals(1, g.newCount)
        assertTrue(g.windowSummary.endsWith("· 1 nova"))
        val onlyNew = LowAdherence.group(items, now, threshold = null, show = false, marks = ListMarks(newIds = setOf("nova"), onlyNew = true))
        assertEquals(listOf("nova"), onlyNew.items.map { it.opportunity.id })
    }

    @Test
    fun `filtro de periodo - hoje, proximos 7 e 30 dias, personalizado e publicacao`() {
        val today = opp("hoje", deadline = at(7, 18))
        val in5 = opp("5d", deadline = at(12, 9))
        val in20 = opp("20d", deadline = at(27, 9))
        val in40 = opp("40d", deadline = at(16, 9, mo = 11))
        assertTrue(PeriodFilter(PeriodPreset.TODAY).matches(today, now))
        assertFalse(PeriodFilter(PeriodPreset.TODAY).matches(in5, now))
        assertTrue(PeriodFilter(PeriodPreset.NEXT_7).matches(in5, now))
        assertFalse(PeriodFilter(PeriodPreset.NEXT_7).matches(in20, now))
        assertTrue(PeriodFilter(PeriodPreset.NEXT_30).matches(in20, now))
        assertFalse(PeriodFilter(PeriodPreset.NEXT_30).matches(in40, now))
        assertTrue(PeriodFilter().matches(in40, now))
        val custom = PeriodFilter(PeriodPreset.CUSTOM, customFrom = at(12, 0), customTo = at(27, 0))
        assertTrue("dia final inclusive", custom.matches(in20, now))
        assertTrue(custom.matches(in5, now))
        assertFalse(custom.matches(today, now))
        assertEquals("12/10–27/10", custom.label())
        val byPublication = PeriodFilter(PeriodPreset.TODAY, PeriodField.PUBLICATION)
        assertTrue(byPublication.matches(opp("pub", published = at(7, 8), deadline = at(30, 9)), now))
        assertEquals("Hoje (publicação)", byPublication.label())
        // Combina com "Mostrar dias anteriores": período personalizado do mês só restringe o que já está na lista.
        val items = listOf(scored(opp("ontem", deadline = at(6, 9))), scored(today), scored(in5))
        val period = PeriodFilter(PeriodPreset.CUSTOM, customFrom = at(6, 0), customTo = at(7, 0))
        assertEquals(listOf("hoje"), LowAdherence.group(items, now, null, false, marks = ListMarks(period = period)).items.map { it.opportunity.id })
        assertEquals(
            setOf("hoje", "ontem"),
            LowAdherence.group(items, now, null, false, showPrevious = true, marks = ListMarks(period = period)).items.map { it.opportunity.id }.toSet(),
        )
    }

    @Test
    fun `situacao oficial pelo texto e pelo id do PNCP com cor do selo`() {
        assertEquals(OfficialSituation.SUSPENSA, OfficialSituation.fromText("COMPRA SUSPENSA"))
        assertEquals(OfficialSituation.REVOGADA, OfficialSituation.fromText("Revogada"))
        assertEquals(OfficialSituation.ANULADA, OfficialSituation.fromText("Anulada"))
        assertEquals(OfficialSituation.CANCELADA, OfficialSituation.fromText("Cancelada"))
        assertEquals(OfficialSituation.DESERTA, OfficialSituation.fromText("Licitação deserta"))
        assertEquals(OfficialSituation.FRACASSADA, OfficialSituation.fromText("Fracassada"))
        assertNull(OfficialSituation.fromText("Divulgada no PNCP"))
        assertNull(OfficialSituation.fromText("Proposta até 13/10/2026 08:59"))
        assertNull(OfficialSituation.fromText(null))
        assertEquals(OfficialSituation.REVOGADA, OfficialSituation.of(2, null))
        assertEquals(OfficialSituation.ANULADA, OfficialSituation.of(3, null))
        assertEquals(OfficialSituation.SUSPENSA, OfficialSituation.of(4, null))
        assertNull(OfficialSituation.of(1, "Divulgada no PNCP"))
        assertEquals(SituationSeverity.RED, OfficialSituation.REVOGADA.severity)
        assertEquals(SituationSeverity.RED, OfficialSituation.CANCELADA.severity)
        assertEquals(SituationSeverity.AMBER, OfficialSituation.SUSPENSA.severity)
        assertEquals(SituationSeverity.GRAY, OfficialSituation.DESERTA.severity)
        assertEquals(OfficialSituation.ANULADA, OfficialSituation.parse("ANULADA"))
        assertNull(OfficialSituation.parse(null))
        assertEquals(OfficialSituation.SUSPENSA, OfficialSituation.fromKeywords(listOf("Pregão", "situação: Suspensa")))
    }

    @Test
    fun `ADIADA - data mudou em relacao ao cache, mantida enquanto a data nova vale`() {
        val old = opp("a", deadline = at(8, 10))
        val moved = Postponement.merge(old, opp("a", deadline = at(15, 9)))
        assertEquals(at(8, 10), moved.previousProposalDeadline)
        assertTrue(moved.postponed)
        assertEquals("ADIADA para 15/10 09:00", Postponement.label(moved))
        assertEquals("antes: 08/10 10:00", Postponement.beforeLabel(moved))
        // Próxima atualização com a mesma data: continua marcada.
        val again = Postponement.merge(moved, opp("a", deadline = at(15, 9)))
        assertEquals(at(8, 10), again.previousProposalDeadline)
        // Mesma data (diferença de segundos) ou sem registro salvo: não é adiamento.
        assertFalse(Postponement.merge(old, opp("a", deadline = at(8, 10) + 30_000)).postponed)
        assertFalse(Postponement.merge(null, opp("a")).postponed)
        // A fonte escreveu "adiada"/"remarcada".
        assertTrue(opp("b", keywords = listOf("Sessão remarcada")).postponed)
        assertNull(Postponement.label(opp("c")))
    }

    @Test
    fun `mudanca de fase - suspensa, revogada, adiada e resultado, uma vez por mudanca`() {
        val base = TenderStatusSnapshot(1, 1, "Divulgada no PNCP", at(10, 9), at(10, 9), false, at(6, 5))
        assertNull("primeira observação só grava", TenderPhaseRule.change(null, OfficialStatus("Suspensa", at(10, 9))))
        assertNull(TenderPhaseRule.change(base, OfficialStatus("Divulgada no PNCP", at(10, 9))))
        assertEquals(TenderPhase.SUSPENDED, TenderPhaseRule.change(base, OfficialStatus("Suspensa", at(10, 9)))!!.phase)
        assertEquals(TenderPhase.REVOKED, TenderPhaseRule.change(base, OfficialStatus("Revogada", at(10, 9)))!!.phase)
        assertEquals(TenderPhase.REVOKED, TenderPhaseRule.change(base, OfficialStatus("Anulada", at(10, 9)))!!.phase)
        assertEquals(TenderPhase.RESULT, TenderPhaseRule.change(base, OfficialStatus("Homologada", at(10, 9)))!!.phase)
        val postponed = TenderPhaseRule.change(base, OfficialStatus("Divulgada no PNCP", at(17, 9)))!!
        assertEquals(TenderPhase.POSTPONED, postponed.phase)
        assertEquals("propostas: 10/10 09:00 → 17/10 09:00", postponed.detail)
        // Já avisada (estado gravado = suspensa): não repete.
        assertNull(TenderPhaseRule.change(base.copy(situation = "Suspensa"), OfficialStatus("Suspensa", at(10, 9))))
        val (title, body) = TenderPhaseRule.message("90005/2026", "Prefeitura", postponed)
        assertEquals("Licitação 90005/2026 adiada", title)
        assertTrue(body.contains("17/10 09:00"))
    }
}
