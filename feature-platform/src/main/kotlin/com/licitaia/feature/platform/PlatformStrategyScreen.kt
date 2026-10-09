package com.licitaia.feature.platform

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.RoboAtivaDto
import com.licitaia.core.platform.net.RoboConfigUpdateRequest
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Presets de estratégia do robô (mesmos nomes do robô de lance local). O valor [value] é o texto gravado
 * no backend (`estrategia`), compatível com o mapeamento usado pela ponte on-device.
 */
data class PlatformStrategyPreset(val value: String, val label: String, val summary: String)

val platformStrategyPresets: List<PlatformStrategyPreset> = listOf(
    PlatformStrategyPreset(
        "conservadora", "Conservadora",
        "Só reage quando perde a 1ª posição e cobre pelo decremento mínimo. Preserva margem com poucos lances.",
    ),
    PlatformStrategyPreset(
        "agressiva", "Agressiva",
        "Cobre o melhor lance com decremento maior para abrir distância. Consome margem mais rápido.",
    ),
    PlatformStrategyPreset(
        "acompanhar_concorrente", "Acompanhar concorrente",
        "Acompanha cada lance do concorrente pelo decremento mínimo, respeitando o intervalo entre lances.",
    ),
    PlatformStrategyPreset(
        "personalizada", "Personalizada",
        "Usa exatamente o decremento e o intervalo definidos abaixo, sem adaptação automática.",
    ),
)

/** Reconhece o preset a partir do texto salvo no backend (tolerante; "moderada"/desconhecido → Conservadora). */
internal fun presetValueFor(estrategia: String?): String = when {
    estrategia == null -> "conservadora"
    estrategia.contains("agress", true) -> "agressiva"
    estrategia.contains("acompan", true) -> "acompanhar_concorrente"
    estrategia.contains("personaliz", true) -> "personalizada"
    else -> "conservadora"
}

data class PlatformStrategyUi(
    val loading: Boolean = true,
    val ativas: List<RoboAtivaDto> = emptyList(),
    val error: String? = null,
    /** licitacaoId cuja configuração está sendo salva (botão em loading). */
    val savingId: String? = null,
)

@HiltViewModel
class PlatformStrategyViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformStrategyUi())
    val state: StateFlow<PlatformStrategyUi> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.roboAtivas().fold(
                onSuccess = { list -> _state.update { PlatformStrategyUi(loading = false, ativas = list) } },
                onFailure = { e -> _state.update { PlatformStrategyUi(loading = false, error = e.message ?: "Não foi possível carregar os robôs ativos.") } },
            )
        }
    }

    /**
     * Salva a estratégia/piso/decremento/intervalo de UMA licitação via `PUT /robo-lances/config/:id`.
     * SEMPRE em dry_run (modoExecucao="dry_run", confirmarAuto nunca enviado): esta tela não libera lance real.
     */
    fun save(licitacaoId: String, estrategia: String, piso: Double, decremento: Double, intervalo: Int?, itemAlvo: String?) {
        if (_state.value.savingId != null) return
        if (piso <= 0.0) { viewModelScope.launch { _events.send("Informe um piso (valor mínimo) válido.") }; return }
        _state.update { it.copy(savingId = licitacaoId) }
        viewModelScope.launch {
            val r = repository.armarRobo(
                licitacaoId,
                RoboConfigUpdateRequest(
                    estrategia = estrategia,
                    valorMinimo = piso,
                    decremento = decremento.coerceAtLeast(0.01),
                    intervaloSegundos = intervalo,
                    itemAlvo = itemAlvo,
                    modoExecucao = "dry_run",
                    confirmarAuto = null,
                    pisosItens = null,
                ),
            )
            r.fold(
                onSuccess = { _events.send("Estratégia salva.") },
                onFailure = { _events.send(it.message ?: "Não foi possível salvar a estratégia.") },
            )
            // Recarrega para refletir o estado salvo e liberar o botão.
            repository.roboAtivas().fold(
                onSuccess = { list -> _state.update { PlatformStrategyUi(loading = false, ativas = list) } },
                onFailure = { _state.update { it.copy(savingId = null) } },
            )
        }
    }
}

@Composable
fun PlatformStrategyScreen(viewModel: PlatformStrategyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    androidx.compose.runtime.LaunchedEffect(viewModel) { viewModel.events.collect(navigator::showMessage) }

    LicitaScaffold(title = "Estratégias", subtitle = "Robô de lances · Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.ativas.isEmpty() -> EmptyState(
                    title = "Nenhum robô ativo",
                    message = "Arme o robô numa licitação (detalhe › Robô de lance) para ajustar a estratégia aqui. " +
                        "No dia, o robô do celular sugere cada lance e você confirma o envio.",
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("intro") {
                        LicitaCard(Modifier.fillMaxWidth()) {
                            Text("Estratégia por licitação", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Ajuste a estratégia, o piso, o decremento e o intervalo de cada robô ativo. O robô nunca envia " +
                                    "lance abaixo do piso. No celular ele sugere cada lance e você toca Enviar.",
                                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                            )
                        }
                    }
                    item("header") { SectionHeader("Robôs ativos (${state.ativas.size})") }
                    items(state.ativas, key = { it.id }) { a ->
                        StrategyConfigCard(
                            ativa = a,
                            saving = state.savingId == a.licitacaoId,
                            onOpen = { navigator.navigate(Routes.platformTender(a.licitacaoId)) },
                            onSave = { estrategia, piso, decremento, intervalo ->
                                viewModel.save(a.licitacaoId, estrategia, piso, decremento, intervalo, a.itemAlvo)
                            },
                        )
                    }
                    item("foot") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StrategyConfigCard(
    ativa: RoboAtivaDto,
    saving: Boolean,
    onOpen: () -> Unit,
    onSave: (estrategia: String, piso: Double, decremento: Double, intervalo: Int?) -> Unit,
) {
    var estrategia by remember(ativa.id) { mutableStateOf(presetValueFor(ativa.estrategia)) }
    var piso by remember(ativa.id) { mutableStateOf(ativa.valorMinimo?.toDoubleOrNull()?.takeIf { it > 0 }?.let { fmtBR(it) } ?: "") }
    var decremento by remember(ativa.id) { mutableStateOf(ativa.decremento?.toDoubleOrNull()?.takeIf { it > 0 }?.let { fmtBR(it) } ?: "") }
    var intervalo by remember(ativa.id) { mutableStateOf(ativa.intervaloSegundos?.toString() ?: "30") }

    val accent = if (ativa.ativo) LicitaColors.Green else LicitaColors.Yellow
    LicitaCard(Modifier.fillMaxWidth(), accent = accent) {
        // Cabeçalho no mesmo formato do robô local (bolha de ícone + título + status).
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.SmartToy, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Robô de lance", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text("Licitação ${ativa.licitacaoId.take(8)}…", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
            }
            StatusBadge(if (ativa.ativo) "Armado" else "Desarmado", if (ativa.ativo) Tone.SUCCESS else Tone.NEUTRAL, pulsing = ativa.ativo)
        }
        ativa.modoExecucao?.let {
            Spacer(Modifier.height(6.dp))
            StatusBadge(if (it == "auto") "Lance automático na NUVEM" else "Manual — você confirma", if (it == "auto") Tone.DANGER else Tone.INFO)
        }
        ativa.status?.takeIf { it.isNotBlank() }?.let { InfoRow("Situação", it.replace('_', ' ')) }
        ativa.ultimoLanceValor?.takeIf { it.isNotBlank() }?.let { InfoRow("Último lance", PlatformFormat.currency(it)) }

        Spacer(Modifier.height(10.dp))
        Text("Estratégia", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Chips em linhas de 2 para caber bem em telas estreitas.
            platformStrategyPresets.chunked(2).forEach { linha ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    linha.forEach { preset ->
                        SelectChip(preset.label, estrategia == preset.value, { estrategia = preset.value })
                    }
                }
            }
        }
        platformStrategyPresets.firstOrNull { it.value == estrategia }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.summary, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }

        Spacer(Modifier.height(12.dp))
        NumberField("Piso / valor mínimo (R$)", piso, "O robô nunca dá lance abaixo deste valor.") { piso = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { NumberField("Decremento (R$)", decremento, null) { decremento = it } }
            Box(Modifier.weight(1f)) { NumberField("Intervalo (s)", intervalo, null, integer = true) { intervalo = it } }
        }

        Spacer(Modifier.height(12.dp))
        AlertBanner(
            "Modo manual no aparelho",
            "Salvar aqui guarda piso, decremento e intervalo. No dia, o robô do celular sugere cada lance e você toca Enviar. " +
                "O lance automático da nuvem só é ligado no detalhe da licitação (Robô na nuvem).",
            Tone.INFO,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                "Salvar estratégia", {
                    val p = parseValorBR(piso)
                    val d = parseValorBR(decremento)?.takeIf { it > 0 } ?: 0.01
                    if (p != null && p > 0) onSave(estrategia, p, d, intervalo.trim().toIntOrNull())
                },
                Modifier.fillMaxWidth(),
                enabled = !saving && parseValorBR(piso)?.let { it > 0 } == true,
                loading = saving, icon = Icons.Outlined.Save,
            )
            SecondaryButton("Abrir licitação", onOpen, Modifier.fillMaxWidth(), tone = Tone.NEUTRAL, icon = Icons.Outlined.OpenInNew)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, supporting: String?, integer: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || (!integer && (it == '.' || it == ',')) }.take(15)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        supportingText = supporting?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 3358.8 → "3.358,80" (padrão brasileiro, sem "R$"). */
private fun fmtBR(v: Double): String = String.format(java.util.Locale("pt", "BR"), "%,.2f", v)

/** "3.358,80" (BR) ou "3358.8": com vírgula, ponto é milhar; sem vírgula, ponto é decimal. */
private fun parseValorBR(s: String): Double? {
    val t = s.trim()
    if (t.isEmpty()) return null
    val n = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
    return n.toDoubleOrNull()?.takeIf { it.isFinite() }
}
