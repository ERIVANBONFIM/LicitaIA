package com.licitaia.feature.tender

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TenderRow(val tender: Tender, val analysis: TenderAnalysis?)

data class TenderListState(
    val loading: Boolean = true,
    val error: String? = null,
    val rows: List<TenderRow> = emptyList(),
    val role: UserRole? = null,
    /** Ids com ação em andamento. */
    val busy: Set<Long> = emptySet(),
) {
    val canAnalyze: Boolean get() = role?.let { Rbac.can(it, Permission.ANALISAR) } ?: false
}

/** Lista de licitações da empresa com a análise correspondente — base de Interesse / Analisar / Participações. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TenderListViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val tenders: TenderRepository,
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val busy = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())

    private val rows = auth.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(TenderListState(loading = false, error = "Sessão encerrada. Entre novamente."))
        } else {
            tenders.observeTenders(session.activeCompany.id)
                .flatMapLatest { list ->
                    if (list.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(list.map { t -> tenders.observeAnalysis(t.id).catch { emit(null) }.map { TenderRow(t, it) } }) { it.toList() }
                    }
                }
                .map { rows -> TenderListState(loading = false, rows = rows.sortedByDescending { it.tender.updatedAt }, role = session.user.role) }
                .catch { emit(TenderListState(loading = false, error = "Não foi possível carregar as licitações.")) }
        }
    }

    val state: StateFlow<TenderListState> = combine(rows, busy) { s, b -> s.copy(busy = b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderListState())

    /** "Arquivar": some desta lista, dos avisos e robôs; tudo fica guardado em "Licitações arquivadas". */
    fun archive(tender: Tender) = withBusy(tender.id, "Não foi possível arquivar a licitação.") {
        tenders.archive(tender.id, true)
        _messages.tryEmit("${tender.number} arquivada (dados preservados). Veja em \"Licitações arquivadas\".")
    }

    fun removeInterest(tender: Tender) = withBusy(tender.id, "Não foi possível remover a licitação.") {
        tenders.removeInterest(tender.id)
        _messages.tryEmit("${tender.number} removida das licitações de interesse")
    }

    /** (Re)executa a análise de IA sem sair da lista. */
    fun analyze(tender: Tender) = withBusy(tender.id, "A análise falhou. Tente novamente.") {
        tenders.analyze(tender.id).fold(
            onSuccess = { _messages.tryEmit("Análise de ${tender.number} concluída: ${it.recommendation.label}") },
            onFailure = { throw it },
        )
    }

    private fun withBusy(id: Long, errorMessage: String, block: suspend () -> Unit) {
        if (id in busy.value) return
        busy.value = busy.value + id
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message?.takeIf(String::isNotBlank) ?: errorMessage)
            } finally {
                busy.value = busy.value - id
            }
        }
    }
}
