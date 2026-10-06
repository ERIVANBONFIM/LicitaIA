package com.licitaia.feature.tender

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.licitaia.core.ui.nav.Routes

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.tenderGraph() {
    composable(Routes.INTERESTS) { InterestsScreen() }
    composable(Routes.ANALYZE) { AnalyzeScreen() }
    composable(Routes.PARTICIPATIONS) { ParticipationsScreen() }
    // Rota literal registrada antes do padrão com argumento: "tender/new" nunca vira tenderId.
    composable(Routes.TENDER_NEW) { TenderNewScreen() }
    composable(Routes.TENDER) { TenderDetailScreen() }
    composable(Routes.TENDER_ANALYSIS) { TenderAnalysisScreen() }
    composable(Routes.TENDER_WORTH) { TenderWorthScreen() }
    composable(Routes.TENDER_PROPOSAL) { ProposalScreen() }
    composable(Routes.PROPOSAL_PDF) { ProposalPdfScreen() }
}
