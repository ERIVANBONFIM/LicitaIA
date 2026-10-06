package com.licitaia.feature.bidding

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.licitaia.core.ui.nav.Routes
import com.licitaia.feature.bidding.ui.RobotConfigScreen
import com.licitaia.feature.bidding.ui.RobotScreen
import com.licitaia.feature.bidding.ui.SimulatorScreen
import com.licitaia.feature.bidding.ui.StrategyScreen

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.biddingGraph() {
    composable(Routes.ROBOT) { RobotScreen() }
    composable(Routes.ROBOT_CONFIG, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { RobotConfigScreen() }
    composable(Routes.STRATEGY) { StrategyScreen() }
    composable(Routes.SIMULATOR) { SimulatorScreen() }
}
