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
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.network.ConnectivityMonitor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import com.licitaia.feature.live.web.CertAutoLogin
import com.licitaia.feature.live.web.PortalWebPolicy
import com.licitaia.feature.live.web.PortalWebSessions
import com.licitaia.feature.live.web.PortalWebViewHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import com.licitaia.feature.live.web.PortalInstability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
 * - a cada N minutos ([AppSettings.portalKeepAliveMinutes]) dá um "toque" no WebView RETIDO da empresa/portal
 *   ([PortalWebViewHolder] — a mesma aba que o usuário usa, com o sessionStorage/memória da SPA): `reload()` se está na
 *   área logada (só a página atual); se a aba está vazia (processo recriado), abre a ENTRADA OFICIAL (Comprasnet
 *   loginPortal.asp?perfil=1) — a área de trabalho (intro.htm) só numa aba que já esteve na área logada. Nunca navega
 *   para o cnetmobile.
 *   Avalia o resultado com a mesma política da tela (URL + marcadores de conteúdo). Com o usuário na tela, nada é
 *   feito. Nenhuma outra ação no portal (sem cliques, sem formulários).
 * - mantém o [PortalKeepAliveService] (FGS `dataSync`) com a notificação baixa "Mantendo sessão … ativa".
 * - se a sessão caiu: marca SESSAO_EXPIRADA (o portal sai do plano e o ciclo para), audita e dispara UMA
 *   notificação crítica SESSOES com rota para o portal. A preferência continua ligada: após novo login manual,
 *   o ciclo volta sozinho.
 *
 * Sem internet (NetworkCallback / NET_CAPABILITY_VALIDATED via [ConnectivityMonitor]) o ciclo pausa e nada é
 * concluído; ao voltar a rede faz um probe logo em seguida. Página de erro, HTTP ≥ 500 ou timeout = inconclusivo.
 * EXPIRED exige confirmação por um segundo probe com rede.
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
    private val connectivity: ConnectivityMonitor,
    private val liveSessions: dagger.Lazy<LiveSessionManager>,
    private val webViews: PortalWebViewHolder,
) {
    private data class Plan(val companyId: Long?, val portals: Set<Portal>, val intervalMinutes: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private val jobs = mutableMapOf<Portal, Job>()

    /**
     * "Compras.gov.br instável" (503 do portal): estado/agenda por empresa+portal e o laço de novas tentativas. Enquanto
     * um laço de [retryJobs] roda, o laço normal de [jobs] do mesmo portal NÃO roda (um único agendador por portal).
     */
    private val retries = mutableMapOf<Pair<Long, Portal>, PortalInstability.Retry>()
    private val retryJobs = mutableMapOf<Portal, Job>()
    private val unstable = MutableStateFlow<Map<Pair<Long, Portal>, PortalInstability.State>>(emptyMap())
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
        // "Compras.gov.br instável": a entrada do Comprasnet exibiu o 503 (tela ou segundo plano).
        scope.launch {
            webViews.instability.collect { e -> runCatching { onUnstableDetected(e) } }
        }
        // Sem internet por mais de 30 s com keep-alive ou pregão assistido ativo: UM aviso por queda.
        scope.launch {
            connectivity.online.collectLatest { online ->
                if (online) return@collectLatest
                delay(OFFLINE_NOTICE_MS)
                if (connectivity.isOnline) return@collectLatest
                val companyId = plan.companyId ?: auth.session.value?.activeCompany?.id
                val liveActive = runCatching {
                    liveSessions.get().sessions.value.any { it.companyId == companyId && it.status != LiveStatus.ENCERRADA }
                }.getOrDefault(false)
                if (plan.portals.isEmpty() && !liveActive) return@collectLatest
                runCatching {
                    notifier.notify(
                        category = NotificationCategory.SESSOES,
                        title = "Sem internet: acompanhamento do pregão pausado",
                        body = "A recarga automática dos portais e o acompanhamento ficam pausados até a conexão voltar. " +
                            "Sua sessão continua salva; ao reconectar, o app verifica o portal automaticamente.",
                        companyId = companyId,
                    )
                }
            }
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
        // Desligou: para as novas tentativas do "portal instável" (o aviso continua). Ligou com o portal instável: começa.
        val retry = retries[companyId to portal]
        if (!enabled) {
            retry?.stop()
            retryJobs.remove(portal)?.cancel()
            publishUnstable()
            reconcileService()
        } else if (retry != null && retry.onDetected(retryEnabled = true)) {
            startRetryLoop(companyId, portal, retry)
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
            stopAllRetries()
            active.forEach { p -> runCatching { portals.auditKeepAlive(companyId, p, "Manter sessão ativa DESLIGADO pela notificação") } }
        }
    }

    // ------------------------------------------------------------------ ciclo

    private fun apply(next: Plan) {
        val restartAll = next.companyId != plan.companyId || next.intervalMinutes != plan.intervalMinutes
        // Troca de empresa / logout: os WebViews retidos das outras empresas são destruídos.
        if (next.companyId != plan.companyId) {
            runCatching { webViews.retainOnly(next.companyId) }
            // Novas tentativas do "portal instável" são da empresa anterior: param e o estado é esquecido.
            retryJobs.values.forEach { it.cancel() }
            retryJobs.clear()
            retries.keys.filter { it.first != next.companyId }.forEach { retries.remove(it) }
            publishUnstable()
        }
        plan = next
        runCatching { webViews.setKeepAlivePortals(next.companyId, next.portals + retryJobs.keys) }
        jobs.keys.toList().forEach { p ->
            if (restartAll || p !in next.portals || retryJobs[p]?.isActive == true) jobs.remove(p)?.cancel()
        }
        val companyId = next.companyId
        if (companyId != null) {
            next.portals.forEach { p ->
                // Agendador único: com novas tentativas do "portal instável" em curso, o keep-alive normal espera.
                if (retryJobs[p]?.isActive == true) return@forEach
                if (jobs[p]?.isActive != true) jobs[p] = launchLoop(companyId, p, next.intervalMinutes)
            }
        }
        reconcileService()
    }

    // ------------------------------------------------------------------ "Compras.gov.br instável" (503)

    /** Estado de instabilidade da empresa/portal para a tela (null = sem instabilidade). */
    fun unstableState(companyId: Long, portal: Portal): Flow<PortalInstability.State?> =
        unstable.map { it[companyId to portal] }.distinctUntilChanged()

    private fun publishUnstable() {
        unstable.value = retries.mapNotNull { (k, r) -> with(PortalInstability) { r.snapshot() }?.let { k to it } }.toMap()
    }

    /** A entrada do Comprasnet exibiu o 503: badge "instável" + (com "Manter sessão ativa") laço de novas tentativas. */
    private suspend fun onUnstableDetected(e: PortalWebViewHolder.UnstableEvent) {
        if (!PortalInstability.supports(e.portal)) return
        if (auth.session.value?.activeCompany?.id != e.companyId) return
        val retry = retries.getOrPut(e.companyId to e.portal) { PortalInstability.Retry() }
        val firstOfEpisode = !retry.unstable
        val keepOn = runCatching { settings.settings.first().isPortalKeepAliveOn(e.companyId, e.portal) }.getOrDefault(false)
        val start = retry.onDetected(retryEnabled = keepOn)
        publishUnstable()
        if (firstOfEpisode) runCatching { portals.auditKeepAlive(e.companyId, e.portal, PortalInstability.auditText(e.afterLandingSso)) }
        if (start) startRetryLoop(e.companyId, e.portal, retry)
    }

    /**
     * Chegou à área logada (tela/login manual ou automático): encerra a instabilidade e, se havia uma, avisa
     * "Compras.gov.br voltou — você está conectado". Chamado pela tela ao detectar a sessão aberta.
     */
    fun onPortalConnected(companyId: Long, portal: Portal) {
        val retry = retries[companyId to portal] ?: return
        val was = retry.onConnected()
        retries.remove(companyId to portal)
        retryJobs.remove(portal)?.cancel()
        publishUnstable()
        if (was) scope.launch { notifyRecovered(companyId, portal) }
        apply(plan)
    }

    /** "Sair do portal": esquece a instabilidade e para as novas tentativas. */
    fun clearUnstable(companyId: Long, portal: Portal) {
        retries.remove(companyId to portal)?.reset()
        retryJobs.remove(portal)?.cancel()
        publishUnstable()
        apply(plan)
    }

    private fun stopAllRetries() {
        retries.values.forEach { it.stop() }
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        publishUnstable()
        reconcileService()
    }

    /**
     * Laço de novas tentativas (a cada [PortalInstability.RETRY_INTERVAL_MS], no máximo [PortalInstability.MAX_ATTEMPTS]).
     * Sem rede: pausa (a espera recomeça quando a rede volta; não conta tentativa). Para se "Manter sessão ativa" for
     * desligado, a empresa mudar ou o usuário sair do portal ([clearUnstable]).
     */
    private fun startRetryLoop(companyId: Long, portal: Portal, retry: PortalInstability.Retry) {
        jobs.remove(portal)?.cancel() // agendador único: o keep-alive normal deste portal espera
        retryJobs.remove(portal)?.cancel()
        lateinit var self: Job
        self = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    val wait = retry.nextDelay()
                    if (wait == null) {
                        if (retry.justExhausted) notifyExhausted(companyId, portal)
                        break
                    }
                    publishUnstable()
                    val wentOffline = withTimeoutOrNull(wait) { connectivity.online.first { !it } } != null
                    if (wentOffline || !connectivity.isOnline) {
                        connectivity.online.first { it }
                        delay(RECONNECT_SETTLE_MS)
                    }
                    val wanted = auth.session.value?.activeCompany?.id == companyId &&
                        runCatching { settings.settings.first().isPortalKeepAliveOn(companyId, portal) }.getOrDefault(false)
                    if (!wanted) { retry.stop(); break }
                    if (!retry.beginAttempt()) break
                    publishUnstable()
                    val autoOn = runCatching { settings.settings.first().isAutoCertLoginOn(companyId, portal) }.getOrDefault(false)
                    val cnpj = auth.session.value?.activeCompany?.takeIf { it.id == companyId }?.cnpj
                    var result = withTimeoutOrNull(AUTO_RELOGIN_TIMEOUT_MS) {
                        runCatching { webViews.unstableRetryAttempt(companyId, portal, cnpj, autoOn) }.getOrNull()
                    } ?: PortalInstability.AttemptResult.INCONCLUSIVE
                    // Rede caiu durante a tentativa: não conclui nada.
                    if (!connectivity.isOnline && result != PortalInstability.AttemptResult.LOGGED_IN) result = PortalInstability.AttemptResult.INCONCLUSIVE
                    when (retry.onAttemptResult(result)) {
                        PortalInstability.AfterAttempt.CONTINUE -> publishUnstable()
                        PortalInstability.AfterAttempt.RECOVERED_CONNECTED -> {
                            retries.remove(companyId to portal)
                            runCatching { portals.markSessionDetected(companyId, portal, loggedIn = true) }
                            notifyRecovered(companyId, portal)
                            break
                        }
                        PortalInstability.AfterAttempt.RECOVERED_AVAILABLE, PortalInstability.AfterAttempt.STOP -> {
                            retries.remove(companyId to portal)
                            notifyAvailable(companyId, portal)
                            break
                        }
                    }
                }
            } finally {
                if (retryJobs[portal] === self) retryJobs.remove(portal)
                publishUnstable()
                // Fim das novas tentativas: o keep-alive normal (se a sessão estiver aberta) volta a rodar.
                main.post { runCatching { apply(plan) } }
            }
        }
        retryJobs[portal] = self
        runCatching { webViews.setKeepAlivePortals(companyId, plan.portals + retryJobs.keys) }
        reconcileService()
        self.start()
    }

    private suspend fun notifyRecovered(companyId: Long, portal: Portal) {
        runCatching { portals.auditKeepAlive(companyId, portal, "${portal.displayName} voltou após instabilidade (503): sessão aberta") }
        runCatching {
            notifier.notify(
                category = NotificationCategory.SESSOES,
                title = PortalInstability.RECOVERED_TITLE,
                body = "O portal voltou a aceitar o login depois da instabilidade (erro 503).",
                critical = false,
                route = Routes.portalWeb(portal),
                companyId = companyId,
            )
        }
    }

    private suspend fun notifyAvailable(companyId: Long, portal: Portal) {
        runCatching {
            notifier.notify(
                category = NotificationCategory.SESSOES,
                title = "${portal.displayName} respondeu de novo — toque para entrar",
                body = "A entrada oficial abriu sem o erro 503. Conclua o login no navegador interno.",
                critical = false,
                route = Routes.portalWeb(portal),
                companyId = companyId,
            )
        }
    }

    private suspend fun notifyExhausted(companyId: Long, portal: Portal) {
        runCatching { portals.auditKeepAlive(companyId, portal, "Compras.gov.br instável: ${PortalInstability.MAX_ATTEMPTS} novas tentativas sem sucesso; tentativas encerradas") }
        runCatching {
            notifier.notify(
                category = NotificationCategory.SESSOES,
                title = PortalInstability.EXHAUSTED_TITLE,
                body = "Tentamos entrar ${PortalInstability.MAX_ATTEMPTS} vezes em cerca de 1 h e o portal continuou respondendo com " +
                    "erro 503. Não é problema do app.",
                critical = false,
                route = Routes.portalWeb(portal),
                companyId = companyId,
            )
        }
    }

    /**
     * Laço do portal. Sem internet o ciclo PAUSA (nenhuma recarga, nenhuma conclusão sobre a sessão) e retoma
     * quando o NetworkCallback informa rede validada de novo, com um probe logo em seguida. Um resultado EXPIRED
     * só vale após confirmação ([PortalWebPolicy.EXPIRED_CONFIRMATIONS] probes seguidos, com rede).
     */
    private fun launchLoop(companyId: Long, portal: Portal, minutes: Int): Job = scope.launch {
        var consecutiveExpired = 0
        var nextDelay = minutes * 60_000L
        while (isActive) {
            // Espera o intervalo; se a rede cair nesse meio-tempo, para de contar e aguarda ela voltar.
            val wentOffline = withTimeoutOrNull(nextDelay) { connectivity.online.first { !it } } != null
            nextDelay = minutes * 60_000L
            if (wentOffline || !connectivity.isOnline) {
                connectivity.online.first { it }
                delay(RECONNECT_SETTLE_MS) // ao voltar: probe logo em seguida
            }
            val outcome = runCatching { ping(companyId, portal) }.getOrDefault(PortalWebPolicy.Signal.NONE)
            if (outcome == PortalWebPolicy.Signal.EXPIRED) {
                consecutiveExpired++
                if (PortalWebPolicy.keepAliveConfirmsExpired(consecutiveExpired)) {
                    // "Entrar automaticamente com certificado" ligado: UMA tentativa de relogin por queda confirmada.
                    if (tryAutoRelogin(companyId, portal)) {
                        consecutiveExpired = 0
                        continue
                    }
                    // Fora deste job: marcar EXPIRADA tira o portal do plano e cancela este laço.
                    scope.launch { onExpired(companyId, portal) }
                    break
                }
                nextDelay = CONFIRM_DELAY_MS // confirma em instantes antes de alertar
            } else {
                consecutiveExpired = 0
            }
        }
    }

    /**
     * Uma recarga da página do usuário. EXPIRED = a sessão caiu; NONE = ok ou inconclusivo
     * (sem rede antes/depois, erro de carga, HTTP ≥ 500, timeout).
     */
    private suspend fun ping(companyId: Long, portal: Portal): PortalWebPolicy.Signal {
        if (auth.session.value?.activeCompany?.id != companyId) return PortalWebPolicy.Signal.NONE
        if (!connectivity.isOnline) return PortalWebPolicy.Signal.NONE
        val last = runCatching { portals.lastWebUrl(companyId, portal) }.getOrNull()
        val fallback = PortalWebPolicy.keepAliveUrl(portal, last)
        // Toque no WebView RETIDO da empresa/portal (mesma aba do usuário: sessionStorage/memória da SPA preservados).
        // Área logada: reload() da página atual. Aba vazia (sem estado): ENTRADA OFICIAL (loginPortal.asp) — intro.htm só
        // numa aba que já esteve na área logada; nunca o cnetmobile por URL. Caiu no www.gov.br: UMA ida à entrada.
        // "Não autorizado" no cnetmobile: UMA volta à área de trabalho. Se cair no login, a política marca expirado.
        val result = webViews.keepAliveTouch(companyId, portal, fallback)
        if (result.skipped) return PortalWebPolicy.Signal.NONE // usuário está na tela: ele mesmo mantém a sessão
        PortalWebSessions.flush(companyId)
        val finalUrl = result.finalUrl
        // Recarregamos a área logada e o portal nos deixou numa página pública (ex.: www.gov.br): sessão caiu.
        // Exceção: abrimos a entrada oficial (aba vazia / reentrada) e ela parou numa página de passagem do Comprasnet.
        if (finalUrl != null && connectivity.isOnline && PortalWebPolicy.leftLoggedArea(portal, finalUrl) &&
            !(result.viaEntry && PortalWebPolicy.isLoginTransitPage(portal, finalUrl))
        ) {
            return PortalWebPolicy.Signal.EXPIRED
        }
        val evaluatedUrl = finalUrl ?: fallback ?: return PortalWebPolicy.Signal.NONE
        return PortalWebPolicy.evaluate(
            portal, evaluatedUrl,
            hasCookies = PortalWebSessions.hasCookies(companyId, evaluatedUrl),
            // Recarregamos uma página da área logada: se terminou no login, o portal nos devolveu para lá.
            previousWasLoginPage = false,
            currentStatus = PortalConnectionStatus.CONECTADO,
            contentExpired = result.contentExpired,
            // A rede pode ter caído durante o carregamento: só conclui se continua online agora.
            online = connectivity.isOnline,
            loadFailed = finalUrl == null,
        )
    }

    /**
     * Relogin automático com certificado no WebView retido (opt-in). true = sessão restabelecida (ou já estava ok);
     * false = desligado/sem certificado/parou (CAPTCHA, 2FA, erro...) → segue o alerta crítico de sessão encerrada.
     */
    private suspend fun tryAutoRelogin(companyId: Long, portal: Portal): Boolean {
        if (!CertAutoLogin.supports(portal)) return false
        val on = runCatching { settings.settings.first().isAutoCertLoginOn(companyId, portal) }.getOrDefault(false)
        if (!on || !connectivity.isOnline) return false
        val company = auth.session.value?.activeCompany?.takeIf { it.id == companyId } ?: return false
        val outcome = withTimeoutOrNull(AUTO_RELOGIN_TIMEOUT_MS) {
            runCatching { webViews.autoRelogin(companyId, portal, company.cnpj) }.getOrNull()
        } ?: CertAutoLogin.Outcome.Stopped(CertAutoLogin.StopReason.TIMEOUT)
        return when (outcome) {
            CertAutoLogin.Outcome.Success -> {
                runCatching { portals.markSessionDetected(companyId, portal, loggedIn = true) }
                runCatching { portals.auditKeepAlive(companyId, portal, "Login automático com certificado: sucesso (reconexão após sessão expirada)") }
                runCatching {
                    notifier.notify(
                        category = NotificationCategory.SESSOES,
                        title = "${portal.displayName}: reconectado com o certificado",
                        body = "A sessão do portal tinha expirado e o app entrou de novo com o certificado digital da empresa.",
                        critical = false,
                        route = Routes.portalWeb(portal),
                        companyId = companyId,
                    )
                }
                true
            }
            CertAutoLogin.Outcome.NotNeeded -> true
            is CertAutoLogin.Outcome.Stopped -> {
                if (outcome.reason != CertAutoLogin.StopReason.BUSY) {
                    runCatching { portals.auditKeepAlive(companyId, portal, "Login automático com certificado: ${outcome.reason.auditText}") }
                }
                false
            }
        }
    }

    /**
     * Liga/desliga "Entrar automaticamente com certificado digital" (auditado, sem URLs). Só liga se o portal tem o
     * fluxo mapeado e já existe um certificado escolhido (alias lembrado) — senão lança com a explicação.
     */
    suspend fun setAutoCertLogin(companyId: Long, portal: Portal, enabled: Boolean) {
        start()
        if (enabled) {
            check(CertAutoLogin.supports(portal)) { "O login automático com certificado está disponível, por enquanto, só para o Compras.gov.br." }
            check(webViews.hasRememberedCertificate(companyId, portal)) {
                "Faça o primeiro login manualmente: em \"Entrar com gov.br\" escolha \"Seu certificado digital\" e selecione o " +
                    "certificado da empresa. Depois disso o app lembra qual usar e esta opção pode ser ligada."
            }
        }
        val key = AppSettings.portalKeepAliveKey(companyId, portal)
        settings.update { s -> s.copy(portalAutoCertLogin = if (enabled) s.portalAutoCertLogin + key else s.portalAutoCertLogin - key) }
        runCatching {
            portals.auditKeepAlive(
                companyId, portal,
                if (enabled) "Login automático com certificado LIGADO: o app só clica nas etapas de login (perfil, certificado, empresa); CAPTCHA/2FA ficam com o usuário"
                else "Login automático com certificado DESLIGADO",
            )
        }
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
        // Portais com sessão mantida + portais com novas tentativas do "instável" em curso.
        val active = plan.portals + retryJobs.filterValues { it.isActive }.keys
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

    private companion object {
        /** Espera após a rede voltar antes do probe (DNS/SSO estabilizarem). */
        const val RECONNECT_SETTLE_MS = 5_000L
        /** Intervalo do probe de confirmação após um primeiro EXPIRED. */
        const val CONFIRM_DELAY_MS = 30_000L
        /** Queda de rede mais longa que isto gera o aviso "Sem internet". */
        const val OFFLINE_NOTICE_MS = 30_000L
        /** Teto de espera do relogin automático (o holder tem o próprio limite de 90 s). */
        const val AUTO_RELOGIN_TIMEOUT_MS = 120_000L
    }
}
