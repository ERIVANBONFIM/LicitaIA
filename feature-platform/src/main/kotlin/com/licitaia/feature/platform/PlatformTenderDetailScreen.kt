package com.licitaia.feature.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.platform.net.PlatformFile
import com.licitaia.core.platform.net.PlatformItem
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.theme.LicitaColors

@Composable
fun PlatformTenderDetailScreen(viewModel: PlatformTenderDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { navigator.showMessage(it) }
    }

    LicitaScaffold(title = "Detalhe da licitação", subtitle = "Plataforma", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LicitaColors.Blue)
                }
                state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
                state.tender != null -> TenderDetail(
                    t = state.tender!!,
                    itens = state.itens,
                    arquivos = state.arquivos,
                    fromCache = state.fromCache,
                    acting = state.acting,
                    onFavorite = viewModel::toggleFavorita,
                    onArchive = viewModel::toggleArquivar,
                    onHide = viewModel::toggleOcultar,
                    onOpenPortal = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { navigator.showMessage("Não foi possível abrir o portal.") }
                    },
                )
            }
        }
    }
}

@Composable
private fun TenderDetail(
    t: TenderDto,
    itens: List<PlatformItem>,
    arquivos: List<PlatformFile>,
    fromCache: Boolean,
    acting: Boolean,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onHide: () -> Unit,
    onOpenPortal: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fromCache) {
            AlertBanner("Offline", "Mostrando a cópia salva; ações e detalhes completos voltam ao reconectar.", Tone.INFO)
        }

        // Cabeçalho + objeto
        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Blue) {
            Text(t.numero.ifBlank { "Licitação" }, style = MaterialTheme.typography.titleMedium, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(t.orgao, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                t.fase?.let { StatusBadge(it.replace('_', ' '), Tone.INFO) }
                t.status?.let { StatusBadge(it, if (it.equals("ativa", true)) Tone.SUCCESS else Tone.WARNING) }
                if (t.favorita) StatusBadge("interesse", Tone.SUCCESS)
            }
            Spacer(Modifier.height(10.dp))
            Text(t.objeto, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        }

        // Ações (VPS)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Ações", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    if (t.favorita) "Remover interesse" else "Marcar interesse",
                    onFavorite, Modifier.fillMaxWidth(), enabled = !acting, tone = Tone.SUCCESS,
                    icon = if (t.favorita) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                )
                SecondaryButton(
                    if (t.status.equals("arquivada", true)) "Desarquivar" else "Arquivar",
                    onArchive, Modifier.fillMaxWidth(), enabled = !acting, tone = Tone.NEUTRAL,
                    icon = Icons.Outlined.Archive,
                )
                SecondaryButton(
                    if (t.status.equals("oculta", true)) "Reexibir" else "Ocultar",
                    onHide, Modifier.fillMaxWidth(), enabled = !acting, tone = Tone.NEUTRAL,
                    icon = Icons.Outlined.VisibilityOff,
                )
                t.portalUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    SecondaryButton("Abrir no portal", { onOpenPortal(url) }, Modifier.fillMaxWidth(), tone = Tone.INFO, icon = Icons.Outlined.OpenInNew)
                }
            }
        }

        // Dados
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
            InfoRow("Participação", if (!t.urlProposta.isNullOrBlank()) "Proposta vinculada" else "Sem proposta vinculada")
        }

        // Análise / IA (leitura)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Análise do edital (IA)", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            t.veredito?.takeIf { it.isNotBlank() }?.let { InfoRow("Veredito", it) }
            t.scoreRelevancia?.let { InfoRow("Relevância", "$it") }
            t.scoreRisco?.let { InfoRow("Risco", "$it") }
            val resumo = t.editalResumoIA?.takeIf { it.isNotBlank() }
            Spacer(Modifier.height(6.dp))
            Text(
                resumo ?: "Nenhuma análise da plataforma ainda. Gerar análise com IA chega em breve no modo nuvem (já disponível no modo local).",
                style = MaterialTheme.typography.bodySmall, color = if (resumo != null) LicitaColors.TextSecondary else LicitaColors.TextMuted,
            )
        }

        // Itens
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Itens (${itens.size})", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            if (itens.isEmpty()) {
                Text("Sem itens carregados na plataforma. Abra no portal para ver a íntegra.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
            } else {
                itens.take(50).forEachIndexed { i, it ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Text(it.descricao ?: "Item ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary, fontWeight = FontWeight.Medium)
                    val meta = listOfNotNull(
                        it.quantidade?.let { q -> "Qtd: $q" },
                        it.unidade?.let { u -> "Un: $u" },
                        it.valor?.let { v -> "Valor: ${PlatformFormat.currency(v)}" },
                    )
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary)
                }
            }
        }

        // Documentos / arquivos
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Documentos (${arquivos.size})", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            if (arquivos.isEmpty()) {
                Text("Nenhum arquivo do edital disponível na plataforma.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
            } else {
                arquivos.forEachIndexed { i, a ->
                    if (i > 0) Spacer(Modifier.height(4.dp))
                    Text(a.nome ?: "Arquivo ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                    a.tipo?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary) }
                }
            }
        }

        // Próximos passos (ainda sem endpoint pronto/simples no modo nuvem)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Em breve na nuvem", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Gerar proposta, robô de lance, mensagens do pregoeiro desta licitação e gerar análise por IA " +
                    "estarão disponíveis no modo nuvem em breve. No modo local essas funções já operam.",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted,
            )
        }
    }
}
