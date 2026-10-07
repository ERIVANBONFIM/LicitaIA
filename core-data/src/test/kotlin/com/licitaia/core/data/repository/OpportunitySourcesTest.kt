package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.HttpStatusFailure
import com.licitaia.connector.api.PortalConnector
import com.licitaia.connector.api.ScreenedListing
import com.licitaia.connector.api.ScreenedOpportunitySource
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
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.SourceDiagnostics
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

    private val future = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000

    private fun opp(id: String, portal: Portal, platform: String? = null, agency: String = "Órgão", deadline: Long = future) = Opportunity(
        id = id, portal = portal, number = "1/2026", agency = agency, objectDescription = "Link de internet",
        modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP, uf = "MG", city = "BH",
        estimatedValue = 1000.0, publishedAt = 0, proposalDeadline = deadline, sessionAt = deadline, platformName = platform,
    )

    // ------------------------------------------------------------ prazo de propostas

    @Test
    fun `registro do Compras gov br sem prazo herda o prazo do PNCP do mesmo numero de controle`() {
        val viaPncp = opp("PNCP:$CONTROL", Portal.COMPRAS_GOV, platform = "Compras.gov.br", deadline = 1_234_567L)
        val viaCompras = opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV, deadline = Opportunity.DEADLINE_UNKNOWN)
        val kept = OpportunityDeduplicator.dedupe(listOf(viaCompras, viaPncp)).single()
        assertEquals("COMPRAS_GOV:$CONTROL", kept.id)
        assertEquals(1_234_567L, kept.proposalDeadline)
        assertEquals(1_234_567L, kept.sessionAt)
    }

    @Test
    fun `prazo vencido e descartado e prazo nao informado e mantido`() {
        val now = 1_000_000L
        val closed = opp("PNCP:11111111000111-1-000001/2026", Portal.PNCP, deadline = now - 1)
        val open = opp("PNCP:22222222000122-1-000002/2026", Portal.PNCP, deadline = now + 1)
        val unknown = opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV, deadline = Opportunity.DEADLINE_UNKNOWN)
        assertEquals(listOf(open, unknown), OpportunityDeadlines.dropClosed(listOf(closed, open, unknown), now))
        assertFalse(unknown.hasProposalDeadline)
    }

    @Test
    fun `busca nao devolve licitacao encerrada nem do cache`() = runBlocking {
        val past = System.currentTimeMillis() - 20L * 24 * 60 * 60 * 1000
        val compras = connector(
            Portal.COMPRAS_GOV, setOf(Portal.COMPRAS_GOV),
            result = listOf(
                opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV, deadline = past),
                opp("COMPRAS_GOV:22222222000122-1-000002/2026", Portal.COMPRAS_GOV),
            ),
        )
        val (repo, _) = repository(listOf(compras))
        val ids = repo.search(1, OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV))).getOrThrow().map { it.opportunity.id }
        assertEquals(listOf("COMPRAS_GOV:22222222000122-1-000002/2026"), ids)

        val (offlineRepo, _) = repository(listOf(compras), online = false, cache = listOf(opp("PNCP:$CONTROL", Portal.PNCP, deadline = past)))
        assertTrue(offlineRepo.search(1, OpportunityFilter()).isFailure)
    }

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
        val connectivity = FakeConnectivity(online)
        val gateway = mockk<com.licitaia.ai.api.AiGateway>()
        val mockProvider = mockk<com.licitaia.ai.api.AIProvider>()
        every { mockProvider.type } returns com.licitaia.domain.model.AiProviderType.MOCK
        coEvery { gateway.current() } returns mockProvider
        val relevance = AiRelevanceScorer(mockk(relaxed = true), gateway, connectivity)
        return OpportunityRepositoryImpl(registry, opportunityDao, tenderDao, radarDao, companyDao, access, connectivity, relevance) to opportunityDao
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
    fun `so Compras gov com UF consulta tambem o PNCP com a UF e mostra as duas fontes`() = runBlocking {
        val pncp = connector(Portal.PNCP, ALL_VIA_PNCP, result = listOf(opp("PNCP:22222222000122-1-000002/2026", Portal.COMPRAS_GOV, platform = "Compras.gov.br")))
        val compras = connector(Portal.COMPRAS_GOV, setOf(Portal.COMPRAS_GOV), result = listOf(opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV)))
        val (repo, _) = repository(listOf(pncp, compras))

        val outcome = repo.searchWithSources(1, OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV), ufs = setOf("BA"))).getOrThrow()

        coVerify { pncp.listOpportunities(match { it.ufs == setOf("BA") && it.portals == setOf(Portal.COMPRAS_GOV) }) }
        coVerify { compras.listOpportunities(any()) }
        assertEquals(mapOf(Portal.PNCP to 1, Portal.COMPRAS_GOV to 1), outcome.sourceCounts)
        assertEquals("PNCP 1 · Compras.gov.br 1", outcome.sourceSummary)

        // PNCP falhando (ex.: 429 persistente) aparece na linha de fontes em vez de sumir.
        coEvery { pncp.listOpportunities(any()) } throws HttpFailure(429)
        val partial = repo.searchWithSources(1, OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV))).getOrThrow()
        assertEquals(setOf(Portal.PNCP), partial.failedSources)
        assertEquals("Compras.gov.br 1 · PNCP indisponível agora", partial.sourceSummary)
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

    // ------------------------------------------------------------ triagem + funil do Compras.gov.br

    @Test
    fun `fonte com triagem recebe o screen e a linha de fontes mostra lidas, candidatas e abertas`() = runBlocking {
        val pncp = connector(Portal.PNCP, ALL_VIA_PNCP, result = listOf(opp("PNCP:22222222000122-1-000002/2026", Portal.COMPRAS_GOV, platform = "Compras.gov.br")))
        val compras = mockk<PortalConnector>(relaxed = true, moreInterfaces = arrayOf(ScreenedOpportunitySource::class)) {
            every { portal } returns Portal.COMPRAS_GOV
            every { searchablePortals } returns setOf(Portal.COMPRAS_GOV)
            every { capabilities } returns caps(false)
        }
        val screened = compras as ScreenedOpportunitySource
        coEvery { screened.listScreened(any(), any()) } answers {
            val screen = secondArg<com.licitaia.connector.api.OpportunityScreen>()
            val all = listOf(
                opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV),
                opp("COMPRAS_GOV:55555555000155-1-000005/2026", Portal.COMPRAS_GOV).copy(objectDescription = "Aquisição de pneus"),
            )
            val kept = all.filter { screen.accept(it) }
            ScreenedListing(kept, SourceDiagnostics(read = 8000, candidates = 34, open = kept.size))
        }
        val (repo, _) = repository(listOf(pncp, compras))

        val outcome = repo.searchWithSources(1, OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV))).getOrThrow()

        coVerify(exactly = 0) { compras.listOpportunities(any()) }
        assertEquals("PNCP 1 · Compras.gov.br 8000 lidas · 34 candidatas · 1 aberta", outcome.sourceSummary)
        assertTrue(outcome.items.none { it.opportunity.objectDescription.contains("pneus") })
    }

    @Test
    fun `triagem do radar descarta palavras fora do radar e relevancia abaixo de 20`() {
        val company = com.licitaia.domain.model.Company(1, "ISP", "ISP", "00000000000191", Segment.TELECOM_ISP, "MG", "BH")
        val radar = Radar(
            id = 1, companyId = 1, name = "ISP", segment = Segment.TELECOM_ISP,
            keywords = listOf("internet", "link", "provedor", "fibra"), portals = listOf(Portal.COMPRAS_GOV), allPortals = false, minScore = 70,
        )
        val screen = CandidateScreens.forRadars(listOf(radar), company)
        assertTrue(screen.accept(opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV).copy(objectDescription = "Contratação de link dedicado de internet")))
        assertFalse(screen.accept(opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV).copy(objectDescription = "Aquisição de pneus", segment = Segment.PERSONALIZADO)))
        assertFalse(
            "internet só como meio + domínio alheio",
            screen.accept(opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV).copy(objectDescription = "Gestão de frota de veículos via internet", segment = Segment.PERSONALIZADO)),
        )
        val search = CandidateScreens.forSearch(OpportunityFilter(query = "pneus"), company, emptyList())
        assertTrue("texto digitado não sofre corte de relevância", search.accept(opp("COMPRAS_GOV:$CONTROL", Portal.COMPRAS_GOV).copy(objectDescription = "Aquisição de pneus")))
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
