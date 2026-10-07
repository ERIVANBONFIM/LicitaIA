package com.licitaia.feature.messages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.licitaia.core.ui.nav.Routes
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonBox
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.ReplyStatus
import com.licitaia.domain.util.Formatters

@Composable
fun MessageDetailScreen(viewModel: MessageDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val clipboard = LocalClipboardManager.current
    val now by rememberNow()
    val replyInPortal: () -> Unit = {
        state.message?.let { m ->
            val text = state.draft.trim()
            if (text.isNotEmpty()) {
                clipboard.setText(AnnotatedString(text))
                navigator.showMessage("Resposta copiada. Cole no chat do pregoeiro e envie no portal.")
            }
            navigator.navigate(Routes.portalWeb(m.portal))
        }
    }

    LaunchedEffect(Unit) { viewModel.events.collect { navigator.showMessage(it) } }

    LicitaScaffold(title = "Mensagem", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val message = state.message
            when {
                state.loading -> SkeletonList(items = 3)
                state.error != null || message == null -> ErrorState(state.error ?: "Mensagem não encontrada.", onRetry = viewModel::load)
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val deadline = message.responseDeadline
                    if (deadline != null && !state.sent) {
                        val critical = deadlineCritical(deadline, now) || message.urgent
                        AlertBanner(
                            title = if (deadline <= now) "Prazo de resposta encerrado" else "Responder em ${deadlineText(deadline, now)}",
                            message = "Prazo do pregoeiro: ${Formatters.dateTime(deadline)}",
                            tone = if (critical) Tone.DANGER else Tone.WARNING,
                            pulsing = critical && deadline > now,
                        )
                    }

                    MessageBody(message, onOpenPortal = { navigator.navigate(Routes.portalWeb(message.portal)) })
                    AiSummaryCard(state, onGenerate = viewModel::generateAi)
                    SuggestedReplyCard(state, onUse = viewModel::useSuggestion)
                    ReplyEditor(state, viewModel, onReplyInPortal = replyInPortal)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

}

@Composable
private fun MessageBody(message: AuctioneerMessage, onOpenPortal: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth(), accent = if (message.isUrgentPending()) LicitaColors.Red else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(message.sender, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Recebida em ${Formatters.dateTime(message.receivedAt)}", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            if (message.urgent) StatusBadge("URGENTE", Tone.DANGER, pulsing = message.isUrgentPending())
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PortalChip(message.portal)
            Text("Pregão ${message.tenderNumber}", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(message.body, style = MaterialTheme.typography.bodyLarge, color = LicitaColors.TextPrimary)
        }
        TextButton(onClick = onOpenPortal) { Text("Abrir a licitação no portal (${message.portal.shortName})") }
    }
}

@Composable
private fun AiSummaryCard(state: MessageDetailUiState, onGenerate: () -> Unit) {
    val summary = state.message?.aiSummary
    LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Purple) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.AutoAwesome, LicitaColors.Purple, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text("Resumo da IA", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            if (!state.aiLoading) {
                IconButton(onClick = onGenerate) { Icon(Icons.Outlined.Refresh, contentDescription = "Gerar novamente", tint = LicitaColors.TextSecondary) }
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(targetState = Triple(state.aiLoading, summary, state.aiError), label = "summary") { (loading, text, error) ->
            when {
                loading -> AiSkeleton("Analisando a mensagem…")
                !text.isNullOrBlank() -> Text(text, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                error != null -> Column {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = LicitaColors.Red)
                    TextButton(onClick = onGenerate) { Text("Tentar novamente") }
                }
                else -> Text("Resumo ainda não gerado. Toque em atualizar para pedir à IA.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun AiSkeleton(label: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        SkeletonBox(Modifier.fillMaxWidth(), height = 12.dp)
        Spacer(Modifier.height(6.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.85f), height = 12.dp)
        Spacer(Modifier.height(6.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.6f), height = 12.dp)
    }
}

@Composable
private fun SuggestedReplyCard(state: MessageDetailUiState, onUse: () -> Unit) {
    val suggestion = state.message?.suggestedReply
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Resposta sugerida", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge("IA", Tone.INFO)
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.aiLoading -> AiSkeleton("Redigindo sugestão…")
            suggestion.isNullOrBlank() -> Text(
                "Sem sugestão disponível. Você pode redigir a resposta manualmente abaixo.",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
            )
            else -> {
                SelectionContainer {
                    Text(suggestion, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
                }
                AnimatedVisibility(state.canReply && !state.sent && state.draft.trim() != suggestion.trim()) {
                    TextButton(onClick = onUse) { Text("Usar esta sugestão no editor") }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Sugestão gerada por IA: revise sempre antes de aprovar. Nada é enviado sem a sua confirmação.",
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )
    }
}

@Composable
private fun ReplyEditor(state: MessageDetailUiState, viewModel: MessageDetailViewModel, onReplyInPortal: () -> Unit) {
    val message = state.message ?: return
    LicitaCard(Modifier.fillMaxWidth(), accent = if (state.sent) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sua resposta", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            StatusBadge(message.replyStatus.label, message.replyStatus.tone())
        }
        Spacer(Modifier.height(10.dp))

        if (state.sent) {
            SelectionContainer {
                Text(message.replyDraft.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Respondida em ${Formatters.dateTime(message.repliedAt)}.",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.GreenBright,
            )
            return@LicitaCard
        }

        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::setDraft,
            enabled = state.canReply && !state.busy,
            placeholder = { Text("Escreva a resposta ao pregoeiro…") },
            minLines = 5, maxLines = 14,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            supportingText = { Text("${state.draft.length}/4000") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (!state.canReply) {
            Spacer(Modifier.height(6.dp))
            AlertBanner(
                "Ações desabilitadas",
                "O perfil ${state.roleLabel} não tem a permissão \"Responder ao pregoeiro\". Peça a um usuário do perfil Licitações ou Administrador.",
                Tone.WARNING,
            )
        }
        AnimatedVisibility(state.approved && state.dirty) {
            Text(
                "O texto foi alterado depois da aprovação. Aprove novamente para liberar o envio.",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.Yellow, modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        ButtonRow {
            SecondaryButton(
                "Salvar rascunho", viewModel::saveDraft, Modifier.weight(1f),
                enabled = state.canReply && !state.busy && state.dirty && state.draft.isNotBlank(), icon = Icons.Outlined.Save, tone = Tone.NEUTRAL,
            )
            PrimaryButton(
                if (state.approved && !state.dirty) "Aprovada" else "Aprovar",
                viewModel::approve, Modifier.weight(1f),
                enabled = state.canReply && state.draft.isNotBlank() && !(state.approved && !state.dirty),
                loading = state.busy, icon = Icons.Outlined.CheckCircle,
            )
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            "Copiar e responder no portal", onReplyInPortal, Modifier.fillMaxWidth(),
            enabled = state.canSend, icon = Icons.AutoMirrored.Outlined.OpenInNew, tone = Tone.SUCCESS,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !state.canReply -> "Seu perfil não pode responder ao pregoeiro."
                state.canSend -> "A resposta aprovada é copiada e o portal abre no navegador interno: cole no chat do pregoeiro e envie por lá."
                else -> "Fluxo: revisar → aprovar → responder no portal. O botão é liberado após a aprovação."
            },
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )
    }
}
