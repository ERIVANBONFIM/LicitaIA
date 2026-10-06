package com.licitaia.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.licitaia.core.ui.nav.Routes
import com.licitaia.feature.settings.ai.AiSettingsScreen
import com.licitaia.feature.settings.companies.CompaniesScreen
import com.licitaia.feature.settings.portals.PortalsScreen
import com.licitaia.feature.settings.security.SecurityScreen

/** Grafo de navegação da feature. O módulo app apenas chama esta função. */
fun NavGraphBuilder.settingsGraph() {
    composable(Routes.SETTINGS) { SettingsScreen() }
    composable(Routes.AI_SETTINGS) { AiSettingsScreen() }
    composable(Routes.SECURITY) { SecurityScreen() }
    composable(Routes.PORTALS) { PortalsScreen() }
    composable(Routes.COMPANIES) { CompaniesScreen() }
}
