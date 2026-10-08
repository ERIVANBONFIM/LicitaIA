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
import com.licitaia.core.platform.net.DocumentoDto
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

data class PlatformDocsUi(
    val loading: Boolean = true,
    val docs: List<DocumentoDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformDocumentsViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformDocsUi())
    val state: StateFlow<PlatformDocsUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.documentos().fold(
                onSuccess = { list -> _state.update { PlatformDocsUi(loading = false, docs = list) } },
                onFailure = { e -> _state.update { PlatformDocsUi(loading = false, error = e.message ?: "Não foi possível carregar os documentos.") } },
            )
        }
    }
}

@Composable
fun PlatformDocumentsScreen(viewModel: PlatformDocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LicitaScaffold(title = "Documentos", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.docs.isEmpty() -> EmptyState(title = "Nenhum documento", message = "A empresa ainda não tem documentos cadastrados na plataforma.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.docs, key = { it.id }) { DocCard(it) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DocCard(d: DocumentoDto) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Text(d.nome.ifBlank { "Documento" }, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        d.categoria?.let { InfoRow("Categoria", it.replace('_', ' ')) }
        InfoRow("Validade", PlatformFormat.dateTime(d.validade))
        d.status?.let {
            Spacer(Modifier.height(8.dp))
            StatusBadge(it, if (it.equals("regular", true)) Tone.SUCCESS else Tone.WARNING)
        }
    }
}
