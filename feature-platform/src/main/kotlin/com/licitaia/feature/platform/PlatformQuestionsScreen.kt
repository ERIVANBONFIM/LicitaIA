package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.ChatMsg
import com.licitaia.core.ui.components.EmptyState
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

data class PlatformQaUi(
    val loading: Boolean = true,
    val mensagens: List<ChatMsg> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PlatformQuestionsViewModel @Inject constructor(
    private val repository: PlatformRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val id: String = savedStateHandle.get<String>("platformId").orEmpty()
    private val _state = MutableStateFlow(PlatformQaUi())
    val state: StateFlow<PlatformQaUi> = _state.asStateFlow()

    init { load() }

    fun onInput(v: String) = _state.update { it.copy(input = v) }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.chatHistorico(id).fold(
                onSuccess = { list -> _state.update { it.copy(loading = false, mensagens = list) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: "Não foi possível carregar as perguntas.") } },
            )
        }
    }

    fun send() {
        val s = _state.value
        val msg = s.input.trim()
        if (msg.isBlank() || s.sending) return
        _state.update { it.copy(sending = true, input = "") }
        viewModelScope.launch {
            val result = repository.chatPerguntar(id, msg)
            // Recarrega o histórico (a resposta da IA pode levar alguns segundos a aparecer).
            repository.chatHistorico(id).fold(
                onSuccess = { list -> _state.update { it.copy(sending = false, mensagens = list, error = result.exceptionOrNull()?.message) } },
                onFailure = { _state.update { it.copy(sending = false, error = result.exceptionOrNull()?.message ?: "Pergunta enviada; a resposta pode levar alguns segundos.") } },
            )
        }
    }
}

@Composable
fun PlatformQuestionsScreen(viewModel: PlatformQuestionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    LicitaScaffold(title = "Pergunte ao edital", subtitle = "Plataforma", showBack = true) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                    state.mensagens.isEmpty() -> EmptyState(
                        title = "Nenhuma pergunta ainda",
                        message = "Pergunte qualquer coisa sobre o edital (prazos, exigências, documentos). A IA responde com base no texto coletado pela plataforma.",
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        itemsIndexed(state.mensagens) { _, m -> QaBubble(m) }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInput,
                label = { Text("Sua pergunta sobre o edital") },
                enabled = !state.sending,
                trailingIcon = {
                    IconButton(onClick = { focus.clearFocus(); viewModel.send() }, enabled = !state.sending && state.input.isNotBlank()) {
                        if (state.sending) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = LicitaColors.Blue)
                        else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Enviar")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { focus.clearFocus(); viewModel.send() }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun QaBubble(m: ChatMsg) {
    val isUser = m.autor.equals("user", true) || m.autor.equals("usuario", true) || m.autor.equals("você", true)
    LicitaCard(Modifier.fillMaxWidth(), accent = if (isUser) LicitaColors.Green else LicitaColors.Blue) {
        Text(
            if (isUser) "Você" else "IA",
            style = MaterialTheme.typography.labelSmall,
            color = if (isUser) LicitaColors.GreenBright else LicitaColors.BlueBright,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(m.texto, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
    }
}
