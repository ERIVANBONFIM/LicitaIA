package com.licitaia.feature.live.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes

/**
 * Rota legada `webview/{sessionId}`: resolve o portal da sessão de pregão e redireciona para
 * o navegador interno do portal ([com.licitaia.feature.live.web.PortalWebViewScreen]),
 * substituindo esta entrada na pilha.
 */
@Composable
fun WebViewScreen(vm: LiveSessionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val session = state.session
    LaunchedEffect(session?.portal) {
        val portal = session?.portal ?: return@LaunchedEffect
        navigator.back()
        navigator.navigate(Routes.portalWeb(portal))
    }
    LicitaScaffold(title = "Portal oficial", showBack = true) { padding ->
        if (state.loading && session == null) SkeletonList(Modifier.padding(padding), items = 2)
        else if (session == null) ErrorState("A sessão foi encerrada ou não existe mais.", Modifier.padding(padding), title = "Sessão não encontrada")
    }
}
