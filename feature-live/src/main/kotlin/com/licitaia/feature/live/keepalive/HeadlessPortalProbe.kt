package com.licitaia.feature.live.keepalive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.licitaia.domain.model.Portal
import com.licitaia.feature.live.web.PortalWebPolicy
import com.licitaia.feature.live.web.PortalWebSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * WebView headless (contexto de aplicação, nunca anexado a uma janela) que recarrega UMA página da área
 * logada do portal com o mesmo perfil/cookies da empresa ([PortalWebSessions]) e devolve onde a navegação
 * terminou + o booleano do [PortalWebPolicy.contentProbeScript].
 *
 * Mesmas restrições do navegador interno: sem acesso a arquivos/conteúdo, sem interface JS, sem janelas,
 * HTTPS e allowlist do portal. Não lê nem preenche nada; não clica em nada. Destruído ao terminar.
 *
 * Perfil: com `MULTI_PROFILE` usa `licitaia-<companyId>`; sem suporte, o perfil padrão do WebView do app
 * (o mesmo usado pela tela do portal nesse aparelho).
 */
internal object HeadlessPortalProbe {

    data class Result(
        /** URL onde a navegação assentou; null = falha de rede/timeout/bloqueio (inconclusivo). */
        val finalUrl: String?,
        val contentExpired: Boolean,
    )

    private const val TIMEOUT_MS = 60_000L
    /** Espera após cada onPageFinished: redirecionamentos de SSO e renderização da SPA. */
    private const val SETTLE_MS = 2_500L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(context: Context, companyId: Long, portal: Portal, url: String): Result = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var done = false
            var pending: Runnable? = null

            fun finish(result: Result) {
                if (done) return
                done = true
                pending?.let(handler::removeCallbacks)
                handler.removeCallbacksAndMessages(null)
                webView?.let { view ->
                    runCatching { view.stopLoading() }
                    runCatching { view.webViewClient = WebViewClient() }
                    runCatching { view.destroy() }
                }
                webView = null
                PortalWebSessions.flush(companyId)
                if (cont.isActive) cont.resume(result)
            }

            val probeScript = PortalWebPolicy.contentProbeScript(portal)
            val checkContent = PortalWebPolicy.hasContentMarkers(portal)

            fun scheduleSettle(view: WebView, finishedUrl: String) {
                pending?.let(handler::removeCallbacks)
                val first = Runnable {
                    if (done) return@Runnable
                    if (!checkContent) { finish(Result(finishedUrl, false)); return@Runnable }
                    view.evaluateJavascript(probeScript) { raw1 ->
                        if (done) return@evaluateJavascript
                        if (PortalWebPolicy.parseProbeResult(raw1)) { finish(Result(finishedUrl, true)); return@evaluateJavascript }
                        // SPA: o aviso pode surgir depois da primeira renderização.
                        val second = Runnable {
                            if (done) return@Runnable
                            view.evaluateJavascript(probeScript) { raw2 -> finish(Result(finishedUrl, PortalWebPolicy.parseProbeResult(raw2))) }
                        }
                        pending = second
                        handler.postDelayed(second, SETTLE_MS)
                    }
                }
                pending = first
                handler.postDelayed(first, SETTLE_MS)
            }

            val created = runCatching {
                WebView(context.applicationContext).apply {
                    // Perfil da empresa antes de qualquer carregamento.
                    PortalWebSessions.attachProfile(this, companyId)
                    PortalWebSessions.configure(this, companyId)
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        allowFileAccess = false
                        allowContentAccess = false
                        setGeolocationEnabled(false)
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        blockNetworkImage = true // só precisamos do documento; economiza dados
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    // Tamanho de tela de celular para a SPA renderizar normalmente (sem anexar a janela).
                    measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                    layout(0, 0, 1080, 1920)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (PortalWebPolicy.isAllowed(portal, request.url.toString())) return false
                            if (request.isForMainFrame) finish(Result(null, false))
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (!PortalWebPolicy.isAllowed(portal, url)) { finish(Result(null, false)); return }
                            pending?.let(handler::removeCallbacks)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (done || !PortalWebPolicy.isAllowed(portal, url)) return
                            // Grava cookies renovados pelo portal a cada navegação concluída.
                            PortalWebSessions.flush(companyId)
                            scheduleSettle(view, url)
                        }

                        // Falha de carga do documento (sem rede, DNS, timeout) = inconclusivo, nunca "sessão encerrada".
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) finish(Result(null, false))
                        }

                        // HTTP ≥ 500 / 408 / 429 no documento: servidor/rede com problema, não resposta de sessão.
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            if (request.isForMainFrame && PortalWebPolicy.isServerFailure(errorResponse.statusCode)) finish(Result(null, false))
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            finish(Result(null, false))
                        }
                    }
                }
            }.getOrNull()

            if (created == null) {
                if (cont.isActive) cont.resume(Result(null, false))
                return@suspendCancellableCoroutine
            }
            webView = created
            handler.postDelayed({ finish(Result(null, false)) }, TIMEOUT_MS)
            cont.invokeOnCancellation { handler.post { finish(Result(null, false)) } }
            created.loadUrl(url)
        }
    }
}
