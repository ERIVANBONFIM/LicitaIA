package com.licitaia.feature.tender

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.edital.EditalBaseEntry
import com.licitaia.domain.edital.EditalDocumentBase
import com.licitaia.domain.edital.EditalQuestion
import com.licitaia.domain.edital.EditalQuestionPrompt
import com.licitaia.domain.edital.EditalQuestionStatus
import com.licitaia.domain.model.EditalImportProgress
import com.licitaia.domain.model.EditalSource
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.pncpControlNumber
import com.licitaia.domain.repository.EditalQuestionRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditalQuestionsState(
    val loading: Boolean = true,
    val questions: List<EditalQuestion> = emptyList(),
    /** Ids sendo respondidos agora (os demais PENDENTE foram interrompidos). */
    val answering: Set<Long> = emptySet(),
    /** Pergunta nova enviada e ainda sem registro gravado. */
    val sending: Boolean = false,
    /** Download/importação do edital em andamento. */
    val importing: Boolean = false,
    val importProgress: EditalImportProgress? = null,
    val importError: String? = null,
    /** Documentos na base de perguntas (pelos marcadores do texto salvo); vazio = texto de arquivo único/antigo. */
    val documents: List<EditalBaseEntry> = emptyList(),
)

private data class QuestionFlags(val sending: Boolean = false, val importing: Boolean = false, val importError: String? = null)

/** "Pergunte ao edital" (aba Perguntas): histórico gravado + perguntas novas + obtenção do texto do edital. */
@HiltViewModel
class EditalQuestionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val questions: EditalQuestionRepository,
    private val tenders: TenderRepository,
) : ViewModel() {

    private val tenderId: Long = savedStateHandle.longArg("tenderId") ?: -1L
    private val flags = MutableStateFlow(QuestionFlags())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<EditalQuestionsState> = combine(
        questions.observeQuestions(tenderId).catch { emit(emptyList()) },
        questions.observeAnswering().catch { emit(emptySet()) },
        tenders.observeEditalImportProgress(tenderId).catch { emit(null) },
        tenders.observeOfficialEditalError(tenderId).catch { emit(null) },
        flags,
    ) { list, answering, progress, officialError, f ->
        EditalQuestionsState(
            loading = false, questions = list, answering = answering, sending = f.sending,
            importing = f.importing || progress != null, importProgress = progress, importError = f.importError ?: officialError,
        )
    }.combine(
        tenders.observeEditalText(tenderId).map { EditalDocumentBase.documentsIn(it) }.catch { emit(emptyList()) },
    ) { s, docs -> s.copy(documents = docs) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditalQuestionsState())

    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || flags.value.sending || tenderId <= 0) return
        flags.update { it.copy(sending = true) }
        viewModelScope.launch {
            // O registro é gravado logo no início: o histórico já mostra a pergunta "Respondendo…".
            val result = runSafely { questions.ask(tenderId, text) }
            flags.update { it.copy(sending = false) }
            result.exceptionOrNull()?.let { _messages.tryEmit(it.message ?: "A IA não respondeu.") }
        }
    }

    fun retry(id: Long) {
        viewModelScope.launch {
            runSafely { questions.retry(id) }.exceptionOrNull()?.let { _messages.tryEmit(it.message ?: "A IA não respondeu.") }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runSafely { questions.delete(id) }
                .onSuccess { _messages.tryEmit("Pergunta apagada.") }
                .onFailure { _messages.tryEmit(it.message ?: "Não foi possível apagar a pergunta.") }
        }
    }

    /**
     * Baixa TODOS os documentos oficiais da contratação no PNCP (edital, TR, anexos, ETP...) e remonta a base de
     * perguntas (também usada pela análise). Serve para o primeiro download e para "Atualizar documentos".
     */
    fun fetchOfficialEdital() = importEdital { tenders.fetchOfficialEdital(tenderId) }

    /** Importa o PDF escolhido pelo usuário (content://). */
    fun importPdf(uri: String) = importEdital { tenders.attachEdital(tenderId, EditalSource.Pdf(uri)) }

    private fun importEdital(block: suspend () -> Result<com.licitaia.domain.model.EditalImportResult>) {
        if (state.value.importing || tenderId <= 0) return
        flags.update { it.copy(importing = true, importError = null) }
        viewModelScope.launch {
            val result = runSafely(block)
            flags.update { it.copy(importing = false, importError = result.exceptionOrNull()?.message) }
            result.onSuccess { r ->
                _messages.tryEmit(
                    if (r.chars > 0) "Documentos prontos: ${r.chars} caracteres na base. Já pode perguntar." else "PDF sem texto reconhecível: cole o texto na tela da licitação.",
                )
            }
        }
    }

    private suspend fun <T> runSafely(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** Aba "Perguntas" da tela de análise do edital. */
@Composable
internal fun EditalQuestionsTab(tender: Tender, canAsk: Boolean, viewModel: EditalQuestionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }

    var draft by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModel.importPdf(uri.toString())
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Apagar pergunta?") },
            text = { Text("A pergunta e a resposta saem do histórico desta licitação.") },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = null; viewModel.delete(id) },
                    colors = ButtonDefaults.buttonColors(containerColor = LicitaColors.Red),
                ) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
            containerColor = LicitaColors.SurfaceElevated,
        )
    }

    val hasText = tender.hasEditalText
    val busy = state.sending
    val send: (String) -> Unit = { text ->
        if (text.isNotBlank() && canAsk && hasText && !busy) {
            viewModel.ask(text)
            draft = ""
        }
    }
    val listState = rememberLazyListState()
    // Nova pergunta/resposta: rola até o fim do histórico (o "chat" cresce para baixo).
    LaunchedEffect(state.questions.size, state.questions.lastOrNull()?.status) {
        if (state.questions.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasText) {
                item(key = "no-text") {
                    NoEditalTextCard(
                        tender = tender, state = state, canImport = canAsk,
                        onDownload = viewModel::fetchOfficialEdital,
                        onImportPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                        onOpenTender = { navigator.navigate(Routes.tender(tender.id)) },
                    )
                }
            } else {
                item(key = "base") {
                    DocumentBaseCard(
                        tender = tender, state = state, canUpdate = canAsk,
                        onUpdate = viewModel::fetchOfficialEdital,
                    )
                }
            }
            if (tender.pncpControlNumber != null) {
                item(key = "official-files") { OfficialFilesSection(tender) }
            }
            if (hasText && tender.editalScanned) {
                item(key = "ocr") {
                    AlertBanner(
                        "Texto obtido por OCR",
                        "O edital foi digitalizado; números, datas e valores podem ter erros de reconhecimento. Confira as respostas no PDF.",
                        Tone.WARNING,
                    )
                }
            }
            if (state.questions.isEmpty() && hasText) {
                item(key = "intro") {
                    LicitaCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.QuestionAnswer, contentDescription = null, tint = LicitaColors.Blue)
                            Spacer(Modifier.width(10.dp))
                            Text("Pergunte ao edital", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Pergunte o que quiser: a IA busca a resposta nos documentos da base acima (${tender.editalChars} caracteres), " +
                                "responde só com o que está escrito neles e cita documento e página. Quando a informação não estiver nos " +
                                "documentos, ela diz que não encontrou e sugere onde procurar. Todas as perguntas ficam gravadas aqui.",
                            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                        )
                    }
                }
            }
            items(state.questions, key = { it.id }) { question ->
                QuestionCard(
                    question = question,
                    answering = question.id in state.answering,
                    canAsk = canAsk && hasText,
                    onCopy = {
                        clipboard.setText(AnnotatedString(question.shareText))
                        navigator.showMessage("Resposta copiada.")
                    },
                    onRedo = { send(question.question) },
                    onRetry = { viewModel.retry(question.id) },
                    onDelete = { confirmDelete = question.id },
                )
            }
        }
        QuestionInput(
            draft = draft, onDraftChange = { draft = it.take(EditalQuestionPrompt.MAX_QUESTION_CHARS) },
            enabled = canAsk && hasText, busy = busy, onSend = { send(draft) },
            disabledHint = when {
                !canAsk -> "Seu perfil não pode fazer perguntas ao edital (permissão de analisar editais)."
                !hasText -> "Obtenha o texto do edital para perguntar."
                else -> null
            },
        )
    }
}

@Composable
private fun NoEditalTextCard(
    tender: Tender,
    state: EditalQuestionsState,
    canImport: Boolean,
    onDownload: () -> Unit,
    onImportPdf: () -> Unit,
    onOpenTender: () -> Unit,
) {
    LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
        Text("Sem o texto do edital", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            if (tender.editalScanned) {
                "O PDF importado é uma imagem sem texto reconhecido. Reconheça o texto (OCR) ou cole o texto na tela da licitação."
            } else {
                "As respostas saem do texto do edital. Baixe o edital oficial ou importe o PDF para começar a perguntar."
            },
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        if (state.importing) {
            val progress = state.importProgress
            Spacer(Modifier.height(10.dp))
            AlertBanner(
                when (progress?.stage) {
                    EditalImportProgress.Stage.BAIXANDO -> "Baixando o edital…"
                    EditalImportProgress.Stage.OCR -> {
                        val total = progress?.totalPages ?: 0
                        if (total > 0) "OCR página ${progress?.page ?: 0} de $total" else "Reconhecendo texto (OCR)…"
                    }
                    EditalImportProgress.Stage.EXTRAINDO -> "Extraindo texto…"
                    else -> "Importando o edital…"
                },
                "Você pode sair da tela: o processo continua e as perguntas são liberadas ao terminar.",
                Tone.INFO, pulsing = true,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
        }
        val error = state.importError
        if (error != null && !state.importing) {
            Spacer(Modifier.height(10.dp))
            AlertBanner("Não foi possível obter o edital", error, Tone.WARNING)
        }
        Spacer(Modifier.height(12.dp))
        if (tender.pncpControlNumber != null && tender.editalPdfPath == null) {
            PrimaryButton(
                if (state.importing) "Baixando…" else "Baixar documentos oficiais (PNCP)", onDownload, Modifier.fillMaxWidth(),
                enabled = canImport && !state.importing, loading = state.importing, icon = Icons.Outlined.CloudDownload,
            )
            Spacer(Modifier.height(8.dp))
        }
        ButtonRow {
            SecondaryButton("Importar PDF", onImportPdf, Modifier.weight(1f), enabled = canImport && !state.importing, icon = Icons.Outlined.UploadFile)
            SecondaryButton("Licitação", onOpenTender, Modifier.weight(1f), tone = Tone.NEUTRAL)
        }
        if (!canImport) {
            Spacer(Modifier.height(6.dp))
            Text("Seu perfil não pode importar editais.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
        }
    }
}

/** Documentos que estão na base de perguntas + "Atualizar documentos" (baixa de novo tudo o que o PNCP publicou). */
@Composable
private fun DocumentBaseCard(
    tender: Tender,
    state: EditalQuestionsState,
    canUpdate: Boolean,
    onUpdate: () -> Unit,
) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = LicitaColors.Blue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Documentos na base de perguntas", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            if (state.documents.isNotEmpty()) StatusBadge("${state.documents.size}", Tone.INFO)
        }
        Spacer(Modifier.height(6.dp))
        if (state.documents.isEmpty()) {
            Text(
                "Texto do edital em arquivo único (${tender.editalChars} caracteres)." +
                    if (tender.pncpControlNumber != null) " Toque em \"Atualizar documentos\" para incluir tudo o que foi publicado no PNCP (termo de referência, anexos, ETP…)." else "",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
            )
        } else {
            // Cobertura: "Edital 56 pág ✓ · Termo de Referência 23 pág ✓ · 3 anexos".
            Text(
                EditalDocumentBase.coverage(state.documents),
                style = MaterialTheme.typography.labelLarge, color = LicitaColors.GreenBright,
            )
            Spacer(Modifier.height(4.dp))
            state.documents.forEach { doc ->
                Text(
                    "• ${doc.displayName} · ${doc.pagesIncluded} pág.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (tender.editalChars <= com.licitaia.domain.edital.EditalExcerptSelector.FULL_BASE_MAX_CHARS)
                    "A IA lê a base INTEIRA a cada pergunta (${tender.editalChars} caracteres)."
                else "Base grande (${tender.editalChars} caracteres): a IA recebe as seções inteiras que casam com a pergunta e trechos de todos os documentos.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
        }
        if (state.importing) {
            val progress = state.importProgress
            Spacer(Modifier.height(8.dp))
            AlertBanner(
                when (progress?.stage) {
                    EditalImportProgress.Stage.BAIXANDO -> "Baixando os documentos…"
                    EditalImportProgress.Stage.OCR -> {
                        val total = progress?.totalPages ?: 0
                        if (total > 0) "OCR página ${progress?.page ?: 0} de $total" else "Reconhecendo texto (OCR)…"
                    }
                    EditalImportProgress.Stage.EXTRAINDO -> "Extraindo texto…"
                    else -> "Atualizando a base…"
                },
                "Você pode sair da tela: o processo continua em segundo plano.",
                Tone.INFO, pulsing = true,
            )
        }
        val error = state.importError
        if (error != null && !state.importing) {
            Spacer(Modifier.height(8.dp))
            AlertBanner("Não foi possível atualizar os documentos", error, Tone.WARNING)
        }
        if (tender.pncpControlNumber != null) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                if (state.importing) "Atualizando…" else "Atualizar documentos", onUpdate, Modifier.fillMaxWidth(),
                enabled = canUpdate && !state.importing, icon = Icons.Outlined.CloudDownload,
            )
        }
    }
}

@Composable
private fun QuestionCard(
    question: EditalQuestion,
    answering: Boolean,
    canAsk: Boolean,
    onCopy: () -> Unit,
    onRedo: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val pending = question.status == EditalQuestionStatus.PENDENTE
    // PENDENTE sem resposta em andamento = interrompida (app fechado no meio): tratada como erro.
    val failed = question.status == EditalQuestionStatus.ERRO || (pending && !answering)
    LicitaCard(Modifier.fillMaxWidth(), accent = if (failed) LicitaColors.Red else null) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                question.question, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                pending && answering -> StatusBadge("Respondendo", Tone.INFO, pulsing = true)
                failed -> StatusBadge(if (pending) "Interrompida" else "Erro", Tone.DANGER)
                question.unsourced -> StatusBadge("Sem fonte — confira", Tone.WARNING)
                else -> StatusBadge("Respondida", Tone.SUCCESS)
            }
        }
        Text(
            buildString {
                append(Formatters.dateTime(question.createdAt))
                if (question.provider.isNotBlank()) append(" · ").append(question.provider)
                question.model?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            },
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )
        Spacer(Modifier.height(10.dp))
        when {
            pending && answering -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LicitaColors.Blue)
                    Spacer(Modifier.width(10.dp))
                    Text("Lendo o edital e respondendo…", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
            }
            failed -> {
                Text(
                    question.answer.ifBlank { "A resposta foi interrompida antes de terminar." },
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.RedBright,
                )
                Spacer(Modifier.height(10.dp))
                ButtonRow {
                    SecondaryButton("Tentar de novo", onRetry, Modifier.weight(1f), enabled = canAsk, icon = Icons.Outlined.Refresh, tone = Tone.WARNING)
                    IconButton(onClick = onDelete, enabled = canAsk) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Apagar", tint = LicitaColors.TextMuted) }
                }
            }
            else -> {
                SelectionContainer {
                    Text(question.answer, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                }
                if (question.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    question.sources.forEach { source ->
                        Text("Fonte: $source", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Blue)
                    }
                } else if (question.unsourced) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sem fonte — confira: a resposta não citou documento/página. Verifique no PDF antes de usar.",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copiar")
                    }
                    TextButton(onClick = onRedo, enabled = canAsk) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refazer")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete, enabled = canAsk) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Apagar", tint = LicitaColors.TextMuted) }
                }
            }
        }
    }
}

@Composable
private fun QuestionInput(
    draft: String,
    onDraftChange: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    disabledHint: String?,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        if (disabledHint != null) {
            Text(
                disabledHint, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = onDraftChange, enabled = enabled,
                placeholder = { Text("Pergunte o que quiser sobre os documentos…") },
                maxLines = 4, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = LicitaColors.Blue)
            } else {
                IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Perguntar", tint = if (enabled && draft.isNotBlank()) LicitaColors.Blue else LicitaColors.TextMuted)
                }
            }
        }
    }
}
