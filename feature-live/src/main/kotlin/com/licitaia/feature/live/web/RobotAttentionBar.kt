package com.licitaia.feature.live.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.feature.live.automation.RobotAttention

/**
 * Faixa do robô sobre a página do Comprasnet: o que ele está fazendo e, quando parou num passo, os botões
 * "Tentar de novo", "Mapear esta tela" e "Continuar manualmente". PARAR sempre visível com robô ativo.
 */
@Composable
internal fun RobotAttentionBar(vm: PortalWebViewModel, companyId: Long) {
    val runs by vm.robots.runs.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val active = runs.values.filter { it.companyId == companyId && it.active }.sortedByDescending { it.updatedAt }
    val run = active.firstOrNull() ?: return
    LicitaCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), accent = if (run.needsUser) LicitaColors.Yellow else LicitaColors.Blue) {
        Row {
            Column(Modifier.weight(1f)) {
                Text("${run.kind.label} · ${run.title}", style = MaterialTheme.typography.labelLarge, color = LicitaColors.TextPrimary)
                if (run.attention == RobotAttention.DECLARATIONS) {
                    Text("Marque o termo e as declarações e toque em Continuar", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                }
                Text(run.message ?: run.step, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 5)
            }
            StatusBadge(run.status.label, if (run.needsUser) Tone.WARNING else Tone.INFO, pulsing = !run.needsUser)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (run.needsUser) {
                val declarations = run.attention == RobotAttention.DECLARATIONS
                // Declarações: o usuário marca no portal e toca Continuar (o robô confere de novo; nunca marca).
                TextButton(onClick = { vm.robots.continueRun(run.id) }) { Text(if (declarations) "Continuar" else "Tentar de novo") }
                if (!declarations) {
                    TextButton(onClick = {
                        vm.mapScreen(companyId) { ok -> navigator.showMessage(if (ok) "Tela mapeada para validação." else "Não foi possível mapear esta tela.") }
                    }) { Text("Mapear esta tela") }
                }
                TextButton(onClick = { vm.robots.takeOverManually(run.id) }) { Text("Continuar manualmente") }
            } else {
                TextButton(onClick = { vm.robots.stop(run.id) }) { Text("PARAR", color = LicitaColors.Red) }
            }
        }
    }
}
