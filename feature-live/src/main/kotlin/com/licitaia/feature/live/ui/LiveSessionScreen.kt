package com.licitaia.feature.live.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
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
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.AssistedBidding
import com.licitaia.domain.bidding.BidDecision
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidEventType
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
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
    val alertsMuted: Boolean = false,
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
        auth.session, loaded, busy, manager.alertsMuted,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val authSession = values[2] as com.licitaia.domain.model.AuthSession?
        LiveSessionUiState(
            loading = !(values[3] as Boolean), session = values[0] as LiveSession?, events = values[1] as List<BidEvent>,
            role = authSession?.user?.role, userName = authSession?.user?.name ?: "Operador", busy = values[4] as Boolean,
            alertsMuted = values[5] as Boolean,
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

    fun startDispute() = run { manager.startDispute(sessionId) }
    fun recordOurBid(value: Double, onResult: (BidResult) -> Unit) = run { onResult(manager.recordOurBid(sessionId, value)) }
    fun recordCompetitorBid(value: Double, onResult: (BidResult) -> Unit) = run { onResult(manager.recordCompetitorBid(sessionId, value)) }
    fun setPosition(position: Int) = run { manager.setPosition(sessionId, position) }
    fun startTimer(seconds: Int) = run { manager.startTimer(sessionId, seconds) }
    fun stopTimer() = run { manager.stopTimer(sessionId) }
    fun finish(won: Boolean, finalValue: Double, onDone: () -> Unit) = run { manager.finishSession(sessionId, won, finalValue); onDone() }
    fun close(onDone: () -> Unit) = run { manager.closeSession(sessionId); onDone() }
}

@Composable
fun LiveSessionScreen(vm: LiveSessionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
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

private enum class Dialog { OUR_BID, COMPETITOR_BID, TIMER, POSITION, FINISH, CLOSE }

@Composable
private fun SessionContent(session: LiveSession, state: LiveSessionUiState, vm: LiveSessionViewModel, modifier: Modifier) {
    val navigator = LocalAppNavigator.current
    val clipboard = LocalClipboardManager.current
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var prefill by remember { mutableStateOf("") }
    val sessionOpen = session.isOpen
    val canAct = sessionOpen && state.canOperate && !state.busy

    val suggestion = remember(session.rule, session.bestBid, session.ourLastBid, session.position, session.captchaPending) {
        AssistedBidding.suggest(session, System.currentTimeMillis())
    }
    val alerts = remember(session) { AssistedBidding.activeAlerts(session) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("head") { HeaderCard(session) }

        if (session.status == LiveStatus.ERRO) {
            item("error") { AlertBanner("Erro na sessão", session.lastError ?: "Erro desconhecido", Tone.DANGER) }
        }
        if (session.status == LiveStatus.ENCERRADA) {
            item("closed") {
                AlertBanner(
                    if (session.isWinning) "Disputa encerrada — vencemos!" else "Disputa encerrada",
                    "Lance final ${Formatters.brl(session.bestBid)} · nosso último lance ${Formatters.brl(session.ourLastBid)} · margem ${Formatters.percent(session.currentMarginPct)}.",
                    if (session.isWinning) Tone.SUCCESS else Tone.NEUTRAL,
                    actionLabel = "Concorrência", onAction = { navigator.navigate(Routes.COMPETITION) },
                )
            }
        }
        if (sessionOpen && alerts.isNotEmpty()) {
            item("alerts") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    alerts.forEach { kind ->
                        val (msg, tone) = when (kind) {
                            AssistedBidding.AlertKind.MARGEM_ABAIXO_MINIMA -> "Margem ${Formatters.percent(session.currentMarginPct)} abaixo da mínima ${Formatters.percent(session.rule.minMarginPct)}." to Tone.WARNING
                            AssistedBidding.AlertKind.PROXIMO_DO_PISO -> "Nosso lance está a ${Formatters.brl(session.ourLastBid?.let { AssistedBidding.distanceToFloor(session.rule, it) })} do piso." to Tone.DANGER
                            AssistedBidding.AlertKind.TEMPO_CRITICO -> "Menos de ${AssistedBidding.TIMER_ALERT_SECONDS} s no cronômetro." to Tone.DANGER
                            AssistedBidding.AlertKind.PERDEMOS_POSICAO -> "Estamos em ${session.position}º. Melhor lance ${Formatters.brl(session.bestBid)}." to Tone.WARNING
                        }
                        AlertBanner(kind.label, msg + if (state.alertsMuted) " (alertas silenciados na Sala de Guerra)" else "", tone, pulsing = tone == Tone.DANGER)
                    }
                }
            }
        }

        item("telemetry") { TelemetryCard(session) }

        if (sessionOpen) {
            item("actions") {
                LicitaCard(Modifier.fillMaxWidth().animateContentSize()) {
                    Text("Registrar o que aconteceu no portal", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text("Com o robô de lance armado por você, os lances enviados por ele entram aqui sozinhos. No modo manual, você dá o lance no portal e registra aqui; abaixo do piso o registro é recusado.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    if (session.status == LiveStatus.AGUARDANDO) {
                        PrimaryButton("Disputa iniciou no portal", { vm.startDispute() }, Modifier.fillMaxWidth(), enabled = canAct, icon = Icons.Outlined.PlayArrow, tone = Tone.SUCCESS)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Registrar nosso lance", { prefill = ""; dialog = Dialog.OUR_BID }, Modifier.weight(1f), enabled = canAct, tone = Tone.SUCCESS)
                        SecondaryButton("Lance concorrente", { prefill = ""; dialog = Dialog.COMPETITOR_BID }, Modifier.weight(1f), enabled = canAct)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Marcar posição", { dialog = Dialog.POSITION }, Modifier.weight(1f), enabled = canAct, icon = Icons.Outlined.Flag, tone = Tone.NEUTRAL)
                        if (session.timerRunning) {
                            SecondaryButton("Pausar cronômetro", { vm.stopTimer() }, Modifier.weight(1f), enabled = !state.busy, icon = Icons.Outlined.Pause, tone = Tone.WARNING)
                        } else {
                            SecondaryButton("Cronômetro", { dialog = Dialog.TIMER }, Modifier.weight(1f), enabled = canAct, icon = Icons.Outlined.Timer, tone = Tone.NEUTRAL)
                        }
                    }
                    if (!state.canOperate) {
                        Spacer(Modifier.height(6.dp))
                        Text("Seu perfil (${state.role?.label ?: "—"}) só visualiza. Registrar lances exige “Operar sessões de pregão”.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    }
                }
            }

            item("suggestion") {
                LicitaCard(Modifier.fillMaxWidth().animateContentSize(), accent = LicitaColors.Blue) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sugerir próximo lance", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                        StatusBadge(session.rule.strategy.label, Tone.INFO)
                    }
                    Spacer(Modifier.height(6.dp))
                    when (val d = suggestion) {
                        is BidDecision.Suggest -> {
                            val gap = AssistedBidding.distanceToFloor(session.rule, d.value)
                            Text(Formatters.brl(d.value), style = MaterialTheme.typography.headlineMedium, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Bold)
                            Text(
                                "Margem resultante ${Formatters.percent(session.rule.marginPct(d.value))} · ${Formatters.brl(gap)} acima do piso",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (AssistedBidding.isMarginBelowMin(session.rule, d.value)) LicitaColors.Yellow else LicitaColors.TextSecondary,
                            )
                            Text(d.reason, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(
                                    "Copiar valor",
                                    { clipboard.setText(AnnotatedString(String.format(java.util.Locale("pt", "BR"), "%.2f", d.value))); navigator.showMessage("Valor copiado: ${Formatters.brl(d.value)}. Cole no portal e registre aqui depois.") },
                                    Modifier.weight(1f), icon = Icons.Outlined.ContentCopy,
                                )
                                SecondaryButton("Registrar este", { prefill = String.format(java.util.Locale.US, "%.2f", d.value); dialog = Dialog.OUR_BID }, Modifier.weight(1f), enabled = canAct, tone = Tone.SUCCESS)
                            }
                        }
                        is BidDecision.Wait -> Text(d.reason, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.GreenBright)
                        is BidDecision.StopAtFloor -> AlertBanner("Piso atingido", "${d.reason} Piso efetivo ${Formatters.brl(BidRuleEngine.effectiveFloor(session.rule))}. Alterar o piso exige confirmação e permissão.", Tone.WARNING)
                        is BidDecision.Blocked -> AlertBanner("Sem sugestão", d.reason, Tone.DANGER)
                        is BidDecision.Place, is BidDecision.RequestAuthorization -> Text("Sem sugestão disponível.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
                    }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Estratégia, piso e margem", { navigator.navigate(Routes.robotConfig(session.id)) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Tune, tone = Tone.NEUTRAL)
                }
            }

            item("portal") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Abrir portal", { navigator.navigate(Routes.portalWeb(session.portal)) }, Modifier.weight(1f), icon = Icons.Outlined.Language, tone = Tone.INFO)
                    PrimaryButton("Encerrar com resultado", { dialog = Dialog.FINISH }, Modifier.weight(1f), enabled = canAct, tone = Tone.WARNING)
                }
                Spacer(Modifier.height(6.dp))
                Text("Com sessão salva, o portal abre direto. CAPTCHA/MFA: sempre você, no portal.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            item("stop") {
                Column {
                    DangerButton("Encerrar acompanhamento", { dialog = Dialog.CLOSE }, icon = Icons.Outlined.Close, enabled = state.canOperate && !state.busy)
                    Spacer(Modifier.height(6.dp))
                    Text("Encerra esta sessão sem informar resultado. As demais sessões não são afetadas.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                }
            }
        } else {
            item("remove") {
                SecondaryButton("Remover da lista", { dialog = Dialog.CLOSE }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Close, tone = Tone.DANGER, enabled = state.canOperate && !state.busy)
            }
        }

        item("logHeader") { SectionHeader("Log da sessão (${state.events.size})") }
        if (state.events.isEmpty()) {
            item("logEmpty") { Text("Nenhum evento registrado ainda.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(8.dp)) }
        }
        itemsIndexed(state.events, key = { index, e -> "ev-$index-${e.timestamp}" }) { _, event -> EventRow(event) }
        item("foot") { Spacer(Modifier.height(24.dp)) }
    }

    when (dialog) {
        Dialog.OUR_BID -> BidDialog(
            title = "Registrar nosso lance",
            hint = "Valor que você JÁ enviou no portal. Piso efetivo ${Formatters.brl(BidRuleEngine.effectiveFloor(session.rule))}" + (session.bestBid?.let { " · melhor lance ${Formatters.brl(it)}" } ?: ""),
            initial = prefill,
            validate = { AssistedBidding.validateOurBid(session.rule, it, session.captchaPending) },
            preview = { v ->
                val best = session.bestBid
                "Margem ${Formatters.percent(session.rule.marginPct(v))} · ${Formatters.brl(AssistedBidding.distanceToFloor(session.rule, v))} acima do piso" +
                    (if (best != null && v >= best) " · não cobre o melhor lance (intermediário)" else "")
            },
            confirmLabel = "Registrar lance",
            onConfirm = { v ->
                dialog = null
                vm.recordOurBid(v) { r ->
                    when (r) {
                        is BidResult.Accepted -> navigator.showMessage("Lance de ${Formatters.brl(r.value)} registrado — ${r.position}º lugar.")
                        is BidResult.Rejected -> navigator.showMessage("Registro recusado: ${r.reason}")
                    }
                }
            },
            onDismiss = { dialog = null },
        )
        Dialog.COMPETITOR_BID -> BidDialog(
            title = "Registrar melhor lance concorrente",
            hint = "Melhor lance visível no portal que não é nosso.",
            initial = prefill,
            validate = { AssistedBidding.validateCompetitorBid(it) },
            preview = { v ->
                val ours = session.ourLastBid
                when {
                    ours == null -> "Ainda não registramos lance nosso."
                    v < ours -> "Esse lance nos tira da 1ª posição."
                    else -> "Nosso lance continua melhor."
                }
            },
            confirmLabel = "Registrar concorrente",
            onConfirm = { v ->
                dialog = null
                vm.recordCompetitorBid(v) { r ->
                    when (r) {
                        is BidResult.Accepted -> navigator.showMessage("Lance concorrente ${Formatters.brl(r.value)} registrado.")
                        is BidResult.Rejected -> navigator.showMessage(r.reason)
                    }
                }
            },
            onDismiss = { dialog = null },
        )
        Dialog.TIMER -> TimerDialog(onStart = { secs -> dialog = null; vm.startTimer(secs) }, onDismiss = { dialog = null })
        Dialog.POSITION -> PositionDialog(session.position, onPick = { p -> dialog = null; vm.setPosition(p) }, onDismiss = { dialog = null })
        Dialog.FINISH -> FinishDialog(
            session,
            onConfirm = { won, value -> dialog = null; vm.finish(won, value) { navigator.showMessage(if (won) "Resultado registrado: vencemos. Concorrência atualizada." else "Resultado registrado: perdemos. Concorrência atualizada.") } },
            onDismiss = { dialog = null },
        )
        Dialog.CLOSE -> ConfirmDialog(
            title = if (sessionOpen) "Encerrar acompanhamento?" else "Remover sessão?",
            message = if (sessionOpen) "A sessão deixa de ser acompanhada sem registrar resultado. Para gerar o histórico de concorrência, use “Encerrar com resultado”. Esta ação fica no log." else "A sessão encerrada será removida da lista (o log e a concorrência permanecem).",
            onConfirm = { dialog = null; vm.close { navigator.back() } },
            onDismiss = { dialog = null },
            confirmLabel = "Confirmar",
            tone = Tone.DANGER,
        )
        null -> Unit
    }
}

@Composable
private fun BidDialog(
    title: String,
    hint: String,
    initial: String,
    validate: (Double) -> String?,
    preview: (Double) -> String,
    confirmLabel: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = parseMoney(text)
    val error = parsed?.let(validate)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LicitaColors.SurfaceElevated,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Valor") }, prefix = { Text("R$ ", color = LicitaColors.TextMuted) },
                    isError = text.isNotBlank() && (parsed == null || error != null),
                    supportingText = {
                        val msg = when {
                            text.isBlank() -> "Use vírgula para centavos (ex.: 150.000,00)."
                            parsed == null -> "Valor inválido."
                            error != null -> error
                            else -> preview(parsed)
                        }
                        Text(msg, color = if (text.isNotBlank() && (parsed == null || error != null)) LicitaColors.Red else LicitaColors.TextMuted)
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null && error == null, colors = ButtonDefaults.buttonColors(containerColor = LicitaColors.Green)) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun TimerDialog(onStart: (Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    val total = (minutes.trim().toIntOrNull() ?: 0) * 60 + (seconds.trim().toIntOrNull() ?: 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LicitaColors.SurfaceElevated,
        title = { Text("Cronômetro da disputa") },
        text = {
            Column {
                Text("Informe o tempo mostrado no portal (ex.: tempo aleatório ou iminência de fechamento). Abaixo de ${AssistedBidding.TIMER_ALERT_SECONDS} s o app alerta.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2 to "2 min", 5 to "5 min", 10 to "10 min").forEach { (m, label) -> SelectChip(label, minutes == m.toString() && seconds.isBlank(), { minutes = m.toString(); seconds = "" }) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) }, modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("min") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = seconds, onValueChange = { seconds = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("s") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), colors = fieldColors(),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(total) }, enabled = total in 1..(24 * 3600), colors = ButtonDefaults.buttonColors(containerColor = LicitaColors.Blue)) { Text("Iniciar ${Formatters.countdown(total)}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PositionDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LicitaColors.SurfaceElevated,
        title = { Text("Nossa posição no portal") },
        text = {
            Column {
                Text("Informe a classificação atual mostrada pelo portal.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { p -> SelectChip("${p}º", current == p, { onPick(p) }, color = if (p == 1) LicitaColors.Green else LicitaColors.Yellow) }
                    SelectChip("5º+", current >= 5, { onPick(5) }, color = LicitaColors.Red)
                }
                Spacer(Modifier.height(8.dp))
                SelectChip("Sem lance", current == 0, { onPick(0) }, color = LicitaColors.TextMuted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun FinishDialog(session: LiveSession, onConfirm: (Boolean, Double) -> Unit, onDismiss: () -> Unit) {
    var won by remember { mutableStateOf(session.isWinning) }
    var text by remember { mutableStateOf((if (session.isWinning) session.ourLastBid else session.bestBid)?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "") }
    val parsed = parseMoney(text)
    val floorBlock = if (won && parsed != null) AssistedBidding.validateOurBid(session.rule, parsed) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LicitaColors.SurfaceElevated,
        title = { Text("Encerrar com resultado") },
        text = {
            Column {
                Text("Resultado publicado pelo portal. Gera um registro de concorrência" + (if (session.tenderId != null) " e atualiza a licitação (vencida/perdida)." else "."), style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("Vencemos", won, { won = true; text = session.ourLastBid?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: text }, Modifier.weight(1f), color = LicitaColors.Green)
                    SelectChip("Perdemos", !won, { won = false; text = session.bestBid?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: text }, Modifier.weight(1f), color = LicitaColors.Red)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (won) "Nosso valor final" else "Lance vencedor") }, prefix = { Text("R$ ", color = LicitaColors.TextMuted) },
                    isError = text.isNotBlank() && (parsed == null || floorBlock != null),
                    supportingText = {
                        val msg = when {
                            parsed == null -> "Informe o valor final."
                            floorBlock != null -> floorBlock
                            won -> "Margem final ${Formatters.percent(session.rule.marginPct(parsed))}"
                            else -> "Nosso último lance: ${Formatters.brl(session.ourLastBid)}"
                        }
                        Text(msg, color = if (text.isNotBlank() && (parsed == null || floorBlock != null)) LicitaColors.Red else LicitaColors.TextMuted)
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let { onConfirm(won, it) } }, enabled = parsed != null && floorBlock == null, colors = ButtonDefaults.buttonColors(containerColor = if (won) LicitaColors.Green else LicitaColors.Red)) { Text(if (won) "Registrar vitória" else "Registrar derrota") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun HeaderCard(session: LiveSession) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PortalChip(session.portal)
            Spacer(Modifier.width(8.dp))
            StatusBadge(session.status.label, session.status.tone(), pulsing = session.status == LiveStatus.EM_DISPUTA)
            Spacer(Modifier.weight(1f))
            StatusBadge("Acompanhamento", Tone.INFO)
        }
        Spacer(Modifier.height(8.dp))
        Text(session.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
        Text(session.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        if (session.tenderId != null) Text("Vinculada a uma licitação de interesse", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
    }
}

@Composable
private fun TelemetryCard(session: LiveSession) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PositionBadge(session.position, session.competitors)
            Column(horizontalAlignment = Alignment.End) {
                val secs = session.remainingSeconds
                Text(
                    Formatters.countdown(secs),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = when {
                        secs == null -> LicitaColors.TextMuted
                        session.timerRunning && secs <= AssistedBidding.TIMER_ALERT_SECONDS -> LicitaColors.RedBright
                        session.timerRunning -> LicitaColors.TextPrimary
                        else -> LicitaColors.TextSecondary
                    },
                )
                Text(when { secs == null -> "sem cronômetro"; session.timerRunning -> "em contagem"; else -> "cronômetro parado" }, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Telemetry("Nosso último lance", Formatters.brl(session.ourLastBid), LicitaColors.TextPrimary)
            Telemetry("Melhor lance", Formatters.brl(session.bestBid), if (session.isWinning) LicitaColors.GreenBright else LicitaColors.BlueBright)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Telemetry("Piso", Formatters.brl(BidRuleEngine.effectiveFloor(session.rule)), LicitaColors.Yellow)
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
        val ref = session.ourLastBid ?: session.rule.initialPrice
        InfoRow("Diferença para o piso", "${Formatters.brl(AssistedBidding.distanceToFloor(session.rule, ref))} (${Formatters.percent(AssistedBidding.distanceToFloorPct(session.rule, ref))})", valueColor = if (AssistedBidding.isNearFloor(session.rule, ref)) LicitaColors.RedBright else LicitaColors.TextPrimary)
        Text("${session.competitors} concorrente(s) estimado(s) · ${if (session.ourLastBid == null) "margem calculada sobre o preço inicial" else "margem sobre o nosso último lance"}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
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
