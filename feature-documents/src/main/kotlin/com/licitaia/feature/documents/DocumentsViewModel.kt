package com.licitaia.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.Segment
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.DocumentRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class DocumentRow(
    val document: CompanyDocument,
    val status: DocumentStatus,
    val daysToExpire: Long?,
)

data class DocumentsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val noSession: Boolean = false,
    val canEdit: Boolean = false,
    val roleLabel: String = "",
    val all: List<DocumentRow> = emptyList(),
    val visible: List<DocumentRow> = emptyList(),
    val missingTypes: List<DocumentType> = emptyList(),
    val statusFilter: DocumentStatus? = null,
    val typeFilter: DocumentType? = null,
) {
    val validCount get() = all.count { it.status == DocumentStatus.VALIDO }
    val expiringCount get() = all.count { it.status == DocumentStatus.VENCE_EM_BREVE }
    val expiredCount get() = all.count { it.status == DocumentStatus.VENCIDO }
    val missingCount get() = missingTypes.size + all.count { it.status == DocumentStatus.AUSENTE }
    val typesInUse: List<DocumentType> get() = all.map { it.document.type }.distinct().sortedBy { it.ordinal }
}

/** Tipos exigidos na habilitação da maioria dos editais (SCM apenas para Telecom/ISP). */
internal fun requiredTypes(segment: Segment): List<DocumentType> = buildList {
    add(DocumentType.CONTRATO_SOCIAL)
    add(DocumentType.CNPJ)
    add(DocumentType.CERTIDAO_FEDERAL)
    add(DocumentType.CERTIDAO_ESTADUAL)
    add(DocumentType.CERTIDAO_MUNICIPAL)
    add(DocumentType.FGTS)
    add(DocumentType.TRABALHISTA)
    add(DocumentType.BALANCO)
    if (segment == Segment.TELECOM_ISP) add(DocumentType.SCM)
    add(DocumentType.ATESTADO)
}

private data class Filters(val status: DocumentStatus? = null, val type: DocumentType? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentsViewModel @Inject constructor(
    auth: AuthRepository,
    private val documents: DocumentRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(Filters())
    private val retry = MutableStateFlow(0)

    val state: StateFlow<DocumentsUiState> = combine(auth.session, retry) { s, _ -> s }
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(DocumentsUiState(loading = false, noSession = true))
            } else {
                val base: Flow<DocumentsUiState> = documents.observeDocuments(session.activeCompany.id).map { list ->
                    val now = System.currentTimeMillis()
                    val rows = list.map { DocumentRow(it, it.status(now), it.daysToExpire(now)) }
                        .sortedWith(compareBy({ statusOrder(it.status) }, { it.daysToExpire ?: Long.MAX_VALUE }))
                    val present = list.map { it.type }.toSet()
                    DocumentsUiState(
                        loading = false,
                        canEdit = Rbac.can(session.user.role, Permission.GERENCIAR_DOCUMENTOS),
                        roleLabel = session.user.role.label,
                        all = rows,
                        missingTypes = requiredTypes(session.activeCompany.segment).filter { it !in present },
                    )
                }
                combine(base, filters) { ui, f ->
                    ui.copy(
                        statusFilter = f.status,
                        typeFilter = f.type,
                        visible = ui.all.filter { row ->
                            (f.status == null || row.status == f.status) && (f.type == null || row.document.type == f.type)
                        },
                    )
                }.catch { emit(DocumentsUiState(loading = false, error = it.message ?: "Falha ao carregar os documentos.")) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsUiState())

    fun setStatusFilter(status: DocumentStatus?) = filters.update { it.copy(status = status) }
    fun setTypeFilter(type: DocumentType?) = filters.update { it.copy(type = type) }
    fun retry() = retry.update { it + 1 }

    private fun statusOrder(status: DocumentStatus) = when (status) {
        DocumentStatus.VENCIDO -> 0
        DocumentStatus.VENCE_EM_BREVE -> 1
        DocumentStatus.AUSENTE -> 2
        DocumentStatus.VALIDO -> 3
    }
}
