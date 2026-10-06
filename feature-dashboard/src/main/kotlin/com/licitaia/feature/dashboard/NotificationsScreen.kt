package com.licitaia.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PulsingDot
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AppNotification
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.NotificationRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<AppNotification> = emptyList(),
) {
    val unread: Int get() = items.count { !it.read }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val repository: NotificationRepository,
) : ViewModel() {

    val state: StateFlow<NotificationsUiState> = auth.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(NotificationsUiState(loading = false))
        } else {
            repository.observeNotifications(session.activeCompany.id)
                .map { list ->
                    NotificationsUiState(
                        loading = false,
                        // não lidas primeiro, depois prioridade da categoria e mais recentes
                        items = list.sortedWith(
                            compareBy<AppNotification> { it.read }
                                .thenBy { it.category.priority }
                                .thenByDescending { it.createdAt },
                        ),
                    )
                }
                .catch { emit(NotificationsUiState(loading = false, error = "Não foi possível carregar as notificações.")) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState())

    fun markRead(id: Long) = safely { repository.markRead(id) }

    fun markAllRead() = safely { auth.session.value?.let { repository.markAllRead(it.activeCompany.id) } }

    fun clear() = safely { auth.session.value?.let { repository.clear(it.activeCompany.id) } }

    private fun safely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

private fun NotificationCategory.icon(): ImageVector = when (this) {
    NotificationCategory.CAPTCHA -> Icons.Outlined.Security
    NotificationCategory.CRITICA -> Icons.Outlined.ErrorOutline
    NotificationCategory.LANCES -> Icons.Outlined.Gavel
    NotificationCategory.MENSAGENS -> Icons.AutoMirrored.Outlined.Chat
    NotificationCategory.DOCUMENTOS -> Icons.Outlined.Description
    NotificationCategory.SESSOES -> Icons.Outlined.LiveTv
    NotificationCategory.RADAR -> Icons.Outlined.Radar
    NotificationCategory.GERAL -> Icons.Outlined.Info
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var category by rememberSaveable { mutableStateOf<NotificationCategory?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LicitaScaffold(
        title = "Notificações",
        showBack = true,
        actions = {
            IconButton(onClick = viewModel::markAllRead, enabled = state.unread > 0) {
                Icon(Icons.Outlined.DoneAll, contentDescription = "Marcar todas como lidas")
            }
            IconButton(onClick = { confirmClear = true }, enabled = state.items.isNotEmpty()) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = "Limpar notificações")
            }
        },
    ) { padding ->
        val error = state.error
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            error != null -> ErrorState(error, Modifier.padding(padding))
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Tudo em dia",
                    message = "Alertas de CAPTCHA, lances, mensagens do pregoeiro, documentos e radares aparecem aqui.",
                    icon = Icons.Outlined.NotificationsNone,
                )
            }
            else -> {
                val present = NotificationCategory.entries.filter { c -> state.items.any { it.category == c } }
                val selected = category?.takeIf { it in present }
                val visible = if (selected == null) state.items else state.items.filter { it.category == selected }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "summary") {
                        Text(
                            if (state.unread > 0) "${state.unread} não lida(s) de ${state.items.size}" else "${state.items.size} notificação(ões), todas lidas",
                            style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
                        )
                    }
                    item(key = "filters") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") { SelectChip("Todas", selected == null, { category = null }) }
                            items(present, key = { it.name }) { c ->
                                SelectChip(c.label, selected == c, { category = if (selected == c) null else c }, color = c.tone().color())
                            }
                        }
                    }
                    items(visible, key = { it.id }) { notification ->
                        NotificationCard(
                            notification,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                if (!notification.read) viewModel.markRead(notification.id)
                                notification.route?.takeIf { it.isNotBlank() }?.let(navigator::navigate)
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Limpar notificações?",
            message = "Todas as notificações desta empresa serão removidas. Esta ação não pode ser desfeita.",
            confirmLabel = "Limpar",
            tone = Tone.DANGER,
            icon = Icons.Outlined.DeleteSweep,
            onConfirm = { confirmClear = false; viewModel.clear() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun NotificationCard(n: AppNotification, modifier: Modifier, onClick: () -> Unit) {
    val tone = if (n.critical) Tone.DANGER else n.category.tone()
    val titleColor by animateColorAsState(if (n.read) LicitaColors.TextSecondary else LicitaColors.TextPrimary, label = "title")
    LicitaCard(
        modifier.fillMaxWidth(), onClick = onClick,
        accent = if (!n.read) tone.color() else null, contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconBubble(n.category.icon(), if (n.read) LicitaColors.TextMuted else tone.color())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        n.title, style = MaterialTheme.typography.titleSmall, color = titleColor,
                        fontWeight = if (n.read) FontWeight.Medium else FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (!n.read) {
                        Spacer(Modifier.width(8.dp))
                        PulsingDot(tone.color(), size = 8.dp)
                    }
                }
                if (n.body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(n.body, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(n.category.label, tone)
                    if (n.critical) {
                        Spacer(Modifier.width(6.dp))
                        StatusBadge("Crítica", Tone.DANGER)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(Formatters.relative(n.createdAt), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    if (!n.route.isNullOrBlank()) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
