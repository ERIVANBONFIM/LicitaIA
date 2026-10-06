package com.licitaia.app

import android.content.Context
import android.content.SharedPreferences
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.repository.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Best-effort public radar and document alerts, never operates authenticated portals.
 *
 * Dois agendamentos periódicos independentes (o mesmo Worker, selecionado pelo input [KEY_KIND]):
 * - [KIND_DOCUMENTS]: vencimento de documentos — dados locais (Room), SEM exigência de rede;
 * - [KIND_RADARS]: radares públicos (PNCP) — exige [NetworkType.CONNECTED].
 * Deduplicação (um aviso de documentos por dia/empresa; ids de oportunidades já vistas) e a
 * "sessão lembrada" (restoreSession) são mantidas em SharedPreferences "personal_alerts".
 */
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
        // Agendamento antigo (sem input) continua cobrindo os dois tipos até ser substituído.
        val kind = inputData.getString(KEY_KIND)
        return try {
            if (kind == null || kind == KIND_DOCUMENTS) checkDocuments(session, company, prefs)
            if (kind == null || kind == KIND_RADARS) checkRadars(session, company, prefs)
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }

    /** Documentos vencidos/vencendo: leitura local, um aviso por dia por empresa. */
    private suspend fun checkDocuments(session: AuthSession, company: Long, prefs: SharedPreferences) {
        val today = System.currentTimeMillis() / 86_400_000
        val key = "documents:$company"
        if (prefs.getLong(key, -1) == today) return
        val count = documents.observeExpiringCount(company).first()
        if (count > 0 && auth.session.value == session) notifier.notify(
            NotificationCategory.DOCUMENTOS, "Documentos exigem atenção", "$count documento(s) vencido(s) ou vencendo em até 30 dias.", companyId = company,
        )
        prefs.edit().putLong(key, today).apply()
    }

    /** Radares ativos: consulta pública ao PNCP; só avisa resultados nunca vistos. */
    private suspend fun checkRadars(session: AuthSession, company: Long, prefs: SharedPreferences) {
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
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KIND_DOCUMENTS = "documents"
        private const val KIND_RADARS = "radars"
        private const val WORK_RADARS = "personal-public-alerts"
        private const val WORK_DOCUMENTS = "personal-document-alerts"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            // Radares: precisam de rede. UPDATE substitui o agendamento antigo (sem input) mantendo o período.
            val radars = PeriodicWorkRequestBuilder<PersonalAlertsWorker>(6, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_KIND to KIND_RADARS))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_RADARS, ExistingPeriodicWorkPolicy.UPDATE, radars)
            // Documentos: só dados locais — sem constraint de rede, para avisar mesmo offline.
            val documents = PeriodicWorkRequestBuilder<PersonalAlertsWorker>(6, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_KIND to KIND_DOCUMENTS))
                .build()
            manager.enqueueUniquePeriodicWork(WORK_DOCUMENTS, ExistingPeriodicWorkPolicy.KEEP, documents)
        }
    }
}
