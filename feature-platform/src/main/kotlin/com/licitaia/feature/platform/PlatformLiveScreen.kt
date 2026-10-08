package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.AoVivoEventoDto
import com.licitaia.core.platform.net.AoVivoSnapshotDto
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformLiveUi(
    val loadingFirst: Boolean = true,
    val ativo: Boolean = false,
    val snapshots: List<AoVivoSnapshotDto> = emptyList(),
    val eventos: List<AoVivoEventoDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformLiveViewModel @Inject constructor(
    private val repository: PlatformRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val id: String = savedStateHandle.get<String>("platformId").orEmpty()

    private val _state = MutableStateFlow(PlatformLiveUi())
    val state: StateFlow<PlatformLiveUi> = _state.asStateFlow()

    init {
        // Polling a cada ~5s enquanto a ViewModel viver (para sozinho ao sair da tela — viewModelScope é cancelado).
        viewModelScope.launch {
            while (isActive) {
                repository.aoVivo(id).fold(
                    onSuccess = { d ->
                        _state.update { it.copy(loadingFirst = false, ativo = d.ativo, snapshots = d.snapshots, eventos = d.eventos, error = null) }
                    },
                    onFailure = { e ->
                        _state.update { it.copy(loadingFirst = false, error = e.message ?: "Falha ao atualizar o acompanhamento.") }
                    },
                )
                delay(5_000)
            }
        }
    }
}

@Composable
fun PlatformLiveScreen(viewModel: PlatformLiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LicitaScaffold(title = "Pregão ao vivo", subtitle = "Plataforma · atualiza a cada 5s", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loadingFirst -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null && state.snapshots.isEmpty() && state.eventos.isEmpty() ->
                    ErrorState(message = state.error!!)
                !state.ativo && state.snapshots.isEmpty() && state.eventos.isEmpty() ->
                    EmptyState(title = "Nenhuma disputa ao vivo agora", message = "Quando a disputa estiver em andamento, os lances e eventos aparecem aqui (leitura em tempo real).")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        StatusBadge(if (state.ativo) "AO VIVO" else "encerrado/parado", if (state.ativo) Tone.SUCCESS else Tone.NEUTRAL)
                    }
                    if (state.snapshots.isNotEmpty()) {
                        item { Text("Itens em disputa", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary) }
                        items(state.snapshots, key = { it.itemNumero ?: it.hashCode() }) { SnapshotCard(it) }
                    }
                    if (state.eventos.isNotEmpty()) {
                        item { Text("Eventos recentes", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary) }
                        item {
                            LicitaCard(Modifier.fillMaxWidth()) {
                                state.eventos.take(20).forEachIndexed { i, e ->
                                    if (i > 0) Spacer(Modifier.height(6.dp))
                                    EventoRow(e)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SnapshotCard(s: AoVivoSnapshotDto) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Item ${s.itemNumero ?: "—"}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            s.phase?.let { StatusBadge(it.replace('_', ' '), Tone.INFO) }
        }
        Spacer(Modifier.height(6.dp))
        InfoRow("Melhor lance", PlatformFormat.currency(s.bestBid))
        InfoRow("Seu lance", PlatformFormat.currency(s.ownBid))
        s.ownPosition?.let { InfoRow("Sua posição", if (it == 1) "1º (na frente)" else "$it") }
        InfoRow("Piso", PlatformFormat.currency(s.floor))
        s.situacao?.let { InfoRow("Situação", it) }
        s.portalState?.let { InfoRow("Portal", it) }
        s.capturedAt?.let { Text("Lido em ${PlatformFormat.dateTime(it)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
    }
}

@Composable
private fun EventoRow(e: AoVivoEventoDto) {
    val tone = when (e.severity?.lowercase()) {
        "high", "critical", "critica", "erro", "error" -> Tone.DANGER
        "warn", "warning", "aviso" -> Tone.WARNING
        else -> Tone.INFO
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(e.newState?.replace('_', ' ') ?: e.severity ?: "evento", tone)
        Spacer(Modifier.height(0.dp))
        Text(
            "  " + (e.reason ?: listOfNotNull(e.previousState, e.newState).joinToString(" → ")),
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f),
        )
    }
    e.createdAt?.let { Text(PlatformFormat.dateTime(it), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
}
