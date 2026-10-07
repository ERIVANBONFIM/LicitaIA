package com.licitaia.feature.bidding.robot

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Portal
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.live.automation.PortalRobotEngine
import com.licitaia.feature.live.automation.PortalSessionGate
import com.licitaia.feature.live.automation.RobotPlanRules
import com.licitaia.feature.live.keepalive.PortalKeepAliveController
import com.licitaia.feature.live.ui.RobotRoutes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agenda do robô de lance armado: ~30 min antes da sessão PREPARA (liga "Manter sessão ativa" do Comprasnet, garante a
 * sessão logada — login com certificado só se já escolhido — e notifica "Disputa começando"); ~2 min antes ENTRA na
 * sala e inicia o robô (modo automático opera quando a fase de lances abrir; modo manual só sugere).
 *
 * Limite honesto do Android: com o app fechado há muito tempo o sistema pode adiar o trabalho ou bloquear o serviço em
 * primeiro plano; a notificação "Disputa começando" pede para abrir o app.
 */
@Singleton
class RobotScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    fun schedule(companyId: Long, tenderKey: String, sessionAt: Long, now: Long = System.currentTimeMillis()) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        fun enqueue(phase: String, at: Long) {
            val delay = (at - now).coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<RobotSessionWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_COMPANY to companyId, KEY_TENDER to tenderKey, KEY_PHASE to phase))
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork(name(companyId, tenderKey, phase), ExistingWorkPolicy.REPLACE, req)
        }
        enqueue(PHASE_PREPARE, RobotPlanRules.prepareAt(sessionAt))
        enqueue(PHASE_START, RobotPlanRules.startAt(sessionAt))
    }

    fun cancel(companyId: Long, tenderKey: String) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        listOf(PHASE_PREPARE, PHASE_START).forEach { wm.cancelUniqueWork(name(companyId, tenderKey, it)) }
    }

    companion object {
        const val TAG = "portal-robot"
        const val KEY_COMPANY = "companyId"
        const val KEY_TENDER = "tenderKey"
        const val KEY_PHASE = "phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_START = "start"
        fun name(companyId: Long, tenderKey: String, phase: String) = "portal-robot-$companyId-$tenderKey-$phase"
    }
}

class RobotSessionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun robotEngine(): PortalRobotEngine
        fun sessionGate(): PortalSessionGate
        fun robotRepository(): PortalRobotRepository
        fun notifier(): AppNotifier
        fun auth(): AuthRepository
        fun keepAlive(): PortalKeepAliveController
    }

    override suspend fun doWork(): Result {
        val companyId = inputData.getLong(RobotScheduler.KEY_COMPANY, -1)
        val key = inputData.getString(RobotScheduler.KEY_TENDER) ?: return Result.failure()
        val phase = inputData.getString(RobotScheduler.KEY_PHASE) ?: return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        // Só a empresa ativa e logada no app opera (o robô nunca roda para outra empresa).
        if (deps.auth().session.value?.activeCompany?.id != companyId) return Result.success()
        val plan = runCatching { deps.robotRepository().getPlan(companyId, key) }.getOrNull() ?: return Result.success()
        if (!plan.bidArmed) return Result.success()
        val tender = runCatching { deps.robotRepository().getMyTenders(companyId) }.getOrNull()?.firstOrNull { it.tenderKey == key } ?: return Result.success()
        val notifier = deps.notifier()
        // Compra suspensa/cancelada/revogada/anulada/deserta/fracassada: o robô NÃO inicia sozinho; só avisa.
        com.licitaia.domain.model.OfficialSituation.fromText(tender.situation)?.let { s ->
            if (phase == RobotScheduler.PHASE_START) runCatching {
                notifier.notify(
                    NotificationCategory.LANCES, "Robô não iniciado: compra ${s.label.lowercase()}",
                    "${tender.label}: o portal indica compra ${s.label.lowercase()}. Confira no portal; o robô não inicia automaticamente.",
                    critical = true, route = RobotRoutes.plan(key), companyId = companyId,
                )
            }
            return Result.success()
        }
        when (phase) {
            RobotScheduler.PHASE_PREPARE -> {
                runCatching { deps.keepAlive().setEnabled(companyId, Portal.COMPRAS_GOV, true) }
                val gate = runCatching { deps.sessionGate().ensureLoggedArea(companyId) }.getOrNull()
                val logged = gate == PortalSessionGate.Result.Workspace || gate == PortalSessionGate.Result.Electronic
                runCatching {
                    notifier.notify(
                        NotificationCategory.SESSOES, "Disputa começando",
                        "${tender.label} às ${Formatters.dateTime(plan.sessionAt)} · robô ${plan.bid.mode.label}. " +
                            if (logged) "Sessão do Comprasnet pronta." else "Entre no Comprasnet (Portais) antes da sessão.",
                        critical = !logged, route = if (logged) RobotRoutes.plan(key) else Routes.portalWeb(Portal.COMPRAS_GOV), companyId = companyId,
                    )
                }
            }
            RobotScheduler.PHASE_START -> {
                val started = deps.robotEngine().startBid(companyId, key)
                runCatching {
                    notifier.notify(
                        NotificationCategory.LANCES, if (started.isSuccess) "Robô na sala de disputa" else "Robô não iniciou",
                        started.fold(
                            { "${tender.label}: ${if (plan.bid.mode == BidRobotMode.AUTOMATICO) "opera sozinho quando a fase de lances abrir" else "sugere lances; toque Enviar"}. Use PARAR a qualquer momento." },
                            { "${tender.label}: ${it.message}" },
                        ),
                        critical = true, route = RobotRoutes.plan(key), companyId = companyId,
                    )
                }
            }
        }
        return Result.success()
    }
}
