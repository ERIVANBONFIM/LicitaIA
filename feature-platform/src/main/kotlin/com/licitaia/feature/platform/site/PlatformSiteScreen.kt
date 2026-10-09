package com.licitaia.feature.platform.site

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.licitaia.core.ui.nav.LocalAppNavigator
import java.io.ByteArrayInputStream

/**
 * O SITE da VPS dentro do app (MODELO B), já logado com a conta da plataforma — o mesmo papel do programa do PC.
 *
 * O que o site pede e precisa do certificado (Registrar Proposta, Portais, Robô de Registro, Navegadores, Logs,
 * Certificado, IA, "Ver a tela do robô") roda NESTE celular pela ponte `LicitaApp` ([SiteLocal]). A disputa de lances
 * (Preparar/Participar) ainda fica segurada aqui com aviso — nunca chega ao robô da VPS.
 *
 * O navegador (WebView) mora no ViewModel: ao abrir uma tela nativa (Compras.gov, IA, Portais) e voltar, o site
 * continua na mesma página.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlatformSiteScreen(viewModel: PlatformSiteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    // seletor de arquivos sempre da composição atual (o WebView sobrevive entre telas)
    SideEffect {
        viewModel.abrirArquivos = { cb, params ->
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            try { fileLauncher.launch(params.createIntent()); true } catch (e: ActivityNotFoundException) { fileCallback = null; false }
        }
    }

    // Voltar do celular: 1º fecha o menu/aviso aberto no site; 2º volta a página; só então sai do app.
    BackHandler {
        val wv = viewModel.webView ?: run { activity?.moveTaskToBack(true); return@BackHandler }
        wv.evaluateJavascript(JS_FECHAR_ABERTO) { r ->
            if (r == "true") return@evaluateJavascript
            if (wv.canGoBack()) wv.goBack() else activity?.moveTaskToBack(true)
        }
    }

    // Telas nativas pedidas pelo site (Compras.gov deste celular, IA, Portais/certificado)
    val navigator = LocalAppNavigator.current
    val ctxAtual = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.abrir.collect {
            if (it.startsWith("externo:")) abrirFora(ctxAtual, Uri.parse(it.removePrefix("externo:"))) else navigator.navigate(it)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F7F6))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val auth = (state as? PlatformSiteViewModel.State.Ready)
        if (auth == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF00874A))
            return@Box
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val existente = viewModel.webView
                if (existente != null) {
                    (existente.parent as? ViewGroup)?.removeView(existente)
                    (existente.context as? MutableContextWrapper)?.baseContext = ctx
                    existente
                } else {
                    criarWebView(MutableContextWrapper(ctx), viewModel, auth) { cb, p -> viewModel.abrirArquivos?.invoke(cb, p) ?: false }
                        .also { viewModel.webView = it; it.loadUrl(auth.siteUrl) }
                }
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun criarWebView(
    ctx: Context,
    viewModel: PlatformSiteViewModel,
    auth: PlatformSiteViewModel.State.Ready,
    abrirArquivos: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
): WebView = WebView(ctx).apply {
    val origin = Uri.parse(auth.siteUrl).let { it.scheme + "://" + it.host }
    val script = PlatformSiteScript.build(auth.token, auth.userJson)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.setSupportZoom(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false) // window.open/target=_blank caem no shouldOverrideUrlLoading
    CookieManager.getInstance().setAcceptCookie(true)

    // Ponte: o que o site pede e precisa do certificado roda NESTE celular (nunca no robô da VPS).
    val wv = this
    addJavascriptInterface(object {
        @JavascriptInterface
        fun handles(method: String?, path: String?): String =
            if (path != null && viewModel.handles(method.orEmpty(), path)) "1" else "0"

        @JavascriptInterface
        fun request(id: Int, method: String?, path: String?, body: String?) {
            viewModel.request(method.orEmpty(), path.orEmpty(), body.orEmpty()) { st, json ->
                wv.post { wv.evaluateJavascript("window.__lzResp($id,$st,${org.json.JSONObject.quote(json)})", null) }
            }
        }
    }, "LicitaApp")

    val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (docStart) WebViewCompat.addDocumentStartJavaScript(this, script, setOf(origin))

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if ((u.scheme + "://" + u.host) == origin) {
                val p = u.path.orEmpty()
                if (p.startsWith("/api/")) { baixar(view.context, u.toString(), auth.token, null, null); return true }
                // "Ver a tela do robô" (link do noVNC): no app é a tela do Compras.gov deste celular
                if (p.startsWith("/vnc/")) { viewModel.request("POST", "/api/vnc/session", "") { _, _ -> }; return true }
                return false
            }
            abrirFora(view.context, u) // links de fora (PNCP, Compras.gov, e-mail...) abrem fora do app
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if ((u.scheme + "://" + u.host) != origin) return null
            val msg = SiteGuard.bloqueio(request.method, u.path.orEmpty()) ?: return null
            return json409(msg)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            // WebView antigo sem script no início da página: injeta aqui (o login entra no 2º carregamento)
            if (!docStart && url != null && url.startsWith(origin)) view.evaluateJavascript(script, null)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // "Sair" no site (ou sessão vencida) → encerra a conta da plataforma no app também
            if (url != null && Uri.parse(url).path == "/login") viewModel.onSiteLoggedOut()
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            abrirArquivos(callback, params)
    }
    setDownloadListener { url, ua, cd, mime, _ -> baixar(context, url, auth.token, cd, mime, ua) }
}

/** A disputa de lances do site ainda não roda no celular: segura aqui (nunca vai ao robô da VPS). */
internal object SiteGuard {
    private val REGRAS: List<Pair<String, Regex>> = listOf(
        "POST" to Regex("^/api/robo-lances/preparar(/.*)?$"),
    )

    fun bloqueio(method: String?, path: String): String? {
        val m = method.orEmpty().uppercase()
        return if (REGRAS.any { (mm, re) -> mm == m && re.matches(path) }) MSG_LANCE else null
    }
}

/** Fecha o que estiver aberto por cima no site (menu lateral, notificações, modal). true = fechou algo. */
private const val JS_FECHAR_ABERTO = """(function(){
  var ov=document.querySelector('.sidebar-overlay'); var sb=document.querySelector('.layout-sidebar.mobile-open');
  if(sb){ if(ov) ov.click(); else sb.classList.remove('mobile-open'); return true; }
  var nd=document.querySelector('.notif-dropdown'); if(nd){ var b=document.querySelector('.header-bell'); if(b) b.click(); return true; }
  var fx=document.querySelector('[role=dialog] [aria-label*=echar],[role=dialog] .close,.modal-overlay,.modal-backdrop');
  if(fx){ fx.click(); return true; }
  return false; })()"""

private const val MSG_LANCE =
    "A disputa de lances pelo celular está em construção: o robô de lance do celular (que sugere e você confirma) entra na próxima etapa. " +
        "Por enquanto use o programa do PC para a disputa."

private fun json409(msg: String): WebResourceResponse {
    val q = org.json.JSONObject.quote(msg)
    val body = """{"error":"no_celular","detail":$q,"mensagem":$q,"estado":"falha","motivos":[$q],""" +
        """"blockingReasons":[{"nome":"Robô do celular","motivo":$q}]}"""
    return WebResourceResponse(
        "application/json", "utf-8", 409, "Conflict",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
    )
}

private fun aviso(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

private fun abrirFora(ctx: Context, u: Uri) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { aviso(ctx, "Não há app para abrir este link.") }
}

/** Baixa um arquivo do site com o login (Bearer) para a pasta Downloads, com aviso na barra de notificações. */
private fun baixar(ctx: Context, url: String, token: String, cd: String?, mime: String?, ua: String? = null) {
    try {
        val nome = URLUtil.guessFileName(url, cd, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Authorization", "Bearer $token")
            .setTitle(nome)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nome)
        if (ua != null) req.addRequestHeader("User-Agent", ua)
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        aviso(ctx, "Baixando $nome… (pasta Downloads)")
    } catch (e: Exception) {
        aviso(ctx, "Não consegui baixar o arquivo.")
    }
}
