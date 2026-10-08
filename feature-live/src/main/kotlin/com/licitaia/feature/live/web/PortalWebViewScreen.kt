package com.licitaia.feature.live.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.app.Activity
import android.content.ContextWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.key
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
    // Aba sem estado: Compras.gov.br SEMPRE pela entrada oficial (nunca intro.htm/última URL); demais portais, com sessão
    // aberta, a última página da área logada; senão, a página de login.
    // "Abrir no portal" com a URL oficial da compra (BLL/Licitanet/PCP): ela é a primeira página.
    val startUrl = remember(portal) { vm.targetUrl ?: PortalWebPolicy.openUrl(portal, state.status, state.lastUrl) }
    var targetPending by remember { mutableStateOf(vm.targetUrl != null) }
    val purchaseSearch by vm.purchaseSearch.collectAsStateWithLifecycle()
    val sessionCheck by vm.sessionCheck.collectAsStateWithLifecycle()
    val loginUrl = remember(portal) { PortalWebPolicy.startUrl(portal) }
    val probeScript = remember(portal) { PortalWebPolicy.contentProbeScript(portal) }
    val checkContent = remember(portal) { PortalWebPolicy.hasContentMarkers(portal) }

    val holder = vm.webViews
    val activity = remember(context) { context.findActivity() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var entry by remember { mutableStateOf<PortalWebViewHolder.Entry?>(null) }
    // Nova geração = novo WebView (após "Sair do portal", que descarta o retido com o sessionStorage da SPA).
    var generation by remember { mutableIntStateOf(0) }
    var forcedUrl by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
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
    val autoBanner by vm.autoLoginBanner.collectAsStateWithLifecycle()
    val unstable by vm.unstable.collectAsStateWithLifecycle()
    // Login automático: no máximo UMA tentativa por abertura da tela (não repete após "Sair do portal").
    var autoArmed by remember { mutableStateOf(false) }

    val autoLoginOnNow by rememberUpdatedState(state.autoLoginOn)

    fun startAutoLogin(target: PortalWebViewHolder.Entry, loadStart: Boolean) {
        val started = holder.startAutoLogin(target, state.companyCnpj, loadStart) { outcome ->
            latestVm.onAutoLoginOutcome(outcome)
            // 503 do portal: não é "sessão expirada" — fica o aviso "Compras.gov.br instável" (e as novas tentativas).
            if (outcome is CertAutoLogin.Outcome.Stopped && outcome.reason != CertAutoLogin.StopReason.PORTAL_UNSTABLE) {
                // Parou durante a entrada/reentrada sem chegar à área logada (ou a área recusou a sessão): agora sim expira.
                val gate = target.entryGate.onAutoLoginStopped(runCatching { target.webView.url }.getOrNull())
                if (gate == PortalWebPolicy.EntryAction.Expire || outcome.reason == CertAutoLogin.StopReason.SESSION_REJECTED) {
                    latestVm.onEntryFailed()
                }
            }
        }
        if (started) latestVm.onAutoLoginStarted()
    }

    /**
     * "Compras eletrônicas": clica no link "Licitação e Dispensa (novo)" do menu do Comprasnet (a SPA só recebe o token
     * por esse link). [auto] = disparado pelo app (após o login / após "Não autorizado"), só se a aba continuar em [expectedUrl].
     */
    fun openElectronicPurchases(target: PortalWebViewHolder.Entry, view: WebView, auto: Boolean, expectedUrl: String? = null) {
        val run = Runnable {
            if (webView !== view || (expectedUrl != null && view.url != expectedUrl)) return@Runnable
            holder.openElectronicPurchases(target) { ok ->
                if (!ok) {
                    latestVm.notify(
                        if (auto) "Abra \"Compras → Licitação e Dispensa (novo)\" no menu do portal."
                        else "Não encontrei \"Licitação e Dispensa (novo)\" nesta página. Use o menu Compras do portal.",
                    )
                }
            }
        }
        if (auto) view.postDelayed(run, ELECTRONIC_CLICK_DELAY_MS) else run.run()
    }

    /**
     * Executa a decisão do [PortalWebPolicy.EntryGate]: navegação (só área de trabalho/entrada — nunca o cnetmobile),
     * clique no link de compras eletrônicas, login automático ou expirar.
     */
    fun applyEntryAction(target: PortalWebViewHolder.Entry, view: WebView, action: PortalWebPolicy.EntryAction, reentry: Boolean) {
        when (action) {
            PortalWebPolicy.EntryAction.None -> Unit
            is PortalWebPolicy.EntryAction.Navigate -> {
                if (reentry) latestVm.onReconnecting()
                // Área de trabalho só se a aba já esteve na área logada (ou está no cnetmobile); senão a entrada oficial.
                val hasState = target.entryGate.reachedLoggedArea || view.url?.let { PortalWebPolicy.isLoggedArea(portal, it) } == true
                view.loadUrl(PortalWebPolicy.safeLoadUrl(portal, action.url, hasState))
            }
            PortalWebPolicy.EntryAction.OpenElectronicPurchases -> openElectronicPurchases(target, view, auto = true, expectedUrl = view.url)
            PortalWebPolicy.EntryAction.StartAutoLogin -> {
                if (reentry) latestVm.onReconnecting()
                val step = view.url?.let { CertAutoLogin.detectStep(portal, it) }
                val onLoginStep = step != null && (step in CertAutoLogin.ACTION_STEPS || step == CertAutoLogin.Step.CERTIFICATE)
                startAutoLogin(target, loadStart = !onLoginStep)
            }
            PortalWebPolicy.EntryAction.Expire -> latestVm.onEntryFailed()
            is PortalWebPolicy.EntryAction.LoginRequired -> {
                // www.gov.br público vindo do portal: não logado → expira (se estava aberta) e UMA ida à entrada oficial.
                latestVm.onLoginRequired()
                action.url?.let { view.loadUrl(it) }
            }
        }
    }

    /** Primeira navegação de uma aba sem estado: SEMPRE a entrada oficial (Compras.gov.br); nunca intro.htm/cnetmobile. */
    fun firstLoad(target: PortalWebViewHolder.Entry?, url: String): String =
        target?.entryGate?.firstLoad(url, verify = latestVm.isVerifyingSession()) ?: PortalWebPolicy.firstNavigationUrl(portal, url)

    // Sessão expirou com a tela aberta e o login automático ligado: uma tentativa por evento de reconexão.
    LaunchedEffect(Unit) {
        vm.autoReloginRequests.collect {
            val target = entry ?: return@collect
            val step = webView?.url?.let { CertAutoLogin.detectStep(portal, it) }
            // Já numa etapa do login (o portal nos mandou para lá): segue dela; senão abre o login oficial.
            val onLoginStep = step != null && (step in CertAutoLogin.ACTION_STEPS || step == CertAutoLogin.Step.CERTIFICATE)
            startAutoLogin(target, loadStart = !onLoginStep)
        }
    }

    // Rede voltou: recarrega sozinho (ou faz a primeira carga adiada). O status da sessão nunca muda por falta de rede.
    LaunchedEffect(state.online) {
        if (!state.online) { wasOffline = true; return@LaunchedEffect }
        if (!wasOffline) return@LaunchedEffect
        wasOffline = false
        val view = webView ?: return@LaunchedEffect
        pageError = null
        if (initialLoadPending || view.url.isNullOrBlank()) {
            initialLoadPending = false
            view.loadUrl(firstLoad(entry, startUrl))
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
                    if (webView !== view || !PortalWebPolicy.parseProbeResult(raw)) return@evaluateJavascript
                    val target = entry
                    if (target != null && PortalWebPolicy.needsEntryFirst(portal, url)) {
                        // cnetmobile "Não autorizado": NÃO expira de imediato — UMA reentrada pela entrada oficial.
                        // Com o login automático em andamento, ele mesmo trata a etapa (navega para a entrada).
                        if (target.autoLoginRunning || pageError != null || !online) return@evaluateJavascript
                        applyEntryAction(target, view, target.entryGate.onUnauthorized(url, autoLoginOnNow), reentry = true)
                    } else {
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

    // Tela em primeiro plano ou não: o holder só pausa o WebView quando o keep-alive deste portal está desligado
    // (nunca pauseTimers, que é global). Grava cookies ao sair de primeiro plano.
    DisposableEffect(lifecycleOwner, entry) {
        val observer = LifecycleEventObserver { _, event ->
            val e = entry
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { if (e != null) holder.setVisible(e, false); latestVm.flush() }
                Lifecycle.Event.ON_RESUME -> if (e != null) holder.setVisible(e, true)
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
                // O ViewModel descarta o WebView retido; a nova geração cria outro e abre o login.
                vm.signOut { forcedUrl = loginUrl; generation++ }
            },
            onDismiss = { confirmSignOut = false },
            confirmLabel = "Sair do portal", tone = Tone.DANGER, icon = Icons.AutoMirrored.Outlined.Logout,
        )
    }

    LicitaScaffold(
        title = portal.displayName,
        showBack = true,
        actions = {
            // "Compras eletrônicas": na área de trabalho logada do Comprasnet, clica no link "Licitação e Dispensa (novo)"
            // do próprio portal (única forma de a SPA receber o token).
            if (PortalWebPolicy.canGoToElectronicPurchases(portal, currentUrl)) {
                IconButton(onClick = {
                    val e = entry
                    val v = webView
                    if (e != null && v != null) openElectronicPurchases(e, v, auto = false)
                }) {
                    Icon(Icons.Outlined.Gavel, contentDescription = "Compras eletrônicas")
                }
            }
            if (state.requiresLogin && state.canSignOut) {
                IconButton(onClick = { confirmSignOut = true }, enabled = !state.busy) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Sair do portal")
                }
            }
            if (state.requiresLogin) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Mais opções") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (portal == Portal.COMPRAS_GOV) {
                            DropdownMenuItem(
                                text = { Text("Mapear esta tela (robô)") },
                                leadingIcon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    vm.mapScreen(companyId) { ok -> navigator.showMessage(if (ok) "Tela mapeada (estrutura salva, sem valores digitados)." else "Não foi possível mapear esta tela.") }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Trocar certificado digital") },
                            leadingIcon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                // Esquece o alias lembrado; o seletor do Android abre na próxima exigência do site.
                                vm.forgetCertificate { webView?.reload() }
                            },
                        )
                        if (state.autoLoginSupported) {
                            DropdownMenuItem(
                                text = { Text("Entrar automaticamente com certificado digital") },
                                leadingIcon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
                                trailingIcon = {
                                    Switch(
                                        checked = state.autoLoginOn,
                                        onCheckedChange = { menuOpen = false; vm.setAutoCertLogin(it) },
                                        modifier = Modifier.semantics { contentDescription = "Entrar automaticamente com certificado digital" },
                                    )
                                },
                                onClick = { menuOpen = false; vm.setAutoCertLogin(!state.autoLoginOn) },
                            )
                        }
                    }
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
                    val isUnstable = unstable != null
                    val (label, tone) = if (isUnstable) PortalInstability.BADGE_LABEL to Tone.WARNING else sessionBadge(state.status, sessionCheck)
                    val verifying = sessionCheck == PortalWebPolicy.SessionCheck.VERIFYING && state.status == PortalConnectionStatus.CONECTADO
                    val loginRequired = sessionCheck == PortalWebPolicy.SessionCheck.LOGIN_REQUIRED
                    StatusBadge(label, tone, pulsing = !isUnstable && state.status == PortalConnectionStatus.CONECTADO && !verifying && !loginRequired)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isUnstable -> "instabilidade do portal (erro 503), não do app"
                            verifying -> "abrindo a entrada oficial do portal"
                            loginRequired -> "o portal pediu login; entre novamente"
                            state.status == PortalConnectionStatus.CONECTADO -> "desde ${Formatters.dateTime(state.lastLoginAt)}"
                            state.status == PortalConnectionStatus.SESSAO_EXPIRADA -> "o portal encerrou a sessão; entre novamente"
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
            when (val b = autoBanner) {
                AutoLoginBanner.Running -> AlertBanner(
                    "Entrando com o certificado digital",
                    "O app está só clicando nas etapas do login (perfil, certificado, empresa). Se aparecer CAPTCHA ou código, ele para e você conclui.",
                    Tone.INFO, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                is AutoLoginBanner.NeedsUser -> AlertBanner(
                    "Conclua o login no portal",
                    "${b.reason} O login automático parou; continue manualmente nesta página.",
                    Tone.WARNING, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    actionLabel = "OK", onAction = { vm.dismissAutoLoginBanner() },
                )
                null -> Unit
            }
            unstable?.let { u ->
                // "Compras.gov.br instável": 503 do portal. "Tentar agora" abre a entrada oficial (e segue o login
                // automático, se ligado); as novas tentativas a cada 5 min ficam com o "Manter sessão ativa".
                AlertBanner(
                    PortalInstability.BADGE_LABEL,
                    PortalInstability.bannerText(u, state.keepAliveOn),
                    Tone.WARNING, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    actionLabel = "Tentar agora",
                    onAction = {
                        val e = entry
                        val v = webView
                        if (e != null && v != null && state.online) {
                            pageError = null
                            if (state.autoLoginOn && !e.autoLoginRunning) startAutoLogin(e, loadStart = true)
                            else if (!e.autoLoginRunning) v.loadUrl(loginUrl)
                        } else if (!state.online) {
                            navigator.showMessage("Sem internet — tente de novo quando a conexão voltar.")
                        }
                    },
                )
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

            purchaseSearch?.let { text ->
                AlertBanner("Abrir no portal", text, Tone.INFO, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), pulsing = true)
            }
            if (portal == Portal.COMPRAS_GOV) RobotAttentionBar(vm, companyId)

            Box(Modifier.fillMaxSize()) {
                key(generation) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // WebView RETIDO da empresa/portal: sessionStorage/memória da SPA sobrevivem ao sair e voltar.
                        val retained = holder.obtain(companyId, portal)
                        holder.attach(retained, activity)
                        holder.setVisible(retained, lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        isolatedProfile = retained.isolatedProfile
                        retained.webView.apply {
                            webViewClient = object : RetainedPortalClient(holder, retained) {
                                override fun onBlocked(url: String) { blockedUrl = url }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (!PortalWebPolicy.isAllowed(portal, url)) return
                                    currentUrl = url; loading = true; pageError = null; blockedUrl = null
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    currentUrl = url
                                    canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                                    latestVm.onNavigated(
                                        url, PortalWebSessions.hasCookies(companyId, url), loadFailed = pageError != null,
                                        entering = retained.entryGate.entering,
                                    )
                                    PortalWebSessions.flush(companyId)
                                    // Troca de rota da SPA (pushState) não dispara onPageFinished: checa o conteúdo depois.
                                    if (checkContent) view.postDelayed({ probeContent(view, url) }, PROBE_DELAY_MS)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    super.onPageFinished(view, url)
                                    loading = false; progress = 100
                                    canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                                    val gate = retained.entryGate
                                    val entering = gate.entering
                                    val signal = latestVm.onNavigated(
                                        url, PortalWebSessions.hasCookies(companyId, url), loadFailed = pageError != null, entering = entering,
                                    )
                                    PortalWebSessions.flush(companyId)
                                    // www.gov.br público vindo do Comprasnet/cnetmobile (ex.: intro.htm sem sessão) ou com a
                                    // sessão marcada como aberta: NÃO logado → expira + UMA ida à entrada oficial.
                                    if (pageError == null && online && signal != PortalWebPolicy.Signal.BLOCKED) {
                                        val landing = gate.onPublicLanding(url, sessionOpen = latestVm.sessionOpen())
                                        if (landing is PortalWebPolicy.EntryAction.LoginRequired) {
                                            applyEntryAction(retained, view, landing, reentry = false)
                                            return
                                        }
                                    }
                                    // Área de trabalho logada (intro.htm): clique automático no link "Licitação e Dispensa
                                    // (novo)" (sempre 1x por abertura; após "Não autorizado": 1 repetição) — nunca
                                    // loadUrl do cnetmobile. Página de login durante a ida à área de trabalho: só conclui
                                    // depois de a aba assentar nela.
                                    val entryAction = if (pageError == null && signal != PortalWebPolicy.Signal.BLOCKED) {
                                        gate.onPage(
                                            url, settled = false, autoLoginOn = autoLoginOnNow, autoLoginRunning = retained.autoLoginRunning,
                                            sessionOpen = signal == PortalWebPolicy.Signal.CONNECTED || latestVm.sessionOpen(),
                                        )
                                    } else {
                                        PortalWebPolicy.EntryAction.None
                                    }
                                    if (entering && gate.entering && pageError == null && PortalWebPolicy.isLoginPage(portal, url)) {
                                        view.postDelayed({
                                            if (webView !== view || view.url != url || pageError != null || !online) return@postDelayed
                                            applyEntryAction(
                                                retained, view,
                                                gate.onPage(url, settled = true, autoLoginOn = autoLoginOnNow, autoLoginRunning = retained.autoLoginRunning),
                                                reentry = false,
                                            )
                                        }, ENTRY_SETTLE_MS)
                                    }
                                    // Nada de redirecionar para o cnetmobile após o login: só o link do portal o abre.
                                    if (entryAction != PortalWebPolicy.EntryAction.None) applyEntryAction(retained, view, entryAction, reentry = false)
                                    if (entryAction !is PortalWebPolicy.EntryAction.Navigate && signal != PortalWebPolicy.Signal.BLOCKED) {
                                        // Aviso de sessão encerrada exibido pela própria página (SPA na área logada).
                                        probeContent(view, url)
                                    }
                                }

                                // Falha de carga NUNCA é sessão encerrada: cancela qualquer EXPIRED pendente desta navegação.
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    super.onReceivedError(view, request, error)
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
                                    super.onReceivedHttpError(view, request, errorResponse)
                                    if (request.isForMainFrame && PortalWebPolicy.isServerFailure(errorResponse.statusCode)) {
                                        latestVm.onLoadFailed()
                                        pageError = "O portal respondeu com erro (HTTP ${errorResponse.statusCode}). Sua sessão continua salva; tente recarregar em instantes."
                                    }
                                }

                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    super.onReceivedSslError(view, handler, error) // cancela
                                    loading = false
                                    pageError = "Certificado HTTPS inválido em ${PortalWebPolicy.host(error.url) ?: "um domínio"}. A navegação foi interrompida por segurança."
                                }
                            }
                            // onCreateWindow (link do portal com target=_blank/window.open) → carrega nesta mesma aba.
                            webChromeClient = object : RetainedChromeClient(holder, retained) {
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
                            val existing = url?.takeIf { it.isNotBlank() && it != "about:blank" }
                            // Nova abertura: até UMA reentrada automática nesta abertura (contador por WebView).
                            retained.entryGate.onScreenOpened()
                            val first = forcedUrl ?: startUrl
                            // Abertura da tela sem sessão + login automático ligado: arma ANTES da primeira carga
                            // (ou avalia a página atual, se a aba já existia). Nunca depois de "Sair do portal".
                            if (!autoArmed && forcedUrl == null && state.autoLoginOn && state.status != PortalConnectionStatus.CONECTADO) {
                                autoArmed = true
                                startAutoLogin(retained, loadStart = false)
                            }
                            autoArmed = true
                            val forced = forcedUrl != null
                            forcedUrl = null
                            if (existing == null) {
                                // WebView novo (sem estado): SEMPRE a entrada oficial do Comprasnet (adiada se estiver sem
                                // internet) — nunca intro.htm, a última URL ou o cnetmobile. Status persistido CONECTADO:
                                // selo "Verificando sessão…" até a primeira página conclusiva.
                                val verify = !forced && latestVm.beginSessionCheck(freshTab = true)
                                if (online) loadUrl(retained.entryGate.firstLoad(first, verify = verify)) else initialLoadPending = true
                                targetPending = false
                            } else if (targetPending && online) {
                                // Aba já existia e o usuário pediu a página de uma compra: vai direto para ela (uma vez).
                                targetPending = false
                                vm.targetUrl?.let { loadUrl(it) }
                            } else {
                                // Voltou para a tela: mantém a página (e o estado da SPA) como estava, sem recarregar.
                                currentUrl = existing; loading = false; progress = 100
                                canGoBack = canGoBack(); canGoForward = canGoForward()
                            }
                            webView = this
                            entry = retained
                            // Reabertura com a aba na área de trabalho logada (intro.htm): vai para Compras eletrônicas
                            // pelo link do portal (1x). Já no cnetmobile: não mexe. Nunca loadUrl do cnetmobile.
                            if (existing != null && online && !retained.autoLoginRunning) {
                                val reattach = retained.entryGate.onScreenReattached(
                                    existing, loading = this.progress < 100, sessionOpen = latestVm.sessionOpen(),
                                )
                                if (reattach == PortalWebPolicy.EntryAction.OpenElectronicPurchases) {
                                    openElectronicPurchases(retained, this, auto = true, expectedUrl = existing)
                                } else if (reattach is PortalWebPolicy.EntryAction.LoginRequired) {
                                    // Aba retida parada no www.gov.br público com a sessão marcada como aberta.
                                    applyEntryAction(retained, this, reattach, reentry = false)
                                }
                            }
                        }
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            addView(retained.webView)
                            tag = retained
                        }
                    },
                    onRelease = { frame ->
                        val retained = frame.tag as? PortalWebViewHolder.Entry
                        PortalWebSessions.flush(companyId)
                        fileCallback?.onReceiveValue(null); fileCallback = null
                        // Desanexa SEM destruir: a sessão da SPA continua viva para a volta e para o keep-alive.
                        if (retained != null) holder.detach(retained)
                        if (webView === retained?.webView) { webView = null; entry = null }
                    },
                )
                }
                if (pageError != null || initialLoadPending) {
                    Box(Modifier.fillMaxSize().background(LicitaColors.Background)) {
                        ErrorState(
                            if (!state.online) "Sem internet — sua sessão continua salva; reconecte para continuar." else pageError ?: "",
                            Modifier.align(Alignment.Center),
                            title = if (!state.online) "Sem internet" else "Página indisponível",
                            onRetry = {
                                pageError = null
                                val view = webView
                                if (initialLoadPending || view?.url.isNullOrBlank()) { initialLoadPending = false; view?.loadUrl(firstLoad(entry, startUrl)) } else view?.reload()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Activity que hospeda a tela (o WebView retido usa-a como contexto só enquanto exibido). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Espera para a 2ª checagem de conteúdo (SPA termina de renderizar depois do onPageFinished). */
private const val PROBE_DELAY_MS = 2_000L

/** Espera após o onPageFinished da área de trabalho antes do clique automático no link (menu termina de montar). */
private const val ELECTRONIC_CLICK_DELAY_MS = 1_200L

/** A entrada oficial só conclui "caiu no login" se a aba ficar na página de login por este tempo (redirecionamentos). */
private const val ENTRY_SETTLE_MS = 2_500L

/** Texto honesto do "Manter sessão ativa" (tela do portal e card em Portais). */
fun keepAliveHonestText(minutes: Int): String =
    "Mantém a sessão ativa recarregando sua página a cada $minutes min enquanto ligado. " +
        "O portal ainda pode encerrar a sessão pelo tempo máximo dele; nesse caso você recebe um alerta."

private fun sessionBadge(status: PortalConnectionStatus, check: PortalWebPolicy.SessionCheck): Pair<String, Tone> {
    val label = PortalWebPolicy.sessionBadgeLabel(status, check)
    val tone = when {
        check == PortalWebPolicy.SessionCheck.VERIFYING && status == PortalConnectionStatus.CONECTADO -> Tone.INFO
        check == PortalWebPolicy.SessionCheck.LOGIN_REQUIRED -> Tone.WARNING
        else -> when (status) {
            PortalConnectionStatus.CONECTADO -> Tone.SUCCESS
            PortalConnectionStatus.SESSAO_EXPIRADA, PortalConnectionStatus.MFA_PENDENTE -> Tone.WARNING
            PortalConnectionStatus.DESCONECTADO -> Tone.NEUTRAL
        }
    }
    return label to tone
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
            setDescription("LicitaPRO · download do portal")
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
