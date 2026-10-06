package com.licitaia.feature.live.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Campos do formulário "Acompanhar pregão" (todos texto; validados ao montar a regra). */
data class AssistedForm(
    val tenderId: Long? = null,
    val portal: Portal = Portal.COMPRAS_GOV,
    val number: String = "",
    val agency: String = "",
    val item: String = "",
    val objectDescription: String = "",
    val initialPrice: String = "",
    val costPrice: String = "",
    val floorPrice: String = "",
    val minMargin: String = "10",
    val reduction: String = "",
    val floorAlertPct: String = "5",
    val competitors: String = "3",
    val strategy: BidStrategy = BidStrategy.CONSERVADORA,
) {
    fun toRule(): BidRule? {
        val initial = parseMoney(initialPrice) ?: return null
        val cost = parseMoney(costPrice) ?: return null
        val floor = parseMoney(floorPrice) ?: return null
        val margin = parsePct(minMargin) ?: return null
        val red = parseMoney(reduction) ?: return null
        val alert = parsePct(floorAlertPct) ?: return null
        return BidRule(
            mode = RobotMode.MANUAL, strategy = strategy, initialPrice = initial, floorPrice = floor, costPrice = cost,
            reductionValue = red, minMarginPct = margin, lossLimit = 0.0, minIntervalSeconds = 1,
            authorizationThresholdPct = alert, simulation = true,
        )
    }

    /** Erros por campo. Vazio = formulário válido. */
    fun errors(): Map<String, String> = buildMap {
        if (number.trim().length < 3) put("number", "Informe o número do pregão (ex.: 90012/2026).")
        if (agency.trim().length < 3) put("agency", "Informe o órgão.")
        if (item.trim().isEmpty()) put("item", "Informe o item ou lote (ex.: Item 1).")
        if (objectDescription.trim().length < 5) put("object", "Descreva o objeto.")
        if (parseMoney(initialPrice) == null) put("initial", "Informe o preço inicial (nossa proposta).")
        if (parseMoney(costPrice) == null) put("cost", "Informe o custo total.")
        if (parseMoney(floorPrice) == null) put("floor", "Informe o piso (preço mínimo).")
        if (parsePct(minMargin) == null) put("margin", "Informe a margem mínima em %.")
        if (parseMoney(reduction) == null) put("reduction", "Informe a redução sugerida por lance.")
        if (parsePct(floorAlertPct) == null) put("alert", "Informe o percentual.")
        if (competitors.trim().toIntOrNull()?.takeIf { it >= 0 } == null) put("competitors", "Informe um número inteiro.")
        toRule()?.let { rule ->
            if (rule.floorPrice > rule.initialPrice) put("floor", "O piso não pode ser maior que o preço inicial.")
            if (rule.reductionValue >= rule.initialPrice) put("reduction", "A redução deve ser menor que o preço inicial.")
            if (rule.minMarginPct >= 100) put("margin", "Deve ser menor que 100%.")
            if (rule.authorizationThresholdPct < 0 || rule.authorizationThresholdPct > 100) put("alert", "Entre 0% e 100%.")
        }
    }

    fun toSpec(companyId: Long): LiveSessionSpec? {
        val rule = toRule() ?: return null
        if (errors().isNotEmpty()) return null
        return LiveSessionSpec(
            companyId = companyId, tenderId = tenderId, portal = portal, tenderNumber = number.trim(), agency = agency.trim(),
            itemLabel = item.trim(), objectDescription = objectDescription.trim(), rule = rule,
            competitors = competitors.trim().toIntOrNull() ?: 0,
        )
    }

    companion object {
        fun fromTender(t: Tender, current: AssistedForm): AssistedForm = current.copy(
            tenderId = t.id, portal = t.portal, number = t.number, agency = t.agency,
            objectDescription = t.objectDescription,
            initialPrice = if (t.estimatedValue > 0) formatMoneyInput(t.estimatedValue) else current.initialPrice,
            item = current.item.ifBlank { "Item único" },
        )
    }
}

fun parsePct(text: String): Double? = text.trim().removeSuffix("%").trim().let { raw ->
    if (raw.isBlank()) null else raw.replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0 }
}

fun formatMoneyInput(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.US, "%.2f", value)

data class AssistedFormUiState(
    val companyId: Long? = null,
    val role: UserRole? = null,
    val tenders: List<Tender> = emptyList(),
    val creating: Boolean = false,
) {
    val canOperate get() = role?.let { Rbac.can(it, Permission.OPERAR_SESSOES) } ?: false
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssistedSessionFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val manager: LiveSessionManager,
    auth: AuthRepository,
    tenderRepository: TenderRepository,
) : ViewModel() {

    val preselectedTenderId: Long? = savedStateHandle.get<Long>("tenderId")?.takeIf { it > 0 }
    private val creating = MutableStateFlow(false)

    private val tenders = auth.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else tenderRepository.observeTenders(s.activeCompany.id).catch { emit(emptyList()) }
    }

    val state: StateFlow<AssistedFormUiState> = combine(auth.session, tenders, creating) { session, tenders, creating ->
        AssistedFormUiState(
            companyId = session?.activeCompany?.id, role = session?.user?.role,
            tenders = tenders.filter { it.status != TenderStatus.DESCARTADA && it.status != TenderStatus.VENCIDA && it.status != TenderStatus.PERDIDA },
            creating = creating,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistedFormUiState())

    fun create(spec: LiveSessionSpec, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            creating.value = true
            val result = runCatching { manager.openSession(spec) }
            creating.value = false
            onDone(result)
        }
    }
}

@Composable
fun AssistedSessionFormScreen(vm: AssistedSessionFormViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var form by remember { mutableStateOf(AssistedForm()) }
    var manual by remember { mutableStateOf(vm.preselectedTenderId == null) }
    var pickTender by remember { mutableStateOf(false) }
    var preselected by remember { mutableStateOf(false) }

    LaunchedEffect(state.tenders, vm.preselectedTenderId) {
        val id = vm.preselectedTenderId
        if (!preselected && id != null) {
            state.tenders.firstOrNull { it.id == id }?.let { form = AssistedForm.fromTender(it, form); manual = false; preselected = true }
        }
    }

    val errors = form.errors()
    val rule = form.toRule()
    val selectedTender = form.tenderId?.let { id -> state.tenders.firstOrNull { it.id == id } }

    LicitaScaffold(title = "Acompanhar pregão", showBack = true, subtitle = "Modo assistido") { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlertBanner(
                "Você opera no portal; o LicitaIA é o copiloto",
                "Nenhum lance é enviado pelo app. Registre aqui o que fizer no portal: o app calcula margem e distância ao piso, cronometra, sugere o próximo lance e alerta.",
                Tone.INFO,
            )
            if (!state.canOperate) {
                AlertBanner("Perfil sem permissão", "Acompanhar pregões exige a permissão “Operar sessões de pregão” (perfil Licitações ou Administrador).", Tone.WARNING)
            }

            SectionHeader("Licitação")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip("Licitação de interesse", !manual, { manual = false; if (form.tenderId == null) pickTender = true })
                SelectChip("Dados manuais", manual, { manual = true; form = form.copy(tenderId = null) })
            }
            if (!manual) {
                LicitaCard(Modifier.fillMaxWidth(), onClick = { pickTender = true }) {
                    if (selectedTender == null) {
                        Text("Toque para escolher uma licitação de interesse", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
                        Text("${state.tenders.size} disponível(is). Ao encerrar, o status dela será atualizado (vencida/perdida).", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PortalChip(selectedTender.portal)
                            Spacer(Modifier.width(8.dp))
                            Text(selectedTender.number, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                        }
                        Text(selectedTender.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                        Text(selectedTender.objectDescription, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
                        Text("Toque para trocar", style = MaterialTheme.typography.labelSmall, color = LicitaColors.BlueBright)
                    }
                }
            }

            SectionHeader("Portal")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Portal.entries.take(3).forEach { p -> SelectChip(p.shortName, form.portal == p, { form = form.copy(portal = p) }, Modifier.weight(1f)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Portal.entries.drop(3).forEach { p -> SelectChip(p.shortName, form.portal == p, { form = form.copy(portal = p) }, Modifier.weight(1f)) }
            }

            PlainField("Nº do pregão", form.number, { form = form.copy(number = it) }, errors["number"])
            PlainField("Órgão", form.agency, { form = form.copy(agency = it) }, errors["agency"])
            PlainField("Item / lote", form.item, { form = form.copy(item = it) }, errors["item"], supporting = "Uma sessão por item ou lote em disputa.")
            PlainField("Objeto", form.objectDescription, { form = form.copy(objectDescription = it) }, errors["object"], singleLine = false)

            SectionHeader("Preços")
            MoneyField("Preço inicial (nossa proposta)", form.initialPrice, { form = form.copy(initialPrice = it) }, "R$", errors["initial"])
            MoneyField("Custo total estimado", form.costPrice, { form = form.copy(costPrice = it) }, "R$", errors["cost"], supporting = "Base do cálculo de margem.")
            MoneyField("Piso (preço mínimo)", form.floorPrice, { form = form.copy(floorPrice = it) }, "R$", errors["floor"], supporting = "O app recusa registrar lance abaixo deste valor.")
            MoneyField("Margem mínima", form.minMargin, { form = form.copy(minMargin = it) }, "%", errors["margin"], supporting = "Abaixo dela o app alerta.")
            rule?.let {
                val floorMargin = it.marginPct(it.floorPrice)
                InfoRow("Margem no preço inicial", Formatters.percent(it.marginPct(it.initialPrice)), valueColor = LicitaColors.GreenBright)
                InfoRow("Margem no piso", Formatters.percent(floorMargin), valueColor = if (floorMargin >= it.minMarginPct) LicitaColors.GreenBright else LicitaColors.Yellow)
                InfoRow("Piso efetivo", Formatters.brl(BidRuleEngine.effectiveFloor(it)), valueColor = LicitaColors.Yellow)
                BidRuleEngine.validateRule(it).forEach { w -> AlertBanner("Atenção", w, Tone.WARNING) }
            }

            SectionHeader("Sugestão de lances")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BidStrategy.entries.take(2).forEach { s -> SelectChip(s.label, form.strategy == s, { form = form.copy(strategy = s) }, Modifier.weight(1f)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BidStrategy.entries.drop(2).forEach { s -> SelectChip(s.label, form.strategy == s, { form = form.copy(strategy = s) }, Modifier.weight(1f)) }
            }
            Text(form.strategy.description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            MoneyField("Redução sugerida por lance", form.reduction, { form = form.copy(reduction = it) }, "R$", errors["reduction"], supporting = "Conservadora usa ½, Agressiva 2×, Acompanhar usa o decremento mínimo.")
            MoneyField("Alertar quando o lance estiver a menos de", form.floorAlertPct, { form = form.copy(floorAlertPct = it) }, "% do piso", errors["alert"])
            MoneyField("Nº estimado de concorrentes", form.competitors, { form = form.copy(competitors = it) }, null, errors["competitors"], integer = true)

            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                "Iniciar acompanhamento",
                onClick = {
                    val companyId = state.companyId ?: return@PrimaryButton
                    val spec = form.toSpec(companyId) ?: return@PrimaryButton
                    vm.create(spec) { result ->
                        result.onSuccess { id -> navigator.showMessage("Sessão assistida criada. Opere no portal e registre os lances aqui."); navigator.back(); navigator.navigate(Routes.liveSession(id)) }
                            .onFailure { e -> navigator.showMessage(e.message ?: "Não foi possível criar a sessão.") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = errors.isEmpty() && state.companyId != null && state.canOperate && !state.creating,
                loading = state.creating,
                icon = Icons.Outlined.Podcasts,
                tone = Tone.SUCCESS,
            )
            Text(
                "A sessão nasce “Aguardando abertura”, sem robô. Tudo que você registrar gera log e auditoria com seu nome.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickTender) {
        AlertDialog(
            onDismissRequest = { pickTender = false },
            containerColor = LicitaColors.SurfaceElevated,
            title = { Text("Licitação de interesse", color = LicitaColors.TextPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.tenders.isEmpty()) {
                        Text("Nenhuma licitação de interesse ativa. Use “Dados manuais” ou cadastre em Tenho Interesse.", color = LicitaColors.TextSecondary)
                    }
                    state.tenders.forEach { t ->
                        LicitaCard(Modifier.fillMaxWidth(), onClick = { form = AssistedForm.fromTender(t, form); manual = false; pickTender = false }, contentPadding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PortalChip(t.portal)
                                Spacer(Modifier.width(8.dp))
                                Text(t.number, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
                                Text(t.status.label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                            }
                            Text(t.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                            Text(Formatters.brl(t.estimatedValue), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTender = false }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun PlainField(label: String, value: String, onValueChange: (String) -> Unit, error: String?, supporting: String? = null, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = singleLine,
        label = { Text(label) }, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it, color = if (error != null) LicitaColors.Red else LicitaColors.TextMuted) } },
        colors = fieldColors(),
    )
}

@Composable
internal fun MoneyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String?,
    error: String?,
    supporting: String? = null,
    integer: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, isError = error != null,
        suffix = suffix?.let { { Text(it, color = LicitaColors.TextMuted) } },
        supportingText = (error ?: supporting)?.let { { Text(it, color = if (error != null) LicitaColors.Red else LicitaColors.TextMuted) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        colors = fieldColors(),
    )
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LicitaColors.Blue, unfocusedBorderColor = LicitaColors.Outline, cursorColor = LicitaColors.Blue,
    focusedTextColor = LicitaColors.TextPrimary, unfocusedTextColor = LicitaColors.TextPrimary,
    focusedLabelColor = LicitaColors.BlueBright, unfocusedLabelColor = LicitaColors.TextSecondary,
    errorBorderColor = LicitaColors.Red, errorLabelColor = LicitaColors.Red,
)
