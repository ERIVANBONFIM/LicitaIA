package com.licitaia.app

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.licitaia.domain.competition.CompetitionResultsSync
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.sync.DailySyncSchedule
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Resultados públicos da Concorrência (PNCP), 1x por dia por volta das 06:00 (Brasília): consulta o resultado das
 * licitações da empresa e a base "Concorrentes do meu segmento". Leve: só consulta pública, com fila espaçada anti-429
 * no conector; não roda na empresa de demonstração. Avisa quando entram resultados novos da empresa.
 */
@HiltWorker
class CompetitionResultsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val auth: AuthRepository,
    private val sync: CompetitionResultsSync,
    private val notifier: AppNotifier,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val session = auth.session.value ?: auth.restoreSession() ?: return Result.success()
        if (session.user.demo || session.activeCompany.demo) return Result.success()
        val companyId = session.activeCompany.id
        // Janela de 20 h: tolera o atraso natural do WorkManager sem rodar duas vezes no mesmo dia.
        val result = sync.refreshIfDue(companyId, minIntervalMs = MIN_INTERVAL_MS) ?: return Result.success()
        return result.fold(
            onSuccess = { report ->
                if (report.imported > 0 && auth.session.value?.activeCompany?.id == companyId) {
                    notifier.notify(
                        NotificationCategory.GERAL,
                        "Novos resultados de licitações",
                        "${report.summary}. Abra Concorrência para ver vencedores e valores homologados.",
                        route = Routes.COMPETITION, companyId = companyId,
                    )
                }
                Result.success()
            },
            onFailure = { if (runAttemptCount < 2) Result.retry() else Result.success() },
        )
    }

    companion object {
        private const val WORK_NAME = "competition-public-results"
        private const val MIN_INTERVAL_MS = 20L * 60 * 60 * 1000
        private const val HOUR = 6

        fun schedule(context: Context) {
            val now = System.currentTimeMillis()
            val delay = (DailySyncSchedule.nextRun(now, HOUR, 0) - now).coerceAtLeast(0L)
            val request = PeriodicWorkRequestBuilder<CompetitionResultsWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            // KEEP: o horário (06:00) é fixado no primeiro agendamento; reabrir o app não desloca a execução.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
