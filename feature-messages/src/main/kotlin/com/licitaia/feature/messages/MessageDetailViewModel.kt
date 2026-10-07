package com.licitaia.feature.messages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.ReplyStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MessageDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val message: AuctioneerMessage? = null,
    val canReply: Boolean = false,
    val roleLabel: String = "",
    val companyName: String = "",
    val draft: String = "",
    val aiLoading: Boolean = false,
    val aiError: String? = null,
    val busy: Boolean = false,
) {
    val sent: Boolean get() = message?.replyStatus == ReplyStatus.ENVIADA_SIMULADA
    val approved: Boolean get() = message?.replyStatus == ReplyStatus.APROVADA

    /** O texto no editor difere do que está salvo. */
    val dirty: Boolean get() = draft.trim() != (message?.replyDraft ?: "").trim()

    /** Só leva ao portal o texto exatamente como foi aprovado. */
    val canSend: Boolean get() = canReply && approved && !dirty && draft.isNotBlank() && !busy
}

@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val messages: MessageRepository,
) : ViewModel() {

    private val messageId: Long? = when (val raw: Any? = savedStateHandle["messageId"]) {
        is Long -> raw
        is Int -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }

    private val _state = MutableStateFlow(MessageDetailUiState())
    val state: StateFlow<MessageDetailUiState> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var observeJob: Job? = null
    private var initialized = false

    init { load() }

    fun load() {
        val id = messageId
        if (id == null || id <= 0) {
            _state.value = MessageDetailUiState(loading = false, error = "Mensagem inválida.")
            return
        }
        observeJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        observeJob = viewModelScope.launch {
            combine(auth.session, messages.observeMessage(id)) { s, m -> s to m }
                .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: "Falha ao carregar a mensagem.") } }
                .collect { (session, message) ->
                    when {
                        session == null -> _state.update { it.copy(loading = false, error = "Sessão encerrada. Entre novamente.") }
                        message == null || message.companyId != session.activeCompany.id ->
                            _state.update { it.copy(loading = false, message = null, error = "Mensagem não encontrada nesta empresa.") }
                        else -> {
                            _state.update {
                                it.copy(
                                    loading = false, error = null, message = message,
                                    canReply = Rbac.can(session.user.role, Permission.RESPONDER_MENSAGENS),
                                    roleLabel = session.user.role.label,
                                    companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name },
                                )
                            }
                            if (!initialized) {
                                initialized = true
                                _state.update { it.copy(draft = message.replyDraft ?: message.suggestedReply.orEmpty()) }
                                if (!message.read) launch { runCatching { messages.markRead(id) } }
                                if (message.aiSummary.isNullOrBlank() || message.suggestedReply.isNullOrBlank()) generateAi()
                            }
                        }
                    }
                }
        }
    }

    fun generateAi() {
        val id = messageId ?: return
        if (_state.value.aiLoading) return
        _state.update { it.copy(aiLoading = true, aiError = null) }
        viewModelScope.launch {
            val result = runCatching { messages.generateAiAssist(id).getOrThrow() }
            result.onSuccess { updated ->
                _state.update {
                    it.copy(
                        aiLoading = false,
                        // O editor parte da sugestão enquanto o usuário não escreveu nada.
                        draft = if (it.draft.isBlank() && !it.sent) updated.suggestedReply.orEmpty() else it.draft,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(aiLoading = false, aiError = e.message ?: "A IA não respondeu. Tente novamente.") }
            }
        }
    }

    fun setDraft(text: String) {
        val s = _state.value
        if (!s.canReply || s.sent) return
        _state.update { it.copy(draft = text.take(4000)) }
    }

    fun useSuggestion() {
        val suggestion = _state.value.message?.suggestedReply ?: return
        setDraft(suggestion)
    }

    fun saveDraft() = runAction("Rascunho salvo") { id, s ->
        messages.saveReplyDraft(id, s.draft.trim())
    }

    fun approve() = runAction("Resposta aprovada. Revise e confirme o envio.") { id, s ->
        if (s.dirty || s.message?.replyStatus == ReplyStatus.NENHUMA) messages.saveReplyDraft(id, s.draft.trim())
        // A auditoria (APROVACAO) é registrada pelo repositório.
        messages.approveReply(id)
    }

    private fun runAction(success: String, block: suspend (Long, MessageDetailUiState) -> Unit) {
        val id = messageId ?: return
        val s = _state.value
        if (!s.canReply || s.busy || s.sent) return
        if (s.draft.isBlank()) {
            _events.trySend("Escreva a resposta antes de continuar")
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block(id, s) }
                .onSuccess { _events.send(success) }
                .onFailure { e -> _events.send(e.message ?: "Não foi possível concluir a ação") }
            _state.update { it.copy(busy = false) }
        }
    }
}
