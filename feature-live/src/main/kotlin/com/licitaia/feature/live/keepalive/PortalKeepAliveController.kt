package com.licitaia.feature.live.keepalive

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.feature.live.web.PortalWebPolicy
import com.licitaia.feature.live.web.PortalWebSessions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Manter sessão ativa" (opt-in por empresa+portal) dos portais oficiais.
 *
 * Enquanto a preferência estiver ligada E a sessão do portal estiver CONECTADA para a empresa ativa:
 * - a cada N minutos ([AppSettings.portalKeepAliveMinutes]) recarrega, num WebView headless com os cookies da
 *   empresa ([HeadlessPortalProbe]), a última página da área logada (ou a `homeUrl`) e avalia o resultado com a
 *   mesma política da tela (URL + marcadores de conteúdo). Nenhuma outra ação no portal.
 * - mantém o [PortalKeepAliveService] (FGS `dataSync`) com a notificação baixa "Mantendo sessão … ativa".
 * - se a sessão caiu: marca SESSAO_EXPIRADA (o portal sai do plano e o ciclo para), audita e dispara UMA
 *   notificação crítica SESSOES com rota para o portal. A preferência continua ligada: após novo login manual,
 *   o ciclo volta sozinho.
 *
 * Para quando nenhum portal está ativo: logout, troca de empresa, "Sair do portal", sessão expirada, preferência
 * desligada ou ação "Parar" da notificação.
 *
 * Serviço: só é iniciado com o app em primeiro plano ([ProcessLifecycleOwner] ≥ STARTED); senão no próximo ON_START.
 * Tudo roda na thread principal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PortalKeepAliveController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val portals: PortalRepository,
    private val settings: SettingsRepository,
    private val notifier: AppNotifier,
) {
    private data class Plan(val companyId: Long?, val portals: Set<Portal>, val intervalMinutes: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private val jobs = mutableMapOf<Portal, Job>()
    private var plan = Plan(null, emptySet(), AppSettings().portalKeepAliveMinutes)

    private val main = Handler(Looper.getMainLooper())
    private val lifecycle: Lifecycle get() = ProcessLifecycleOwner.get().lifecycle
    private var serviceRunning = false
    private var observing = false

    private val onForeground = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            main.post { runCatching { reconcileService() } }
        }
    }

    /** Começa a observar sessão/preferências. Idempotente (chamado pelo [PortalKeepAliveInitializer] e pelas telas). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            auth.session.flatMapLatest { session ->
                if (session == null) {
                    flowOf(Plan(null, emptySet(), AppSettings().portalKeepAliveMinutes))
                } else {
                    val companyId = session.activeCompany.id
                    combine(settings.settings, portals.observeSessions(companyId)) { st, sessions ->
                        val active = Portal.entries.filter { p ->
                            PortalWebPolicy.rules(p).requiresLogin &&
                                st.isPortalKeepAliveOn(companyId, p) &&
                                sessions.firstOrNull { it.portal == p }?.status == PortalConnectionStatus.CONECTADO
                        }.toSet()
                        Plan(companyId, active, st.portalKeepAliveMinutes)
                    }.catch { emit(Plan(companyId, emptySet(), AppSettings().portalKeepAliveMinutes)) }
                }
            }
                .distinctUntilChanged()
                .catch { emit(Plan(null, emptySet(), AppSettings().portalKeepAliveMinutes)) }
                .collect { apply(it) }
        }
    }

    // ------------------------------------------------------------------ preferências (telas)

    /** Liga/desliga para o portal da empresa (auditado, sem URLs). */
    suspend fun setEnabled(companyId: Long, portal: Portal, enabled: Boolean) {
        start()
        if (!PortalWebPolicy.rules(portal).requiresLogin) return
        val key = AppSettings.portalKeepAliveKey(companyId, portal)
        var minutes = AppSettings().portalKeepAliveMinutes
        settings.update { s ->
            minutes = s.portalKeepAliveMinutes
            s.copy(portalKeepAlive = if (enabled) s.portalKeepAlive + key else s.portalKeepAlive - key)
        }
        runCatching {
            portals.auditKeepAlive(
                companyId, portal,
                if (enabled) "Manter sessão ativa LIGADO: recarrega a página do portal a cada $minutes min enquanto a sessão estiver aberta"
                else "Manter sessão ativa DESLIGADO",
            )
        }
    }

    suspend fun setIntervalMinutes(minutes: Int) {
        if (minutes !in AppSettings.PORTAL_KEEP_ALIVE_OPTIONS) return
        settings.update { it.copy(portalKeepAliveMinutes = minutes) }
    }

    /** Ação "Parar" da notificação: desliga a preferência de todos os portais ativos da empresa. */
    fun stopFromNotification() {
        scope.launch {
            val companyId = plan.companyId ?: auth.session.value?.activeCompany?.id ?: return@launch
            val active = plan.portals.ifEmpty {
                Portal.entries.filter { runCatching { settings.settings.first().isPortalKeepAliveOn(companyId, it) }.getOrDefault(false) }.toSet()
            }
            settings.update { s -> s.copy(portalKeepAlive = s.portalKeepAlive.filterNot { it.startsWith("$companyId:") }.toSet()) }
            active.forEach { p -> runCatching { portals.auditKeepAlive(companyId, p, "Manter sessão ativa DESLIGADO pela notificação") } }
        }
    }

    // ------------------------------------------------------------------ ciclo

    private fun apply(next: Plan) {
        val restartAll = next.companyId != plan.companyId || next.intervalMinutes != plan.intervalMinutes
        plan = next
        jobs.keys.toList().forEach { p ->
            if (restartAll || p !in next.portals) jobs.remove(p)?.cancel()
        }
        val companyId = next.companyId
        if (companyId != null) {
            next.portals.forEach { p -> if (jobs[p]?.isActive != true) jobs[p] = launchLoop(companyId, p, next.intervalMinutes) }
        }
        reconcileService()
    }

    private fun launchLoop(companyId: Long, portal: Portal, minutes: Int): Job = scope.launch {
        while (isActive) {
            delay(minutes * 60_000L)
            val outcome = runCatching { ping(companyId, portal) }.getOrDefault(PortalWebPolicy.Signal.NONE)
            if (outcome == PortalWebPolicy.Signal.EXPIRED) {
                // Fora deste job: marcar EXPIRADA tira o portal do plano e cancela este laço.
                scope.launch { onExpired(companyId, portal) }
                break
            }
        }
    }

    /** Uma recarga da página do usuário. EXPIRED = a sessão caiu; NONE = ok ou inconclusivo (rede/timeout). */
    private suspend fun ping(companyId: Long, portal: Portal): PortalWebPolicy.Signal {
        if (auth.session.value?.activeCompany?.id != companyId) return PortalWebPolicy.Signal.NONE
        val last = runCatching { portals.lastWebUrl(companyId, portal) }.getOrNull()
        val url = PortalWebPolicy.keepAliveUrl(portal, last) ?: return PortalWebPolicy.Signal.NONE
        val result = HeadlessPortalProbe.run(context, companyId, portal, url)
        val finalUrl = result.finalUrl ?: return PortalWebPolicy.Signal.NONE
        return PortalWebPolicy.evaluate(
            portal, finalUrl,
            hasCookies = PortalWebSessions.hasCookies(companyId, finalUrl),
            // Carregamos uma página da área logada: se terminou no login, o portal nos devolveu para lá.
            previousWasLoginPage = false,
            currentStatus = PortalConnectionStatus.CONECTADO,
            contentExpired = result.contentExpired,
        )
    }

    private suspend fun onExpired(companyId: Long, portal: Portal) {
        if (auth.session.value?.activeCompany?.id != companyId) return
        val status = runCatching { portals.observeSessions(companyId).first().firstOrNull { it.portal == portal }?.status }.getOrNull()
        if (status != PortalConnectionStatus.CONECTADO) return // o usuário saiu/já foi marcado: sem alerta duplicado
        runCatching { portals.markSessionDetected(companyId, portal, loggedIn = false) }
        runCatching { portals.auditKeepAlive(companyId, portal, "Manter sessão ativa: o portal encerrou a sessão; recarga automática interrompida até novo login") }
        runCatching {
            notifier.notify(
                category = NotificationCategory.SESSOES,
                title = "${portal.displayName}: sessão encerrada — toque para entrar de novo",
                body = "O portal encerrou a sessão (tempo máximo ou inatividade dele). Entre novamente no navegador interno; " +
                    "a recarga automática volta sozinha depois do login.",
                critical = true,
                route = Routes.portalWeb(portal),
                companyId = companyId,
            )
        }
    }

    // ------------------------------------------------------------------ Foreground Service

    private fun reconcileService() {
        val active = plan.portals
        if (active.isEmpty()) { stopService(); return }
        val names = active.sortedBy { it.ordinal }.map { it.displayName }
        if (serviceRunning) {
            runCatching {
                NotificationManagerCompat.from(context).notify(
                    PortalKeepAliveService.NOTIFICATION_ID,
                    PortalKeepAliveService.buildNotification(context, names, plan.intervalMinutes),
                )
            }
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { observe(); return }
        serviceRunning = try {
            ContextCompat.startForegroundService(context, PortalKeepAliveService.startIntent(context, names, plan.intervalMinutes))
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / IllegalStateException: tenta no próximo ON_START.
            false
        }
        if (serviceRunning) unobserve() else observe()
    }

    /** Chamado pelo serviço quando o sistema o encerra (ex.: startForeground negado). */
    internal fun onServiceStopped() {
        main.post {
            serviceRunning = false
            if (plan.portals.isNotEmpty()) observe()
        }
    }

    private fun stopService() {
        unobserve()
        if (!serviceRunning) {
            runCatching { NotificationManagerCompat.from(context).cancel(PortalKeepAliveService.NOTIFICATION_ID) }
            return
        }
        serviceRunning = false
        runCatching { context.stopService(PortalKeepAliveService.stopIntent(context)) }
        runCatching { NotificationManagerCompat.from(context).cancel(PortalKeepAliveService.NOTIFICATION_ID) }
    }

    private fun observe() {
        if (observing) return
        observing = true
        if (runCatching { lifecycle.addObserver(onForeground) }.isFailure) observing = false
    }

    private fun unobserve() {
        if (!observing) return
        observing = false
        runCatching { lifecycle.removeObserver(onForeground) }
    }
}
