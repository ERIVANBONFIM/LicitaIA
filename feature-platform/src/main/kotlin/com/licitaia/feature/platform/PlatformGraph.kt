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
    composable(Routes.PLATFORM_HUB) { PlatformHubScreen() }
    composable(Routes.PLATFORM_DIRECTORY) { PlatformDirectoryScreen() }
    composable(Routes.PLATFORM_DOCS) { PlatformDocumentsScreen() }
    composable(Routes.PLATFORM_RADAR) { PlatformRadarScreen() }
    composable(Routes.PLATFORM_COMPETITORS) { PlatformCompetitorsScreen() }
    composable(Routes.PLATFORM_MESSAGES) { PlatformMessagesScreen() }
    composable(Routes.PLATFORM_AUDIT) { PlatformAuditScreen() }
    composable(Routes.PLATFORM_SOON) { PlatformSoonScreen() }
    composable(
        Routes.PLATFORM_TENDER,
        arguments = listOf(navArgument("platformId") { type = NavType.StringType }),
    ) { PlatformTenderDetailScreen() }
    composable(
        Routes.PLATFORM_TENDER_QA,
        arguments = listOf(navArgument("platformId") { type = NavType.StringType }),
    ) { PlatformQuestionsScreen() }
}
