package com.licitaia.feature.documents

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.documents.CnpjCheck
import com.licitaia.domain.documents.CnpjMatch
import com.licitaia.domain.documents.DocumentField
import com.licitaia.domain.documents.DocumentNotesCodec
import com.licitaia.domain.documents.DocumentReader
import com.licitaia.domain.documents.DocumentReading
import com.licitaia.domain.documents.DocumentSuggestion
import com.licitaia.domain.documents.DocumentTextAnalyzer
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.DocumentRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.documents.files.AttachmentInfo
import com.licitaia.feature.documents.files.DocumentFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Número / código de controle (guardado nas observações, ver [DocumentNotesCodec]). */
    val number: String = "",
    /** CNPJ impresso no documento (guardado nas observações). */
    val cnpj: String = "",
) {
    fun toDocument() = CompanyDocument(
        id = id, companyId = companyId, type = type, title = title.trim(),
        issuer = issuer.trim().ifBlank { null }, issuedAt = issuedAt, expiresAt = expiresAt,
        attachmentUri = attachmentUri, tags = tags, notes = DocumentNotesCodec.encode(number, cnpj, notes), createdAt = createdAt,
    )
}

/** Origem de um campo preenchido automaticamente. */
enum class FieldOrigin(val label: String) {
    DOCUMENTO("Lido do documento"),
    IA("Sugerido pela IA — confira"),
}

/** Valor lido que não foi aplicado porque o campo já tinha outro conteúdo. */
data class SuggestionConflict(val field: String, val label: String, val value: String)

sealed interface ReadState {
    data object Idle : ReadState
    data class Running(val stage: String) : ReadState
    data class Done(val reading: DocumentReading, val applied: Int) : ReadState
    data class Failed(val message: String) : ReadState
}

data class DocumentEditUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val isNew: Boolean = true,
    val canEdit: Boolean = false,
    val roleLabel: String = "",
    val companyName: String = "",
    val companyCnpj: String = "",
    val form: DocumentForm = DocumentForm(),
    val saving: Boolean = false,
    val titleError: String? = null,
    val dateError: String? = null,
    /** Tipo definido pelo usuário (chip "+ Tipo", toque no chip ou documento existente). */
    val typeChosen: Boolean = false,
    val attaching: Boolean = false,
    val attachmentInfo: AttachmentInfo? = null,
    val read: ReadState = ReadState.Idle,
    val origins: Map<String, FieldOrigin> = emptyMap(),
    val conflicts: List<SuggestionConflict> = emptyList(),
    val pendingSuggestion: DocumentSuggestion? = null,
) {
    val status: DocumentStatus get() = form.toDocument().status(System.currentTimeMillis())
    val cnpjWarning: String? get() = CnpjCheck.warning(form.cnpj, companyCnpj)
    val cnpjMatch: CnpjMatch get() = CnpjCheck.compare(form.cnpj, companyCnpj)
    val busy: Boolean get() = attaching || read is ReadState.Running
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
    private val files: DocumentFileStore,
    private val reader: DocumentReader,
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

    /** Anexo gravado no banco ao abrir a tela (só é apagado depois de salvar a troca). */
    private var originalUri: String? = null
    /** Cópias feitas nesta edição; as não salvas são apagadas ao sair. */
    private val sessionCopies = mutableSetOf<String>()
    private var committed = false
    private var readJob: Job? = null

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
            val companyName = session.activeCompany.tradeName.ifBlank { session.activeCompany.name }
            val id = rawId
            if (id == null) {
                _state.update { it.copy(loading = false, error = "Documento inválido.") }
                return@launch
            }
            if (id <= 0) {
                // -1 = "Novo documento" (tipo identificado pela leitura do anexo); -(100+n) = chip "+ Tipo".
                val preset = if (id <= -100) DocumentType.entries.getOrNull((-id - 100).toInt()) else null
                _state.value = DocumentEditUiState(
                    loading = false, isNew = true, canEdit = canEdit, roleLabel = session.user.role.label,
                    companyName = companyName, companyCnpj = session.activeCompany.cnpj,
                    typeChosen = preset != null,
                    form = DocumentForm(companyId = session.activeCompany.id, type = preset ?: DocumentType.OUTROS, title = preset?.label.orEmpty()),
                )
                return@launch
            }
            runCatching { documents.getDocument(id) }
                .onSuccess { doc ->
                    if (doc == null || doc.companyId != session.activeCompany.id) {
                        _state.update { it.copy(loading = false, error = "Documento não encontrado nesta empresa.") }
                    } else {
                        originalUri = doc.attachmentUri
                        val meta = DocumentNotesCodec.decode(doc.notes)
                        _state.value = DocumentEditUiState(
                            loading = false, isNew = false, canEdit = canEdit, roleLabel = session.user.role.label,
                            companyName = companyName, companyCnpj = session.activeCompany.cnpj, typeChosen = true,
                            form = DocumentForm(
                                id = doc.id, companyId = doc.companyId, createdAt = doc.createdAt, type = doc.type,
                                title = doc.title, issuer = doc.issuer.orEmpty(), issuedAt = doc.issuedAt,
                                expiresAt = doc.expiresAt, attachmentUri = doc.attachmentUri, tags = doc.tags,
                                notes = meta.notes, number = meta.number, cnpj = meta.cnpj,
                            ),
                        )
                        doc.attachmentUri?.let { refreshInfo(it) }
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Falha ao carregar o documento.") } }
        }
    }

    /** Edição manual: o campo [field] (se informado) deixa de ser marcado como "lido do documento". */
    fun edit(field: String? = null, transform: (DocumentForm) -> DocumentForm) {
        if (!_state.value.canEdit) return
        _state.update {
            it.copy(
                form = transform(it.form), titleError = null, dateError = null,
                origins = if (field != null) it.origins - field else it.origins,
                conflicts = if (field != null) it.conflicts.filterNot { c -> c.field == field } else it.conflicts,
            )
        }
    }

    fun chooseType(type: DocumentType) {
        if (!_state.value.canEdit) return
        _state.update {
            it.copy(
                form = it.form.withType(type), typeChosen = true, origins = it.origins - DocumentField.TYPE,
                conflicts = it.conflicts.filterNot { c -> c.field == DocumentField.TYPE },
            )
        }
    }

    fun addTag(raw: String) {
        val tag = raw.trim().trimStart('#').lowercase()
        if (tag.isBlank()) return
        edit { if (tag in it.tags || it.tags.size >= 12) it else it.copy(tags = it.tags + tag) }
    }

    fun removeTag(tag: String) = edit { it.copy(tags = it.tags - tag) }

    // ------------------------------------------------------------------ anexo

    fun createCameraTarget(): Uri? = if (_state.value.canEdit) runCatching { files.createCameraTarget() }.getOrNull() else null

    fun onCameraResult(success: Boolean, target: Uri) {
        if (!success) {
            files.discardCameraTarget(target)
            return
        }
        attach(listOf(target)) { files.discardCameraTarget(target) }
    }

    /** Copia o(s) arquivo(s) para o armazenamento privado da empresa e dispara a leitura automática. */
    fun attach(sources: List<Uri>, afterCopy: () -> Unit = {}) {
        val current = _state.value
        if (!current.canEdit || sources.isEmpty() || current.attaching) return
        readJob?.cancel()
        _state.update { it.copy(attaching = true, read = ReadState.Idle, conflicts = emptyList(), pendingSuggestion = null) }
        viewModelScope.launch {
            try {
                val stored = files.import(current.form.companyId, sources)
                sessionCopies += stored.uri
                val previous = _state.value.form.attachmentUri
                // Cópia anterior desta mesma edição (substituída antes de salvar) já pode ir embora.
                if (previous != null && previous in sessionCopies && previous != stored.uri) {
                    files.delete(previous, current.form.companyId)
                    sessionCopies -= previous
                }
                _state.update {
                    it.copy(
                        attaching = false, form = it.form.copy(attachmentUri = stored.uri),
                        attachmentInfo = AttachmentInfo(stored.name, stored.mime, stored.size),
                    )
                }
                _events.send(DocumentEditEvent.Message(if (stored.pages > 1) "${stored.pages} páginas unidas em um PDF" else "Arquivo anexado"))
                readAttachment()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(attaching = false) }
                _events.send(DocumentEditEvent.Message(e.message ?: "Não foi possível anexar o arquivo."))
            } finally {
                afterCopy()
            }
        }
    }

    fun removeAttachment() {
        val uri = _state.value.form.attachmentUri ?: return
        edit { it.copy(attachmentUri = null) }
        readJob?.cancel()
        _state.update { it.copy(attachmentInfo = null, read = ReadState.Idle, conflicts = emptyList(), pendingSuggestion = null) }
        if (uri in sessionCopies) {
            sessionCopies -= uri
            viewModelScope.launch { files.delete(uri, _state.value.form.companyId) }
        }
    }

    /** Lê o anexo atual (texto do PDF ou OCR) e preenche as sugestões no formulário — sem gravar nada. */
    fun readAttachment() {
        val current = _state.value
        val uri = current.form.attachmentUri ?: return
        if (!current.canEdit) return
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _state.update { it.copy(read = ReadState.Running("Preparando a leitura"), conflicts = emptyList(), pendingSuggestion = null) }
            try {
                val reading = reader.read(uri, _state.value.attachmentInfo?.mime) { stage ->
                    _state.update { s -> if (s.read is ReadState.Running) s.copy(read = ReadState.Running(stage)) else s }
                }
                applyReading(reading)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(read = ReadState.Failed(e.message?.takeIf(String::isNotBlank) ?: "Não foi possível ler o documento. Preencha os campos manualmente."))
                }
            }
        }
    }

    private fun applyReading(reading: DocumentReading) {
        val s = reading.suggestion
        _state.update { st ->
            var form = st.form
            val origins = st.origins.toMutableMap()
            val conflicts = mutableListOf<SuggestionConflict>()
            var applied = 0
            fun origin(field: String) = if (field in s.aiFields) FieldOrigin.IA else FieldOrigin.DOCUMENTO

            // Tipo: aplicado se ainda não foi escolhido; senão vira conflito (ex.: chip "+ FGTS" com a CND Federal anexada).
            val suggestedType = s.type
            val typeMatches = suggestedType == null || suggestedType == form.type || !st.typeChosen || form.type == DocumentType.OUTROS
            if (suggestedType != null && suggestedType != form.type) {
                if (typeMatches) {
                    form = form.withType(suggestedType); origins[DocumentField.TYPE] = origin(DocumentField.TYPE); applied++
                } else conflicts += SuggestionConflict(DocumentField.TYPE, "Tipo", suggestedType.label)
            } else if (suggestedType != null) {
                origins[DocumentField.TYPE] = origin(DocumentField.TYPE)
            }
            // Mesmo tipo (ex.: renovação da certidão): os valores do novo anexo substituem os anteriores.
            // Tipo divergente: só preenche campos vazios; os demais aparecem para o usuário aplicar.
            fun <T> put(field: String, label: String, value: T?, currentValue: T?, isEmpty: Boolean, show: (T) -> String, set: (DocumentForm, T) -> DocumentForm) {
                if (value == null || (value is String && value.isBlank())) return
                val same = value == currentValue || (value is String && currentValue is String && sameText(value, currentValue))
                if (same) { origins[field] = origin(field); return }
                if (typeMatches || isEmpty) {
                    form = set(form, value); origins[field] = origin(field); applied++
                } else conflicts += SuggestionConflict(field, label, show(value))
            }
            put(DocumentField.ISSUER, "Emissor", s.issuer, form.issuer, form.issuer.isBlank(), { it }) { f, v -> f.copy(issuer = v.take(120)) }
            put(DocumentField.NUMBER, "Nº / código", s.number, form.number, form.number.isBlank(), { it }) { f, v -> f.copy(number = v.take(80)) }
            val cnpj = s.cnpj?.let(Formatters::cnpj)
            put(DocumentField.CNPJ, "CNPJ", cnpj, form.cnpj, form.cnpj.isBlank(), { it }) { f, v -> f.copy(cnpj = v) }
            put(DocumentField.ISSUED_AT, "Emissão", s.issuedAt, form.issuedAt, form.issuedAt == null, Formatters::date) { f, v -> f.copy(issuedAt = v) }
            put(DocumentField.EXPIRES_AT, "Validade", s.expiresAt, form.expiresAt, form.expiresAt == null, Formatters::date) { f, v -> f.copy(expiresAt = v) }

            st.copy(
                form = form, origins = origins, conflicts = conflicts, pendingSuggestion = s.takeIf { conflicts.isNotEmpty() },
                read = ReadState.Done(reading, applied), titleError = null, dateError = null,
            )
        }
    }

    /** Aplica um valor lido que estava em conflito com o que já havia no formulário. */
    fun applyConflict(field: String) {
        val s = _state.value.pendingSuggestion ?: return
        if (!_state.value.canEdit) return
        _state.update { st ->
            val origin = if (field in s.aiFields) FieldOrigin.IA else FieldOrigin.DOCUMENTO
            val form = when (field) {
                DocumentField.TYPE -> s.type?.let { st.form.withType(it) } ?: st.form
                DocumentField.ISSUER -> st.form.copy(issuer = s.issuer.orEmpty())
                DocumentField.NUMBER -> st.form.copy(number = s.number.orEmpty())
                DocumentField.CNPJ -> st.form.copy(cnpj = s.cnpj?.let(Formatters::cnpj).orEmpty())
                DocumentField.ISSUED_AT -> st.form.copy(issuedAt = s.issuedAt)
                DocumentField.EXPIRES_AT -> st.form.copy(expiresAt = s.expiresAt)
                else -> st.form
            }
            val remaining = st.conflicts.filterNot { it.field == field }
            st.copy(
                form = form, origins = st.origins + (field to origin), conflicts = remaining,
                typeChosen = st.typeChosen || field == DocumentField.TYPE,
                pendingSuggestion = s.takeIf { remaining.isNotEmpty() },
            )
        }
    }

    fun applyAllConflicts() {
        _state.value.conflicts.map { it.field }.forEach(::applyConflict)
    }

    private fun refreshInfo(uri: String) {
        viewModelScope.launch {
            val info = files.info(uri)
            _state.update { if (it.form.attachmentUri == uri) it.copy(attachmentInfo = info) else it }
        }
    }

    // ------------------------------------------------------------------ salvar / excluir

    fun save() {
        val current = _state.value
        if (!current.canEdit || current.saving) return
        if (current.busy) {
            viewModelScope.launch { _events.send(DocumentEditEvent.Message("Aguarde a leitura do anexo terminar.")) }
            return
        }
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
                    committed = true
                    withContext(NonCancellable) {
                        // Anexo antigo substituído/removido e cópias descartadas nesta edição.
                        val saved = doc.attachmentUri
                        (sessionCopies + listOfNotNull(originalUri)).filter { it != saved }.forEach { files.delete(it, doc.companyId) }
                    }
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
                    committed = true
                    withContext(NonCancellable) {
                        (sessionCopies + listOfNotNull(originalUri, current.form.attachmentUri)).forEach { files.delete(it, current.form.companyId) }
                    }
                    _events.send(DocumentEditEvent.Closed("Documento excluído"))
                }
                .onFailure { e ->
                    _state.update { it.copy(saving = false) }
                    _events.send(DocumentEditEvent.Message(e.message ?: "Não foi possível excluir o documento"))
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (committed || sessionCopies.isEmpty()) return
        // Saiu sem salvar: as cópias desta edição não são referenciadas por nenhum documento.
        val pending = sessionCopies.toList()
        val companyId = _state.value.form.companyId
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { pending.forEach { files.delete(it, companyId) } }
    }
}

/** Troca o tipo mantendo o título em sincronia enquanto o usuário não o personalizou. */
internal fun DocumentForm.withType(type: DocumentType): DocumentForm {
    val autoTitle = title.isBlank() || DocumentType.entries.any { it.label == title }
    return copy(type = type, title = if (autoTitle) type.label else title)
}

/** Normalização usada para comparar textos lidos com os digitados (sem acento/caixa). */
internal fun sameText(a: String, b: String) = DocumentTextAnalyzer.normalize(a).trim() == DocumentTextAnalyzer.normalize(b).trim()
