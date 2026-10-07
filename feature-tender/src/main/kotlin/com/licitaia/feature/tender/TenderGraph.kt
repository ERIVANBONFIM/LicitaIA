package com.licitaia.feature.tender

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.licitaia.core.ui.nav.Routes

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.tenderGraph() {
    composable(Routes.INTERESTS) { InterestsScreen() }
    composable(Routes.ANALYZE) { AnalyzeScreen() }
    composable(Routes.PARTICIPATIONS) { ParticipationsScreen() }
    composable(Routes.ARCHIVED) { ArchivedScreen() }
    // Rota literal registrada antes do padrão com argumento: "tender/new" nunca vira tenderId.
    composable(Routes.TENDER_NEW) { TenderNewScreen() }
    composable(Routes.TENDER) { TenderDetailScreen() }
    // Aba inicial opcional (?tab=analise|perguntas|itens); "tender/{id}/analysis" sem argumento continua valendo.
    composable(
        "${Routes.TENDER_ANALYSIS}?tab={tab}",
        arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { TenderAnalysisScreen() }
    composable(Routes.TENDER_WORTH) { TenderWorthScreen() }
    composable(Routes.TENDER_COMPETITORS) { TenderCompetitorsScreen() }
    composable(Routes.TENDER_PROPOSAL) { ProposalScreen() }
    composable(Routes.PROPOSAL_PDF) { ProposalPdfScreen() }
}
