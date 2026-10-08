package com.licitaia.app.shell

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.licitaia.app.update.UpdateDialog
import com.licitaia.app.update.UpdateViewModel
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.PulsingDot
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.icon
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.AppNavigator
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.LocalShellState
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AppNotification
import com.licitaia.feature.audit.auditGraph
import com.licitaia.feature.auth.authGraph
import com.licitaia.feature.bidding.biddingGraph
import com.licitaia.feature.competition.competitionGraph
import com.licitaia.feature.dashboard.dashboardGraph
import com.licitaia.feature.documents.documentsGraph
import com.licitaia.feature.live.liveGraph
import com.licitaia.feature.messages.messagesGraph
import com.licitaia.feature.platform.platformGraph
import com.licitaia.feature.radar.radarGraph
import com.licitaia.feature.settings.settingsGraph
import com.licitaia.feature.tender.tenderGraph
import com.licitaia.feature.warroom.warRoomGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Raiz do app: restaura a sessão, monta menu lateral, barra inferior, NavHost com os grafos
 * das features, toasts in-app de alertas (CAPTCHA etc.) e a sobreposição de bloqueio.
 *
 * @param pendingRoute rota recebida por toque em notificação do sistema.
 */
@Composable
fun AppRoot(
    pendingRoute: StateFlow<String?>,
    onRouteConsumed: () -> Unit,
    viewModel: ShellViewModel = hiltViewModel(),
) {
    val startRoute by viewModel.startRoute.collectAsStateWithLifecycle()
    when (val start = startRoute) {
        null -> SplashScreen()
        else -> MainShell(viewModel, start, pendingRoute, onRouteConsumed)
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(LicitaColors.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(LicitaColors.BrandGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Radar, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("LicitaPRO", style = MaterialTheme.typography.headlineLarge, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp, color = LicitaColors.Blue)
        }
    }
}

private fun NavHostController.navigateClearingAll(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * No MODO PLATAFORMA, os itens do menu/barra apontam para as telas alimentadas pela VPS; os que ainda não têm
 * endpoint caem no estado gracioso ([Routes.PLATFORM_SOON]); os "locais por natureza" (Configurações, Segurança,
 * IA, Portais) seguem para as telas locais, que operam no aparelho. No modo local, nada é redirecionado.
 */
private fun resolvePlatformRoute(route: String, platformMode: Boolean): String {
    if (!platformMode) return route
    return when (route) {
        // Licitações e seus recortes → lista da plataforma (chips favorita/status/minhas lá dentro).
        Routes.SEARCH, Routes.INTERESTS, Routes.PARTICIPATIONS, Routes.ARCHIVED -> Routes.PLATFORM_TENDERS
        Routes.RADAR -> Routes.PLATFORM_RADAR
        Routes.DOCUMENTS -> Routes.PLATFORM_DOCS
        Routes.COMPETITION -> Routes.PLATFORM_COMPETITORS
        Routes.MESSAGES -> Routes.PLATFORM_MESSAGES
        Routes.COMPANIES -> Routes.PLATFORM_DIRECTORY
        Routes.AUDIT -> Routes.PLATFORM_AUDIT
        Routes.ROBOT -> Routes.PLATFORM_ROBOT
        Routes.LIVE -> Routes.PLATFORM_LIVE
        // Analisar Edital → lista de licitações (cada detalhe tem "Analisar com IA" on-device).
        Routes.ANALYZE -> Routes.PLATFORM_TENDERS
        // Sala de Guerra → acompanhamento ao vivo das disputas da plataforma.
        Routes.WARROOM -> Routes.PLATFORM_LIVE
        // Estratégias do robô → edita as configs ativas na nuvem (dry_run).
        Routes.STRATEGY -> Routes.PLATFORM_STRATEGY
        // Locais por natureza (device) e o próprio dashboard/notifs: telas locais, sem redirecionar.
        else -> route
    }
}

@Composable
private fun MainShell(
    viewModel: ShellViewModel,
    startRoute: String,
    pendingRoute: StateFlow<String?>,
    onRouteConsumed: () -> Unit,
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val session by viewModel.session.collectAsStateWithLifecycle()
    val shellSession by viewModel.shellSession.collectAsStateWithLifecycle()
    val platformMode by viewModel.platformMode.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val postponedUpdate by updateViewModel.postponed.collectAsStateWithLifecycle()
    val shell by viewModel.shellState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val companies by viewModel.companies.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
    val route by pendingRoute.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()

    val backEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route
    // O shell abre com sessão local OU com a sessão sintética da plataforma (mesmo shell, fonte diferente).
    val loggedIn = shellSession != null
    val onTopLevel = currentRoute in Routes.topLevel
    val platformModeState = rememberUpdatedState(platformMode)
    val showBottomBar = loggedIn && onTopLevel && settings.showBottomBar

    var confirmLogout by remember { mutableStateOf(false) }
    var confirmExitDemo by remember { mutableStateOf(false) }
    var confirmResetDemo by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<AppNotification?>(null) }

    val navigator = remember(nav) {
        object : AppNavigator {
            override fun navigate(route: String) {
                val wasTopLevel = route in Routes.topLevel
                val target = resolvePlatformRoute(route, platformModeState.value)
                if (wasTopLevel) navigateTop(target)
                else nav.navigate(target) { launchSingleTop = true }
            }

            override fun navigateTop(route: String) {
                nav.navigate(resolvePlatformRoute(route, platformModeState.value)) {
                    popUpTo(Routes.DASHBOARD) { inclusive = false }
                    launchSingleTop = true
                }
            }

            override fun back() {
                if (!nav.popBackStack()) nav.navigateClearingAll(Routes.DASHBOARD)
            }

            override fun openDrawer() {
                scope.launch { drawerState.open() }
            }

            override fun onLoggedIn() = nav.navigateClearingAll(Routes.DASHBOARD)

            override fun showMessage(message: String) {
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(message)
                }
            }
        }
    }

    // Logout (ou sessão perdida) do modo LOCAL → volta ao login local limpando a pilha.
    // As rotas da plataforma (platform/...) não dependem da sessão local: não são redirecionadas aqui.
    LaunchedEffect(loggedIn, currentRoute) {
        if (!loggedIn && currentRoute != null && currentRoute != Routes.LOGIN && !currentRoute.startsWith("platform")) {
            drawerState.close()
            nav.navigateClearingAll(Routes.LOGIN)
        }
    }

    // Toque em notificação do sistema → navega quando logado e desbloqueado.
    LaunchedEffect(route, loggedIn, locked, currentRoute) {
        val target = route
        if (target != null && loggedIn && !locked && currentRoute != null && currentRoute != Routes.LOGIN) {
            onRouteConsumed()
            runCatching { navigator.navigate(target) }
        }
    }

    // Alertas in-app (toast no topo): CAPTCHA, autorização de lance, mensagens...
    val hapticsEnabled by rememberUpdatedState(settings.hapticFeedback)
    LaunchedEffect(Unit) {
        viewModel.alerts.collect { notification ->
            alert = notification
            if (notification.critical && hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(alert) {
        if (alert != null) {
            delay(if (alert?.critical == true) 8_000 else 5_000)
            alert = null
        }
    }

    // Conectividade: avisa quando a conexão volta (a faixa "Sem internet" some sozinha).
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) wasOffline = true
        else if (wasOffline) { wasOffline = false; navigator.showMessage("Conexão restabelecida") }
    }

    // Permissão de notificações (Android 13+), pedida uma vez após o login.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(loggedIn) {
        if (loggedIn && Build.VERSION.SDK_INT >= 33) {
            runCatching { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    // Volta das Configurações do sistema ("instalar apps desconhecidos") → retoma a atualização.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updateViewModel.onResume(context) }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    CompositionLocalProvider(LocalAppNavigator provides navigator, LocalShellState provides shell) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = loggedIn && !locked && (onTopLevel || drawerState.isOpen),
            drawerContent = {
                DrawerContent(
                    session = shellSession,
                    companies = companies,
                    currentRoute = currentRoute,
                    criticalPending = shell.criticalPending,
                    onNavigate = { target ->
                        scope.launch { drawerState.close() }
                        navigator.navigateTop(target)
                    },
                    onSwitchCompany = { id ->
                        viewModel.switchCompany(id) { ok ->
                            scope.launch { drawerState.close() }
                            if (ok) {
                                navigator.navigateTop(Routes.DASHBOARD)
                                navigator.showMessage("Empresa ativa alterada.")
                            } else {
                                navigator.showMessage("Não foi possível trocar de empresa.")
                            }
                        }
                    },
                    onLogout = { confirmLogout = true },
                    onExitDemo = { confirmExitDemo = true },
                    onResetDemo = { confirmResetDemo = true },
                )
            },
        ) {
            Box(Modifier.fillMaxSize().background(LicitaColors.Background)) {
                Column(Modifier.fillMaxSize()) {
                    // Faixa fixa "Sem internet" (global): ocupa a área da barra de status; as telas abaixo não repetem o recuo.
                    AnimatedVisibility(visible = !online) {
                        Text(
                            "Sem internet — busca, portais e IA ficam indisponíveis até reconectar",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF1A1A1A),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFACC15))
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .then(if (!online) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
                            .then(if (showBottomBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                    ) {
                        NavHost(
                            navController = nav,
                            startDestination = startRoute,
                            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 14 } },
                            exitTransition = { fadeOut(tween(160)) },
                            popEnterTransition = { fadeIn(tween(220)) },
                            popExitTransition = { fadeOut(tween(160)) },
                        ) {
                            authGraph()
                            dashboardGraph()
                            radarGraph()
                            tenderGraph()
                            documentsGraph()
                            liveGraph()
                            biddingGraph()
                            warRoomGraph()
                            messagesGraph()
                            competitionGraph()
                            auditGraph()
                            settingsGraph()
                            platformGraph()
                        }
                    }
                    AnimatedVisibility(visible = showBottomBar) {
                        BottomBar(
                            currentRoute = currentRoute,
                            platformMode = platformMode,
                            critical = shell.criticalPending,
                            onNavigate = navigator::navigateTop,
                            onMore = navigator::openDrawer,
                        )
                    }
                }

                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = if (showBottomBar) 84.dp else 8.dp),
                ) { data ->
                    Snackbar(data, containerColor = LicitaColors.SurfaceHigh, contentColor = LicitaColors.TextPrimary, shape = MaterialTheme.shapes.medium)
                }

                // Atualização adiada em "Depois": faixa fixa acima da barra inferior até instalar.
                val postponedUpdate = postponedUpdate
                if (postponedUpdate != null && loggedIn && !locked && !updateState.visible) {
                    androidx.compose.material3.Surface(
                        onClick = updateViewModel::resumePostponed,
                        color = LicitaColors.Blue,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                        shape = MaterialTheme.shapes.extraLarge,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = if (showBottomBar) 84.dp else 12.dp, start = 16.dp, end = 16.dp),
                    ) {
                        androidx.compose.material3.Text(
                            "Nova versão ${postponedUpdate.versionName} disponível · Atualizar",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }

                InAppAlertHost(
                    alert = alert.takeIf { loggedIn && !locked },
                    onClick = { clicked ->
                        alert = null
                        clicked.route?.let { target -> runCatching { navigator.navigate(target) } }
                    },
                    onDismiss = { alert = null },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (locked && loggedIn && currentRoute?.startsWith("platform") != true) {
                    LockScreen(
                        userName = session?.user?.name.orEmpty(),
                        biometricEnabled = settings.biometricLock,
                        hasPin = hasPin,
                        onPin = viewModel::unlockWithPin,
                        onBiometricSuccess = viewModel::unlock,
                        onLogout = viewModel::logout,
                    )
                }
            }
        }
    }

    // Atualização via GitHub Releases: só com sessão ativa e app desbloqueado.
    if (updateState.visible && loggedIn && !locked) {
        UpdateDialog(state = updateState, viewModel = updateViewModel)
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "Sair do LicitaPRO?",
            message = "As sessões de pregão continuam registradas e serão restauradas no próximo login.",
            confirmLabel = "Sair",
            tone = Tone.DANGER,
            onConfirm = {
                confirmLogout = false
                scope.launch { drawerState.close() }
                viewModel.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
    if (confirmExitDemo) {
        ConfirmDialog(
            title = "Sair da demonstração?",
            message = "A empresa de demonstração e todos os seus dados fictícios (licitações, documentos, propostas, mensagens e sessões) serão apagados deste aparelho. Suas empresas reais não são afetadas.",
            confirmLabel = "Apagar e sair",
            tone = Tone.DANGER,
            onConfirm = {
                confirmExitDemo = false
                scope.launch { drawerState.close() }
                viewModel.exitDemo { result ->
                    result.onFailure { navigator.showMessage(it.message ?: "Não foi possível encerrar a demonstração.") }
                }
            },
            onDismiss = { confirmExitDemo = false },
        )
    }
    if (confirmResetDemo) {
        ConfirmDialog(
            title = "Reiniciar demonstração?",
            message = "Os dados de exemplo voltam ao estado original. Tudo o que foi alterado na demonstração será descartado.",
            confirmLabel = "Reiniciar",
            tone = Tone.WARNING,
            onConfirm = {
                confirmResetDemo = false
                scope.launch { drawerState.close() }
                viewModel.resetDemo { result ->
                    result.onSuccess {
                        navigator.navigateTop(Routes.DASHBOARD)
                        navigator.showMessage("Demonstração reiniciada.")
                    }.onFailure { navigator.showMessage(it.message ?: "Não foi possível reiniciar a demonstração.") }
                }
            },
            onDismiss = { confirmResetDemo = false },
        )
    }
}

@Composable
private fun BottomBar(currentRoute: String?, platformMode: Boolean, critical: Boolean, onNavigate: (String) -> Unit, onMore: () -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = LicitaColors.BlueBright,
        selectedTextColor = LicitaColors.BlueBright,
        indicatorColor = LicitaColors.Blue.copy(alpha = 0.16f),
        unselectedIconColor = LicitaColors.TextSecondary,
        unselectedTextColor = LicitaColors.TextSecondary,
    )
    // No modo plataforma os itens redirecionam (ex.: Radar→platform/radar): destacar comparando com a rota resolvida.
    fun sel(route: String) = currentRoute == resolvePlatformRoute(route, platformMode)
    NavigationBar(containerColor = LicitaColors.Surface, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = sel(Routes.DASHBOARD), onClick = { onNavigate(Routes.DASHBOARD) }, colors = colors,
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) }, label = { Text("Início") },
        )
        NavigationBarItem(
            selected = sel(Routes.RADAR), onClick = { onNavigate(Routes.RADAR) }, colors = colors,
            icon = { Icon(Icons.Outlined.Radar, contentDescription = null) }, label = { Text("Radar") },
        )
        NavigationBarItem(
            selected = sel(Routes.LIVE), onClick = { onNavigate(Routes.LIVE) }, colors = colors,
            icon = {
                Box {
                    Icon(Icons.Outlined.Gavel, contentDescription = null)
                    if (critical) PulsingDot(LicitaColors.Red, Modifier.align(Alignment.TopEnd), size = 8.dp)
                }
            },
            label = { Text("Pregões") },
        )
        NavigationBarItem(
            selected = sel(Routes.ROBOT), onClick = { onNavigate(Routes.ROBOT) }, colors = colors,
            icon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) }, label = { Text("Robô") },
        )
        NavigationBarItem(
            selected = false, onClick = onMore, colors = colors,
            icon = { Icon(Icons.Outlined.Menu, contentDescription = null) }, label = { Text("Mais") },
        )
    }
}

/** Toast premium no topo da tela para alertas em tempo real. */
@Composable
private fun InAppAlertHost(
    alert: AppNotification?,
    onClick: (AppNotification) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Mantém o último alerta durante a animação de saída.
    var last by remember { mutableStateOf<AppNotification?>(null) }
    if (alert != null) last = alert
    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically(tween(260)) { -it } + fadeIn(tween(260)),
        exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val shown = last ?: return@AnimatedVisibility
        val tone = if (shown.critical) Tone.DANGER else shown.category.tone()
        val color = tone.color()
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(LicitaColors.SurfaceHigh)
                .border(1.dp, color.copy(alpha = 0.7f), MaterialTheme.shapes.large)
                .clickable { onClick(shown) }
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shown.critical) PulsingDot(color, size = 12.dp) else Icon(tone.icon(), contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(shown.title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shown.body, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Fechar", tint = LicitaColors.TextSecondary) }
        }
    }
}
