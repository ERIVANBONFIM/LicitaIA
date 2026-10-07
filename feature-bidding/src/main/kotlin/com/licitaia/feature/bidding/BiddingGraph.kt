package com.licitaia.feature.bidding

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.licitaia.core.ui.nav.Routes
import com.licitaia.feature.bidding.ui.RobotConfigScreen
import com.licitaia.feature.bidding.ui.RobotPlanScreen
import com.licitaia.feature.bidding.ui.RobotScreen
import com.licitaia.feature.live.ui.RobotRoutes
import com.licitaia.feature.bidding.ui.StrategyScreen

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.biddingGraph() {
    composable(Routes.ROBOT) { RobotScreen() }
    composable(Routes.ROBOT_CONFIG, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { RobotConfigScreen() }
    // Robô do Comprasnet por licitação (proposta + lance). "robotplan/{key}" não colide com "robot/{sessionId}".
    composable(RobotRoutes.PLAN, arguments = listOf(navArgument("key") { type = NavType.StringType })) { RobotPlanScreen() }
    // Entrada pela proposta do app (feature-tender navega por "robotproposal/<tenderId>").
    composable(RobotRoutes.PROPOSAL, arguments = listOf(navArgument("tenderId") { type = NavType.LongType })) { com.licitaia.feature.bidding.ui.RobotProposalEntryScreen() }
    composable(Routes.STRATEGY) { StrategyScreen() }
}
