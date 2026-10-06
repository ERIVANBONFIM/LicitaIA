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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Icon
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
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.AuctionSimulator
import com.licitaia.domain.bidding.CompetitorBehavior
import com.licitaia.domain.bidding.SimulationParams
import com.licitaia.domain.bidding.SimulationResult
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class StrategyInfo(
    val strategy: BidStrategy,
    val summary: String,
    val pros: List<String>,
    val cons: List<String>,
    val bestFor: String,
)

val strategyInfos: List<StrategyInfo> = listOf(
    StrategyInfo(
        BidStrategy.CONSERVADORA,
        "Só reage quando perdemos a 1ª posição e cobre o melhor lance com metade da redução configurada.",
        listOf("Preserva margem ao máximo", "Poucos lances: baixa exposição", "Ideal com piso apertado"),
        listOf("Pode perder para concorrentes rápidos", "Chega devagar ao preço de equilíbrio"),
        "Itens de margem baixa ou quando o piso está próximo do preço de mercado.",
    ),
    StrategyInfo(
        BidStrategy.AGRESSIVA,
        "Cobre o concorrente com o dobro da redução para abrir distância e desencorajar novos lances.",
        listOf("Desencoraja concorrentes", "Resolve disputas curtas rapidamente"),
        listOf("Consome margem mais rápido", "Chega ao piso cedo e para", "Pode 'deixar dinheiro na mesa'"),
        "Disputas com poucos concorrentes e margem folgada, quando ganhar vale mais do que a última fatia de margem.",
    ),
    StrategyInfo(
        BidStrategy.ACOMPANHAR_CONCORRENTE,
        "Cobre o melhor lance pelo decremento mínimo do portal, sempre respeitando o intervalo mínimo.",
        listOf("Menor custo por lance", "Fica em 1º gastando o mínimo", "Preserva margem em disputas longas"),
        listOf("Muitos lances (depende do intervalo)", "Concorrentes agressivos forçam o robô ao piso"),
        "Pregões longos com vários concorrentes conservadores.",
    ),
    StrategyInfo(
        BidStrategy.PERSONALIZADA,
        "Usa exatamente a redução e o intervalo configurados pela equipe.",
        listOf("Previsível e auditável", "Ajustável ao histórico do órgão"),
        listOf("Exige calibrar a redução", "Não se adapta sozinha ao ritmo da disputa"),
        "Quando há histórico do órgão/concorrência (ver Concorrência) para calibrar o passo.",
    ),
)

data class StrategyUiState(
    val loading: Boolean = true,
    val sessions: List<LiveSession> = emptyList(),
    val role: UserRole? = null,
    val scenario: SimulationParams = defaultScenario,
    val comparison: Map<BidStrategy, SimulationResult> = emptyMap(),
) {
    val canChangeRules get() = role?.let { Rbac.can(it, Permission.ALTERAR_REGRAS) } ?: false
}

private val defaultScenario = SimulationParams(
    competitors = 5, initialPrice = 200_000.0, floorPrice = 162_000.0, costPrice = 140_000.0, reductionValue = 600.0,
    durationSeconds = 600, strategy = BidStrategy.CONSERVADORA, behavior = CompetitorBehavior.MISTO, minIntervalSeconds = 6, seed = 2026,
)

@HiltViewModel
class StrategyViewModel @Inject constructor(
    private val manager: LiveSessionManager,
    auth: AuthRepository,
) : ViewModel() {

    private val scenario = MutableStateFlow(defaultScenario)
    private val comparison = MutableStateFlow<Map<BidStrategy, SimulationResult>>(emptyMap())
    private val loading = MutableStateFlow(true)

    val state: StateFlow<StrategyUiState> = combine(manager.sessions, auth.session, scenario, comparison, loading) { s, a, sc, cmp, l ->
        StrategyUiState(l, s, a?.user?.role, sc, cmp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StrategyUiState())

    init { compare(defaultScenario) }

    fun useSessionScenario(session: LiveSession) {
        val r = session.rule
        compare(
            scenario.value.copy(
                initialPrice = r.initialPrice, floorPrice = r.floorPrice, costPrice = r.costPrice,
                reductionValue = r.reductionValue, minIntervalSeconds = r.minIntervalSeconds, competitors = session.competitors.coerceIn(1, 30),
            ),
        )
    }

    fun setBehavior(behavior: CompetitorBehavior) = compare(scenario.value.copy(behavior = behavior))

    fun reseed() = compare(scenario.value.copy(seed = scenario.value.seed + 1))

    private fun compare(params: SimulationParams) {
        scenario.value = params
        viewModelScope.launch {
            loading.value = true
            comparison.value = withContext(Dispatchers.Default) {
                BidStrategy.entries.associateWith { AuctionSimulator.run(params.copy(strategy = it)) }
            }
            loading.value = false
        }
    }

    fun apply(session: LiveSession, strategy: BidStrategy, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { manager.updateRule(session.id, session.rule.copy(strategy = strategy)) }
            onDone()
        }
    }
}

@Composable
fun StrategyScreen(vm: StrategyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var applyTarget by remember { mutableStateOf<Pair<LiveSession, BidStrategy>?>(null) }
    var pickSessionFor by remember { mutableStateOf<BidStrategy?>(null) }
    val openSessions = state.sessions.filter { it.status != LiveStatus.ENCERRADA && it.status != LiveStatus.ERRO }

    LicitaScaffold(title = "Estratégias", showBack = false, actions = {
        SecondaryButton("Simulador", { navigator.navigate(Routes.SIMULATOR) }, Modifier.padding(end = 4.dp))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("intro") {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Compare as 4 estratégias no mesmo cenário", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Mesmo pregão simulado (${state.scenario.competitors} concorrentes, inicial ${Formatters.brlCompact(state.scenario.initialPrice)}, piso ${Formatters.brlCompact(state.scenario.floorPrice)}, ${state.scenario.durationSeconds / 60} min) com a mesma semente para cada estratégia.",
                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Comportamento dos concorrentes", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompetitorBehavior.entries.forEach { b -> SelectChip(b.label, state.scenario.behavior == b, { vm.setBehavior(b) }) }
                    }
                    if (openSessions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Usar os números de uma sessão", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(openSessions, key = { it.id }) { s ->
                                SelectChip(
                                    "${s.portal.shortName} ${s.tenderNumber}",
                                    state.scenario.initialPrice == s.rule.initialPrice && state.scenario.floorPrice == s.rule.floorPrice,
                                    { vm.useSessionScenario(s) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Nova rodada (outra semente)", { vm.reseed() }, Modifier.fillMaxWidth(), tone = Tone.NEUTRAL)
                }
            }
            if (state.loading && state.comparison.isEmpty()) {
                item("skeleton") { SkeletonList(items = 2) }
            } else {
                val bestStrategy = state.comparison.entries
                    .filter { it.value.won }
                    .maxByOrNull { it.value.marginPct }?.key
                items(strategyInfos, key = { it.strategy.name }) { info ->
                    StrategyCard(
                        info = info,
                        result = state.comparison[info.strategy],
                        best = info.strategy == bestStrategy,
                        canApply = state.canChangeRules && openSessions.isNotEmpty(),
                        onApply = {
                            if (openSessions.size == 1) applyTarget = openSessions.first() to info.strategy else pickSessionFor = info.strategy
                        },
                    )
                }
                if (!state.canChangeRules) {
                    item("rbac") {
                        AlertBanner("Aplicar a uma sessão exige permissão", "Somente Diretoria ou Administrador podem alterar a estratégia de um robô (“Alterar regras do robô”).", Tone.WARNING)
                    }
                }
            }
            item("foot") { Spacer(Modifier.height(24.dp)) }
        }
    }

    pickSessionFor?.let { strategy ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickSessionFor = null },
            containerColor = LicitaColors.SurfaceElevated,
            title = { Text("Aplicar ${strategy.label} em…", color = LicitaColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    openSessions.forEach { s ->
                        LicitaCard(Modifier.fillMaxWidth(), onClick = { pickSessionFor = null; applyTarget = s to strategy }, contentPadding = PaddingValues(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PortalChip(s.portal)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(s.tenderNumber, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                    Text("Atual: ${s.rule.strategy.label}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { pickSessionFor = null }) { Text("Cancelar") } },
        )
    }

    applyTarget?.let { (session, strategy) ->
        ConfirmDialog(
            title = "Aplicar estratégia ${strategy.label}?",
            message = "Sessão ${session.portal.shortName} · ${session.tenderNumber}\nEstratégia atual: ${session.rule.strategy.label}\n\nA mudança vale a partir do próximo lance e fica registrada na auditoria.",
            onConfirm = {
                applyTarget = null
                vm.apply(session, strategy) { navigator.showMessage("Estratégia ${strategy.label} aplicada em ${session.tenderNumber}.") }
            },
            onDismiss = { applyTarget = null },
            confirmLabel = "Aplicar",
        )
    }
}

@Composable
private fun StrategyCard(info: StrategyInfo, result: SimulationResult?, best: Boolean, canApply: Boolean, onApply: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val color = info.strategy.color()
    LicitaCard(Modifier.fillMaxWidth().animateContentSize(), onClick = { expanded = !expanded }, accent = if (best) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.strategy.label, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(1f))
            if (best) {
                Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = LicitaColors.GreenBright)
                Spacer(Modifier.width(6.dp))
                StatusBadge("Melhor no cenário", Tone.SUCCESS)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(info.summary, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        if (result != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Resultado", if (result.won) "Venceu" else if (result.position == 0) "Sem lance" else "${result.position}º", if (result.won) LicitaColors.GreenBright else LicitaColors.RedBright)
                Metric("Lance final", Formatters.brlCompact(result.ourFinalBid ?: 0.0), LicitaColors.TextPrimary)
                Metric("Margem", Formatters.percent(result.marginPct), if (result.marginPct >= 10) LicitaColors.GreenBright else LicitaColors.Yellow)
                Metric("Lances", "${result.ourBids}", LicitaColors.TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Text(result.stopReason.label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                info.pros.forEach { ProCon(it, true) }
                info.cons.forEach { ProCon(it, false) }
                Spacer(Modifier.height(6.dp))
                InfoRow("Indicada para", info.bestFor)
                result?.let {
                    Spacer(Modifier.height(6.dp))
                    BidChart(it.series, it.params.floorPrice, it.params.initialPrice, it.params.durationSeconds, ourColor = color)
                    Spacer(Modifier.height(6.dp))
                    Text(it.recommendation, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (expanded) "Menos detalhes" else "Detalhes e gráfico", { expanded = !expanded }, Modifier.weight(1f), tone = Tone.NEUTRAL)
            SecondaryButton("Aplicar a sessão", onApply, Modifier.weight(1f), enabled = canApply, icon = Icons.Outlined.CheckCircle)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
    }
}

@Composable
private fun ProCon(text: String, pro: Boolean) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (pro) Icons.Outlined.CheckCircle else Icons.Outlined.RemoveCircleOutline, contentDescription = null,
            tint = if (pro) LicitaColors.GreenBright else LicitaColors.RedBright, modifier = Modifier.padding(top = 2.dp).height(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
    }
}
