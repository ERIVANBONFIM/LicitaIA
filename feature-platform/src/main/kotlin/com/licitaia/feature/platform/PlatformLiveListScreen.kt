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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.RoboAtivaDto
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

data class PlatformLiveListUi(
    val loading: Boolean = true,
    val ativas: List<RoboAtivaDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformLiveListViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformLiveListUi())
    val state: StateFlow<PlatformLiveListUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.roboAtivas().fold(
                onSuccess = { list -> _state.update { PlatformLiveListUi(loading = false, ativas = list) } },
                onFailure = { e -> _state.update { PlatformLiveListUi(loading = false, error = e.message ?: "Não foi possível carregar os pregões ao vivo.") } },
            )
        }
    }
}

@Composable
fun PlatformLiveListScreen(viewModel: PlatformLiveListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LicitaScaffold(title = "Pregões ao Vivo", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.ativas.isEmpty() -> EmptyState(
                    title = "Nenhum pregão ao vivo",
                    message = "Quando houver disputa ativa, ela aparece aqui. Toque em atualizar após armar/participar.",
                    actionLabel = "Atualizar",
                    onAction = viewModel::load,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.ativas, key = { it.id }) { a ->
                        LiveRow(a) { navigator.navigate(Routes.platformLive(a.licitacaoId)) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LiveRow(a: RoboAtivaDto, onOpen: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth().clickable(onClick = onOpen), accent = LicitaColors.Red) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.estrategia?.replaceFirstChar { it.uppercase() } ?: "Disputa", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            a.modoExecucao?.let { StatusBadge(if (it == "auto") "AUTO" else "dry_run", if (it == "auto") Tone.DANGER else Tone.INFO) }
        }
        Spacer(Modifier.height(6.dp))
        a.valorMinimo?.let { InfoRow("Piso", PlatformFormat.currency(it)) }
        a.ultimoLanceValor?.let { InfoRow("Último lance", PlatformFormat.currency(it)) }
        a.status?.let { InfoRow("Status", it) }
        Spacer(Modifier.height(4.dp))
        Text("Toque para acompanhar ao vivo", style = MaterialTheme.typography.labelSmall, color = LicitaColors.BlueBright)
    }
}
