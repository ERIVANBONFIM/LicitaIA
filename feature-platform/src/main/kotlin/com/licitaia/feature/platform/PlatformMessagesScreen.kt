package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.licitaia.core.platform.net.MensagemDto
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

data class PlatformMessagesUi(
    val loading: Boolean = true,
    val mensagens: List<MensagemDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PlatformMessagesViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformMessagesUi())
    val state: StateFlow<PlatformMessagesUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.mensagens().fold(
                onSuccess = { list -> _state.update { PlatformMessagesUi(loading = false, mensagens = list) } },
                onFailure = { e -> _state.update { PlatformMessagesUi(loading = false, error = e.message ?: "Não foi possível carregar as mensagens.") } },
            )
        }
    }
}

@Composable
fun PlatformMessagesScreen(viewModel: PlatformMessagesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LicitaScaffold(title = "Mensagens do pregoeiro", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.mensagens.isEmpty() -> EmptyState(title = "Nenhuma mensagem", message = "As mensagens do chat de disputa aparecem aqui quando houver.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    itemsIndexed(state.mensagens) { i, m -> MessageCard(m, i) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(m: MensagemDto, index: Int) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Text(m.displayTitle, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        if (m.displayBody.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(m.displayBody, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        }
        m.createdAt?.let {
            Spacer(Modifier.height(6.dp))
            Text(PlatformFormat.dateTime(it), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
    }
}
