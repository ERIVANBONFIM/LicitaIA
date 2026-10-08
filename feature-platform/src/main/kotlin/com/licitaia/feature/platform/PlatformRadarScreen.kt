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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.RadarFiltroDto
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformRadarUi(
    val loading: Boolean = true,
    val filtros: List<RadarFiltroDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformRadarViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformRadarUi())
    val state: StateFlow<PlatformRadarUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.radarFiltros().fold(
                onSuccess = { list -> _state.update { PlatformRadarUi(loading = false, filtros = list) } },
                onFailure = { e -> _state.update { PlatformRadarUi(loading = false, error = e.message ?: "Não foi possível carregar os radares.") } },
            )
        }
    }
}

@Composable
fun PlatformRadarScreen(viewModel: PlatformRadarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LicitaScaffold(title = "Radares salvos", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.filtros.isEmpty() -> EmptyState(title = "Nenhum radar salvo", message = "Os filtros de radar criados na plataforma aparecem aqui.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.filtros, key = { it.id }) { RadarCard(it) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RadarCard(f: RadarFiltroDto) {
    LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Blue) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.nome.ifBlank { "Radar" }, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusBadge(if (f.ativo) "ativo" else "inativo", if (f.ativo) Tone.SUCCESS else Tone.NEUTRAL)
        }
        Spacer(Modifier.height(8.dp))
        if (f.palavrasChave.isNotEmpty()) InfoRow("Palavras-chave", f.palavrasChave.joinToString(", "))
        if (f.portais.isNotEmpty()) InfoRow("Portais", f.portais.joinToString(", "))
        if (f.estados.isNotEmpty()) InfoRow("Estados", f.estados.joinToString(", "))
        f.countMatch?.let { InfoRow("Resultados", it.toString()) }
    }
}
