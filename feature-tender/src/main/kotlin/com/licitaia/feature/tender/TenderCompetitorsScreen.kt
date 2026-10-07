package com.licitaia.feature.tender

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.competition.CompetitionResultsSync
import com.licitaia.domain.competition.CompetitorLookup
import com.licitaia.domain.competition.CompetitorRanking
import com.licitaia.domain.competition.CompetitorStat
import com.licitaia.domain.competition.MarketSnapshot
import com.licitaia.domain.competition.PublicAwardResult
import com.licitaia.domain.competition.TenderCompetitors
import com.licitaia.domain.competition.TenderCompetitorsView
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.TenderRepository
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
import kotlinx.coroutines.launch

/** Monta a visão de concorrentes de uma licitação a partir da base pública (mesma regra em todas as telas). */
internal object TenderCompetitorsSupport {
    fun segmentOf(tender: Tender, companySegment: Segment): Segment =
        if (tender.segment == Segment.PERSONALIZADO) companySegment else tender.segment

    fun lookup(tender: Tender, companySegment: Segment) = CompetitorLookup(
        agencyCnpj = if (tender.isManual) null else TenderCompetitors.agencyCnpj(tender.opportunityId),
        objectKeywords = TenderCompetitors.objectKeywords(tender.objectDescription),
        segmentKeywords = TenderCompetitors.segmentKeywords(segmentOf(tender, companySegment)),
        uf = tender.uf.takeIf { it.length == 2 },
    )

    fun view(tender: Tender, results: List<PublicAwardResult>, ourCnpj: String, companySegment: Segment): TenderCompetitorsView {
        val l = lookup(tender, companySegment)
        return TenderCompetitors.build(results, l.agencyCnpj, l.objectKeywords, l.segmentKeywords, ourCnpj)
    }

    /** "5 concorrente(s)" com a origem: dados públicos (PNCP) ou estimativa da análise. */
    fun countLabel(view: TenderCompetitorsView?, analysis: TenderAnalysis): Pair<Int, String> {
        val n = view?.publicCount
        return if (n != null) {
            n to (if (view.countFromAgency) "vencedores no mesmo órgão (PNCP)" else "vencedores no segmento (PNCP)")
        } else {
            analysis.fit.historicalCompetitors to (if (analysis.heuristicOnly) "estimado (heurística, sem dados públicos)" else "estimado pela IA (sem dados públicos)")
        }
    }
}

data class TenderCompetitorsState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val tender: Tender? = null,
    val analysis: TenderAnalysis? = null,
    val view: TenderCompetitorsView? = null,
    val marketUpdatedAt: Long? = null,
    val fetching: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TenderCompetitorsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val tenders: TenderRepository,
    private val sync: CompetitionResultsSync,
) : ViewModel() {
    private val tenderId: Long = savedStateHandle.longArg("tenderId") ?: -1L
    private val fetching = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<TenderCompetitorsState> = auth.session.flatMapLatest { session ->
        if (session == null || tenderId <= 0) flowOf(TenderCompetitorsState(loading = false, notFound = true))
        else combine(
            tenders.observeTender(tenderId),
            tenders.observeAnalysis(tenderId).catch { emit(null) },
            sync.observeMarket(session.activeCompany.id).catch { emit(MarketSnapshot()) },
            fetching,
        ) { tender, analysis, market, busy ->
            if (tender == null || tender.companyId != session.activeCompany.id) TenderCompetitorsState(loading = false, notFound = true)
            else TenderCompetitorsState(
                loading = false, tender = tender, analysis = analysis,
                view = TenderCompetitorsSupport.view(tender, market.results, session.activeCompany.cnpj, session.activeCompany.segment),
                marketUpdatedAt = market.updatedAt, fetching = busy,
            )
        }.catch { emit(TenderCompetitorsState(loading = false, notFound = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderCompetitorsState())

    /** "Buscar concorrentes agora": consulta o PNCP (mesmo órgão + segmento) e grava na base da Concorrência. */
    fun fetchNow() {
        val session = auth.session.value ?: return
        val tender = state.value.tender ?: return
        if (fetching.value) return
        fetching.value = true
        viewModelScope.launch {
            try {
                val result = sync.fetchCompetitors(session.activeCompany.id, TenderCompetitorsSupport.lookup(tender, session.activeCompany.segment))
                result.fold(
                    onSuccess = { b ->
                        _messages.tryEmit(
                            when {
                                b.results.isEmpty() && b.partial -> "O PNCP limitou as consultas agora. Tente de novo em alguns minutos."
                                b.results.isEmpty() -> "Nenhum resultado homologado encontrado no PNCP para contratações semelhantes."
                                b.partial -> "${b.results.size} resultado(s) gravado(s); o PNCP limitou as consultas — o restante fica para depois."
                                else -> "${b.results.size} resultado(s) homologado(s) gravado(s) na Concorrência."
                            },
                        )
                    },
                    onFailure = { e -> _messages.tryEmit(e.message?.takeIf(String::isNotBlank) ?: "Não foi possível consultar o PNCP.") },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Não foi possível consultar o PNCP.")
            } finally {
                fetching.value = false
            }
        }
    }
}

@Composable
fun TenderCompetitorsScreen(viewModel: TenderCompetitorsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }
    val tender = state.tender
    LicitaScaffold(title = "Concorrentes", subtitle = tender?.number, showBack = true) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            state.notFound || tender == null -> ErrorState("Esta licitação não existe ou pertence a outra empresa.", Modifier.padding(padding), title = "Licitação não encontrada")
            else -> {
                val view = state.view
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "intro") {
                        LicitaCard(Modifier.fillMaxWidth()) {
                            Text("De onde vêm estes nomes", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Fornecedores com resultado HOMOLOGADO publicado no PNCP: (a) no mesmo órgão, em contratações de objeto semelhante, e (b) no seu segmento. " +
                                    "São vencedores reais de itens — não a lista de quem vai disputar esta licitação.",
                                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                            )
                            state.analysis?.let { a ->
                                Spacer(Modifier.height(8.dp))
                                val (n, origin) = TenderCompetitorsSupport.countLabel(view, a)
                                InfoRow("Concorrentes", "$n · $origin")
                            }
                            state.marketUpdatedAt?.let { InfoRow("Base atualizada", Formatters.dateTime(it)) }
                            Spacer(Modifier.height(10.dp))
                            PrimaryButton(
                                if (state.fetching) "Consultando o PNCP…" else "Buscar concorrentes agora", viewModel::fetchNow, Modifier.fillMaxWidth(),
                                enabled = !state.fetching, loading = state.fetching, icon = Icons.Outlined.TravelExplore,
                            )
                            Spacer(Modifier.height(6.dp))
                            SecondaryButton("Ver na Concorrência", { navigator.navigateTop(Routes.COMPETITION) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Leaderboard, tone = Tone.NEUTRAL)
                        }
                    }
                    if (view == null || !view.hasData) {
                        item(key = "empty") {
                            AlertBanner(
                                "Ainda sem dados públicos",
                                if (state.analysis != null) "O número exibido em \"Vale a pena participar?\" é uma estimativa da análise. Toque em \"Buscar concorrentes agora\" para consultar os resultados homologados no PNCP (pode levar até 1 minuto; o PNCP limita consultas)."
                                else "Toque em \"Buscar concorrentes agora\" para consultar os resultados homologados no PNCP.",
                                Tone.INFO,
                            )
                        }
                    }
                    if (view != null) {
                        val agency = view.agencyCompetitors
                        item(key = "agency-h") { SectionHeader("Mesmo órgão · objeto semelhante (${agency.size})") }
                        if (agency.isEmpty()) {
                            item(key = "agency-empty") {
                                Text(
                                    if (view.agencyCnpj == null) "Sem número de controle PNCP: não dá para identificar o órgão." else "Nenhum resultado do órgão na base ainda.",
                                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted,
                                )
                            }
                        }
                        items(agency, key = { "a:" + it.document + it.name }) { CompetitorCard(it) }
                        val segment = view.segmentCompetitors
                        item(key = "segment-h") { SectionHeader("Segmento (${segment.size})") }
                        if (segment.isEmpty()) {
                            item(key = "segment-empty") { Text("Nenhum resultado do segmento na base ainda.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted) }
                        }
                        items(segment, key = { "s:" + it.document + it.name }) { CompetitorCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompetitorCard(stat: CompetitorStat) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(stat.name, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                val doc = CompetitorRanking.formatDocument(stat.document)
                Text(if (doc.isEmpty()) "CNPJ não publicado" else "CNPJ $doc", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
            }
            StatusBadge("${stat.wins} vitória(s)", Tone.INFO)
        }
        Spacer(Modifier.height(6.dp))
        InfoRow("Valor médio homologado", Formatters.brl(TenderCompetitors.averageHomologated(stat)))
        InfoRow("Desconto médio vs estimado", stat.avgDiscountPct?.let { Formatters.percent(it) } ?: "—")
        InfoRow("Última vitória", if (stat.lastWinAt > 0) Formatters.date(stat.lastWinAt) else "—")
        if (stat.contracts > 1) InfoRow("Contratações", "${stat.contracts}")
    }
}

/** Ícone do item "Concorrência histórica" (reuso na tela "Vale a pena participar?"). */
internal val CompetitorsIcon = Icons.Outlined.Groups
