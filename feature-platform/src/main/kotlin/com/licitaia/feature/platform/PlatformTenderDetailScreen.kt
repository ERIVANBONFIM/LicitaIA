package com.licitaia.feature.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.theme.LicitaColors

@Composable
fun PlatformTenderDetailScreen(viewModel: PlatformTenderDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LicitaScaffold(title = "Detalhe da licitação", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LicitaColors.Blue)
                }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.tender != null -> TenderDetail(state.tender!!, state.fromCache)
            }
        }
    }
}

@Composable
private fun TenderDetail(t: TenderDto, fromCache: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fromCache) {
            AlertBanner("Offline", "Mostrando a cópia local; não foi possível falar com a plataforma agora.", Tone.INFO)
        }
        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Blue) {
            Text(t.numero.ifBlank { "Licitação" }, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(t.orgao, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
            t.fase?.let {
                Spacer(Modifier.height(8.dp))
                StatusBadge(it.replace('_', ' '), Tone.INFO)
            }
            Spacer(Modifier.height(10.dp))
            Text(t.objeto, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        }

        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Dados", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            InfoRow("Modalidade", t.modalidade ?: "—")
            t.modoDisputa?.let { InfoRow("Modo de disputa", it) }
            InfoRow("Valor estimado", PlatformFormat.currency(t.valorEstimado))
            InfoRow("Publicação", PlatformFormat.dateTime(t.dataPublicacao))
            InfoRow("Abertura", PlatformFormat.dateTime(t.dataAbertura))
            InfoRow("Encerramento", PlatformFormat.dateTime(t.dataEncerramento))
            t.uasg?.let { InfoRow("UASG", it) }
            InfoRow("Local", listOfNotNull(t.cidade, t.estado).joinToString("/").ifBlank { "—" })
            t.portal?.let { InfoRow("Portal", it) }
            t.status?.let { InfoRow("Status", it) }
            InfoRow("Participação", if (!t.urlProposta.isNullOrBlank()) "Proposta vinculada" else "Sem proposta vinculada")
        }
    }
}
