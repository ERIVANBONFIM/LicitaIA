package com.licitaia.feature.bidding.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.StatCard
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.bidding.AuctionSimulator
import com.licitaia.domain.bidding.CompetitorBehavior
import com.licitaia.domain.bidding.SimulationParams
import com.licitaia.domain.bidding.SimulationResult
import com.licitaia.domain.bidding.SimulationStopReason
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SimulatorForm(
    val competitors: String = "5",
    val initialPrice: String = "200000",
    val floorPrice: String = "162000",
    val costPrice: String = "140000",
    val reduction: String = "600",
    val durationMinutes: String = "10",
    val minInterval: String = "6",
    val strategy: BidStrategy = BidStrategy.ACOMPANHAR_CONCORRENTE,
    val behavior: CompetitorBehavior = CompetitorBehavior.MISTO,
    val seed: Long = 42,
) {
    fun toParams(): SimulationParams? {
        return SimulationParams(
            competitors = competitors.trim().toIntOrNull() ?: return null,
            initialPrice = parseBrl(initialPrice) ?: return null,
            floorPrice = parseBrl(floorPrice) ?: return null,
            costPrice = parseBrl(costPrice) ?: return null,
            reductionValue = parseBrl(reduction) ?: return null,
            durationSeconds = ((parseBrl(durationMinutes) ?: return null) * 60).toInt(),
            strategy = strategy,
            behavior = behavior,
            minIntervalSeconds = minInterval.trim().toIntOrNull() ?: return null,
            seed = seed,
        )
    }
}

data class SimulatorUiState(
    val form: SimulatorForm = SimulatorForm(),
    val running: Boolean = false,
    val result: SimulationResult? = null,
    val errors: List<String> = emptyList(),
)

@HiltViewModel
class SimulatorViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(SimulatorUiState())
    val state: StateFlow<SimulatorUiState> = _state.asStateFlow()

    fun update(transform: SimulatorForm.() -> SimulatorForm) = _state.update { it.copy(form = it.form.transform(), errors = emptyList()) }

    fun run(newSeed: Boolean = false) {
        val form = if (newSeed) _state.value.form.copy(seed = _state.value.form.seed + 1) else _state.value.form
        val params = form.toParams()
        if (params == null) {
            _state.update { it.copy(form = form, errors = listOf("Preencha todos os campos com números válidos.")) }
            return
        }
        val errors = AuctionSimulator.validate(params)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(form = form, errors = errors) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(form = form, running = true, errors = emptyList()) }
            val result = withContext(Dispatchers.Default) { AuctionSimulator.run(params) }
            _state.update { it.copy(running = false, result = result) }
        }
    }
}

@Composable
fun SimulatorScreen(vm: SimulatorViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form

    LicitaScaffold(title = "Simulador", showBack = false) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LicitaCard(Modifier.fillMaxWidth()) {
                Text("Teste a estratégia sem portal real", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Disputa 100% local e determinística: a mesma semente reproduz o mesmo pregão. Os concorrentes seguem perfis configuráveis; o nosso lado usa o mesmo motor de regras que sugere lances no modo assistido — treino, não operação.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }

            SectionHeader("Cenário")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Concorrentes", form.competitors, { v -> vm.update { copy(competitors = v) } }, Modifier.weight(1f))
                NumberField("Duração", form.durationMinutes, { v -> vm.update { copy(durationMinutes = v) } }, Modifier.weight(1f), suffix = "min")
            }
            NumberField("Preço inicial", form.initialPrice, { v -> vm.update { copy(initialPrice = v) } }, suffix = "R$")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Piso", form.floorPrice, { v -> vm.update { copy(floorPrice = v) } }, Modifier.weight(1f), suffix = "R$")
                NumberField("Custo", form.costPrice, { v -> vm.update { copy(costPrice = v) } }, Modifier.weight(1f), suffix = "R$")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Redução por lance", form.reduction, { v -> vm.update { copy(reduction = v) } }, Modifier.weight(1f), suffix = "R$")
                NumberField("Intervalo mín.", form.minInterval, { v -> vm.update { copy(minInterval = v) } }, Modifier.weight(1f), suffix = "s")
            }

            Text("Comportamento dos concorrentes", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompetitorBehavior.entries.forEach { b -> SelectChip(b.label, form.behavior == b, { vm.update { copy(behavior = b) } }) }
            }
            Text(form.behavior.description, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)

            Text("Nossa estratégia", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BidStrategy.entries.take(2).forEach { s -> SelectChip(s.label, form.strategy == s, { vm.update { copy(strategy = s) } }, Modifier.weight(1f), color = s.color()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BidStrategy.entries.drop(2).forEach { s -> SelectChip(s.label, form.strategy == s, { vm.update { copy(strategy = s) } }, Modifier.weight(1f), color = s.color()) }
            }

            state.errors.forEach { AlertBanner("Parâmetro inválido", it, Tone.DANGER) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Simular pregão", { vm.run() }, Modifier.weight(1f), loading = state.running, icon = Icons.Outlined.PlayArrow)
                SecondaryButton("Outra rodada", { vm.run(newSeed = true) }, Modifier.weight(1f), enabled = !state.running, tone = Tone.NEUTRAL)
            }

            AnimatedVisibility(state.result != null) {
                state.result?.let { ResultSection(it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultSection(result: SimulationResult) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Resultado (semente ${result.params.seed})")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                "Posição final", if (result.won) "1º — venceu" else if (result.position == 0) "Sem lance" else "${result.position}º",
                Icons.Outlined.EmojiEvents, Modifier.weight(1f), tone = if (result.won) Tone.SUCCESS else Tone.DANGER, highlight = result.won,
            )
            StatCard("Margem", Formatters.percent(result.marginPct), Icons.Outlined.Percent, Modifier.weight(1f), tone = if (result.marginPct >= 10) Tone.SUCCESS else if (result.marginPct > 0) Tone.WARNING else Tone.DANGER)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Nosso lance final", Formatters.brlCompact(result.ourFinalBid ?: 0.0), Icons.Outlined.Sell, Modifier.weight(1f), tone = Tone.INFO)
            StatCard("Lances (nossos/total)", "${result.ourBids}/${result.totalBids}", Icons.Outlined.Gavel, Modifier.weight(1f), tone = Tone.NEUTRAL)
        }
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Evolução dos lances", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            BidChart(result.series, result.params.floorPrice, result.params.initialPrice, result.params.durationSeconds, ourColor = result.params.strategy.color())
        }
        LicitaCard(Modifier.fillMaxWidth()) {
            InfoRow("Preço final da disputa", Formatters.brl(result.finalPrice))
            InfoRow("Motivo da parada", result.stopReason.label, valueColor = when (result.stopReason) {
                SimulationStopReason.VENCEU_NO_TEMPO -> LicitaColors.GreenBright
                SimulationStopReason.PARADO_NO_PISO -> LicitaColors.Yellow
                else -> LicitaColors.RedBright
            })
            InfoRow("Estratégia", result.params.strategy.label, valueColor = result.params.strategy.color())
            InfoRow("Concorrentes", "${result.params.competitors} · ${result.params.behavior.label}")
        }
        AlertBanner("Recomendação", result.recommendation, if (result.won) Tone.SUCCESS else Tone.WARNING)
    }
}
