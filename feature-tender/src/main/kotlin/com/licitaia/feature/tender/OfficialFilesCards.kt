package com.licitaia.feature.tender

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.OfficialLinksRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.OfficialLinksUi
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.OfficialFile
import com.licitaia.domain.model.OfficialLinks
import com.licitaia.domain.model.PortalLinks
import com.licitaia.domain.model.Tender
import com.licitaia.domain.util.Formatters

/** Arquivos oficiais de uma licitação (aba Perguntas): cache por oportunidade + "Atualizar". */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OfficialFilesViewModel @Inject constructor(
    private val repo: OfficialLinksRepository,
) : ViewModel() {
    private val opportunity = MutableStateFlow<String?>(null)
    private val flags = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    val links: StateFlow<OfficialLinks?> = opportunity
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val status: StateFlow<Pair<Boolean, String?>> = flags

    fun bind(opportunityId: String) {
        if (opportunity.value == opportunityId) return
        opportunity.value = opportunityId
        viewModelScope.launch {
            if (repo.observe(opportunityId).first() == null) refresh()
        }
    }

    fun refresh() {
        val id = opportunity.value ?: return
        if (flags.value.first) return
        flags.value = true to null
        viewModelScope.launch {
            val r = repo.refresh(id)
            flags.value = false to r.exceptionOrNull()?.let { it.message ?: "Não foi possível listar os arquivos no PNCP." }
        }
    }
}

@Composable
internal fun OfficialFilesSection(tender: Tender, viewModel: OfficialFilesViewModel = hiltViewModel()) {
    if (tender.isManual) return
    LaunchedEffect(tender.opportunityId) { viewModel.bind(tender.opportunityId) }
    val links by viewModel.links.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    OfficialFilesCard(links, status.first, status.second, viewModel::refresh, title = "Arquivos oficiais da licitação")
}

/** "Abrir no portal" (Comprasnet com pesquisa da compra; BLL/Licitanet/PCP pela URL oficial) + "Ver no PNCP". */
@Composable
internal fun PortalLinksCard(tender: Tender, links: OfficialLinks?, onOpenPortal: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val pncp = links?.pncpUrl ?: if (tender.isManual) null else PortalLinks.pncpPageUrl(tender.opportunityId)
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Public, LicitaColors.Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Portal da licitação", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text(
                    if (tender.portal == com.licitaia.domain.model.Portal.COMPRAS_GOV) "Abre o Comprasnet no navegador do app e pesquisa a compra (UASG + número)."
                    else "Abre a página oficial da compra no sistema de origem.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!tender.isManual || tender.portal != com.licitaia.domain.model.Portal.PNCP) {
            PrimaryButton(PortalLinks.buttonLabel(tender.portal, tender.uasg, tender.number), onOpenPortal, Modifier.fillMaxWidth(), icon = Icons.Outlined.OpenInBrowser)
        }
        links?.originUrl?.let { origin ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Página da compra no portal", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (!OfficialLinksUi.openExternal(context, origin)) navigator.showMessage("Nenhum navegador disponível.") }) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = "Abrir página da compra no navegador")
                }
                IconButton(onClick = { if (OfficialLinksUi.copy(context, "Página da compra", origin)) navigator.showMessage("Link copiado.") }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar link da página da compra")
                }
            }
        }
        if (pncp != null) {
            Spacer(Modifier.height(6.dp))
            SecondaryButton(
                "Ver no PNCP", { if (!OfficialLinksUi.openExternal(context, pncp)) navigator.showMessage("Nenhum navegador disponível.") },
                Modifier.fillMaxWidth(), icon = Icons.Outlined.OpenInNew, tone = Tone.NEUTRAL,
            )
        }
    }
}

/** "Arquivos da licitação": todos os arquivos oficiais do PNCP com Baixar / Abrir / Copiar link e "Atualizar". */
@Composable
internal fun OfficialFilesCard(links: OfficialLinks?, loading: Boolean, error: String?, onRefresh: () -> Unit, title: String = "Arquivos da licitação") {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.FolderOpen, LicitaColors.Green)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                Text(
                    when {
                        links == null -> if (loading) "Consultando o PNCP…" else "Edital, termo de referência e anexos publicados no PNCP."
                        else -> "${links.files.size} arquivo(s) · atualizado ${Formatters.relative(links.fetchedAt)}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Outlined.Refresh, contentDescription = "Atualizar arquivos") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
        if (error != null && !loading) {
            Spacer(Modifier.height(8.dp))
            AlertBanner("Não foi possível listar os arquivos", error, Tone.WARNING, actionLabel = "Tentar de novo", onAction = onRefresh)
        }
        val files = links?.files.orEmpty()
        if (links != null && files.isEmpty() && !loading) {
            Spacer(Modifier.height(8.dp))
            Text("O PNCP não tem arquivos publicados para esta contratação.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        }
        files.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = LicitaColors.Outline)
            OfficialFileRow(
                f,
                onDownload = { navigator.showMessage(OfficialLinksUi.download(context, f)) },
                onOpen = { if (!OfficialLinksUi.openExternal(context, f.url)) navigator.showMessage("Nenhum app abre este link.") },
                onShare = { OfficialLinksUi.share(context, f.title, f.url) },
                onCopy = { if (OfficialLinksUi.copy(context, f.title, f.url)) navigator.showMessage("Link copiado.") },
            )
        }
    }
}

@Composable
private fun OfficialFileRow(f: OfficialFile, onDownload: () -> Unit, onOpen: () -> Unit, onShare: () -> Unit, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.title, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            f.typeName?.let { StatusBadge(it, Tone.INFO) }
        }
        val meta = listOfNotNull(
            f.publishedAt?.let { "publicado ${Formatters.date(it)}" },
            f.sizeBytes?.let { "%.1f MB".format(java.util.Locale("pt", "BR"), it / 1_048_576.0) },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Baixar", onDownload, Modifier.weight(1f), icon = Icons.Outlined.Download)
            Spacer(Modifier.width(6.dp))
            SecondaryButton("Abrir", onOpen, Modifier.weight(1f), icon = Icons.Outlined.OpenInNew, tone = Tone.NEUTRAL)
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copiar link") }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = "Compartilhar link") }
        }
    }
}
