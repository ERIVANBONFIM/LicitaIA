package com.licitaia.connector.pncp

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.LiveSessionHandle
import com.licitaia.connector.api.OfficialDocument
import com.licitaia.connector.api.OfficialDocumentSource
import com.licitaia.connector.api.OfficialItemsSource
import com.licitaia.domain.proposal.OfficialTenderItem
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalBidState
import com.licitaia.connector.api.PortalConnector
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.PortalMessage
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.SubmissionResult
import com.licitaia.connector.api.TenderDetails
import com.licitaia.connector.api.WithdrawnIds
import com.licitaia.connector.api.WithdrawnListingSource
import com.licitaia.connector.api.WithdrawnSituation
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    /** Pausa entre páginas (o PNCP devolve 429 com rajadas de ~20 requisições em poucos segundos). */
    private val pageDelayMs: Long = DEFAULT_PAGE_DELAY_MS,
    /** Esperas entre retentativas após HTTP 429 sem `Retry-After` (testes usam 0). */
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) : PortalConnector, OfficialDocumentSource, OfficialItemsSource, WithdrawnListingSource, com.licitaia.connector.api.OfficialStatusSource {

    constructor(
        client: OkHttpClient,
        json: Json = defaultJson(),
        baseUrl: HttpUrl = PncpApi.DEFAULT_BASE_URL.toHttpUrl(),
        clock: () -> Long = System::currentTimeMillis,
        pageDelayMs: Long = DEFAULT_PAGE_DELAY_MS,
        retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    ) : this(PncpApi(client, json, baseUrl), clock, pageDelayMs, retryDelaysMs)

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
            "A busca lista contratações com recebimento de propostas ABERTO e encerramento nos próximos $PROPOSAL_HORIZON_DAYS dias, " +
                "limitada a $MAX_ITEMS_PER_SEARCH resultados por busca ($MAX_ITEMS_PLATFORM_SEARCH quando o filtro é por plataforma).",
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

        // `dataFinal` do endpoint /contratacoes/proposta é o LIMITE do encerramento das propostas: com "hoje" a API só
        // devolve o que encerra hoje (06/10/2026: 21 pregões no país inteiro). Usa-se um horizonte de N dias.
        val dataFinal = PncpMapper.queryDate(clock() + PROPOSAL_HORIZON_DAYS * DAY_MS)
        val codes = filter.modality?.let { listOf(PncpModalities.codeOf(it)) } ?: PncpModalities.SEARCHED_CODES
        val ufs: List<String?> = filter.ufs.map { it.trim().uppercase() }.filter { it.length == 2 }.distinct()
            .takeIf { it.isNotEmpty() && it.size <= MAX_UF_QUERIES } ?: listOf(null)

        // Filtro por plataforma (ex.: só Compras.gov.br): o portal é classificado localmente, então lê mais páginas.
        val maxItems = maxItemsFor(filter)
        val combos = codes.size * ufs.size
        val pagesPerCombo = (maxItems / (PncpApi.MAX_PAGE_SIZE * combos)).coerceAtLeast(1)

        // Mesma consulta (dia, modalidades, UFs, teto) nos últimos 10 min: reaproveita a listagem sem ir à rede.
        val now = clock()
        val key = "$dataFinal|$codes|$ufs|$maxItems"
        val previous = listingCache[key]
        val cached = previous?.takeIf { now - it.fetchedAt in 0 until LISTING_CACHE_TTL_MS }
        val listed = cached?.opportunities ?: fetchListing(dataFinal, codes, ufs, maxItems, pagesPerCombo).let { (list, complete) ->
            if (complete) {
                listingCache.entries.removeIf { now - it.value.fetchedAt !in 0 until LISTING_STALE_MS }
                listingCache[key] = CachedListing(now, list)
                list
            } else {
                // O parcial de um 429 não vai para o cache (a próxima atualização tenta de novo), mas é completado com a
                // última listagem COMPLETA da mesma consulta (até [LISTING_STALE_MS]), sem as que já encerraram: a busca
                // não "perde" de repente a maior parte das abertas porque o limite de consultas do PNCP apertou.
                val stale = previous?.takeIf { now - it.fetchedAt in 0 until LISTING_STALE_MS }?.opportunities.orEmpty()
                mergePartial(list, stale, now)
            }
        }
        // A API não filtra por texto/valor: aplica-se o filtro completo localmente (mesmas regras do app), fora do Main.
        return withContext(Dispatchers.Default) {
            listed.filter { OpportunityFilterMatcher.matches(filter, it) }.sortedBy { it.proposalDeadline }
        }
    }

    private fun isCancelled(situacao: String?): Boolean = WithdrawnSituation.isWithdrawn(null, situacao)

    private val withdrawn = WithdrawnIds()

    override fun drainWithdrawnIds(): Set<String> = withdrawn.drain()

    private class CachedListing(val fetchedAt: Long, val opportunities: List<Opportunity>)

    private val listingCache = java.util.concurrent.ConcurrentHashMap<String, CachedListing>()

    /** Lê as páginas; `second = false` = parcial após 429 persistente. */
    private suspend fun fetchListing(
        dataFinal: String,
        codes: List<Int?>,
        ufs: List<String?>,
        maxItems: Int,
        pagesPerCombo: Int,
    ): Pair<List<Opportunity>, Boolean> {
        val collected = LinkedHashMap<String, Opportunity>()
        var requests = 0
        var partial = false
        outer@ for (uf in ufs) {
            for (code in codes) {
                var page = 1
                while (page <= pagesPerCombo && collected.size < maxItems) {
                    if (requests > 0 && pageDelayMs > 0) delay(pageDelayMs) // respeita o limite de requisições do PNCP
                    val result = try {
                        withRateLimitRetry {
                            api.contratacoesComPropostaAberta(
                                dataFinal = dataFinal, codigoModalidade = code, uf = uf, pagina = page, tamanhoPagina = PncpApi.MAX_PAGE_SIZE,
                            )
                        }
                    } catch (e: PncpException) {
                        // 429 persistente (após as retentativas) com resultados já obtidos: devolve o parcial
                        // (o backoff do app cuida das próximas buscas).
                        if (e.httpStatus == 429 && collected.isNotEmpty()) { partial = true; break@outer }
                        throw e
                    }
                    requests++
                    // Revogadas/anuladas/suspensas/desertas/fracassadas nunca entram na listagem; ficam registradas para a
                    // limpeza do cache local de oportunidades.
                    val (cancelled, live) = result.data.partition { isCancelled(it.situacaoCompraNome) }
                    cancelled.forEach { dto -> PncpMapper.toOpportunity(dto)?.let { withdrawn.record(it.id) } }
                    live.mapNotNull(PncpMapper::toOpportunity).forEach { collected.putIfAbsent(it.id, it) }
                    if (result.data.isEmpty() || result.paginasRestantes <= 0) break
                    page++
                }
            }
        }
        return collected.values.toList() to !partial
    }

    /**
     * HTTP 429 do PNCP: espera `Retry-After` (limitado) ou 2–5 s e tenta de novo, até [MAX_RATE_LIMIT_RETRIES] vezes,
     * em vez de desistir na primeira rajada. Outras falhas sobem direto.
     */
    private suspend fun <T> withRateLimitRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: PncpException) {
                if (e.httpStatus != 429 || attempt >= MAX_RATE_LIMIT_RETRIES) throw e
                val wait = rateLimitWaitMs(attempt, e.retryAfterMs, retryDelaysMs)
                attempt++
                if (wait > 0) delay(wait)
            }
        }
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

    /**
     * Edital oficial + anexos relevantes de uma contratação (`/arquivos` da API de integração do PNCP),
     * escolhidos por [PncpEditalSelector]. Vale para ids PNCP e Compras.gov.br (mesmo número de controle).
     */
    override suspend fun officialEditalDocuments(pncpControlNumber: String): List<OfficialDocument> {
        val ref = PncpControlNumber.parse(pncpControlNumber)
            ?: throw IllegalArgumentException("Número de controle PNCP inválido: $pncpControlNumber")
        val documents = withRateLimitRetry { api.documentos(ref) }
        return PncpEditalSelector.select(documents)
    }

    /**
     * Itens oficiais da contratação (`/itens?pagina=N&tamanhoPagina=500` da API de integração), com retentativa em 429
     * e pausa entre páginas. Itens cancelados/desertos/fracassados ficam de fora (não recebem proposta).
     */
    override suspend fun officialItems(pncpControlNumber: String): List<OfficialTenderItem> {
        val ref = PncpControlNumber.parse(pncpControlNumber)
            ?: throw IllegalArgumentException("Número de controle PNCP inválido: $pncpControlNumber")
        val all = mutableListOf<PncpItem>()
        for (page in 1..MAX_ITEM_PAGES) {
            if (page > 1 && pageDelayMs > 0) delay(pageDelayMs)
            val chunk = withRateLimitRetry { api.itens(ref, page, PncpApi.MAX_ITEMS_PAGE_SIZE) }
            all += chunk
            if (chunk.size < PncpApi.MAX_ITEMS_PAGE_SIZE) break
        }
        return all.mapNotNull(PncpMapper::toOfficialItem).distinctBy { it.number }
    }

    /** Órgão e unidade compradora (`/api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{seq}`: `orgaoEntidade` + `unidadeOrgao`). */
    override suspend fun officialBuyer(pncpControlNumber: String): com.licitaia.domain.proposal.OfficialBuyer? {
        val ref = PncpControlNumber.parse(pncpControlNumber) ?: return null
        val compra = withRateLimitRetry { api.contratacao(ref) } ?: return null
        return PncpMapper.toBuyer(compra)
    }

    /** Situação oficial e datas (`/api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{seq}`), para a conferência diária. */
    override suspend fun officialStatus(pncpControlNumber: String): com.licitaia.domain.model.OfficialStatus? {
        val ref = PncpControlNumber.parse(pncpControlNumber) ?: return null
        val compra = withRateLimitRetry { api.contratacao(ref) } ?: return null
        return PncpMapper.toOfficialStatus(compra)
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
        const val DEFAULT_PAGE_DELAY_MS = 350L

        /** Validade da listagem em memória para a mesma consulta (atualizações de 2/5/15 min não relêem tudo). */
        const val LISTING_CACHE_TTL_MS = 10L * 60 * 1000

        /** Até quando a última listagem completa serve para completar uma listagem parcial (429). */
        const val LISTING_STALE_MS = 6L * 60 * 60 * 1000

        /** Parcial (429) + última listagem completa da mesma consulta, sem duplicatas e sem as que já encerraram. */
        internal fun mergePartial(partial: List<Opportunity>, lastComplete: List<Opportunity>, now: Long): List<Opportunity> {
            if (lastComplete.isEmpty()) return partial
            val merged = LinkedHashMap<String, Opportunity>()
            partial.forEach { merged[it.id] = it }
            lastComplete.filterNot { it.isProposalClosed(now) }.forEach { merged.putIfAbsent(it.id, it) }
            return merged.values.toList()
        }

        /** Teto de páginas de itens (500 por página). */
        const val MAX_ITEM_PAGES = 4

        /** Retentativas após HTTP 429 (além da requisição original). */
        const val MAX_RATE_LIMIT_RETRIES = 3

        /** Esperas padrão (2 s, 3,5 s, 5 s) quando o PNCP não manda `Retry-After`. */
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(2_000L, 3_500L, 5_000L)

        /** Teto para `Retry-After` (a busca não fica presa esperando minutos). */
        const val MAX_RETRY_AFTER_MS = 10_000L

        /**
         * Espera antes da retentativa [attempt] (0 = primeira): `Retry-After` limitado a [MAX_RETRY_AFTER_MS]; sem ele,
         * a tabela [delays] (último valor repetido). Pura e testável.
         */
        fun rateLimitWaitMs(attempt: Int, retryAfterMs: Long?, delays: List<Long> = DEFAULT_RETRY_DELAYS_MS): Long {
            retryAfterMs?.takeIf { it >= 0 }?.let { return it.coerceAtMost(MAX_RETRY_AFTER_MS) }
            if (delays.isEmpty()) return 0L
            return delays[attempt.coerceIn(0, delays.size - 1)]
        }

        const val NOT_SUPPORTED = "Não suportado pelo PNCP (consulta pública): login, propostas, lances e mensagens não existem nesta API."

        /** Teto de itens por busca (≈ 4 páginas de 50). */
        const val MAX_ITEMS_PER_SEARCH = 200

        /**
         * Teto quando o filtro pede só plataformas específicas (Compras.gov.br, Licitanet, BLL, PCP): 2 páginas por
         * modalidade (8 requisições). Medido em 06/10/2026: o PNCP devolve 429 a partir de ~11 requisições seguidas.
         */
        const val MAX_ITEMS_PLATFORM_SEARCH = 400

        /** Horizonte do encerramento das propostas consultado (`dataFinal` = hoje + N dias). */
        const val PROPOSAL_HORIZON_DAYS = 60L

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** true quando o filtro pede só plataformas classificadas (sem o "PNCP/outras"). */
        fun isPlatformFocused(filter: OpportunityFilter): Boolean =
            filter.portals.isNotEmpty() && Portal.PNCP !in filter.portals

        fun maxItemsFor(filter: OpportunityFilter): Int =
            if (isPlatformFocused(filter)) MAX_ITEMS_PLATFORM_SEARCH else MAX_ITEMS_PER_SEARCH

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
