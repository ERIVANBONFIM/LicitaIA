package com.licitaia.feature.live.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
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
 *   sobrevive a reload) ou abre a última URL quando a aba está vazia (processo recriado).
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
    )

    private val entries = LinkedHashMap<Pair<Long, Portal>, Entry>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
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
        runCatching { entry.webView.webChromeClient = WebChromeClient() }
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
        val action = PortalWebPolicy.keepAliveAction(portal, entry.webView.url, entry.visible, fallbackUrl)
        if (action == PortalWebPolicy.KeepAliveAction.Skip) return@withContext TouchResult(null, false, skipped = true)
        resume(entry)
        awaitNavigation(entry) { view ->
            when (action) {
                PortalWebPolicy.KeepAliveAction.Reload -> view.reload()
                is PortalWebPolicy.KeepAliveAction.Load -> view.loadUrl(action.url)
                PortalWebPolicy.KeepAliveAction.Skip -> Unit
            }
        }
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
    }
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
        entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Started)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!allowed(url)) return
        // Grava cookies renovados pelo portal a cada navegação concluída.
        PortalWebSessions.flush(entry.companyId)
        entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Finished(url))
    }

    // Falha de carga do documento (sem rede, DNS, timeout) = inconclusivo, nunca "sessão encerrada".
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
    }

    // HTTP ≥ 500 / 408 / 429 no documento: servidor/rede com problema, não resposta de sessão.
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame && PortalWebPolicy.isServerFailure(errorResponse.statusCode)) {
            entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        entry.probe?.invoke(PortalWebViewHolder.ProbeEvent.Failed)
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        holder.onClientCertRequest(entry, request)
    }
}
