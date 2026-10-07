package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.PortalConnector
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.CompanyEntity
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.OpportunityFlagDao
import com.licitaia.core.data.db.RadarDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.OfficialSituation
import com.licitaia.domain.model.OfficialStatus
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ProposalWindows
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.TenderPhase
import com.licitaia.domain.model.TenderStatusSnapshot
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Repositório: dias anteriores do mês à parte (`previousDays`), pesquisa por texto também no cache inteiro, ADIADA
 * detectada contra o cache, 1ª vez no cache gravada e descartadas fora dos avisos; conferência de fase (PhaseCheck).
 */
class PreviousDaysSearchTest {
    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun opp(id: String, deadline: Long) = Opportunity(
        id = id, portal = Portal.COMPRAS_GOV, number = "1/2026", agency = "Órgão", objectDescription = "Link de internet",
        modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP, uf = "MG", city = "BH",
        estimatedValue = 1000.0, publishedAt = ProposalWindows.monthStart(now), proposalDeadline = deadline, sessionAt = deadline,
    )

    private val open = opp("COMPRAS_GOV:11111111000111-1-000001/2026", now + 10 * day)
    /** Encerrada ontem (dia anterior do mês atual): só existe no cache. */
    private val yesterday = opp("COMPRAS_GOV:22222222000122-1-000002/2026", ProposalWindows.dayStart(now) - day / 2)

    private val radar = Radar(id = 7, companyId = 1, name = "Internet", segment = Segment.TELECOM_ISP, keywords = listOf("internet"))

    private class FakeConnectivity : ConnectivityMonitor {
        override val online: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private val flagDao = mockk<OpportunityFlagDao>(relaxed = true)

    private fun repository(savedOpen: Opportunity? = null): OpportunityRepositoryImpl {
        val connector = mockk<PortalConnector>(relaxed = true) {
            every { portal } returns Portal.COMPRAS_GOV
            every { searchablePortals } returns setOf(Portal.COMPRAS_GOV)
            every { capabilities } returns ConnectorCapabilities(true, true, false, false, false, false, false, emptyList())
            coEvery { listOpportunities(any()) } answers { listOf(open).filter { OpportunityFilterMatcher.matches(firstArg(), it) } }
        }
        val registry = object : ConnectorRegistry {
            override fun get(portal: Portal) = connector
            override fun all() = listOf(connector)
        }
        val opportunityDao = mockk<OpportunityDao>(relaxed = true)
        coEvery { opportunityDao.getAll() } returns listOf(yesterday.toEntity(now))
        coEvery { opportunityDao.getByIds(any()) } returns listOfNotNull(savedOpen?.toEntity(now))
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
        return OpportunityRepositoryImpl(registry, opportunityDao, tenderDao, radarDao, companyDao, access, connectivity, relevance, flagDao)
    }

    private fun assumeYesterdayInMonth() = assumeTrue(
        "no 1º dia do mês não há dia anterior no mês atual",
        ProposalWindows.dayStart(now) > ProposalWindows.monthStart(now),
    )

    @Test
    fun `pesquisa por texto procura tambem nos dias anteriores do cache`() = runBlocking {
        assumeYesterdayInMonth()
        val repo = repository()
        val typed = repo.searchWithSources(1, OpportunityFilter(query = "internet")).getOrThrow()
        assertEquals(listOf(open.id), typed.items.map { it.opportunity.id })
        assertEquals(listOf(yesterday.id), typed.previousDays.map { it.opportunity.id })
        // Sem texto, a consulta às fontes não mistura o cache (só o que a fonte trouxe).
        val general = repo.searchWithSources(1, OpportunityFilter()).getOrThrow()
        assertTrue(general.previousDays.isEmpty())
        // Abrir a tela (só o salvo): os de dias anteriores vêm à parte; a tela decide mostrá-los (chip).
        val cached = repo.searchCached(1, OpportunityFilter()).getOrThrow()
        assertTrue(cached.items.isEmpty())
        assertEquals(listOf(yesterday.id), cached.previousDays.map { it.opportunity.id })
    }

    @Test
    fun `data diferente da salva marca ADIADA e grava a 1a vez no cache`() = runBlocking {
        val before = open.copy(proposalDeadline = now + 3 * day, sessionAt = now + 3 * day)
        val outcome = repository(savedOpen = before).searchWithSources(1, OpportunityFilter()).getOrThrow()
        val item = outcome.items.single().opportunity
        assertEquals(before.proposalDeadline, item.previousProposalDeadline)
        assertTrue(item.postponed)
        coVerify { flagDao.insertFirstSeen(match { rows -> rows.any { it.opportunityId == open.id } }) }
    }

    @Test
    fun `descartadas nao geram aviso do radar`() = runBlocking {
        coEvery { flagDao.discardedIds(1) } returns emptyList()
        assertEquals(listOf(open.id), repository().runRadarWithAi(radar.id, aiLimit = 0).getOrThrow().map { it.opportunity.id })
        coEvery { flagDao.discardedIds(1) } returns listOf(open.id)
        assertTrue(repository().runRadarWithAi(radar.id, aiLimit = 0).getOrThrow().isEmpty())
    }

    @Test
    fun `conferencia de fase compara com as datas da licitacao e grava o novo estado`() {
        val deadline = now + 5 * day
        // 1ª conferência: adiada desde o "Tenho interesse".
        val first = PhaseCheck.apply(null, OfficialStatus("Divulgada no PNCP", deadline + day), deadline, deadline, 10, 1, now)
        assertEquals(TenderPhase.POSTPONED, first.change!!.phase)
        assertEquals(deadline + day, first.snapshot.proposalDeadline)
        assertEquals("a sessão acompanha o encerramento", deadline + day, first.snapshot.sessionAt)
        assertNull(first.situation)
        // Mesma situação/data na próxima: nada a avisar.
        assertNull(PhaseCheck.apply(first.snapshot, OfficialStatus("Divulgada no PNCP", deadline + day), deadline + day, deadline + day, 10, 1, now).change)
        // Revogada: avisa e o selo fica vermelho.
        val revoked = PhaseCheck.apply(first.snapshot, OfficialStatus("Revogada", deadline + day), deadline + day, deadline + day, 10, 1, now)
        assertEquals(TenderPhase.REVOKED, revoked.change!!.phase)
        assertEquals(OfficialSituation.REVOGADA, revoked.situation)
        val snapshot: TenderStatusSnapshot = revoked.snapshot
        assertEquals("Revogada", snapshot.situation)
        // Sem data na fonte: mantém a da licitação.
        assertEquals(deadline, PhaseCheck.apply(null, OfficialStatus("Suspensa"), deadline, deadline, 11, 1, now).snapshot.proposalDeadline)
    }
}
