package com.licitaia.core.data.repository

import com.licitaia.ai.api.AIProvider
import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.RelevanceItem
import com.licitaia.core.data.db.RelevanceScoreDao
import com.licitaia.core.data.db.RelevanceScoreEntity
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AiScoreUpdate
import com.licitaia.domain.model.AiScoringRequest
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.ScoreSource
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.network.ConnectivityMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.licitaia.core.ai.AiCompanyScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nota de relevância por IA com cache em Room (`relevance_scores`).
 *
 * - Só usa provedor REAL (OpenAI/Anthropic/Gemini/API personalizada configurados); com o MOCK não há nota por IA e a
 *   lista mostra a heurística (marcada como tal).
 * - Lotes de até [AIProvider.MAX_RELEVANCE_BATCH], um de cada vez (paralelismo 1), checando a rede antes de cada lote;
 *   cancelar a coleta interrompe entre lotes.
 * - A nota fica salva por (oportunidade, empresa, assinatura do radar): a mesma pergunta não é refeita.
 */
@Singleton
class AiRelevanceScorer @Inject constructor(
    private val dao: RelevanceScoreDao,
    private val gateway: AiGateway,
    private val connectivity: ConnectivityMonitor,
) {
    private var clock: () -> Long = System::currentTimeMillis

    internal constructor(dao: RelevanceScoreDao, gateway: AiGateway, connectivity: ConnectivityMonitor, clock: () -> Long) :
        this(dao, gateway, connectivity) {
        this.clock = clock
    }

    /** Provedor real configurado (não MOCK)? */
    suspend fun aiAvailable(): Boolean = runCatching { gateway.current().type != AiProviderType.MOCK }.getOrDefault(false)

    /** Aplica as notas por IA já salvas para esta assinatura (sem chamar a IA). */
    suspend fun applyCached(companyId: Long, signature: String, items: List<ScoredOpportunity>): List<ScoredOpportunity> {
        if (items.isEmpty()) return items
        val cached = runCatching {
            items.map { it.opportunity.id }.distinct().chunked(SQL_CHUNK)
                .flatMap { dao.find(companyId, signature, it) }
                .filter { clock() - it.createdAt <= TTL_MS }
                .associateBy { it.opportunityId }
        }.getOrDefault(emptyMap())
        if (cached.isEmpty()) return items
        return items.map { item ->
            cached[item.opportunity.id]?.let { item.copy(score = it.score, scoreSource = ScoreSource.AI, scoreReason = it.reason.ifBlank { null }) }
                ?: item
        }
    }

    /** Avalia os candidatos do pedido em lotes, salvando cada nota; falha/sem rede → emite [AiScoreUpdate.failure] e para. */
    fun rate(request: AiScoringRequest, company: Company): Flow<AiScoreUpdate> = rateInScope(request, company)
        .let { if (request.companyId > 0) it.flowOn(AiCompanyScope(request.companyId)) else it }

    private fun rateInScope(request: AiScoringRequest, company: Company): Flow<AiScoreUpdate> = flow {
        val pending = request.candidates.filter { it.scoreSource == ScoreSource.HEURISTIC }
        if (pending.isEmpty()) return@flow
        val provider: AIProvider = runCatching { gateway.current() }.getOrNull()
            ?.takeIf { it.type != AiProviderType.MOCK }
            ?: return@flow
        runCatching { dao.prune(clock() - TTL_MS) }
        for (batch in pending.chunked(AIProvider.MAX_RELEVANCE_BATCH)) {
            if (!connectivity.hasNetwork) {
                emit(AiScoreUpdate(emptyList(), failure = "Sem internet — notas por IA pausadas; os demais itens mostram a nota heurística."))
                return@flow
            }
            val items = batch.map {
                RelevanceItem(it.opportunity.id, it.opportunity.objectDescription, it.opportunity.agency, it.opportunity.modality.label)
            }
            val scores = try {
                provider.rateRelevance(company, request.radarHint, items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: "falha do provedor"
                emit(AiScoreUpdate(emptyList(), failure = "IA indisponível ($detail). Os demais itens mostram a nota heurística."))
                return@flow
            }
            val byId = scores.associateBy { it.id }
            val now = clock()
            val rated = batch.mapNotNull { item ->
                val s = byId[item.opportunity.id] ?: return@mapNotNull null
                item.copy(score = s.score.coerceIn(0, 100), scoreSource = ScoreSource.AI, scoreReason = s.reason.ifBlank { null })
            }
            if (rated.isNotEmpty()) {
                runCatching {
                    dao.upsertAll(
                        rated.map {
                            RelevanceScoreEntity(
                                opportunityId = it.opportunity.id, companyId = request.companyId,
                                radarSignature = request.radarSignature, score = it.score, reason = it.scoreReason.orEmpty(),
                                provider = provider.type.name, createdAt = now,
                            )
                        },
                    )
                }
            }
            emit(AiScoreUpdate(rated))
        }
    }

    internal companion object {
        /** Teto de candidatos avaliados por execução (tela). */
        const val MAX_PER_RUN = 60
        const val SQL_CHUNK = 500
        /** Notas antigas expiram (o objeto não muda, mas o modelo/critério pode mudar). */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
