package com.licitaia.feature.radar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Radar
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.RadarRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RadarListState(
    val loading: Boolean = true,
    val error: String? = null,
    val radars: List<Radar> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RadarListViewModel @Inject constructor(
    auth: AuthRepository,
    private val repository: RadarRepository,
) : ViewModel() {

    val state: StateFlow<RadarListState> = auth.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(RadarListState(loading = false, error = "Sessão encerrada. Entre novamente."))
        } else {
            repository.observeRadars(session.activeCompany.id)
                .map { RadarListState(loading = false, radars = it) }
                .catch { emit(RadarListState(loading = false, error = "Não foi possível carregar os radares.")) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadarListState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun toggle(radar: Radar) = safely("Não foi possível atualizar o radar.") {
        repository.upsert(radar.copy(active = !radar.active))
        _messages.tryEmit(if (radar.active) "Radar \"${radar.name}\" pausado" else "Radar \"${radar.name}\" ativado")
    }

    fun delete(radar: Radar) = safely("Não foi possível excluir o radar.") {
        repository.delete(radar.id)
        _messages.tryEmit("Radar \"${radar.name}\" excluído")
    }

    private fun safely(errorMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.tryEmit(errorMessage)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadarListScreen(viewModel: RadarListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var toDelete by remember { mutableStateOf<Radar?>(null) }

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.messages.collect(navigator::showMessage)
    }

    LicitaScaffold(
        title = "Radar de Licitações",
        showBack = false,
        floatingActionButton = {
            if (!state.loading && state.error == null) {
                ExtendedFloatingActionButton(
                    onClick = { navigator.navigate(Routes.radarEdit()) },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("Novo radar") },
                    containerColor = LicitaColors.Blue,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        val error = state.error
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            error != null -> ErrorState(error, Modifier.padding(padding))
            state.radars.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nenhum radar criado",
                    message = "Crie radares por segmento, palavras-chave, UF e faixa de valor. O LicitaIA monitora os portais e avisa quando surgir uma oportunidade aderente.",
                    icon = Icons.Outlined.Radar,
                    actionLabel = "Criar primeiro radar",
                    onAction = { navigator.navigate(Routes.radarEdit()) },
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") {
                    Text(
                        "${state.radars.count { it.active }} ativo(s) de ${state.radars.size} radar(es)",
                        style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
                    )
                }
                items(state.radars, key = { it.id }) { radar ->
                    RadarCard(
                        radar = radar,
                        modifier = Modifier.animateItem(),
                        onToggle = { viewModel.toggle(radar) },
                        onEdit = { navigator.navigate(Routes.radarEdit(radar.id)) },
                        onDelete = { toDelete = radar },
                        onResults = { navigator.navigate(Routes.radarResults(radar.id)) },
                    )
                }
            }
        }
    }

    toDelete?.let { radar ->
        ConfirmDialog(
            title = "Excluir radar?",
            message = "O radar \"${radar.name}\" deixará de monitorar os portais. As licitações já marcadas como interesse são mantidas.",
            confirmLabel = "Excluir",
            tone = Tone.DANGER,
            icon = Icons.Outlined.DeleteOutline,
            onConfirm = { toDelete = null; viewModel.delete(radar) },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun RadarCard(
    radar: Radar,
    modifier: Modifier,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResults: () -> Unit,
) {
    LicitaCard(modifier.fillMaxWidth(), accent = if (radar.active) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.Radar, if (radar.active) LicitaColors.Green else LicitaColors.TextMuted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(radar.name, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(radar.segment.label, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
            }
            Switch(checked = radar.active, onCheckedChange = { onToggle() })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBadge(if (radar.active) "Monitorando" else "Pausado", if (radar.active) Tone.SUCCESS else Tone.NEUTRAL, pulsing = radar.active)
            StatusBadge("Score ≥ ${radar.minScore}", Tone.INFO)
            if (radar.requireLocalSupport) StatusBadge("Atend. local", Tone.WARNING)
        }
        Spacer(Modifier.height(10.dp))
        if (radar.keywords.isNotEmpty()) SummaryLine("Palavras-chave", radar.keywords.joinToString(", "))
        if (radar.forbiddenKeywords.isNotEmpty()) SummaryLine("Proibidas", radar.forbiddenKeywords.joinToString(", "))
        SummaryLine(
            "Portais",
            if (radar.allPortals || radar.portals.isEmpty()) "Todos os portais conectados" else radar.portals.joinToString(", ") { it.shortName },
        )
        SummaryLine(
            "Abrangência",
            listOfNotNull(
                radar.ufs.takeIf { it.isNotEmpty() }?.joinToString(", "),
                radar.region?.takeIf { it.isNotBlank() }?.let { "Região $it" },
            ).joinToString(" · ").ifBlank { "Todo o Brasil" },
        )
        if (radar.minValue != null || radar.maxValue != null) {
            SummaryLine(
                "Valor",
                when {
                    radar.minValue != null && radar.maxValue != null -> "${Formatters.brlCompact(radar.minValue!!)} a ${Formatters.brlCompact(radar.maxValue!!)}"
                    radar.minValue != null -> "a partir de ${Formatters.brlCompact(radar.minValue!!)}"
                    else -> "até ${Formatters.brlCompact(radar.maxValue!!)}"
                },
            )
        }
        radar.modality?.let { SummaryLine("Modalidade", it.label) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Ver resultados", onResults, Modifier.weight(1f), icon = Icons.Outlined.TravelExplore)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Editar radar", tint = LicitaColors.TextSecondary) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Excluir radar", tint = LicitaColors.RedBright) }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextMuted, modifier = Modifier.width(104.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
