package com.licitaia.feature.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.RadarRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.sync.DailySyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class RadarResultsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    auth: AuthRepository,
    tenders: TenderRepository,
    private val radars: RadarRepository,
    private val opportunities: OpportunityRepository,
    connectivity: ConnectivityMonitor,
    private val daily: DailySyncRepository,
    private val settings: SettingsRepository,
    private val flags: com.licitaia.domain.repository.OpportunityFlagsRepository,
    private val links: com.licitaia.domain.model.OfficialLinksRepository,
) : OpportunityListViewModel(auth, tenders, connectivity) {

    override val officialLinks: com.licitaia.domain.model.OfficialLinksRepository get() = links

    override val dailySync: DailySyncRepository get() = daily

    override val viewSettings: SettingsRepository get() = settings

    override val flagsRepository: com.licitaia.domain.repository.OpportunityFlagsRepository get() = flags

    val radarId: Long = savedStateHandle.longArg("radarId") ?: -1L

    private val _radar = MutableStateFlow<Radar?>(null)
    val radar: StateFlow<Radar?> = _radar.asStateFlow()

    init {
        start()
    }

    override suspend fun fetch(companyId: Long, cacheOnly: Boolean): Result<com.licitaia.domain.model.SearchOutcome> {
        val radar = radars.getRadar(radarId)?.takeIf { it.companyId == companyId }
            ?: return Result.failure(IllegalStateException("Radar não encontrado para a empresa ativa."))
        _radar.value = radar
        // Abrir os resultados: o que está salvo (atualização diária); o botão/puxar consulta as fontes.
        return if (cacheOnly) opportunities.runRadarCached(radarId) else opportunities.runRadarWithSources(radarId)
    }

    override fun aiScores(request: com.licitaia.domain.model.AiScoringRequest) = opportunities.scoreWithAi(request)

    override fun sourceSync() = opportunities.observeSourceSync()
}

@Composable
fun RadarResultsScreen(viewModel: RadarResultsViewModel = hiltViewModel()) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val radar by viewModel.radar.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    OpportunityEvents(viewModel)

    LicitaScaffold(
        title = radar?.name ?: "Resultados do Radar",
        showBack = true,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !list.loading && !list.refreshing) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Atualizar resultados")
            }
            if (radar != null) {
                IconButton(onClick = { navigator.navigate(Routes.radarEdit(viewModel.radarId)) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Editar radar")
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding)) {
        OpportunityRefreshBox(list, viewModel, Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = radar
            if (current != null && !current.active && list.error == null) {
                item(key = "paused") {
                    AlertBanner(
                        "Radar pausado",
                        "Os resultados abaixo são de uma execução manual. Ative o radar para receber alertas.",
                        Tone.WARNING, Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            previousDaysChip(list, viewModel)
            markChips(list, viewModel)
            opportunityItems(
                state = list,
                viewModel = viewModel,
                emptyTitle = "Nada no radar por enquanto",
                emptyMessage = "Nenhuma oportunidade atende aos critérios deste radar. Tente reduzir o score mínimo ou ampliar as palavras-chave.",
                emptyIcon = Icons.Outlined.Radar,
            )
        }
        }
        UndoDiscardBar(list, viewModel)
        }
    }
}
