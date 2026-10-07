package com.licitaia.feature.bidding.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.BidStrategyConfig
import com.licitaia.domain.bidding.BidStrategyConfigRepository
import com.licitaia.domain.bidding.DecrementMode
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Explicação curta de cada estratégia (sem números de simulação). */
data class StrategyInfo(val strategy: BidStrategy, val summary: String, val bestFor: String)

val strategyInfos: List<StrategyInfo> = listOf(
    StrategyInfo(
        BidStrategy.CONSERVADORA,
        "Só reage quando perdemos a 1ª posição e cobre o melhor lance com o decremento mínimo. Preserva margem com poucos lances.",
        "Itens de margem baixa ou piso próximo do preço de mercado.",
    ),
    StrategyInfo(
        BidStrategy.AGRESSIVA,
        "Cobre o melhor lance com o dobro do decremento para abrir distância e desencorajar novos lances. Consome margem mais rápido.",
        "Disputas com poucos concorrentes e margem folgada.",
    ),
    StrategyInfo(
        BidStrategy.ACOMPANHAR_CONCORRENTE,
        "Acompanha cada lance do concorrente pelo decremento mínimo, respeitando o intervalo entre lances.",
        "Pregões longos com vários concorrentes conservadores.",
    ),
    StrategyInfo(
        BidStrategy.PERSONALIZADA,
        "Usa exatamente o decremento e o intervalo definidos abaixo, sem adaptação automática.",
        "Quando a equipe já conhece o órgão e os concorrentes (veja Concorrência).",
    ),
)

/** Formulário em texto (o usuário digita com vírgula); convertido em [BidStrategyConfig] ao salvar. */
data class StrategyForm(
    val strategy: BidStrategy = BidStrategy.CONSERVADORA,
    val decrementMode: DecrementMode = DecrementMode.VALOR,
    val decrementValue: String = "",
    val decrementPct: String = "",
    val minMarginPct: String = "",
    val reactOnlyWhenLosingFirst: Boolean = true,
    val finalBidEnabled: Boolean = false,
    val finalBidSecondsBefore: String = "",
    val minIntervalSeconds: String = "",
    val authorizationThresholdPct: String = "",
) {
    fun toConfig(): Result<BidStrategyConfig> {
        fun num(text: String, label: String): Double = parseDecimal(text) ?: throw IllegalArgumentException("Valor inválido em \"$label\".")
        fun numPct(text: String, label: String): Double =
            text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() } ?: throw IllegalArgumentException("Valor inválido em \"$label\".")
        fun int(text: String, label: String): Int = text.trim().toIntOrNull() ?: throw IllegalArgumentException("Valor inválido em \"$label\".")
        return runCatching {
            val c = BidStrategyConfig(
                strategy = strategy,
                decrementMode = decrementMode,
                decrementValue = if (decrementMode == DecrementMode.VALOR) num(decrementValue, "Decremento (R$)") else decrementValue.toMoneyOr(1.0),
                decrementPct = if (decrementMode == DecrementMode.PERCENTUAL) numPct(decrementPct, "Decremento (%)") else decrementPct.toPctOr(0.5),
                minMarginPct = numPct(minMarginPct, "Margem mínima"),
                reactOnlyWhenLosingFirst = reactOnlyWhenLosingFirst,
                finalBidEnabled = finalBidEnabled,
                finalBidSecondsBefore = if (finalBidEnabled) int(finalBidSecondsBefore, "Segundos antes do fim") else finalBidSecondsBefore.trim().toIntOrNull() ?: 10,
                minIntervalSeconds = int(minIntervalSeconds, "Intervalo entre lances"),
                authorizationThresholdPct = numPct(authorizationThresholdPct, "Pedir autorização perto do piso"),
            )
            val errors = c.validate()
            require(errors.isEmpty()) { errors.first() }
            c
        }
    }

    companion object {
        fun from(c: BidStrategyConfig) = StrategyForm(
            strategy = c.strategy,
            decrementMode = c.decrementMode,
            decrementValue = String.format(PT, "%.2f", c.decrementValue),
            decrementPct = c.decrementPct.plain(),
            minMarginPct = c.minMarginPct.plain(),
            reactOnlyWhenLosingFirst = c.reactOnlyWhenLosingFirst,
            finalBidEnabled = c.finalBidEnabled,
            finalBidSecondsBefore = c.finalBidSecondsBefore.toString(),
            minIntervalSeconds = c.minIntervalSeconds.toString(),
            authorizationThresholdPct = c.authorizationThresholdPct.plain(),
        )

        private val PT = Locale("pt", "BR")
        private fun Double.plain(): String = if (this % 1.0 == 0.0) toLong().toString() else toString().replace('.', ',')
        private fun String.toMoneyOr(def: Double) = parseDecimal(this)?.takeIf { it > 0 } ?: def

        /** "1.234,56" / "1234,56" / "1.5" → número; com vírgula, o ponto é separador de milhar. */
        internal fun parseDecimal(text: String): Double? {
            val t = text.trim()
            val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
            return normalized.toDoubleOrNull()?.takeIf { !it.isNaN() }
        }
        private fun String.toPctOr(def: Double) = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: def
    }
}

data class StrategyUiState(
    val loading: Boolean = true,
    val companyId: Long = 0,
    val role: UserRole? = null,
    val saved: BidStrategyConfig = BidStrategyConfig(),
    val openSessions: List<LiveSession> = emptyList(),
) {
    val canChangeRules get() = role?.let { Rbac.can(it, Permission.ALTERAR_REGRAS) } ?: false
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StrategyViewModel @Inject constructor(
    private val manager: LiveSessionManager,
    private val auth: AuthRepository,
    private val configs: BidStrategyConfigRepository,
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    val saving = MutableStateFlow(false)

    val state: StateFlow<StrategyUiState> = auth.session.flatMapLatest { s ->
        if (s == null) flowOf(StrategyUiState(loading = false))
        else combine(configs.observe(s.activeCompany.id), manager.sessions) { cfg, sessions ->
            StrategyUiState(
                loading = false, companyId = s.activeCompany.id, role = s.user.role, saved = cfg,
                openSessions = sessions.filter { it.isOpen && it.companyId == s.activeCompany.id },
            )
        }.catch { emit(StrategyUiState(loading = false, role = s.user.role, companyId = s.activeCompany.id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StrategyUiState())

    fun save(form: StrategyForm) {
        val companyId = state.value.companyId
        if (companyId <= 0 || saving.value) return
        val config = form.toConfig().getOrElse { _messages.tryEmit(it.message ?: "Dados inválidos."); return }
        saving.value = true
        viewModelScope.launch {
            try {
                configs.save(companyId, config)
                _messages.tryEmit("Configuração do robô salva. Vale para as próximas sessões.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Não foi possível salvar a configuração.")
            } finally {
                saving.value = false
            }
        }
    }

    /** Aplica a configuração salva (estratégia, decremento, margem, intervalo, autorização) às sessões abertas. */
    fun applyToOpenSessions() {
        val s = state.value
        if (s.openSessions.isEmpty() || saving.value) return
        saving.value = true
        viewModelScope.launch {
            var ok = 0
            var firstError: String? = null
            for (session in s.openSessions) {
                val c = s.saved
                val reference = session.ourLastBid ?: session.rule.initialPrice
                val rule = session.rule.copy(
                    strategy = c.strategy,
                    reductionValue = c.decrementFor(reference).takeIf { it > 0.0 } ?: session.rule.reductionValue,
                    minMarginPct = c.minMarginPct,
                    minIntervalSeconds = c.minIntervalSeconds,
                    authorizationThresholdPct = c.authorizationThresholdPct,
                )
                try {
                    manager.updateRule(session.id, rule)
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e.message
                }
            }
            saving.value = false
            _messages.tryEmit(
                if (firstError == null) "Configuração aplicada em $ok sessão(ões) aberta(s)."
                else "Aplicada em $ok sessão(ões). Falha: $firstError",
            )
        }
    }
}

@Composable
fun StrategyScreen(vm: StrategyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(vm) { vm.messages.collect(navigator::showMessage) }

    var form by remember { mutableStateOf<StrategyForm?>(null) }
    // Carrega o formulário a partir da configuração salva (e recarrega ao trocar de empresa).
    LaunchedEffect(state.loading, state.companyId, state.saved.updatedAt) {
        if (!state.loading) form = StrategyForm.from(state.saved)
    }
    var confirmApply by remember { mutableStateOf(false) }
    val editable = state.canChangeRules && !saving

    LicitaScaffold(title = "Estratégias", subtitle = "Configuração do robô de lances", showBack = false) { padding ->
        val f = form
        if (state.loading || f == null) {
            SkeletonList(Modifier.padding(padding))
            return@LicitaScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("intro") {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Estratégia padrão da empresa", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "O robô de lances usa esta configuração em toda sessão nova. O piso e o custo continuam definidos por licitação, " +
                            "e o robô nunca envia lance abaixo do piso.",
                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                    )
                    if (state.saved.updatedAt > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Salva em ${Formatters.dateTime(state.saved.updatedAt)}" + (state.saved.updatedBy.takeIf { it.isNotBlank() }?.let { " por $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                    }
                }
            }
            if (!state.canChangeRules) {
                item("rbac") {
                    AlertBanner("Somente leitura", "Somente Diretoria ou Administrador podem alterar as regras do robô (“Alterar regras do robô”).", Tone.WARNING)
                }
            }

            item("strategyHeader") { SectionHeader("Estratégia") }
            strategyInfos.forEach { info ->
                item("s-${info.strategy.name}") {
                    StrategyOption(info, selected = f.strategy == info.strategy, enabled = editable) { form = f.copy(strategy = info.strategy) }
                }
            }

            item("paramsHeader") { SectionHeader("Parâmetros") }
            item("params") {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Decremento mínimo por lance", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecrementMode.entries.forEach { mode ->
                            SelectChip(mode.label, f.decrementMode == mode, { if (editable) form = f.copy(decrementMode = mode) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (f.decrementMode == DecrementMode.VALOR) {
                        NumberField("Decremento (R$)", f.decrementValue, editable, "Mínimo exigido pelo edital/portal.") { form = f.copy(decrementValue = it) }
                    } else {
                        NumberField("Decremento (% do lance atual)", f.decrementPct, editable, "Calculado sobre o nosso último lance.") { form = f.copy(decrementPct = it) }
                    }
                    Spacer(Modifier.height(8.dp))
                    NumberField("Margem mínima (%)", f.minMarginPct, editable, "O robô não propõe lance com margem menor sobre o custo.") { form = f.copy(minMarginPct = it) }
                    Spacer(Modifier.height(8.dp))
                    NumberField("Intervalo mínimo entre lances (s)", f.minIntervalSeconds, editable, "Compras.gov.br: 20 s entre lances do mesmo fornecedor.", integer = true) { form = f.copy(minIntervalSeconds = it) }
                    Spacer(Modifier.height(8.dp))
                    NumberField("Pedir autorização perto do piso (%)", f.authorizationThresholdPct, editable, "Pede sua confirmação quando o próximo lance ficar a menos disso do piso.") { form = f.copy(authorizationThresholdPct = it) }
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        "Reagir só quando perder o 1º lugar", "Enquanto estivermos em 1º, o robô não cobre o próprio lance.",
                        f.reactOnlyWhenLosingFirst, editable,
                    ) { form = f.copy(reactOnlyWhenLosingFirst = it) }
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        "Lance final nos últimos segundos", "Na fase final (tempo aleatório/iminência), dá um último lance perto do encerramento.",
                        f.finalBidEnabled, editable,
                    ) { form = f.copy(finalBidEnabled = it) }
                    if (f.finalBidEnabled) {
                        Spacer(Modifier.height(8.dp))
                        NumberField("Segundos antes do fim", f.finalBidSecondsBefore, editable, null, integer = true) { form = f.copy(finalBidSecondsBefore = it) }
                    }
                }
            }

            item("actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Salvar configuração", { vm.save(f) }, Modifier.fillMaxWidth(), enabled = editable, loading = saving, icon = Icons.Outlined.Save)
                    if (state.openSessions.isNotEmpty()) {
                        SecondaryButton(
                            "Aplicar às ${state.openSessions.size} sessão(ões) aberta(s)", { confirmApply = true }, Modifier.fillMaxWidth(),
                            enabled = editable, icon = Icons.Outlined.SyncAlt,
                        )
                        Text(
                            "Sessões já abertas mantêm a regra atual até você aplicar. Piso e custo de cada sessão não mudam.",
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                        )
                    }
                }
            }
            item("foot") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmApply) {
        ConfirmDialog(
            title = "Aplicar a configuração salva?",
            message = "Estratégia ${state.saved.strategy.label}, decremento, margem mínima, intervalo e alerta de autorização passam a valer nas " +
                "${state.openSessions.size} sessão(ões) aberta(s) a partir da próxima sugestão de lance. Fica registrado na auditoria.",
            onConfirm = { confirmApply = false; vm.applyToOpenSessions() },
            onDismiss = { confirmApply = false },
            confirmLabel = "Aplicar",
        )
    }
}

private fun BidStrategy.tint(): Color = when (this) {
    BidStrategy.CONSERVADORA -> LicitaColors.BlueBright
    BidStrategy.AGRESSIVA -> LicitaColors.RedBright
    BidStrategy.ACOMPANHAR_CONCORRENTE -> LicitaColors.Yellow
    BidStrategy.PERSONALIZADA -> LicitaColors.Purple
}

@Composable
private fun StrategyOption(info: StrategyInfo, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val color = info.strategy.tint()
    LicitaCard(Modifier.fillMaxWidth(), onClick = if (enabled) onSelect else null, accent = if (selected) color else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
                tint = if (selected) color else LicitaColors.TextMuted,
            )
            Spacer(Modifier.width(10.dp))
            Text(info.strategy.label, style = MaterialTheme.typography.titleMedium, color = if (selected) color else LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            if (selected) StatusBadge("Padrão", Tone.SUCCESS)
        }
        Spacer(Modifier.height(6.dp))
        Text(info.summary, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text("Indicada para: ${info.bestFor}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
    }
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean, supporting: String?, integer: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || (!integer && (it == ',' || it == '.')) }.take(12)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        supportingText = supporting?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
            Text(description, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
