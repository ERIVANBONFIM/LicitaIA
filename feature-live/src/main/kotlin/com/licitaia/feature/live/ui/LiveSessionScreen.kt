package com.licitaia.feature.live.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.BindingConfirmDialog
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.DangerButton
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.LinearMeter
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
import com.licitaia.domain.bidding.BidContext
import com.licitaia.domain.bidding.BidDecision
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidEventType
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveSessionUiState(
    val loading: Boolean = true,
    val session: LiveSession? = null,
    val events: List<BidEvent> = emptyList(),
    val role: UserRole? = null,
    val userName: String = "Operador",
    val busy: Boolean = false,
) {
    val canOperate get() = role?.let { Rbac.can(it, Permission.OPERAR_SESSOES) } ?: false
    val canChangeRules get() = role?.let { Rbac.can(it, Permission.ALTERAR_REGRAS) } ?: false
}

@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val manager: LiveSessionManager,
    auth: AuthRepository,
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()
    private val loaded = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<LiveSessionUiState> = combine(
        manager.observeSession(sessionId),
        manager.observeEvents(sessionId).catch { emit(emptyList()) },
        auth.session, loaded, busy,
    ) { session, events, authSession, loaded, busy ->
        LiveSessionUiState(
            loading = !loaded, session = session, events = events,
            role = authSession?.user?.role, userName = authSession?.user?.name ?: "Operador", busy = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSessionUiState())

    init {
        viewModelScope.launch {
            var tries = 0
            while (manager.sessions.value.none { it.id == sessionId } && tries < 15) { delay(100); tries++ }
            loaded.value = true
        }
    }

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        runCatching { block() }
        busy.value = false
    }

    fun start() = run { manager.startRobot(sessionId) }
    fun pause() = run { manager.pauseRobot(sessionId) }
    fun resume() = run { manager.resumeRobot(sessionId) }
    fun stop() = run { manager.stopRobot(sessionId) }
    fun takeOver() = run { manager.takeOverManually(sessionId) }
    fun confirmCaptcha() = run { manager.confirmCaptchaResolved(sessionId) }
    fun demoCaptcha() = run { manager.triggerDemoCaptcha(sessionId) }
    fun respond(authId: String, approved: Boolean) = run { manager.respondAuthorization(sessionId, authId, approved) }
    fun close(onDone: () -> Unit) = run { manager.closeSession(sessionId); onDone() }

    fun manualBid(value: Double, onResult: (BidResult) -> Unit) = run {
        onResult(manager.submitManualBid(sessionId, value))
    }
}

@Composable
fun LiveSessionScreen(vm: LiveSessionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val session = state.session

    LicitaScaffold(
        title = session?.let { "${it.portal.shortName} · ${it.tenderNumber}" } ?: "Sessão",
        showBack = true,
        subtitle = session?.itemLabel,
    ) { padding ->
        when {
            state.loading && session == null -> SkeletonList(Modifier.padding(padding), items = 3)
            session == null -> ErrorState("A sessão foi encerrada ou não existe mais.", Modifier.padding(padding), title = "Sessão não encontrada")
            else -> SessionContent(session, state, vm, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SessionContent(session: LiveSession, state: LiveSessionUiState, vm: LiveSessionViewModel, modifier: Modifier) {
    val navigator = LocalAppNavigator.current
    var confirmCaptcha by remember { mutableStateOf(false) }
    var confirmTakeOver by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var bidText by remember(session.id) { mutableStateOf("") }
    var bidToConfirm by remember { mutableStateOf<Double?>(null) }
    val sessionOpen = session.status != LiveStatus.ENCERRADA && session.status != LiveStatus.ERRO

    val suggestion = remember(session.rule, session.bestBid, session.ourLastBid, session.position, session.captchaPending) {
        val decision = BidRuleEngine.decide(
            BidContext(session.rule, session.ourLastBid, session.bestBid, session.position, session.captchaPending, System.currentTimeMillis()),
        )
        when (decision) {
            is BidDecision.Suggest -> decision.value
            is BidDecision.Place -> decision.value
            is BidDecision.RequestAuthorization -> decision.value
            else -> null
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("head") { HeaderCard(session) }

        if (session.captchaPending) {
            item("captcha") {
                LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Red) {
                    AlertBanner(
                        "CAPTCHA aguardando — sessão pausada.",
                        "O portal exibiu um CAPTCHA/MFA. O LicitaIA nunca resolve CAPTCHA: resolva você mesmo no portal e confirme aqui para retomar. Pendente ${session.captchaSince?.let { Formatters.relative(it) } ?: ""}.",
                        Tone.DANGER, pulsing = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Resolver no portal", { navigator.navigate(Routes.portalWeb(session.portal)) }, Modifier.weight(1f), icon = Icons.Outlined.Language, tone = Tone.DANGER)
                        SecondaryButton("Já resolvi — retomar", { confirmCaptcha = true }, Modifier.weight(1f), tone = Tone.SUCCESS)
                    }
                }
            }
        }

        session.pendingAuthorization?.let { auth ->
            item("auth") {
                LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Autorização pendente", style = MaterialTheme.typography.titleMedium, color = LicitaColors.Yellow, modifier = Modifier.weight(1f))
                        Text(Formatters.relative(auth.requestedAt), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(Formatters.brl(auth.proposedValue), style = MaterialTheme.typography.headlineMedium, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Margem ${Formatters.percent(session.rule.marginPct(auth.proposedValue))} · ${Formatters.brl(auth.proposedValue - session.rule.floorPrice)} acima do piso", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(auth.reason, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Aprovar lance", { vm.respond(auth.id, true) }, Modifier.weight(1f), enabled = state.canOperate && !state.busy, tone = Tone.SUCCESS)
                        SecondaryButton("Negar", { vm.respond(auth.id, false) }, Modifier.weight(1f), enabled = !state.busy, tone = Tone.DANGER)
                    }
                    if (!state.canOperate) Text("Aprovar exige a permissão “Operar sessões de pregão”.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                }
            }
        }

        if (session.status == LiveStatus.ERRO) {
            item("error") { AlertBanner("Erro crítico — robô interrompido", session.lastError ?: "Erro desconhecido", Tone.DANGER) }
        }
        if (session.status == LiveStatus.ENCERRADA) {
            item("closed") {
                AlertBanner(
                    if (session.isWinning) "Disputa encerrada — vencemos!" else "Disputa encerrada",
                    "Melhor lance final ${Formatters.brl(session.bestBid)}. Nosso último lance ${Formatters.brl(session.ourLastBid)}.",
                    if (session.isWinning) Tone.SUCCESS else Tone.NEUTRAL,
                )
            }
        }

        item("telemetry") { TelemetryCard(session) }

        item("robot") {
            LicitaCard(Modifier.fillMaxWidth().animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Robô de lances", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                    StatusBadge(session.robotStatus.label, session.robotStatus.tone(), pulsing = session.robotStatus == RobotStatus.ATIVO)
                }
                Spacer(Modifier.height(6.dp))
                InfoRow("Modo", session.rule.mode.label)
                InfoRow("Estratégia", session.rule.strategy.label)
                InfoRow("Intervalo mínimo", "${session.rule.minIntervalSeconds} s")
                InfoRow("Autorização", if (session.rule.mode == RobotMode.SUPERVISIONADO) "A cada lance" else "A menos de ${Formatters.percent(session.rule.authorizationThresholdPct, 0)} do piso ou margem < ${Formatters.percent(session.rule.minMarginPct, 0)}")
                if (suggestion != null && sessionOpen) {
                    Spacer(Modifier.height(6.dp))
                    AlertBanner(
                        if (session.rule.mode == RobotMode.MANUAL) "Sugestão do robô (modo manual)" else "Próximo lance calculado",
                        "${Formatters.brl(suggestion)} · margem ${Formatters.percent(session.rule.marginPct(suggestion))}",
                        Tone.INFO, actionLabel = "Usar", onAction = { bidText = String.format(java.util.Locale.US, "%.2f", suggestion) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (session.robotStatus) {
                        RobotStatus.INATIVO, RobotStatus.ENCERRADO, RobotStatus.CONTROLE_MANUAL, RobotStatus.PARADO_NO_PISO ->
                            PrimaryButton("Iniciar robô", { vm.start() }, Modifier.weight(1f), enabled = sessionOpen && state.canOperate && !state.busy, icon = Icons.Outlined.PlayArrow, tone = Tone.SUCCESS)
                        RobotStatus.PAUSADO ->
                            PrimaryButton("Retomar robô", { vm.resume() }, Modifier.weight(1f), enabled = sessionOpen && state.canOperate && !state.busy, icon = Icons.Outlined.PlayArrow, tone = Tone.SUCCESS)
                        RobotStatus.ATIVO, RobotStatus.AGUARDANDO_AUTORIZACAO, RobotStatus.BLOQUEADO_CAPTCHA ->
                            PrimaryButton("Pausar robô", { vm.pause() }, Modifier.weight(1f), enabled = !state.busy, icon = Icons.Outlined.Pause, tone = Tone.WARNING)
                        RobotStatus.ERRO ->
                            SecondaryButton("Bloqueado por erro", {}, Modifier.weight(1f), enabled = false, tone = Tone.DANGER)
                    }
                    SecondaryButton("Alterar estratégia", { navigator.navigate(Routes.robotConfig(session.id)) }, Modifier.weight(1f), enabled = sessionOpen, icon = Icons.Outlined.Tune)
                }
                if (session.robotStatus !in setOf(RobotStatus.INATIVO, RobotStatus.ENCERRADO, RobotStatus.ERRO) && sessionOpen) {
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Encerrar robô (sessão continua)", { confirmStop = true }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Stop, tone = Tone.DANGER)
                }
                if (!state.canOperate) {
                    Spacer(Modifier.height(6.dp))
                    Text("Seu perfil (${state.role?.label ?: "—"}) não pode iniciar robôs nem enviar lances; pausar é sempre permitido.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                }
            }
        }

        if (sessionOpen) {
            item("manual") {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Lance manual (simulado)", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text("Precisa ser inferior ao melhor lance (${Formatters.brl(session.bestBid)}) e nunca abaixo do piso (${Formatters.brl(BidRuleEngine.effectiveFloor(session.rule))}).", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    val parsed = parseMoney(bidText)
                    val validation = parsed?.let { BidRuleEngine.validateBid(session.rule, it, session.bestBid, session.captchaPending) }
                    OutlinedTextField(
                        value = bidText, onValueChange = { bidText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Valor do lance") }, prefix = { Text("R$ ", color = LicitaColors.TextMuted) },
                        isError = bidText.isNotBlank() && (parsed == null || validation != null),
                        supportingText = {
                            val msg = when {
                                bidText.isBlank() -> "Use vírgula para centavos (ex.: 150.000,00)."
                                parsed == null -> "Valor inválido."
                                validation != null -> validation
                                else -> "Margem ${Formatters.percent(session.rule.marginPct(parsed))}"
                            }
                            Text(msg, color = if (bidText.isNotBlank() && (parsed == null || validation != null)) LicitaColors.Red else LicitaColors.TextMuted)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LicitaColors.Blue, unfocusedBorderColor = LicitaColors.Outline, cursorColor = LicitaColors.Blue,
                            focusedTextColor = LicitaColors.TextPrimary, unfocusedTextColor = LicitaColors.TextPrimary,
                            focusedLabelColor = LicitaColors.BlueBright, unfocusedLabelColor = LicitaColors.TextSecondary,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(
                        "Envio integrado indisponível", { parsed?.let { bidToConfirm = it } }, Modifier.fillMaxWidth(),
                        enabled = false, icon = Icons.Outlined.Send,
                    )
                }
            }
            item("takeover") {
                Column {
                    DangerButton("PARAR E ASSUMIR", { confirmTakeOver = true }, icon = Icons.Outlined.FrontHand, enabled = session.robotStatus != RobotStatus.CONTROLE_MANUAL)
                    Spacer(Modifier.height(6.dp))
                    Text("Interrompe imediatamente toda automação desta sessão e deixa o controle 100% com você. As demais sessões não são afetadas.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                }
            }
            item("tools") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Abrir portal", { navigator.navigate(Routes.portalWeb(session.portal)) }, Modifier.weight(1f), icon = Icons.Outlined.Language, tone = Tone.NEUTRAL)
                    Text("CAPTCHA/MFA: resolva no portal oficial.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item("close") {
            SecondaryButton(if (sessionOpen) "Encerrar sessão" else "Remover sessão", { confirmClose = true }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Close, tone = Tone.DANGER)
        }

        item("logHeader") { SectionHeader("Log da sessão (${state.events.size})") }
        if (state.events.isEmpty()) {
            item("logEmpty") { Text("Nenhum evento registrado ainda.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(8.dp)) }
        }
        itemsIndexed(state.events, key = { index, e -> "ev-$index-${e.timestamp}" }) { _, event -> EventRow(event) }
        item("foot") { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmCaptcha) {
        ConfirmDialog(
            title = "Confirmar resolução do CAPTCHA",
            message = "Confirme apenas se você já resolveu o CAPTCHA/MFA diretamente no portal. A automação desta sessão será retomada somente após esta confirmação.",
            onConfirm = { confirmCaptcha = false; vm.confirmCaptcha() },
            onDismiss = { confirmCaptcha = false },
            confirmLabel = "Já resolvi — retomar",
            tone = Tone.SUCCESS,
        )
    }
    if (confirmTakeOver) {
        ConfirmDialog(
            title = "Parar e assumir?",
            message = "O robô desta sessão será interrompido agora e nenhum lance automático será enviado. Você passa a operar manualmente.",
            onConfirm = { confirmTakeOver = false; vm.takeOver() },
            onDismiss = { confirmTakeOver = false },
            confirmLabel = "PARAR E ASSUMIR",
            tone = Tone.DANGER,
        )
    }
    if (confirmStop) {
        ConfirmDialog(
            title = "Encerrar robô?",
            message = "O robô deixa de atuar nesta sessão. A sessão continua aberta para acompanhamento e lances manuais.",
            onConfirm = { confirmStop = false; vm.stop() },
            onDismiss = { confirmStop = false },
            confirmLabel = "Encerrar robô",
            tone = Tone.DANGER,
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            title = if (sessionOpen) "Encerrar sessão?" else "Remover sessão?",
            message = if (sessionOpen) "A sessão deixará de ser acompanhada e o robô será encerrado. Esta ação fica registrada no log." else "A sessão encerrada será removida da lista.",
            onConfirm = { confirmClose = false; vm.close { navigator.back() } },
            onDismiss = { confirmClose = false },
            confirmLabel = "Confirmar",
            tone = Tone.DANGER,
        )
    }
    bidToConfirm?.let { value ->
        BindingConfirmDialog(
            title = "Confirmar lance manual",
            details = listOf(
                "Portal" to session.portal.displayName,
                "Pregão" to session.tenderNumber,
                "Item" to session.itemLabel,
                "Valor do lance" to Formatters.brl(value),
                "Melhor lance atual" to Formatters.brl(session.bestBid),
                "Piso" to Formatters.brl(session.rule.floorPrice),
                "Margem" to Formatters.percent(session.rule.marginPct(value)),
            ),
            acknowledgeText = "Confirmo que revisei o valor e autorizo este lance em meu nome (${state.userName}).",
            onConfirm = {
                bidToConfirm = null
                vm.manualBid(value) { result ->
                    when (result) {
                        is BidResult.Accepted -> { bidText = ""; navigator.showMessage("Lance de ${Formatters.brl(result.value)} registrado (simulação) — ${result.position}º lugar.") }
                        is BidResult.Rejected -> navigator.showMessage("Lance recusado: ${result.reason}")
                    }
                }
            },
            onDismiss = { bidToConfirm = null },
            confirmLabel = "Enviar lance",
        )
    }
}

@Composable
private fun HeaderCard(session: LiveSession) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortalChip(session.portal)
            Spacer(Modifier.width(8.dp))
            StatusBadge(session.status.label, session.status.tone(), pulsing = session.status == LiveStatus.EM_DISPUTA)
            Spacer(Modifier.weight(1f))
            StatusBadge("Registro local", Tone.INFO)
        }
        Spacer(Modifier.height(8.dp))
        Text(session.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
        Text(session.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
    }
}

@Composable
private fun TelemetryCard(session: LiveSession) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PositionBadge(session.position, session.competitors)
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatters.countdown(session.remainingSeconds), style = MaterialTheme.typography.headlineSmall, color = if ((session.remainingSeconds ?: 999) < 120) LicitaColors.Yellow else LicitaColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text("tempo restante", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Telemetry("Nosso último lance", Formatters.brl(session.ourLastBid), LicitaColors.TextPrimary)
            Telemetry("Melhor lance", Formatters.brl(session.bestBid), if (session.isWinning) LicitaColors.GreenBright else LicitaColors.BlueBright)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Telemetry("Piso", Formatters.brl(session.rule.floorPrice), LicitaColors.Yellow)
            Telemetry("Custo", Formatters.brl(session.rule.costPrice), LicitaColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        val margin = session.currentMarginPct
        LinearMeter(
            "Margem atual (mín. ${Formatters.percent(session.rule.minMarginPct, 0)})",
            margin.coerceIn(0.0, 100.0).toInt(),
            tone = if (margin >= session.rule.minMarginPct) Tone.SUCCESS else if (margin > 0) Tone.WARNING else Tone.DANGER,
            valueText = Formatters.percent(margin),
        )
        Spacer(Modifier.height(10.dp))
        MarginToFloorBar(session)
        Spacer(Modifier.height(8.dp))
        Text("${session.competitors} concorrente(s) · ${session.unreadMessages} mensagem(ns) do pregoeiro nesta sessão", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
    }
}

@Composable
private fun EventRow(event: BidEvent) {
    val color = eventColor(event.type)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(event.type.label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.weight(1f))
                Text(Formatters.time(event.timestamp), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            Text(event.description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            Row {
                Text(event.actor, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.weight(1f))
                event.value?.let { Text(Formatters.brl(it), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextPrimary) }
            }
        }
    }
}

fun eventColor(type: BidEventType): Color = when (type) {
    BidEventType.OUR_BID, BidEventType.ROBOT_STARTED, BidEventType.ROBOT_RESUMED, BidEventType.AUTH_GRANTED, BidEventType.CAPTCHA_RESOLVED -> LicitaColors.GreenBright
    BidEventType.COMPETITOR_BID, BidEventType.SESSION_OPENED, BidEventType.MESSAGE -> LicitaColors.BlueBright
    BidEventType.BID_BLOCKED, BidEventType.CAPTCHA_DETECTED, BidEventType.ERROR -> LicitaColors.RedBright
    BidEventType.ROBOT_PAUSED, BidEventType.AUTH_REQUESTED, BidEventType.FLOOR_REACHED, BidEventType.POSITION_CHANGED, BidEventType.AUTH_DENIED -> LicitaColors.Yellow
    BidEventType.MANUAL_TAKEOVER, BidEventType.RULE_CHANGED -> LicitaColors.Purple
    BidEventType.ROBOT_STOPPED, BidEventType.SESSION_CLOSED -> LicitaColors.TextMuted
}

fun parseMoney(text: String): Double? {
    val cleaned = text.trim().removePrefix("R$").trim()
    if (cleaned.isBlank()) return null
    val normalized = if (cleaned.contains(',')) cleaned.replace(".", "").replace(',', '.') else cleaned
    return normalized.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0 }
}
