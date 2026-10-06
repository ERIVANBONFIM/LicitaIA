package com.licitaia.feature.documents

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.licitaia.core.ui.nav.Routes

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.documentsGraph() {
    composable(Routes.DOCUMENTS) { DocumentsScreen() }
    composable(Routes.DOCUMENT_EDIT) { DocumentEditScreen() }
}
