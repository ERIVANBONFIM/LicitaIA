package com.licitaia.feature.live

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.licitaia.core.ui.nav.Routes
import com.licitaia.feature.live.ui.AssistedSessionFormScreen
import com.licitaia.feature.live.ui.LiveScreen
import com.licitaia.feature.live.ui.LiveSessionScreen
import com.licitaia.feature.live.ui.WebViewScreen
import com.licitaia.feature.live.web.PortalWebViewScreen

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.liveGraph() {
    val sessionArg = listOf(navArgument("sessionId") { type = NavType.StringType })
    composable(Routes.LIVE) { LiveScreen() }
    // Formulário "Acompanhar pregão" (rota literal declarada antes do padrão live/{sessionId}).
    composable(
        "${Routes.LIVE_NEW}?tenderId={tenderId}",
        arguments = listOf(navArgument("tenderId") { type = NavType.LongType; defaultValue = -1L }),
    ) { AssistedSessionFormScreen() }
    composable(Routes.LIVE_SESSION, arguments = sessionArg) { LiveSessionScreen() }
    // Rota legada (por sessão de pregão): redireciona para o navegador do portal da sessão.
    composable(Routes.WEBVIEW, arguments = sessionArg) { WebViewScreen() }
    // Navegador interno do portal (login manual, cookies persistentes, perfil por empresa).
    composable(Routes.PORTAL_WEB, arguments = listOf(navArgument("portal") { type = NavType.StringType })) { PortalWebViewScreen() }
}
