package com.licitaia.feature.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.ConfirmDialog
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.util.Formatters
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentEditScreen(viewModel: DocumentEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DocumentEditEvent.Message -> navigator.showMessage(event.text)
                is DocumentEditEvent.Closed -> {
                    navigator.showMessage(event.text)
                    navigator.back()
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // Permissão persistente para reabrir o anexo depois de reiniciar o app.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.edit { it.copy(attachmentUri = uri.toString()) }
        }
    }

    LicitaScaffold(
        title = when {
            state.loading || state.error != null -> "Documento"
            state.isNew -> "Novo documento"
            else -> "Editar documento"
        },
        showBack = true,
        actions = {
            if (!state.loading && state.error == null && !state.isNew) {
                IconButton(
                    onClick = {
                        if (state.canEdit) confirmDelete = true
                        else navigator.showMessage("O perfil ${state.roleLabel} não pode excluir documentos.")
                    },
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Excluir", tint = if (state.canEdit) LicitaColors.Red else LicitaColors.TextMuted)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList(items = 3)
                state.error != null -> ErrorState(state.error ?: "", onRetry = viewModel::load)
                else -> {
                    val form = state.form
                    val canEdit = state.canEdit
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!canEdit) {
                            AlertBanner(
                                "Somente leitura",
                                "O perfil ${state.roleLabel} não tem a permissão \"Gerenciar documentos\".",
                                Tone.WARNING,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Empresa proprietária", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                                Text(state.companyName, style = MaterialTheme.typography.titleSmall, color = LicitaColors.GreenBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusBadge(state.status.label, state.status.tone())
                        }

                        SectionHeader("Tipo")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DocumentType.entries.forEach { type ->
                                SelectChip(type.label, form.type == type, onClick = {
                                    viewModel.edit { f ->
                                        // Mantém o título em sincronia enquanto o usuário não o personalizou.
                                        val autoTitle = f.title.isBlank() || DocumentType.entries.any { it.label == f.title }
                                        f.copy(type = type, title = if (autoTitle) type.label else f.title)
                                    }
                                })
                            }
                        }

                        OutlinedTextField(
                            value = form.title,
                            onValueChange = { v -> viewModel.edit { it.copy(title = v.take(120)) } },
                            label = { Text("Título") },
                            isError = state.titleError != null,
                            supportingText = state.titleError?.let { { Text(it) } },
                            enabled = canEdit, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = form.issuer,
                            onValueChange = { v -> viewModel.edit { it.copy(issuer = v.take(120)) } },
                            label = { Text("Emissor") },
                            placeholder = { Text("Ex.: Receita Federal, Junta Comercial") },
                            enabled = canEdit, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DateField("Emissão", form.issuedAt, canEdit, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(issuedAt = v) } }
                            DateField("Validade", form.expiresAt, canEdit, Modifier.weight(1f)) { v -> viewModel.edit { it.copy(expiresAt = v) } }
                        }
                        AnimatedVisibility(state.dateError != null) {
                            Text(state.dateError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = LicitaColors.Red)
                        }
                        val expiry = expiryText(form.toDocument().daysToExpire(System.currentTimeMillis()), form.expiresAt)
                        if (expiry != null) {
                            Text("Este documento $expiry.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                        }

                        SectionHeader("Anexo")
                        AttachmentCard(
                            uri = form.attachmentUri,
                            canEdit = canEdit,
                            onPick = { runCatching { picker.launch(arrayOf("application/pdf", "image/*", "*/*")) }.onFailure { navigator.showMessage("Nenhum seletor de arquivos disponível") } },
                            onOpen = { form.attachmentUri?.let { openAttachment(context, it) { msg -> navigator.showMessage(msg) } } },
                            onRemove = { viewModel.edit { it.copy(attachmentUri = null) } },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = LicitaColors.TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OCR (em breve): preenchimento automático a partir do anexo.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
                        }

                        SectionHeader("Tags")
                        TagsEditor(form.tags, canEdit, viewModel::addTag, viewModel::removeTag)

                        OutlinedTextField(
                            value = form.notes,
                            onValueChange = { v -> viewModel.edit { it.copy(notes = v.take(1000)) } },
                            label = { Text("Observações") },
                            enabled = canEdit, minLines = 3, maxLines = 6,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(4.dp))
                        PrimaryButton(
                            if (state.isNew) "Cadastrar documento" else "Salvar alterações",
                            onClick = viewModel::save,
                            enabled = canEdit, loading = state.saving, icon = Icons.Outlined.Save,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!canEdit) {
                            Text(
                                "Ação desabilitada: requer a permissão \"Gerenciar documentos\".",
                                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Excluir documento?",
            message = "\"${state.form.title}\" será removido do cofre desta empresa. O arquivo original no aparelho não é apagado.",
            confirmLabel = "Excluir", tone = Tone.DANGER, icon = Icons.Outlined.DeleteOutline,
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: Long?, enabled: Boolean, modifier: Modifier, onChange: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LicitaCard(modifier = modifier, onClick = if (enabled) ({ open = true }) else null, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = LicitaColors.Blue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                Text(
                    if (value == null) "Definir" else Formatters.date(value),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (value == null) LicitaColors.TextMuted else LicitaColors.TextPrimary,
                )
            }
            if (value != null && enabled) {
                Icon(
                    Icons.Outlined.Close, contentDescription = "Limpar $label", tint = LicitaColors.TextMuted,
                    modifier = Modifier.size(18.dp).clip(MaterialTheme.shapes.small).clickable { onChange(null) },
                )
            }
        }
    }
    if (open) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = value?.let(::localToPickerUtc))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onChange(pickerUtcToLocal(it)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** O DatePicker trabalha em UTC (meia-noite); convertemos para meio-dia no fuso local para evitar virar o dia. */
private fun pickerUtcToLocal(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
    }.timeInMillis
}

private fun localToPickerUtc(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

@Composable
private fun AttachmentCard(uri: String?, canEdit: Boolean, onPick: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val name by androidx.compose.runtime.produceState<String?>(null, uri) {
        value = uri?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { displayName(context, it) } }
    }
    LicitaCard(Modifier.fillMaxWidth(), accent = if (uri != null) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Outlined.AttachFile, if (uri != null) LicitaColors.Green else LicitaColors.TextSecondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (uri == null) "Nenhum arquivo anexado" else (name ?: "Arquivo anexado"),
                    style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (uri == null) "PDF ou imagem do documento, escolhido no seletor de arquivos do sistema."
                    else "O LicitaIA guarda apenas a referência ao arquivo, com permissão de leitura.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            if (uri != null) {
                SecondaryButton("Abrir", onOpen, Modifier.weight(1f), icon = Icons.Outlined.OpenInNew)
            }
            SecondaryButton(if (uri == null) "Anexar arquivo" else "Trocar", onPick, Modifier.weight(1f), enabled = canEdit, icon = Icons.Outlined.AttachFile)
            if (uri != null && canEdit) {
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Remover anexo", tint = LicitaColors.Red) }
            }
        }
    }
}

private fun displayName(context: Context, uri: String): String? = runCatching {
    val parsed = Uri.parse(uri)
    if (parsed.scheme == "content") {
        context.contentResolver.query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } else parsed.lastPathSegment
}.getOrNull()

private fun openAttachment(context: Context, uri: String, onError: (String) -> Unit) {
    try {
        val parsed = Uri.parse(uri)
        val type = runCatching { context.contentResolver.getType(parsed) }.getOrNull() ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(parsed, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        onError("Nenhum aplicativo instalado abre este tipo de arquivo")
    } catch (e: Exception) {
        onError("Não foi possível abrir o anexo. Anexe o arquivo novamente.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsEditor(tags: List<String>, canEdit: Boolean, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isEmpty()) {
            Text("Nenhuma tag. Use tags para agrupar (ex.: habilitação, fiscal, técnico).", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    SelectChip(if (canEdit) "#$tag  ✕" else "#$tag", selected = true, onClick = { if (canEdit) onRemove(tag) })
                }
            }
        }
        if (canEdit) {
            val submit = { onAdd(input); input = "" }
            OutlinedTextField(
                value = input,
                onValueChange = { v ->
                    // Vírgula ou espaço confirmam a tag.
                    if (v.endsWith(",") || v.endsWith(" ")) { onAdd(v.dropLast(1)); input = "" } else input = v.take(24)
                },
                label = { Text("Adicionar tag") },
                singleLine = true,
                trailingIcon = { TextButton(onClick = submit, enabled = input.isNotBlank()) { Text("Incluir") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
