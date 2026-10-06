package com.licitaia.feature.tender

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentPaste
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
import com.licitaia.domain.model.EditalImportResult
import com.licitaia.domain.model.EditalSource
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
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
import kotlinx.coroutines.flow.flatMapLatest
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
) {
    val canAnalyze: Boolean get() = role?.let { Rbac.can(it, Permission.ANALISAR) } ?: false
}

private data class DetailFlags(val importing: Boolean = false, val analyzing: Boolean = false, val editalError: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TenderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    auth: AuthRepository,
    private val tenders: TenderRepository,
    proposals: ProposalRepository,
) : ViewModel() {

    private val tenderId: Long = savedStateHandle.longArg("tenderId") ?: -1L

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
            ) { tender, analysis, versions ->
                if (tender == null || tender.companyId != session.activeCompany.id) {
                    TenderDetailState(loading = false, notFound = true)
                } else {
                    TenderDetailState(loading = false, tender = tender, analysis = analysis, proposals = versions, role = session.user.role)
                }
            }.catch { emit(TenderDetailState(loading = false, notFound = true)) }
        }
    }

    val state: StateFlow<TenderDetailState> = combine(remote, flags) { s, f ->
        s.copy(importing = f.importing, analyzing = f.analyzing, editalError = f.editalError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderDetailState())

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

    /** Importa o PDF escolhido (content://) e extrai o texto. */
    fun importPdf(uri: String) = attach(EditalSource.Pdf(uri)) { result ->
        when {
            result.scanned -> "PDF sem texto (escaneado): OCR ainda não disponível — cole o texto do edital manualmente."
            else -> "Edital importado: ${result.pages ?: 0} página(s), ${result.chars} caracteres. Pronto para analisar com IA."
        }
    }

    /** Guarda o texto colado pelo usuário como texto do edital. */
    fun pasteText(text: String) = attach(EditalSource.Text(text)) { result -> "Texto do edital salvo (${result.chars} caracteres)." }

    private fun attach(source: EditalSource, message: (EditalImportResult) -> String) {
        if (flags.value.importing || tenderId <= 0) return
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
                    flags.update { it.copy(importing = false, editalError = if (r.scanned) message(r) else null) }
                    _messages.tryEmit(message(r))
                },
                onFailure = { e ->
                    flags.update { it.copy(importing = false, editalError = e.message?.takeIf(String::isNotBlank) ?: "Não foi possível importar o edital.") }
                },
            )
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
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            GradientCard(Modifier.fillMaxWidth()) {
                TenderHeadline(tender, analysis)
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
                onPaste = { pasteOpen = true },
                onOpenPdf = {
                    val path = tender.editalPdfPath
                    if (path == null || !openPdf(context, path)) navigator.showMessage("Não foi possível abrir o PDF (arquivo ausente ou nenhum leitor instalado).")
                },
                onAnalyze = viewModel::analyze,
            )
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
                    AlertBanner(
                        "Análise heurística — configure um provedor de IA para analisar o edital",
                        "Esta recomendação foi calculada por regras locais (sem modelo de IA). Nenhum documento, risco ou preço foi lido do edital.",
                        Tone.WARNING, actionLabel = "Configurar", onAction = { navigator.navigate(Routes.AI_SETTINGS) },
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
                InfoRow("Modalidade", tender.modality.label)
                InfoRow("Segmento", tender.segment.label)
                InfoRow("Local", "${tender.city}/${tender.uf}")
                InfoRow("Valor estimado", Formatters.brl(tender.estimatedValue), valueColor = LicitaColors.GreenBright)
                InfoRow("Edital registrado", if (tender.editalRegistered) "Sim" else "Pendente", valueColor = if (tender.editalRegistered) LicitaColors.GreenBright else LicitaColors.Yellow)
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
                ActionTile(Icons.Outlined.Balance, LicitaColors.Yellow, "Vale a pena participar?", "Veredito executivo com todos os indicadores") {
                    navigator.navigate(Routes.tenderWorth(tender.id))
                }
                ActionTile(
                    Icons.Outlined.RequestQuote, LicitaColors.Green, "Proposta comercial",
                    if (state.proposals.isEmpty()) "Gerar com IA, revisar, aprovar e preparar envio" else "${state.proposals.size} versão(ões) · ${state.proposals.first().status.label}",
                ) { navigator.navigate(Routes.tenderProposal(tender.id)) }
                ActionTile(Icons.Outlined.LiveTv, LicitaColors.Red, "Abrir pregão ao vivo", "Acompanhar a sessão e o robô de lances") {
                    navigator.navigateTop(Routes.LIVE)
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
    onPaste: () -> Unit,
    onOpenPdf: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val busy = state.importing || state.analyzing
    val statusText = when {
        tender.editalScanned && !tender.hasEditalText -> "PDF escaneado (sem camada de texto) — cole o texto manualmente"
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
            StatusBadge(if (tender.hasEditalText) "Texto OK" else if (tender.editalScanned) "Escaneado" else "Pendente", statusTone)
        }
        if (tender.editalPdfPath != null) {
            Spacer(Modifier.height(8.dp))
            InfoRow("Arquivo", "${tender.id}.pdf" + (tender.editalPages?.let { " · $it pág." } ?: ""))
        }
        if (state.importing) {
            Spacer(Modifier.height(10.dp))
            AlertBanner("Importando o edital…", "Copiando o PDF e extraindo o texto. Editais grandes podem levar alguns segundos.", Tone.INFO, pulsing = true)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
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
            SecondaryButton("Abrir PDF", onOpenPdf, Modifier.fillMaxWidth(), icon = Icons.Outlined.OpenInNew, tone = Tone.NEUTRAL)
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
                "A análise com IA exige o texto do edital. Importe o PDF ou cole o texto.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
        }
        if (!state.canAnalyze && state.role != null) {
            Spacer(Modifier.height(6.dp))
            Text("Seu perfil (${state.role.label}) não pode importar nem analisar editais.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
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
