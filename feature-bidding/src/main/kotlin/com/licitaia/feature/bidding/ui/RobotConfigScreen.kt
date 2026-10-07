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
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.RobotMode
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleForm(
    /** Modo assistido: sempre MANUAL (o app só sugere; quem envia é o usuário no portal). */
    val mode: RobotMode = RobotMode.MANUAL,
    val strategy: BidStrategy = BidStrategy.CONSERVADORA,
    val initialPrice: String = "",
    val floorPrice: String = "",
    val costPrice: String = "",
    val reduction: String = "",
    val minMargin: String = "",
    val lossLimit: String = "0",
    val minInterval: String = "5",
    val authThreshold: String = "5",
) {
    fun toRule(): BidRule? {
        val initial = parseBrl(initialPrice) ?: return null
        val floor = parseBrl(floorPrice) ?: return null
        val cost = parseBrl(costPrice) ?: return null
        val red = parseBrl(reduction) ?: return null
        val margin = parseBrl(minMargin) ?: return null
        val loss = parseBrl(lossLimit) ?: return null
        val interval = minInterval.trim().toIntOrNull() ?: return null
        val threshold = parseBrl(authThreshold) ?: return null
        return BidRule(RobotMode.MANUAL, strategy, initial, floor, cost, red, margin, loss, interval, threshold, simulation = true)
    }

    /** Erros por campo (chave = nome do campo) + erros gerais. */
    fun fieldErrors(): Map<String, String> = buildMap {
        if (parseBrl(initialPrice) == null) put("initial", "Informe um valor numérico.")
        if (parseBrl(floorPrice) == null) put("floor", "Informe um valor numérico.")
        if (parseBrl(costPrice) == null) put("cost", "Informe um valor numérico.")
        if (parseBrl(reduction) == null) put("reduction", "Informe um valor numérico.")
        if (parseBrl(minMargin) == null) put("margin", "Informe um percentual.")
        if (parseBrl(lossLimit) == null) put("loss", "Informe um valor numérico.")
        if (minInterval.trim().toIntOrNull() == null) put("interval", "Informe segundos inteiros.")
        if (parseBrl(authThreshold) == null) put("threshold", "Informe um percentual.")
        val rule = toRule()
        if (rule != null) {
            if (rule.floorPrice > rule.initialPrice) put("floor", "O piso não pode ser maior que o preço inicial.")
            if (rule.reductionValue >= rule.initialPrice) put("reduction", "A redução deve ser menor que o preço inicial.")
            if (rule.minIntervalSeconds < 1) put("interval", "Mínimo de 1 segundo.")
            if (rule.authorizationThresholdPct < 0 || rule.authorizationThresholdPct > 100) put("threshold", "Entre 0% e 100%.")
            if (rule.minMarginPct >= 100) put("margin", "Deve ser menor que 100%.")
        }
    }

    companion object {
        fun from(rule: BidRule) = RuleForm(
            mode = RobotMode.MANUAL, strategy = rule.strategy,
            initialPrice = formatInput(rule.initialPrice), floorPrice = formatInput(rule.floorPrice),
            costPrice = formatInput(rule.costPrice), reduction = formatInput(rule.reductionValue),
            minMargin = formatInput(rule.minMarginPct), lossLimit = formatInput(rule.lossLimit),
            minInterval = rule.minIntervalSeconds.toString(), authThreshold = formatInput(rule.authorizationThresholdPct),
        )
    }
}

data class RobotConfigUiState(
    val loading: Boolean = true,
    val session: LiveSession? = null,
    val role: UserRole? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** Motivo da recusa do motor (RBAC/validação); null = sem erro. */
    val error: String? = null,
) {
    val canChangeRules get() = role?.let { Rbac.can(it, Permission.ALTERAR_REGRAS) } ?: false
    val canChangeFloor get() = role?.let { Rbac.can(it, Permission.APROVAR_PISO) } ?: false
}

@HiltViewModel
class RobotConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val manager: LiveSessionManager,
    auth: AuthRepository,
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()
    private val saving = MutableStateFlow(false)
    private val saved = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<RobotConfigUiState> = combine(
        manager.observeSession(sessionId), auth.session, saving, saved, loaded, error,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        RobotConfigUiState(
            loading = !(values[4] as Boolean), session = values[0] as LiveSession?,
            role = (values[1] as com.licitaia.domain.model.AuthSession?)?.user?.role,
            saving = values[2] as Boolean, saved = values[3] as Boolean, error = values[5] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RobotConfigUiState())

    init {
        viewModelScope.launch {
            // Pequena espera para o motor publicar a sessão (restauração após reabrir o app).
            var tries = 0
            while (manager.sessions.value.none { it.id == sessionId } && tries < 15) { kotlinx.coroutines.delay(100); tries++ }
            loaded.value = true
        }
    }

    fun save(rule: BidRule) {
        viewModelScope.launch {
            saving.value = true
            error.value = null
            val result = runCatching { manager.updateRule(sessionId, rule) }
            saving.value = false
            result.onSuccess { saved.value = true }
                .onFailure { e -> error.value = e.message ?: "Não foi possível salvar os parâmetros." }
        }
    }

    fun dismissError() { error.value = null }
}

@Composable
fun RobotConfigScreen(vm: RobotConfigViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val session = state.session

    LicitaScaffold(title = "Parâmetros da sessão", showBack = true, subtitle = session?.let { "${it.portal.shortName} · ${it.tenderNumber}" }) { padding ->
        when {
            state.loading && session == null -> SkeletonList(Modifier.padding(padding), items = 3)
            session == null -> ErrorState("A sessão não está mais disponível.", Modifier.padding(padding), title = "Sessão não encontrada")
            else -> ConfigForm(
                session = session,
                canChangeRules = state.canChangeRules,
                canChangeFloor = state.canChangeFloor,
                role = state.role,
                saving = state.saving,
                onSave = { rule -> vm.save(rule) },
                modifier = Modifier.padding(padding),
            )
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            navigator.showMessage("Parâmetros da sessão atualizados.")
            navigator.back()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { navigator.showMessage(it); vm.dismissError() }
    }
}

@Composable
private fun ConfigForm(
    session: LiveSession,
    canChangeRules: Boolean,
    canChangeFloor: Boolean,
    role: UserRole?,
    saving: Boolean,
    onSave: (BidRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var form by remember(session.id) { mutableStateOf(RuleForm.from(session.rule)) }
    var confirmFloor by remember { mutableStateOf<BidRule?>(null) }
    val errors = form.fieldErrors()
    val candidate = form.toRule()
    val warnings = candidate?.let { BidRuleEngine.validateRule(it) }.orEmpty()
    val floorChanged = candidate != null && candidate.floorPrice != session.rule.floorPrice
    val otherChanged = candidate != null && candidate.copy(floorPrice = session.rule.floorPrice) != session.rule.copy(mode = RobotMode.MANUAL, simulation = true)
    val blockedReason = when {
        floorChanged && !canChangeFloor -> "Alterar o piso exige a permissão “Aprovar/alterar piso” (Diretoria, Financeiro ou Admin). Seu perfil: ${role?.label ?: "—"}."
        otherChanged && !canChangeRules -> "Alterar estratégia e margens exige a permissão “Alterar regras do robô” (Diretoria ou Admin). Seu perfil: ${role?.label ?: "—"}."
        else -> null
    }
    val canSave = candidate != null && errors.isEmpty() && blockedReason == null && (floorChanged || otherChanged) && !saving

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LicitaCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PortalChip(session.portal)
                Spacer(Modifier.width(8.dp))
                Text(session.tenderNumber, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                StatusBadge("Acompanhamento", Tone.INFO)
            }
            Spacer(Modifier.height(4.dp))
            Text(session.itemLabel, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
            InfoRow("Melhor lance atual", Formatters.brl(session.bestBid))
            InfoRow("Nosso último lance", Formatters.brl(session.ourLastBid))
        }

        SectionHeader("Estratégia de sugestão")
        Text("Define como o app sugere o próximo lance. Quem envia é sempre você, no portal.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BidStrategy.entries.take(2).forEach { s -> SelectChip(s.label, form.strategy == s, { form = form.copy(strategy = s) }, Modifier.weight(1f), color = s.color()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BidStrategy.entries.drop(2).forEach { s -> SelectChip(s.label, form.strategy == s, { form = form.copy(strategy = s) }, Modifier.weight(1f), color = s.color()) }
        }
        Text(form.strategy.description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)

        SectionHeader("Preços")
        NumberField("Preço inicial", form.initialPrice, { form = form.copy(initialPrice = it) }, suffix = "R$", error = errors["initial"])
        NumberField(
            "Piso (preço mínimo)", form.floorPrice, { form = form.copy(floorPrice = it) }, suffix = "R$", error = errors["floor"],
            supporting = "O app recusa registrar lance abaixo deste valor." + if (!canChangeFloor) " Seu perfil não pode alterá-lo." else "",
        )
        NumberField("Custo total estimado", form.costPrice, { form = form.copy(costPrice = it) }, suffix = "R$", error = errors["cost"], supporting = "Base do cálculo de margem.")
        candidate?.let {
            val floorMargin = it.marginPct(it.floorPrice)
            InfoRow("Margem no piso", Formatters.percent(floorMargin), valueColor = if (floorMargin >= it.minMarginPct) LicitaColors.GreenBright else LicitaColors.Yellow)
            InfoRow("Piso efetivo", Formatters.brl(BidRuleEngine.effectiveFloor(it)), valueColor = LicitaColors.Yellow)
        }

        SectionHeader("Comportamento")
        NumberField("Redução por lance", form.reduction, { form = form.copy(reduction = it) }, suffix = "R$", error = errors["reduction"], supporting = "Conservadora usa ½, Agressiva usa 2×, Acompanhar usa o decremento mínimo.")
        NumberField("Margem mínima", form.minMargin, { form = form.copy(minMargin = it) }, suffix = "%", error = errors["margin"], supporting = "Abaixo dela o app alerta.")
        NumberField("Limite de perda", form.lossLimit, { form = form.copy(lossLimit = it) }, suffix = "R$", error = errors["loss"], supporting = "0 = nunca vender abaixo do custo (endurece o piso efetivo).")
        NumberField("Alertar quando o lance estiver a menos de", form.authThreshold, { form = form.copy(authThreshold = it) }, suffix = "% do piso", error = errors["threshold"])

        warnings.forEach { AlertBanner("Atenção", it, Tone.WARNING) }
        blockedReason?.let { AlertBanner("Permissão insuficiente", it, Tone.DANGER) }

        PrimaryButton(
            text = if (floorChanged) "Salvar e confirmar novo piso" else "Salvar regra",
            onClick = {
                val rule = candidate ?: return@PrimaryButton
                if (floorChanged) confirmFloor = rule else onSave(rule)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave,
            loading = saving,
            icon = Icons.Outlined.Save,
        )
        Text(
            "Toda alteração gera auditoria (mudança de piso registra valor anterior e novo e exige confirmação).",
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )
        Spacer(Modifier.height(24.dp))
    }

    confirmFloor?.let { rule ->
        ConfirmDialog(
            title = "Confirmar mudança de piso",
            message = "Piso atual: ${Formatters.brl(session.rule.floorPrice)}\nNovo piso: ${Formatters.brl(rule.floorPrice)}\nMargem no novo piso: ${Formatters.percent(rule.marginPct(rule.floorPrice))}\n\nO app passará a aceitar registrar lances até este valor. A mudança fica registrada na auditoria com seu nome.",
            onConfirm = { confirmFloor = null; onSave(rule) },
            onDismiss = { confirmFloor = null },
            confirmLabel = "Confirmar piso",
            tone = if (rule.floorPrice < session.rule.floorPrice) Tone.DANGER else Tone.WARNING,
        )
    }
}
