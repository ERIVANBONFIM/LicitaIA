package com.licitaia.app.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.licitaia.app.RadarAlerts
import com.licitaia.connector.api.SourceSyncPolicy
import com.licitaia.core.data.repository.ListingMaintenance
import com.licitaia.core.data.settings.DailySyncPrefs
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.sync.DailySyncSchedule
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sincronização COMPLETA diária (05:30 ou recuperação ao abrir o app), em segundo plano:
 * 1. busca geral com [SourceSyncPolicy.DAILY_FULL]: varredura completa do Compras.gov.br (cache persistente), listagem
 *    do PNCP e enriquecimento de prazos no PNCP (até [ENRICH_PASSES] rodadas, respeitando o limite do PNCP);
 * 2. radares ativos com notas por IA ([RadarAlerts], sem um aviso por radar);
 * 3. aviso discreto único "N licitações novas hoje" (canal Radar do app);
 * 4. limpeza do cache local e reagendamento do próximo dia (também quando falha).
 * Sem sessão lembrada (usuário saiu) ou conta de demonstração: só limpeza e reagendamento.
 */
@HiltWorker
class DailySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val auth: AuthRepository,
    private val opportunities: OpportunityRepository,
    private val radarAlerts: RadarAlerts,
    private val maintenance: ListingMaintenance,
    private val prefs: DailySyncPrefs,
    private val controller: DailySyncController,
    private val notifier: AppNotifier,
    private val phases: com.licitaia.core.data.repository.TenderPhaseWatcher,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        prefs.setRunning(true)
        try {
            val session = auth.session.value ?: auth.restoreSession()
            if (session == null || session.user.demo) {
                runCatching { controller.runCleanup() }
                return Result.success()
            }
            val company = session.activeCompany.id
            val before = maintenance.cachedIds()
            val fresh = HashSet<String>()
            val ok = withTimeoutOrNull(RUN_BUDGET_MS) {
                withContext(SourceSyncPolicy.DAILY_FULL) {
                    // Busca geral: completa + PNCP + prazos. As rodadas seguintes só leem o cache e consultam mais prazos.
                    var pass = 0
                    while (pass < ENRICH_PASSES && !isStopped) {
                        val outcome = opportunities.searchWithSources(company, OpportunityFilter()).getOrThrow()
                        fresh += outcome.items.map { it.opportunity.id }.filter { it !in before }
                        pass++
                        val unknown = outcome.sourceDiagnostics.values.sumOf { it.unknownDeadline }
                        if (unknown == 0 || outcome.fromCache) break
                    }
                    // Radares com notas por IA (os ids vistos evitam o aviso repetido do Worker de 3 h).
                    val radars = radarAlerts.check(session, company, notify = false) { isStopped }
                    fresh += radars.found.filter { it !in before }
                }
                true
            } ?: false
            if (!ok) return retryOrFail()
            // Mudança de fase das licitações acompanhadas (suspensa, revogada/anulada, adiada, resultado): um aviso por
            // mudança, que abre a licitação. Falha aqui não derruba a atualização.
            if (!isStopped) runCatching { notifyPhaseChanges(company, session) }
            val now = System.currentTimeMillis()
            prefs.markCompleted(now)
            runCatching { controller.runCleanup(now) }
            // Primeira carga (cache vazio): tudo seria "novo"; o aviso fica para os dias seguintes.
            if (fresh.isNotEmpty() && before.isNotEmpty() && auth.session.value == session) {
                val n = fresh.size
                runCatching {
                    notifier.notify(
                        NotificationCategory.RADAR,
                        if (n == 1) "1 licitação nova hoje" else "$n licitações novas hoje",
                        "Atualização automática concluída ${DailySyncSchedule.whenLabel(now, now)}. Abra Buscar Licitações para ver.",
                        companyId = company,
                    )
                }
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return retryOrFail()
        } finally {
            prefs.setRunning(false)
            // Próximo dia agendado sempre (sucesso, falha ou retentativa).
            runCatching { controller.reschedule() }
        }
    }

    private suspend fun notifyPhaseChanges(company: Long, session: com.licitaia.domain.model.AuthSession) {
        for (notice in phases.check(company)) {
            if (auth.session.value != session) break
            val (title, body) = com.licitaia.domain.model.TenderPhaseRule.message(notice.number, notice.agency, notice.change)
            runCatching {
                notifier.notify(
                    NotificationCategory.RADAR, title, body,
                    critical = notice.change.phase != com.licitaia.domain.model.TenderPhase.RESULT,
                    route = com.licitaia.core.ui.nav.Routes.tender(notice.tenderId),
                    companyId = company,
                )
            }
        }
    }

    /** Falha (sem rede/limite das fontes): WorkManager tenta de novo com backoff; a recuperação ao abrir cobre o resto. */
    private fun retryOrFail(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    /** Expedited em Android < 12 roda como serviço em primeiro plano: notificação discreta enquanto sincroniza. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Atualização diária de licitações", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Aviso enquanto o LicitaIA baixa as licitações do dia"
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Atualizando licitações…")
            .setContentText("Compras.gov.br e PNCP")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(FOREGROUND_ID, notification)
    }

    private companion object {
        /** Abaixo do limite de 10 min do WorkManager. */
        const val RUN_BUDGET_MS = 9L * 60 * 1000
        /** Rodadas da busca geral para consultar prazos no PNCP (cada uma limitada pelo conector). */
        const val ENRICH_PASSES = 3
        const val MAX_ATTEMPTS = 3
        const val CHANNEL_ID = "daily_sync"
        const val FOREGROUND_ID = 5530
    }
}
