package com.licitaia.feature.live.automation

import com.licitaia.domain.model.Portal
import com.licitaia.domain.portal.MyTendersRefreshSummary
import com.licitaia.domain.portal.MyTendersRefresher
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalNotLoggedInException
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.PortalTenderMatching
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.TenderRepository
import com.licitaia.feature.live.web.PortalWebViewHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Buscar minhas licitações" do Comprasnet com a sessão logada do usuário, na aba RETIDA (a mesma do usuário, anexada
 * à janela do app — [PortalWebViewHolder.installHost]):
 *
 * menu "Compras" → "Licitação e Dispensa (novo)" ([PortalSessionGate.ensureElectronic]) → lista "Compras eletrônicas" →
 * aba "Minhas participações" → filtros "Em andamento" e "Propostas" → todas as páginas → cartões
 * ([SpaPurchaseParser]: modalidade, número/ano, UASG, órgão, etapa/prazo, compra suspensa/anulada/revogada, favorita).
 *
 * As páginas legadas `/assinadas/cotacao.asp` NÃO são lidas: são da antiga Cotação Eletrônica (Lei 8.666) e não listam
 * pregões/dispensas atuais. Favoritas em "Todas as compras" não têm filtro próprio (a lista tem milhares de compras):
 * só entram as favoritas que aparecem em "Minhas participações" (coração cheio → "Favorita" em `situation`).
 *
 * Só com o app aberto (WebView numa janela) e com o portal fora da tela (a busca navega na aba). O resultado é casado
 * com as oportunidades/licitações do app ([PortalTenderMatching]) e gravado em `portal_my_tenders`.
 */
@Singleton
class MyTendersSync @Inject constructor(
    private val holder: PortalWebViewHolder,
    private val gate: PortalSessionGate,
    private val repo: PortalRobotRepository,
    private val opportunities: OpportunityRepository,
    private val tenders: TenderRepository,
) : MyTendersRefresher {

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _lastResult = MutableStateFlow<String?>(null)
    /** Texto do último resultado ("12 licitações · 3 casadas" / motivo da falha). */
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()
    @Volatile var lastRunAt: Long = 0L
        private set

    override suspend fun refreshMyTenders(companyId: Long, includeElectronic: Boolean): Result<MyTendersRefreshSummary> = mutex.withLock {
        _running.value = true
        try {
            val result = runCatching { doRefresh(companyId, includeElectronic) }
            if (result.exceptionOrNull() is CancellationException) throw result.exceptionOrNull()!!
            lastRunAt = System.currentTimeMillis()
            _lastResult.value = result.fold(
                { s -> "${s.found} licitação(ões) · ${s.matched} casada(s) com o app" + if (s.warnings.isNotEmpty()) " · ${s.warnings.size} aviso(s)" else "" },
                { it.message ?: "Falha ao buscar." },
            )
            result
        } finally {
            _running.value = false
        }
    }

    private suspend fun doRefresh(companyId: Long, includeElectronic: Boolean): MyTendersRefreshSummary {
        // A única fonte é a lista do SPA, que navega na aba retida: sem ela não há o que ler.
        if (!includeElectronic) return MyTendersRefreshSummary(0, 0, emptyList(), listOf("Leitura do portal não solicitada."))
        val entry = withContext(Dispatchers.Main) { holder.peek(companyId, Portal.COMPRAS_GOV) }
        if (entry?.visible == true) error("Saia da tela do Comprasnet para buscar: a busca usa a mesma aba do portal.")
        val owner = "a busca de minhas licitações"
        gate.claimTab(owner)?.let { other -> error("A aba do Comprasnet está em uso ($other). Tente de novo em instantes.") }
        try {
            when (val g = gate.ensureElectronic(companyId, allowAutoLogin = false)) {
                PortalSessionGate.Result.Electronic -> Unit
                is PortalSessionGate.Result.NotLogged -> throw PortalNotLoggedInException()
                is PortalSessionGate.Result.Failed -> error("Compras eletrônicas: ${g.reason}")
                PortalSessionGate.Result.AppNotVisible -> error(PortalSessionGate.OPEN_APP_MESSAGE)
                PortalSessionGate.Result.Workspace -> error("Compras eletrônicas não abriu.")
            }
            val now = System.currentTimeMillis()
            val nav = SpaNavigator(gate.driver(companyId))
            val (cards, warnings) = nav.readMyParticipations(FILTERS)
            val items = SpaPurchaseParser.toMyTenders(cards, companyId, PortalMyTender.SOURCE_PARTICIPACOES, participation = true, now = now)
            if (items.isEmpty() && warnings.isNotEmpty()) error("Minhas participações: ${warnings.first()}")
            val matched = match(companyId, items)
            repo.upsertMyTenders(companyId, matched)
            return MyTendersRefreshSummary(
                matched.size, matched.count { it.matchedOpportunityId != null || it.matchedTenderId != null },
                listOf(PortalMyTender.SOURCE_PARTICIPACOES), warnings,
            )
        } finally {
            gate.releaseTab(owner)
        }
    }

    /** Casa com licitações ("Tenho interesse") e com oportunidades já em cache (ids prováveis). */
    private suspend fun match(companyId: Long, items: List<PortalMyTender>): List<PortalMyTender> {
        val myTenders = runCatching { withTimeoutOrNull(5_000) { tenders.observeTenders(companyId).first() } }.getOrNull().orEmpty()
        return items.map { mine ->
            var oppId: String? = null
            val ids = buildList {
                mine.pncpControl?.let { add("${Portal.COMPRAS_GOV.name}:$it"); add("${Portal.PNCP.name}:$it") }
                val num = mine.number.padStart(5, '0')
                // Código de modalidade do id legado (05 pregão, 06 dispensa, 03 concorrência, 02/01 TP/convite, 20 concurso).
                val known = CompraCode.modalityCode(mine.modality)
                (listOfNotNull(known) + listOf("05", "06", "03", "02", "01", "20")).distinct()
                    .forEach { add("${Portal.COMPRAS_GOV.name}:${mine.uasg}-$it-$num/${mine.year}") }
            }
            for (id in ids) {
                val opp = runCatching { opportunities.getOpportunity(id) }.getOrNull() ?: continue
                if (PortalTenderMatching.match(mine, listOf(opp)) != null) { oppId = opp.id; break }
            }
            val tender = PortalTenderMatching.matchTender(mine, oppId, myTenders)
            mine.copy(matchedOpportunityId = oppId ?: tender?.opportunityId, matchedTenderId = tender?.id)
        }
    }

    private companion object {
        /** Filtros de "Minhas participações" lidos (o portal pagina 10 por página). */
        val FILTERS = listOf("Em andamento", "Propostas")
    }
}
