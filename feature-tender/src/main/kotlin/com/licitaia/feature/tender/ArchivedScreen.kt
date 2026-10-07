package com.licitaia.feature.tender

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.OfficialLinksUi
import com.licitaia.core.ui.components.PortalChip
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.ArchiveRepository
import com.licitaia.domain.model.ArchivedEntry
import com.licitaia.domain.model.PortalLinks
import com.licitaia.domain.model.UasgCode
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ArchivedState(val loading: Boolean = true, val query: String = "", val items: List<ArchivedEntry> = emptyList(), val total: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArchivedViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val archive: ArchiveRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<ArchivedState> = auth.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else archive.observeArchived(s.activeCompany.id).catch { emit(emptyList()) }
    }.combine(query) { all, q -> ArchivedState(loading = false, query = q, items = all.filter { it.matches(q) }, total = all.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchivedState())

    fun setQuery(q: String) { query.value = q }

    fun unarchive(entry: ArchivedEntry) {
        val companyId = auth.session.value?.activeCompany?.id ?: return
        viewModelScope.launch {
            runCatching { archive.unarchive(companyId, entry.opportunityId) }
                .onSuccess { _messages.tryEmit("Desarquivada: ${entry.number} volta às listas.") }
                .onFailure { _messages.tryEmit(it.message ?: "Não foi possível desarquivar.") }
        }
    }
}

@Composable
fun ArchivedScreen(viewModel: ArchivedViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }
    LicitaScaffold(title = "Licitações arquivadas", subtitle = if (state.total > 0) "${state.total} arquivada(s)" else null) { padding ->
        if (state.loading) {
            SkeletonList(Modifier.padding(padding))
            return@LicitaScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = state.query, onValueChange = viewModel::setQuery, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Número, órgão, objeto ou UASG") },
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (state.total == 0) "Nenhuma licitação arquivada" else "Nada encontrado",
                        message = if (state.total == 0) "Use \"Arquivar\" nos cards da Busca/Radar ou na tela da licitação para tirar da frente o que não interessa. Nada é apagado."
                        else "Nenhuma arquivada casa com \"${state.query}\".",
                        icon = Icons.Outlined.Archive,
                    )
                }
            }
            items(state.items, key = { it.opportunityId }) { entry ->
                LicitaCard(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        entry.portal?.let { PortalChip(it) }
                        entry.tender?.let { StatusBadge(it.status.label, it.status.tone()) } ?: StatusBadge("Oportunidade", Tone.NEUTRAL)
                    }
                    Spacer(Modifier.height(6.dp))
                    val unit = entry.portal?.let { UasgCode.label(it, entry.uasg) }
                    Text(listOfNotNull(entry.number, unit).joinToString(" · "), style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                    if (entry.agency.isNotBlank()) Text(entry.agency, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.objectDescription.isNotBlank()) {
                        Text(entry.objectDescription, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Arquivada em ${Formatters.dateTime(entry.archivedAt)}", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                    Spacer(Modifier.height(8.dp))
                    ButtonRow {
                        SecondaryButton("Desarquivar", { viewModel.unarchive(entry) }, Modifier.weight(1f), icon = Icons.Outlined.Unarchive)
                        SecondaryButton(
                            "Abrir",
                            {
                                val t = entry.tender
                                val pncp = PortalLinks.pncpPageUrl(entry.opportunityId)
                                when {
                                    t != null -> navigator.navigate(Routes.tender(t.id))
                                    pncp != null -> if (!OfficialLinksUi.openExternal(context, pncp)) navigator.showMessage("Nenhum navegador disponível.")
                                    else -> navigator.showMessage("Desarquive para ver esta oportunidade na Busca.")
                                }
                            },
                            Modifier.weight(1f), icon = Icons.Outlined.OpenInNew, tone = Tone.NEUTRAL,
                        )
                    }
                }
            }
        }
    }
}
