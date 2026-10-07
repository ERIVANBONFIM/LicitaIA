package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.PortalConnector
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.CompanyEntity
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.RadarDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.SearchOutcome
import com.licitaia.domain.model.Segment
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import com.licitaia.domain.scoring.RadarMatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dispensas sem disputa (contratação direta): ocultas por padrão na Busca, nos Radares e no Worker. */
class NoDisputeFilterTest {

    private val future = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000

    private fun opp(id: String, noDispute: Boolean, deadline: Long = if (noDispute) Opportunity.DEADLINE_UNKNOWN else future) = Opportunity(
        id = id, portal = Portal.COMPRAS_GOV, number = "1/2026", agency = "Órgão", objectDescription = "Link de internet",
        modality = Modality.DISPENSA_ELETRONICA, segment = Segment.TELECOM_ISP, uf = "MG", city = "BH",
        // Dispensa sem disputa não tem data de proposta: o "dia" dela é a publicação (hoje → de hoje em diante).
        estimatedValue = 1000.0, publishedAt = System.currentTimeMillis(), proposalDeadline = deadline, sessionAt = deadline, noDispute = noDispute,
    )

    private val direct = opp("COMPRAS_GOV:11111111000111-1-000001/2026", noDispute = true)
    private val disputed = opp("COMPRAS_GOV:22222222000122-1-000002/2026", noDispute = false)

    private val radar = Radar(id = 7, companyId = 1, name = "Internet", segment = Segment.TELECOM_ISP, keywords = listOf("internet"))

    @Test
    fun `busca mostra dispensas sem disputa por padrao e o radar respeita a propria opcao`() {
        assertTrue(OpportunityFilter().showNoDispute)
        assertFalse(radar.showNoDispute)
        assertTrue(OpportunityFilterMatcher.matches(OpportunityFilter(), direct))
        assertTrue(OpportunityFilterMatcher.matches(OpportunityFilter(), disputed))
        assertFalse(OpportunityFilterMatcher.matches(OpportunityFilter(showNoDispute = false), direct))
        assertFalse(RadarMatcher.matches(radar, direct, "MG"))
        assertTrue(RadarMatcher.matches(radar, disputed, "MG"))
        assertTrue(RadarMatcher.matches(radar.copy(showNoDispute = true), direct, "MG"))
    }

    @Test
    fun `split conta as ocultas so entre as que casam com o filtro`() {
        val other = direct.copy(id = "COMPRAS_GOV:33333333000133-1-000003/2026", uf = "SP")
        val filter = OpportunityFilter(ufs = setOf("MG"), showNoDispute = true)
        val hidden = NoDisputeVisibility.split(listOf(direct, disputed, other), show = false) { OpportunityFilterMatcher.matches(filter, it) }
        assertEquals(listOf(disputed), hidden.visible)
        assertEquals(1, hidden.hidden)
        val shown = NoDisputeVisibility.split(listOf(direct, disputed, other), show = true) { OpportunityFilterMatcher.matches(filter, it) }
        assertEquals(listOf(direct, disputed), shown.visible)
        assertEquals(0, shown.hidden)
    }

    @Test
    fun `linha de fontes mostra as dispensas sem disputa ocultas`() {
        val outcome = SearchOutcome(emptyList(), sourceCounts = mapOf(Portal.PNCP to 10), hiddenNoDispute = 5)
        assertEquals("PNCP 10 · 5 dispensas sem disputa ocultas", outcome.sourceSummary)
        assertEquals("PNCP 10 · 1 dispensa sem disputa oculta", outcome.copy(hiddenNoDispute = 1).sourceSummary)
        assertEquals("PNCP 10", outcome.copy(hiddenNoDispute = 0).sourceSummary)
        assertNull(SearchOutcome(emptyList()).sourceSummary)
    }

    @Test
    fun `prazo herdado do PNCP desfaz a marcacao sem disputa`() {
        val viaCompras = direct
        val viaPncp = disputed.copy(id = "PNCP:11111111000111-1-000001/2026")
        val kept = OpportunityDeduplicator.dedupe(listOf(viaCompras, viaPncp)).single()
        assertEquals(viaCompras.id, kept.id)
        assertFalse(kept.noDispute)
        assertEquals(future, kept.proposalDeadline)
    }

    @Test
    fun `marcacao e opcao do radar sobrevivem ao cache Room`() {
        assertTrue(direct.toEntity(0).toDomain().noDispute)
        assertTrue(radar.copy(showNoDispute = true).toEntity().toDomain().showNoDispute)
        assertFalse(radar.toEntity().toDomain().showNoDispute)
    }

    // ------------------------------------------------------------ repositório (Busca, Radar e Worker)

    private class FakeConnectivity : ConnectivityMonitor {
        override val online: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private val received = slot<OpportunityFilter>()

    private fun repository(radar: Radar): OpportunityRepositoryImpl {
        val connector = mockk<PortalConnector>(relaxed = true) {
            every { portal } returns Portal.COMPRAS_GOV
            every { searchablePortals } returns setOf(Portal.COMPRAS_GOV)
            every { capabilities } returns ConnectorCapabilities(true, true, false, false, false, false, false, emptyList())
            coEvery { listOpportunities(capture(received)) } answers {
                listOf(direct, disputed).filter { OpportunityFilterMatcher.matches(received.captured, it) }
            }
        }
        val registry = object : ConnectorRegistry {
            override fun get(portal: Portal) = connector
            override fun all() = listOf(connector)
        }
        val opportunityDao = mockk<OpportunityDao>(relaxed = true)
        coEvery { opportunityDao.getAll() } returns emptyList()
        val tenderDao = mockk<TenderDao>(relaxed = true)
        coEvery { tenderDao.opportunityIds(any()) } returns emptyList()
        val radarDao = mockk<RadarDao>(relaxed = true)
        coEvery { radarDao.getActive(any()) } returns emptyList()
        coEvery { radarDao.getById(radar.id) } returns radar.toEntity()
        val companyDao = mockk<CompanyDao>()
        coEvery { companyDao.getById(any()) } returns CompanyEntity(1, "Empresa", "Empresa", "00000000000191", Segment.TELECOM_ISP, "MG", "BH", null)
        val access = mockk<RepositoryAccess>(relaxed = true)
        every { access.owns(any()) } returns true
        val connectivity = FakeConnectivity()
        val gateway = mockk<com.licitaia.ai.api.AiGateway>()
        val mockProvider = mockk<com.licitaia.ai.api.AIProvider>()
        every { mockProvider.type } returns com.licitaia.domain.model.AiProviderType.MOCK
        coEvery { gateway.current() } returns mockProvider
        val relevance = AiRelevanceScorer(mockk(relaxed = true), gateway, connectivity)
        return OpportunityRepositoryImpl(registry, opportunityDao, tenderDao, radarDao, companyDao, access, connectivity, relevance)
    }

    @Test
    fun `busca padrao mostra as dispensas sem disputa e o filtro desligado oculta e conta`() = runBlocking {
        val repo = repository(radar)
        val shown = repo.searchWithSources(1, OpportunityFilter()).getOrThrow()
        assertEquals(setOf(direct.id, disputed.id), shown.items.map { it.opportunity.id }.toSet())
        assertEquals(0, shown.hiddenNoDispute)

        val hidden = repo.searchWithSources(1, OpportunityFilter(showNoDispute = false)).getOrThrow()
        assertEquals(listOf(disputed.id), hidden.items.map { it.opportunity.id })
        assertEquals(1, hidden.hiddenNoDispute)
        assertTrue(hidden.sourceSummary!!.endsWith("1 dispensa sem disputa oculta"))
        // A fonte recebe o pedido com as dispensas incluídas (para contar); o filtro do usuário é aplicado depois.
        assertTrue(received.captured.showNoDispute)
    }

    @Test
    fun `radar e Worker respeitam a opcao do radar`() = runBlocking {
        val hiding = repository(radar)
        val outcome = hiding.runRadarWithSources(radar.id).getOrThrow()
        assertEquals(listOf(disputed.id), outcome.items.map { it.opportunity.id })
        assertEquals(1, outcome.hiddenNoDispute)
        // Caminho do PersonalAlertsWorker (runRadarWithAi): sem notificação de dispensa sem disputa.
        assertEquals(listOf(disputed.id), hiding.runRadarWithAi(radar.id, aiLimit = 5).getOrThrow().map { it.opportunity.id })

        val showing = repository(radar.copy(showNoDispute = true))
        val all = showing.runRadarWithAi(radar.id, aiLimit = 5).getOrThrow().map { it.opportunity.id }.toSet()
        assertEquals(setOf(direct.id, disputed.id), all)
        assertEquals(0, showing.runRadarWithSources(radar.id).getOrThrow().hiddenNoDispute)
    }
}
