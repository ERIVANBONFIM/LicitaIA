package com.licitaia.feature.tender

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.PulsingDot
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.live.LiveSessionManager
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.EditalImportProgress
import com.licitaia.domain.model.EditalImportResult
import com.licitaia.domain.model.EditalSource
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.model.pncpControlNumber
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompetitionRepository
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TenderDetailState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val tender: Tender? = null,
    val analysis: TenderAnalysis? = null,
    val proposals: List<Proposal> = emptyList(),
    val role: UserRole? = null,
    /** Importação/extração do edital em andamento. */
    val importing: Boolean = false,
    /** Análise de IA disparada a partir desta tela. */
    val analyzing: Boolean = false,
    /** Último erro do card "Edital" (import/colagem/análise). */
    val editalError: String? = null,
    /** Etapa atual da importação/OCR (página X de N) enquanto [importing]. */
    val importProgress: EditalImportProgress? = null,
    /** Resultado desta licitação já registrado no histórico de concorrência. */
    val resultRegistered: Boolean = false,
    /** Gravação do resultado (status + concorrência) em andamento. */
    val savingResult: Boolean = false,
    /** Sessão assistida ABERTA vinculada a esta licitação (Pregões ao Vivo), se houver. */
    val liveSessionId: String? = null,
    /** Motivo da última falha ao baixar o edital oficial do PNCP (inclusive o disparo automático do "Tenho Interesse"). */
    val officialEditalError: String? = null,
    /** Provedor de IA que o app usa agora (MOCK = nenhum provedor real disponível). */
    val activeAi: AiProviderType = AiProviderType.MOCK,
) {
    val canAnalyze: Boolean get() = role?.let { Rbac.can(it, Permission.ANALISAR) } ?: false
    /** Pode criar/operar sessões assistidas (mesma regra do FAB "Acompanhar pregão" em Pregões ao Vivo). */
    val canOperateLive: Boolean get() = role?.let { Rbac.can(it, Permission.OPERAR_SESSOES) } ?: false
}

private data class DetailFlags(
    val importing: Boolean = false,
    val analyzing: Boolean = false,
    val editalError: String? = null,
    val savingResult: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TenderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    auth: AuthRepository,
    private val tenders: TenderRepository,
    proposals: ProposalRepository,
    private val competition: CompetitionRepository,
    private val liveSessions: LiveSessionManager,
    aiConfig: AiConfigRepository,
) : ViewModel() {

    private val tenderId: Long = savedStateHandle.longArg("tenderId") ?: -1L

    init {
        // Garante que as sessões assistidas da empresa estejam carregadas (idempotente) para o atalho "Abrir acompanhamento".
        viewModelScope.launch {
            auth.session.filterNotNull().map { it.activeCompany.id }.distinctUntilChanged().collect { companyId ->
                runCatching { liveSessions.restoreOrSeed(companyId) }
            }
        }
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val flags = MutableStateFlow(DetailFlags())

    private val remote = auth.session.flatMapLatest { session ->
        if (session == null || tenderId <= 0) {
            flowOf(TenderDetailState(loading = false, notFound = true))
        } else {
            combine(
                tenders.observeTender(tenderId),
                tenders.observeAnalysis(tenderId).catch { emit(null) },
                proposals.observeProposals(tenderId).catch { emit(emptyList()) },
                competition.observeRecords(session.activeCompany.id).catch { emit(emptyList()) },
            ) { tender, analysis, versions, records ->
                if (tender == null || tender.companyId != session.activeCompany.id) {
                    TenderDetailState(loading = false, notFound = true)
                } else {
                    TenderDetailState(
                        loading = false, tender = tender, analysis = analysis, proposals = versions, role = session.user.role,
                        resultRegistered = records.any { it.portal == tender.portal && it.tenderNumber == tender.number },
                    )
                }
            }.catch { emit(TenderDetailState(loading = false, notFound = true)) }
        }
    }

    private val progress = tenders.observeEditalImportProgress(tenderId).catch { emit(null) }

    /** Id da sessão assistida aberta para esta licitação (a mais recente, se houver mais de uma). */
    private val liveSessionId = liveSessions.sessions
        .map { list -> list.filter { it.tenderId == tenderId && it.isOpen }.maxByOrNull { it.startedAt }?.id }
        .distinctUntilChanged()
        .catch { emit(null) }

    private val officialError = tenders.observeOfficialEditalError(tenderId).catch { emit(null) }

    private val activeAi = aiConfig.observeEffective().catch { emit(AiProviderType.MOCK) }

    val state: StateFlow<TenderDetailState> = combine(remote, flags, progress, liveSessionId, officialError) { s, f, p, live, official ->
        s.copy(
            // Uma importação/OCR que continua em segundo plano (após sair e voltar à tela) também conta como "importando".
            importing = f.importing || p != null, analyzing = f.analyzing, editalError = f.editalError,
            importProgress = p, savingResult = f.savingResult, liveSessionId = live, officialEditalError = official,
        )
    }.combine(activeAi) { s, ai -> s.copy(activeAi = ai) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderDetailState())

    /** Baixa o edital oficial publicado no PNCP (com anexos relevantes) e extrai o texto; o erro fica no card. */
    fun fetchOfficialEdital() {
        if (state.value.importing || tenderId <= 0) return
        flags.update { it.copy(importing = true, editalError = null) }
        viewModelScope.launch {
            val result = try {
                tenders.fetchOfficialEdital(tenderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { r ->
                    flags.update { it.copy(importing = false, editalError = if (r.scanned && !r.ocr) SCANNED_NO_TEXT else null) }
                    _messages.tryEmit(
                        when {
                            r.ocr -> "Edital oficial baixado; texto obtido por OCR (${r.pages ?: 0} página(s), ${r.chars} caracteres) — confira trechos importantes."
                            r.scanned -> SCANNED_NO_TEXT
                            else -> "Edital oficial baixado: ${r.pages ?: 0} página(s), ${r.chars} caracteres. Pronto para analisar com IA."
                        },
                    )
                },
                // O motivo aparece no card via observeOfficialEditalError ("Não foi possível baixar o edital… — Tentar de novo").
                onFailure = { flags.update { it.copy(importing = false) } },
            )
        }
    }

    private companion object {
        const val SCANNED_NO_TEXT = "PDF sem texto (escaneado): o OCR não reconheceu texto suficiente — cole o texto do edital manualmente."
    }

    fun toggleChecklist(index: Int) {
        viewModelScope.launch {
            try {
                tenders.toggleChecklistItem(tenderId, index)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.tryEmit("Não foi possível atualizar o checklist.")
            }
        }
    }

    /** Importa o PDF escolhido (content://) e extrai o texto; PDF escaneado passa pelo OCR local automaticamente. */
    fun importPdf(uri: String) = attach(EditalSource.Pdf(uri)) { result ->
        when {
            result.ocr -> "Texto obtido por OCR (${result.pages ?: 0} página(s), ${result.chars} caracteres) — confira trechos importantes antes de analisar."
            result.scanned -> "PDF sem texto (escaneado): o OCR não reconheceu texto suficiente — cole o texto do edital manualmente."
            else -> "Edital importado: ${result.pages ?: 0} página(s), ${result.chars} caracteres. Pronto para analisar com IA."
        }
    }

    /** OCR manual (ML Kit, no aparelho) sobre o PDF já importado. */
    fun recognizeText() = attach(EditalSource.Ocr) { result ->
        "Texto obtido por OCR (${result.pages ?: 0} página(s), ${result.chars} caracteres) — confira trechos importantes antes de analisar."
    }

    /** Guarda o texto colado pelo usuário como texto do edital. */
    fun pasteText(text: String) = attach(EditalSource.Text(text)) { result -> "Texto do edital salvo (${result.chars} caracteres)." }

    private fun attach(source: EditalSource, message: (EditalImportResult) -> String) {
        if (state.value.importing || tenderId <= 0) return
        flags.update { it.copy(importing = true, editalError = null) }
        viewModelScope.launch {
            val result = try {
                tenders.attachEdital(tenderId, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { r ->
                    // Texto reconhecido por OCR fica com aviso permanente no card; PDF escaneado sem texto também.
                    flags.update { it.copy(importing = false, editalError = if (r.scanned && !r.ocr) message(r) else null) }
                    _messages.tryEmit(message(r))
                },
                onFailure = { e ->
                    flags.update { it.copy(importing = false, editalError = e.message?.takeIf(String::isNotBlank) ?: "Não foi possível importar o edital.") }
                },
            )
        }
    }

    /**
     * Registra o resultado do pregão: grava o [CompetitionRecord] (histórico de concorrência) e, se
     * necessário, muda o status para VENCIDA/PERDIDA. Falha na gravação não altera o status.
     */
    fun registerResult(form: TenderResultForm) {
        val tender = state.value.tender ?: return
        if (flags.value.savingResult) return
        flags.update { it.copy(savingResult = true) }
        viewModelScope.launch {
            try {
                val record = form.toRecord(tender).getOrThrow()
                competition.insert(record)
                val target = if (form.won) TenderStatus.VENCIDA else TenderStatus.PERDIDA
                if (tender.status != target) tenders.updateStatus(tender.id, target)
                _messages.tryEmit(if (form.won) "Vitória registrada na análise de concorrência." else "Derrota registrada na análise de concorrência.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message?.takeIf(String::isNotBlank) ?: "Não foi possível registrar o resultado.")
            } finally {
                flags.update { it.copy(savingResult = false) }
            }
        }
    }

    /** Só muda o status (sem gravar na concorrência). */
    fun setOutcome(won: Boolean) {
        val tender = state.value.tender ?: return
        if (flags.value.savingResult) return
        flags.update { it.copy(savingResult = true) }
        viewModelScope.launch {
            try {
                tenders.updateStatus(tender.id, if (won) TenderStatus.VENCIDA else TenderStatus.PERDIDA)
                _messages.tryEmit("Status atualizado para ${if (won) "Vencida" else "Perdida"}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message?.takeIf(String::isNotBlank) ?: "Não foi possível alterar o status.")
            } finally {
                flags.update { it.copy(savingResult = false) }
            }
        }
    }

    /** Analisa com o provedor de IA ativo usando o texto real do edital. */
    fun analyze() {
        if (flags.value.analyzing || tenderId <= 0) return
        flags.update { it.copy(analyzing = true, editalError = null) }
        viewModelScope.launch {
            val result = try {
                tenders.analyze(tenderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { a ->
                    flags.update { it.copy(analyzing = false) }
                    _messages.tryEmit(
                        if (a.heuristicOnly) "Análise heurística concluída (sem IA): ${a.recommendation.label}" else "Análise concluída: ${a.recommendation.label}",
                    )
                },
                onFailure = { e ->
                    flags.update { it.copy(analyzing = false, editalError = e.message?.takeIf(String::isNotBlank) ?: "A IA não conseguiu concluir a análise.") }
                },
            )
        }
    }
}

@Composable
fun TenderDetailScreen(viewModel: TenderDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }

    val tender = state.tender
    LicitaScaffold(title = tender?.number ?: "Licitação", showBack = true) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            state.notFound || tender == null -> ErrorState(
                "Esta licitação não existe ou pertence a outra empresa.", Modifier.padding(padding),
                title = "Licitação não encontrada",
            )
            else -> TenderDetailContent(state, tender, padding, viewModel)
        }
    }
}

@Composable
private fun TenderDetailContent(state: TenderDetailState, tender: Tender, padding: PaddingValues, viewModel: TenderDetailViewModel) {
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val analysis = state.analysis
    val onToggle: (Int) -> Unit = viewModel::toggleChecklist
    var pasteOpen by remember { mutableStateOf(false) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModel.importPdf(uri.toString())
    }
    if (pasteOpen) {
        PasteEditalDialog(
            onDismiss = { pasteOpen = false },
            onConfirm = { text ->
                pasteOpen = false
                viewModel.pasteText(text)
            },
        )
    }
    // null = fechado; true/false = resultado pré-selecionado ("Vencemos"/"Perdemos").
    var resultDialog by remember { mutableStateOf<Boolean?>(null) }
    val decided = tender.status == TenderStatus.VENCIDA || tender.status == TenderStatus.PERDIDA
    resultDialog?.let { won ->
        TenderResultDialog(
            tender = tender,
            initialWon = won,
            suggestedBid = state.proposals.firstOrNull()?.totalValue,
            allowToggleOutcome = !decided,
            onDismiss = { resultDialog = null },
            onConfirm = { form ->
                resultDialog = null
                viewModel.registerResult(form)
            },
            onSkip = if (decided) null else {
                { chosen ->
                    resultDialog = null
                    viewModel.setOutcome(chosen)
                }
            },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            GradientCard(Modifier.fillMaxWidth()) {
                TenderHeadline(tender, analysis)
                // Situação oficial (suspensa/revogada...) e UASG à vista no cabeçalho.
                val unit = com.licitaia.domain.model.UasgCode.label(tender.portal, tender.uasg)
                if (tender.officialSituation != null || unit != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.licitaia.core.ui.components.OfficialSituationBadge(tender.officialSituation)
                        unit?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(tender.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                Spacer(Modifier.height(14.dp))
                FlowTimeline(completedSteps(tender, analysis, state.proposals))
            }
        }
        val deadlineTone = deadlineTone(tender.proposalDeadline)
        if (deadlineTone == Tone.DANGER || deadlineTone == Tone.WARNING) {
            item(key = "deadline") {
                AlertBanner(deadlineLabel(tender.proposalDeadline), "Propostas até ${Formatters.dateTime(tender.proposalDeadline)}", deadlineTone)
            }
        }
        item(key = "edital") {
            EditalCard(
                tender = tender,
                state = state,
                onImportPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                onDownloadOfficial = viewModel::fetchOfficialEdital,
                onPaste = { pasteOpen = true },
                onOcr = viewModel::recognizeText,
                onOpenPdf = {
                    val path = tender.editalPdfPath
                    if (path == null || !openPdf(context, path)) navigator.showMessage("Não foi possível abrir o PDF (arquivo ausente ou nenhum leitor instalado).")
                },
                onAnalyze = viewModel::analyze,
            )
        }
        if (tender.status.isParticipation()) {
            item(key = "result") {
                ResultCard(tender, state, onRegister = { won -> resultDialog = won })
            }
        }
        item(key = "recommendation") {
            if (analysis == null) {
                val waiting = state.analyzing || tender.status == TenderStatus.EM_ANALISE || (!tender.isManual && tender.status == TenderStatus.INTERESSE)
                LicitaCard(Modifier.fillMaxWidth(), onClick = { navigator.navigate(Routes.tenderAnalysis(tender.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (waiting) {
                            PulsingDot(LicitaColors.Blue, size = 10.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            if (waiting) {
                                Text("IA analisando o edital…", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                Text("A recomendação e a faixa de preço aparecem aqui ao final da análise.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                            } else {
                                Text("Sem análise", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                                Text(
                                    if (tender.hasEditalText) "Toque em \"Analisar com IA\" no card Edital." else "Importe o PDF do edital (ou cole o texto) e analise com IA.",
                                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                                )
                            }
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = LicitaColors.TextMuted)
                    }
                }
            } else {
                if (analysis.heuristicOnly) {
                    HeuristicAnalysisBanner(
                        activeAi = state.activeAi, canAnalyze = state.canAnalyze, analyzing = state.analyzing,
                        onReanalyze = viewModel::analyze, onConfigure = { navigator.navigate(Routes.AI_SETTINGS) },
                        detail = "Esta recomendação foi calculada por regras locais (sem modelo de IA); nada foi lido do edital.",
                    )
                    Spacer(Modifier.height(12.dp))
                }
                LicitaCard(Modifier.fillMaxWidth(), accent = analysis.recommendation.tone().color(), onClick = { navigator.navigate(Routes.tenderWorth(tender.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (analysis.heuristicOnly) "Recomendação heurística (sem IA)" else "Recomendação da IA", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                            Text(
                                analysis.recommendation.label, style = MaterialTheme.typography.headlineSmall,
                                color = analysis.recommendation.tone().color(), fontWeight = FontWeight.Bold,
                            )
                        }
                        StatusBadge("Score ${analysis.fit.overall}", analysis.recommendation.tone())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(analysis.summary, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    Text("Faixa de preço sugerida", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        PriceCell("Mínimo", analysis.priceRange.min, LicitaColors.TextPrimary, Modifier.weight(1f))
                        PriceCell("Sugerido", analysis.priceRange.suggested, LicitaColors.GreenBright, Modifier.weight(1.2f))
                        PriceCell("Máximo", analysis.priceRange.max, LicitaColors.TextPrimary, Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "data") {
            LicitaCard(Modifier.fillMaxWidth()) {
                Text("Dados principais", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                InfoRow("Portal", tender.portal.displayName)
                InfoRow("Número", tender.number)
                InfoRow("Órgão", tender.agency)
                com.licitaia.domain.model.UasgCode.label(tender.portal, tender.uasg)?.let { label ->
                    InfoRow(if (tender.portal == com.licitaia.domain.model.Portal.COMPRAS_GOV) "UASG" else "Cód. unidade", label.substringAfterLast(' '))
                }
                tender.officialSituation?.let { InfoRow("Situação oficial", it.label, valueColor = LicitaColors.Red) }
                InfoRow("Modalidade", tender.modality.label)
                InfoRow("Segmento", tender.segment.label)
                InfoRow("Local", "${tender.city}/${tender.uf}")
                InfoRow("Valor estimado", Formatters.brl(tender.estimatedValue), valueColor = LicitaColors.GreenBright)
                val (editalLabel, editalColor) = when {
                    tender.editalPdfPath != null -> "baixado (${tender.editalPages ?: 0} páginas)" to LicitaColors.GreenBright
                    tender.hasEditalText -> "texto colado (${tender.editalChars} caracteres)" to LicitaColors.GreenBright
                    tender.pncpControlNumber != null || tender.editalRegistered -> "link disponível" to LicitaColors.Yellow
                    else -> "não disponível" to LicitaColors.TextMuted
                }
                InfoRow("Edital", editalLabel, valueColor = editalColor)
            }
        }
        item(key = "dates") {
            LicitaCard(Modifier.fillMaxWidth()) {
                Text("Datas", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                InfoRow("Limite de propostas", Formatters.dateTime(tender.proposalDeadline), valueColor = deadlineTone(tender.proposalDeadline).let { if (it == Tone.INFO || it == Tone.NEUTRAL) LicitaColors.TextPrimary else it.color() })
                InfoRow("Sessão pública", Formatters.dateTime(tender.sessionAt))
                if (analysis != null) {
                    InfoRow("Abertura", Formatters.dateTime(analysis.extracted.openingAt))
                    InfoRow("Prazo de instalação", analysis.extracted.installationDeadline)
                }
                InfoRow("Interesse registrado", Formatters.dateTime(tender.createdAt))
            }
        }
        if (analysis != null && analysis.checklist.isNotEmpty()) {
            item(key = "checklist-h") {
                val done = analysis.checklist.count { it.done }
                SectionHeader("Checklist de pendências · $done/${analysis.checklist.size}")
            }
            item(key = "checklist") {
                LicitaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    analysis.checklist.forEachIndexed { index, item ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onToggle(index) }.padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = item.done, onCheckedChange = { onToggle(index) })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title, style = MaterialTheme.typography.bodyMedium,
                                    color = if (item.done) LicitaColors.TextMuted else LicitaColors.TextPrimary,
                                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                )
                                item.relatedDocument?.let {
                                    Text(it.label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                                }
                            }
                            if (item.critical && !item.done) StatusBadge("Crítico", Tone.DANGER)
                        }
                    }
                }
            }
        }
        item(key = "actions-h") { SectionHeader("Próximos passos") }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(Icons.Outlined.Analytics, LicitaColors.Blue, "Análise do edital", if (analysis == null) "Acompanhar a análise da IA" else "Exigências, documentos e riscos extraídos") {
                    navigator.navigate(Routes.tenderAnalysis(tender.id))
                }
                ActionTile(
                    Icons.Outlined.QuestionAnswer, LicitaColors.Purple, "Pergunte ao edital",
                    if (tender.hasEditalText) "Tire dúvidas com a IA citando o item do edital; histórico gravado" else "Obtenha o edital e pergunte o que quiser sobre ele",
                ) { navigator.navigate(Routes.tenderQuestions(tender.id)) }
                ActionTile(Icons.Outlined.Inventory2, LicitaColors.Green, "Ver itens", "Itens oficiais: quantidades, unidades e valores estimados") {
                    navigator.navigate(Routes.tenderItems(tender.id))
                }
                ActionTile(Icons.Outlined.Balance, LicitaColors.Yellow, "Vale a pena participar?", "Veredito executivo com todos os indicadores") {
                    navigator.navigate(Routes.tenderWorth(tender.id))
                }
                ActionTile(
                    Icons.Outlined.RequestQuote, LicitaColors.Green, "Proposta comercial",
                    if (state.proposals.isEmpty()) "Gerar com IA, revisar, aprovar e preparar envio" else "${state.proposals.size} versão(ões) · ${state.proposals.first().status.label}",
                ) { navigator.navigate(Routes.tenderProposal(tender.id)) }
                if (tender.portal == com.licitaia.domain.model.Portal.COMPRAS_GOV) {
                    ActionTile(Icons.Outlined.SmartToy, LicitaColors.Yellow, "Robô do Comprasnet", "Cadastrar a proposta no portal e armar o robô de lance") {
                        navigator.navigate("robotproposal/${tender.id}")
                    }
                }
                val liveId = state.liveSessionId
                when {
                    liveId != null -> ActionTile(Icons.Outlined.LiveTv, LicitaColors.Red, "Abrir acompanhamento", "Sessão assistida em andamento para esta licitação") {
                        navigator.navigate(Routes.liveSession(liveId))
                    }
                    state.canOperateLive -> ActionTile(Icons.Outlined.LiveTv, LicitaColors.Red, "Acompanhar pregão", "Abrir sessão assistida já vinculada a esta licitação") {
                        navigator.navigate(Routes.liveNew(tender.id))
                    }
                    else -> ActionTile(Icons.Outlined.LiveTv, LicitaColors.Red, "Pregões ao vivo", "Acompanhar as sessões assistidas da empresa") {
                        navigator.navigateTop(Routes.LIVE)
                    }
                }
                ActionTile(Icons.Outlined.Checklist, LicitaColors.Purple, "Licitações de interesse", "Voltar à lista") {
                    navigator.navigateTop(Routes.INTERESTS)
                }
            }
        }
    }
}

@Composable
private fun EditalCard(
    tender: Tender,
    state: TenderDetailState,
    onImportPdf: () -> Unit,
    onDownloadOfficial: () -> Unit,
    onPaste: () -> Unit,
    onOcr: () -> Unit,
    onOpenPdf: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val busy = state.importing || state.analyzing
    val canDownloadOfficial = tender.pncpControlNumber != null && tender.editalPdfPath == null
    val ocrText = tender.editalScanned && tender.hasEditalText
    val statusText = when {
        tender.editalScanned && !tender.hasEditalText -> "PDF escaneado (sem camada de texto) — reconheça o texto (OCR) ou cole manualmente"
        ocrText -> "${tender.editalPages ?: 0} página(s) · ${tender.editalChars} caracteres reconhecidos por OCR"
        tender.hasEditalText && tender.editalPages != null -> "${tender.editalPages} página(s) · ${tender.editalChars} caracteres extraídos"
        tender.hasEditalText -> "Texto colado · ${tender.editalChars} caracteres"
        else -> "Nenhum edital anexado"
    }
    val statusTone = when {
        tender.hasEditalText -> Tone.SUCCESS
        tender.editalScanned -> Tone.WARNING
        else -> Tone.NEUTRAL
    }
    LicitaCard(Modifier.fillMaxWidth(), accent = if (tender.hasEditalText) null else LicitaColors.Yellow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.PictureAsPdf, if (tender.hasEditalText) LicitaColors.Green else LicitaColors.Yellow)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Edital", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            StatusBadge(if (ocrText) "OCR" else if (tender.hasEditalText) "Texto OK" else if (tender.editalScanned) "Escaneado" else "Pendente", statusTone)
        }
        if (tender.editalPdfPath != null) {
            Spacer(Modifier.height(8.dp))
            InfoRow("Arquivo", "${tender.id}.pdf" + (tender.editalPages?.let { " · $it pág." } ?: ""))
        }
        if (ocrText && !busy) {
            Spacer(Modifier.height(10.dp))
            AlertBanner(
                "Texto obtido por OCR — confira trechos importantes",
                "O PDF é uma imagem digitalizada; o texto foi reconhecido no aparelho e pode conter erros em números, datas e valores. Abra o PDF para conferir antes de decidir.",
                Tone.WARNING,
            )
        }
        if (state.importing) {
            val progress = state.importProgress
            Spacer(Modifier.height(10.dp))
            when (progress?.stage) {
                EditalImportProgress.Stage.OCR -> {
                    val label = if (progress.totalPages > 0) "OCR página ${progress.page} de ${progress.totalPages}" else "Reconhecendo texto (OCR)…"
                    AlertBanner(
                        label,
                        "OCR no aparelho (sem enviar o PDF para fora). Páginas digitalizadas levam alguns segundos cada; você pode sair da tela que o processo continua.",
                        Tone.INFO, pulsing = true,
                    )
                    if (progress.totalPages > 0) {
                        LinearProgressIndicator(
                            progress = { progress.page.toFloat() / progress.totalPages },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
                    }
                }
                EditalImportProgress.Stage.BAIXANDO -> {
                    AlertBanner(
                        "Baixando…",
                        "Baixando o edital oficial (e anexos relevantes) publicado no PNCP. Você pode sair da tela que o download continua.",
                        Tone.INFO, pulsing = true,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
                }
                EditalImportProgress.Stage.EXTRAINDO -> {
                    AlertBanner("Extraindo texto…", "Lendo o texto do PDF do edital. Editais grandes podem levar alguns segundos.", Tone.INFO, pulsing = true)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
                }
                else -> {
                    AlertBanner(
                        "Importando o edital…",
                        progress?.stage?.label?.let { "$it. Editais grandes podem levar alguns segundos." }
                            ?: "Copiando o PDF e extraindo o texto. Editais grandes podem levar alguns segundos.",
                        Tone.INFO, pulsing = true,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
                }
            }
        }
        if (state.analyzing) {
            Spacer(Modifier.height(10.dp))
            AlertBanner("IA analisando o edital…", "O texto real do edital foi enviado ao provedor configurado.", Tone.INFO, pulsing = true)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
        }
        val error = state.editalError
        if (error != null && !busy) {
            Spacer(Modifier.height(10.dp))
            AlertBanner(if (tender.editalScanned && !tender.hasEditalText) "PDF sem texto" else "Falha", error, if (tender.editalScanned && !tender.hasEditalText) Tone.WARNING else Tone.DANGER)
        }
        val officialError = state.officialEditalError
        if (officialError != null && !busy && tender.pncpControlNumber != null) {
            Spacer(Modifier.height(10.dp))
            AlertBanner(
                if (tender.editalPdfPath == null) "Não foi possível baixar o edital" else "Falha ao processar o edital oficial",
                officialError,
                Tone.WARNING,
                actionLabel = if (state.canAnalyze) "Tentar de novo" else null,
                onAction = if (state.canAnalyze) onDownloadOfficial else null,
            )
        }
        if (canDownloadOfficial) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                if (state.importing) "Baixando…" else "Baixar edital oficial (PNCP)",
                onDownloadOfficial, Modifier.fillMaxWidth(),
                enabled = state.canAnalyze && !busy, loading = state.importing, icon = Icons.Outlined.CloudDownload,
            )
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            SecondaryButton(
                if (tender.editalPdfPath == null) "Importar PDF" else "Trocar PDF", onImportPdf, Modifier.weight(1f),
                enabled = state.canAnalyze && !busy, icon = Icons.Outlined.UploadFile,
            )
            SecondaryButton(
                "Colar texto", onPaste, Modifier.weight(1f),
                enabled = state.canAnalyze && !busy, icon = Icons.Outlined.ContentPaste, tone = Tone.NEUTRAL,
            )
        }
        if (tender.editalPdfPath != null) {
            Spacer(Modifier.height(8.dp))
            ButtonRow {
                SecondaryButton(
                    if (tender.editalScanned && !tender.hasEditalText) "Reconhecer texto (OCR)" else "Refazer OCR", onOcr, Modifier.weight(1f),
                    enabled = state.canAnalyze && !busy, icon = Icons.Outlined.DocumentScanner,
                    tone = if (tender.editalScanned && !tender.hasEditalText) Tone.WARNING else Tone.NEUTRAL,
                )
                SecondaryButton("Abrir PDF", onOpenPdf, Modifier.weight(1f), icon = Icons.Outlined.OpenInNew, tone = Tone.NEUTRAL)
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            if (state.analyzing) "Analisando…" else if (state.analysis == null) "Analisar com IA" else "Reanalisar com IA",
            onAnalyze, Modifier.fillMaxWidth(),
            enabled = state.canAnalyze && tender.hasEditalText && !busy, loading = state.analyzing, icon = Icons.Outlined.AutoAwesome,
        )
        if (!tender.hasEditalText) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (tender.pncpControlNumber != null) "A análise com IA exige o texto do edital. Baixe o edital oficial, importe o PDF ou cole o texto."
                else "A análise com IA exige o texto do edital. Importe o PDF ou cole o texto.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
        }
        if (!state.canAnalyze && state.role != null) {
            Spacer(Modifier.height(6.dp))
            Text("Seu perfil (${state.role.label}) não pode importar nem analisar editais.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
        }
    }
}

/**
 * Card "Resultado do pregão": muda o status para Vencida/Perdida abrindo o diálogo "Registrar
 * resultado" (que alimenta a análise de concorrência). Em licitações já decididas sem registro,
 * oferece registrar o resultado a posteriori.
 */
@Composable
private fun ResultCard(tender: Tender, state: TenderDetailState, onRegister: (won: Boolean) -> Unit) {
    val won = tender.status == TenderStatus.VENCIDA
    val lost = tender.status == TenderStatus.PERDIDA
    val decided = won || lost
    val accent = when {
        won -> LicitaColors.Green
        lost -> LicitaColors.Red
        else -> null
    }
    LicitaCard(Modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.EmojiEvents, accent ?: LicitaColors.Yellow)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Resultado do pregão", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text(
                    when {
                        decided && state.resultRegistered -> "Resultado registrado na análise de concorrência."
                        decided -> "Status definido, mas sem dados do pregão (concorrentes, fechamento, lances)."
                        else -> "Ao encerrar a sessão, informe se vencemos ou perdemos e os dados do pregão."
                    },
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            if (decided) StatusBadge(if (won) "Vencemos" else "Não vencemos", if (won) Tone.SUCCESS else Tone.DANGER)
        }
        if (!(decided && state.resultRegistered)) {
            Spacer(Modifier.height(12.dp))
            if (decided) {
                SecondaryButton(
                    "Registrar resultado", { onRegister(won) }, Modifier.fillMaxWidth(),
                    enabled = state.canAnalyze && !state.savingResult, icon = Icons.Outlined.EmojiEvents,
                )
            } else {
                ButtonRow {
                    SecondaryButton(
                        "Vencemos", { onRegister(true) }, Modifier.weight(1f),
                        enabled = state.canAnalyze && !state.savingResult, icon = Icons.Outlined.EmojiEvents, tone = Tone.SUCCESS,
                    )
                    SecondaryButton(
                        "Perdemos", { onRegister(false) }, Modifier.weight(1f),
                        enabled = state.canAnalyze && !state.savingResult, tone = Tone.DANGER,
                    )
                }
            }
            if (state.savingResult) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
            }
        }
    }
}

@Composable
private fun PasteEditalDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val chars = text.trim().length
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colar texto do edital") },
        text = {
            Column {
                Text(
                    "Cole o texto integral do edital (ou as seções de objeto, habilitação, prazos, garantias e penalidades). Mínimo de 200 caracteres.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 8, maxLines = 14,
                    placeholder = { Text("Texto do edital…") }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("$chars caracteres", style = MaterialTheme.typography.labelSmall, color = if (chars >= 200) LicitaColors.TextMuted else LicitaColors.Yellow)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = chars >= 200, colors = ButtonDefaults.buttonColors(containerColor = LicitaColors.Blue)) { Text("Salvar texto") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = LicitaColors.SurfaceElevated,
    )
}

@Composable
private fun PriceCell(label: String, value: Double, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        Text(Formatters.brlCompact(value), style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, color: Color, title: String, subtitle: String, onClick: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = LicitaColors.TextMuted)
        }
    }
}
