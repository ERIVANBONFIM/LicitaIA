package com.licitaia.feature.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.session.PlatformSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Recortes da lista de licitações da plataforma. Todos derivam de `/licitacoes`.
 *
 * O backend hoje NÃO filtra por `favorita`/`status` na query (confirmado via curl: os parâmetros são
 * ignorados), então os recortes são aplicados sobre o espelho local já sincronizado. A busca textual
 * (`busca=`) é a única que o servidor filtra — por isso ela dispara um pull específico.
 */
enum class TenderRecorte { TODAS, INTERESSE, ARQUIVADAS, PARTICIPACOES }

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

    private val allTenders: StateFlow<List<PlatformTenderEntity>> =
        repository.observeTenders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _recorte = MutableStateFlow(TenderRecorte.TODAS)
    val recorte: StateFlow<TenderRecorte> = _recorte.asStateFlow()

    /** Lista exibida = espelho filtrado pelo recorte e pelo texto digitado. */
    val tenders: StateFlow<List<PlatformTenderEntity>> =
        combine(allTenders, _query, _recorte) { list, q, recorte ->
            val byRecorte = when (recorte) {
                TenderRecorte.TODAS -> list
                TenderRecorte.INTERESSE -> list.filter { it.favorita }
                TenderRecorte.ARQUIVADAS -> list.filter { it.status.equals("arquivada", ignoreCase = true) }
                TenderRecorte.PARTICIPACOES -> list.filter {
                    !it.urlProposta.isNullOrBlank() || it.fase == "fase_lance" || it.fase == "homologada"
                }
            }
            val termo = q.trim()
            if (termo.isBlank()) byRecorte
            else byRecorte.filter {
                it.numero.contains(termo, true) || it.orgao.contains(termo, true) ||
                    it.objeto.contains(termo, true) || (it.cidade?.contains(termo, true) == true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingMutations: StateFlow<Int> =
        repository.observePendingMutations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _sync = MutableStateFlow(PlatformSyncUi())
    val sync: StateFlow<PlatformSyncUi> = _sync.asStateFlow()

    init {
        viewModelScope.launch { repository.ensureSessionLoaded() }
        refresh()
    }

    fun onQuery(v: String) = _query.update { v }

    fun setRecorte(r: TenderRecorte) = _recorte.update { r }

    /** Sincronização incremental padrão (últimas licitações da empresa). */
    fun refresh() {
        if (_sync.value.syncing) return
        _sync.update { it.copy(syncing = true, message = null, isError = false) }
        viewModelScope.launch {
            val result = repository.syncTenders()
            _sync.update {
                result.fold(
                    onSuccess = { r -> PlatformSyncUi(syncing = false, message = "Sincronizado: ${r.totalLocal} licitações.") },
                    onFailure = { e -> PlatformSyncUi(syncing = false, message = e.message ?: "Falha ao sincronizar.", isError = true) },
                )
            }
        }
    }

    /** Busca textual no servidor (`busca=`), trazendo correspondências que ainda não estavam no espelho. */
    fun search() {
        val termo = _query.value.trim()
        if (termo.isBlank() || _sync.value.syncing) {
            if (termo.isBlank()) refresh()
            return
        }
        _sync.update { it.copy(syncing = true, message = null, isError = false) }
        viewModelScope.launch {
            val result = repository.searchTenders(termo)
            _sync.update {
                result.fold(
                    onSuccess = { r -> PlatformSyncUi(syncing = false, message = "Busca \"$termo\": ${r.fetched} resultado(s) da plataforma.") },
                    onFailure = { e -> PlatformSyncUi(syncing = false, message = e.message ?: "Falha na busca.", isError = true) },
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }
}
