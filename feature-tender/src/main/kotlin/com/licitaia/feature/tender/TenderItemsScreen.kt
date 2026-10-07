package com.licitaia.feature.tender

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.licitaia.core.ui.components.AlertBanner
import com.licitaia.core.ui.components.ButtonRow
import com.licitaia.core.ui.components.EmptyState
import com.licitaia.core.ui.components.GradientCard
import com.licitaia.core.ui.components.InfoRow
import com.licitaia.core.ui.components.LicitaCard
import com.licitaia.core.ui.components.PrimaryButton
import com.licitaia.core.ui.components.SecondaryButton
import com.licitaia.core.ui.components.SkeletonList
import com.licitaia.core.ui.components.StatusBadge
import com.licitaia.core.ui.components.Tone
import com.licitaia.core.ui.nav.LocalAppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.core.ui.theme.LicitaColors
import com.licitaia.domain.edital.EditalQuestion
import com.licitaia.domain.edital.EditalQuestionPrompt
import com.licitaia.domain.edital.EditalQuestionStatus
import com.licitaia.domain.model.Tender
import com.licitaia.domain.proposal.OfficialBuyer
import com.licitaia.domain.proposal.OfficialItemFields
import com.licitaia.domain.proposal.OfficialTenderItem
import com.licitaia.domain.repository.EditalQuestionRepository
import com.licitaia.domain.repository.TenderItems
import com.licitaia.domain.repository.TenderItemsRepository
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TenderItemsState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val result: TenderItems? = null,
    val error: String? = null,
    /** Perguntas gravadas desta licitação (para achar a resposta de "Buscar no edital" de cada item). */
    val questions: List<EditalQuestion> = emptyList(),
    /** Ids das perguntas sendo respondidas agora. */
    val answering: Set<Long> = emptySet(),
    /** Itens com "Buscar no edital" enviado e ainda sem registro gravado. */
    val asking: Set<Int> = emptySet(),
) {
    /** Última pergunta "local de entrega" gravada para o item, se houver. */
    fun deliveryQuestion(item: OfficialTenderItem): EditalQuestion? {
        val text = EditalQuestionPrompt.deliveryQuestion(item)
        return questions.lastOrNull { it.question == text }
    }
}

private data class ItemsLocal(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val result: TenderItems? = null,
    val error: String? = null,
    val asking: Set<Int> = emptySet(),
)

/** Aba "Itens": itens oficiais (PNCP → Compras.gov.br) com cache em memória por sessão; "Atualizar" consulta de novo. */
@HiltViewModel
class TenderItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TenderItemsRepository,
    private val questionsRepository: EditalQuestionRepository,
) : ViewModel() {

    private val tenderId: Long = savedStateHandle.longArg("tenderId") ?: -1L
    private val local = MutableStateFlow(ItemsLocal())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val state: StateFlow<TenderItemsState> = combine(
        local,
        questionsRepository.observeQuestions(tenderId).catch { emit(emptyList()) },
        questionsRepository.observeAnswering().catch { emit(emptySet()) },
    ) { l, questions, answering ->
        TenderItemsState(
            loading = l.loading, refreshing = l.refreshing, result = l.result, error = l.error,
            questions = questions, answering = answering, asking = l.asking,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TenderItemsState())

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        if (tenderId <= 0 || local.value.refreshing) return
        local.update { it.copy(refreshing = refresh, loading = it.result == null, error = null) }
        viewModelScope.launch {
            val result = try {
                repository.officialItems(tenderId, refresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            local.update { current ->
                result.fold(
                    onSuccess = { current.copy(loading = false, refreshing = false, result = it, error = null) },
                    onFailure = { current.copy(loading = false, refreshing = false, error = it.message ?: "Não foi possível consultar os itens oficiais.") },
                )
            }
        }
    }

    /**
     * "Buscar no edital": pergunta à IA (fluxo do "Pergunte ao edital", com documento/página na fonte) o endereço/local de
     * entrega ou execução do item. A pergunta fica gravada no histórico da aba Perguntas.
     */
    fun searchDelivery(item: OfficialTenderItem) {
        if (tenderId <= 0 || item.number in local.value.asking) return
        local.update { it.copy(asking = it.asking + item.number) }
        viewModelScope.launch {
            val result = try {
                questionsRepository.ask(tenderId, EditalQuestionPrompt.deliveryQuestion(item))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            local.update { it.copy(asking = it.asking - item.number) }
            result.exceptionOrNull()?.let { _messages.tryEmit(it.message ?: "A IA não respondeu.") }
        }
    }
}

/** Aba "Itens" da tela de análise do edital: cartões resumidos; o toque abre o detalhe completo do item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TenderItemsTab(tender: Tender, canAsk: Boolean = true, viewModel: TenderItemsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    LaunchedEffect(viewModel) { viewModel.messages.collect(navigator::showMessage) }
    val result = state.result
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    when {
        state.loading && result == null -> Column(Modifier.fillMaxSize()) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
            SkeletonList()
        }
        result == null -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            AlertBanner(
                "Itens indisponíveis", state.error ?: "Não foi possível consultar os itens oficiais.", Tone.WARNING,
                actionLabel = "Atualizar", onAction = viewModel::refresh,
            )
        }
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                ItemsSummary(
                    tender = tender, result = result, refreshing = state.refreshing, error = state.error,
                    onRefresh = viewModel::refresh,
                    onUseInProposal = { navigator.navigate(Routes.tenderProposal(tender.id)) },
                )
            }
            if (result.items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "Nenhum item oficial",
                        message = result.message ?: "A fonte oficial não publicou itens para esta contratação.",
                        icon = Icons.Outlined.Inventory2,
                    )
                }
            }
            // Chave com o índice: fontes podem repetir o nº do item (lotes).
            itemsIndexed(result.items, key = { index, item -> "$index-${item.number}" }) { index, item ->
                OfficialItemCard(item, onOpen = { selected = index })
            }
        }
    }

    val item = selected?.let { result?.items?.getOrNull(it) }
    if (item != null && result != null) {
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LicitaColors.SurfaceElevated,
        ) {
            val question = state.deliveryQuestion(item)
            OfficialItemDetail(
                item = item,
                tender = tender,
                buyer = result.buyer,
                source = result.source,
                deliveryQuestion = question,
                searching = item.number in state.asking || (question != null && question.id in state.answering),
                canSearch = canAsk && tender.hasEditalText,
                searchHint = when {
                    !canAsk -> "Seu perfil não pode fazer perguntas ao edital."
                    !tender.hasEditalText -> "Baixe os documentos do edital (aba Perguntas) para buscar o local de entrega."
                    else -> null
                },
                onSearch = { viewModel.searchDelivery(item) },
            )
        }
    }
}

@Composable
private fun ItemsSummary(
    tender: Tender,
    result: TenderItems,
    refreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onUseInProposal: () -> Unit,
) {
    GradientCard(Modifier.fillMaxWidth()) {
        Text("Itens oficiais", style = MaterialTheme.typography.labelMedium, color = LicitaColors.TextSecondary)
        Text(
            if (result.items.isEmpty()) "—" else Formatters.brl(result.knownTotal),
            style = MaterialTheme.typography.headlineSmall, color = LicitaColors.GreenBright, fontWeight = FontWeight.Bold,
        )
        Text(
            buildString {
                append("${result.items.size} item(ns)")
                if (result.items.isNotEmpty()) append(" · total geral estimado")
                if (result.confidentialCount > 0) append(" · ${result.confidentialCount} sigiloso(s)/sem valor fora do total")
            },
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(result.source?.let { "Fonte: $it" } ?: "Fonte oficial")
                append(" · consultado em ${Formatters.dateTime(result.fetchedAt)}")
                if (result.fromCache) append(" (salvo nesta sessão)")
            },
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )
        if (result.items.isNotEmpty()) {
            Text("Toque em um item para ver todos os detalhes, o órgão e o local de entrega.", style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted)
        }
        if (tender.estimatedValue > 0 && result.items.isNotEmpty() && result.confidentialCount == 0 &&
            kotlin.math.abs(result.knownTotal - tender.estimatedValue) > tender.estimatedValue * 0.01
        ) {
            Text(
                "Valor estimado do cadastro: ${Formatters.brl(tender.estimatedValue)} (difere da soma dos itens).",
                style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow,
            )
        }
        if (refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = LicitaColors.Blue, trackColor = LicitaColors.Outline)
        }
        if (error != null && !refreshing) {
            Spacer(Modifier.height(8.dp))
            AlertBanner("Falha ao atualizar", error, Tone.WARNING)
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            SecondaryButton(
                if (refreshing) "Atualizando…" else "Atualizar", onRefresh, Modifier.weight(1f),
                enabled = !refreshing, icon = Icons.Outlined.Refresh, tone = Tone.NEUTRAL,
            )
            PrimaryButton("Usar na proposta", onUseInProposal, Modifier.weight(1.3f), icon = Icons.Outlined.RequestQuote, tone = Tone.SUCCESS)
        }
    }
}

/** Cartão resumido (como antes): nº, badges, descrição em 3 linhas, quantidade e valores. Toque → detalhe completo. */
@Composable
private fun OfficialItemCard(item: OfficialTenderItem, onOpen: () -> Unit) {
    LicitaCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Item ${item.number}", style = MaterialTheme.typography.titleSmall, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            if (item.confidentialBudget) StatusBadge("Sigiloso", Tone.WARNING)
            item.materialOrService?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(6.dp))
                StatusBadge(it, Tone.NEUTRAL)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Ver detalhes", tint = LicitaColors.TextMuted)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.description.ifBlank { "(sem descrição)" },
            style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        InfoRow("Quantidade", "${numberText(item.quantity)} ${item.unit.ifBlank { "un" }}")
        InfoRow("Valor unitário estimado", unitPriceText(item))
        InfoRow(
            "Valor total",
            totalText(item),
            valueColor = if (item.referenceTotal != null && !item.confidentialBudget) LicitaColors.GreenBright else LicitaColors.TextMuted,
        )
        item.benefit?.takeIf { it.isNotBlank() }?.let { InfoRow("Benefício ME/EPP", it) }
    }
}

private fun unitPriceText(item: OfficialTenderItem): String = when {
    item.confidentialBudget -> "Sigiloso"
    item.estimatedUnitPrice != null -> Formatters.brl(item.estimatedUnitPrice)
    else -> "Não informado"
}

private fun totalText(item: OfficialTenderItem): String =
    if (item.confidentialBudget) "Sigiloso" else item.referenceTotal?.let { Formatters.brl(it) } ?: "Não informado"

private fun yesNo(value: Boolean?): String? = when (value) {
    true -> "Sim"
    false -> "Não"
    null -> null
}

/** Detalhe completo do item: tudo o que a fonte oficial publicou + órgão/unidade + local de entrega (busca no edital). */
@Composable
private fun OfficialItemDetail(
    item: OfficialTenderItem,
    tender: Tender,
    buyer: OfficialBuyer?,
    source: String?,
    deliveryQuestion: EditalQuestion?,
    searching: Boolean,
    canSearch: Boolean,
    searchHint: String?,
    onSearch: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Item ${item.number}", style = MaterialTheme.typography.titleLarge, color = LicitaColors.TextPrimary, modifier = Modifier.weight(1f))
            if (item.confidentialBudget) StatusBadge("Sigiloso", Tone.WARNING)
            item.situation?.let {
                Spacer(Modifier.width(6.dp))
                StatusBadge(it, Tone.INFO)
            }
        }
        Text(
            "${tender.portal.shortName} ${tender.number}" + (source?.let { " · fonte: $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted,
        )

        DetailSection("Descrição completa")
        SelectionContainer {
            Text(item.description.ifBlank { "(sem descrição)" }, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary)
        }
        item.complementaryInfo?.let {
            DetailSection("Informação complementar")
            SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary) }
        }

        DetailSection("Quantidade e valores")
        InfoRow("Quantidade", "${numberText(item.quantity)} ${item.unit.ifBlank { "un" }}")
        InfoRow("Valor unitário estimado", unitPriceText(item))
        InfoRow("Valor total estimado", totalText(item), valueColor = if (item.referenceTotal != null && !item.confidentialBudget) LicitaColors.GreenBright else LicitaColors.TextMuted)
        InfoRow("Orçamento sigiloso", if (item.confidentialBudget) "Sim" else "Não")

        DetailSection("Classificação")
        item.materialOrService?.takeIf { it.isNotBlank() }?.let { InfoRow("Material/Serviço", it) }
        item.category?.let { InfoRow("Categoria", it) }
        item.judgingCriterion?.takeIf { it.isNotBlank() }?.let { InfoRow("Critério de julgamento", it) }
        item.benefit?.takeIf { it.isNotBlank() }?.let { InfoRow("Benefício ME/EPP", it) }
        item.situation?.let { InfoRow("Situação do item", it) }
        if (item.catalogName != null || item.catalogCode != null) {
            InfoRow("Catálogo (CATMAT/CATSER)", listOfNotNull(item.catalogName, item.catalogCode?.let { "código $it" }).joinToString(" · "))
        }
        if (item.ncmNbsCode != null || item.ncmNbsDescription != null) {
            InfoRow("NCM/NBS", listOfNotNull(item.ncmNbsCode, item.ncmNbsDescription).joinToString(" — "))
        }
        item.preferenceMargin?.let { InfoRow("Margem de preferência", it) }
        yesNo(item.productiveIncentive)?.let { InfoRow("Incentivo produtivo básico (PPB)", it) }
        yesNo(item.nationalContentRequired)?.let { InfoRow("Exige conteúdo nacional", it) }
        if (item.hasResult == true || item.supplier != null) {
            InfoRow("Resultado", item.supplier?.let { "Homologado para $it" } ?: "Resultado publicado")
        }
        OfficialItemFields.formatDateTime(item.includedAt)?.let { InfoRow("Incluído na fonte em", it) }
        OfficialItemFields.formatDateTime(item.updatedAt)?.let { InfoRow("Atualizado em", it) }

        DetailSection("Órgão e local")
        if (buyer == null) {
            Text("Dados do órgão indisponíveis.", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextMuted)
        } else {
            buyer.agencyName?.let { InfoRow("Órgão", it) }
            buyer.agencyCnpj?.let { InfoRow("CNPJ", Formatters.cnpj(it)) }
            if (buyer.unitCode != null || buyer.unitName != null) {
                InfoRow("Unidade compradora", listOfNotNull(buyer.unitCode?.let { "UASG $it" }, buyer.unitName).joinToString(" — "))
            }
            if (buyer.city != null || buyer.uf != null) InfoRow("Município/UF", listOfNotNull(buyer.city, buyer.uf).joinToString("/"))
            if (!buyer.fromOfficialSource) {
                Text("Dados do cadastro da licitação (a fonte oficial não respondeu).", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow)
            }
        }

        DetailSection("Local de entrega/execução")
        Text(
            "O endereço de entrega fica nos documentos (edital, termo de referência). A IA procura nos documentos da base e " +
                "responde só com o que está escrito, citando documento e página.",
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        DeliveryAnswer(deliveryQuestion, searching)
        Spacer(Modifier.height(6.dp))
        SecondaryButton(
            when {
                searching -> "Buscando no edital…"
                deliveryQuestion != null -> "Buscar de novo no edital"
                else -> "Buscar no edital"
            },
            onSearch, Modifier.fillMaxWidth(), enabled = canSearch && !searching, icon = Icons.Outlined.TravelExplore,
        )
        searchHint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = LicitaColors.Yellow) }
    }
}

@Composable
private fun DetailSection(title: String) {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = LicitaColors.Outline)
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = LicitaColors.Blue, fontWeight = FontWeight.SemiBold)
}

/** Resposta gravada de "Buscar no edital" (com fontes e aviso de "sem fonte"). */
@Composable
private fun DeliveryAnswer(question: EditalQuestion?, searching: Boolean) {
    when {
        searching -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LicitaColors.Blue)
            Spacer(Modifier.width(10.dp))
            Text("Procurando o local de entrega nos documentos…", style = MaterialTheme.typography.bodySmall, color = LicitaColors.TextSecondary)
        }
        question == null -> Unit
        question.status == EditalQuestionStatus.OK -> LicitaCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = LicitaColors.Blue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${Formatters.dateTime(question.createdAt)} · ${question.provider}",
                    style = MaterialTheme.typography.labelSmall, color = LicitaColors.TextMuted, modifier = Modifier.weight(1f),
                )
                if (question.unsourced) StatusBadge("Sem fonte — confira", Tone.WARNING)
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer { Text(question.answer, style = MaterialTheme.typography.bodyMedium, color = LicitaColors.TextPrimary) }
            question.sources.forEach { Text("Fonte: $it", style = MaterialTheme.typography.labelSmall, color = LicitaColors.Blue) }
        }
        else -> Text(
            question.answer.ifBlank { "A busca foi interrompida. Toque em \"Buscar de novo no edital\"." },
            style = MaterialTheme.typography.bodySmall, color = LicitaColors.RedBright,
        )
    }
}
