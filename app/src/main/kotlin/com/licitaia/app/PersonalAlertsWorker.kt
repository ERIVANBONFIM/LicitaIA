package com.licitaia.app

import android.content.Context
import android.content.SharedPreferences
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.licitaia.domain.documents.DocumentValidity
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.repository.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.licitaia.connector.api.SourceSyncPolicy
import com.licitaia.domain.sync.ForegroundListingRefresh
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Best-effort public radar and document alerts, never operates authenticated portals.
 *
 * Dois agendamentos periódicos independentes (o mesmo Worker, selecionado pelo input [KEY_KIND]):
 * - [KIND_DOCUMENTS]: vencimento de documentos — dados locais (Room), SEM exigência de rede;
 * - [KIND_RADARS]: radares públicos (PNCP e Compras.gov.br, mesma lógica de busca/dedup/portais do app) a cada
 *   [RADAR_INTERVAL_HOURS] h — exige [NetworkType.CONNECTED] e bateria não baixa. Leve: roda com
 *   [SourceSyncPolicy.INCREMENTAL_ONLY] (o Compras.gov.br só baixa as publicações desde a última sincronização; a
 *   varredura completa da janela fica para a atualização diária das 05:30 — [DailySyncWorker]; antes da primeira
 *   completa o Worker não sincroniza o Compras.gov.br nem grava marca), com tempo máximo por execução, e não roda
 *   enquanto uma atualização pedida na busca/resultados estiver em andamento ([ForegroundListingRefresh]).
 *   O repositório limita a uma busca simultânea por radar e aplica backoff após HTTP 429/5xx; a notificação só
 *   cita oportunidades nunca vistas ([RadarAlerts]).
 * Deduplicação (um aviso de documentos por dia/empresa; ids de oportunidades já vistas) e a
 * "sessão lembrada" (restoreSession) são mantidas em SharedPreferences "personal_alerts".
 */
@HiltWorker
class PersonalAlertsWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val auth: AuthRepository,
    private val documents: DocumentRepository,
    private val notifier: AppNotifier,
    private val radarAlerts: RadarAlerts,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val session = auth.session.value ?: auth.restoreSession() ?: return Result.success()
        if (session.user.demo) return Result.success()
        val company = session.activeCompany.id
        val prefs = applicationContext.getSharedPreferences(RadarAlerts.PREFS, Context.MODE_PRIVATE)
        // Agendamento antigo (sem input) continua cobrindo os dois tipos até ser substituído.
        val kind = inputData.getString(KEY_KIND)
        return try {
            if (kind == null || kind == KIND_DOCUMENTS) checkDocuments(session, company, prefs)
            // Atualização pedida na busca/resultados em andamento: ela já mantém o cache das fontes; o Worker não concorre.
            if ((kind == null || kind == KIND_RADARS) && !ForegroundListingRefresh.isActive) {
                // Só incremental (nunca a varredura completa da janela) e com tempo máximo por execução.
                withTimeoutOrNull(RADAR_RUN_BUDGET_MS) {
                    withContext(SourceSyncPolicy.INCREMENTAL_ONLY) { radarAlerts.check(session, company, notify = true) { isStopped } }
                }
            }
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }

    /**
     * Documentos perto do vencimento: leitura local. Avisa uma vez por documento em cada etapa — faltando 15 dias,
     * faltando 3 dias e vencido ([DocumentValidity.ALERT_THRESHOLDS]); renovar a validade reinicia os avisos.
     */
    private suspend fun checkDocuments(session: AuthSession, company: Long, prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        val key = "documents-sent:$company"
        val sent = prefs.getStringSet(key, emptySet()).orEmpty()
        val docs = documents.observeDocuments(company).first()
        val due = DocumentValidity.dueAlerts(docs, now, sent)
        if (due.isNotEmpty() && auth.session.value == session) {
            val (title, body) = DocumentValidity.notificationText(due)
            notifier.notify(
                NotificationCategory.DOCUMENTOS, title, body,
                route = com.licitaia.core.ui.nav.Routes.DOCUMENTS, companyId = company,
            )
        }
        // Guarda só as chaves ainda relevantes (documentos excluídos/renovados saem do conjunto).
        val prefixes = docs.map { d -> "doc:${d.id}:${d.expiresAt}:" }
        val keep = (sent + due.map { it.key }).filter { k -> prefixes.any { k.startsWith(it) } }.toSet()
        prefs.edit().putStringSet(key, keep).apply()
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KIND_DOCUMENTS = "documents"
        private const val KIND_RADARS = "radars"
        private const val WORK_RADARS = "personal-public-alerts"
        private const val WORK_DOCUMENTS = "personal-document-alerts"
        /** Radares em segundo plano: incremental leve a cada 3 h (a carga completa é a diária das 05:30). */
        private const val RADAR_INTERVAL_HOURS = 3L
        /** Tempo máximo de uma execução dos radares em segundo plano (o que faltar fica para o próximo ciclo). */
        private const val RADAR_RUN_BUDGET_MS = 3L * 60 * 1000

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            // Radares: precisam de rede e bateria não baixa. Cada execução só baixa as publicações novas (incremental).
            // UPDATE substitui o agendamento anterior (era a cada 15 min).
            val radars = PeriodicWorkRequestBuilder<PersonalAlertsWorker>(RADAR_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_KIND to KIND_RADARS))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
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
