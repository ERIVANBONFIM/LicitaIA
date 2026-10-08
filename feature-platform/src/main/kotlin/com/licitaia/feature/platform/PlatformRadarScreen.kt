package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.RadarFiltroDto
import com.licitaia.core.platform.net.RadarUpsertRequest
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

data class PlatformRadarUi(
    val loading: Boolean = true,
    val filtros: List<RadarFiltroDto> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class PlatformRadarViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlatformRadarUi())
    val state: StateFlow<PlatformRadarUi> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.radarFiltros().fold(
                onSuccess = { list -> _state.update { it.copy(loading = false, filtros = list) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: "Não foi possível carregar os radares.") } },
            )
        }
    }

    fun save(id: String?, req: RadarUpsertRequest) = run("Radar salvo.") {
        if (id == null) repository.criarRadar(req) else repository.updateRadar(id, req)
    }

    fun delete(id: String) = run("Radar excluído.") { repository.deleteRadar(id) }

    fun toggleAtivo(f: RadarFiltroDto) = run(if (f.ativo) "Radar desativado." else "Radar ativado.") {
        repository.updateRadar(f.id, f.toUpsert(ativo = !f.ativo))
    }

    private fun run(okMsg: String, block: suspend () -> Result<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            block().fold(
                onSuccess = { _events.send(okMsg); reload() },
                onFailure = { _events.send(it.message ?: "Não foi possível concluir."); _state.update { s -> s.copy(busy = false) } },
            )
        }
    }

    private suspend fun reload() {
        repository.radarFiltros().fold(
            onSuccess = { list -> _state.update { it.copy(busy = false, filtros = list) } },
            onFailure = { _state.update { it.copy(busy = false) } },
        )
    }
}

private fun RadarFiltroDto.toUpsert(ativo: Boolean = this.ativo) = RadarUpsertRequest(
    nome = nome,
    palavrasChave = palavrasChave,
    portais = portais,
    estados = estados,
    valorMinimo = valorMinimo?.toDoubleOrNull(),
    valorMaximo = valorMaximo?.toDoubleOrNull(),
    ativo = ativo,
)

@Composable
fun PlatformRadarScreen(viewModel: PlatformRadarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    // null = fechado; dto com id vazio = novo.
    var editing by remember { mutableStateOf<RadarFiltroDto?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.events.collect { navigator.showMessage(it) } }

    LicitaScaffold(
        title = "Radares salvos",
        subtitle = "Plataforma",
        showBack = true,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = RadarFiltroDto() },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Novo radar") },
                containerColor = LicitaColors.Blue,
                contentColor = androidx.compose.ui.graphics.Color.White,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LicitaColors.Blue) }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.filtros.isEmpty() -> EmptyState(title = "Nenhum radar salvo", message = "Toque em \"Novo radar\" para criar um filtro de busca.")
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(state.filtros, key = { it.id }) { f ->
                        RadarCard(f, enabled = !state.busy, onEdit = { editing = f }, onDelete = { viewModel.delete(f.id) }, onToggle = { viewModel.toggleAtivo(f) })
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    editing?.let { f ->
        RadarEditorDialog(
            original = f,
            onDismiss = { editing = null },
            onSave = { req -> viewModel.save(f.id.ifBlank { null }, req); editing = null },
        )
    }
}

@Composable
private fun RadarCard(f: RadarFiltroDto, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onToggle: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth(), accent = if (f.ativo) LicitaColors.Blue else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.nome.ifBlank { "Radar" }, style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(checked = f.ativo, onCheckedChange = { onToggle() }, enabled = enabled)
        }
        Spacer(Modifier.height(6.dp))
        if (f.palavrasChave.isNotEmpty()) InfoRow("Palavras-chave", f.palavrasChave.joinToString(", "))
        if (f.portais.isNotEmpty()) InfoRow("Portais", f.portais.joinToString(", "))
        if (f.estados.isNotEmpty()) InfoRow("Estados", f.estados.joinToString(", "))
        f.countMatch?.let { InfoRow("Resultados", it.toString()) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Editar", onEdit, Modifier.weight(1f), enabled = enabled, tone = Tone.NEUTRAL, icon = Icons.Outlined.Edit)
            SecondaryButton("Excluir", onDelete, Modifier.weight(1f), enabled = enabled, tone = Tone.DANGER, icon = Icons.Outlined.Delete)
        }
    }
}

@Composable
private fun RadarEditorDialog(original: RadarFiltroDto, onDismiss: () -> Unit, onSave: (RadarUpsertRequest) -> Unit) {
    var nome by remember { mutableStateOf(original.nome) }
    var palavras by remember { mutableStateOf(original.palavrasChave.joinToString(", ")) }
    var estados by remember { mutableStateOf(original.estados.joinToString(", ")) }
    var portais by remember { mutableStateOf(original.portais.joinToString(", ")) }
    var valorMin by remember { mutableStateOf(original.valorMinimo?.let { formatNumber(it) } ?: "") }
    var valorMax by remember { mutableStateOf(original.valorMaximo?.let { formatNumber(it) } ?: "") }
    var ativo by remember { mutableStateOf(original.ativo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original.id.isBlank()) "Novo radar" else "Editar radar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(nome, { nome = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(palavras, { palavras = it }, label = { Text("Palavras-chave (vírgula)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(estados, { estados = it }, label = { Text("UFs (ex.: BA, PE)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(portais, { portais = it }, label = { Text("Portais (vírgula, opcional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(valorMin, { valorMin = it.filter { c -> c.isDigit() } }, label = { Text("Valor mín.") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(valorMax, { valorMax = it.filter { c -> c.isDigit() } }, label = { Text("Valor máx.") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ativo, onCheckedChange = { ativo = it })
                    Spacer(Modifier.height(0.dp))
                    Text("  Ativo", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
                }
            }
        },
        confirmButton = {
            PrimaryButton("Salvar", {
                onSave(
                    RadarUpsertRequest(
                        nome = nome.trim(),
                        palavrasChave = splitCsv(palavras),
                        portais = splitCsv(portais),
                        estados = splitCsv(estados).map { it.uppercase() },
                        valorMinimo = valorMin.trim().toDoubleOrNull(),
                        valorMaximo = valorMax.trim().toDoubleOrNull(),
                        ativo = ativo,
                    ),
                )
            }, enabled = nome.trim().isNotEmpty())
        },
        dismissButton = { SecondaryButton("Cancelar", onDismiss, tone = Tone.NEUTRAL) },
    )
}

private fun splitCsv(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
private fun formatNumber(s: String): String = s.toDoubleOrNull()?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: s
