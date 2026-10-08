package com.licitaia.feature.platform

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.licitaia.core.ui.nav.Routes

/** Grafo de navegação da plataforma LicitaPRO (modo online). O módulo app apenas chama esta função. */
fun NavGraphBuilder.platformGraph() {
    composable(Routes.PLATFORM_LOGIN) { PlatformLoginScreen() }
    composable(Routes.PLATFORM_TENDERS) { PlatformTendersScreen() }
    composable(Routes.PLATFORM_DIRECTORY) { PlatformDirectoryScreen() }
    composable(
        Routes.PLATFORM_TENDER,
        arguments = listOf(navArgument("platformId") { type = NavType.StringType }),
    ) { PlatformTenderDetailScreen() }
}
