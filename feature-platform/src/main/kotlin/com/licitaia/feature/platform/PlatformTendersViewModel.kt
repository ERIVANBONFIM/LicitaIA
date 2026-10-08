package com.licitaia.feature.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.TenderFilter
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.session.PlatformSession
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
 * Recortes da lista de licitações. Agora TODOS são resolvidos no SERVIDOR (o backend respeita
 * `favorita`/`status`; Participações usa `GET /licitacoes/minhas`). A busca textual usa `busca=`.
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
)

@HiltViewModel
class PlatformTendersViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {

    val session: StateFlow<PlatformSession> = repository.session

    /** Lista exibida: resultado da consulta ao servidor para o recorte/busca atuais. */
    private val _tenders = MutableStateFlow<List<PlatformTenderEntity>>(emptyList())
    val tenders: StateFlow<List<PlatformTenderEntity>> = _tenders.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _recorte = MutableStateFlow(TenderRecorte.TODAS)
    val recorte: StateFlow<TenderRecorte> = _recorte.asStateFlow()

    val pendingMutations: StateFlow<Int> =
        repository.observePendingMutations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _sync = MutableStateFlow(PlatformSyncUi())
    val sync: StateFlow<PlatformSyncUi> = _sync.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch { repository.ensureSessionLoaded() }
        load()
    }

    fun onQuery(v: String) = _query.update { v }

    /** Troca de chip → consulta o servidor com o filtro certo. */
    fun setRecorte(r: TenderRecorte) {
        if (_recorte.value == r) return
        _recorte.update { r }
        load()
    }

    /** Busca textual (ação do teclado) e botão atualizar: ambos reconsultam o servidor. */
    fun search() = load()
    fun refresh() = load()

    private fun load() {
        val recorte = _recorte.value
        val query = _query.value
        _sync.update { it.copy(syncing = true, message = null, isError = false) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val result = repository.fetchTenders(recorte.filter, query)
            result.fold(
                onSuccess = { list ->
                    _tenders.value = list
                    _sync.update { PlatformSyncUi(syncing = false, message = "${list.size} licitação(ões).") }
                },
                onFailure = { e ->
                    // Cancelado por uma consulta mais nova (troca de chip/busca): ignora silenciosamente.
                    if (e is kotlinx.coroutines.CancellationException) return@fold
                    // Offline: para "Todas" sem busca, mostra o espelho local; nos recortes, lista vazia + aviso.
                    if (recorte == TenderRecorte.TODAS && query.isBlank()) {
                        _tenders.value = repository.mirrorSnapshot()
                    }
                    _sync.update { PlatformSyncUi(syncing = false, message = e.message ?: "Falha ao consultar a plataforma.", isError = true) }
                },
            )
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }
}
