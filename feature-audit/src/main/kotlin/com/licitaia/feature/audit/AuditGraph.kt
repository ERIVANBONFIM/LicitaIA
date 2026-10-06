package com.licitaia.feature.audit

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.licitaia.core.ui.nav.Routes

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.auditGraph() {
    composable(Routes.AUDIT) { AuditScreen() }
}
