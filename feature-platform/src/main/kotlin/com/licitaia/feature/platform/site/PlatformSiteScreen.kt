package com.licitaia.feature.platform.site

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.io.ByteArrayInputStream

/**
 * O SITE da VPS dentro do app (MODELO B), já logado com a conta da plataforma — o mesmo papel do programa do PC.
 *
 * ETAPA 1: os botões que precisam do certificado (soltar robô de proposta, preparar/participar da disputa, abrir login
 * de portal, certificado, "Ver a tela do robô") são SEGURADOS aqui no aparelho com um aviso — nunca chegam ao robô da
 * VPS. Na ETAPA 2 eles passam a chamar os robôs deste celular.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlatformSiteScreen(viewModel: PlatformSiteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    // Voltar do celular: 1º fecha o menu/aviso aberto no site; 2º volta a página; só então sai do app.
    val activity = LocalContext.current as? android.app.Activity
    BackHandler(enabled = webView != null) {
        val wv = webView ?: return@BackHandler
        wv.evaluateJavascript(JS_FECHAR_ABERTO) { r ->
            if (r == "true") return@evaluateJavascript
            if (wv.canGoBack()) wv.goBack() else activity?.moveTaskToBack(true)
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
        val origin = remember(auth) { Uri.parse(auth.siteUrl).let { it.scheme + "://" + it.host } }
        val script = remember(auth) { PlatformSiteScript.build(auth.token, auth.userJson) }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false) // window.open/target=_blank caem no shouldOverrideUrlLoading
                    CookieManager.getInstance().setAcceptCookie(true)

                    val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    if (docStart) WebViewCompat.addDocumentStartJavaScript(this, script, setOf(origin))

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            val mesmoSite = (u.scheme + "://" + u.host) == origin
                            if (mesmoSite) {
                                val p = u.path.orEmpty()
                                if (p.startsWith("/api/")) { baixar(ctx, u.toString(), auth.token, null, null); return true }
                                if (p.startsWith("/vnc/")) { aviso(ctx, MSG_TELA_ROBO); return true }
                                return false
                            }
                            // links de fora (PNCP, Compras.gov, e-mail...) abrem fora do app
                            abrirFora(ctx, u)
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
                            canGoBack = view.canGoBack()
                            // "Sair" no site (ou sessão vencida) → encerra a conta da plataforma no app também
                            if (url != null && Uri.parse(url).path == "/login") viewModel.onSiteLoggedOut()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            return try { fileLauncher.launch(params.createIntent()); true } catch (e: ActivityNotFoundException) { fileCallback = null; false }
                        }
                    }
                    setDownloadListener { url, ua, cd, mime, _ ->
                        baixar(ctx, url, auth.token, cd, mime, ua)
                    }
                    loadUrl(auth.siteUrl)
                    webView = this
                }
            },
        )
    }

    DisposableEffect(Unit) { onDispose { webView?.destroy(); webView = null } }
}

/** Botões que, no app, não podem acionar o robô/navegador da VPS (ETAPA 1: aviso; ETAPA 2: robô do celular). */
internal object SiteGuard {
    private val REGRAS: List<Pair<String, Regex>> = listOf(
        "POST" to Regex("^/api/executor/registrar-proposta$"),
        "POST" to Regex("^/api/robo-lances/(preparar|participar)(/.*)?$"),
        "POST" to Regex("^/api/vnc/session$"),
        "POST" to Regex("^/api/integracoes/portal/[^/]+/abrir-login$"),
        "POST" to Regex("^/api/empresa/certificado$"),
        "DELETE" to Regex("^/api/empresa/certificado$"),
        "POST" to Regex("^/api/robo-registro/religar-vpn$"),
    )

    fun bloqueio(method: String?, path: String): String? {
        val m = method.orEmpty().uppercase()
        val hit = REGRAS.any { (mm, re) -> mm == m && re.matches(path) } ||
            (m == "POST" && Regex("^/api/licitacoes/[\\w-]{8,64}/registration-eligibility/refresh$").matches(path))
        return if (hit) MSG_ETAPA2 else null
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

private const val MSG_ETAPA2 =
    "No app do celular esta função vai rodar no próprio celular, com o certificado daqui (nunca no robô da VPS). " +
        "Está em construção: por enquanto use o programa do PC para isto."
private const val MSG_TELA_ROBO =
    "No app, a tela do robô é a do próprio celular (em construção). O navegador da VPS não é usado aqui."

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
