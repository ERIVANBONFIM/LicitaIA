package com.licitaia.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatCard
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.AppNavigator
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.Tender
import com.licitaia.domain.util.Formatters
import java.util.Calendar

private data class Indicator(
    val label: String,
    val value: Int,
    val icon: ImageVector,
    val tone: Tone,
    val highlight: Boolean,
    val onClick: () -> Unit,
)

private data class Shortcut(val label: String, val icon: ImageVector, val color: Color, val route: String)

private val shortcuts = listOf(
    Shortcut("Buscar", Icons.Outlined.Search, LicitaColors.Blue, Routes.SEARCH),
    Shortcut("Radar", Icons.Outlined.Radar, LicitaColors.Green, Routes.RADAR),
    Shortcut("Interesse", Icons.Outlined.StarOutline, LicitaColors.Yellow, Routes.INTERESTS),
    Shortcut("Analisar edital", Icons.Outlined.Analytics, LicitaColors.Purple, Routes.ANALYZE),
    Shortcut("Pregões ao vivo", Icons.Outlined.LiveTv, LicitaColors.Red, Routes.LIVE),
    Shortcut("Robô", Icons.Outlined.SmartToy, LicitaColors.Cyan, Routes.ROBOT),
    Shortcut("Sala de Guerra", Icons.Outlined.Shield, LicitaColors.RedBright, Routes.WARROOM),
    Shortcut("Documentos", Icons.Outlined.Folder, LicitaColors.GreenBright, Routes.DOCUMENTS),
    Shortcut("Portais", Icons.Outlined.Hub, LicitaColors.BlueBright, Routes.PORTALS),
    Shortcut("Configurações", Icons.Outlined.Settings, LicitaColors.TextSecondary, Routes.SETTINGS),
)

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    LicitaScaffold(title = "Início", showBack = false) { padding ->
        if (state.loading) {
            SkeletonList(Modifier.padding(padding), items = 5)
            return@LicitaScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "captcha") {
                AnimatedVisibility(
                    state.captchaSessions.isNotEmpty(),
                    enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.captchaSessions.forEach { session ->
                            AlertBanner(
                                title = "CAPTCHA aguardando — sessão pausada",
                                message = "${session.portal.shortName} · ${session.tenderNumber} · ${session.itemLabel}. " +
                                    "Resolva manualmente no portal para retomar.",
                                tone = Tone.DANGER,
                                actionLabel = "Abrir",
                                onAction = { navigator.navigate(Routes.liveSession(session.id)) },
                                pulsing = true,
                            )
                        }
                    }
                }
            }
            item(key = "hero") { Hero(state) }
            item(key = "indicators") { IndicatorGrid(state, navigator) }
            item(key = "shortcuts-h") { SectionHeader("Atalhos") }
            item(key = "shortcuts") { ShortcutGrid(navigator) }

            item(key = "live-h") {
                SectionHeader("Pregões agora", actionLabel = "Ver todos", onAction = { navigator.navigateTop(Routes.LIVE) })
            }
            item(key = "live") {
                if (state.sessions.isEmpty()) {
                    LicitaCard(Modifier.fillMaxWidth(), onClick = { navigator.navigateTop(Routes.LIVE) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(Icons.Outlined.LiveTv, LicitaColors.TextSecondary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Nenhum pregão em andamento", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                Text("As sessões abertas aparecem aqui em tempo real.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                            }
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.sessions, key = { it.id }) { session ->
                            LiveSessionCompactCard(session) { navigator.navigate(Routes.liveSession(session.id)) }
                        }
                    }
                }
            }

            item(key = "deadlines-h") {
                SectionHeader("Próximos prazos", actionLabel = "Ver todas", onAction = { navigator.navigateTop(Routes.INTERESTS) })
            }
            if (state.upcoming.isEmpty()) {
                item(key = "deadlines-empty") {
                    EmptyState(
                        title = "Sem prazos próximos",
                        message = "Marque \"Tenho Interesse\" em uma licitação para acompanhar os prazos aqui.",
                        icon = Icons.Outlined.EventAvailable,
                        actionLabel = "Buscar licitações",
                        onAction = { navigator.navigateTop(Routes.SEARCH) },
                    )
                }
            } else {
                items(state.upcoming, key = { "t-${it.id}" }) { tender ->
                    DeadlineCard(tender) { navigator.navigate(Routes.tender(tender.id)) }
                }
            }
        }
    }
}

@Composable
private fun Hero(state: DashboardUiState) {
    GradientCard(Modifier.fillMaxWidth()) {
        Text(
            "${greeting()}, ${state.userName.trim().substringBefore(' ').ifBlank { "bem-vindo" }}",
            style = MaterialTheme.typography.headlineSmall, color = LicitaColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Business, contentDescription = null, tint = LicitaColors.GreenBright, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                state.companyName, style = MaterialTheme.typography.titleSmall, color = LicitaColors.GreenBright,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (state.roleLabel.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                StatusBadge(state.roleLabel, Tone.INFO)
            }
        }
        if (state.companyCnpj.isNotBlank()) {
            Text("CNPJ ${Formatters.cnpj(state.companyCnpj)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
        }
        Spacer(Modifier.height(14.dp))
        Text(operationalSummary(state), style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.captchaSessions.isNotEmpty() -> StatusBadge("Ação necessária", Tone.DANGER, pulsing = true)
                state.pendingAuthorizations > 0 -> StatusBadge("Autorizações pendentes", Tone.WARNING, pulsing = true)
                state.liveCount > 0 -> StatusBadge("Operação ao vivo", Tone.SUCCESS, pulsing = true)
                else -> StatusBadge("Operação estável", Tone.SUCCESS)
            }
            if (state.documentsExpiring > 0) StatusBadge("${state.documentsExpiring} doc. vencendo", Tone.WARNING)
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Bom dia"
    in 12..17 -> "Boa tarde"
    else -> "Boa noite"
}

private fun operationalSummary(s: DashboardUiState): String {
    val parts = buildList {
        add(plural(s.liveCount, "pregão ao vivo", "pregões ao vivo"))
        add(plural(s.robotsActive, "robô ativo", "robôs ativos"))
        if (s.pendingAuthorizations > 0) add(plural(s.pendingAuthorizations, "autorização pendente", "autorizações pendentes"))
        if (s.radarMatches > 0) add(plural(s.radarMatches, "oportunidade no radar", "oportunidades no radar"))
    }
    return parts.joinToString(" · ")
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

@Composable
private fun IndicatorGrid(state: DashboardUiState, navigator: AppNavigator) {
    val indicators = listOf(
        Indicator("Pregões ao vivo", state.liveCount, Icons.Outlined.Gavel, if (state.captchaSessions.isNotEmpty()) Tone.DANGER else Tone.INFO, state.liveCount > 0) {
            navigator.navigateTop(Routes.LIVE)
        },
        Indicator("Robôs ativos", state.robotsActive, Icons.Outlined.SmartToy, Tone.SUCCESS, state.robotsActive > 0) {
            navigator.navigateTop(Routes.ROBOT)
        },
        Indicator("Alertas não lidos", state.unreadAlerts, Icons.Outlined.NotificationsActive, if (state.unreadAlerts > 0) Tone.WARNING else Tone.NEUTRAL, false) {
            navigator.navigate(Routes.NOTIFICATIONS)
        },
        Indicator("Radares encontrados", state.radarMatches, Icons.Outlined.Radar, Tone.SUCCESS, false) {
            navigator.navigateTop(Routes.RADAR)
        },
        Indicator("Licitações de interesse", state.interests, Icons.Outlined.StarOutline, Tone.INFO, false) {
            navigator.navigateTop(Routes.INTERESTS)
        },
        Indicator("Documentos vencendo", state.documentsExpiring, Icons.Outlined.WarningAmber, if (state.documentsExpiring > 0) Tone.WARNING else Tone.SUCCESS, false) {
            navigator.navigateTop(Routes.DOCUMENTS)
        },
        Indicator("Autorizações pendentes", state.pendingAuthorizations, Icons.Outlined.VerifiedUser, if (state.pendingAuthorizations > 0) Tone.WARNING else Tone.NEUTRAL, state.pendingAuthorizations > 0) {
            val waiting = state.sessions.firstOrNull { it.pendingAuthorization != null }
            if (waiting != null) navigator.navigate(Routes.liveSession(waiting.id)) else navigator.navigateTop(Routes.PARTICIPATIONS)
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        indicators.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { ind ->
                    StatCard(
                        label = ind.label, value = ind.value.toString(), icon = ind.icon,
                        modifier = Modifier.weight(1f), tone = ind.tone, highlight = ind.highlight, onClick = ind.onClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutGrid(navigator: AppNavigator) {
    LicitaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
        shortcuts.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { shortcut ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { navigator.navigateTop(shortcut.route) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconBubble(shortcut.icon, shortcut.color, size = 44.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            shortcut.label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.heightIn(min = 28.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSessionCompactCard(session: LiveSession, onClick: () -> Unit) {
    val accent = when {
        session.captchaPending -> LicitaColors.Red
        session.pendingAuthorization != null -> LicitaColors.Yellow
        else -> null
    }
    LicitaCard(Modifier.width(264.dp), onClick = onClick, accent = accent, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortalChip(session.portal)
            Spacer(Modifier.weight(1f))
            StatusBadge(
                if (session.captchaPending) "CAPTCHA" else session.status.label,
                if (session.captchaPending) Tone.DANGER else session.status.tone(),
                pulsing = session.captchaPending || session.status.tone() == Tone.SUCCESS,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(session.tenderNumber, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(session.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Posição", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Text(
                    if (session.position > 0) "${session.position}º" else "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (session.isWinning) LicitaColors.GreenBright else LicitaColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1.6f)) {
                Text("Melhor lance", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Text(Formatters.brl(session.bestBid), style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Tempo", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Text(Formatters.countdown(session.remainingSeconds), style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusBadge("Robô: ${session.robotStatus.label}", session.robotStatus.tone())
    }
}

@Composable
private fun DeadlineCard(tender: Tender, onClick: () -> Unit) {
    val days = ((tender.proposalDeadline - System.currentTimeMillis()) / CompanyDocument.DAY_MS).toInt()
    val tone = when {
        days <= 2 -> Tone.DANGER
        days <= 7 -> Tone.WARNING
        else -> Tone.INFO
    }
    LicitaCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Schedule, tone.color())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${tender.portal.shortName} · ${tender.number}",
                    style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(tender.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Propostas até ${Formatters.dateTime(tender.proposalDeadline)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(
                when (days) {
                    0 -> "Hoje"
                    1 -> "Amanhã"
                    else -> "em $days dias"
                },
                tone,
            )
        }
    }
}
