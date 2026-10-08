package com.licitaia.feature.documents

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.documents.CnpjMatch
import com.licitaia.domain.documents.DocumentField
import com.licitaia.domain.documents.DocumentValidity
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.util.Formatters
import com.licitaia.feature.documents.files.AttachmentInfo
import com.licitaia.feature.documents.files.DocumentFileStore
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentEditScreen(viewModel: DocumentEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveAttachment by rememberSaveable { mutableStateOf(false) }
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }

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

    // PDF (ou qualquer arquivo) pelo seletor do sistema (SAF). O arquivo é copiado para o app: não precisa de permissão persistente.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.attach(listOf(uri))
    }
    // Galeria: uma ou várias imagens (várias = páginas unidas em um PDF).
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(DocumentFileStore.MAX_PAGES)) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.attach(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraTarget?.let { viewModel.onCameraResult(ok, Uri.parse(it)) }
        cameraTarget = null
    }
    val actions = AttachActions(
        pickFile = {
            runCatching { filePicker.launch(arrayOf("application/pdf", "image/*")) }
                .onFailure { navigator.showMessage("Nenhum seletor de arquivos disponível") }
        },
        pickGallery = {
            runCatching { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .onFailure { navigator.showMessage("Nenhuma galeria disponível") }
        },
        takePhoto = {
            val target = viewModel.createCameraTarget()
            if (target == null) navigator.showMessage("Não foi possível preparar a câmera")
            else {
                cameraTarget = target.toString()
                runCatching { camera.launch(target) }.onFailure {
                    cameraTarget = null
                    navigator.showMessage("Nenhum aplicativo de câmera disponível")
                }
            }
        },
    )

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
                            val doc = form.toDocument()
                            val now = System.currentTimeMillis()
                            StatusBadge(DocumentValidity.badge(state.status, doc.daysToExpire(now)), state.status.tone())
                        }

                        SectionHeader("Anexo")
                        AttachmentCard(
                            uri = form.attachmentUri,
                            info = state.attachmentInfo,
                            canEdit = canEdit,
                            attaching = state.attaching,
                            actions = actions,
                            onOpen = { form.attachmentUri?.let { openAttachment(context, it, state.attachmentInfo?.mime) { msg -> navigator.showMessage(msg) } } },
                            onShare = { form.attachmentUri?.let { shareAttachment(context, it, state.attachmentInfo, form.title) { msg -> navigator.showMessage(msg) } } },
                            onRemove = { confirmRemoveAttachment = true },
                        )
                        ReadStatusCard(state, canEdit, onRetry = viewModel::readAttachment, onApply = viewModel::applyConflict, onApplyAll = viewModel::applyAllConflicts)

                        SectionHeader("Tipo")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DocumentType.entries.forEach { type ->
                                SelectChip(type.label, form.type == type, onClick = { viewModel.chooseType(type) })
                            }
                        }
                        OriginHint(state.origins[DocumentField.TYPE])

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
                            onValueChange = { v -> viewModel.edit(DocumentField.ISSUER) { it.copy(issuer = v.take(120)) } },
                            label = { Text("Emissor") },
                            placeholder = { Text("Ex.: Receita Federal, Junta Comercial") },
                            supportingText = state.origins[DocumentField.ISSUER]?.let { o -> { OriginText(o) } },
                            enabled = canEdit, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = form.number,
                            onValueChange = { v -> viewModel.edit(DocumentField.NUMBER) { it.copy(number = v.take(80)) } },
                            label = { Text("Nº / código de controle") },
                            placeholder = { Text("Número da certidão ou código de autenticidade") },
                            supportingText = state.origins[DocumentField.NUMBER]?.let { o -> { OriginText(o) } },
                            enabled = canEdit, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = form.cnpj,
                            onValueChange = { v -> viewModel.edit(DocumentField.CNPJ) { it.copy(cnpj = v.filter { c -> c.isDigit() || c in "./-" }.take(18)) } },
                            label = { Text("CNPJ no documento") },
                            placeholder = { Text("Empresa: ${Formatters.cnpj(state.companyCnpj)}") },
                            isError = state.cnpjMatch == CnpjMatch.DIVERGENTE,
                            supportingText = state.origins[DocumentField.CNPJ]?.let { o -> { OriginText(o) } },
                            enabled = canEdit, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.cnpjWarning?.let { warning ->
                            val divergent = state.cnpjMatch == CnpjMatch.DIVERGENTE
                            AlertBanner(
                                if (divergent) "CNPJ diferente da empresa" else "CNPJ de outro estabelecimento",
                                warning, if (divergent) Tone.DANGER else Tone.WARNING,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                DateField("Emissão", form.issuedAt, canEdit, Modifier.fillMaxWidth(), endOfDay = false) { v -> viewModel.edit(DocumentField.ISSUED_AT) { it.copy(issuedAt = v) } }
                                OriginHint(state.origins[DocumentField.ISSUED_AT])
                            }
                            Column(Modifier.weight(1f)) {
                                DateField("Validade", form.expiresAt, canEdit, Modifier.fillMaxWidth(), endOfDay = true) { v -> viewModel.edit(DocumentField.EXPIRES_AT) { it.copy(expiresAt = v) } }
                                OriginHint(state.origins[DocumentField.EXPIRES_AT])
                            }
                        }
                        AnimatedVisibility(state.dateError != null) {
                            Text(state.dateError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = LicitaColors.Red)
                        }
                        val expiry = expiryText(form.toDocument().daysToExpire(System.currentTimeMillis()), form.expiresAt)
                        if (expiry != null) {
                            Text("Este documento $expiry.", style = MaterialTheme.typography.bodySmall, color = state.status.tone().color())
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
                        if (state.origins.isNotEmpty() && canEdit) {
                            Text(
                                "Campos marcados foram lidos do anexo. Confira antes de salvar — nada é gravado sem tocar no botão abaixo.",
                                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                            )
                        }
                        PrimaryButton(
                            if (state.isNew) "Cadastrar documento" else "Salvar alterações",
                            onClick = viewModel::save,
                            enabled = canEdit && !state.busy, loading = state.saving, icon = Icons.Outlined.Save,
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
            message = "\"${state.form.title}\" será removido do cofre desta empresa, junto com a cópia do anexo guardada no LicitaPRO. " +
                "Arquivos originais em outros apps não são apagados.",
            confirmLabel = "Excluir", tone = Tone.DANGER, icon = Icons.Outlined.DeleteOutline,
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmRemoveAttachment) {
        ConfirmDialog(
            title = "Remover anexo?",
            message = "O arquivo deixa de fazer parte deste documento quando você salvar. Os campos preenchidos continuam como estão.",
            confirmLabel = "Remover", tone = Tone.DANGER, icon = Icons.Outlined.Close,
            onConfirm = { confirmRemoveAttachment = false; viewModel.removeAttachment() },
            onDismiss = { confirmRemoveAttachment = false },
        )
    }
}

private class AttachActions(val pickFile: () -> Unit, val pickGallery: () -> Unit, val takePhoto: () -> Unit)

@Composable
private fun OriginText(origin: FieldOrigin) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (origin == FieldOrigin.IA) Icons.Outlined.AutoAwesome else Icons.Outlined.DocumentScanner,
            contentDescription = null, tint = if (origin == FieldOrigin.IA) LicitaColors.Purple else LicitaColors.GreenBright,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(origin.label, color = if (origin == FieldOrigin.IA) LicitaColors.Purple else LicitaColors.GreenBright)
    }
}

@Composable
private fun OriginHint(origin: FieldOrigin?) {
    if (origin == null) return
    Box(Modifier.padding(start = 4.dp, top = 2.dp)) {
        ProvideOriginStyle { OriginText(origin) }
    }
}

@Composable
private fun ProvideOriginStyle(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelSmall,
        content = content,
    )
}

@Composable
private fun ReadStatusCard(
    state: DocumentEditUiState,
    canEdit: Boolean,
    onRetry: () -> Unit,
    onApply: (String) -> Unit,
    onApplyAll: () -> Unit,
) {
    when (val read = state.read) {
        ReadState.Idle -> {
            if (state.form.attachmentUri == null && canEdit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = LicitaColors.Purple, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Ao anexar, o LicitaPRO lê o documento (texto do PDF ou OCR) e sugere tipo, emissor, número, CNPJ e validade.",
                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                    )
                }
            } else if (state.form.attachmentUri != null && canEdit) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ler o anexo e sugerir os campos")
                }
            }
        }
        is ReadState.Running -> LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Purple) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Outlined.DocumentScanner, LicitaColors.Purple, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lendo o documento…", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
                    Text(read.stage, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = LicitaColors.Purple, trackColor = LicitaColors.SurfaceHigh)
        }
        is ReadState.Failed -> AlertBanner(
            "Leitura automática indisponível", read.message, Tone.WARNING,
            actionLabel = if (canEdit) "Tentar de novo" else null, onAction = onRetry,
        )
        is ReadState.Done -> LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.GreenBright) {
            val r = read.reading
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(Icons.Outlined.DocumentScanner, LicitaColors.GreenBright, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val filled = r.suggestion.filledFields.size
                    Text(
                        if (filled == 0) "Nenhum campo reconhecido" else "Leitura concluída: $filled campo(s) encontrado(s)",
                        style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary,
                    )
                    Text(
                        buildString {
                            append("Via ${r.method.label}")
                            if (r.pages > 1) append(" · ${r.pages} páginas")
                            r.aiProvider?.let { append(" · complementado por $it") }
                        },
                        style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                    )
                }
                if (canEdit) {
                    IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, contentDescription = "Ler de novo", tint = LicitaColors.TextSecondary) }
                }
            }
            r.suggestion.validityNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it + if (r.suggestion.expiresAt == null) " — informe a data de emissão para calcular a validade." else ".",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            r.warning?.let {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = LicitaColors.Yellow, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LicitaColors.Yellow)
                }
            }
            if (state.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "O anexo traz valores diferentes dos que já estão no formulário:",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
                state.conflicts.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                        Text(
                            "${c.label}: ${c.value}", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary,
                            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (canEdit) TextButton(onClick = { onApply(c.field) }) { Text("Aplicar") }
                    }
                }
                if (canEdit && state.conflicts.size > 1) {
                    TextButton(onClick = onApplyAll) { Text("Aplicar todos") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: Long?, enabled: Boolean, modifier: Modifier, endOfDay: Boolean, onChange: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LicitaCard(modifier = modifier, onClick = if (enabled) ({ open = true }) else null, contentPadding = PaddingValues(12.dp)) {
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
                    pickerState.selectedDateMillis?.let { onChange(pickerUtcToLocal(it, endOfDay)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * O DatePicker trabalha em UTC (meia-noite); convertemos para o fuso local: meio-dia para a emissão e
 * 23:59:59 para a validade (o documento vale durante todo o último dia).
 */
private fun pickerUtcToLocal(utcMillis: Long, endOfDay: Boolean): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        if (endOfDay) set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
        else set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
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
private fun AttachmentCard(
    uri: String?,
    info: AttachmentInfo?,
    canEdit: Boolean,
    attaching: Boolean,
    actions: AttachActions,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
) {
    var replacing by remember { mutableStateOf(false) }
    LicitaCard(Modifier.fillMaxWidth(), accent = if (uri != null) LicitaColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (uri != null && info?.isImage == true) {
                AsyncImage(
                    model = uri, contentDescription = "Prévia do anexo", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onOpen),
                )
            } else {
                IconBubble(
                    if (info?.isPdf == true) Icons.Outlined.PictureAsPdf else Icons.Outlined.AttachFile,
                    if (uri != null) LicitaColors.Green else LicitaColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        attaching -> "Copiando o arquivo…"
                        uri == null -> "Nenhum arquivo anexado"
                        else -> info?.name ?: "Arquivo anexado"
                    },
                    style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        uri == null -> "PDF, foto da câmera ou imagens da galeria (várias imagens viram um PDF)."
                        else -> listOfNotNull(
                            when {
                                info?.isPdf == true -> "PDF"
                                info?.isImage == true -> "Imagem"
                                else -> null
                            },
                            info?.size?.let(::formatSize),
                            "guardado no app, só desta empresa",
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
            if (uri != null && canEdit && !attaching) {
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Remover anexo", tint = LicitaColors.Red) }
            }
        }
        if (attaching) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = LicitaColors.Green, trackColor = LicitaColors.SurfaceHigh)
        }
        Spacer(Modifier.height(12.dp))
        if (uri != null && !replacing) {
            ButtonRow {
                SecondaryButton("Abrir", onOpen, Modifier.weight(1f), icon = Icons.Outlined.OpenInNew)
                SecondaryButton("Enviar", onShare, Modifier.weight(1f), icon = Icons.Outlined.Share)
                if (canEdit) {
                    SecondaryButton("Trocar", { replacing = true }, Modifier.weight(1f), enabled = !attaching, icon = Icons.Outlined.SwapHoriz)
                }
            }
        } else if (canEdit) {
            ButtonRow {
                SecondaryButton("PDF", { replacing = false; actions.pickFile() }, Modifier.weight(1f), enabled = !attaching, icon = Icons.Outlined.PictureAsPdf)
                SecondaryButton("Câmera", { replacing = false; actions.takePhoto() }, Modifier.weight(1f), enabled = !attaching, icon = Icons.Outlined.PhotoCamera)
                SecondaryButton("Galeria", { replacing = false; actions.pickGallery() }, Modifier.weight(1f), enabled = !attaching, icon = Icons.Outlined.Image)
            }
            if (replacing) {
                TextButton(onClick = { replacing = false }) { Text("Cancelar troca") }
            }
        } else if (uri == null) {
            Text("Sem permissão para anexar arquivos.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale("pt", "BR"), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun openAttachment(context: Context, uri: String, mime: String?, onError: (String) -> Unit) {
    try {
        val parsed = Uri.parse(uri)
        val type = mime ?: runCatching { context.contentResolver.getType(parsed) }.getOrNull() ?: "*/*"
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

private fun shareAttachment(context: Context, uri: String, info: AttachmentInfo?, title: String, onError: (String) -> Unit) {
    try {
        val parsed = Uri.parse(uri)
        val type = info?.mime ?: runCatching { context.contentResolver.getType(parsed) }.getOrNull() ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, parsed)
            putExtra(Intent.EXTRA_SUBJECT, title)
            // ClipData garante a permissão de leitura também no app escolhido pelo chooser.
            clipData = ClipData.newUri(context.contentResolver, info?.name ?: title, parsed)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Enviar documento").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (e: ActivityNotFoundException) {
        onError("Nenhum aplicativo disponível para compartilhar")
    } catch (e: Exception) {
        onError("Não foi possível compartilhar o anexo.")
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
