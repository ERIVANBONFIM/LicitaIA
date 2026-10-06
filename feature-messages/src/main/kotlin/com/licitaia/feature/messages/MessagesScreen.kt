package com.licitaia.feature.messages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PulsingDot
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.ReplyStatus
import com.licitaia.domain.util.Formatters
import kotlinx.coroutines.delay

internal fun ReplyStatus.tone(): Tone = when (this) {
    ReplyStatus.NENHUMA -> Tone.NEUTRAL
    ReplyStatus.RASCUNHO -> Tone.WARNING
    ReplyStatus.APROVADA -> Tone.INFO
    ReplyStatus.ENVIADA_SIMULADA -> Tone.SUCCESS
}

/** Relógio de 1 s para as contagens regressivas. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1_000)
        value = System.currentTimeMillis()
    }
}

@Composable
fun MessagesScreen(viewModel: MessagesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var tab by rememberSaveable { mutableStateOf(MessageTab.ALL) }
    val now by rememberNow()

    LicitaScaffold(
        title = "Mensagens do Pregoeiro",
        showBack = false,
        actions = {
            if (state.unreadCount > 0) {
                IconButton(onClick = {
                    viewModel.markAllRead()
                    navigator.showMessage("Mensagens marcadas como lidas")
                }) { Icon(Icons.Outlined.DoneAll, contentDescription = "Marcar todas como lidas") }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList()
                state.noSession -> ErrorState("Sessão encerrada. Entre novamente para ver as mensagens.")
                state.error != null -> ErrorState(state.error ?: "", onRetry = viewModel::retry)
                else -> {
                    val list = state.forTab(tab)
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "tabs") {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                SelectChip("Todas (${state.all.size})", tab == MessageTab.ALL, { tab = MessageTab.ALL })
                                SelectChip("Não lidas (${state.unreadCount})", tab == MessageTab.UNREAD, { tab = MessageTab.UNREAD })
                                SelectChip("Urgentes (${state.urgentCount})", tab == MessageTab.URGENT, { tab = MessageTab.URGENT }, color = LicitaColors.Red)
                            }
                        }
                        if (state.urgentCount > 0 && tab != MessageTab.URGENT) {
                            item(key = "urgentBanner") {
                                AlertBanner(
                                    "${state.urgentCount} mensagem(ns) urgente(s) aguardando resposta",
                                    "Convocações e diligências têm prazo curto. Responda dentro do prazo do pregoeiro.",
                                    Tone.DANGER, actionLabel = "Ver", onAction = { tab = MessageTab.URGENT }, pulsing = true,
                                )
                            }
                        }
                        if (!state.canReply) {
                            item(key = "rbac") {
                                AlertBanner(
                                    "Somente leitura",
                                    "O perfil ${state.roleLabel} não tem a permissão \"Responder ao pregoeiro\".",
                                    Tone.WARNING,
                                )
                            }
                        }
                        if (list.isEmpty()) {
                            item(key = "empty") {
                                when (tab) {
                                    MessageTab.ALL -> EmptyState(
                                        "Nenhuma mensagem",
                                        "As mensagens do pregoeiro recebidas nas sessões acompanhadas aparecem aqui, com resumo e sugestão de resposta da IA.",
                                    )
                                    MessageTab.UNREAD -> EmptyState("Tudo lido", "Você não tem mensagens pendentes de leitura.", icon = Icons.Outlined.MarkEmailRead)
                                    MessageTab.URGENT -> EmptyState("Sem urgências", "Nenhuma mensagem urgente aguardando resposta.", icon = Icons.Outlined.MarkEmailRead)
                                }
                            }
                        } else {
                            items(list, key = { "msg-${it.id}" }) { message ->
                                MessageCard(message, now, Modifier.animateItem()) { navigator.navigate(Routes.message(message.id)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: AuctioneerMessage, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val pendingUrgent = message.isUrgentPending()
    LicitaCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        accent = when {
            pendingUrgent -> LicitaColors.Red
            !message.read -> LicitaColors.Blue
            else -> null
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!message.read) {
                PulsingDot(if (pendingUrgent) LicitaColors.Red else LicitaColors.Blue)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                message.sender, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary,
                fontWeight = if (message.read) FontWeight.Medium else FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(Formatters.relative(message.receivedAt, now), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PortalChip(message.portal)
            Text("Pregão ${message.tenderNumber}", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            message.body, style = MaterialTheme.typography.bodyMedium,
            color = if (message.read) LicitaColors.TextSecondary else LicitaColors.TextPrimary,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.urgent) StatusBadge("URGENTE", Tone.DANGER, pulsing = pendingUrgent)
            if (message.replyStatus != ReplyStatus.NENHUMA) StatusBadge(message.replyStatus.label, message.replyStatus.tone())
            Spacer(Modifier.weight(1f))
            val deadline = message.responseDeadline
            if (deadline != null && message.replyStatus != ReplyStatus.ENVIADA_SIMULADA) {
                DeadlineLabel(deadline, now)
            }
        }
    }
}

@Composable
internal fun DeadlineLabel(deadline: Long, now: Long, modifier: Modifier = Modifier) {
    val critical = deadlineCritical(deadline, now)
    val color by animateColorAsState(if (critical) LicitaColors.Red else LicitaColors.Yellow, label = "deadline")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            deadlineText(deadline, now), style = MaterialTheme.typography.labelMedium, color = color,
            fontWeight = if (critical) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
