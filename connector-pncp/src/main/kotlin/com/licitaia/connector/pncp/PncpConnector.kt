package com.licitaia.connector.pncp

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
 * Conector REAL do PNCP — somente consulta pública, sem login.
 *
 * Fonte dos dados: API de consulta do PNCP (https://pncp.gov.br/api/consulta/v3/api-docs) e os GETs
 * públicos de documentos/itens da API de integração (https://pncp.gov.br/api/pncp/v3/api-docs).
 * Nenhuma ação autenticada (proposta, lance, mensagem) existe nessa API: os métodos correspondentes
 * devolvem falha explícita e nunca simulam sucesso.
 */
class PncpConnector internal constructor(
    private val api: PncpApi,
    private val clock: () -> Long = System::currentTimeMillis,
) : PortalConnector {

    constructor(
        client: OkHttpClient,
        json: Json = defaultJson(),
        baseUrl: HttpUrl = PncpApi.DEFAULT_BASE_URL.toHttpUrl(),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(PncpApi(client, json, baseUrl), clock)

    override val portal: Portal = Portal.PNCP

    /** O PNCP agrega publicações de Compras.gov.br, Licitanet, BLL, PCP e outras plataformas (ver [PncpPlatforms]). */
    override val searchablePortals: Set<Portal> = PncpPlatforms.COVERED_PORTALS

    override val capabilities: ConnectorCapabilities = ConnectorCapabilities(
        supportsWebView = true,
        supportsOfficialApi = true,
        supportsBrowserAutomation = false,
        supportsPersistentSession = false,
        requiresMfa = false,
        mayShowCaptcha = false,
        isMock = false,
        limitations = listOf(
            "Somente consulta pública (API de consulta do PNCP, sem chave ou login); sem lances, propostas ou mensagens.",
            "A busca lista contratações com recebimento de propostas ABERTO na data da consulta, limitada a $MAX_ITEMS_PER_SEARCH resultados por busca.",
            "Modalidades representadas: Pregão Eletrônico, Dispensa, Concorrência (eletrônica/presencial) e Credenciamento. " +
                "Pregão presencial, inexigibilidade, leilão e outras não são listadas.",
            "A API não oferece busca por texto: palavras-chave e valores são filtrados no aparelho sobre os resultados obtidos.",
            "A plataforma de origem (Compras.gov.br, Licitanet, BLL, Portal de Compras Públicas ou outra) é identificada pelo " +
                "campo usuarioNome; o filtro por portal é aplicado no aparelho sobre os resultados obtidos.",
            "Segmento é inferido por palavras do objeto (heurística); valor estimado 0 indica orçamento sigiloso ou não informado.",
            "A data da sessão de disputa não é publicada pelo PNCP; o app usa o fim do recebimento de propostas.",
            "Os documentos (edital) são links públicos do PNCP; o conteúdo do PDF não é lido aqui.",
        ),
    )

    // ------------------------------------------------------------ consulta pública

    override suspend fun listOpportunities(filter: OpportunityFilter): List<Opportunity> {
        // O filtro de portal é aplicado localmente sobre a plataforma classificada (usuarioNome).
        if (filter.portals.isNotEmpty() && filter.portals.none { it in searchablePortals }) return emptyList()

        val today = PncpMapper.queryDate(clock())
        val codes = filter.modality?.let { listOf(PncpModalities.codeOf(it)) } ?: PncpModalities.SEARCHED_CODES
        val ufs: List<String?> = filter.ufs.map { it.trim().uppercase() }.filter { it.length == 2 }.distinct()
            .takeIf { it.isNotEmpty() && it.size <= MAX_UF_QUERIES } ?: listOf(null)

        val combos = codes.size * ufs.size
        val pagesPerCombo = (MAX_ITEMS_PER_SEARCH / (PncpApi.MAX_PAGE_SIZE * combos)).coerceAtLeast(1)

        val collected = LinkedHashMap<String, Opportunity>()
        for (uf in ufs) {
            for (code in codes) {
                var page = 1
                while (page <= pagesPerCombo && collected.size < MAX_ITEMS_PER_SEARCH) {
                    val result = api.contratacoesComPropostaAberta(
                        dataFinal = today, codigoModalidade = code, uf = uf, pagina = page, tamanhoPagina = PncpApi.MAX_PAGE_SIZE,
                    )
                    result.data.mapNotNull(PncpMapper::toOpportunity).forEach { collected.putIfAbsent(it.id, it) }
                    if (result.data.isEmpty() || result.paginasRestantes <= 0) break
                    page++
                }
            }
        }
        // A API não filtra por texto/valor: aplica-se o filtro completo localmente (mesmas regras do app).
        return collected.values
            .filter { OpportunityFilterMatcher.matches(filter, it) }
            .sortedBy { it.proposalDeadline }
    }

    override suspend fun getTenderDetails(opportunityId: String): TenderDetails? {
        val ref = PncpControlNumber.fromOpportunityId(opportunityId) ?: return null
        val dto = api.contratacao(ref) ?: return null
        val base = PncpMapper.toOpportunity(dto) ?: return null

        // Documentos e itens são complementos: falha neles não derruba o detalhe principal.
        val documents = try {
            api.documentos(ref).filter { it.statusAtivo != false }
        } catch (e: CancellationException) {
            throw e
        } catch (_: PncpException) {
            emptyList()
        }
        val items = try {
            api.itens(ref).map(PncpMapper::describeItem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: PncpException) {
            emptyList()
        }

        val edital = documents.firstOrNull { it.tipoDocumentoNome.equals("Edital", ignoreCase = true) }
            ?: documents.firstOrNull { it.titulo?.contains("edital", ignoreCase = true) == true }
        val editalUrl = (edital?.url ?: edital?.uri)?.takeIf { it.startsWith("https://", ignoreCase = true) }
        val keywords = base.keywords + documents.mapNotNull { d -> d.titulo?.let { "documento: $it" } }

        return TenderDetails(
            opportunity = base.copy(editalUrl = editalUrl ?: base.editalUrl, keywords = keywords.distinct()),
            // O texto do edital não é lido aqui: a importação do PDF é feita por outro fluxo.
            editalText = null,
            items = items,
        )
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
        warnings = listOf(NOT_SUPPORTED, "Envie a proposta no sistema de origem indicado pelo PNCP (linkSistemaOrigem) ou no portal do órgão."),
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
        const val NOT_SUPPORTED = "Não suportado pelo PNCP (consulta pública): login, propostas, lances e mensagens não existem nesta API."

        /** Teto de itens por busca (≈ 4 páginas de 50). */
        const val MAX_ITEMS_PER_SEARCH = 200

        /** Acima disso, a UF não é enviada à API e o filtro é aplicado localmente. */
        const val MAX_UF_QUERIES = 3

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}
