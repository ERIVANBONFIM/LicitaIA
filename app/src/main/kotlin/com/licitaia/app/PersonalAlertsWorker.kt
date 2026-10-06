package com.licitaia.app

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.repository.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Best-effort public radar and document alerts, never operates authenticated portals. */
@HiltWorker
class PersonalAlertsWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val auth: AuthRepository,
    private val radars: RadarRepository,
    private val opportunities: OpportunityRepository,
    private val documents: DocumentRepository,
    private val notifier: AppNotifier,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val session = auth.session.value ?: auth.restoreSession() ?: return Result.success()
        if (session.user.demo) return Result.success()
        val company = session.activeCompany.id
        val prefs = applicationContext.getSharedPreferences("personal_alerts", Context.MODE_PRIVATE)
        return try {
            val today = System.currentTimeMillis() / 86_400_000
            val key = "documents:$company"
            if (prefs.getLong(key, -1) != today) {
                val count = documents.observeExpiringCount(company).first()
                if (count > 0 && auth.session.value == session) notifier.notify(
                    NotificationCategory.DOCUMENTOS, "Documentos exigem atenção", "$count documento(s) vencido(s) ou vencendo em até 30 dias.", companyId = company,
                )
                prefs.edit().putLong(key, today).apply()
            }
            for (radar in radars.observeRadars(company).first().filter { it.active }) {
                if (isStopped || auth.session.value != session) break
                val found = opportunities.runRadar(radar.id).getOrThrow()
                val seenKey = "radar:${company}:${radar.id}"
                val previous = prefs.getStringSet(seenKey, emptySet()).orEmpty()
                val ids = found.map { it.opportunity.id }.toSet()
                val fresh = ids - previous
                if (fresh.isNotEmpty() && auth.session.value == session) notifier.notify(
                    NotificationCategory.RADAR, "Novas oportunidades no PNCP", "${fresh.size} resultado(s) novo(s) para ${radar.name}. Abra o radar para revisar.", companyId = company,
                )
                prefs.edit().putStringSet(seenKey, (ids + previous).take(2000).toSet()).apply()
            }
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PersonalAlertsWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("personal-public-alerts", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
