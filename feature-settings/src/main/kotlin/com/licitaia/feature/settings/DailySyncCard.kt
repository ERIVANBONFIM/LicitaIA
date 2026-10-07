package com.licitaia.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.sync.DailySyncRepository
import com.licitaia.domain.sync.DailySyncSchedule
import com.licitaia.domain.sync.DailySyncSettings
import com.licitaia.domain.sync.DailySyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Configuração da atualização diária das licitações (horário e liga/desliga, gravados no DataStore). */
@HiltViewModel
class DailySyncViewModel @Inject constructor(private val daily: DailySyncRepository) : ViewModel() {
    val state: StateFlow<Pair<DailySyncSettings, DailySyncStatus>> = combine(daily.settings, daily.status) { s, st -> s to st }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailySyncSettings() to DailySyncStatus())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { runCatching { daily.updateSettings { it.copy(enabled = enabled) } } }
    }

    fun setTime(hour: Int, minute: Int) {
        viewModelScope.launch { runCatching { daily.updateSettings { it.copy(hour = hour, minute = minute) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailySyncCard(haptic: Boolean, viewModel: DailySyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val (settings, status) = state
    var picking by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val line = remember(settings, status, now / 60_000L) { DailySyncSchedule.sourceLine(status, settings, now) }

    LicitaCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = settings.enabled) { picking = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(Icons.Outlined.Schedule, LicitaColors.Cyan)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (settings.enabled) "Atualização diária: ${settings.timeLabel}" else "Atualização diária: desligada",
                    style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary,
                )
                Text(
                    if (settings.enabled) "Toque para mudar o horário. Baixa licitações e pregões novos (Compras.gov.br e PNCP), prazos e notas dos radares antes de você abrir o app."
                    else "As licitações só são baixadas quando você puxar para atualizar na busca.",
                    style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
                )
            }
        }
        Text(line, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
        HorizontalDivider(color = LicitaColors.OutlineSoft, modifier = Modifier.padding(vertical = 6.dp))
        SwitchRow(
            "Atualizar automaticamente todo dia",
            "Também apaga do aparelho as canceladas, encerradas e as de meses anteriores que não estão mais abertas (nunca as de interesse ou com análise/proposta).",
            settings.enabled, viewModel::setEnabled, haptic = haptic,
        )
    }

    if (picking) {
        val picker = rememberTimePickerState(initialHour = settings.hour, initialMinute = settings.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Horário da atualização diária") },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = { viewModel.setTime(picker.hour, picker.minute); picking = false }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancelar") } },
        )
    }
}
