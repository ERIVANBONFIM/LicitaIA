package com.licitaia.feature.warroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.DangerButton
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.LinearMeter
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SimulationBadge
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatCard
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WarRoomAlert(val sessionId: String, val title: String, val message: String, val tone: Tone)

data class WarRoomUiState(
    val loading: Boolean = true,
    val sessions: List<LiveSession> = emptyList(),
    val unreadMessages: Int = 0,
    val pausing: Boolean = false,
    val lastPausedCount: Int? = null,
) {
    val open: List<LiveSession> get() = sessions.filter { it.status != LiveStatus.ENCERRADA }
    val activeAuctions: Int get() = open.count { it.status == LiveStatus.EM_DISPUTA || it.status == LiveStatus.CAPTCHA_PENDENTE }
    val activeRobots: Int get() = open.count { it.robotRunning }
    val captchas: Int get() = open.count { it.captchaPending }
    val pendingAuthorizations: Int get() = open.count { it.pendingAuthorization != null }
    val errors: Int get() = sessions.count { it.status == LiveStatus.ERRO }
    val winning: Int get() = open.count { it.isWinning }
    val averageMargin: Double get() = open.filter { it.ourLastBid != null }.map { it.currentMarginPct }.average().takeIf { !it.isNaN() } ?: 0.0

    /** Risco agregado: CAPTCHA/erros/piso pesam mais; margem baixa e autorizações pendentes somam. */
    val riskScore: Int
        get() {
            if (open.isEmpty()) return 0
            var score = 0
            score += captchas * 30
            score += errors * 35
            score += open.count { it.robotStatus == RobotStatus.PARADO_NO_PISO } * 20
            score += pendingAuthorizations * 10
            score += open.count { it.currentMarginPct < it.rule.minMarginPct && it.ourLastBid != null } * 15
            score += open.count { !it.isWinning && it.ourLastBid != null } * 8
            return score.coerceIn(0, 100)
        }
    val riskLevel: RiskLevel
        get() = when {
            riskScore >= 70 -> RiskLevel.CRITICO
            riskScore >= 40 -> RiskLevel.ALTO
            riskScore >= 15 -> RiskLevel.MEDIO
            else -> RiskLevel.BAIXO
        }

    val alerts: List<WarRoomAlert>
        get() = buildList {
            sessions.filter { it.captchaPending }.forEach { add(WarRoomAlert(it.id, "CAPTCHA aguardando — sessão pausada.", "${it.portal.shortName} · ${it.tenderNumber}", Tone.DANGER)) }
            sessions.filter { it.status == LiveStatus.ERRO }.forEach { add(WarRoomAlert(it.id, "Erro crítico — robô interrompido", "${it.portal.shortName} · ${it.tenderNumber}: ${it.lastError ?: ""}", Tone.DANGER)) }
            sessions.filter { it.pendingAuthorization != null }.forEach { add(WarRoomAlert(it.id, "Autorização de lance pendente", "${it.portal.shortName} · ${it.tenderNumber}: ${Formatters.brl(it.pendingAuthorization?.proposedValue)}", Tone.WARNING)) }
            sessions.filter { it.robotStatus == RobotStatus.PARADO_NO_PISO }.forEach { add(WarRoomAlert(it.id, "Robô parado no piso", "${it.portal.shortName} · ${it.tenderNumber}: piso ${Formatters.brl(it.rule.floorPrice)}", Tone.WARNING)) }
            open.filter { it.ourLastBid != null && it.currentMarginPct < it.rule.minMarginPct && it.status != LiveStatus.ERRO }.forEach {
                add(WarRoomAlert(it.id, "Margem abaixo da mínima", "${it.portal.shortName} · ${it.tenderNumber}: ${Formatters.percent(it.currentMarginPct)} (mín. ${Formatters.percent(it.rule.minMarginPct)})", Tone.WARNING))
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WarRoomViewModel @Inject constructor(
    private val manager: LiveSessionManager,
    private val auth: AuthRepository,
    messages: MessageRepository,
) : ViewModel() {

    private val ready = MutableStateFlow(false)
    private val pausing = MutableStateFlow(false)
    private val lastPaused = MutableStateFlow<Int?>(null)

    private val unread = auth.session.flatMapLatest { s ->
        if (s == null) flowOf(0) else messages.observeUnreadCount(s.activeCompany.id).catch { emit(0) }
    }

    val state: StateFlow<WarRoomUiState> = combine(manager.sessions, unread, ready, pausing, lastPaused) { sessions, unread, ready, pausing, paused ->
        WarRoomUiState(loading = !ready, sessions = sessions, unreadMessages = unread, pausing = pausing, lastPausedCount = paused)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WarRoomUiState())

    init {
        viewModelScope.launch {
            auth.session.filterNotNull().map { it.activeCompany.id }.collect { companyId ->
                runCatching { manager.restoreOrSeed(companyId) }
                ready.value = true
            }
        }
    }

    fun pauseAll(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            pausing.value = true
            val count = runCatching { manager.pauseAllRobots("Parada de emergência — Sala de Guerra") }.getOrDefault(0)
            lastPaused.value = count
            pausing.value = false
            onDone(count)
        }
    }
}

@Composable
fun WarRoomScreen(vm: WarRoomViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var confirmPauseAll by remember { mutableStateOf(false) }

    LicitaScaffold(title = "Sala de Guerra", showBack = false) { padding ->
        when {
            state.loading && state.sessions.isEmpty() -> SkeletonList(Modifier.padding(padding))
            state.sessions.isEmpty() -> EmptyState(
                "Nenhuma operação em andamento", "Quando houver pregões ao vivo, o painel executivo mostra robôs, CAPTCHAs, autorizações, margem e risco em tempo real.",
                Modifier.padding(padding), icon = Icons.Outlined.Security,
                actionLabel = "Pregões ao Vivo", onAction = { navigator.navigateTop(Routes.LIVE) },
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("hero") {
                    GradientCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Operações simultâneas", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                                Text("${state.activeAuctions} pregão(ões) · ${state.winning} vencendo", style = MaterialTheme.typography.headlineSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold)
                            }
                            StatusBadge("Registro local", Tone.INFO)
                        }
                        Spacer(Modifier.height(12.dp))
                        val riskTone = state.riskLevel.tone()
                        LinearMeter("Risco agregado — ${state.riskLevel.label}", state.riskScore, tone = riskTone, valueText = "${state.riskScore}/100")
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(Formatters.percent(state.averageMargin), style = MaterialTheme.typography.titleLarge, color = if (state.averageMargin >= 10) LicitaColors.GreenBright else LicitaColors.Yellow, fontWeight = FontWeight.Bold)
                                Text("margem média", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${state.errors}", style = MaterialTheme.typography.titleLarge, color = if (state.errors > 0) LicitaColors.RedBright else LicitaColors.TextPrimary, fontWeight = FontWeight.Bold)
                                Text("erros críticos", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            }
                        }
                    }
                }
                item("counters1") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Pregões ativos", "${state.activeAuctions}", Icons.Outlined.Podcasts, Modifier.weight(1f), tone = Tone.INFO, onClick = { navigator.navigateTop(Routes.LIVE) })
                        StatCard("Robôs ativos", "${state.activeRobots}", Icons.Outlined.SmartToy, Modifier.weight(1f), tone = Tone.SUCCESS, highlight = state.activeRobots > 0, onClick = { navigator.navigateTop(Routes.ROBOT) })
                    }
                }
                item("counters2") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("CAPTCHAs", "${state.captchas}", Icons.Outlined.VerifiedUser, Modifier.weight(1f), tone = if (state.captchas > 0) Tone.DANGER else Tone.NEUTRAL, highlight = state.captchas > 0)
                        StatCard("Autorizações", "${state.pendingAuthorizations}", Icons.Outlined.Gavel, Modifier.weight(1f), tone = if (state.pendingAuthorizations > 0) Tone.WARNING else Tone.NEUTRAL, highlight = state.pendingAuthorizations > 0)
                    }
                }
                item("counters3") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Mensagens não lidas", "${state.unreadMessages}", Icons.Outlined.Mail, Modifier.weight(1f), tone = if (state.unreadMessages > 0) Tone.INFO else Tone.NEUTRAL, onClick = { navigator.navigateTop(Routes.MESSAGES) })
                        StatCard("Risco", state.riskLevel.label, Icons.Outlined.Security, Modifier.weight(1f), tone = state.riskLevel.tone())
                    }
                }

                item("emergency") {
                    Column {
                        DangerButton("PAUSAR TODOS OS ROBÔS", { confirmPauseAll = true }, icon = Icons.Outlined.PanTool, enabled = !state.pausing)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Interrompe todas as automações imediatamente. As sessões continuam abertas para acompanhamento e controle manual. Fica registrado na auditoria.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                        AnimatedVisibility(state.lastPausedCount != null) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                AlertBanner("Parada de emergência executada", "${state.lastPausedCount ?: 0} robô(s) pausado(s). Sessões mantidas abertas.", Tone.WARNING)
                            }
                        }
                    }
                }

                if (state.alerts.isNotEmpty()) {
                    item("alertsHeader") { SectionHeader("Alertas críticos (${state.alerts.size})") }
                    state.alerts.forEachIndexed { index, alert ->
                        item("alert-$index-${alert.sessionId}") {
                            AlertBanner(alert.title, alert.message, alert.tone, pulsing = alert.tone == Tone.DANGER, actionLabel = "Abrir", onAction = { navigator.navigate(Routes.liveSession(alert.sessionId)) })
                        }
                    }
                }

                item("gridHeader") { SectionHeader("Status de cada sessão", actionLabel = "Ver todas", onAction = { navigator.navigateTop(Routes.LIVE) }) }
                state.sessions.chunked(2).forEachIndexed { rowIndex, pair ->
                    item("row-$rowIndex-${pair.joinToString { it.id }}") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { s -> SessionTile(s, Modifier.weight(1f)) { navigator.navigate(Routes.liveSession(s.id)) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                item("foot") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmPauseAll) {
        ConfirmDialog(
            title = "Pausar todos os robôs?",
            message = "${state.activeRobots} robô(s) ativo(s) serão pausados agora em todas as sessões. Nenhuma sessão será encerrada; você poderá retomar cada robô individualmente.",
            onConfirm = {
                confirmPauseAll = false
                vm.pauseAll { count -> navigator.showMessage(if (count > 0) "$count robô(s) pausado(s). Sessões mantidas abertas." else "Nenhum robô estava ativo.") }
            },
            onDismiss = { confirmPauseAll = false },
            confirmLabel = "PAUSAR TODOS",
            tone = Tone.DANGER,
            icon = Icons.Outlined.PanTool,
        )
    }
}

@Composable
private fun SessionTile(session: LiveSession, modifier: Modifier, onClick: () -> Unit) {
    val accent by animateColorAsState(
        when {
            session.captchaPending || session.status == LiveStatus.ERRO -> LicitaColors.Red
            session.pendingAuthorization != null || session.robotStatus == RobotStatus.PARADO_NO_PISO -> LicitaColors.Yellow
            session.isWinning -> LicitaColors.Green
            session.status == LiveStatus.ENCERRADA -> LicitaColors.Outline
            else -> LicitaColors.Blue
        },
        tween(500), label = "tile",
    )
    LicitaCard(modifier, onClick = onClick, accent = accent, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortalChip(session.portal)
            Spacer(Modifier.weight(1f))
            Text(Formatters.countdown(session.remainingSeconds), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        Spacer(Modifier.height(6.dp))
        Text(session.tenderNumber, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1)
        Text(session.itemLabel, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (session.position == 0) "—" else "${session.position}º",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = if (session.isWinning) LicitaColors.GreenBright else if (session.position == 0) LicitaColors.TextMuted else LicitaColors.Yellow,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(Formatters.brlCompact(session.ourLastBid ?: session.rule.initialPrice), style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextPrimary)
                Text("margem ${Formatters.percent(session.currentMarginPct)}", style = MaterialTheme.typography.labelSmall, color = if (session.currentMarginPct >= session.rule.minMarginPct) LicitaColors.GreenBright else LicitaColors.RedBright)
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusBadge(session.status.label, session.status.tone(), pulsing = session.captchaPending)
        Spacer(Modifier.height(4.dp))
        StatusBadge(session.robotStatus.label, session.robotStatus.tone(), pulsing = session.robotStatus == RobotStatus.ATIVO)
    }
}
