package com.licitaia.feature.platform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.RoboAtivaDto
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformRobotUi(
    val loading: Boolean = true,
    val ativas: List<RoboAtivaDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformRobotViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformRobotUi())
    val state: StateFlow<PlatformRobotUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.roboAtivas().fold(
                onSuccess = { list -> _state.update { PlatformRobotUi(loading = false, ativas = list) } },
                onFailure = { e -> _state.update { PlatformRobotUi(loading = false, error = e.message ?: "Não foi possível carregar os robôs ativos.") } },
            )
        }
    }
}

@Composable
fun PlatformRobotScreen(viewModel: PlatformRobotViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LicitaScaffold(title = "Robô de Lances", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.ativas.isEmpty() -> EmptyState(
                    title = "Nenhum robô ativo",
                    message = "Arme o robô numa licitação (detalhe › Robô de lance). A disputa roda no aparelho com o certificado local.",
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("intro") {
                        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Blue) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(Icons.Outlined.SmartToy, LicitaColors.Blue)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Robôs de lance ativos", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                                    Text(
                                        "Cada robô opera no aparelho com o certificado local. Abra para armar, simular e acompanhar.",
                                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                    item("header") { SectionHeader("Em operação (${state.ativas.size})") }
                    items(state.ativas, key = { it.id }) { a -> AtivaCard(a) { navigator.navigate(Routes.platformTender(a.licitacaoId)) } }
                    item("foot") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AtivaCard(a: RoboAtivaDto, onOpen: () -> Unit) {
    val accent = if (a.ativo) LicitaColors.Green else LicitaColors.Yellow
    LicitaCard(Modifier.fillMaxWidth(), onClick = onOpen, accent = accent) {
        // Mesmo formato do robô local: bolha de ícone + título/subtítulo + status pulsante.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.SmartToy, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.estrategia?.replaceFirstChar { it.uppercase() } ?: "Robô de lance", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text("Licitação ${a.licitacaoId.take(8)}…", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
            }
            StatusBadge(if (a.ativo) "Armado" else "Desarmado", if (a.ativo) Tone.SUCCESS else Tone.NEUTRAL, pulsing = a.ativo)
        }
        a.modoExecucao?.let {
            Spacer(Modifier.height(6.dp))
            StatusBadge(if (it == "auto") "Lance automático na NUVEM" else "Manual — você confirma", if (it == "auto") Tone.DANGER else Tone.INFO)
        }
        Spacer(Modifier.height(6.dp))
        a.valorMinimo?.let { InfoRow("Piso (valor mínimo)", PlatformFormat.currency(it)) }
        a.decremento?.let { InfoRow("Decremento", PlatformFormat.currency(it)) }
        a.intervaloSegundos?.let { InfoRow("Intervalo (s)", "$it") }
        a.itemAlvo?.takeIf { it.isNotBlank() }?.let { InfoRow("Item alvo", it) }
        a.ultimoLanceValor?.takeIf { it.isNotBlank() }?.let { InfoRow("Último lance", PlatformFormat.currency(it)) }
    }
}
