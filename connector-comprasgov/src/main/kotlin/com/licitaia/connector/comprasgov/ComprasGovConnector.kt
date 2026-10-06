package com.licitaia.connector.comprasgov

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.LiveSessionHandle
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalBidState
import com.licitaia.connector.api.PortalConnector
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.PortalMessage
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.SubmissionResult
import com.licitaia.connector.api.TenderDetails
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Conector REAL do Compras.gov.br — somente consulta pública via API de Dados Abertos, sem chave/login.
 *
 * Fonte: https://dadosabertos.compras.gov.br (OpenAPI em /v3/api-docs). A API expõe as contratações da
 * Lei 14.133 publicadas no PNCP pelo Compras.gov.br (módulo contratações) e as licitações da Lei 8.666
 * (módulo legado). Nenhuma ação autenticada (proposta, lance, mensagem) existe nessa API: os métodos
 * correspondentes devolvem falha explícita e nunca simulam sucesso.
 *
 * Observações verificadas em 06/10/2026:
 * - A listagem vem ordenada por data de publicação ASCENDENTE e com defasagem de alguns dias em relação ao
 *   PNCP (última publicação disponível: 29/09/2026). Por isso a busca sonda o total e lê as ÚLTIMAS páginas.
 * - Contratações cujo prazo de propostas já encerrou são descartadas (não são mais oportunidades).
 * - O módulo legado não tem registros em 2026 (e os de 2024/2025 têm `pertence14133 = true`, isto é,
 *   são duplicatas do módulo 14.133). Ele só é consultado quando o filtro fixa Pregão ou Concorrência.
 */
class ComprasGovConnector internal constructor(
    private val api: ComprasGovApi,
    private val clock: () -> Long = System::currentTimeMillis,
) : PortalConnector {

    constructor(
        client: OkHttpClient,
        json: Json = defaultJson(),
        baseUrl: HttpUrl = ComprasGovApi.DEFAULT_BASE_URL.toHttpUrl(),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(ComprasGovApi(client, json, baseUrl), clock)

    override val portal: Portal = Portal.COMPRAS_GOV

    override val capabilities: ConnectorCapabilities = ConnectorCapabilities(
        supportsWebView = true,
        supportsOfficialApi = true,
        supportsBrowserAutomation = false,
        supportsPersistentSession = false,
        requiresMfa = false,
        mayShowCaptcha = false,
        isMock = false,
        limitations = listOf(
            "Somente consulta pública (API de Dados Abertos do Compras.gov.br, sem chave ou login); " +
                "lances, propostas e mensagens são manuais no portal.",
            "A busca cobre contratações da Lei 14.133 publicadas nos últimos $SEARCH_WINDOW_DAYS dias com propostas ainda abertas, " +
                "limitada a $MAX_ITEMS_PER_SEARCH resultados; os dados abertos têm defasagem de alguns dias em relação ao PNCP.",
            "Modalidades representadas: Pregão Eletrônico, Dispensa e Concorrência Eletrônica. " +
                "Credenciamento não tem código de consulta nesta API; inexigibilidade e presenciais não são listadas.",
            "Licitações da Lei 8.666 (módulo legado) só são consultadas para Pregão/Concorrência e não trazem UF, município nem nome do órgão (apenas UASG).",
            "A API não oferece busca por texto: palavras-chave e valores são filtrados no aparelho sobre os resultados obtidos.",
            "Segmento é inferido por palavras do objeto (heurística); valor estimado 0 indica orçamento sigiloso ou não informado.",
            "A data da sessão de disputa não é publicada; o app usa o fim do recebimento de propostas.",
            "O payload não traz URL da compra no Compras.gov.br: o link aponta para a página pública do PNCP do mesmo número de controle.",
        ),
    )

    // ------------------------------------------------------------ consulta pública

    override suspend fun listOpportunities(filter: OpportunityFilter): List<Opportunity> {
        if (filter.portals.isNotEmpty() && Portal.COMPRAS_GOV !in filter.portals) return emptyList()

        val now = clock()
        val dataFinal = ComprasGovMapper.queryDate(now)
        val dataInicial = ComprasGovMapper.queryDate(now - SEARCH_WINDOW_DAYS * DAY_MS)
        val codes: List<Int> = filter.modality?.let { m -> listOfNotNull(ComprasGovModalities.codeOf(m)) } ?: ComprasGovModalities.SEARCHED_CODES
        val ufs: List<String?> = filter.ufs.map { it.trim().uppercase() }.filter { it.length == 2 }.distinct()
            .takeIf { it.isNotEmpty() && it.size <= MAX_UF_QUERIES } ?: listOf(null)

        val collected = LinkedHashMap<String, Opportunity>()
        if (codes.isNotEmpty()) {
            val combos = codes.size * ufs.size
            val perCombo = (MAX_ITEMS_PER_SEARCH / combos).coerceIn(ComprasGovApi.MIN_PAGE_SIZE, ComprasGovApi.MAX_PAGE_SIZE)
            for (uf in ufs) {
                for (code in codes) {
                    if (collected.size >= MAX_ITEMS_PER_SEARCH) break
                    collectNewest14133(dataInicial, dataFinal, code, uf, perCombo, now, collected)
                }
            }
        }

        // Legado (Lei 8.666): complemento opcional, só para modalidades compatíveis; falha nele não derruba a busca.
        val legacyCode = filter.modality?.let(ComprasGovModalities::legacyCodeOf)
        if (legacyCode != null && collected.size < MAX_ITEMS_PER_SEARCH) {
            try {
                api.licitacoesLegado(dataInicial, dataFinal, legacyCode, pagina = 1, tamanhoPagina = LEGACY_PAGE_SIZE).resultado
                    .filterNot { isClosed(it.data_abertura_proposta, now) }
                    .mapNotNull(ComprasGovMapper::toOpportunity)
                    .forEach { collected.putIfAbsent(it.id, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ComprasGovException) {
                // complemento: a busca principal (14.133) já respondeu; o legado indisponível não é erro para o usuário
            }
        }

        // A API não filtra por texto/valor: aplica-se o filtro completo localmente (mesmas regras do app).
        return collected.values
            .filter { OpportunityFilterMatcher.matches(filter, it) }
            .sortedBy { it.proposalDeadline }
    }

    /**
     * A listagem é ascendente por publicação; para obter as contratações MAIS RECENTES, sonda-se o total
     * com uma página mínima e leem-se as últimas páginas (de trás para a frente) até [perCombo] itens.
     */
    private suspend fun collectNewest14133(
        dataInicial: String,
        dataFinal: String,
        code: Int,
        uf: String?,
        perCombo: Int,
        now: Long,
        into: MutableMap<String, Opportunity>,
    ) {
        val probe = api.contratacoes14133(dataInicial, dataFinal, code, uf, pagina = 1, tamanhoPagina = ComprasGovApi.MIN_PAGE_SIZE)
        val total = probe.totalRegistros
        if (total <= 0 || probe.resultado.isEmpty()) return
        var page = ((total + perCombo - 1) / perCombo).toInt().coerceAtLeast(1)
        var comboCollected = 0
        var pagesRead = 0
        while (page >= 1 && comboCollected < perCombo && pagesRead < MAX_PAGES_PER_COMBO && into.size < MAX_ITEMS_PER_SEARCH) {
            val result = api.contratacoes14133(dataInicial, dataFinal, code, uf, pagina = page, tamanhoPagina = perCombo)
            if (result.resultado.isEmpty()) break
            comboCollected += result.resultado.size
            result.resultado
                .filterNot { isClosed(it.dataEncerramentoPropostaPncp, now) }
                .mapNotNull(ComprasGovMapper::toOpportunity)
                .forEach { into.putIfAbsent(it.id, it) }
            pagesRead++
            page--
        }
    }

    /** Prazo conhecido e já vencido → não é mais oportunidade. Sem data, mantém-se. */
    private fun isClosed(deadlineText: String?, now: Long): Boolean {
        val deadline = ComprasGovMapper.parseDate(deadlineText) ?: return false
        return deadline < now
    }

    override suspend fun getTenderDetails(opportunityId: String): TenderDetails? {
        ComprasGovPncpRef.fromOpportunityId(opportunityId)?.let { return details14133(it) }
        ComprasGovLegacyRef.fromOpportunityId(opportunityId)?.let { return detailsLegado(it) }
        return null
    }

    private suspend fun details14133(ref: ComprasGovPncpRef): TenderDetails? {
        val dto = api.contratacao14133(ref.raw) ?: return null
        val base = ComprasGovMapper.toOpportunity(dto) ?: return null
        val items = try {
            api.itens14133(ref.raw).map(ComprasGovMapper::describeItem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ComprasGovException) {
            emptyList()
        }
        // O texto do edital não é lido aqui; a API de dados abertos não publica os arquivos da compra.
        return TenderDetails(opportunity = base, editalText = null, items = items)
    }

    private suspend fun detailsLegado(ref: ComprasGovLegacyRef): TenderDetails? {
        val dto = api.licitacaoLegado(ref.idCompra) ?: return null
        val base = ComprasGovMapper.toOpportunity(dto) ?: return null
        val rawItems = try {
            api.itensLegado(ref.idCompra)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ComprasGovException) {
            emptyList()
        }
        // O nome da UASG só aparece nos itens: usa-se para melhorar o nome do órgão quando disponível.
        val uasgName = rawItems.firstNotNullOfOrNull { it.nome_uasg?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val opportunity = if (uasgName != null) base.copy(agency = "$uasgName (${base.agency})") else base
        return TenderDetails(opportunity = opportunity, editalText = null, items = rawItems.map(ComprasGovMapper::describeItem))
    }

    // ------------------------------------------------------------ não suportado (consulta pública)

    override suspend fun authenticate(credentials: PortalCredentials): PortalAuthResult = PortalAuthResult.Failure(NOT_SUPPORTED)

    override suspend fun restoreSession(sessionKey: String): PortalAuthResult = PortalAuthResult.Failure(NOT_SUPPORTED)

    override suspend fun logout(sessionKey: String) { /* não há sessão: nada a encerrar */ }

    override suspend fun getMessages(liveSessionId: String): List<PortalMessage> = emptyList()

    override suspend fun prepareProposal(proposal: Proposal, tender: Tender): ProposalPreparation = ProposalPreparation(
        portal = portal,
        tenderNumber = tender.number,
        itemLabel = proposal.items.firstOrNull()?.description ?: "",
        companyId = proposal.companyId,
        totalValue = proposal.totalValue,
        warnings = listOf(NOT_SUPPORTED, "Envie a proposta manualmente no Compras.gov.br (área logada do fornecedor)."),
    )

    override suspend fun submitProposal(preparation: ProposalPreparation, confirmation: HumanConfirmation): SubmissionResult =
        SubmissionResult.Failure(NOT_SUPPORTED)

    override suspend fun openLiveSession(sessionId: String, spec: LiveSessionSpec): LiveSessionHandle =
        throw UnsupportedOperationException(NOT_SUPPORTED)

    override suspend fun readCurrentBidState(sessionId: String): PortalBidState? = null

    override suspend fun submitBid(sessionId: String, itemLabel: String, value: Double, confirmation: HumanConfirmation?): BidSubmission =
        BidSubmission.Rejected(NOT_SUPPORTED)

    override suspend fun pauseAutomation(sessionId: String) { /* não há automação */ }

    companion object {
        const val NOT_SUPPORTED =
            "Não suportado pelo Compras.gov.br (consulta pública): login, propostas, lances e mensagens são manuais no portal."

        /** Teto de itens por busca. */
        const val MAX_ITEMS_PER_SEARCH = 200

        /** Janela padrão de publicação consultada. */
        const val SEARCH_WINDOW_DAYS = 30L

        /** Acima disso, a UF não é enviada à API e o filtro é aplicado localmente. */
        const val MAX_UF_QUERIES = 3

        /** Páginas lidas (além da sonda) por combinação modalidade×UF. */
        const val MAX_PAGES_PER_COMBO = 4

        const val LEGACY_PAGE_SIZE = 50

        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}
