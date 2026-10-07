package com.licitaia.feature.radar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.AiScoreUpdate
import com.licitaia.domain.model.AiScoringRequest
import com.licitaia.domain.model.LowAdherence
import com.licitaia.domain.model.ProposalWindows
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.SectionedOpportunities
import com.licitaia.domain.model.SearchOutcome
import com.licitaia.domain.scoring.AiScoreMerge
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import java.util.Calendar
import java.util.TimeZone
import com.licitaia.domain.sync.DailySyncRepository
import com.licitaia.domain.sync.DailySyncSchedule
import com.licitaia.domain.sync.DailySyncSettings
import com.licitaia.domain.sync.DailySyncStatus
import com.licitaia.domain.sync.ForegroundListingRefresh
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal val BRAZIL_UFS = listOf(
    "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA",
    "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO",
)

internal val BRAZIL_REGIONS = listOf("Norte", "Nordeste", "Centro-Oeste", "Sudeste", "Sul")

/** Argumentos de rota chegam como String (sem navArgument tipado) — aceita ambos. */
internal fun SavedStateHandle.longArg(name: String): Long? = get<Any?>(name)?.toString()?.toLongOrNull()

/** Aceita "1.234,56", "1234.56" e "1234". Vazio → null. Inválido → NaN. */
internal fun parseMoney(text: String): Double? {
    val raw = text.trim().replace("R$", "").replace(" ", "")
    if (raw.isEmpty()) return null
    val normalized = if (raw.contains(',')) raw.replace(".", "").replace(',', '.') else raw
    return normalized.toDoubleOrNull()?.takeIf { it >= 0 } ?: Double.NaN
}

internal fun moneyText(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> String.format(java.util.Locale.US, "%.2f", value).replace('.', ',')
}

/** O DatePicker devolve meia-noite UTC; convertemos para meia-noite local para exibir o dia certo. */
internal fun utcToLocalMidnight(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

internal fun localToUtcMidnight(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

sealed interface OpportunityEvent {
    data class Message(val text: String) : OpportunityEvent
    data class Navigate(val route: String) : OpportunityEvent
}

data class OpportunityListState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<ScoredOpportunity> = emptyList(),
    /** Ids de oportunidade com ação em andamento. */
    val busy: Set<String> = emptySet(),
    val canAnalyze: Boolean = true,
    /** Atualização em segundo plano (automática ou puxar para atualizar) com a lista atual ainda visível. */
    val refreshing: Boolean = false,
    /** Quando a lista foi obtida das fontes pela última vez (null = ainda não / só cache). */
    val updatedAt: Long? = null,
    /** Sem internet no momento: a lista (se houver) vem do cache local. */
    val offline: Boolean = false,
    /** Quantos itens cada fonte trouxe na última consulta (ex.: "PNCP 120 · Compras.gov.br 35"). */
    val sourceSummary: String? = null,
    /** Itens com nota por IA esperada nesta execução (0 = sem provedor real ou nada a avaliar). */
    val aiTotal: Int = 0,
    /** Quantos desses já têm a nota por IA (cache + lotes recebidos). */
    val aiRated: Int = 0,
    /** A IA parou (sem rede/erro): os itens restantes ficam com a nota heurística. */
    val aiFailure: String? = null,
    /** [items] agrupados nas seções fixas Hoje → Próximos dias → Vão abrir (pelo relógio da última atualização). */
    val sections: List<SectionedOpportunities> = emptyList(),
    /** Andamento da sincronização das fontes ("Sincronizando Compras.gov.br… 4.500 linhas"); null sem sincronização. */
    val syncLabel: String? = null,
    /** O último resultado é PARCIAL (varredura completa em andamento): refeito automaticamente ao terminar. */
    val partialSync: Boolean = false,
    /** Causa real da lista vazia (sincronizando, nada casou, tudo oculto, abaixo do score). */
    val emptyReason: String? = null,
    /** Atualização pedida pelo usuário (puxar/botão): só ela mostra o indicador grande do pull-to-refresh. */
    val userRefreshing: Boolean = false,
    /** Itens de baixa aderência (nota < [com.licitaia.domain.model.LowAdherence.THRESHOLD]) ocultos (só na Busca). */
    val hiddenLow: List<ScoredOpportunity> = emptyList(),
    /** "Mostrar baixa aderência" ligado. */
    val showLowAdherence: Boolean = false,
    /**
     * Linha da atualização diária: "Atualizado hoje às 05:30 · Próxima atualização automática: amanhã 05:30" (null sem
     * atualização diária nesta tela).
     */
    val scheduleLine: String? = null,
    /** A lista atual veio só do que está salvo no aparelho (abertura da tela), sem consultar as fontes. */
    val fromSnapshot: Boolean = false,
) {
    /** Há itens aguardando a nota por IA. */
    val aiPending: Boolean get() = aiTotal > 0 && aiRated < aiTotal && aiFailure == null

    /** Todos os itens (visíveis + baixa aderência ocultos) — base das mesclagens de nota. */
    val allItems: List<ScoredOpportunity> get() = if (hiddenLow.isEmpty()) items else items + hiddenLow
}

/** Base das telas que listam oportunidades (busca e resultados de radar). */
abstract class OpportunityListViewModel(
    protected val auth: AuthRepository,
    private val tenders: TenderRepository,
    private val connectivity: ConnectivityMonitor,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _list = MutableStateFlow(OpportunityListState())
    val list: StateFlow<OpportunityListState> = _list.asStateFlow()

    private val _events = MutableSharedFlow<OpportunityEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<OpportunityEvent> = _events.asSharedFlow()

    private var loadJob: Job? = null
    private var aiJob: Job? = null

    /**
     * [cacheOnly] = abertura da tela/atualização diária concluída: só o que já está salvo no aparelho (sem consultar as
     * fontes). false = puxar para atualizar/botão/busca digitada: consulta as fontes (incremental leve).
     */
    protected abstract suspend fun fetch(companyId: Long, cacheOnly: Boolean): Result<SearchOutcome>

    /** Notas por IA para os candidatos da última consulta (padrão: nenhuma). */
    protected open fun aiScores(request: AiScoringRequest): Flow<AiScoreUpdate> = emptyFlow()

    /** Andamento da sincronização das fontes (padrão: nenhum). */
    protected open fun sourceSync(): Flow<String?> = emptyFlow()

    /** Atualização diária (05:30): configuração e estado; null = tela sem atualização diária. */
    protected open val dailySync: DailySyncRepository? get() = null

    /** Último [DailySyncStatus.lastCompletedAt] visto (a lista é relida do cache quando ele avança). */
    private var lastDailyCompletedAt: Long? = null
    private var dailySeen = false
    private var dailySettings: DailySyncSettings = DailySyncSettings()
    private var dailyStatus: DailySyncStatus = DailySyncStatus()

    /**
     * Nota abaixo da qual os itens ficam ocultos por padrão (null = sem filtro; os radares usam o próprio score mínimo).
     */
    protected open val lowAdherenceThreshold: Int? get() = null

    /** Liga/desliga "Mostrar baixa aderência" (reagrupa a lista atual, sem nova consulta). */
    fun setShowLowAdherence(show: Boolean) {
        if (_list.value.showLowAdherence == show) return
        viewModelScope.launch {
            val base = _list.value
            val g = grouped(base.allItems, show)
            _list.update { state ->
                val r = if (state.items === base.items && state.hiddenLow === base.hiddenLow) g else group(state.allItems, show)
                state.copy(showLowAdherence = show, items = r.items, sections = r.sections, hiddenLow = r.hiddenLow)
            }
        }
    }

    /**
     * Pede as notas por IA SEM bloquear a lista: os resultados heurísticos já estão visíveis e cada lote recebido
     * atualiza nota/ordem (itens aprovados entram, reprovados saem). Uma nova consulta cancela a anterior.
     */
    private fun startAiScoring(request: AiScoringRequest?) {
        aiJob?.cancel()
        if (request == null || request.candidates.isEmpty()) return
        aiJob = viewModelScope.launch {
            try {
                aiScores(request).collect { update ->
                    // Mescla/ordena fora da main thread; se a lista mudou nesse meio-tempo, refaz sobre a atual.
                    val base = _list.value
                    val merged = grouped(AiScoreMerge.merge(base.allItems, update.rated, request.minScore), base.showLowAdherence)
                    _list.update { state ->
                        val g = if (state.items === base.items && state.hiddenLow === base.hiddenLow && state.showLowAdherence == base.showLowAdherence) merged
                        else group(AiScoreMerge.merge(state.allItems, update.rated, request.minScore), state.showLowAdherence)
                        state.copy(
                            items = g.items, sections = g.sections, hiddenLow = g.hiddenLow,
                            aiRated = (state.aiRated + update.rated.size).coerceAtMost(state.aiTotal),
                            aiFailure = update.failure ?: state.aiFailure,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _list.update { it.copy(aiFailure = "IA indisponível (${e.message ?: "falha"}). Notas heurísticas mantidas.") }
            }
        }
    }

    /**
     * Chamado no init das subclasses: recarrega a cada troca de empresa ativa e, quando a internet volta,
     * recarrega se a última tentativa falhou ou mostrou só o cache.
     */
    protected fun start() {
        viewModelScope.launch {
            // Abrir a tela / trocar de empresa: mostra na hora o que já está salvo (sem baixar tudo de novo).
            auth.session.map { it?.activeCompany?.id }.distinctUntilChanged().collect { load(silent = false, cacheOnly = true) }
        }
        dailySync?.let { daily ->
            // Recuperação (a das 05:30 não rodou) e limpeza do dia, em segundo plano, sem bloquear a tela.
            daily.onAppOpened()
            viewModelScope.launch {
                combine(daily.settings, daily.status) { settings, status -> settings to status }.collect { (settings, status) ->
                    dailySettings = settings
                    dailyStatus = status
                    _list.update { it.copy(scheduleLine = DailySyncSchedule.sourceLine(status, settings, clock())) }
                    // A atualização diária (ou a de recuperação) terminou com a tela aberta: relê o cache, sem rede.
                    val completed = status.lastCompletedAt
                    val previous = lastDailyCompletedAt
                    val first = !dailySeen
                    dailySeen = true
                    lastDailyCompletedAt = completed
                    if (!first && completed != null && completed != previous && loadJob?.isActive != true) {
                        load(silent = _list.value.allItems.isNotEmpty(), cacheOnly = true)
                    }
                }
            }
        }
        viewModelScope.launch {
            // Progresso da varredura completa; ao terminar, refaz a lista se a última era parcial.
            sourceSync().distinctUntilChanged().collect { label ->
                val before = _list.value
                _list.update { it.copy(syncLabel = label) }
                if (label == null && before.syncLabel != null && before.partialSync && !before.loading && loadJob?.isActive != true) {
                    load(silent = before.allItems.isNotEmpty())
                }
            }
        }
        viewModelScope.launch {
            connectivity.online.drop(1).collect { online ->
                _list.update { it.copy(offline = !online) }
                val s = _list.value
                // A internet voltou depois de uma falha: tenta de novo (o que está salvo já aparece sem rede).
                if (online && !s.loading && s.error != null) load(silent = s.allItems.isNotEmpty())
            }
        }
    }

    /**
     * Recarrega mostrando o esqueleto de carregamento (troca de filtros, tentar novamente). Filtros sobre o que já está
     * salvo; a subclasse decide consultar as fontes (ex.: texto digitado).
     */
    fun reload() = load(silent = false, cacheOnly = true)

    /** Puxar para atualizar (ou botão): consulta as fontes (incremental leve), mantendo a lista visível e o indicador grande. */
    fun refresh() = load(silent = _list.value.allItems.isNotEmpty(), user = true)

    /**
     * Enquanto a tela está visível (o chamador cancela ao sair): a cada [CLOCK_REFRESH_MS] re-filtra a lista pelo
     * relógio, SEM rede — o que encerrou some, "Hoje" acompanha a virada do dia e a linha da atualização diária
     * ("Atualizado hoje às 05:30…") é refeita. Novas licitações chegam pela atualização diária (ou puxando para atualizar).
     */
    suspend fun clockRefreshLoop() {
        while (true) {
            delay(CLOCK_REFRESH_MS)
            val current = _list.value
            val g = grouped(current.allItems, current.showLowAdherence)
            _list.update {
                val line = if (dailySync != null) DailySyncSchedule.sourceLine(dailyStatus, dailySettings, clock()) else it.scheduleLine
                if (it.items === current.items && it.hiddenLow === current.hiddenLow && it.showLowAdherence == current.showLowAdherence) {
                    it.copy(items = g.items, sections = g.sections, hiddenLow = g.hiddenLow, scheduleLine = line)
                } else it.copy(scheduleLine = line)
            }
        }
    }

    private fun load(silent: Boolean, user: Boolean = false, cacheOnly: Boolean = false) {
        loadJob?.cancel()
        aiJob?.cancel()
        val job = viewModelScope.launch {
            val session = auth.session.value
            if (session == null) {
                _list.update {
                    it.copy(
                        loading = false, refreshing = false, userRefreshing = false, error = "Sessão encerrada. Entre novamente.",
                        items = emptyList(), sections = emptyList(), hiddenLow = emptyList(),
                    )
                }
                return@launch
            }
            val online = connectivity.isOnline
            _list.update {
                if (silent) {
                    it.copy(refreshing = true, userRefreshing = user, offline = !online, canAnalyze = Rbac.can(session.user.role, Permission.ANALISAR))
                } else {
                    it.copy(loading = true, refreshing = false, userRefreshing = false, error = null, offline = !online, canAnalyze = Rbac.can(session.user.role, Permission.ANALISAR))
                }
            }
            // Sem internet o repositório responde na hora (cache local ou erro "Sem internet"), sem esperar timeout.
            val result = try {
                if (cacheOnly) fetch(session.activeCompany.id, cacheOnly = true)
                // Consulta às fontes pedida pelo usuário: o Worker de alertas não concorre enquanto ela roda.
                else ForegroundListingRefresh.track { fetch(session.activeCompany.id, cacheOnly = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // Dedup e texto de diagnóstico calculados uma vez, fora da main thread.
            val showLow = _list.value.showLowAdherence
            val prepared = result.getOrNull()?.let { outcome ->
                grouped(outcome.items, showLow) to withContext(Dispatchers.Default) { outcome.sourceSummary }
            }
            _list.update { state ->
                result.fold(
                    onSuccess = {
                        val g = prepared?.first?.let { g -> if (state.showLowAdherence == showLow) g else group(it.items, state.showLowAdherence) }
                        state.copy(
                            loading = false, refreshing = false, userRefreshing = false, items = g?.items.orEmpty(),
                            sections = g?.sections.orEmpty(), hiddenLow = g?.hiddenLow.orEmpty(), error = null,
                            // Só o que está salvo: "atualizado" = fim da última atualização diária (ou o que já se sabia).
                            updatedAt = when {
                                it.cacheSnapshot -> dailyStatus.lastCompletedAt ?: state.updatedAt
                                online -> clock()
                                else -> state.updatedAt
                            },
                            fromSnapshot = it.cacheSnapshot,
                            offline = !online,
                            sourceSummary = prepared?.second,
                            aiTotal = it.aiRequest?.total ?: 0,
                            aiRated = it.aiRequest?.alreadyRated ?: 0,
                            aiFailure = null,
                            partialSync = it.syncing,
                            emptyReason = it.emptyReason,
                        )
                    },
                    onFailure = {
                        val message = it.message?.takeIf(String::isNotBlank) ?: "Falha ao consultar os portais. Tente novamente."
                        if (silent && state.allItems.isNotEmpty()) {
                            // Atualização em segundo plano falhou: mantém a lista atual e avisa discretamente.
                            if (online) _events.tryEmit(OpportunityEvent.Message("Não foi possível atualizar agora: $message"))
                            state.copy(loading = false, refreshing = false, userRefreshing = false, offline = !online)
                        } else {
                            state.copy(
                                loading = false, refreshing = false, userRefreshing = false, items = emptyList(), sections = emptyList(),
                                hiddenLow = emptyList(), error = message, offline = !online,
                            )
                        }
                    },
                )
            }
            startAiScoring(result.getOrNull()?.aiRequest)
            // Parcial e a sincronização já terminou antes de a lista ser aplicada: refaz logo (sem esperar 2 min).
            val s = _list.value
            if (s.partialSync && s.syncLabel == null) {
                delay(PARTIAL_RETRY_MS)
                if (_list.value.partialSync && _list.value.syncLabel == null) load(silent = _list.value.allItems.isNotEmpty())
            }
        }
        loadJob = job
        // Consulta cancelada sem substituta (ex.: tela fechada): nunca deixa um indicador de atualização "preso".
        job.invokeOnCompletion { cause ->
            if (cause != null && loadJob === job) _list.update { it.copy(refreshing = false, userRefreshing = false) }
        }
    }

    /** "Tenho Interesse" — ou abre a licitação quando já está em interesse. */
    fun onInterest(item: ScoredOpportunity) = withBusy(item) { companyId ->
        if (item.interested) {
            val existing = tenders.findByOpportunity(companyId, item.opportunity.id)
            val id = existing?.id ?: tenders.markInterest(companyId, item.opportunity)
            _events.tryEmit(OpportunityEvent.Navigate(Routes.tender(id)))
        } else {
            tenders.markInterest(companyId, item.opportunity)
            markInterested(item.opportunity.id)
            _events.tryEmit(OpportunityEvent.Message("Adicionada às Licitações de Interesse. A IA já está analisando o edital."))
        }
    }

    /** "Analisar" — marca interesse se preciso e abre a análise do edital. */
    fun onAnalyze(item: ScoredOpportunity) = withBusy(item) { companyId ->
        val id = tenders.findByOpportunity(companyId, item.opportunity.id)?.id
            ?: tenders.markInterest(companyId, item.opportunity)
        markInterested(item.opportunity.id)
        _events.tryEmit(OpportunityEvent.Navigate(Routes.tenderAnalysis(id)))
    }

    private fun markInterested(opportunityId: String) = _list.update { state ->
        fun List<ScoredOpportunity>.mark() = map { if (it.opportunity.id == opportunityId) it.copy(interested = true) else it }
        state.copy(
            items = state.items.mark(), sections = state.sections.map { it.copy(items = it.items.mark()) },
            hiddenLow = state.hiddenLow.mark(),
        )
    }

    /**
     * Separa a baixa aderência (só com [lowAdherenceThreshold]), agrupa (Hoje / Próximos dias / Vão abrir) e ordena
     * cada seção por nota e horário; encerradas somem.
     */
    private fun group(items: List<ScoredOpportunity>, showLow: Boolean): LowAdherence.Grouped =
        LowAdherence.group(items, clock(), lowAdherenceThreshold, showLow)

    /** [group] fora da main thread. */
    private suspend fun grouped(items: List<ScoredOpportunity>, showLow: Boolean) =
        withContext(Dispatchers.Default) { group(items, showLow) }

    private fun withBusy(item: ScoredOpportunity, block: suspend (companyId: Long) -> Unit) {
        val key = item.opportunity.id
        if (key in _list.value.busy) return
        val companyId = auth.session.value?.activeCompany?.id ?: run {
            _events.tryEmit(OpportunityEvent.Message("Sessão encerrada. Entre novamente."))
            return
        }
        _list.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            try {
                block(companyId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(OpportunityEvent.Message(e.message?.takeIf(String::isNotBlank) ?: "Não foi possível concluir a ação."))
            } finally {
                _list.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    internal companion object {
        /** Re-filtro local (sem rede) da lista pelo relógio com a tela aberta. */
        const val CLOCK_REFRESH_MS = 60L * 1000
        const val PARTIAL_RETRY_MS = 5_000L
    }
}
