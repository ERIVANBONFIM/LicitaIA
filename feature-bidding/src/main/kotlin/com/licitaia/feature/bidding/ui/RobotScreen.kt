package com.licitaia.feature.bidding.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.DangerButton
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.portal.RobotProposalStatus
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.live.ui.MyTendersPanel
import com.licitaia.feature.live.ui.MyTendersViewModel
import com.licitaia.feature.live.ui.RobotRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class RobotViewModel @Inject constructor(manager: LiveSessionManager) : ViewModel() {
    val sessions: StateFlow<List<LiveSession>> = manager.sessions
}

/**
 * Tela "Robô": estado REAL do robô do Comprasnet — sessão do portal, minhas licitações, robôs agendados/rodando,
 * "Buscar minhas licitações", "Configurar robô" e "Mapear telas do portal". O robô opera na sua própria sessão
 * logada (certificado A1), com confirmação explícita, piso inviolável e PARAR sempre à mão.
 */
@Composable
fun RobotScreen(vm: RobotViewModel = hiltViewModel(), mineVm: MyTendersViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val mine by mineVm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { mineVm.events.collect { navigator.showMessage(it) } }
    var pickTender by remember { mutableStateOf(false) }
    val open = sessions.filter { it.isOpen }
    val activeRuns = mine.runs.filter { it.active }

    if (pickTender) {
        AlertDialog(
            onDismissRequest = { pickTender = false },
            title = { Text("Configurar robô de qual licitação?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mine.tenders.isEmpty()) Text("Nenhuma licitação ainda. Toque em “Buscar minhas licitações”.")
                    mine.tenders.forEach { t ->
                        Text(
                            "${t.label}\n${t.objectDescription.take(80)}", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { pickTender = false; navigator.navigate(RobotRoutes.plan(t.tenderKey)) }.padding(vertical = 6.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTender = false }) { Text("Fechar") } },
        )
    }

    LicitaScaffold(title = "Robô", showBack = false, subtitle = "Comprasnet · proposta e lances") { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeRuns.isNotEmpty()) {
                DangerButton("PARAR TODOS OS ROBÔS (${activeRuns.size})", { mineVm.stopAll() }, icon = Icons.Outlined.StopCircle)
            }

            // Sessão do Comprasnet
            LicitaCard(Modifier.fillMaxWidth(), accent = if (mine.portalConnected) LicitaColors.Green else LicitaColors.Yellow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Outlined.SmartToy, if (mine.portalConnected) LicitaColors.Green else LicitaColors.Yellow)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sessão do Comprasnet", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                        Text(
                            when {
                                mine.loggedOutByCheck -> "Deslogado — a última leitura do robô caiu no login do portal. Entre de novo em Portais."
                                mine.portalConnected -> "Logado — o robô usa esta sessão (certificado da empresa). Deixe o app aberto enquanto ele opera."
                                mine.portalStatus == PortalConnectionStatus.SESSAO_EXPIRADA -> "Sessão expirada — entre de novo em Portais."
                                else -> "Deslogado — entre no Comprasnet em Portais."
                            },
                            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                        )
                    }
                    StatusBadge(if (mine.portalConnected) "Logado" else "Deslogado", if (mine.portalConnected) Tone.SUCCESS else Tone.WARNING, pulsing = mine.portalConnected)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Abrir Comprasnet", { navigator.navigate(Routes.portalWeb(Portal.COMPRAS_GOV)) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Language, tone = Tone.NEUTRAL)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Buscar minhas licitações", { mineVm.refresh() }, Modifier.weight(1f), loading = mine.syncing, icon = Icons.Outlined.Refresh, enabled = mine.canOperate)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Configurar robô", { pickTender = true }, Modifier.weight(1f), icon = Icons.Outlined.Settings, enabled = mine.canOperate)
                SecondaryButton("Mapear telas", { mineVm.setMapMode(!mine.mapMode) }, Modifier.weight(1f), icon = Icons.Outlined.Map, tone = if (mine.mapMode) Tone.SUCCESS else Tone.INFO)
            }

            // Modo mapear
            LicitaCard(Modifier.fillMaxWidth(), accent = if (mine.mapMode) LicitaColors.Green else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mapear telas do portal", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                        Text(
                            "Ligado: cada tela do Comprasnet aberta no navegador do app grava a ESTRUTURA (campos, botões, tabelas) — sem valores digitados, sem tokens, CPF/CNPJ mascarados. ${mine.snapshots} tela(s) gravada(s).",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                        )
                    }
                    Switch(checked = mine.mapMode, onCheckedChange = { mineVm.setMapMode(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Compartilhar mapa", {
                        mineVm.export { intent ->
                            if (intent == null) navigator.showMessage("Nenhuma tela mapeada ainda.")
                            else runCatching { context.startActivity(Intent.createChooser(intent, "Compartilhar mapa das telas").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }, Modifier.weight(1f), icon = Icons.Outlined.Share, enabled = mine.snapshots > 0)
                    SecondaryButton("Apagar", { mineVm.clearSnapshots() }, Modifier.weight(1f), tone = Tone.NEUTRAL, enabled = mine.snapshots > 0)
                }
            }

            // Robôs agendados / em execução
            val scheduled = mine.plans.values.filter { it.bidArmed || it.proposalStatus != RobotProposalStatus.NAO_CONFIGURADA }
            if (scheduled.isNotEmpty() || activeRuns.isNotEmpty()) {
                SectionHeader("Robôs agendados e em execução")
                activeRuns.forEach { r ->
                    LicitaCard(Modifier.fillMaxWidth(), onClick = { navigator.navigate(RobotRoutes.plan(r.tenderKey)) }, accent = if (r.needsUser) LicitaColors.Yellow else LicitaColors.Blue) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.kind.label} · ${r.title}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                            StatusBadge(r.status.label, if (r.needsUser) Tone.WARNING else Tone.INFO, pulsing = !r.needsUser)
                        }
                        Text(r.message ?: r.step, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                    }
                }
                scheduled.forEach { p ->
                    val t = mine.tenders.firstOrNull { it.tenderKey == p.tenderKey }
                    LicitaCard(Modifier.fillMaxWidth(), onClick = { navigator.navigate(RobotRoutes.plan(p.tenderKey)) }) {
                        Text(t?.label ?: p.tenderKey, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                        Text(
                            "Proposta: ${p.proposalStatus.label} · Lance: ${if (p.bidArmed) "armado (${p.bid.mode.label})" else "desarmado"}" +
                                (p.sessionAt?.let { " · sessão ${Formatters.dateTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                        )
                    }
                }
            }

            MyTendersPanel(
                mine, onRefresh = { mineVm.refresh() },
                onOpenTender = { navigator.navigate(RobotRoutes.plan(it.tenderKey)) },
                onOpenPortal = { navigator.navigate(Routes.portalWeb(Portal.COMPRAS_GOV)) },
            )

            SectionHeader("Estratégia de lance")
            SecondaryButton("Estratégias do robô", { navigator.navigate(Routes.STRATEGY) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.School)
            Text(
                "As estratégias (Conservadora, Agressiva, Acompanhar, Personalizada) são as mesmas usadas pelo robô de lance.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )

            if (open.isNotEmpty()) {
                SectionHeader("Sessões acompanhadas (${open.size})")
                open.forEach { s ->
                    LicitaCard(Modifier.fillMaxWidth(), onClick = { navigator.navigate(Routes.liveSession(s.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PortalChip(s.portal)
                            Spacer(Modifier.width(8.dp))
                            Text(s.tenderNumber, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                            StatusBadge(s.status.label, s.status.tone())
                        }
                        Text(s.itemLabel, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                        Text("Estratégia ${s.rule.strategy.label} · piso ${Formatters.brl(s.rule.floorPrice)} · margem ${Formatters.percent(s.currentMarginPct)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
