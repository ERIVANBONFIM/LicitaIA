package com.licitaia.feature.live.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.util.Formatters

/**
 * Navegador interno do portal oficial, com login MANUAL do usuário e sessão persistida em cookies.
 *
 * O app nunca lê, guarda ou preenche usuário/senha (o preenchimento, se houver, é do Autofill do
 * próprio Android); não contorna CAPTCHA/MFA; não executa ações no portal. Reconhece a sessão
 * por URL + existência de cookies + um booleano de "aviso de sessão encerrada" calculado na página
 * ([PortalWebPolicy.contentProbeScript]; nenhum texto, input ou cookie é lido pelo app).
 */
@Composable
fun PortalWebViewScreen(vm: PortalWebViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val portal = state.portal

    LaunchedEffect(Unit) { vm.events.collect { navigator.showMessage(it) } }

    when {
        portal == null -> LicitaScaffold(title = "Portal oficial", showBack = true) { padding ->
            ErrorState("Portal inválido.", Modifier.padding(padding), title = "Não foi possível abrir")
        }
        !state.ready -> LicitaScaffold(title = portal.displayName, showBack = true) { padding -> SkeletonList(Modifier.padding(padding), items = 2) }
        state.companyId == null -> LicitaScaffold(title = portal.displayName, showBack = true) { padding ->
            ErrorState("Entre no LicitaIA e selecione a empresa antes de abrir o portal.", Modifier.padding(padding), title = "Sem empresa ativa")
        }
        else -> PortalWebContent(portal = portal, companyId = state.companyId!!, state = state, vm = vm)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PortalWebContent(portal: Portal, companyId: Long, state: PortalWebUiState, vm: PortalWebViewModel) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Com sessão já aberta, volta para a última página da área logada (ou à área de trabalho); senão, à página de login.
    val startUrl = remember(portal) { PortalWebPolicy.openUrl(portal, state.status, state.lastUrl) }
    val loginUrl = remember(portal) { PortalWebPolicy.startUrl(portal) }
    val probeScript = remember(portal) { PortalWebPolicy.contentProbeScript(portal) }
    val checkContent = remember(portal) { PortalWebPolicy.hasContentMarkers(portal) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var blockedUrl by remember { mutableStateOf<String?>(null) }
    var isolatedProfile by remember { mutableStateOf(true) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    // Aberta sem internet: não carrega (evita página de erro/redirect ao login) e carrega ao reconectar.
    var initialLoadPending by remember { mutableStateOf(false) }
    var wasOffline by remember { mutableStateOf(!state.online) }
    val latestVm by rememberUpdatedState(vm)
    val online by rememberUpdatedState(state.online)

    // Rede voltou: recarrega sozinho (ou faz a primeira carga adiada). O status da sessão nunca muda por falta de rede.
    LaunchedEffect(state.online) {
        if (!state.online) { wasOffline = true; return@LaunchedEffect }
        if (!wasOffline) return@LaunchedEffect
        wasOffline = false
        val view = webView ?: return@LaunchedEffect
        pageError = null
        if (initialLoadPending || view.url.isNullOrBlank()) {
            initialLoadPending = false
            view.loadUrl(startUrl)
        } else {
            view.reload()
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    /**
     * Checagem de conteúdo (SPA): o script devolve só true/false calculado na página. Roda agora e de novo
     * [PROBE_DELAY_MS] depois, desde que a aba continue na mesma URL.
     */
    fun probeContent(view: WebView, url: String) {
        if (!checkContent) return
        val run = Runnable {
            if (webView !== view || view.url != url) return@Runnable
            runCatching {
                view.evaluateJavascript(probeScript) { raw ->
                    if (webView === view && PortalWebPolicy.parseProbeResult(raw)) {
                        latestVm.onNavigated(url, PortalWebSessions.hasCookies(companyId, url), contentExpired = true, loadFailed = pageError != null)
                    }
                }
            }
        }
        run.run()
        view.postDelayed(run, PROBE_DELAY_MS)
    }

    fun openExternally(url: String) {
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
            .onFailure { navigator.showMessage("Não foi possível abrir o navegador externo.") }
    }

    // Pausa/retoma o WebView com a tela e grava cookies ao sair de primeiro plano.
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { webView?.onPause(); latestVm.flush() }
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); latestVm.flush() }
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sair de ${portal.displayName}?",
            message = "Os cookies deste portal serão removidos do navegador interno deste aparelho e o status voltará a \"Sem sessão\". Você precisará fazer login novamente no portal.",
            onConfirm = {
                confirmSignOut = false
                vm.signOut { webView?.loadUrl(loginUrl) }
            },
            onDismiss = { confirmSignOut = false },
            confirmLabel = "Sair do portal", tone = Tone.DANGER, icon = Icons.AutoMirrored.Outlined.Logout,
        )
    }

    LicitaScaffold(
        title = portal.displayName,
        showBack = true,
        actions = {
            if (state.requiresLogin && state.canSignOut) {
                IconButton(onClick = { confirmSignOut = true }, enabled = !state.busy) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Sair do portal")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Barra de endereço: navegação, domínio + cadeado, recarregar.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar página") }
                IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Avançar página") }
                val https = PortalWebPolicy.isHttps(currentUrl)
                Row(
                    Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(LicitaColors.Surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (https) Icons.Outlined.Lock else Icons.Outlined.LockOpen, contentDescription = if (https) "Conexão segura" else "Conexão não segura",
                        tint = if (https) LicitaColors.GreenBright else LicitaColors.Red, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        PortalWebPolicy.host(currentUrl) ?: currentUrl, style = MaterialTheme.typography.labelMedium,
                        color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { pageError = null; webView?.reload() }) { Icon(Icons.Outlined.Refresh, contentDescription = "Recarregar") }
            }
            if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
            else Spacer(Modifier.height(3.dp))

            // Status da sessão (heurística) + avisos.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.requiresLogin) {
                    val (label, tone) = sessionBadge(state.status)
                    StatusBadge(label, tone, pulsing = state.status == PortalConnectionStatus.CONECTADO)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state.status) {
                            PortalConnectionStatus.CONECTADO -> "desde ${Formatters.dateTime(state.lastLoginAt)}"
                            PortalConnectionStatus.SESSAO_EXPIRADA -> "o portal encerrou a sessão; entre novamente"
                            else -> "digite usuário e senha somente na página oficial"
                        },
                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    StatusBadge("Consulta pública", Tone.INFO)
                    Spacer(Modifier.width(8.dp))
                    Text("sem login", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
            }
            if (state.requiresLogin) {
                // "Manter sessão ativa" (opt-in): recarga periódica da página do usuário em segundo plano.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Manter sessão ativa", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextPrimary)
                        Text(
                            if (state.keepAliveOn) keepAliveHonestText(state.keepAliveMinutes)
                            else "Recarrega sua página a cada ${state.keepAliveMinutes} min enquanto ligado.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = state.keepAliveOn,
                        onCheckedChange = { vm.setKeepAlive(it) },
                        modifier = Modifier.semantics { contentDescription = "Manter sessão ativa" },
                    )
                }
            }
            AnimatedVisibility(!state.online) {
                AlertBanner(
                    "Sem internet",
                    "Sem internet — sua sessão continua salva; reconecte para continuar. A página recarrega sozinha quando a conexão voltar.",
                    Tone.WARNING, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (!isolatedProfile && state.requiresLogin) {
                Text(
                    "Este aparelho não separa cookies por empresa no navegador interno: ao trocar de empresa, saia do portal antes.",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
                )
            }
            AnimatedVisibility(blockedUrl != null) {
                val url = blockedUrl ?: ""
                AlertBanner(
                    "Link fora do portal bloqueado",
                    (PortalWebPolicy.host(url) ?: url) + " não faz parte de ${portal.displayName}.",
                    Tone.WARNING, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    actionLabel = "Abrir fora", onAction = { openExternally(url); blockedUrl = null },
                )
            }

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            // Perfil por empresa (antes de qualquer carregamento). Fallback: perfil compartilhado.
                            isolatedProfile = PortalWebSessions.attachProfile(this, companyId)
                            PortalWebSessions.configure(this, companyId)
                            // Autofill do Android/Google preenche o login; o app não armazena senha.
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                            settings.apply {
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
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (PortalWebPolicy.isAllowed(portal, url)) return false
                                    if (request.isForMainFrame) blockedUrl = url
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (!PortalWebPolicy.isAllowed(portal, url)) {
                                        view.stopLoading(); blockedUrl = url; return
                                    }
                                    currentUrl = url; loading = true; pageError = null; blockedUrl = null
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    currentUrl = url
                                    canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                                    latestVm.onNavigated(url, PortalWebSessions.hasCookies(companyId, url), loadFailed = pageError != null)
                                    PortalWebSessions.flush(companyId)
                                    // Troca de rota da SPA (pushState) não dispara onPageFinished: checa o conteúdo depois.
                                    if (checkContent) view.postDelayed({ probeContent(view, url) }, PROBE_DELAY_MS)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false; progress = 100
                                    canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                                    val signal = latestVm.onNavigated(url, PortalWebSessions.hasCookies(companyId, url), loadFailed = pageError != null)
                                    PortalWebSessions.flush(companyId)
                                    // Login recém-detectado: leva uma única vez à área de trabalho do fornecedor.
                                    if (signal == PortalWebPolicy.Signal.CONNECTED) {
                                        PortalWebPolicy.postLoginRedirect(portal, url)?.let { view.loadUrl(it) }
                                    } else if (signal != PortalWebPolicy.Signal.BLOCKED) {
                                        // Aviso de sessão encerrada exibido pela própria página (SPA na área logada).
                                        probeContent(view, url)
                                    }
                                }

                                // Falha de carga NUNCA é sessão encerrada: cancela qualquer EXPIRED pendente desta navegação.
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) {
                                        loading = false
                                        latestVm.onLoadFailed()
                                        pageError = if (!online) {
                                            "Sem internet — sua sessão continua salva; reconecte para continuar."
                                        } else {
                                            "Não foi possível carregar ${PortalWebPolicy.host(request.url.toString()) ?: "a página"} (${error.description}). Verifique a conexão."
                                        }
                                    }
                                }

                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                    if (request.isForMainFrame && PortalWebPolicy.isServerFailure(errorResponse.statusCode)) {
                                        latestVm.onLoadFailed()
                                        pageError = "O portal respondeu com erro (HTTP ${errorResponse.statusCode}). Sua sessão continua salva; tente recarregar em instantes."
                                    }
                                }

                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    handler.cancel()
                                    loading = false
                                    pageError = "Certificado HTTPS inválido em ${PortalWebPolicy.host(error.url) ?: "um domínio"}. A navegação foi interrompida por segurança."
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = callback
                                    return runCatching { fileLauncher.launch(params.createIntent()); true }
                                        .getOrElse { fileCallback = null; callback.onReceiveValue(null); false }
                                }
                            }
                            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                                startDownload(ctx, companyId, url, userAgent, contentDisposition, mimeType, ::openExternally)
                            }
                            if (online) loadUrl(startUrl) else initialLoadPending = true
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        PortalWebSessions.flush(companyId)
                        view.stopLoading()
                        view.webChromeClient = null
                        view.destroy()
                        webView = null
                    },
                )
                if (pageError != null || initialLoadPending) {
                    Box(Modifier.fillMaxSize().background(LicitaColors.Background)) {
                        ErrorState(
                            if (!state.online) "Sem internet — sua sessão continua salva; reconecte para continuar." else pageError ?: "",
                            Modifier.align(Alignment.Center),
                            title = if (!state.online) "Sem internet" else "Página indisponível",
                            onRetry = {
                                pageError = null
                                val view = webView
                                if (initialLoadPending || view?.url.isNullOrBlank()) { initialLoadPending = false; view?.loadUrl(startUrl) } else view?.reload()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Espera para a 2ª checagem de conteúdo (SPA termina de renderizar depois do onPageFinished). */
private const val PROBE_DELAY_MS = 2_000L

/** Texto honesto do "Manter sessão ativa" (tela do portal e card em Portais). */
fun keepAliveHonestText(minutes: Int): String =
    "Mantém a sessão ativa recarregando sua página a cada $minutes min enquanto ligado. " +
        "O portal ainda pode encerrar a sessão pelo tempo máximo dele; nesse caso você recebe um alerta."

private fun sessionBadge(status: PortalConnectionStatus): Pair<String, Tone> = when (status) {
    PortalConnectionStatus.CONECTADO -> "Sessão aberta" to Tone.SUCCESS
    PortalConnectionStatus.SESSAO_EXPIRADA -> "Sessão expirada" to Tone.WARNING
    PortalConnectionStatus.MFA_PENDENTE -> "MFA pendente" to Tone.WARNING
    PortalConnectionStatus.DESCONECTADO -> "Faça login no portal" to Tone.NEUTRAL
}

/**
 * Download via gerenciador do Android com os cookies do perfil (editais/anexos atrás de login).
 * Se falhar, abre a URL no navegador externo.
 */
private fun startDownload(
    context: Context, companyId: Long, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?,
    fallback: (String) -> Unit,
) {
    if (!PortalWebPolicy.isHttps(url)) { fallback(url); return }
    val ok = runCatching {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            setTitle(fileName)
            setDescription("LicitaIA · download do portal")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            userAgent?.let { addRequestHeader("User-Agent", it) }
            // Cookies de sessão do perfil (nunca credenciais) para que o portal autorize o arquivo.
            val cookies: String? = PortalWebSessions.cookieManager(companyId).getCookie(url) ?: CookieManager.getInstance().getCookie(url)
            cookies?.let { addRequestHeader("Cookie", it) }
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }.isSuccess
    if (!ok) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { fallback(url) }
    }
}
