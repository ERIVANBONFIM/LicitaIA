package com.licitaia.feature.bidding.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
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
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SimulationBadge
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RobotUiState(
    val loading: Boolean = true,
    val sessions: List<LiveSession> = emptyList(),
    val role: UserRole? = null,
) {
    val canOperate: Boolean get() = role?.let { Rbac.can(it, Permission.OPERAR_SESSOES) } ?: false
    val canChangeRules: Boolean get() = role?.let { Rbac.can(it, Permission.ALTERAR_REGRAS) } ?: false
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RobotViewModel @Inject constructor(
    private val manager: LiveSessionManager,
    private val auth: AuthRepository,
) : ViewModel() {

    private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)

    val state: StateFlow<RobotUiState> = combine(manager.sessions, auth.session, ready) { sessions, session, ready ->
        RobotUiState(loading = !ready, sessions = sessions, role = session?.user?.role)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RobotUiState())

    init {
        viewModelScope.launch {
            auth.session.filterNotNull().map { it.activeCompany.id }.collect { companyId ->
                runCatching { manager.restoreOrSeed(companyId) }
                ready.value = true
            }
        }
    }

    fun start(id: String) = viewModelScope.launch { manager.startRobot(id) }
    fun pause(id: String) = viewModelScope.launch { manager.pauseRobot(id) }
    fun resume(id: String) = viewModelScope.launch { manager.resumeRobot(id) }
    fun stop(id: String) = viewModelScope.launch { manager.stopRobot(id) }
}

@Composable
fun RobotScreen(vm: RobotViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var stopTarget by remember { mutableStateOf<LiveSession?>(null) }

    LicitaScaffold(title = "Robô de Lances", showBack = false) { padding ->
        val active = state.sessions.filter { it.status != LiveStatus.ENCERRADA }
        when {
            state.loading && state.sessions.isEmpty() -> SkeletonList(Modifier.padding(padding))
            state.sessions.isEmpty() -> EmptyState(
                "Nenhuma sessão de pregão", "Abra uma sessão em Pregões ao Vivo para configurar e ativar o robô.",
                Modifier.padding(padding), icon = Icons.Outlined.SmartToy,
                actionLabel = "Ir para Pregões ao Vivo", onAction = { navigator.navigateTop(Routes.LIVE) },
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("header") {
                    LicitaCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${active.count { it.robotRunning }} robô(s) ativo(s)", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                                Text("${active.size} sessão(ões) aberta(s) · ${active.count { it.captchaPending }} CAPTCHA(s) pendente(s)", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                            }
                            SimulationBadge()
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Todo robô inicia inativo, nunca envia lance abaixo do piso e nunca opera com CAPTCHA pendente. Nenhum lance real é enviado nesta versão.",
                            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted,
                        )
                    }
                }
                if (!state.canOperate) {
                    item("rbac") {
                        AlertBanner(
                            "Perfil sem permissão para operar robôs",
                            "Seu perfil (${state.role?.label ?: "—"}) pode visualizar, mas iniciar/retomar robôs exige a permissão “Operar sessões de pregão”.",
                            Tone.WARNING,
                        )
                    }
                }
                item("sec") { SectionHeader("Robôs por sessão", actionLabel = "Estratégias", onAction = { navigator.navigate(Routes.STRATEGY) }) }
                items(state.sessions, key = { it.id }) { session ->
                    RobotCard(
                        session = session,
                        canOperate = state.canOperate,
                        canChangeRules = state.canChangeRules,
                        onStart = { vm.start(session.id) },
                        onPause = { vm.pause(session.id) },
                        onResume = { vm.resume(session.id) },
                        onStop = { stopTarget = session },
                        onConfig = { navigator.navigate(Routes.robotConfig(session.id)) },
                        onOpen = { navigator.navigate(Routes.liveSession(session.id)) },
                    )
                }
                item("foot") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    stopTarget?.let { s ->
        ConfirmDialog(
            title = "Encerrar robô?",
            message = "O robô de ${s.portal.shortName} · ${s.tenderNumber} deixará de atuar. A sessão continua aberta para controle manual.",
            onConfirm = { vm.stop(s.id); stopTarget = null },
            onDismiss = { stopTarget = null },
            confirmLabel = "Encerrar robô",
            tone = Tone.DANGER,
        )
    }
}

@Composable
private fun RobotCard(
    session: LiveSession,
    canOperate: Boolean,
    canChangeRules: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onConfig: () -> Unit,
    onOpen: () -> Unit,
) {
    val robot = session.robotStatus
    val accent = when (robot) {
        RobotStatus.ATIVO -> LicitaColors.Green
        RobotStatus.BLOQUEADO_CAPTCHA, RobotStatus.ERRO -> LicitaColors.Red
        RobotStatus.AGUARDANDO_AUTORIZACAO, RobotStatus.PARADO_NO_PISO -> LicitaColors.Yellow
        else -> null
    }
    LicitaCard(Modifier.fillMaxWidth().animateContentSize(), onClick = onOpen, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortalChip(session.portal)
            Spacer(Modifier.width(8.dp))
            Text(session.tenderNumber, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge(robot.label, robot.tone(), pulsing = robot == RobotStatus.ATIVO || robot == RobotStatus.BLOQUEADO_CAPTCHA)
        }
        Spacer(Modifier.height(6.dp))
        Text(session.itemLabel, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, fontWeight = FontWeight.Medium)
        Text(session.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        InfoRow("Modo", session.rule.mode.label)
        InfoRow("Estratégia", session.rule.strategy.label, valueColor = session.rule.strategy.color())
        InfoRow("Piso", Formatters.brl(session.rule.floorPrice), valueColor = LicitaColors.Yellow)
        InfoRow("Margem atual", Formatters.percent(session.currentMarginPct), valueColor = if (session.currentMarginPct >= session.rule.minMarginPct) LicitaColors.GreenBright else LicitaColors.RedBright)
        InfoRow("Posição", if (session.position == 0) "Sem lance" else "${session.position}º" + if (session.isWinning) " — vencendo" else "")

        AnimatedVisibility(session.captchaPending) {
            Column {
                Spacer(Modifier.height(8.dp))
                AlertBanner("CAPTCHA aguardando — sessão pausada.", "Resolva no portal e confirme na tela da sessão.", Tone.DANGER, pulsing = true)
            }
        }
        AnimatedVisibility(session.pendingAuthorization != null) {
            Column {
                Spacer(Modifier.height(8.dp))
                AlertBanner("Autorização pendente", "Lance de ${Formatters.brl(session.pendingAuthorization?.proposedValue)} aguarda sua decisão.", Tone.WARNING, actionLabel = "Decidir", onAction = onOpen)
            }
        }
        session.lastError?.takeIf { robot == RobotStatus.ERRO }?.let {
            Spacer(Modifier.height(8.dp))
            AlertBanner("Erro crítico — robô interrompido", it, Tone.DANGER)
        }

        Spacer(Modifier.height(12.dp))
        val sessionOpen = session.status != LiveStatus.ENCERRADA && session.status != LiveStatus.ERRO
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (robot) {
                RobotStatus.INATIVO, RobotStatus.ENCERRADO, RobotStatus.CONTROLE_MANUAL, RobotStatus.PARADO_NO_PISO ->
                    PrimaryButton("Iniciar", onStart, Modifier.weight(1f), enabled = canOperate && sessionOpen, icon = Icons.Outlined.PlayArrow, tone = Tone.SUCCESS)
                RobotStatus.PAUSADO ->
                    PrimaryButton("Retomar", onResume, Modifier.weight(1f), enabled = canOperate && sessionOpen, icon = Icons.Outlined.PlayArrow, tone = Tone.SUCCESS)
                RobotStatus.ATIVO, RobotStatus.AGUARDANDO_AUTORIZACAO, RobotStatus.BLOQUEADO_CAPTCHA ->
                    PrimaryButton("Pausar", onPause, Modifier.weight(1f), icon = Icons.Outlined.Pause, tone = Tone.WARNING)
                RobotStatus.ERRO ->
                    SecondaryButton("Bloqueado por erro", {}, Modifier.weight(1f), enabled = false, tone = Tone.DANGER)
            }
            SecondaryButton("Configurar", onConfig, Modifier.weight(1f), icon = Icons.Outlined.Tune, enabled = sessionOpen)
        }
        if (robot != RobotStatus.INATIVO && robot != RobotStatus.ENCERRADO && robot != RobotStatus.ERRO && sessionOpen) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Encerrar robô", onStop, Modifier.fillMaxWidth(), icon = Icons.Outlined.Stop, tone = Tone.DANGER)
        }
        if (!canChangeRules) {
            Spacer(Modifier.height(6.dp))
            Text("Alterar estratégia/piso exige perfil Diretoria, Financeiro (piso) ou Administrador.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
    }
}
