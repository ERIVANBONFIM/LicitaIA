package com.licitaia.feature.tender

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.ErrorState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.IconBubble
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.LicitaScaffold
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SectionHeader
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.components.color
import com.licitaia.core.ui.components.tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.ExtractedEdital
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.pncpControlNumber
import com.licitaia.domain.util.Formatters
import kotlinx.coroutines.delay

private fun Tender.pncpControlNumberOrNull(): String? = pncpControlNumber

/** Abas da tela: Análise (padrão), Perguntas ("Pergunte ao edital") e Itens (itens oficiais). */
private enum class AnalysisTab(val route: String, val label: String, val title: String) {
    ANALISE(Routes.TAB_ANALYSIS, "Análise", "Análise do Edital"),
    PERGUNTAS(Routes.TAB_QUESTIONS, "Perguntas", "Pergunte ao edital"),
    ITENS(Routes.TAB_ITEMS, "Itens", "Itens da licitação"),
    ;

    companion object {
        fun fromRoute(value: String?): AnalysisTab = entries.firstOrNull { it.route == value } ?: ANALISE
    }
}

@Composable
fun TenderAnalysisScreen(viewModel: TenderAnalysisViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tender = state.tender
    var tabIndex by rememberSaveable { mutableIntStateOf(AnalysisTab.fromRoute(viewModel.initialTab).ordinal) }
    val tab = AnalysisTab.entries[tabIndex]

    LicitaScaffold(
        title = tab.title,
        subtitle = tender?.number,
        showBack = true,
        actions = {
            if (tab == AnalysisTab.ANALISE && state.analysis != null) {
                IconButton(onClick = viewModel::analyze, enabled = state.canAnalyze && !state.analyzing) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Reanalisar")
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> SkeletonList(Modifier.padding(padding))
            state.notFound || tender == null -> ErrorState("Esta licitação não existe ou pertence a outra empresa.", Modifier.padding(padding), title = "Licitação não encontrada")
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = LicitaColors.Background,
                    contentColor = LicitaColors.Blue,
                ) {
                    AnalysisTab.entries.forEach { entry ->
                        Tab(
                            selected = entry == tab,
                            onClick = { tabIndex = entry.ordinal },
                            text = { Text(entry.label, maxLines = 1) },
                            selectedContentColor = LicitaColors.Blue,
                            unselectedContentColor = LicitaColors.TextSecondary,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        AnalysisTab.PERGUNTAS -> EditalQuestionsTab(tender = tender, canAsk = state.canAnalyze)
                        AnalysisTab.ITENS -> TenderItemsTab(tender = tender, canAsk = state.canAnalyze)
                        AnalysisTab.ANALISE -> AnalysisContent(state, tender, viewModel)
                    }
                }
            }
        }
    }
}

/** Conteúdo da aba "Análise" (comportamento anterior da tela). */
@Composable
private fun AnalysisContent(state: TenderAnalysisState, tender: Tender, viewModel: TenderAnalysisViewModel) {
    val navigator = LocalAppNavigator.current
    val analysis = state.analysis
    when {
    // Sem análise e nada em andamento: nada roda sozinho — card "Análise por IA ainda não feita" com "Analisar com IA".
    analysis == null && !state.analyzing && state.error == null -> IdleState(
        tender = tender, canAnalyze = state.canAnalyze, onAnalyze = viewModel::analyze,
        onOpenTender = { navigator.navigate(Routes.tender(tender.id)) }, modifier = Modifier,
    )
    analysis == null -> AnalyzingState(
        tender = tender, error = state.error, analyzing = state.analyzing, canAnalyze = state.canAnalyze,
        onAnalyze = viewModel::analyze, modifier = Modifier,
    )
    else -> LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "reanalyzing") {
            AnimatedVisibility(state.analyzing, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    AlertBanner("Reanalisando com a IA…", "Os dados abaixo serão substituídos ao concluir.", Tone.INFO, pulsing = true)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
                }
            }
            val error = state.error
            if (error != null && !state.analyzing) {
                AlertBanner("Falha na reanálise", error, Tone.DANGER, actionLabel = "Repetir", onAction = viewModel::analyze)
            }
        }
        if (analysis.heuristicOnly) {
            item(key = "heuristic") {
                HeuristicAnalysisBanner(
                    activeAi = state.activeAi, canAnalyze = state.canAnalyze, analyzing = state.analyzing,
                    onReanalyze = viewModel::analyze, onConfigure = { navigator.navigate(Routes.AI_SETTINGS) },
                    detail = "Os dados abaixo vêm de regras locais e da presunção do segmento, não da leitura do edital.",
                )
            }
        } else if (!tender.hasEditalText) {
            item(key = "no-edital") {
                AlertBanner(
                    "Análise sem o texto do edital",
                    "A IA recebeu apenas os metadados da licitação. Importe o PDF do edital na tela da licitação e reanalise para resultados reais.",
                    Tone.WARNING, actionLabel = "Licitação", onAction = { navigator.navigate(Routes.tender(tender.id)) },
                )
            }
        }
        item(key = "summary") {
            GradientCard(Modifier.fillMaxWidth()) {
                TenderHeadline(tender, analysis)
                Spacer(Modifier.height(12.dp))
                Text(analysis.summary, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge(analysis.recommendation.label, analysis.recommendation.tone())
                    Text(
                        "Provedor: ${analysis.providerName} · ${Formatters.dateTime(analysis.generatedAt)}",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextSecondary,
                    )
                }
                if (analysis.aiFields.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Campos preenchidos pela IA: ${analysis.aiFields.sorted().joinToString(", ")}. Os demais são cálculo local (cofre, prazos, geografia).",
                        style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Vale a pena participar?", { navigator.navigate(Routes.tenderWorth(tender.id)) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Balance, tone = Tone.WARNING)
            }
        }
        item(key = "extracted-h") { SectionHeader("Dados extraídos do edital") }
        item(key = "extracted") { ExtractedCard(analysis.extracted) }
        item(key = "requirements-h") { SectionHeader("Exigências técnicas") }
        item(key = "requirements") { BulletCard(analysis.extracted.technicalRequirements, "Nenhuma exigência técnica específica identificada.") }
        item(key = "docs-h") {
            val ok = state.documents.count { it.status == DocumentStatus.VALIDO }
            SectionHeader("Documentos exigidos × cofre · $ok/${state.documents.size}", actionLabel = "Cofre", onAction = { navigator.navigateTop(Routes.DOCUMENTS) })
        }
        item(key = "docs") {
            if (state.documents.isEmpty()) {
                LicitaCard(Modifier.fillMaxWidth()) {
                    Text("O edital não lista documentos específicos além da habilitação padrão.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
                }
            } else {
                LicitaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    state.documents.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(Icons.Outlined.Description, row.status.tone().color(), size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.type.label, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
                                val doc = row.document
                                Text(
                                    when {
                                        doc == null -> "Não cadastrado no cofre"
                                        doc.expiresAt != null -> "${doc.title} · vence ${Formatters.date(doc.expiresAt)}"
                                        else -> doc.title
                                    },
                                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
                                )
                            }
                            StatusBadge(row.status.label, row.status.tone())
                        }
                    }
                }
            }
        }
        item(key = "guarantees-h") { SectionHeader("Garantias e penalidades") }
        item(key = "guarantees") {
            LicitaCard(Modifier.fillMaxWidth()) {
                Text("Garantias", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                Bullets(analysis.extracted.guarantees, "Sem exigência de garantia.")
                Spacer(Modifier.height(10.dp))
                Text("Penalidades", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                Bullets(analysis.extracted.penalties, "Penalidades padrão da Lei 14.133/2021.", color = LicitaColors.RedBright)
            }
        }
        item(key = "attest-h") { SectionHeader("Atestados e certificações") }
        item(key = "attest") {
            LicitaCard(Modifier.fillMaxWidth()) {
                Text("Atestados de capacidade", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                Bullets(analysis.extracted.attestationRequirements, "Não exigidos.")
                Spacer(Modifier.height(10.dp))
                Text("Certificações", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                Bullets(analysis.extracted.certificationRequirements, "Não exigidas.")
            }
        }
        item(key = "risk-h") { SectionHeader("Análise de risco") }
        item(key = "risk") {
            LicitaCard(Modifier.fillMaxWidth()) {
                RiskRow("Risco operacional", analysis.fit.operationalRisk)
                RiskRow("Risco documental", analysis.fit.documentaryRisk)
                RiskRow("Risco contratual", analysis.fit.contractualRisk)
                if (analysis.criticalPoints.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Pontos críticos", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
                    Bullets(analysis.criticalPoints, "", color = LicitaColors.Yellow)
                }
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Gerar proposta comercial", { navigator.navigate(Routes.tenderProposal(tender.id)) }, Modifier.fillMaxWidth(), tone = Tone.SUCCESS)
                SecondaryButton(
                    if (state.analyzing) "Reanalisando…" else "Reanalisar com a IA", viewModel::analyze, Modifier.fillMaxWidth(),
                    enabled = state.canAnalyze && !state.analyzing, icon = Icons.Outlined.Refresh, tone = Tone.NEUTRAL,
                )
                if (!state.canAnalyze) {
                    Text("Seu perfil (${state.role?.label ?: "—"}) não pode iniciar análises.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    }
}

/** Licitação ainda sem análise: nada roda em segundo plano — o usuário decide quando analisar. */
@Composable
private fun IdleState(tender: Tender, canAnalyze: Boolean, onAnalyze: () -> Unit, onOpenTender: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LicitaCard(Modifier.fillMaxWidth()) { TenderHeadline(tender, null) }
        Spacer(Modifier.height(20.dp))
        if (!tender.isManual && !tender.hasEditalText) {
            EmptyState(
                title = "Análise por IA ainda não feita",
                message = "A licitação está salva com os dados da fonte. A IA só analisa quando você pedir" +
                    (if (tender.pncpControlNumberOrNull() != null) " (o edital oficial do PNCP é baixado antes, para a IA ler o documento real)." else "."),
                icon = Icons.Outlined.AutoAwesome,
                actionLabel = if (canAnalyze) "Analisar com IA" else "Abrir licitação",
                onAction = if (canAnalyze) onAnalyze else onOpenTender,
            )
        } else if (tender.hasEditalText) {
            EmptyState(
                title = "Edital pronto para análise",
                message = "O texto do edital (${tender.editalChars} caracteres) está salvo. A IA vai ler o edital real para extrair documentos, prazos, garantias, penalidades e riscos.",
                icon = Icons.Outlined.AutoAwesome,
                actionLabel = if (canAnalyze) "Analisar com IA" else null,
                onAction = if (canAnalyze) onAnalyze else null,
            )
        } else {
            EmptyState(
                title = "Importe o edital para analisar",
                message = "Sem o texto do edital a IA só teria os metadados e o resultado seria uma estimativa. Importe o PDF ou cole o texto na tela da licitação.",
                icon = Icons.Outlined.Description,
                actionLabel = "Abrir licitação",
                onAction = onOpenTender,
            )
        }
        if (!canAnalyze) {
            Text("Seu perfil não pode iniciar análises. Peça a um usuário de Licitações.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
        }
    }
}

private val ANALYZING_STEPS = listOf(
    "Lendo o edital e anexos…",
    "Extraindo objeto, prazos e exigências…",
    "Comparando documentos com o cofre…",
    "Calculando score de aderência…",
    "Montando recomendação e faixa de preço…",
)

@Composable
private fun AnalyzingState(
    tender: Tender,
    error: String?,
    analyzing: Boolean,
    canAnalyze: Boolean,
    onAnalyze: () -> Unit,
    modifier: Modifier,
) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(error) {
        while (error == null) {
            delay(1_800)
            step = (step + 1) % ANALYZING_STEPS.size
        }
    }
    val transition = rememberInfiniteTransition(label = "ai")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "rot")
    val pulse by transition.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LicitaCard(Modifier.fillMaxWidth()) { TenderHeadline(tender, null) }
        Spacer(Modifier.height(28.dp))
        if (error == null) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(110.dp).scale(pulse).alpha(0.25f).clip(CircleShape).background(LicitaColors.Blue),
                )
                Box(Modifier.size(84.dp).clip(CircleShape).background(LicitaColors.BrandGradient), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(40.dp).rotate(rotation),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("IA analisando o edital", style = MaterialTheme.typography.titleLarge, color = LicitaColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(ANALYZING_STEPS[step], style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(0.6f), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
            Spacer(Modifier.height(18.dp))
            Text(
                "Isso pode levar alguns segundos. Você pode sair da tela: a análise continua e você recebe um aviso.",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
            )
        } else {
            ErrorState(error, title = "A análise não foi concluída", onRetry = if (canAnalyze) onAnalyze else null)
            if (!canAnalyze) {
                Text("Seu perfil não pode iniciar análises. Peça a um usuário de Licitações.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
            }
        }
    }
}

@Composable
private fun ExtractedCard(e: ExtractedEdital) {
    LicitaCard(Modifier.fillMaxWidth()) {
        InfoRow("Objeto", e.objectDescription)
        InfoRow("Órgão", e.agency)
        InfoRow("Portal", e.portal)
        InfoRow("Número", e.number)
        InfoRow("Modalidade", e.modality)
        InfoRow("Valor estimado", Formatters.brl(e.estimatedValue), valueColor = LicitaColors.GreenBright)
        InfoRow("Data limite (propostas)", Formatters.dateTime(e.proposalDeadline))
        InfoRow("Abertura", Formatters.dateTime(e.openingAt))
        InfoRow("Sessão", Formatters.dateTime(e.sessionAt))
        InfoRow("Prazo de instalação", e.installationDeadline)
        InfoRow("SLA", e.sla)
    }
}

@Composable
private fun BulletCard(items: List<String>, emptyText: String) {
    LicitaCard(Modifier.fillMaxWidth()) { Bullets(items, emptyText) }
}

@Composable
private fun Bullets(items: List<String>, emptyText: String, color: Color = LicitaColors.Blue) {
    if (items.isEmpty()) {
        if (emptyText.isNotBlank()) Text(emptyText, style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
        return
    }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { text ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
            }
        }
    }
}

@Composable
internal fun RiskRow(label: String, risk: RiskLevel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextSecondary, modifier = Modifier.weight(1f))
        StatusBadge(risk.label, risk.tone(), pulsing = risk == RiskLevel.CRITICO)
    }
}
