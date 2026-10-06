package com.licitaia.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.DocumentRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentForm(
    val id: Long = 0,
    val companyId: Long = 0,
    val createdAt: Long = 0,
    val type: DocumentType = DocumentType.OUTROS,
    val title: String = "",
    val issuer: String = "",
    val issuedAt: Long? = null,
    val expiresAt: Long? = null,
    val attachmentUri: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String = "",
) {
    fun toDocument() = CompanyDocument(
        id = id, companyId = companyId, type = type, title = title.trim(),
        issuer = issuer.trim().ifBlank { null }, issuedAt = issuedAt, expiresAt = expiresAt,
        attachmentUri = attachmentUri, tags = tags, notes = notes.trim(), createdAt = createdAt,
    )
}

data class DocumentEditUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val isNew: Boolean = true,
    val canEdit: Boolean = false,
    val roleLabel: String = "",
    val companyName: String = "",
    val form: DocumentForm = DocumentForm(),
    val saving: Boolean = false,
    val titleError: String? = null,
    val dateError: String? = null,
) {
    val status: DocumentStatus get() = form.toDocument().status(System.currentTimeMillis())
}

sealed interface DocumentEditEvent {
    data class Message(val text: String) : DocumentEditEvent
    data class Closed(val text: String) : DocumentEditEvent
}

@HiltViewModel
class DocumentEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val documents: DocumentRepository,
) : ViewModel() {

    private val rawId: Long? = when (val raw: Any? = savedStateHandle["documentId"]) {
        is Long -> raw
        is Int -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }

    private val _state = MutableStateFlow(DocumentEditUiState())
    val state: StateFlow<DocumentEditUiState> = _state.asStateFlow()

    private val _events = Channel<DocumentEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val session = auth.session.value
            if (session == null) {
                _state.update { it.copy(loading = false, error = "Sessão encerrada. Entre novamente.") }
                return@launch
            }
            val canEdit = Rbac.can(session.user.role, Permission.GERENCIAR_DOCUMENTOS)
            val id = rawId
            if (id == null) {
                _state.update { it.copy(loading = false, error = "Documento inválido.") }
                return@launch
            }
            if (id <= 0) {
                val preset = DocumentType.entries.getOrNull((-id - 100).toInt())
                val type = preset ?: DocumentType.CONTRATO_SOCIAL
                _state.value = DocumentEditUiState(
                    loading = false, isNew = true, canEdit = canEdit, roleLabel = session.user.role.label,
                    companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name },
                    form = DocumentForm(companyId = session.activeCompany.id, type = type, title = if (preset != null) preset.label else ""),
                )
                return@launch
            }
            runCatching { documents.getDocument(id) }
                .onSuccess { doc ->
                    if (doc == null || doc.companyId != session.activeCompany.id) {
                        _state.update { it.copy(loading = false, error = "Documento não encontrado nesta empresa.") }
                    } else {
                        _state.value = DocumentEditUiState(
                            loading = false, isNew = false, canEdit = canEdit, roleLabel = session.user.role.label,
                            companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name },
                            form = DocumentForm(
                                id = doc.id, companyId = doc.companyId, createdAt = doc.createdAt, type = doc.type,
                                title = doc.title, issuer = doc.issuer.orEmpty(), issuedAt = doc.issuedAt,
                                expiresAt = doc.expiresAt, attachmentUri = doc.attachmentUri, tags = doc.tags, notes = doc.notes,
                            ),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Falha ao carregar o documento.") } }
        }
    }

    fun edit(transform: (DocumentForm) -> DocumentForm) {
        if (!_state.value.canEdit) return
        _state.update { it.copy(form = transform(it.form), titleError = null, dateError = null) }
    }

    fun addTag(raw: String) {
        val tag = raw.trim().trimStart('#').lowercase()
        if (tag.isBlank()) return
        edit { if (tag in it.tags || it.tags.size >= 12) it else it.copy(tags = it.tags + tag) }
    }

    fun removeTag(tag: String) = edit { it.copy(tags = it.tags - tag) }

    fun save() {
        val current = _state.value
        if (!current.canEdit || current.saving) return
        val form = current.form
        val titleError = if (form.title.isBlank()) "Informe o título do documento" else null
        val dateError = if (form.issuedAt != null && form.expiresAt != null && form.expiresAt < form.issuedAt) {
            "A validade não pode ser anterior à emissão"
        } else null
        if (titleError != null || dateError != null) {
            _state.update { it.copy(titleError = titleError, dateError = dateError) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val doc = form.toDocument().let { if (it.createdAt == 0L) it.copy(createdAt = System.currentTimeMillis()) else it }
            // A auditoria (CADASTRO) é registrada pelo repositório.
            runCatching { documents.upsert(doc) }
                .onSuccess {
                    _events.send(DocumentEditEvent.Closed(if (current.isNew) "Documento cadastrado" else "Documento atualizado"))
                }
                .onFailure { e ->
                    _state.update { it.copy(saving = false) }
                    _events.send(DocumentEditEvent.Message(e.message ?: "Não foi possível salvar o documento"))
                }
        }
    }

    fun delete() {
        val current = _state.value
        if (!current.canEdit || current.isNew || current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { documents.delete(current.form.id) }
                .onSuccess {
                    _events.send(DocumentEditEvent.Closed("Documento excluído"))
                }
                .onFailure { e ->
                    _state.update { it.copy(saving = false) }
                    _events.send(DocumentEditEvent.Message(e.message ?: "Não foi possível excluir o documento"))
                }
        }
    }
}
