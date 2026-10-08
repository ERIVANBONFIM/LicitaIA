package com.licitaia.feature.platform

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.DocumentoDto
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformDocsUi(
    val loading: Boolean = true,
    val docs: List<DocumentoDto> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class PlatformDocumentsViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformDocsUi())
    val state: StateFlow<PlatformDocsUi> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.documentos().fold(
                onSuccess = { list -> _state.update { it.copy(loading = false, docs = list) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: "Não foi possível carregar os documentos.") } },
            )
        }
    }

    fun upload(bytes: ByteArray, fileName: String, nome: String, categoria: String, validade: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.uploadDocumento(bytes, fileName, nome.ifBlank { fileName }, categoria.ifBlank { "outros" }, validade?.ifBlank { null })
                .fold(
                    onSuccess = { _events.send("Documento enviado."); reload() },
                    onFailure = { _events.send(it.message ?: "Falha ao enviar o documento."); _state.update { s -> s.copy(busy = false) } },
                )
        }
    }

    fun delete(id: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.deleteDocumento(id).fold(
                onSuccess = { _events.send("Documento excluído."); reload() },
                onFailure = { _events.send(it.message ?: "Falha ao excluir."); _state.update { s -> s.copy(busy = false) } },
            )
        }
    }

    /** Baixa os bytes do documento (Bearer) e devolve ao chamador para salvar em Downloads (MediaStore). */
    suspend fun fetchBytes(id: String): Result<ByteArray> = repository.downloadDocumento(id)
    fun notify(msg: String) { viewModelScope.launch { _events.send(msg) } }

    private suspend fun reload() {
        repository.documentos().fold(
            onSuccess = { list -> _state.update { it.copy(busy = false, docs = list) } },
            onFailure = { _state.update { it.copy(busy = false) } },
        )
    }
}

@Composable
fun PlatformDocumentsScreen(viewModel: PlatformDocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingUpload?>(null) }
    var downloading by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events.collect { navigator.showMessage(it) }
    }

    val baixar: (DocumentoDto) -> Unit = { d ->
        if (!downloading) {
            downloading = true
            scope.launch {
                viewModel.fetchBytes(d.id).fold(
                    onSuccess = { bytes ->
                        val saved = runCatching { saveToDownloads(context, bytes, downloadFileName(d)) }.getOrNull()
                        viewModel.notify(if (saved != null) "Salvo em Downloads: $saved" else "Baixado, mas não foi possível salvar em Downloads.")
                    },
                    onFailure = { viewModel.notify(it.message ?: "Não foi possível baixar o documento.") },
                )
                downloading = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val name = queryDisplayName(context, uri) ?: "documento"
            if (bytes != null) pending = PendingUpload(bytes, name) else navigator.showMessage("Não foi possível ler o arquivo.")
        }
    }

    LicitaScaffold(
        title = "Documentos",
        subtitle = "Plataforma",
        showBack = true,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch("*/*") },
                icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                text = { Text("Enviar") },
                containerColor = LicitaColors.Blue,
                contentColor = androidx.compose.ui.graphics.Color.White,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.docs.isEmpty() -> EmptyState(title = "Nenhum documento", message = "Toque em Enviar para adicionar um PDF ou foto do aparelho.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.docs, key = { it.id }) { DocCard(it, enabled = !state.busy && !downloading, onDownload = { baixar(it) }, onDelete = { viewModel.delete(it.id) }) }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    pending?.let { p ->
        UploadDialog(
            fileName = p.fileName,
            onDismiss = { pending = null },
            onConfirm = { nome, categoria, validade ->
                viewModel.upload(p.bytes, p.fileName, nome, categoria, validade)
                pending = null
            },
        )
    }
}

private data class PendingUpload(val bytes: ByteArray, val fileName: String)

@Composable
private fun UploadDialog(fileName: String, onDismiss: () -> Unit, onConfirm: (String, String, String?) -> Unit) {
    var nome by remember { mutableStateOf(fileName) }
    var categoria by remember { mutableStateOf("outros") }
    var validade by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enviar documento") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(fileName, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(nome, { nome = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(categoria, { categoria = it }, label = { Text("Categoria") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(validade, { validade = it }, label = { Text("Validade (AAAA-MM-DD, opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { PrimaryButton("Enviar", { onConfirm(nome, categoria, validade) }) },
        dismissButton = { SecondaryButton("Cancelar", onDismiss, tone = Tone.NEUTRAL) },
    )
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

@Composable
private fun DocCard(d: DocumentoDto, enabled: Boolean, onDownload: () -> Unit, onDelete: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.nome.ifBlank { "Documento" }, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDownload, enabled = enabled) { Icon(Icons.Outlined.Download, contentDescription = "Baixar", tint = LicitaColors.Blue) }
            IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Outlined.Delete, contentDescription = "Excluir", tint = LicitaColors.RedBright) }
        }
        Spacer(Modifier.height(4.dp))
        d.categoria?.let { InfoRow("Categoria", it.replace('_', ' ')) }
        InfoRow("Validade", PlatformFormat.dateTime(d.validade))
        d.status?.let {
            Spacer(Modifier.height(8.dp))
            StatusBadge(it, if (it.equals("regular", true)) Tone.SUCCESS else Tone.WARNING)
        }
    }
}

/** Nome do arquivo para salvar: usa o nome do doc + extensão plausível (default .pdf). */
private fun downloadFileName(d: DocumentoDto): String {
    val base = d.nome.ifBlank { "documento" }.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return if (base.contains('.')) base else "$base.pdf"
}

private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xml" -> "application/xml"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

/** Salva os bytes em Downloads (MediaStore no 29+; app-external em 26-28). Retorna o local salvo. */
private fun saveToDownloads(context: android.content.Context, bytes: ByteArray, fileName: String): String {
    val mime = mimeOf(fileName)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Downloads indisponível.")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Falha ao escrever.")
        values.clear()
        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/$fileName"
    }
    // 26-28: pasta de Downloads do app (sem permissão), aberta pelo gerenciador de arquivos.
    val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    val file = java.io.File(dir, fileName)
    file.writeBytes(bytes)
    return file.absolutePath
}
