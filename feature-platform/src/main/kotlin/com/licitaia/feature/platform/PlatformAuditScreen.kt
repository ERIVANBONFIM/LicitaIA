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
import com.licitaia.core.platform.net.AuditoriaItemDto
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
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

data class PlatformAuditUi(
    val loading: Boolean = true,
    val itens: List<AuditoriaItemDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformAuditViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformAuditUi())
    val state: StateFlow<PlatformAuditUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.auditoria(200).fold(
                onSuccess = { list -> _state.update { PlatformAuditUi(loading = false, itens = list) } },
                onFailure = { e -> _state.update { PlatformAuditUi(loading = false, error = e.message ?: "Não foi possível carregar a auditoria.") } },
            )
        }
    }
}

@Composable
fun PlatformAuditScreen(viewModel: PlatformAuditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LicitaScaffold(title = "Auditoria", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.itens.isEmpty() -> EmptyState(title = "Sem registros", message = "A trilha de auditoria da empresa aparece aqui.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.itens, key = { it.id }) { AuditRow(it) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(a: AuditoriaItemDto) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Text(a.acao.replace('_', ' '), style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        val linha = listOfNotNull(a.entidade, a.usuario?.nome).joinToString(" · ")
        if (linha.isNotBlank()) Text(linha, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        a.createdAt?.let { Text(PlatformFormat.dateTime(it), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted) }
    }
}
