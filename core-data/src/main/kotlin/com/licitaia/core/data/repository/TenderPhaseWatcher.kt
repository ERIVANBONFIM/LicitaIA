package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.OfficialStatusSource
import com.licitaia.core.data.db.OpportunityFlagDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.competition.TrackedTenderProvider
import com.licitaia.domain.model.OfficialSituation
import com.licitaia.domain.model.OfficialStatus
import com.licitaia.domain.model.TenderPhaseChange
import com.licitaia.domain.model.TenderPhaseRule
import com.licitaia.domain.model.TenderStatusSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conferência diária das licitações acompanhadas (interesse/análise/proposta e "minhas licitações" com licitação do app):
 * consulta a situação oficial (PNCP `situacaoCompra` + datas), grava o último estado em `tender_status_watch`, atualiza a
 * situação/datas da licitação e devolve as mudanças de fase (uma por mudança: o estado novo é gravado na hora).
 */
@Singleton
class TenderPhaseWatcher @Inject constructor(
    private val registry: ConnectorRegistry,
    private val tenderDao: TenderDao,
    private val flagDao: OpportunityFlagDao,
    private val providers: Set<@JvmSuppressWildcards TrackedTenderProvider>,
) {
    data class Notice(val tenderId: Long, val number: String, val agency: String, val change: TenderPhaseChange)

    suspend fun check(companyId: Long, now: Long = System.currentTimeMillis(), limit: Int = MAX_PER_RUN): List<Notice> = withContext(Dispatchers.IO) {
        val source = runCatching { registry.all() }.getOrDefault(emptyList())
            .filter { !it.capabilities.isMock }.filterIsInstance<OfficialStatusSource>().firstOrNull()
            ?: return@withContext emptyList()
        val tracked = providers.flatMap { p -> runCatching { p.trackedTenders(companyId) }.getOrDefault(emptyList()) }
            .filter { it.tenderId != null }
            .distinctBy { it.tenderId }
            .take(limit)
        val notices = ArrayList<Notice>()
        for (t in tracked) {
            val tenderId = t.tenderId ?: continue
            val tender = tenderDao.getById(tenderId)?.takeIf { it.companyId == companyId } ?: continue
            val status = try {
                source.officialStatus(t.controlNumber)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            val old = flagDao.watch(tenderId)?.toDomain()
            val result = PhaseCheck.apply(old, status, tender.proposalDeadline, tender.sessionAt, tenderId, companyId, now)
            flagDao.saveWatch(result.snapshot.toEntity())
            tenderDao.updateOfficialStatus(
                tenderId, result.situation?.name, result.snapshot.proposalDeadline, result.snapshot.sessionAt, now,
            )
            result.change?.let { notices += Notice(tenderId, tender.number, tender.agency, it) }
        }
        notices
    }

    private companion object {
        /** Teto por execução (o PNCP limita as consultas; o restante é conferido nos próximos dias). */
        const val MAX_PER_RUN = 40
    }
}

/** Passo puro da conferência (testável): novo estado gravado, situação para o selo e a mudança a avisar. */
internal object PhaseCheck {
    data class Result(val snapshot: TenderStatusSnapshot, val situation: OfficialSituation?, val change: TenderPhaseChange?)

    fun apply(
        old: TenderStatusSnapshot?,
        status: OfficialStatus,
        tenderDeadline: Long,
        tenderSession: Long,
        tenderId: Long,
        companyId: Long,
        now: Long,
    ): Result {
        val deadline = status.proposalDeadline.takeIf { it > 0L } ?: tenderDeadline
        // O PNCP não publica a sessão: acompanha o encerramento quando ela era igual a ele.
        val session = if (tenderSession == tenderDeadline || tenderSession <= 0L) deadline else tenderSession
        // Primeira conferência: compara com as datas que a licitação já tinha (adiamento desde o "Tenho interesse").
        val baseline = old ?: TenderStatusSnapshot(tenderId, companyId, null, tenderDeadline, tenderSession, false, 0L)
        val change = TenderPhaseRule.change(baseline, status.copy(proposalDeadline = deadline), session)
        val snapshot = TenderStatusSnapshot(tenderId, companyId, status.situation, deadline, session, status.hasResult, now)
        return Result(snapshot, OfficialSituation.fromText(status.situation), change)
    }
}
