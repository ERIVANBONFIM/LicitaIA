package com.licitaia.core.data.repository

import com.licitaia.ai.api.AIProvider
import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.AiProviderException
import com.licitaia.ai.api.RelevanceItem
import com.licitaia.ai.api.RelevanceScore
import com.licitaia.core.data.db.RelevanceScoreDao
import com.licitaia.core.data.db.RelevanceScoreEntity
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AiScoringRequest
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ScoreSource
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.Segment
import com.licitaia.domain.network.ConnectivityMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nota por IA: cache por assinatura de radar, lotes de 25 e falha explícita (nota fica heurística). */
class AiRelevanceScorerTest {
    private val company = Company(1, "ME Telecom", "ME", "27147548000115", Segment.TELECOM_ISP, "BA", "Salvador")
    private val now = 1_000_000_000L

    private class Online(value: Boolean) : ConnectivityMonitor {
        override val online: StateFlow<Boolean> = MutableStateFlow(value)
    }

    /** DAO em memória. */
    private class MemoryDao : RelevanceScoreDao {
        val rows = mutableMapOf<Triple<String, Long, String>, RelevanceScoreEntity>()
        override suspend fun find(companyId: Long, signature: String, ids: List<String>) =
            rows.values.filter { it.companyId == companyId && it.radarSignature == signature && it.opportunityId in ids }
        override suspend fun upsertAll(entities: List<RelevanceScoreEntity>) {
            entities.forEach { rows[Triple(it.opportunityId, it.companyId, it.radarSignature)] = it }
        }
        override suspend fun prune(olderThan: Long) {
            rows.values.removeAll { it.createdAt < olderThan }
        }
        override suspend fun deleteByCompany(companyId: Long) {
            rows.values.removeAll { it.companyId == companyId }
        }
    }

    private fun scored(i: Int, score: Int = 72) = ScoredOpportunity(
        Opportunity(
            id = "PNCP:$i", portal = Portal.COMPRAS_GOV, number = "$i/2026", agency = "Órgão $i",
            objectDescription = "Link dedicado $i", modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP,
            uf = "BA", city = "Salvador", estimatedValue = 1.0, publishedAt = 0, proposalDeadline = now + i, sessionAt = 0,
        ),
        score, interested = false,
    )

    private fun provider(type: AiProviderType = AiProviderType.OPENAI): AIProvider = mockk {
        every { this@mockk.type } returns type
        coEvery { rateRelevance(any(), any(), any()) } answers {
            thirdArg<List<RelevanceItem>>().map { RelevanceScore(it.id, 91, "Objeto é link dedicado") }
        }
    }

    private fun scorer(dao: RelevanceScoreDao, p: AIProvider, online: Boolean = true): AiRelevanceScorer {
        val gateway = mockk<AiGateway>()
        coEvery { gateway.current() } returns p
        return AiRelevanceScorer(dao, gateway, Online(online)) { now }
    }

    private fun request(items: List<ScoredOpportunity>) =
        AiScoringRequest(1, radarId = 7, minScore = 70, radarSignature = "sig", radarHint = "Telecom", candidates = items)

    @Test
    fun avaliaEmLotesDe25SalvaEReusaOCache() = runBlocking {
        val dao = MemoryDao()
        val p = provider()
        val s = scorer(dao, p)
        val items = (1..30).map { scored(it) }

        val updates = s.rate(request(items), company).toList()
        assertEquals(listOf(25, 5), updates.map { it.rated.size })
        assertTrue(updates.flatMap { it.rated }.all { it.scoreSource == ScoreSource.AI && it.score == 91 })
        coVerify(exactly = 2) { p.rateRelevance(company, "Telecom", any()) }
        assertEquals(30, dao.rows.size)
        assertEquals("OPENAI", dao.rows.values.first().provider)

        // Mesma assinatura: nota aplicada do cache, sem nova chamada.
        val cached = s.applyCached(1, "sig", items)
        assertTrue(cached.all { it.scoreSource == ScoreSource.AI && it.scoreReason == "Objeto é link dedicado" })
        assertTrue(s.rate(request(cached), company).toList().isEmpty())
        coVerify(exactly = 2) { p.rateRelevance(any(), any(), any()) }

        // Radar mudou (outra assinatura) ou outra empresa: sem reuso.
        assertTrue(s.applyCached(1, "outra", items).all { it.scoreSource == ScoreSource.HEURISTIC })
        assertTrue(s.applyCached(2, "sig", items).all { it.scoreSource == ScoreSource.HEURISTIC })
    }

    @Test
    fun mockNaoGeraNotaPorIa() = runBlocking {
        val s = scorer(MemoryDao(), provider(AiProviderType.MOCK))
        assertTrue(!s.aiAvailable())
        assertTrue(s.rate(request(listOf(scored(1))), company).toList().isEmpty())
    }

    @Test
    fun falhaDoProvedorOuSemRedeParaEMantemHeuristica() = runBlocking {
        val failing = mockk<AIProvider> {
            every { type } returns AiProviderType.OPENAI
            coEvery { rateRelevance(any(), any(), any()) } throws AiProviderException("HTTP 500")
        }
        val dao = MemoryDao()
        val updates = scorer(dao, failing).rate(request((1..30).map { scored(it) }), company).toList()
        assertEquals(1, updates.size)
        assertNotNull(updates.single().failure)
        assertTrue(updates.single().rated.isEmpty())
        assertTrue(dao.rows.isEmpty())

        val offline = scorer(MemoryDao(), provider(), online = false).rate(request(listOf(scored(1))), company).toList()
        assertTrue(offline.single().failure!!.contains("Sem internet"))
    }
}
