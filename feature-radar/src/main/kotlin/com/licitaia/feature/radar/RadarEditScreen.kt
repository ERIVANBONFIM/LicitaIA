package com.licitaia.feature.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.scoreTone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.util.Formatters

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadarEditScreen(viewModel: RadarEditViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    LaunchedEffect(form.saved) {
        if (form.saved) {
            navigator.showMessage("Radar salvo")
            navigator.back()
        }
    }

    LicitaScaffold(title = if (form.isNew) "Novo radar" else "Editar radar", showBack = true) { padding ->
        val loadError = form.loadError
        when {
            form.loading -> SkeletonList(Modifier.padding(padding))
            loadError != null -> ErrorState(loadError, Modifier.padding(padding), onRetry = viewModel::load)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------------------------------------------- Identificação
                LicitaCard(Modifier.fillMaxWidth()) {
                    GroupTitle("Identificação")
                    OutlinedTextField(
                        value = form.name, onValueChange = { v -> viewModel.edit { it.copy(name = v) } },
                        label = { Text("Nome do radar") }, placeholder = { Text("Ex.: Links dedicados — Sul") },
                        isError = form.nameError != null, supportingText = form.nameError?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                    FieldLabel("Segmento")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Segment.entries.forEach { s ->
                            SelectChip(s.label, form.segment == s, { viewModel.edit { it.copy(segment = s) } })
                        }
                    }
                    ToggleRow("Radar ativo", "Monitora os portais e gera alertas", form.active) { v -> viewModel.edit { it.copy(active = v) } }
                }

                // ---------------------------------------------------- Palavras
                LicitaCard(Modifier.fillMaxWidth()) {
                    GroupTitle("Palavras-chave")
                    KeywordEditor(
                        label = "Palavras-chave", placeholder = "Ex.: link dedicado, fibra óptica",
                        words = form.keywords, color = LicitaColors.Blue, error = form.keywordsError,
                        onAdd = { viewModel.addKeywords(it, forbidden = false) },
                        onRemove = { viewModel.removeKeyword(it, forbidden = false) },
                    )
                    Spacer(Modifier.height(8.dp))
                    KeywordEditor(
                        label = "Palavras proibidas", placeholder = "Ex.: satélite, locação de veículos",
                        words = form.forbidden, color = LicitaColors.Red, error = null,
                        onAdd = { viewModel.addKeywords(it, forbidden = true) },
                        onRemove = { viewModel.removeKeyword(it, forbidden = true) },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.preferredObject, onValueChange = { v -> viewModel.edit { it.copy(preferredObject = v) } },
                        label = { Text("Objeto preferencial") },
                        placeholder = { Text("Descreva o objeto ideal para a IA pontuar a aderência") },
                        minLines = 2, maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ---------------------------------------------------- Portais
                LicitaCard(Modifier.fillMaxWidth()) {
                    GroupTitle("Portais")
                    ToggleRow("Todos os portais conectados", "Inclui novos portais automaticamente", form.allPortals) { v ->
                        viewModel.edit { it.copy(allPortals = v) }
                    }
                    AnimatedVisibility(!form.allPortals, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Portal.entries.forEach { p ->
                                    SelectChip(
                                        p.displayName, p in form.portals,
                                        { viewModel.edit { it.copy(portals = if (p in it.portals) it.portals - p else it.portals + p) } },
                                        color = p.color(),
                                    )
                                }
                            }
                            ErrorText(form.portalsError)
                        }
                    }
                }

                // ---------------------------------------------------- Abrangência
                LicitaCard(Modifier.fillMaxWidth()) {
                    GroupTitle("Abrangência")
                    FieldLabel(if (form.ufs.isEmpty()) "UF — todas" else "UF — ${form.ufs.size} selecionada(s)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BRAZIL_UFS.forEach { uf ->
                            SelectChip(uf, uf in form.ufs, { viewModel.edit { it.copy(ufs = if (uf in it.ufs) it.ufs - uf else it.ufs + uf) } })
                        }
                    }
                    FieldLabel("Região")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectChip("Qualquer", form.region == null, { viewModel.edit { it.copy(region = null) } })
                        BRAZIL_REGIONS.forEach { r ->
                            SelectChip(r, form.region == r, { viewModel.edit { it.copy(region = r) } })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = form.agency, onValueChange = { v -> viewModel.edit { it.copy(agency = v) } },
                        label = { Text("Órgão (opcional)") }, placeholder = { Text("Ex.: Prefeitura, Tribunal, Universidade") },
                        singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                    ToggleRow(
                        "Exigir atendimento técnico local",
                        "Mostra apenas editais que exigem presença/suporte local",
                        form.requireLocalSupport,
                    ) { v -> viewModel.edit { it.copy(requireLocalSupport = v) } }
                    ToggleRow(
                        "Mostrar dispensas sem disputa (contratação direta)",
                        "Inclui contratações diretas sem recebimento de propostas (\"Não se aplica\"); ocultas por padrão",
                        form.showNoDispute,
                    ) { v -> viewModel.edit { it.copy(showNoDispute = v) } }
                }

                // ---------------------------------------------------- Critérios
                LicitaCard(Modifier.fillMaxWidth()) {
                    GroupTitle("Critérios")
                    FieldLabel("Modalidade")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SelectChip("Todas", form.modality == null, { viewModel.edit { it.copy(modality = null) } })
                        Modality.entries.forEach { m ->
                            SelectChip(m.label, form.modality == m, { viewModel.edit { it.copy(modality = m) } })
                        }
                    }
                    FieldLabel("Valor estimado (R$)")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = form.minValue, onValueChange = { v -> viewModel.edit { it.copy(minValue = v) } },
                            label = { Text("Mínimo") }, singleLine = true, isError = form.valueError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = form.maxValue, onValueChange = { v -> viewModel.edit { it.copy(maxValue = v) } },
                            label = { Text("Máximo") }, singleLine = true, isError = form.valueError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f),
                        )
                    }
                    ErrorText(form.valueError)

                    FieldLabel("Período de publicação")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DateField("Data inicial", form.startDate, Modifier.weight(1f), isError = form.dateError != null) { d ->
                            viewModel.edit { it.copy(startDate = d) }
                        }
                        DateField("Data final", form.endDate, Modifier.weight(1f), isError = form.dateError != null) { d ->
                            viewModel.edit { it.copy(endDate = d) }
                        }
                    }
                    ErrorText(form.dateError)

                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Score mínimo da IA", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
                        Text("${form.minScore}", style = MaterialTheme.typography.titleMedium, color = scoreTone(form.minScore).color())
                    }
                    Slider(
                        value = form.minScore.toFloat(),
                        onValueChange = { v -> viewModel.edit { it.copy(minScore = (v / 5).toInt() * 5) } },
                        valueRange = 0f..100f,
                    )
                    OutlinedTextField(
                        value = form.cnae, onValueChange = { v -> viewModel.edit { it.copy(cnae = v.take(12)) } },
                        label = { Text("CNAE (opcional)") }, placeholder = { Text("6110-8/03") },
                        isError = form.cnaeError != null, supportingText = form.cnaeError?.let { { Text(it) } },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                }

                AnimatedVisibility(form.saveError != null) {
                    AlertBanner("Não foi possível salvar", form.saveError.orEmpty(), Tone.DANGER)
                }
                PrimaryButton(
                    if (form.isNew) "Criar radar" else "Salvar alterações", viewModel::save,
                    Modifier.fillMaxWidth(), loading = form.saving, icon = Icons.Outlined.Save,
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun ErrorText(text: String?) {
    if (text != null) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Red, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordEditor(
    label: String,
    placeholder: String,
    words: List<String>,
    color: Color,
    error: String?,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var text by rememberSaveable(label) { mutableStateOf("") }
    val commit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            // vírgula confirma a palavra, como em um campo de tags
            if (v.endsWith(",") || v.endsWith(";")) {
                onAdd(v)
                text = ""
            } else {
                text = v
            }
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        isError = error != null,
        supportingText = { Text(error ?: "Separe por vírgula ou toque em +") },
        trailingIcon = {
            IconButton(onClick = commit, enabled = text.isNotBlank()) { Icon(Icons.Outlined.Add, contentDescription = "Adicionar") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    if (words.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            words.forEach { word ->
                InputChip(
                    selected = true,
                    onClick = { onRemove(word) },
                    label = { Text(word) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remover $word", modifier = Modifier.size(16.dp)) },
                    colors = InputChipDefaults.inputChipColors(
                        selectedContainerColor = color.copy(alpha = 0.16f),
                        selectedLabelColor = color,
                        selectedTrailingIconColor = color,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: Long?, modifier: Modifier, isError: Boolean, onChange: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value?.let(Formatters::date).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Qualquer") },
            isError = isError,
            trailingIcon = {
                if (value != null) {
                    IconButton(onClick = { onChange(null) }) { Icon(Icons.Outlined.Close, contentDescription = "Limpar data") }
                } else {
                    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.CalendarMonth, contentDescription = "Escolher data") }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        // camada clicável sobre o campo somente-leitura (exceto o ícone à direita)
        Box(Modifier.matchParentSize().padding(end = 52.dp).clip(MaterialTheme.shapes.medium).clickable { open = true })
    }
    if (open) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = value?.let(::localToUtcMidnight))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onChange(utcToLocalMidnight(it)) }
                        open = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
