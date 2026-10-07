package com.licitaia.feature.live.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.security.KeyChain
import android.view.View
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.model.Portal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Mantém UM WebView vivo por (empresa, portal) durante a vida do processo.
 *
 * Por quê: a área logada do Compras.gov.br (`cnetmobile.estaleiro.serpro.gov.br/comprasnet-web`) é uma SPA que guarda
 * o token de acesso em `sessionStorage`/memória da ABA. Um WebView novo (headless do keep-alive antigo, ou a tela
 * recriada ao voltar) não tem esse estado → a SPA mostra "Não autorizado" e a sessão parecia "cair". Com o WebView
 * retido:
 * - a tela do portal ANEXA/DESANEXA este mesmo WebView (o `baseContext` do [MutableContextWrapper] vira a Activity ao
 *   exibir e volta ao Application ao sair — sem vazar a Activity);
 * - o "Manter sessão ativa" faz o "toque" NESTE WebView ([keepAliveTouch]): `reload()` na mesma aba (sessionStorage
 *   sobrevive a reload) ou, com a aba vazia (processo recriado), abre a ENTRADA OFICIAL do Comprasnet — nunca o cnetmobile
 *   direto num WebView sem estado ([PortalWebPolicy.EntryGate]).
 *
 * Nunca é chamado `WebView.pauseTimers()` (é global e pararia o keep-alive); `onPause()` só quando a tela sai e o
 * keep-alive deste portal está desligado. `destroy()` só em "Sair do portal", troca de empresa/logout.
 *
 * Certificado digital (A1 instalado no Android): [RetainedPortalClient.onReceivedClientCertRequest] usa o KeyChain do
 * sistema; o app guarda apenas o ALIAS escolhido por (empresa, host) no [SecretStore]. A chave privada nunca é
 * exportada nem copiada — é entregue ao WebView pelo próprio KeyChain.
 *
 * Tudo na thread principal.
 */
@Singleton
class PortalWebViewHolder @Inject constructor(
    @ApplicationContext private val app: Context,
    private val secrets: SecretStore,
) {
    /** WebView retido de uma empresa/portal. */
    class Entry internal constructor(
        val companyId: Long,
        val portal: Portal,
        internal val wrapper: MutableContextWrapper,
        val webView: WebView,
        /** Perfil isolado por empresa aplicado (false = perfil compartilhado do WebView do app). */
        val isolatedProfile: Boolean,
    ) {
        /** Anexado à tela do portal. */
        var attached: Boolean = false
            internal set
        /** Tela em primeiro plano (RESUMED) exibindo este WebView. */
        var visible: Boolean = false
            internal set
        internal var keepAlive = false
        internal var paused = false
        internal var discarded = false
        internal var probe: ((ProbeEvent) -> Unit)? = null
        /** Tentativa de "Entrar automaticamente com certificado digital" em andamento (no máximo uma). */
        internal var autoLogin: AutoLoginSession? = null
        /** Momento do último clique do app no link de compras eletrônicas (aceita a janela nova que ele abrir). */
        internal var electronicClickAt = 0L
        /** A navegação atual passou pelo retorno do gov.br (`landing_sso.asp`) — só para a auditoria do 503. */
        internal var afterLandingSso = false
        /** Último aviso de "portal instável" emitido (evita duplicar o evento da mesma página). */
        internal var unstableReportedAt = 0L

        /** Há login automático em andamento neste WebView. */
        val autoLoginRunning: Boolean get() = autoLogin != null

        /**
         * Entrada oficial deste WebView (vida do WebView): se já passou pelo Comprasnet, reentradas após "Não autorizado"
         * e a última URL a abrir ao chegar na área logada. Ver [PortalWebPolicy.EntryGate].
         */
        val entryGate = PortalWebPolicy.EntryGate(portal)
    }

    /** Estado de uma tentativa de login automático no WebView retido. */
    internal class AutoLoginSession(
        val cnpj: String,
        val onOutcome: (CertAutoLogin.Outcome) -> Unit,
    ) {
        val run = CertAutoLogin.Run()
        /** Incrementa a cada página iniciada/concluída (progresso para o timer da etapa). */
        var progress = 0
        var lastUrl: String? = null
        var lastAt = 0L
        val timers = mutableListOf<Runnable>()
    }

    internal sealed interface ProbeEvent {
        data object Started : ProbeEvent
        data class Finished(val url: String) : ProbeEvent
        data object Failed : ProbeEvent
    }

    /** Resultado de um toque do keep-alive. */
    data class TouchResult(
        /** URL onde a navegação assentou; null = falha/timeout/bloqueio (inconclusivo). */
        val finalUrl: String?,
        val contentExpired: Boolean,
        /** Nada foi feito (usuário está na tela ou não há URL). */
        val skipped: Boolean = false,
        /** O toque abriu a entrada oficial (aba sem estado ou reentrada após "Não autorizado"). */
        val viaEntry: Boolean = false,
    )

    /** "Compras.gov.br instável": a página de entrada exibiu o 503 do portal ([PortalInstability]). */
    data class UnstableEvent(val companyId: Long, val portal: Portal, val afterLandingSso: Boolean)

    private val entries = LinkedHashMap<Pair<Long, Portal>, Entry>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _instability = MutableSharedFlow<UnstableEvent>(extraBufferCapacity = 16)
    /** Eventos de "portal instável" (tela ou segundo plano). Consumidos pelo keep-alive controller (agendador único). */
    val instability: SharedFlow<UnstableEvent> = _instability

    fun peek(companyId: Long, portal: Portal): Entry? = entries[companyId to portal]

    /** WebView retido da empresa/portal (cria na primeira vez, com o perfil da empresa e as restrições do portal). */
    fun obtain(companyId: Long, portal: Portal): Entry {
        entries[companyId to portal]?.let { return it }
        val wrapper = MutableContextWrapper(app)
        val webView = WebView(wrapper)
        // Perfil por empresa antes de qualquer carregamento. Fallback: perfil compartilhado.
        val isolated = PortalWebSessions.attachProfile(webView, companyId)
        PortalWebSessions.configure(webView, companyId)
        configure(webView)
        val entry = Entry(companyId, portal, wrapper, webView, isolated)
        webView.webViewClient = RetainedPortalClient(this, entry)
        webView.webChromeClient = RetainedChromeClient(this, entry)
        // Tamanho de tela de celular para a SPA renderizar enquanto não está anexado a uma janela.
        webView.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        webView.layout(0, 0, 1080, 1920)
        entries[companyId to portal] = entry
        return entry
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Autofill do Android/Google preenche o login; o app não armazena senha.
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            // Links do portal que abrem nova janela (target=_blank / window.open, ex.: "Licitação e Dispensa (novo)")
            // são carregados NESTE WebView pelo onCreateWindow ([openWindowInPlace]); nenhuma janela extra é exibida.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    // ------------------------------------------------------------------ tela

    /**
     * A tela do portal vai exibir o WebView: remove do pai antigo, troca o contexto para a Activity e retoma.
     * O chamador instala o próprio WebViewClient (subclasse de [RetainedPortalClient]) e WebChromeClient.
     */
    fun attach(entry: Entry, activity: Activity?) {
        (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
        entry.wrapper.baseContext = activity ?: app
        entry.attached = true
        resume(entry)
    }

    fun setVisible(entry: Entry, visible: Boolean) {
        entry.visible = visible && entry.attached
        if (visible) resume(entry) else if (!entry.keepAlive) pause(entry)
    }

    /**
     * A tela saiu: desanexa SEM destruir (sessionStorage/memória da SPA continuam), volta ao contexto de aplicação,
     * reinstala o cliente de segundo plano e pausa só se o keep-alive deste portal estiver desligado.
     */
    fun detach(entry: Entry) {
        (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
        entry.attached = false
        entry.visible = false
        entry.wrapper.baseContext = app
        if (entry.discarded) { destroy(entry); return }
        runCatching { entry.webView.webChromeClient = RetainedChromeClient(this, entry) }
        runCatching { entry.webView.webViewClient = RetainedPortalClient(this, entry) }
        runCatching { entry.webView.setDownloadListener(null) }
        PortalWebSessions.flush(entry.companyId)
        if (!entry.keepAlive) pause(entry)
    }

    /**
     * "Sair do portal": tira o WebView do holder (o próximo será novo, sem sessionStorage). Se a tela ainda o exibe,
     * é destruído no [detach].
     */
    fun discard(companyId: Long, portal: Portal) {
        val entry = entries.remove(companyId to portal) ?: return
        cancelAutoLogin(entry)
        entry.discarded = true
        if (!entry.attached) destroy(entry)
    }

    /** Troca de empresa / logout (companyId null): destrói os WebViews retidos das outras empresas. */
    fun retainOnly(companyId: Long?) {
        entries.values.filter { it.companyId != companyId }.forEach { discard(it.companyId, it.portal) }
    }

    /** Portais com "Manter sessão ativa" ligado para a empresa (os demais podem pausar fora da tela). */
    fun setKeepAlivePortals(companyId: Long?, portals: Set<Portal>) {
        entries.values.forEach { e ->
            val on = e.companyId == companyId && e.portal in portals
            e.keepAlive = on
            if (on) resume(e) else if (!e.visible) pause(e)
        }
    }

    private fun resume(entry: Entry) {
        if (!entry.paused) return
        entry.paused = false
        runCatching { entry.webView.onResume() }
    }

    private fun pause(entry: Entry) {
        if (entry.paused || entry.discarded) return
        entry.paused = true
        runCatching { entry.webView.onPause() } // nunca pauseTimers(): é global e pararia o keep-alive
    }

    private fun destroy(entry: Entry) {
        entry.probe?.invoke(ProbeEvent.Failed)
        entry.probe = null
        cancelAutoLogin(entry)
        PortalWebSessions.flush(entry.companyId)
        runCatching { entry.webView.stopLoading() }
        runCatching { entry.webView.webViewClient = WebViewClient() }
        runCatching { entry.webView.webChromeClient = null }
        runCatching { entry.webView.destroy() }
        entry.wrapper.baseContext = app
    }

    // ------------------------------------------------------------------ keep-alive

    /**
     * Um "toque" do keep-alive no WebView retido: `reload()` se está na área logada, ou abre [fallbackUrl] se a aba
     * está vazia (processo recriado) / fora da área logada. Nada é feito com o usuário na tela. Espera a navegação
     * assentar (redirecionamentos de SSO + renderização da SPA) e devolve a URL final + o booleano do
     * [PortalWebPolicy.contentProbeScript]. Sem cliques, sem formulários.
     */
    suspend fun keepAliveTouch(companyId: Long, portal: Portal, fallbackUrl: String?): TouchResult = withContext(Dispatchers.Main) {
        val entry = obtain(companyId, portal)
        val gate = entry.entryGate
        val action = PortalWebPolicy.keepAliveAction(portal, entry.webView.url, entry.visible, fallbackUrl, gate.reachedLoggedArea)
        if (action == PortalWebPolicy.KeepAliveAction.Skip) return@withContext TouchResult(null, false, skipped = true)
        resume(entry)
        val viaEntry = action is PortalWebPolicy.KeepAliveAction.Load && action.viaEntry
        val loadedEntry = action is PortalWebPolicy.KeepAliveAction.Load && action.url == PortalWebPolicy.startUrl(portal)
        val first = awaitNavigation(entry) { view ->
            when (action) {
                // Recarrega SÓ a página atual (área logada); nunca navega para outro lugar.
                PortalWebPolicy.KeepAliveAction.Reload -> view.reload()
                // Aba vazia (sem estado): entrada oficial; fora da área logada numa aba que já esteve nela: área de
                // trabalho. Nunca o cnetmobile.
                is PortalWebPolicy.KeepAliveAction.Load ->
                    view.loadUrl(PortalWebPolicy.safeLoadUrl(portal, action.url, gate.reachedLoggedArea))
                PortalWebPolicy.KeepAliveAction.Skip -> Unit
            }
        }.copy(viaEntry = viaEntry)
        val landed = first.finalUrl
        // Caiu no www.gov.br público (intro.htm/cnetmobile sem sessão): não está logado → UMA ida à entrada oficial
        // (com a sessão gov.br ainda válida ela volta à área logada; senão para no login e a política conclui EXPIRED).
        if (!loadedEntry && landed != null && PortalWebPolicy.isPublicLanding(portal, landed) && !entry.visible) {
            return@withContext awaitNavigation(entry) { view -> view.loadUrl(PortalWebPolicy.startUrl(portal)) }.copy(viaEntry = true)
        }
        // cnetmobile com "Não autorizado": volta UMA vez à área de trabalho (intro.htm, ainda logada) antes de concluir.
        if (!viaEntry && PortalWebPolicy.workspaceUrl(portal) != null && first.contentExpired && landed != null &&
            PortalWebPolicy.needsEntryFirst(portal, landed) && !entry.visible
        ) {
            // Estar no cnetmobile (aberto só pelo link da área logada) = a aba tem estado: pode voltar à área de trabalho.
            val back = PortalWebPolicy.reentryUrl(portal, gate.reachedLoggedArea || PortalWebPolicy.isLoggedArea(portal, landed))
            return@withContext awaitNavigation(entry) { view -> view.loadUrl(back) }.copy(viaEntry = true)
        }
        first
    }

    private suspend fun awaitNavigation(entry: Entry, start: (WebView) -> Unit): TouchResult = suspendCancellableCoroutine { cont ->
        val view = entry.webView
        val probeScript = PortalWebPolicy.contentProbeScript(entry.portal)
        val checkContent = PortalWebPolicy.hasContentMarkers(entry.portal)
        var done = false
        var pending: Runnable? = null
        lateinit var timeout: Runnable

        fun finish(result: TouchResult) {
            if (done) return
            done = true
            pending?.let(main::removeCallbacks)
            main.removeCallbacks(timeout)
            if (entry.probe != null) entry.probe = null
            PortalWebSessions.flush(entry.companyId)
            if (cont.isActive) cont.resume(result)
        }

        fun settle(url: String) {
            pending?.let(main::removeCallbacks)
            val first = Runnable {
                if (done) return@Runnable
                if (!checkContent) { finish(TouchResult(url, false)); return@Runnable }
                runCatching {
                    view.evaluateJavascript(probeScript) { raw1 ->
                        if (done) return@evaluateJavascript
                        if (PortalWebPolicy.parseProbeResult(raw1)) { finish(TouchResult(url, true)); return@evaluateJavascript }
                        // SPA: o aviso pode surgir depois da primeira renderização.
                        val second = Runnable {
                            if (done) return@Runnable
                            runCatching { view.evaluateJavascript(probeScript) { raw2 -> finish(TouchResult(url, PortalWebPolicy.parseProbeResult(raw2))) } }
                                .onFailure { finish(TouchResult(url, false)) }
                        }
                        pending = second
                        main.postDelayed(second, SETTLE_MS)
                    }
                }.onFailure { finish(TouchResult(null, false)) }
            }
            pending = first
            main.postDelayed(first, SETTLE_MS)
        }

        timeout = Runnable { finish(TouchResult(null, false)) }
        entry.probe = { event ->
            when (event) {
                ProbeEvent.Started -> pending?.let(main::removeCallbacks)
                is ProbeEvent.Finished -> settle(event.url)
                ProbeEvent.Failed -> finish(TouchResult(null, false))
            }
        }
        cont.invokeOnCancellation { main.post { finish(TouchResult(null, false)) } }
        main.postDelayed(timeout, TIMEOUT_MS)
        runCatching { start(view) }.onFailure { finish(TouchResult(null, false)) }
    }

    // ------------------------------------------------------------------ "Compras eletrônicas" (link do menu do portal)

    /**
     * Clica no link "Licitação e Dispensa (novo)" do menu do próprio portal ([PortalWebPolicy.electronicLinkScript]),
     * só se a aba está na área de trabalho logada. A URL usada é a que o portal fornece (com o token); o app nunca abre
     * o cnetmobile por `loadUrl` digitado. Uma janela nova aberta pelo link (onCreateWindow) nos próximos segundos é
     * carregada neste mesmo WebView.
     * @param onResult true = clicou; false = link não encontrado / fora da área de trabalho.
     */
    fun openElectronicPurchases(entry: Entry, onResult: (Boolean) -> Unit = {}) {
        val view = entry.webView
        val url = runCatching { view.url }.getOrNull()
        if (entry.discarded || !PortalWebPolicy.canGoToElectronicPurchases(entry.portal, url)) { onResult(false); return }
        entry.electronicClickAt = System.currentTimeMillis()
        runCatching {
            view.evaluateJavascript(PortalWebPolicy.electronicLinkScript(entry.portal)) { raw ->
                val ok = PortalWebPolicy.parseElectronicLinkResult(raw)
                if (!ok) entry.electronicClickAt = 0L
                onResult(ok)
            }
        }.onFailure { entry.electronicClickAt = 0L; onResult(false) }
    }

    /**
     * onCreateWindow: link do portal que abriria nova janela/aba. Aceita só com gesto do usuário ou logo após o clique
     * do app no link de compras eletrônicas. Cria um WebView TEMPORÁRIO (mesmo perfil da empresa) só para descobrir a
     * primeira URL da janela; ela é repassada a este WebView (se estiver na allowlist) e o temporário é destruído.
     */
    internal fun openWindowInPlace(entry: Entry, isUserGesture: Boolean, resultMsg: Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val recent = System.currentTimeMillis() - entry.electronicClickAt < WINDOW_AFTER_CLICK_MS
        if (entry.discarded || !(isUserGesture || recent)) return false
        val temp = runCatching { WebView(entry.wrapper) }.getOrNull() ?: return false
        PortalWebSessions.attachProfile(temp, entry.companyId)
        PortalWebSessions.configure(temp, entry.companyId)
        temp.settings.javaScriptEnabled = true
        temp.settings.domStorageEnabled = true
        temp.settings.allowFileAccess = false
        temp.settings.allowContentAccess = false
        temp.settings.setSupportMultipleWindows(false)
        var handed = false
        fun handOff(url: String?) {
            if (handed) return
            val u = url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: return
            handed = true
            runCatching { temp.stopLoading() }
            if (PortalWebPolicy.isAllowed(entry.portal, u) && !entry.discarded) {
                // URL fornecida pelo próprio portal (link do menu): carrega na MESMA aba.
                runCatching { entry.webView.loadUrl(u) }
            }
            main.post { runCatching { temp.destroy() } }
        }
        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                handOff(request.url.toString()); return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { handOff(url) }
        }
        // Nenhuma URL em tempo razoável: descarta o temporário.
        main.postDelayed({ if (!handed) { handed = true; runCatching { temp.destroy() } } }, WINDOW_AFTER_CLICK_MS)
        transport.webView = temp
        resultMsg.sendToTarget()
        entry.electronicClickAt = 0L
        return true
    }

    // ------------------------------------------------------------------ login automático com certificado

    /**
     * Inicia UMA tentativa de "Entrar automaticamente com certificado digital" neste WebView (ver [CertAutoLogin]).
     * @param loadStart abre a página oficial de login agora (reconexão); false = acompanha a navegação atual/próxima
     *        (abertura da tela: o chamador faz o `loadUrl`, ou a aba já está numa etapa do login).
     * @return false se já há uma tentativa em andamento ou o portal não tem o fluxo mapeado (nada é feito).
     */
    fun startAutoLogin(entry: Entry, cnpj: String, loadStart: Boolean, onOutcome: (CertAutoLogin.Outcome) -> Unit): Boolean {
        if (!CertAutoLogin.supports(entry.portal) || entry.discarded || entry.autoLogin != null) return false
        val session = AutoLoginSession(cnpj, onOutcome)
        entry.autoLogin = session
        val timeout = Runnable { if (entry.autoLogin === session) applyDecision(entry, session, session.run.onTimeout(), null) }
        session.timers += timeout
        main.postDelayed(timeout, AUTO_LOGIN_TIMEOUT_MS)
        resume(entry)
        val view = entry.webView
        if (loadStart) {
            runCatching { view.loadUrl(PortalWebPolicy.startUrl(entry.portal)) }
                .onFailure { finishAutoLogin(entry, session, CertAutoLogin.Outcome.Stopped(CertAutoLogin.StopReason.LOAD_FAILED)) }
        } else {
            // A aba já mostra uma página (tela reaberta): avalia-a agora; a próxima navegação continua o fluxo.
            view.url?.takeIf { it.isNotBlank() && it != "about:blank" && view.progress >= 100 }?.let { url ->
                main.post { onAutoLoginPage(entry, url) }
            }
        }
        return true
    }

    /** Cancela a tentativa em andamento sem resultado (ex.: "Sair do portal", WebView destruído). */
    fun cancelAutoLogin(entry: Entry) {
        val s = entry.autoLogin ?: return
        s.timers.forEach(main::removeCallbacks)
        s.timers.clear()
        entry.autoLogin = null
    }

    /** Página começou a carregar: conta como progresso da etapa. */
    internal fun onAutoLoginProgress(entry: Entry) {
        entry.autoLogin?.let { it.progress++ }
    }

    /** Falha de carga do documento principal durante a tentativa. */
    internal fun onAutoLoginLoadFailed(entry: Entry) {
        val s = entry.autoLogin ?: return
        applyDecision(entry, s, s.run.onPage(CertAutoLogin.Step.OTHER, loadFailed = true), null)
    }

    /** onPageStarted: rastro do retorno do gov.br (`landing_sso.asp`) até a página de entrada seguinte. */
    internal fun onPageStartedInternal(entry: Entry, url: String) {
        when {
            PortalInstability.isLandingSso(entry.portal, url) -> entry.afterLandingSso = true
            PortalInstability.isEntryLoginPage(entry.portal, url) -> Unit // mantém: é a volta do landing_sso
            else -> entry.afterLandingSso = false
        }
    }

    /**
     * onPageFinished (tela ou segundo plano). Na entrada do Comprasnet (`loginPortal.asp`), checa NA PÁGINA se ela mostra
     * o 503 do portal ([PortalInstability.probeScript], só true/false): emite [instability] e o login automático em
     * andamento para com [CertAutoLogin.StopReason.PORTAL_UNSTABLE]. Repete a checagem uma vez (texto montado depois).
     */
    internal fun onPageDone(entry: Entry, url: String) {
        if (!PortalInstability.isEntryLoginPage(entry.portal, url)) { onAutoLoginPage(entry, url); return }
        val view = entry.webView
        val script = PortalInstability.probeScript()
        var delivered = false
        fun deliver(matched: Boolean) {
            if (delivered) return
            delivered = true
            val unstable = PortalInstability.isUnstableProbe(entry.portal, url, matched)
            if (unstable) reportUnstable(entry)
            onAutoLoginPage(entry, url, unstable)
        }
        runCatching { view.evaluateJavascript(script) { raw -> deliver(PortalInstability.parseProbe(raw)) } }.onFailure { deliver(false) }
        // evaluateJavascript sem resposta (WebView pausado): segue o fluxo sem a checagem.
        main.postDelayed({ deliver(false) }, PROBE_FALLBACK_MS)
        main.postDelayed({
            if (entry.discarded || runCatching { view.url }.getOrNull() != url) return@postDelayed
            runCatching {
                view.evaluateJavascript(script) { raw ->
                    if (PortalInstability.isUnstableProbe(entry.portal, url, PortalInstability.parseProbe(raw))) {
                        reportUnstable(entry)
                        entry.autoLogin?.let { s -> applyDecision(entry, s, s.run.stop(CertAutoLogin.StopReason.PORTAL_UNSTABLE), null) }
                    }
                }
            }
        }, LATE_PROBE_MS)
    }

    private fun reportUnstable(entry: Entry) {
        val now = System.currentTimeMillis()
        if (now - entry.unstableReportedAt < UNSTABLE_DEDUP_MS) return
        entry.unstableReportedAt = now
        _instability.tryEmit(UnstableEvent(entry.companyId, entry.portal, entry.afterLandingSso))
    }

    /** onPageFinished de cada etapa: decide (pura) e, se for o caso, roda o script curto de clique. */
    internal fun onAutoLoginPage(entry: Entry, url: String, unstable: Boolean = false) {
        val s = entry.autoLogin ?: return
        val now = System.currentTimeMillis()
        // onPageFinished duplicado para a mesma página não conta como "etapa repetida".
        if (!unstable && url == s.lastUrl && now - s.lastAt < DUPLICATE_PAGE_MS) return
        s.lastUrl = url
        s.lastAt = now
        s.progress++
        val step = CertAutoLogin.detectStep(entry.portal, url)
        applyDecision(entry, s, s.run.onPage(step, unstable = unstable), url)
    }

    // ------------------------------------------------------------------ "Compras.gov.br instável": nova tentativa

    /**
     * UMA nova tentativa após o 503 (chamada só pelo keep-alive controller, agendador único): abre a entrada oficial e,
     * com [autoLogin] (e certificado lembrado), segue o fluxo do login automático; senão só verifica se a entrada
     * carrega sem o 503. Com o usuário na tela, só age se a aba ainda está na entrada do Comprasnet.
     */
    suspend fun unstableRetryAttempt(companyId: Long, portal: Portal, cnpj: String?, autoLogin: Boolean): PortalInstability.AttemptResult {
        val canAuto = autoLogin && cnpj != null && CertAutoLogin.supports(portal) && hasRememberedCertificate(companyId, portal)
        return withContext(Dispatchers.Main) {
            val entry = obtain(companyId, portal)
            if (entry.discarded || entry.autoLogin != null) return@withContext PortalInstability.AttemptResult.BUSY
            if (entry.visible) {
                val u = runCatching { entry.webView.url }.getOrNull()
                if (u == null || !PortalInstability.isEntryLoginPage(portal, u)) return@withContext PortalInstability.AttemptResult.BUSY
            }
            resume(entry)
            if (canAuto) {
                val outcome = suspendCancellableCoroutine<CertAutoLogin.Outcome> { cont ->
                    val started = startAutoLogin(entry, cnpj!!, loadStart = true) { o -> if (cont.isActive) cont.resume(o) }
                    if (!started) cont.resume(CertAutoLogin.Outcome.Stopped(CertAutoLogin.StopReason.BUSY))
                    else cont.invokeOnCancellation { main.post { cancelAutoLogin(entry) } }
                }
                return@withContext when (outcome) {
                    CertAutoLogin.Outcome.Success, CertAutoLogin.Outcome.NotNeeded -> PortalInstability.AttemptResult.LOGGED_IN
                    is CertAutoLogin.Outcome.Stopped -> when (outcome.reason) {
                        CertAutoLogin.StopReason.PORTAL_UNSTABLE, CertAutoLogin.StopReason.ERROR_PAGE,
                        CertAutoLogin.StopReason.LOAD_FAILED -> PortalInstability.AttemptResult.UNSTABLE
                        CertAutoLogin.StopReason.TIMEOUT -> PortalInstability.AttemptResult.INCONCLUSIVE
                        CertAutoLogin.StopReason.BUSY -> PortalInstability.AttemptResult.BUSY
                        else -> PortalInstability.AttemptResult.STOPPED
                    }
                }
            }
            val result = awaitNavigation(entry) { view -> view.loadUrl(PortalWebPolicy.startUrl(portal)) }
            val finalUrl = result.finalUrl ?: return@withContext PortalInstability.AttemptResult.UNSTABLE
            when {
                PortalWebPolicy.isLoggedArea(portal, finalUrl) && !result.contentExpired -> PortalInstability.AttemptResult.LOGGED_IN
                PortalInstability.isUnstableProbe(portal, finalUrl, evaluateBoolean(entry, PortalInstability.probeScript())) ->
                    PortalInstability.AttemptResult.UNSTABLE
                else -> PortalInstability.AttemptResult.AVAILABLE
            }
        }
    }

    /** `evaluateJavascript` que devolve só true/false (false em falha/sem resposta). */
    private suspend fun evaluateBoolean(entry: Entry, script: String): Boolean = suspendCancellableCoroutine { cont ->
        var done = false
        fun finish(v: Boolean) { if (!done) { done = true; if (cont.isActive) cont.resume(v) } }
        runCatching { entry.webView.evaluateJavascript(script) { raw -> finish(PortalWebPolicy.parseProbeResult(raw)) } }.onFailure { finish(false) }
        main.postDelayed({ finish(false) }, PROBE_FALLBACK_MS)
    }

    private fun applyDecision(entry: Entry, s: AutoLoginSession, decision: CertAutoLogin.Decision, url: String?) {
        if (entry.autoLogin !== s) return
        when (decision) {
            is CertAutoLogin.Decision.Execute -> {
                val view = entry.webView
                if (decision.step == CertAutoLogin.Step.UNAUTHORIZED) {
                    // "Não autorizado" no cnetmobile: volta à área de trabalho (intro.htm) se a aba já esteve na área
                    // logada; senão à entrada oficial. Nunca o cnetmobile por URL.
                    val current = runCatching { view.url }.getOrNull()
                    val hasState = entry.entryGate.reachedLoggedArea || (current != null && PortalWebPolicy.isLoggedArea(entry.portal, current))
                    val back = PortalWebPolicy.reentryUrl(entry.portal, hasState)
                    runCatching { view.loadUrl(back) }
                        .onFailure { applyDecision(entry, s, s.run.stop(CertAutoLogin.StopReason.LOAD_FAILED), null) }
                    return
                }
                val script = CertAutoLogin.script(decision.step, s.cnpj) ?: return
                // Páginas como o SSO gov.br montam os botões depois do onPageFinished: "notfound" é repetido
                // algumas vezes antes de desistir.
                var notFoundTries = 0
                lateinit var run: Runnable
                run = Runnable {
                    if (entry.autoLogin !== s) return@Runnable
                    if (url != null && view.url != url) return@Runnable // já navegou: a nova página conduz o fluxo
                    val before = s.progress
                    runCatching {
                        view.evaluateJavascript(script) { raw ->
                            if (entry.autoLogin !== s) return@evaluateJavascript
                            val parsed = CertAutoLogin.parseResult(raw)
                            if (parsed == CertAutoLogin.ScriptResult.NOT_FOUND && notFoundTries < NOT_FOUND_RETRIES) {
                                notFoundTries++
                                main.postDelayed(run, NOT_FOUND_RETRY_MS)
                                return@evaluateJavascript
                            }
                            val next = s.run.onScriptResult(parsed)
                            if (next != CertAutoLogin.Decision.Wait) { applyDecision(entry, s, next, null); return@evaluateJavascript }
                            // Clicou: se nenhuma página nova começar dentro do prazo, o botão não levou a lugar algum.
                            val stepTimer = Runnable {
                                if (entry.autoLogin === s && s.progress == before) {
                                    applyDecision(entry, s, s.run.stop(CertAutoLogin.StopReason.NOT_FOUND), null)
                                }
                            }
                            s.timers += stepTimer
                            main.postDelayed(stepTimer, STEP_TIMEOUT_MS)
                        }
                    }.onFailure { applyDecision(entry, s, s.run.stop(CertAutoLogin.StopReason.NOT_FOUND), null) }
                }
                s.timers += run
                // Pequena espera: a página termina de montar (cards, botões) depois do onPageFinished.
                main.postDelayed(run, SCRIPT_DELAY_MS)
            }
            CertAutoLogin.Decision.Wait -> Unit
            is CertAutoLogin.Decision.Stop -> finishAutoLogin(entry, s, CertAutoLogin.Outcome.Stopped(decision.reason))
            CertAutoLogin.Decision.Success, CertAutoLogin.Decision.NotNeeded -> {
                // Chegou à área logada: confirma que a SPA não mostra "Não autorizado" antes de declarar o resultado.
                // Com o aviso: UMA navegação para a entrada oficial (etapa UNAUTHORIZED); na segunda, SESSION_REJECTED.
                val ok = if (decision == CertAutoLogin.Decision.Success || s.run.reentered) CertAutoLogin.Outcome.Success else CertAutoLogin.Outcome.NotNeeded
                val view = entry.webView
                val check = Runnable {
                    if (entry.autoLogin !== s) return@Runnable
                    if (!PortalWebPolicy.hasContentMarkers(entry.portal)) { finishAutoLogin(entry, s, ok); return@Runnable }
                    runCatching {
                        view.evaluateJavascript(PortalWebPolicy.contentProbeScript(entry.portal)) { raw ->
                            if (entry.autoLogin !== s) return@evaluateJavascript
                            if (PortalWebPolicy.parseProbeResult(raw)) applyDecision(entry, s, s.run.onUnauthorized(), null)
                            else finishAutoLogin(entry, s, ok)
                        }
                    }.onFailure { finishAutoLogin(entry, s, ok) }
                }
                s.timers += check
                main.postDelayed(check, SETTLE_MS)
            }
        }
    }

    private fun finishAutoLogin(entry: Entry, s: AutoLoginSession, outcome: CertAutoLogin.Outcome) {
        if (entry.autoLogin !== s) return
        s.timers.forEach(main::removeCallbacks)
        s.timers.clear()
        entry.autoLogin = null
        PortalWebSessions.flush(entry.companyId)
        runCatching { s.onOutcome(outcome) }
    }

    /**
     * Relogin automático pelo keep-alive (sessão expirou, app em primeiro ou segundo plano, tela do portal fora de
     * vista): abre o login oficial no WebView retido e conduz as etapas. Uma tentativa por chamada.
     */
    suspend fun autoRelogin(companyId: Long, portal: Portal, cnpj: String): CertAutoLogin.Outcome {
        if (!CertAutoLogin.supports(portal)) return CertAutoLogin.Outcome.Stopped(CertAutoLogin.StopReason.NOT_FOUND)
        if (!hasRememberedCertificate(companyId, portal)) return CertAutoLogin.Outcome.Stopped(CertAutoLogin.StopReason.NO_CERTIFICATE)
        return withContext(Dispatchers.Main) {
            val entry = obtain(companyId, portal)
            if (entry.visible) return@withContext CertAutoLogin.Outcome.Stopped(CertAutoLogin.StopReason.BUSY)
            suspendCancellableCoroutine<CertAutoLogin.Outcome> { cont ->
                val started = startAutoLogin(entry, cnpj, loadStart = true) { outcome -> if (cont.isActive) cont.resume(outcome) }
                if (!started) {
                    cont.resume(CertAutoLogin.Outcome.Stopped(CertAutoLogin.StopReason.BUSY))
                } else {
                    cont.invokeOnCancellation { main.post { cancelAutoLogin(entry) } }
                }
            }
        }
    }

    /** Há certificado (alias do KeyChain) lembrado para algum host deste portal na empresa. */
    suspend fun hasRememberedCertificate(companyId: Long, portal: Portal): Boolean {
        val hosts = runCatching { PortalWebPolicy.parseHostIndex(secrets.get(PortalWebPolicy.clientCertIndexKey(companyId))) }.getOrDefault(emptySet())
        return hosts.any { host ->
            PortalWebPolicy.clientCertAllowed(portal, host) &&
                runCatching { secrets.get(PortalWebPolicy.clientCertAliasKey(companyId, host)) }.getOrNull() != null
        }
    }

    // ------------------------------------------------------------------ certificado digital (KeyChain)

    /**
     * Pedido de certificado do cliente (TLS) — ex.: gov.br "Seu certificado digital". Só para hosts da allowlist.
     * Alias lembrado por (empresa, host) → usa direto; senão, com a tela visível, abre o seletor do sistema
     * (`KeyChain.choosePrivateKeyAlias`). Cancelado pelo usuário → `request.cancel()`. Em segundo plano (keep-alive)
     * sem alias lembrado → cancela (nunca abre diálogo sem o usuário na tela).
     */
    internal fun onClientCertRequest(entry: Entry, request: ClientCertRequest) {
        val host = request.host
        if (!PortalWebPolicy.clientCertAllowed(entry.portal, host)) { runCatching { request.cancel() }; return }
        val key = PortalWebPolicy.clientCertAliasKey(entry.companyId, host)
        scope.launch {
            val remembered = runCatching { secrets.get(key) }.getOrNull()
            if (remembered != null) {
                if (proceedWith(request, remembered)) return@launch
                forgetHost(entry.companyId, host) // certificado removido do aparelho / permissão revogada
            }
            val activity = entry.wrapper.baseContext as? Activity
            if (activity == null || !entry.visible || activity.isFinishing) { runCatching { request.cancel() }; return@launch }
            runCatching {
                KeyChain.choosePrivateKeyAlias(
                    activity,
                    { alias ->
                        // Chamado numa thread de binder.
                        if (alias == null) { runCatching { request.cancel() }; return@choosePrivateKeyAlias }
                        scope.launch {
                            if (proceedWith(request, alias)) remember(entry.companyId, host, alias) else runCatching { request.cancel() }
                        }
                    },
                    request.keyTypes, request.principals, request.host, request.port, null,
                )
            }.onFailure { runCatching { request.cancel() } }
        }
    }

    /** Obtém chave + cadeia do KeyChain em segundo plano e entrega ao WebView. A chave não sai do KeyChain/WebView. */
    private suspend fun proceedWith(request: ClientCertRequest, alias: String): Boolean {
        val material: Pair<PrivateKey, Array<X509Certificate>>? = withContext(Dispatchers.IO) {
            runCatching {
                val pk = KeyChain.getPrivateKey(app, alias)
                val chain = KeyChain.getCertificateChain(app, alias)
                if (pk != null && !chain.isNullOrEmpty()) pk to chain else null
            }.getOrNull()
        }
        val (pk, chain) = material ?: return false
        return runCatching { request.proceed(pk, chain) }.isSuccess
    }

    private suspend fun remember(companyId: Long, host: String, alias: String) {
        runCatching {
            secrets.put(PortalWebPolicy.clientCertAliasKey(companyId, host), alias)
            val indexKey = PortalWebPolicy.clientCertIndexKey(companyId)
            val hosts = PortalWebPolicy.parseHostIndex(secrets.get(indexKey)) + host.lowercase()
            secrets.put(indexKey, PortalWebPolicy.formatHostIndex(hosts))
        }
    }

    private suspend fun forgetHost(companyId: Long, host: String) {
        runCatching {
            secrets.remove(PortalWebPolicy.clientCertAliasKey(companyId, host))
            val indexKey = PortalWebPolicy.clientCertIndexKey(companyId)
            val hosts = PortalWebPolicy.parseHostIndex(secrets.get(indexKey)) - host.lowercase()
            if (hosts.isEmpty()) secrets.remove(indexKey) else secrets.put(indexKey, PortalWebPolicy.formatHostIndex(hosts))
        }
    }

    /**
     * "Trocar certificado": esquece os aliases dos hosts deste portal (empresa) e limpa a escolha que o WebView
     * guarda em memória; na próxima exigência do site o seletor do sistema abre de novo.
     */
    suspend fun forgetClientCertificate(companyId: Long, portal: Portal) {
        val indexKey = PortalWebPolicy.clientCertIndexKey(companyId)
        val hosts = runCatching { PortalWebPolicy.parseHostIndex(secrets.get(indexKey)) }.getOrDefault(emptySet())
        hosts.filter { PortalWebPolicy.clientCertAllowed(portal, it) }.forEach { forgetHost(companyId, it) }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                runCatching { WebView.clearClientCertPreferences { if (cont.isActive) cont.resume(Unit) } }
                    .onFailure { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        /** Espera após cada onPageFinished: redirecionamentos de SSO e renderização da SPA. */
        const val SETTLE_MS = 2_500L
        /** Tempo máximo de uma tentativa de login automático. */
        const val AUTO_LOGIN_TIMEOUT_MS = 90_000L
        /** Depois de um clique, uma página nova precisa começar dentro deste prazo. */
        const val STEP_TIMEOUT_MS = 20_000L
        /** Espera após onPageFinished antes de rodar o script da etapa. */
        const val SCRIPT_DELAY_MS = 900L
        /** ~8 s de novas tentativas quando o botão esperado ainda não apareceu. */
        const val NOT_FOUND_RETRIES = 10
        const val NOT_FOUND_RETRY_MS = 800L
        /** onPageFinished repetido da mesma URL dentro deste intervalo é ignorado. */
        const val DUPLICATE_PAGE_MS = 1_500L
        /** Janela nova aceita (onCreateWindow sem gesto) até este tempo após o clique no link de compras eletrônicas. */
        const val WINDOW_AFTER_CLICK_MS = 10_000L
        /** Sem resposta do evaluateJavascript da checagem do 503 dentro deste prazo: segue sem ela. */
        const val PROBE_FALLBACK_MS = 1_500L
        /** Segunda checagem do 503 (texto montado depois do onPageFinished). */
        const val LATE_PROBE_MS = 2_500L
        /** Mesma página de 503 avaliada de novo dentro deste intervalo não gera novo evento. */
        const val UNSTABLE_DEDUP_MS = 10_000L
    }
}

/**
 * WebChromeClient base do WebView retido: links do portal que abririam nova janela são carregados na mesma aba
 * ([PortalWebViewHolder.openWindowInPlace]). A tela estende este cliente (progresso, seletor de arquivos).
 */
open class RetainedChromeClient(
    private val holder: PortalWebViewHolder,
    private val entry: PortalWebViewHolder.Entry,
) : WebChromeClient() {
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean =
        holder.openWindowInPlace(entry, isUserGesture, resultMsg)
}

/**
 * Cliente base do WebView retido: allowlist do portal, avisos ao keep-alive em andamento e certificado digital.
 * A tela do portal estende este cliente (chamando `super`) para atualizar a interface.
 */
open class RetainedPortalClient(
    private val holder: PortalWebViewHolder,
    private val entry: PortalWebViewHolder.Entry,
) : WebViewClient() {

    /** Navegação de main frame fora da allowlist (bloqueada). */
    open fun onBlocked(url: String) = Unit

    private fun allowed(url: String) = PortalWebPolicy.isAllowed(entry.portal, url)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (allowed(url)) return false
        if (request.isForMainFrame) {
            entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
            onBlocked(url)
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (!allowed(url)) {
            view.stopLoading()
            entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
            onBlocked(url)
            return
        }
        entry.entryGate.onPageStarted(url)
        holder.onPageStartedInternal(entry, url)
        entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Started)
        holder.onAutoLoginProgress(entry)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!allowed(url)) return
        // Rastro da vida do WebView: esteve na área logada (pode voltar à área de trabalho) / caiu no www.gov.br (não pode).
        entry.entryGate.onPageLoaded(url)
        // Grava cookies renovados pelo portal a cada navegação concluída.
        PortalWebSessions.flush(entry.companyId)
        entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Finished(url))
        // Entrada do Comprasnet com o 503 do portal → "Compras.gov.br instável". Login automático com certificado (se
        // houver tentativa em andamento): decide a etapa e clica (ou para com PORTAL_UNSTABLE).
        holder.onPageDone(entry, url)
    }

    // Falha de carga do documento (sem rede, DNS, timeout) = inconclusivo, nunca "sessão encerrada".
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
            holder.onAutoLoginLoadFailed(entry)
        }
    }

    // HTTP ≥ 500 / 408 / 429 no documento: servidor/rede com problema, não resposta de sessão.
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame && PortalWebPolicy.isServerFailure(errorResponse.statusCode)) {
            entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
            holder.onAutoLoginLoadFailed(entry)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
        holder.onAutoLoginLoadFailed(entry)
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        holder.onClientCertRequest(entry, request)
    }
}
