package com.licitaia.feature.radar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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
)

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

    protected abstract suspend fun fetch(companyId: Long): Result<List<ScoredOpportunity>>

    /**
     * Chamado no init das subclasses: recarrega a cada troca de empresa ativa e, quando a internet volta,
     * recarrega se a última tentativa falhou ou mostrou só o cache.
     */
    protected fun start() {
        viewModelScope.launch {
            auth.session.map { it?.activeCompany?.id }.distinctUntilChanged().collect { reload() }
        }
        viewModelScope.launch {
            connectivity.online.drop(1).collect { online ->
                _list.update { it.copy(offline = !online) }
                val s = _list.value
                if (online && !s.loading && (s.error != null || s.updatedAt == null || s.offline)) load(silent = s.items.isNotEmpty())
            }
        }
    }

    /** Recarrega mostrando o esqueleto de carregamento (troca de filtros, tentar novamente). */
    fun reload() = load(silent = false)

    /** Puxar para atualizar: mantém a lista visível enquanto consulta. */
    fun refresh() = load(silent = _list.value.items.isNotEmpty())

    /**
     * Atualização automática enquanto a tela está visível (o chamador cancela ao sair): a cada
     * [AUTO_REFRESH_MS] desde a última atualização, só com internet e sem outra consulta em andamento.
     */
    suspend fun autoRefreshLoop() {
        while (true) {
            val last = _list.value.updatedAt ?: clock()
            delay((last + AUTO_REFRESH_MS - clock()).coerceAtLeast(MIN_AUTO_DELAY_MS))
            val s = _list.value
            if (connectivity.isOnline && !s.loading && !s.refreshing && loadJob?.isActive != true) load(silent = s.items.isNotEmpty())
        }
    }

    private fun load(silent: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val session = auth.session.value
            if (session == null) {
                _list.update { it.copy(loading = false, refreshing = false, error = "Sessão encerrada. Entre novamente.", items = emptyList()) }
                return@launch
            }
            val online = connectivity.isOnline
            _list.update {
                if (silent) {
                    it.copy(refreshing = true, offline = !online, canAnalyze = Rbac.can(session.user.role, Permission.ANALISAR))
                } else {
                    it.copy(loading = true, error = null, offline = !online, canAnalyze = Rbac.can(session.user.role, Permission.ANALISAR))
                }
            }
            // Sem internet o repositório responde na hora (cache local ou erro "Sem internet"), sem esperar timeout.
            val result = try {
                fetch(session.activeCompany.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _list.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(
                            loading = false, refreshing = false, items = it.distinctBy { s -> s.opportunity.id }, error = null,
                            updatedAt = if (online) clock() else state.updatedAt, offline = !online,
                        )
                    },
                    onFailure = {
                        val message = it.message?.takeIf(String::isNotBlank) ?: "Falha ao consultar os portais. Tente novamente."
                        if (silent && state.items.isNotEmpty()) {
                            // Atualização em segundo plano falhou: mantém a lista atual e avisa discretamente.
                            if (online) _events.tryEmit(OpportunityEvent.Message("Não foi possível atualizar agora: $message"))
                            state.copy(loading = false, refreshing = false, offline = !online)
                        } else {
                            state.copy(loading = false, refreshing = false, items = emptyList(), error = message, offline = !online)
                        }
                    },
                )
            }
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
        state.copy(items = state.items.map { if (it.opportunity.id == opportunityId) it.copy(interested = true) else it })
    }

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
        /** Atualização automática da busca/resultados com a tela aberta. */
        const val AUTO_REFRESH_MS = 2L * 60 * 1000
        const val MIN_AUTO_DELAY_MS = 5_000L
    }
}
