package com.licitaia.feature.platform

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.licitaia.core.ui.nav.Routes

/** Grafo de navegação da plataforma LicitaPRO (modo online opcional). O módulo app apenas chama esta função. */
fun NavGraphBuilder.platformGraph() {
    composable(Routes.PLATFORM_LOGIN) { PlatformLoginScreen() }
    composable(Routes.PLATFORM_TENDERS) { PlatformTendersScreen() }
}
