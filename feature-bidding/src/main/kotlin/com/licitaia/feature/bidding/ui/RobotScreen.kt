package com.licitaia.feature.bidding.ui

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
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
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
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class RobotViewModel @Inject constructor(manager: LiveSessionManager) : ViewModel() {
    val sessions: StateFlow<List<LiveSession>> = manager.sessions
}

/**
 * Tela "Robô de Lances": explica honestamente que não há automação de lances (não existe API oficial
 * autorizada) e encaminha ao modo assistido. Estratégia e Simulador continuam disponíveis para treino.
 */
@Composable
fun RobotScreen(vm: RobotViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val open = sessions.filter { it.isOpen }

    LicitaScaffold(title = "Robô de Lances", showBack = false) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Outlined.SmartToy, LicitaColors.Yellow)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Automação de lances indisponível", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                        Text("Não há API oficial autorizada pelos portais.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                    }
                    StatusBadge("Desligado", Tone.NEUTRAL)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Compras.gov.br, BLL, Licitanet e Portal de Compras Públicas não oferecem API autenticada pública para enviar lances, " +
                        "e automatizar o navegador sem autorização violaria os termos de uso. Por isso o LicitaIA não envia, agenda nem repete lances — " +
                        "e nunca resolve CAPTCHA/MFA.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }

            AlertBanner(
                "Use o modo assistido",
                "Você dá o lance no portal (navegador interno com sessão salva) e registra aqui. O app valida o piso, calcula margem, sugere o próximo lance pela estratégia escolhida, cronometra e alerta.",
                Tone.INFO,
            )
            PrimaryButton("Ir para Pregões ao Vivo", { navigator.navigateTop(Routes.LIVE) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Podcasts)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Estratégias", { navigator.navigate(Routes.STRATEGY) }, Modifier.weight(1f), icon = Icons.Outlined.School)
                SecondaryButton("Simulador", { navigator.navigate(Routes.SIMULATOR) }, Modifier.weight(1f), tone = Tone.NEUTRAL)
            }
            Text(
                "Estratégia e Simulador rodam 100% no aparelho com o mesmo motor de regras do modo assistido — servem para treinar e comparar cenários, nunca para operar.",
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
