package com.licitaia.feature.radar

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.licitaia.core.ui.nav.Routes

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.radarGraph() {
    composable(Routes.SEARCH) { SearchScreen() }
    composable(Routes.RADAR) { RadarListScreen() }
    composable(Routes.RADAR_EDIT) { RadarEditScreen() }
    composable(Routes.RADAR_RESULTS) { RadarResultsScreen() }
}
