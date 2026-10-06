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
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.Segment
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.TenderRepository
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
) : OpportunityListViewModel(auth, tenders) {

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    init {
        start()
    }

    override suspend fun fetch(companyId: Long): Result<List<ScoredOpportunity>> {
        val f = _filters.value
        return opportunities.search(
            companyId,
            OpportunityFilter(
                query = f.query.trim(),
                portals = f.portals,
                ufs = f.ufs,
                segment = f.segment,
                modality = f.modality,
                minValue = parseMoney(f.minValue)?.takeIf { !it.isNaN() },
                maxValue = parseMoney(f.maxValue)?.takeIf { !it.isNaN() },
                minScore = f.minScore,
            ),
        )
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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "query") {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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
            }
            item(key = "portals") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") { SelectChip("Todos os portais", filters.portals.isEmpty(), viewModel::clearPortals) }
                    items(Portal.entries, key = { it.name }) { portal ->
                        SelectChip(portal.shortName, portal in filters.portals, { viewModel.togglePortal(portal) }, color = portal.color())
                    }
                }
            }
            item(key = "source-note") {
                Text(
                    "Fonte real: PNCP · consulta pública (propostas em aberto). Os demais portais ainda não têm API pública integrada.",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
            opportunityItems(
                state = list,
                viewModel = viewModel,
                emptyTitle = "Nenhuma licitação encontrada",
                emptyMessage = "Ajuste a busca ou os filtros. Você também pode criar um Radar para ser avisado de novas oportunidades.",
            )
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
