package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.HttpStatusFailure
import com.licitaia.connector.api.PortalConnector
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.CompanyEntity
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.RadarDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.network.OfflineException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Fontes de busca por portal, deduplicação PNCP × Compras.gov.br, backoff e modo offline. */
class OpportunitySourcesTest {

    private fun opp(id: String, portal: Portal, platform: String? = null, agency: String = "Órgão") = Opportunity(
        id = id, portal = portal, number = "1/2026", agency = agency, objectDescription = "Link de internet",
        modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP, uf = "MG", city = "BH",
        estimatedValue = 1000.0, publishedAt = 0, proposalDeadline = 1, sessionAt = 1, platformName = platform,
    )

    private fun caps(mock: Boolean) = ConnectorCapabilities(true, true, false, false, false, false, mock, emptyList())

    private fun connector(portal: Portal, searchable: Set<Portal>, mock: Boolean = false, result: List<Opportunity> = emptyList()): PortalConnector =
        mockk(relaxed = true) {
            every { this@mockk.portal } returns portal
            every { searchablePortals } returns searchable
            every { capabilities } returns caps(mock)
            coEvery { listOpportunities(any()) } returns result
        }

    private val ALL_VIA_PNCP = setOf(Portal.PNCP, Portal.COMPRAS_GOV, Portal.LICITANET, Portal.BLL, Portal.PORTAL_COMPRAS_PUBLICAS)
    private val CONTROL = "20918579000183-1-000016/2025"

    // ------------------------------------------------------------ dedup

    @Test
    fun `mesmo numero de controle mantem o registro do conector Compras gov br`() {
        val viaPncp = opp("PNCP:$CONTROL", Portal.COMPRAS_GOV, platform = "Compras.gov.br")
        val viaCompras = opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV, agency = "Órgão (dados ricos)")
        val other = opp("PNCP:11111111000111-1-000001/2026", Portal.LICITANET, platform = "Licitanet")

        val result = OpportunityDeduplicator.dedupe(listOf(viaPncp, other, viaCompras))

        assertEquals(2, result.size)
        val kept = result.first()
        assertEquals("COMPRAS_GOV:$CONTROL", kept.id)
        assertEquals("Órgão (dados ricos)", kept.agency)
        assertEquals("Compras.gov.br", kept.platformName)
        assertEquals(other, result[1])
    }

    @Test
    fun `sem registro do Compras gov br fica o do PNCP com o portal classificado`() {
        val a = opp("PNCP:$CONTROL", Portal.PORTAL_COMPRAS_PUBLICAS, platform = "Portal de Compras Públicas")
        val b = opp("PNCP:$CONTROL", Portal.PORTAL_COMPRAS_PUBLICAS)
        val result = OpportunityDeduplicator.dedupe(listOf(a, b))
        assertEquals(listOf(a), result)
    }

    @Test
    fun `ids legados fora do padrao so deduplicam por igualdade`() {
        val legacy = opp("COMPRAS_GOV:123456-5-90001/2026", Portal.COMPRAS_GOV)
        val pncp = opp("PNCP:$CONTROL", Portal.PNCP)
        assertEquals(2, OpportunityDeduplicator.dedupe(listOf(legacy, pncp, legacy)).size)
    }

    // ------------------------------------------------------------ seleção de fontes

    @Test
    fun `Licitanet BLL e PCP sao buscados via PNCP e mocks sao ignorados`() {
        val pncp = connector(Portal.PNCP, ALL_VIA_PNCP)
        val compras = connector(Portal.COMPRAS_GOV, setOf(Portal.COMPRAS_GOV))
        val mockLicitanet = connector(Portal.LICITANET, setOf(Portal.LICITANET), mock = true)
        val all = listOf(pncp, compras, mockLicitanet)

        assertEquals(listOf(pncp), SearchSources.select(all, setOf(Portal.LICITANET)))
        assertEquals(listOf(pncp), SearchSources.select(all, setOf(Portal.BLL, Portal.PORTAL_COMPRAS_PUBLICAS)))
        assertEquals(listOf(pncp, compras), SearchSources.select(all, setOf(Portal.COMPRAS_GOV)))
        assertEquals(listOf(pncp), SearchSources.select(all, setOf(Portal.PNCP)))
        assertTrue(SearchSources.select(listOf(mockLicitanet), setOf(Portal.LICITANET)).isEmpty())
    }

    // ------------------------------------------------------------ repositório (filtro por portal)

    private class FakeConnectivity(online: Boolean) : ConnectivityMonitor {
        val state = MutableStateFlow(online)
        override val online: StateFlow<Boolean> = state
    }

    private fun repository(connectors: List<PortalConnector>, online: Boolean = true, cache: List<Opportunity> = emptyList()): Pair<OpportunityRepositoryImpl, OpportunityDao> {
        val registry = object : ConnectorRegistry {
            override fun get(portal: Portal) = connectors.first { it.portal == portal }
            override fun all() = connectors
        }
        val opportunityDao = mockk<OpportunityDao>(relaxed = true)
        coEvery { opportunityDao.getAll() } returns cache.map { it.toEntity(0) }
        val tenderDao = mockk<TenderDao>(relaxed = true)
        coEvery { tenderDao.opportunityIds(any()) } returns emptyList()
        val radarDao = mockk<RadarDao>(relaxed = true)
        coEvery { radarDao.getActive(any()) } returns emptyList()
        val companyDao = mockk<CompanyDao>()
        coEvery { companyDao.getById(any()) } returns CompanyEntity(1, "Empresa", "Empresa", "00000000000191", Segment.TELECOM_ISP, "MG", "BH", null)
        val access = mockk<RepositoryAccess>(relaxed = true)
        every { access.owns(any()) } returns true
        return OpportunityRepositoryImpl(registry, opportunityDao, tenderDao, radarDao, companyDao, access, FakeConnectivity(online)) to opportunityDao
    }

    @Test
    fun `filtro Compras gov traz o conector Compras gov br e as do PNCP classificadas, sem duplicar`() = runBlocking {
        val pncpResults = listOf(
            opp("PNCP:$CONTROL", Portal.COMPRAS_GOV, platform = "Compras.gov.br"),
            opp("PNCP:22222222000122-1-000002/2026", Portal.COMPRAS_GOV, platform = "Compras.gov.br"),
            opp("PNCP:33333333000133-1-000003/2026", Portal.LICITANET, platform = "Licitanet"),
        )
        val comprasResults = listOf(opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV))
        val pncp = connector(Portal.PNCP, ALL_VIA_PNCP, result = pncpResults)
        val compras = connector(Portal.COMPRAS_GOV, setOf(Portal.COMPRAS_GOV), result = comprasResults)
        val (repo, _) = repository(listOf(pncp, compras))

        val found = repo.search(1, OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV))).getOrThrow().map { it.opportunity }

        assertEquals(setOf("COMPRAS_GOV:$CONTROL", "PNCP:22222222000122-1-000002/2026"), found.map { it.id }.toSet())
        assertTrue(found.all { it.portal == Portal.COMPRAS_GOV })
    }

    @Test
    fun `filtro Licitanet devolve resultados reais via PNCP em vez de erro`() = runBlocking {
        val pncp = connector(
            Portal.PNCP, ALL_VIA_PNCP,
            result = listOf(
                opp("PNCP:33333333000133-1-000003/2026", Portal.LICITANET, platform = "Licitanet"),
                opp("PNCP:44444444000144-1-000004/2026", Portal.PNCP, platform = "Licitar Digital"),
            ),
        )
        val compras = connector(Portal.COMPRAS_GOV, setOf(Portal.COMPRAS_GOV))
        val (repo, _) = repository(listOf(pncp, compras, connector(Portal.LICITANET, setOf(Portal.LICITANET), mock = true)))

        val result = repo.search(1, OpportunityFilter(portals = setOf(Portal.LICITANET)))

        assertTrue(result.isSuccess)
        assertEquals(listOf("PNCP:33333333000133-1-000003/2026"), result.getOrThrow().map { it.opportunity.id })
        coVerify(exactly = 0) { compras.listOpportunities(any()) }
    }

    @Test
    fun `offline falha na hora sem chamar a rede e usa o cache quando existe`() = runBlocking {
        val pncp = connector(Portal.PNCP, ALL_VIA_PNCP)
        val (repo, _) = repository(listOf(pncp), online = false)
        val empty = repo.search(1, OpportunityFilter())
        assertTrue(empty.exceptionOrNull() is OfflineException)
        coVerify(exactly = 0) { pncp.listOpportunities(any()) }

        val cached = opp("PNCP:$CONTROL", Portal.BLL, platform = "BLL Compras")
        val (repoWithCache, _) = repository(listOf(pncp), online = false, cache = listOf(cached))
        assertEquals(listOf(cached.id), repoWithCache.search(1, OpportunityFilter(portals = setOf(Portal.BLL))).getOrThrow().map { it.opportunity.id })
    }

    // ------------------------------------------------------------ backoff

    private class HttpFailure(override val httpStatus: Int?) : IOException("HTTP $httpStatus"), HttpStatusFailure

    @Test
    fun `429 e 5xx geram backoff exponencial e sucesso zera`() {
        val b = SourceBackoff(baseMs = 1_000, maxMs = 5_000)
        assertTrue(b.onFailure(Portal.PNCP, HttpFailure(429), now = 0))
        assertEquals(1_000, b.remainingMs(Portal.PNCP, 0))
        assertTrue(b.onFailure(Portal.PNCP, HttpFailure(503), now = 0))
        assertEquals(2_000, b.remainingMs(Portal.PNCP, 0))
        b.onFailure(Portal.PNCP, HttpFailure(500), 0)
        b.onFailure(Portal.PNCP, HttpFailure(500), 0)
        assertEquals("teto", 5_000, b.remainingMs(Portal.PNCP, 0))
        assertEquals(0, b.remainingMs(Portal.COMPRAS_GOV, 0))
        b.onSuccess(Portal.PNCP)
        assertEquals(0, b.remainingMs(Portal.PNCP, 0))
    }

    @Test
    fun `falhas sem status ou 4xx comuns nao geram backoff`() {
        val b = SourceBackoff()
        assertFalse(b.onFailure(Portal.PNCP, IOException("sem rede"), 0))
        assertFalse(b.onFailure(Portal.PNCP, HttpFailure(404), 0))
        assertTrue("causa encadeada", b.onFailure(Portal.PNCP, IOException("wrap", HttpFailure(429)), 0))
    }

    @Test
    fun `fonte em backoff e pulada e a busca usa o cache`() = runBlocking {
        val pncp = connector(Portal.PNCP, ALL_VIA_PNCP)
        coEvery { pncp.listOpportunities(any()) } throws HttpFailure(429)
        val cached = opp("PNCP:$CONTROL", Portal.PNCP)
        val (repo, _) = repository(listOf(pncp), cache = listOf(cached))

        assertEquals(listOf(cached.id), repo.search(1, OpportunityFilter()).getOrThrow().map { it.opportunity.id })
        assertEquals(listOf(cached.id), repo.search(1, OpportunityFilter()).getOrThrow().map { it.opportunity.id })
        coVerify(exactly = 1) { pncp.listOpportunities(any()) }
    }
}
