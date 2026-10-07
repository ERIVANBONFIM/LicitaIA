package com.licitaia.feature.live.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.AssistedBidding
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveUiState(
    val loading: Boolean = true,
    val sessions: List<LiveSession> = emptyList(),
    val role: UserRole? = null,
    val companyId: Long? = null,
    val alertsMuted: Boolean = false,
) {
    val canOperate get() = role?.let { Rbac.can(it, Permission.OPERAR_SESSOES) } ?: false
}

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val manager: LiveSessionManager,
    auth: AuthRepository,
) : ViewModel() {

    private val ready = MutableStateFlow(false)

    val state: StateFlow<LiveUiState> = combine(manager.sessions, auth.session, ready, manager.alertsMuted) { sessions, session, ready, muted ->
        LiveUiState(loading = !ready, sessions = sessions, role = session?.user?.role, companyId = session?.activeCompany?.id, alertsMuted = muted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    init {
        viewModelScope.launch {
            auth.session.filterNotNull().map { it.activeCompany.id }.collect { companyId ->
                runCatching { manager.restoreOrSeed(companyId) }
                ready.value = true
            }
        }
    }
}

@Composable
fun LiveScreen(vm: LiveViewModel = hiltViewModel(), myVm: MyTendersViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mine by myVm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    androidx.compose.runtime.LaunchedEffect(Unit) { myVm.events.collect { navigator.showMessage(it) } }

    LicitaScaffold(
        title = "Pregões ao Vivo",
        showBack = false,
        subtitle = "Comprasnet · robô e acompanhamento",
        floatingActionButton = {
            if (state.canOperate) {
                ExtendedFloatingActionButton(
                    onClick = { navigator.navigate(Routes.LIVE_NEW) },
                    containerColor = LicitaColors.Blue, contentColor = Color.White,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Acompanhar pregão") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading && state.sessions.isEmpty() && mine.tenders.isEmpty() -> SkeletonList(Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Sessões reais do Comprasnet: minhas licitações (com data de sessão), robôs armados e em andamento.
                item("mine") {
                    MyTendersPanel(
                        mine, onRefresh = { myVm.refresh() },
                        onOpenTender = { navigator.navigate(RobotRoutes.plan(it.tenderKey)) },
                        onOpenPortal = { navigator.navigate(Routes.portalWeb(Portal.COMPRAS_GOV)) },
                        maxItems = 6,
                    )
                }
                if (state.sessions.isEmpty()) {
                    item("empty") {
                        EmptyState(
                            "Nenhum pregão acompanhado",
                            "Sessões do robô de lance e acompanhamentos assistidos aparecem aqui com o histórico de lances.",
                            icon = Icons.Outlined.Podcasts,
                            actionLabel = if (state.canOperate) "Acompanhar pregão" else "Abrir portais", onAction = { navigator.navigate(if (state.canOperate) Routes.LIVE_NEW else Routes.PORTALS) },
                        )
                    }
                }
                if (state.sessions.isNotEmpty()) item("summary") {
                    val open = state.sessions.filter { it.isOpen }
                    val alerts = open.sumOf { AssistedBidding.activeAlerts(it).size }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${open.size} sessão(ões) acompanhada(s)", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                            Text(
                                "${open.count { it.isWinning }} vencendo · ${open.count { it.timerRunning }} cronômetro(s) · $alerts alerta(s)",
                                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                            )
                        }
                        StatusBadge(if (state.alertsMuted) "Alertas silenciados" else "Acompanhamento", if (state.alertsMuted) Tone.WARNING else Tone.INFO)
                    }
                }
                val critical = state.sessions.filter { it.isOpen && AssistedBidding.activeAlerts(it).any { a -> a == AssistedBidding.AlertKind.PROXIMO_DO_PISO || a == AssistedBidding.AlertKind.TEMPO_CRITICO } }
                if (critical.isNotEmpty()) {
                    item("critical") {
                        AlertBanner(
                            "Atenção: piso próximo ou fechamento iminente",
                            critical.joinToString { "${it.portal.shortName} ${it.tenderNumber}" },
                            Tone.DANGER, pulsing = true, actionLabel = "Abrir", onAction = { navigator.navigate(Routes.liveSession(critical.first().id)) },
                        )
                    }
                }
                items(state.sessions, key = { it.id }) { session ->
                    LiveSessionCard(
                        session = session,
                        onOpen = { navigator.navigate(Routes.liveSession(session.id)) },
                        onWebView = { navigator.navigate(Routes.portalWeb(session.portal)) },
                    )
                }
            }
        }
    }
}

@Composable
fun LiveSessionCard(
    session: LiveSession,
    onOpen: () -> Unit,
    onWebView: () -> Unit,
) {
    val alerts = AssistedBidding.activeAlerts(session)
    val accent by animateColorAsState(
        when {
            session.status == LiveStatus.ERRO -> LicitaColors.Red
            AssistedBidding.AlertKind.PROXIMO_DO_PISO in alerts || AssistedBidding.AlertKind.TEMPO_CRITICO in alerts -> LicitaColors.Red
            alerts.isNotEmpty() -> LicitaColors.Yellow
            session.isWinning -> LicitaColors.Green
            session.status == LiveStatus.ENCERRADA -> LicitaColors.Outline
            else -> LicitaColors.Blue
        },
        tween(500), label = "accent",
    )
    LicitaCard(Modifier.fillMaxWidth().animateContentSize(), onClick = onOpen, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortalChip(session.portal)
            Spacer(Modifier.width(8.dp))
            Text(session.tenderNumber, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge(session.status.label, session.status.tone(), pulsing = session.status == LiveStatus.EM_DISPUTA)
        }
        Spacer(Modifier.height(4.dp))
        Text(session.itemLabel, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, fontWeight = FontWeight.Medium)
        Text(session.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PositionBadge(session.position, session.competitors)
            Telemetry("Nosso lance", session.ourLastBid?.let(Formatters::brlCompact) ?: "—", LicitaColors.TextPrimary)
            Telemetry("Melhor lance", session.bestBid?.let(Formatters::brlCompact) ?: "—", if (session.isWinning) LicitaColors.GreenBright else LicitaColors.BlueBright)
            Telemetry(
                if (session.timerRunning) "Tempo" else "Cronômetro",
                Formatters.countdown(session.remainingSeconds),
                if (session.timerRunning && (session.remainingSeconds ?: 999) <= AssistedBidding.TIMER_ALERT_SECONDS) LicitaColors.RedBright else if (session.timerRunning) LicitaColors.TextPrimary else LicitaColors.TextMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Telemetry("Piso", Formatters.brlCompact(BidRuleEngine.effectiveFloor(session.rule)), LicitaColors.Yellow)
            Telemetry("Margem", Formatters.percent(session.currentMarginPct), if (session.currentMarginPct >= session.rule.minMarginPct) LicitaColors.GreenBright else LicitaColors.RedBright)
            Telemetry("Estratégia", session.rule.strategy.label, LicitaColors.TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        MarginToFloorBar(session)
        if (alerts.isNotEmpty() && session.isOpen) {
            Spacer(Modifier.height(8.dp))
            Text(alerts.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
        }
        if (session.isOpen) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Operar", onOpen, Modifier.weight(1f), icon = Icons.Outlined.OpenInNew)
                SecondaryButton("Portal", onWebView, Modifier.weight(1f), icon = Icons.Outlined.Language, tone = Tone.NEUTRAL)
            }
        }
    }
}

@Composable
fun PositionBadge(position: Int, competitors: Int) {
    val color = when {
        position == 1 -> LicitaColors.Green
        position == 0 -> LicitaColors.TextMuted
        position <= 2 -> LicitaColors.Yellow
        else -> LicitaColors.Red
    }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (position == 0) "—" else "${position}º", style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text("de ${competitors + 1}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(if (position == 1) "Vencendo" else "Posição", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
    }
}

@Composable
fun Telemetry(label: String, value: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
    }
}

/** Barra que mostra onde o nosso lance está entre o preço inicial (100%) e o piso efetivo (0%). */
@Composable
fun MarginToFloorBar(session: LiveSession) {
    val rule = session.rule
    val floor = BidRuleEngine.effectiveFloor(rule)
    val span = (rule.initialPrice - floor).takeIf { it > 0 } ?: 1.0
    val ours = session.ourLastBid ?: rule.initialPrice
    val fraction = ((ours - floor) / span).coerceIn(0.0, 1.0).toFloat()
    val animated by animateFloatAsState(fraction, tween(600), label = "floor")
    val color = when {
        fraction <= 0.05f -> LicitaColors.Red
        fraction <= 0.25f -> LicitaColors.Yellow
        else -> LicitaColors.Green
    }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("Folga até o piso", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
            Text(Formatters.brlCompact((ours - floor).coerceAtLeast(0.0)), style = MaterialTheme.typography.labelSmall, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(LicitaColors.Outline)) {
            Box(Modifier.fillMaxWidth(animated).height(6.dp).clip(RoundedCornerShape(50)).background(color))
        }
    }
}
