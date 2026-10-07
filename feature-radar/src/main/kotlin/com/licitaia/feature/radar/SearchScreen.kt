package com.licitaia.feature.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.LowAdherence
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.ModalityGroup
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.model.Segment
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.OpportunityFlagsRepository
import com.licitaia.domain.repository.SettingsRepository
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.sync.DailySyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SearchFilters(
    val query: String = "",
    val portals: Set<Portal> = emptySet(),
    val ufs: Set<String> = emptySet(),
    val segment: Segment? = null,
    val modality: Modality? = null,
    val minValue: String = "",
    val maxValue: String = "",
    val minScore: Int = 0,
    val valueError: String? = null,
) {
    /** Quantidade de filtros avançados ativos (exclui texto e portal, sempre visíveis). */
    val advancedCount: Int
        get() = listOf(ufs.isNotEmpty(), segment != null, modality != null, minValue.isNotBlank(), maxValue.isNotBlank(), minScore > 0).count { it }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    auth: AuthRepository,
    tenders: TenderRepository,
    private val opportunities: OpportunityRepository,
    connectivity: ConnectivityMonitor,
    private val daily: DailySyncRepository,
    private val settings: SettingsRepository,
    private val flags: OpportunityFlagsRepository,
    private val links: com.licitaia.domain.model.OfficialLinksRepository,
) : OpportunityListViewModel(auth, tenders, connectivity) {

    override val officialLinks: com.licitaia.domain.model.OfficialLinksRepository get() = links

    override val dailySync: DailySyncRepository get() = daily

    override val viewSettings: SettingsRepository get() = settings

    override val flagsRepository: OpportunityFlagsRepository get() = flags

    /** Texto digitado: a pesquisa procura também nos dias anteriores do mês, independentemente do chip. */
    override val forcePreviousDays: Boolean get() = _filters.value.query.isNotBlank()

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    init {
        start()
        viewModelScope.launch {
            // Selo "Nova": o que entrou no cache desde a abertura ANTERIOR da Busca; grava esta abertura.
            runCatching {
                previousSearchOpenAt = settings.settings.first().lastSearchOpenedAt
                settings.update { it.copy(lastSearchOpenedAt = System.currentTimeMillis()) }
            }
            recomputeMarks()
        }
    }

    override fun aiScores(request: com.licitaia.domain.model.AiScoringRequest) = opportunities.scoreWithAi(request)

    override fun sourceSync() = opportunities.observeSourceSync()

    /** Busca: nota < 30 oculta por padrão ("Mostrar baixa aderência"). */
    override val lowAdherenceThreshold: Int? get() = LowAdherence.THRESHOLD

    override suspend fun fetch(companyId: Long, cacheOnly: Boolean): Result<com.licitaia.domain.model.SearchOutcome> {
        val f = _filters.value
        val filter = OpportunityFilter(
                query = f.query.trim(),
                portals = f.portals,
                ufs = f.ufs,
                segment = f.segment,
                modality = f.modality,
                minValue = parseMoney(f.minValue)?.takeIf { !it.isNaN() },
                maxValue = parseMoney(f.maxValue)?.takeIf { !it.isNaN() },
                minScore = f.minScore,
                // Dispensas (inclusive sem disputa) sempre entram; o chip de modalidade filtra na tela.
                showNoDispute = true,
        )
        // Texto digitado consulta as fontes (o salvo no aparelho já passou pela triagem de relevância); filtros de
        // portal/UF/modalidade/valor ao abrir ou trocar usam só o que está salvo — rápido, sem baixar tudo de novo.
        return if (cacheOnly && filter.query.isBlank()) opportunities.searchCached(companyId, filter)
        else opportunities.searchWithSources(companyId, filter)
    }

    fun edit(transform: (SearchFilters) -> SearchFilters) = _filters.update { transform(it).copy(valueError = null) }

    /** Filtro por portal aplica na hora — é o filtro mais usado. */
    fun togglePortal(portal: Portal) {
        _filters.update { it.copy(portals = if (portal in it.portals) it.portals - portal else it.portals + portal) }
        reload()
    }

    fun clearPortals() {
        _filters.update { it.copy(portals = emptySet()) }
        reload()
    }

    /** Valida e executa a busca. Retorna false quando há erro de validação. */
    fun search(): Boolean {
        val f = _filters.value
        val min = parseMoney(f.minValue)
        val max = parseMoney(f.maxValue)
        val error = when {
            min?.isNaN() == true || max?.isNaN() == true -> "Informe valores numéricos válidos"
            min != null && max != null && min > max -> "O valor mínimo não pode ser maior que o máximo"
            else -> null
        }
        if (error != null) {
            _filters.update { it.copy(valueError = error) }
            return false
        }
        reload()
        return true
    }

    fun clearFilters() {
        _filters.update { SearchFilters(query = it.query) }
        reload()
    }
}

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var showFilters by rememberSaveable { mutableStateOf(false) }

    OpportunityEvents(viewModel)

    LicitaScaffold(title = "Buscar Licitações", showBack = false) { padding ->
        // Campo de busca e portais fixos no topo; o pull-to-refresh envolve SÓ a lista (o indicador aparece no topo
        // da lista, nunca sobre o campo de busca).
        Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = filters.query,
                        onValueChange = { v -> viewModel.edit { it.copy(query = v) } },
                        placeholder = { Text("Objeto, órgão ou número do edital") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (filters.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.edit { it.copy(query = "") }; viewModel.search() }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Limpar busca")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); viewModel.search() }),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    BadgedBox(badge = { if (filters.advancedCount > 0) Badge { Text("${filters.advancedCount}") } }) {
                        FilledTonalIconButton(onClick = { showFilters = !showFilters }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Filtros")
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") { SelectChip("Todos os portais", filters.portals.isEmpty(), viewModel::clearPortals) }
                    items(Portal.entries, key = { it.name }) { portal ->
                        SelectChip(portal.shortName, portal in filters.portals, { viewModel.togglePortal(portal) }, color = portal.color())
                    }
                }
        OpportunityRefreshBox(list, viewModel, Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "source-note") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "Fontes: PNCP e Compras.gov.br (consulta pública): licitações, pregões e dispensas com propostas do mês atual em diante; a lista mostra de hoje em diante. Licitanet, BLL e PCP aparecem pelas publicações dessas plataformas no PNCP. Puxe para atualizar.",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                    // "Atualizado hoje às 05:30 · Próxima atualização automática: amanhã 05:30".
                    list.scheduleLine?.let { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                    }
                }
            }
            item(key = "filters") {
                AnimatedVisibility(showFilters, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    FiltersPanel(
                        filters = filters,
                        onEdit = viewModel::edit,
                        onApply = { focus.clearFocus(); if (viewModel.search()) showFilters = false },
                        onClear = { viewModel.clearFilters() },
                    )
                }
            }
            // Modalidade (Todas · Pregão · Dispensa · Concorrência/Outras) e dias anteriores: sem nova consulta.
            if (!list.loading && list.error == null) {
                item(key = "modality") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(ModalityGroup.entries, key = { it.name }) { g ->
                            SelectChip(g.label, list.modalityGroup == g, { viewModel.setModalityGroup(g) })
                        }
                    }
                }
            }
            previousDaysChip(list, viewModel)
            markChips(list, viewModel)
            // Baixa aderência (nota < 30) oculta por padrão; o chip mostra/oculta sem nova consulta.
            val lowCount = list.hiddenLow.size
            if (!list.loading && list.error == null && (lowCount > 0 || list.showLowAdherence)) {
                item(key = "low-adherence") {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        SelectChip(
                            if (list.showLowAdherence) "Mostrando baixa aderência" else "Mostrar baixa aderência ($lowCount)",
                            list.showLowAdherence,
                            { viewModel.setShowLowAdherence(!list.showLowAdherence) },
                        )
                    }
                }
            }
            opportunityItems(
                state = list,
                viewModel = viewModel,
                emptyTitle = "Nenhuma licitação encontrada",
                emptyMessage = when {
                    !list.previousVisible && list.previousCount > 0 ->
                        "Nada de hoje em diante com estes filtros. Há ${list.previousCount} de dias anteriores do mês: toque em \"Mostrar dias anteriores\"."
                    lowCount > 0 -> "Só há resultados de baixa aderência (nota abaixo de ${LowAdherence.THRESHOLD}). Toque em \"Mostrar baixa aderência\" para vê-los."
                    else -> "Ajuste a busca ou os filtros. Você também pode criar um Radar para ser avisado de novas oportunidades."
                },
                showScheduleInHeader = false,
            )
        }
        }
        UndoDiscardBar(list, viewModel)
        }
    }
}

@Composable
private fun FiltersPanel(
    filters: SearchFilters,
    onEdit: ((SearchFilters) -> SearchFilters) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    LicitaCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        FilterLabel("UF")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BRAZIL_UFS, key = { it }) { uf ->
                SelectChip(uf, uf in filters.ufs, { onEdit { it.copy(ufs = if (uf in it.ufs) it.ufs - uf else it.ufs + uf) } })
            }
        }
        FilterLabel("Segmento")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "any") { SelectChip("Todos", filters.segment == null, { onEdit { it.copy(segment = null) } }) }
            items(Segment.entries, key = { it.name }) { s ->
                SelectChip(s.label, filters.segment == s, { onEdit { it.copy(segment = s) } })
            }
        }
        FilterLabel("Modalidade")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "any") { SelectChip("Todas", filters.modality == null, { onEdit { it.copy(modality = null) } }) }
            items(Modality.entries, key = { it.name }) { m ->
                SelectChip(m.label, filters.modality == m, { onEdit { it.copy(modality = m) } })
            }
        }
        FilterLabel("Faixa de valor estimado (R$)")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = filters.minValue, onValueChange = { v -> onEdit { it.copy(minValue = v) } },
                label = { Text("Mínimo") }, singleLine = true, isError = filters.valueError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = filters.maxValue, onValueChange = { v -> onEdit { it.copy(maxValue = v) } },
                label = { Text("Máximo") }, singleLine = true, isError = filters.valueError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
            )
        }
        if (filters.valueError != null) {
            Text(filters.valueError, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Red, modifier = Modifier.padding(top = 4.dp))
        }
        FilterLabel(if (filters.minScore > 0) "Score mínimo de aderência: ${filters.minScore}" else "Score mínimo de aderência: qualquer")
        Slider(
            value = filters.minScore.toFloat(),
            onValueChange = { v -> onEdit { it.copy(minScore = (v / 5).toInt() * 5) } },
            valueRange = 0f..100f,
        )
        Spacer(Modifier.height(4.dp))
        ButtonRow {
            SecondaryButton("Limpar", onClear, Modifier.weight(1f), tone = Tone.NEUTRAL)
            PrimaryButton("Aplicar filtros", onApply, Modifier.weight(1.4f), icon = Icons.Outlined.Search)
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}
