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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QuestionAnswer
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
import com.licitaia.core.ui.nav.Routes
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
                    s = state,
                    onFavorite = viewModel::toggleFavorita,
                    onArchive = viewModel::toggleArquivar,
                    onHide = viewModel::toggleOcultar,
                    onAnalyze = viewModel::analyze,
                    onRegisterResult = viewModel::registrarResultado,
                    onOpenQa = { navigator.navigate(Routes.platformTenderQa(state.tender!!.id)) },
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
    s: PlatformDetailUi,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onHide: () -> Unit,
    onAnalyze: () -> Unit,
    onRegisterResult: (String) -> Unit,
    onOpenQa: () -> Unit,
    onOpenPortal: (String) -> Unit,
) {
    val t = s.tender ?: return
    val itens = s.itens
    val arquivos = s.arquivos
    val acting = s.acting
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.fromCache) {
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

        // Vale a pena participar? / Análise do edital por IA (ler + gerar)
        LicitaCard(Modifier.fillMaxWidth(), accent = LicitaColors.Yellow) {
            Text("Vale a pena? · Análise do edital (IA)", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            t.veredito?.takeIf { it.isNotBlank() }?.let { InfoRow("Veredito", it) }
            t.scoreRelevancia?.let { InfoRow("Relevância", "$it") }
            t.scoreRisco?.let { InfoRow("Risco", "$it") }
            t.precoSugeridoIA?.takeIf { it.isNotBlank() }?.let { InfoRow("Preço sugerido (IA)", PlatformFormat.currency(it)) }
            t.margemEstimadaIA?.takeIf { it.isNotBlank() }?.let { InfoRow("Margem estimada (IA)", it) }
            val resumo = t.editalResumoIA?.takeIf { it.isNotBlank() }
            Spacer(Modifier.height(6.dp))
            Text(
                resumo ?: "Nenhuma análise da plataforma ainda. Toque em \"Analisar com IA\" para gerar (roda no servidor).",
                style = MaterialTheme.typography.bodySmall, color = if (resumo != null) LicitaColors.TextSecondary else LicitaColors.TextMuted,
            )
            if (s.analyzing) {
                Spacer(Modifier.height(10.dp))
                AlertBanner("IA analisando o edital…", "Status: ${s.analysisStatus ?: "na fila"}. Pode levar alguns segundos.", Tone.INFO, pulsing = true)
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                if (s.analyzing) "Analisando…" else if (t.editalResumoIA.isNullOrBlank()) "Analisar com IA" else "Reanalisar com IA",
                onAnalyze, Modifier.fillMaxWidth(), enabled = !s.analyzing, tone = Tone.INFO, icon = Icons.Outlined.Analytics,
            )
        }

        // Pergunte ao edital (Q&A por IA)
        LicitaCard(Modifier.fillMaxWidth(), onClick = onOpenQa) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.QuestionAnswer, contentDescription = null, tint = LicitaColors.Blue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pergunte ao edital", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Tire dúvidas sobre prazos, exigências e documentos (IA)", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = LicitaColors.TextMuted)
            }
        }

        // Linha do tempo (derivada das datas e fase)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Linha do tempo", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            InfoRow("Publicação", PlatformFormat.dateTime(t.dataPublicacao))
            InfoRow("Abertura", PlatformFormat.dateTime(t.dataAbertura))
            InfoRow("Encerramento", PlatformFormat.dateTime(t.dataEncerramento))
            t.fase?.let { InfoRow("Fase atual", it.replace('_', ' ')) }
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

        // Robô de lance (leitura; armar/rodar = F4)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Robô de lance", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            val robo = s.roboConfig
            if (robo == null) {
                Text("Sem configuração de robô para esta licitação.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
            } else {
                InfoRow("Status", if (robo.ativo) "Armado" else "Desarmado")
                robo.modoExecucao?.let { InfoRow("Modo", it) }
                robo.estrategia?.let { InfoRow("Estratégia", it) }
                InfoRow("Lances registrados", "${s.roboLances}")
            }
            Spacer(Modifier.height(6.dp))
            Text("Armar/rodar o robô na nuvem chega em breve (F4). No modo local o robô já opera.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }

        // Resultado do pregão
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Resultado do pregão", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            val res = s.resultado
            val resStr = res?.resultado
            if (res != null && resStr != null) {
                StatusBadge(resStr, if (resStr.equals("vencida", true)) Tone.SUCCESS else Tone.WARNING)
                res.valorArrematado?.let { InfoRow("Valor arrematado", PlatformFormat.currency(it)) }
                res.posicaoFinal?.let { InfoRow("Posição final", "$it") }
                res.totalConcorrentes?.let { InfoRow("Concorrentes", "$it") }
            } else {
                Text("Ainda sem resultado. Registre quando o pregão encerrar.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Vencemos", { onRegisterResult("vencida") }, Modifier.weight(1f), enabled = !acting, tone = Tone.SUCCESS)
                    SecondaryButton("Perdemos", { onRegisterResult("perdida") }, Modifier.weight(1f), enabled = !acting, tone = Tone.NEUTRAL)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Desistimos", { onRegisterResult("desistida") }, Modifier.weight(1f), enabled = !acting, tone = Tone.NEUTRAL)
                    SecondaryButton("Anulada", { onRegisterResult("anulada") }, Modifier.weight(1f), enabled = !acting, tone = Tone.NEUTRAL)
                }
            }
        }

        // Próximos passos (sem endpoint pronto na VPS ou bloqueado)
        LicitaCard(Modifier.fillMaxWidth()) {
            Text("Em breve na nuvem", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Proposta comercial (geração por IA está sem crédito na VPS) e acompanhar o pregão ao vivo chegam em " +
                    "breve. No modo local essas funções já operam.",
                style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted,
            )
        }
    }
}
