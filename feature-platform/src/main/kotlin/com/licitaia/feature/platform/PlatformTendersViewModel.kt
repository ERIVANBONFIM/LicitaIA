package com.licitaia.feature.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.TenderFilter
import com.licitaia.core.platform.TenderFiltros
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.domain.network.ConnectivityMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Recortes da lista de licitações. Online: resolvidos no SERVIDOR (o backend respeita `favorita`/`status`;
 * Participações usa `GET /licitacoes/minhas`; busca usa `busca=`). Offline: aplicados sobre o espelho local.
 */
enum class TenderRecorte(val filter: TenderFilter) {
    TODAS(TenderFilter.TODAS),
    INTERESSE(TenderFilter.INTERESSE),
    ARQUIVADAS(TenderFilter.ARQUIVADAS),
    PARTICIPACOES(TenderFilter.PARTICIPACOES),
}

data class PlatformSyncUi(
    val syncing: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    /** true = sem conexão, exibindo dados salvos (espelho local). */
    val offline: Boolean = false,
)

@HiltViewModel
class PlatformTendersViewModel @Inject constructor(
    private val repository: PlatformRepository,
    private val connectivity: ConnectivityMonitor,
) : ViewModel() {

    val session: StateFlow<PlatformSession> = repository.session

    /** Lista exibida: resultado da consulta ao servidor (online) ou o espelho local filtrado (offline). */
    private val _tenders = MutableStateFlow<List<PlatformTenderEntity>>(emptyList())
    val tenders: StateFlow<List<PlatformTenderEntity>> = _tenders.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _recorte = MutableStateFlow(TenderRecorte.TODAS)
    val recorte: StateFlow<TenderRecorte> = _recorte.asStateFlow()

    private val _filtros = MutableStateFlow(TenderFiltros())
    val filtros: StateFlow<TenderFiltros> = _filtros.asStateFlow()

    val pendingMutations: StateFlow<Int> =
        repository.observePendingMutations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _sync = MutableStateFlow(PlatformSyncUi())
    val sync: StateFlow<PlatformSyncUi> = _sync.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch { repository.ensureSessionLoaded() }
        load()
        // Reconectou → re-sincroniza automaticamente o recorte atual.
        viewModelScope.launch {
            var wasOnline = connectivity.online.value
            connectivity.online.collect { online ->
                if (online && !wasOnline) load()
                wasOnline = online
            }
        }
    }

    fun onQuery(v: String) = _query.update { v }

    /** Troca de chip → reconsulta (servidor online, espelho offline). */
    fun setRecorte(r: TenderRecorte) {
        if (_recorte.value == r) return
        _recorte.update { r }
        load()
    }

    /** Aplica filtros avançados e reconsulta. */
    fun applyFiltros(f: TenderFiltros) {
        _filtros.update { f }
        load()
    }

    fun clearFiltros() {
        if (_filtros.value.isEmpty) return
        _filtros.update { TenderFiltros() }
        load()
    }

    /** Busca textual (ação do teclado) e botão atualizar. */
    fun search() = load()
    fun refresh() = load()

    private fun load() {
        val recorte = _recorte.value
        val query = _query.value
        loadJob?.cancel()
        val filtros = _filtros.value
        // Sem conexão (celular): mostra já o espelho local, sem tentar a rede.
        if (!connectivity.hasNetwork) {
            loadJob = viewModelScope.launch { showMirror(recorte, query, reason = "Sem conexão · mostrando dados salvos") }
            return
        }
        _sync.update { it.copy(syncing = true, message = null, isError = false, offline = false) }
        loadJob = viewModelScope.launch {
            val result = repository.fetchTenders(recorte.filter, query, filtros)
            result.fold(
                onSuccess = { list ->
                    _tenders.value = list
                    _sync.update { PlatformSyncUi(syncing = false, message = "${list.size} licitação(ões).") }
                },
                onFailure = { e ->
                    // Cancelado por uma consulta mais nova (troca de chip/busca): ignora silenciosamente.
                    if (e is kotlinx.coroutines.CancellationException) return@fold
                    // VPS/rede indisponível: cai para o espelho local filtrado, com aviso claro.
                    showMirror(recorte, query, reason = "Sem conexão com a plataforma · mostrando dados salvos")
                },
            )
        }
    }

    /** Preenche a lista com o espelho local filtrado pelo recorte/busca e marca o estado offline. */
    private suspend fun showMirror(recorte: TenderRecorte, query: String, reason: String) {
        val mirror = repository.mirrorSnapshot()
        val filtered = filterMirror(mirror, recorte, query)
        _tenders.value = filtered
        _sync.update {
            PlatformSyncUi(
                syncing = false,
                offline = true,
                isError = false,
                message = if (filtered.isEmpty()) "$reason (nada salvo neste recorte)" else reason,
            )
        }
    }

    private fun filterMirror(list: List<PlatformTenderEntity>, recorte: TenderRecorte, query: String): List<PlatformTenderEntity> {
        val byRecorte = when (recorte) {
            TenderRecorte.TODAS -> list
            TenderRecorte.INTERESSE -> list.filter { it.favorita }
            TenderRecorte.ARQUIVADAS -> list.filter { it.status.equals("arquivada", ignoreCase = true) }
            TenderRecorte.PARTICIPACOES -> list.filter {
                !it.urlProposta.isNullOrBlank() || it.fase == "fase_lance" || it.fase == "homologada"
            }
        }
        val termo = query.trim()
        return if (termo.isBlank()) byRecorte
        else byRecorte.filter {
            it.numero.contains(termo, true) || it.orgao.contains(termo, true) ||
                it.objeto.contains(termo, true) || (it.cidade?.contains(termo, true) == true)
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }
}
