package com.licitaia.feature.platform

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
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

data class PlatformRadarResultsUi(
    val loading: Boolean = true,
    val tenders: List<TenderDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformRadarResultsViewModel @Inject constructor(
    private val repository: PlatformRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val radarId: String = savedStateHandle.get<String>("radarId").orEmpty()

    private val _state = MutableStateFlow(PlatformRadarResultsUi())
    val state: StateFlow<PlatformRadarResultsUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.radarLicitacoes(radarId).fold(
                onSuccess = { list -> _state.update { it.copy(loading = false, tenders = list) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: "Não foi possível carregar os resultados.") } },
            )
        }
    }
}

@Composable
fun PlatformRadarResultsScreen(viewModel: PlatformRadarResultsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    LicitaScaffold(title = "Resultados do radar", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.tenders.isEmpty() -> EmptyState(title = "Nenhuma licitação encontrada", message = "Este radar ainda não encontrou licitações. Ajuste os filtros ou tente novamente mais tarde.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("${state.tenders.size} licitação(ões)", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextMuted)
                    }
                    items(state.tenders, key = { it.id }) { t ->
                        RadarResultRow(t) { navigator.navigate(Routes.platformTender(t.id)) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RadarResultRow(t: TenderDto, onClick: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                t.numero.ifBlank { "Licitação" },
                style = MaterialTheme.typography.titleSmall,
                color = LicitaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            t.fase?.let { StatusBadge(it.replace('_', ' '), Tone.INFO) }
        }
        Spacer(Modifier.height(6.dp))
        Text(t.orgao, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(t.objeto, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        InfoRow("Valor estimado", PlatformFormat.currency(t.valorEstimado))
        InfoRow("Abertura", PlatformFormat.dateTime(t.dataAbertura))
        listOfNotNull(t.portal, t.estado?.let { uf -> t.cidade?.let { "$it/$uf" } ?: uf }).takeIf { it.isNotEmpty() }?.let {
            InfoRow("Portal / Local", it.joinToString(" · "))
        }
    }
}
