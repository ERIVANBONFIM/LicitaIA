package com.licitaia.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.licitaia.app.shell.AppRoot
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaTheme
import com.licitaia.domain.model.Portal
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.feature.live.automation.PortalRobotEngine
import com.licitaia.feature.live.web.PortalWebViewHolder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Activity única. FragmentActivity é exigida pelo BiometricPrompt. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var portalWebViews: PortalWebViewHolder

    @Inject
    lateinit var robotEngine: PortalRobotEngine

    /** Rota pedida por uma notificação do sistema (extra "route"). */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) consumeRouteExtra(intent)
        observeScreenshotProtection()

        setContent {
            LicitaTheme {
                AppRoot(
                    pendingRoute = pendingRoute,
                    onRouteConsumed = { pendingRoute.value = null },
                )
            }
        }
        // Depois do setContent: host invisível ATRÁS do conteúdo, onde o WebView retido do portal fica anexado a esta
        // janela enquanto a tela do portal não o exibe (sem janela ele não carrega páginas).
        portalWebViews.installHost(this)
        observeRobotRequests()
    }

    override fun onDestroy() {
        portalWebViews.uninstallHost(this)
        super.onDestroy()
    }

    /** O robô parou pedindo algo no portal (ex.: termo/declarações): abre a tela do portal com a faixa do robô. */
    private fun observeRobotRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                robotEngine.portalRequests.collect { pendingRoute.value = Routes.portalWeb(Portal.COMPRAS_GOV) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeRouteExtra(intent)
    }

    private fun consumeRouteExtra(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_ROUTE)
        pendingRoute.value = route
    }

    /** FLAG_SECURE conforme a configuração "proteção de captura de tela". */
    private fun observeScreenshotProtection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.settings.map { it.screenshotProtection }.distinctUntilChanged().collect { protect ->
                    if (protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
