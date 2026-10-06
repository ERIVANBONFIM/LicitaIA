package com.licitaia.feature.tender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SelectChip
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.util.Formatters
import java.util.Calendar
import java.util.TimeZone

/** Cadastro manual de uma licitação real (edital obtido no portal, por e-mail ou impresso). */
@Composable
fun TenderNewScreen(viewModel: TenderNewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }
    LaunchedEffect(state.createdId) {
        val id = state.createdId ?: return@LaunchedEffect
        navigator.back()
        navigator.navigate(Routes.tender(id))
    }
    val form = state.form

    LicitaScaffold(title = "Cadastrar licitação", subtitle = "Edital real", showBack = true) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                GradientCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Outlined.EditNote, LicitaColors.BlueBright)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Licitação fora do radar", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                            Text(
                                "Informe os dados do edital como constam no portal. Depois, na licitação, importe o PDF (ou cole o texto) para a IA analisar o edital real.",
                                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                            )
                        }
                    }
                }
            }
            if (!state.canCreate && state.role != null) {
                item(key = "rbac") {
                    AlertBanner("Sem permissão", "Seu perfil (${state.role?.label}) não pode cadastrar licitações. Peça a um usuário de Licitações.", Tone.WARNING)
                }
            }
            item(key = "portal") {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Portal", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    ChipRow(Portal.entries.map { it.shortName to (it == form.portal) }) { index -> viewModel.edit { it.copy(portal = Portal.entries[index]) } }
                    Spacer(Modifier.height(12.dp))
                    Text("Modalidade", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    ChipRow(Modality.entries.map { it.label to (it == form.modality) }) { index -> viewModel.edit { it.copy(modality = Modality.entries[index]) } }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Segmento" + (state.companySegment?.let { " (padrão: ${it.label})" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipRow(Segment.entries.map { it.label to (it == (form.segment ?: state.companySegment)) }) { index -> viewModel.edit { it.copy(segment = Segment.entries[index]) } }
                }
            }
            item(key = "ident") {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Identificação", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.number, onValueChange = { v -> viewModel.edit { it.copy(number = v.take(40)) } },
                        label = { Text("Número do edital") }, placeholder = { Text("Ex.: 90012/2026") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.agency, onValueChange = { v -> viewModel.edit { it.copy(agency = v.take(160)) } },
                        label = { Text("Órgão licitante") }, placeholder = { Text("Ex.: Prefeitura Municipal de …") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.objectDescription, onValueChange = { v -> viewModel.edit { it.copy(objectDescription = v.take(1_000)) } },
                        label = { Text("Objeto") }, placeholder = { Text("Descrição do objeto como consta no edital") }, minLines = 3, maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = form.uf, onValueChange = { v -> viewModel.edit { it.copy(uf = v.filter(Char::isLetter).take(2).uppercase()) } },
                            label = { Text("UF") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(0.6f),
                        )
                        OutlinedTextField(
                            value = form.city, onValueChange = { v -> viewModel.edit { it.copy(city = v.take(80)) } },
                            label = { Text("Cidade") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1.4f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.estimatedValue, onValueChange = { v -> viewModel.edit { it.copy(estimatedValue = v.take(20)) } },
                        label = { Text("Valor estimado (R$)") }, placeholder = { Text("Ex.: 250000,00 — deixe vazio se sigiloso") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "dates") {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("Datas", style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateField("Propostas até", form.proposalDeadlineDate, Modifier.weight(1.4f)) { d -> viewModel.edit { it.copy(proposalDeadlineDate = d) } }
                        OutlinedTextField(
                            value = form.proposalDeadlineTime, onValueChange = { v -> viewModel.edit { it.copy(proposalDeadlineTime = v.take(5)) } },
                            label = { Text("Hora") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(0.8f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateField("Sessão pública", form.sessionDate, Modifier.weight(1.4f)) { d -> viewModel.edit { it.copy(sessionDate = d) } }
                        OutlinedTextField(
                            value = form.sessionTime, onValueChange = { v -> viewModel.edit { it.copy(sessionTime = v.take(5)) } },
                            label = { Text("Hora") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(0.8f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = form.editalUrl, onValueChange = { v -> viewModel.edit { it.copy(editalUrl = v.take(500)) } },
                        label = { Text("URL do edital (opcional)") }, placeholder = { Text("https://…") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.errors.isNotEmpty()) {
                item(key = "errors") {
                    AlertBanner("Revise o cadastro", state.errors.joinToString("\n"), Tone.WARNING)
                }
            }
            item(key = "save") {
                PrimaryButton(
                    if (state.saving) "Salvando…" else "Cadastrar licitação", viewModel::save, Modifier.fillMaxWidth(),
                    enabled = state.canCreate, loading = state.saving, icon = Icons.Outlined.Save, tone = Tone.SUCCESS,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nenhuma análise é disparada automaticamente: você importa o edital e decide quando analisar.",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    // Quebra em linhas de até 3 chips para caber em telas estreitas.
    options.chunked(3).forEachIndexed { rowIndex, row ->
        if (rowIndex > 0) Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEachIndexed { i, (label, selected) ->
                SelectChip(label, selected, { onSelect(rowIndex * 3 + i) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: Long?, modifier: Modifier, onChange: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LicitaCard(modifier = modifier, onClick = { open = true }, contentPadding = PaddingValues(12.dp)) {
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

/** O DatePicker trabalha em UTC (meia-noite); convertemos para meio-dia local para não virar o dia. */
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
