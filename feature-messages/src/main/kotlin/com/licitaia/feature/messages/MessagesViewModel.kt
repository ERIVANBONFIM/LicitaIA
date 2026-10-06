package com.licitaia.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.ReplyStatus
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MessageTab(val label: String) { ALL("Todas"), UNREAD("Não lidas"), URGENT("Urgentes") }

data class MessagesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val noSession: Boolean = false,
    val canReply: Boolean = false,
    val roleLabel: String = "",
    val all: List<AuctioneerMessage> = emptyList(),
) {
    val unreadCount get() = all.count { !it.read }
    val urgentCount get() = all.count { it.isUrgentPending() }

    fun forTab(tab: MessageTab): List<AuctioneerMessage> = when (tab) {
        MessageTab.ALL -> all
        MessageTab.UNREAD -> all.filter { !it.read }
        MessageTab.URGENT -> all.filter { it.isUrgentPending() }
    }
}

/** Urgente e ainda sem resposta enviada. */
internal fun AuctioneerMessage.isUrgentPending(): Boolean = urgent && replyStatus != ReplyStatus.ENVIADA_SIMULADA

/** Texto de contagem regressiva do prazo de resposta. */
internal fun deadlineText(deadline: Long, now: Long): String {
    val diff = deadline - now
    if (diff <= 0) return "Prazo encerrado"
    val totalSec = diff / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h >= 48 -> "${h / 24} dias"
        h >= 1 -> "${h}h ${m.toString().padStart(2, '0')}min"
        else -> "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
}

/** Prazo crítico: menos de 30 minutos restantes (ou já vencido). */
internal fun deadlineCritical(deadline: Long, now: Long): Boolean = deadline - now < 30 * 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val messages: MessageRepository,
) : ViewModel() {

    private val retry = MutableStateFlow(0)

    val state: StateFlow<MessagesUiState> = combine(auth.session, retry) { s, _ -> s }
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(MessagesUiState(loading = false, noSession = true))
            } else {
                messages.observeMessages(session.activeCompany.id)
                    .map { list ->
                        MessagesUiState(
                            loading = false,
                            canReply = Rbac.can(session.user.role, Permission.RESPONDER_MENSAGENS),
                            roleLabel = session.user.role.label,
                            all = list.sortedWith(
                                compareByDescending<AuctioneerMessage> { it.isUrgentPending() }.thenByDescending { it.receivedAt },
                            ),
                        )
                    }
                    .catch { emit(MessagesUiState(loading = false, error = it.message ?: "Falha ao carregar as mensagens.")) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun retry() = retry.update { it + 1 }

    fun markAllRead() {
        val unread = state.value.all.filter { !it.read }
        viewModelScope.launch { unread.forEach { runCatching { messages.markRead(it.id) } } }
    }
}
