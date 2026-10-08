package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.licitaia.core.platform.net.ConcorrenteDto
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.theme.LicitaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformCompetitorsUi(
    val loading: Boolean = true,
    val concorrentes: List<ConcorrenteDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformCompetitorsViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformCompetitorsUi())
    val state: StateFlow<PlatformCompetitorsUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.concorrentes().fold(
                onSuccess = { list -> _state.update { PlatformCompetitorsUi(loading = false, concorrentes = list) } },
                onFailure = { e -> _state.update { PlatformCompetitorsUi(loading = false, error = e.message ?: "Não foi possível carregar a concorrência.") } },
            )
        }
    }
}

@Composable
fun PlatformCompetitorsScreen(viewModel: PlatformCompetitorsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LicitaScaffold(title = "Concorrência", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.concorrentes.isEmpty() -> EmptyState(title = "Nenhum concorrente", message = "A plataforma ainda não mapeou concorrentes para a sua empresa.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.concorrentes, key = { it.id }) { CompetitorCard(it) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CompetitorCard(c: ConcorrenteDto) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Text(c.razaoSocial.ifBlank { c.nomeFantasia ?: "Concorrente" }, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        c.nomeFantasia?.takeIf { it.isNotBlank() }?.let { InfoRow("Nome fantasia", it) }
        InfoRow("CNPJ", c.cnpj.ifBlank { "—" })
        InfoRow("Local", listOfNotNull(c.cidade, c.estado).joinToString("/").ifBlank { "—" })
        c.porte?.let { InfoRow("Porte", it) }
        c.situacao?.let { InfoRow("Situação", it) }
    }
}
