package com.licitaia.feature.tender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SimulationBadge
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompanyRepository
import com.licitaia.domain.repository.ProposalPdfGenerator
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProposalPdfState(
    val loading: Boolean = true,
    val stage: String = "Carregando proposta…",
    val error: String? = null,
    val proposal: Proposal? = null,
    val tender: Tender? = null,
    val path: String? = null,
    val pages: List<Bitmap> = emptyList(),
)

@HiltViewModel
class ProposalPdfViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val proposals: ProposalRepository,
    private val tenders: TenderRepository,
    private val companies: CompanyRepository,
    private val pdfGenerator: ProposalPdfGenerator,
    private val audit: AuditRepository,
) : ViewModel() {

    private val proposalId: Long = savedStateHandle.longArg("proposalId") ?: -1L
    private val _state = MutableStateFlow(ProposalPdfState())
    val state: StateFlow<ProposalPdfState> = _state.asStateFlow()

    init {
        load(regenerate = false)
    }

    fun load(regenerate: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, stage = "Carregando proposta…") }
            try {
                val session = auth.session.value ?: error("Sessão encerrada. Entre novamente.")
                val proposal = (if (proposalId > 0) proposals.getProposal(proposalId) else null)
                    ?: throw IllegalStateException("Proposta não encontrada.")
                if (proposal.companyId != session.activeCompany.id) throw IllegalStateException("Esta proposta pertence a outra empresa.")
                val tender = tenders.observeTender(proposal.tenderId).first() ?: throw IllegalStateException("Licitação da proposta não encontrada.")
                _state.update { it.copy(proposal = proposal, tender = tender) }

                var path = proposal.pdfPath?.takeIf { File(it).exists() }
                if (path == null || regenerate) {
                    _state.update { it.copy(stage = "Gerando PDF…") }
                    val company = if (session.activeCompany.id == tender.companyId) session.activeCompany
                    else companies.getCompany(tender.companyId) ?: error("Empresa não encontrada.")
                    path = pdfGenerator.generate(proposal, tender, company).getOrThrow()
                    proposals.attachPdf(proposal.id, path)
                    try {
                        audit.record(
                            action = AuditAction.GERACAO_DOCUMENTO, portal = tender.portal, tenderNumber = tender.number,
                            item = "Proposta v${proposal.version}", newValue = path.substringAfterLast('/'),
                            details = if (regenerate) "PDF da proposta regenerado pelo usuário." else "PDF da proposta gerado ao abrir o visualizador.",
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                _state.update { it.copy(stage = "Renderizando páginas…") }
                val pages = render(path)
                recycleCurrent()
                _state.update { it.copy(loading = false, path = path, pages = pages, proposal = proposal.copy(pdfPath = path)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message?.takeIf(String::isNotBlank) ?: "Não foi possível abrir o PDF.") }
            }
        }
    }

    private suspend fun render(path: String): List<Bitmap> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Bitmap>()
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PAGES)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val scale = RENDER_WIDTH / page.width.toFloat()
                        val bitmap = Bitmap.createBitmap(RENDER_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        result += bitmap
                    }
                }
            }
        }
        result
    }

    private fun recycleCurrent() {
        _state.value.pages.forEach { if (!it.isRecycled) it.recycle() }
    }

    override fun onCleared() {
        recycleCurrent()
        super.onCleared()
    }

    private companion object {
        const val RENDER_WIDTH = 1240
        const val MAX_PAGES = 12
    }
}

@Composable
fun ProposalPdfScreen(viewModel: ProposalPdfViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }

    val proposal = state.proposal
    val tender = state.tender
    val title = "Proposta ${tender?.number.orEmpty()} v${proposal?.version ?: ""}".trim()

    LicitaScaffold(
        title = "PDF da Proposta",
        subtitle = tender?.let { "${it.number} · v${proposal?.version ?: "-"}" },
        showBack = true,
        actions = {
            IconButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }, enabled = !state.loading && zoom > 1f) {
                Icon(Icons.Outlined.ZoomOut, contentDescription = "Reduzir")
            }
            IconButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }, enabled = !state.loading && zoom < 3f) {
                Icon(Icons.Outlined.ZoomIn, contentDescription = "Ampliar")
            }
            IconButton(onClick = { viewModel.load(regenerate = true) }, enabled = !state.loading && state.error == null) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Regenerar PDF")
            }
            IconButton(
                onClick = {
                    val path = state.path
                    if (path == null || !sharePdf(context, path, title)) navigator.showMessage("Não foi possível compartilhar o PDF.")
                },
                enabled = !state.loading && state.path != null,
            ) { Icon(Icons.Outlined.Share, contentDescription = "Compartilhar") }
        },
    ) { padding ->
        val error = state.error
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LinearProgressIndicator(Modifier.fillMaxWidth(0.6f), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
                Spacer(Modifier.height(16.dp))
                Text(state.stage, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
            }
            error != null -> ErrorState(error, Modifier.padding(padding), title = "PDF indisponível", onRetry = { viewModel.load(regenerate = false) })
            state.pages.isEmpty() -> ErrorState("O documento não possui páginas renderizáveis.", Modifier.padding(padding), onRetry = { viewModel.load(regenerate = true) })
            else -> BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(LicitaColors.Background)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, 3f)
                        }
                    },
            ) {
                val pageWidth = maxWidth * zoom
                val hScroll = rememberScrollState()
                LaunchedEffect(zoom) { if (zoom == 1f) hScroll.scrollTo(0) }
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        proposal?.let { StatusBadge(it.status.label, it.status.tone()) }
                        SimulationBadge()
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${state.pages.size} pág. · ${(zoom * 100).toInt()}%" + (proposal?.let { " · ${Formatters.brl(it.totalValue)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                        )
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().horizontalScroll(hScroll),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(state.pages, key = { index, _ -> index }) { index, bitmap ->
                            Surface(
                                Modifier
                                    .width(pageWidth - 24.dp)
                                    .shadow(6.dp, MaterialTheme.shapes.small)
                                    .clip(MaterialTheme.shapes.small),
                                color = androidx.compose.ui.graphics.Color.White,
                            ) {
                                Column {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Página ${index + 1}",
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
