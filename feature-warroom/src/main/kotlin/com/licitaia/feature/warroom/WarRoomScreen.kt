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
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatCard
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.AssistedBidding
import com.licitaia.domain.bidding.AssistedBidding.AlertKind
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.Tender
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import java.util.Optional
import javax.inject.Inject

data class WarRoomAlert(val sessionId: String, val title: String, val message: String, val tone: Tone)

data class WarRoomUiState(
    val loading: Boolean = true,
    val demo: Boolean = false,
    val sessions: List<LiveSession> = emptyList(),
    val unreadMessages: Int = 0,
    /** Mensagens não lidas mais recentes (até 3). */
    val latestUnread: List<AuctioneerMessage> = emptyList(),
    val tenders: List<Tender> = emptyList(),
    val myTenders: List<PortalMyTender> = emptyList(),
    val toggling: Boolean = false,
    val alertsMuted: Boolean = false,
    val lastAffected: Int? = null,
) {
    val open: List<LiveSession> get() = sessions.filter { it.isOpen }
    val inDispute: Int get() = open.count { it.status == LiveStatus.EM_DISPUTA }
    val waiting: Int get() = open.count { it.status == LiveStatus.AGUARDANDO }
    val timers: Int get() = open.count { it.timerRunning }
    val errors: Int get() = sessions.count { it.status == LiveStatus.ERRO }
    val winning: Int get() = open.count { it.isWinning }
    val averageMargin: Double get() = open.filter { it.ourLastBid != null }.map { it.currentMarginPct }.average().takeIf { !it.isNaN() } ?: 0.0
    private val alertsBySession: List<Pair<LiveSession, Set<AlertKind>>> get() = open.map { it to AssistedBidding.activeAlerts(it) }
    val alertCount: Int get() = alertsBySession.sumOf { it.second.size }
    val nearFloor: Int get() = alertsBySession.count { AlertKind.PROXIMO_DO_PISO in it.second }
    val closingSoon: Int get() = alertsBySession.count { AlertKind.TEMPO_CRITICO in it.second }

    fun upcoming(now: Long): List<UpcomingSession> = WarRoomAgenda.upcomingSessions(tenders, myTenders, now)
    fun deadlines(now: Long): List<UpcomingDeadline> = WarRoomAgenda.upcomingDeadlines(tenders, now)

    /** Risco agregado: piso/fechamento pesam mais; margem baixa e posição somam. */
    val riskScore: Int
        get() {
            if (open.isEmpty()) return 0
            var score = 0
            score += errors * 35
            score += nearFloor * 25
            score += closingSoon * 20
            score += alertsBySession.count { AlertKind.MARGEM_ABAIXO_MINIMA in it.second } * 15
            score += alertsBySession.count { AlertKind.PERDEMOS_POSICAO in it.second } * 8
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
            sessions.filter { it.status == LiveStatus.ERRO }.forEach { add(WarRoomAlert(it.id, "Erro na sessão", "${it.portal.shortName} · ${it.tenderNumber}: ${it.lastError ?: ""}", Tone.DANGER)) }
            alertsBySession.forEach { (s, kinds) ->
                val label = "${s.portal.shortName} · ${s.tenderNumber}"
                kinds.forEach { kind ->
                    when (kind) {
                        AlertKind.PROXIMO_DO_PISO -> add(WarRoomAlert(s.id, kind.label, "$label: ${Formatters.brl(s.ourLastBid)} a ${Formatters.brl(s.ourLastBid?.let { AssistedBidding.distanceToFloor(s.rule, it) })} do piso ${Formatters.brl(BidRuleEngine.effectiveFloor(s.rule))}", Tone.DANGER))
                        AlertKind.TEMPO_CRITICO -> add(WarRoomAlert(s.id, kind.label, "$label: ${Formatters.countdown(s.remainingSeconds)} no cronômetro", Tone.DANGER))
                        AlertKind.MARGEM_ABAIXO_MINIMA -> add(WarRoomAlert(s.id, kind.label, "$label: ${Formatters.percent(s.currentMarginPct)} (mín. ${Formatters.percent(s.rule.minMarginPct)})", Tone.WARNING))
                        AlertKind.PERDEMOS_POSICAO -> add(WarRoomAlert(s.id, kind.label, "$label: ${s.position}º · melhor lance ${Formatters.brl(s.bestBid)}", Tone.WARNING))
                    }
                }
            }
        }
}

private data class CompanyData(
    val demo: Boolean = false,
    val unread: List<AuctioneerMessage> = emptyList(),
    val tenders: List<Tender> = emptyList(),
    val myTenders: List<PortalMyTender> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WarRoomViewModel @Inject constructor(
    private val manager: LiveSessionManager,
    private val auth: AuthRepository,
    messages: MessageRepository,
    tenders: TenderRepository,
    portalRobot: Optional<PortalRobotRepository>,
) : ViewModel() {

    private val ready = MutableStateFlow(false)
    private val toggling = MutableStateFlow(false)
    private val lastAffected = MutableStateFlow<Int?>(null)

    private val companyData = auth.session.flatMapLatest { s ->
        if (s == null) return@flatMapLatest flowOf(CompanyData())
        val id = s.activeCompany.id
        val myTenders = portalRobot.orElse(null)?.observeMyTenders(id)?.catch { emit(emptyList()) } ?: flowOf(emptyList())
        combine(
            messages.observeMessages(id).map { list -> list.filter { !it.read }.sortedByDescending { it.receivedAt } }.catch { emit(emptyList()) },
            tenders.observeTenders(id).catch { emit(emptyList()) },
            myTenders,
        ) { unread, t, my -> CompanyData(s.user.demo || s.activeCompany.demo, unread, t, my) }
    }

    val state: StateFlow<WarRoomUiState> = combine(manager.sessions, companyData, ready, toggling, manager.alertsMuted, lastAffected) { values ->
        @Suppress("UNCHECKED_CAST")
        val data = values[1] as CompanyData
        WarRoomUiState(
            loading = !(values[2] as Boolean), demo = data.demo, sessions = values[0] as List<LiveSession>,
            unreadMessages = data.unread.size, latestUnread = data.unread.take(3), tenders = data.tenders, myTenders = data.myTenders,
            toggling = values[3] as Boolean, alertsMuted = values[4] as Boolean, lastAffected = values[5] as Int?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WarRoomUiState())

    init {
        viewModelScope.launch {
            auth.session.filterNotNull().map { it.activeCompany.id }.collect { companyId ->
                runCatching { manager.restoreOrSeed(companyId) }
                ready.value = true
            }
        }
    }

    fun setMuted(muted: Boolean, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            toggling.value = true
            val count = runCatching { manager.setAlertsMuted(muted) }.getOrDefault(0)
            lastAffected.value = if (muted) count else null
            toggling.value = false
            onDone(count)
        }
    }
}

/** Relógio de 1 s para as contagens regressivas. */
@Composable
private fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1_000)
        value = System.currentTimeMillis()
    }
}

@Composable
fun WarRoomScreen(vm: WarRoomViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var confirmMute by remember { mutableStateOf(false) }
    val now by rememberNow()
    val upcoming = state.upcoming(now)
    val deadlines = state.deadlines(now)

    LicitaScaffold(title = "Sala de Guerra", showBack = false, subtitle = if (state.demo) "Demonstração" else null) { padding ->
        when {
            state.loading && state.sessions.isEmpty() -> SkeletonList(Modifier.padding(padding))
            state.sessions.isEmpty() && upcoming.isEmpty() && deadlines.isEmpty() && state.unreadMessages == 0 -> EmptyState(
                "Nada para os próximos 7 dias",
                "Aqui aparecem as sessões das suas licitações (interesse, participação e minhas licitações) com contagem regressiva, " +
                    "os pregões em andamento, alertas de piso, mensagens do pregoeiro e prazos. Marque “Tenho interesse” nas oportunidades do Radar para começar.",
                Modifier.padding(padding), icon = Icons.Outlined.Security,
                actionLabel = "Abrir Radar", onAction = { navigator.navigateTop(Routes.RADAR) },
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.sessions.isNotEmpty()) {
                    item("hero") {
                        GradientCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Pregões em andamento", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                                    Text("${state.open.size} pregão(ões) · ${state.winning} vencendo", style = MaterialTheme.typography.headlineSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold)
                                }
                                StatusBadge(if (state.alertsMuted) "Alertas silenciados" else "Alertas ativos", if (state.alertsMuted) Tone.WARNING else Tone.SUCCESS)
                            }
                            Spacer(Modifier.height(12.dp))
                            val riskTone = state.riskLevel.tone()
                            LinearMeter("Risco agregado — ${state.riskLevel.label}", state.riskScore, tone = riskTone, valueText = "${state.riskScore}/100")
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(Formatters.percent(state.averageMargin), style = MaterialTheme.typography.titleLarge, color = if (state.averageMargin >= 10) LicitaColors.GreenBright else LicitaColors.Yellow, fontWeight = FontWeight.Bold)
                                    Text("margem média (sessões com lance)", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${state.alertCount}", style = MaterialTheme.typography.titleLarge, color = if (state.alertCount > 0) LicitaColors.Yellow else LicitaColors.TextPrimary, fontWeight = FontWeight.Bold)
                                    Text("alertas ativos", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                                }
                            }
                        }
                    }
                    item("counters1") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Em disputa", "${state.inDispute}", Icons.Outlined.Podcasts, Modifier.weight(1f), tone = Tone.INFO, onClick = { navigator.navigateTop(Routes.LIVE) })
                            StatCard("Vencendo", "${state.winning}", Icons.Outlined.EmojiEvents, Modifier.weight(1f), tone = Tone.SUCCESS, highlight = state.winning > 0)
                        }
                    }
                    item("counters2") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Perto do piso", "${state.nearFloor}", Icons.Outlined.WarningAmber, Modifier.weight(1f), tone = if (state.nearFloor > 0) Tone.DANGER else Tone.NEUTRAL, highlight = state.nearFloor > 0)
                            StatCard("Cronômetros", "${state.timers}" + if (state.closingSoon > 0) " (${state.closingSoon} < 60 s)" else "", Icons.Outlined.Timer, Modifier.weight(1f), tone = if (state.closingSoon > 0) Tone.DANGER else Tone.NEUTRAL, highlight = state.closingSoon > 0)
                        }
                    }
                }
                item("counters3") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Mensagens não lidas", "${state.unreadMessages}", Icons.Outlined.Mail, Modifier.weight(1f), tone = if (state.unreadMessages > 0) Tone.INFO else Tone.NEUTRAL, onClick = { navigator.navigateTop(Routes.MESSAGES) })
                        StatCard("Sessões em 7 dias", "${upcoming.size}", Icons.Outlined.Event, Modifier.weight(1f), tone = if (upcoming.isNotEmpty()) Tone.INFO else Tone.NEUTRAL)
                    }
                }

                if (state.alerts.isNotEmpty()) {
                    item("alertsHeader") { SectionHeader("Alertas (${state.alerts.size})") }
                    state.alerts.forEachIndexed { index, alert ->
                        item("alert-$index-${alert.sessionId}") {
                            AlertBanner(alert.title, alert.message, alert.tone, pulsing = alert.tone == Tone.DANGER, actionLabel = "Abrir", onAction = { navigator.navigate(Routes.liveSession(alert.sessionId)) })
                        }
                    }
                }

                if (state.open.isNotEmpty()) {
                    item("mute") {
                        Column {
                            if (state.alertsMuted) {
                                SecondaryButton("Reativar alertas de todas", { vm.setMuted(false) { navigator.showMessage("Alertas reativados.") } }, Modifier.fillMaxWidth(), enabled = !state.toggling, icon = Icons.Outlined.NotificationsActive, tone = Tone.SUCCESS)
                            } else {
                                DangerButton("PAUSAR ALERTAS DE TODAS", { confirmMute = true }, icon = Icons.Outlined.NotificationsOff, enabled = !state.toggling)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Silencia as notificações de margem, piso e cronômetro de todas as sessões desta empresa. As sessões continuam abertas. Fica registrado na auditoria.",
                                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                            )
                            AnimatedVisibility(state.alertsMuted && state.lastAffected != null) {
                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    AlertBanner("Alertas silenciados", "${state.lastAffected ?: 0} sessão(ões) aberta(s) mantida(s). Os banners nesta tela continuam visíveis.", Tone.WARNING)
                                }
                            }
                        }
                    }
                }

                if (state.sessions.isNotEmpty()) {
                    item("gridHeader") { SectionHeader("Sessões em andamento", actionLabel = "Ver todas", onAction = { navigator.navigateTop(Routes.LIVE) }) }
                    state.sessions.chunked(2).forEachIndexed { rowIndex, pair ->
                        item("row-$rowIndex-${pair.joinToString { it.id }}") {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { s -> SessionTile(s, Modifier.weight(1f)) { navigator.navigate(Routes.liveSession(s.id)) } }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                item("upcomingHeader") { SectionHeader("Próximas sessões (7 dias)") }
                if (upcoming.isEmpty()) {
                    item("upcomingEmpty") {
                        LicitaCard(Modifier.fillMaxWidth()) {
                            Text("Nenhuma sessão das suas licitações nos próximos 7 dias.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton("Abrir Radar", { navigator.navigateTop(Routes.RADAR) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Radar, tone = Tone.NEUTRAL)
                        }
                    }
                } else {
                    upcoming.forEach { u ->
                        item("up-${u.key}") {
                            UpcomingCard(u, now) { u.tenderId?.let { navigator.navigate(Routes.tender(it)) } ?: navigator.navigate(Routes.portalWeb(u.portal)) }
                        }
                    }
                }

                if (deadlines.isNotEmpty()) {
                    item("deadlinesHeader") { SectionHeader("Prazos (7 dias)") }
                    item("deadlines") {
                        LicitaCard(Modifier.fillMaxWidth()) {
                            deadlines.take(12).forEachIndexed { i, d ->
                                if (i > 0) Spacer(Modifier.height(8.dp))
                                DeadlineRow(d, now) { d.tenderId?.let { navigator.navigate(Routes.tender(it)) } }
                            }
                            if (deadlines.any { it.estimated }) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Esclarecimento e impugnação: até 3 dias úteis antes da abertura (art. 164 da Lei 14.133/2021), calculado sem feriados. Confira o edital.",
                                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                                )
                            }
                        }
                    }
                }

                if (state.latestUnread.isNotEmpty()) {
                    item("msgHeader") { SectionHeader("Mensagens do pregoeiro não lidas", actionLabel = "Ver todas", onAction = { navigator.navigateTop(Routes.MESSAGES) }) }
                    state.latestUnread.forEach { m ->
                        item("msg-${m.id}") {
                            LicitaCard(Modifier.fillMaxWidth(), onClick = { navigator.navigate(Routes.message(m.id)) }, accent = if (m.urgent) LicitaColors.Red else LicitaColors.Blue, contentPadding = PaddingValues(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PortalChip(m.portal)
                                    Text(m.tenderNumber, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                                    if (m.urgent) StatusBadge("URGENTE", Tone.DANGER)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(m.body, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${m.sender} · ${Formatters.relative(m.receivedAt, now)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            }
                        }
                    }
                }
                item("foot") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmMute) {
        ConfirmDialog(
            title = "Pausar alertas de todas as sessões?",
            message = "${state.open.size} sessão(ões) aberta(s) deixam de notificar margem, piso e cronômetro até você reativar. Nenhuma sessão é encerrada e nada muda no portal.",
            onConfirm = {
                confirmMute = false
                vm.setMuted(true) { count -> navigator.showMessage("Alertas silenciados em $count sessão(ões). Sessões mantidas abertas.") }
            },
            onDismiss = { confirmMute = false },
            confirmLabel = "PAUSAR ALERTAS",
            tone = Tone.DANGER,
            icon = Icons.Outlined.NotificationsOff,
        )
    }
}

@Composable
private fun UpcomingCard(u: UpcomingSession, now: Long, onClick: () -> Unit) {
    val soon = u.sessionAt - now < WarRoomAgenda.DAY_MS
    LicitaCard(Modifier.fillMaxWidth(), onClick = onClick, accent = if (soon) LicitaColors.Yellow else null, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PortalChip(u.portal)
            Text(u.number, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                WarRoomAgenda.countdown(u.sessionAt, now), style = MaterialTheme.typography.titleSmall,
                color = if (soon) LicitaColors.Yellow else LicitaColors.TextPrimary, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(u.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${u.agency} · sessão ${Formatters.dateTime(u.sessionAt)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.licitaia.core.ui.components.OfficialSituationBadge(u.situation)
            StatusBadge(u.statusLabel, if (u.participating) Tone.SUCCESS else Tone.NEUTRAL)
            if (u.participating) StatusBadge("Participando", Tone.INFO)
        }
    }
}

@Composable
private fun DeadlineRow(d: UpcomingDeadline, now: Long, onClick: () -> Unit) {
    val soon = d.at - now < WarRoomAgenda.DAY_MS
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${d.kind.label}${if (d.estimated) " (estimado)" else ""}", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
            Text("${d.portal.shortName} ${d.number} · ${Formatters.dateTime(d.at)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SecondaryButton(WarRoomAgenda.countdown(d.at, now), onClick, tone = if (soon) Tone.DANGER else Tone.NEUTRAL)
    }
}

@Composable
private fun SessionTile(session: LiveSession, modifier: Modifier, onClick: () -> Unit) {
    val alerts = AssistedBidding.activeAlerts(session)
    val accent by animateColorAsState(
        when {
            session.status == LiveStatus.ERRO -> LicitaColors.Red
            AlertKind.PROXIMO_DO_PISO in alerts || AlertKind.TEMPO_CRITICO in alerts -> LicitaColors.Red
            alerts.isNotEmpty() -> LicitaColors.Yellow
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
            Text(Formatters.countdown(session.remainingSeconds), style = MaterialTheme.typography.labelSmall, color = if (session.timerRunning) LicitaColors.TextPrimary else LicitaColors.TextMuted)
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
        StatusBadge(session.status.label, session.status.tone(), pulsing = session.status == LiveStatus.EM_DISPUTA)
        if (alerts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            StatusBadge("${alerts.size} alerta(s)", if (AlertKind.PROXIMO_DO_PISO in alerts || AlertKind.TEMPO_CRITICO in alerts) Tone.DANGER else Tone.WARNING)
        }
    }
}
