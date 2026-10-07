package com.licitaia.connector.pncp

import com.licitaia.domain.competition.CompetitorRanking
import com.licitaia.domain.competition.MarketQuery
import com.licitaia.domain.competition.PublicAwardResult
import com.licitaia.domain.competition.PublicResultsBatch
import com.licitaia.domain.competition.PublicResultsSource
import com.licitaia.domain.scoring.TextMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Resultados PÚBLICOS de contratações no PNCP (somente GET, sem login):
 * - itens: `GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens` (campo `temResultado`);
 * - resultados: `GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens/{numeroItem}/resultados`
 *   (fornecedor `nomeRazaoSocialFornecedor`/`niFornecedor`, `valorTotalHomologado`, `percentualDesconto`);
 * - mercado: `GET /api/consulta/v1/contratacoes/publicacao` (contratações publicadas com `valorTotalHomologado`).
 *
 * O PNCP devolve HTTP 429 com rajadas curtas (~5 requisições rápidas): todas as chamadas desta fonte passam por uma
 * fila única com espaçamento mínimo ([spacingMs]) e retentativas com `Retry-After`/espera crescente. Com 429
 * persistente o lote volta PARCIAL (o restante fica para a próxima atualização), nunca inventado.
 */
class PncpResultsSource internal constructor(
    private val api: PncpApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val spacingMs: Long = DEFAULT_SPACING_MS,
    private val retryDelaysMs: List<Long> = PncpConnector.DEFAULT_RETRY_DELAYS_MS,
) : PublicResultsSource {

    constructor(
        client: OkHttpClient,
        json: Json = PncpConnector.defaultJson(),
        baseUrl: HttpUrl = PncpApi.DEFAULT_BASE_URL.toHttpUrl(),
        clock: () -> Long = System::currentTimeMillis,
        spacingMs: Long = DEFAULT_SPACING_MS,
        retryDelaysMs: List<Long> = PncpConnector.DEFAULT_RETRY_DELAYS_MS,
    ) : this(PncpApi(client, json, baseUrl), clock, spacingMs, retryDelaysMs)

    private val gate = Mutex()
    private var lastRequestAt = 0L

    /** Sinaliza 429 persistente (após as retentativas): o chamador encerra o lote como parcial. */
    private class RateLimited(cause: PncpException) : Exception(cause.message, cause)

    override suspend fun awardResults(controlNumber: String, maxItems: Int): PublicResultsBatch {
        val ref = PncpControlNumber.parse(controlNumber) ?: return PublicResultsBatch(emptyList())
        return try {
            val (results, partial) = resultsFor(ref, header = null, maxItems = maxItems, ownTender = true)
            PublicResultsBatch(results, partial, checkedContracts = 1)
        } catch (e: RateLimited) {
            PublicResultsBatch(emptyList(), partial = true, checkedContracts = 0)
        }
    }

    override suspend fun recentAwardsLike(query: MarketQuery): PublicResultsBatch {
        val keywords = query.keywords.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (keywords.isEmpty()) return PublicResultsBatch(emptyList())
        val ufs = query.ufs.map { it.trim().uppercase() }.filter { it.length == 2 }.distinct()
        val now = clock()
        val dataInicial = PncpMapper.queryDate(now - query.windowDays.coerceIn(15, 365) * DAY_MS)
        // As mais recentes ainda não têm resultado: a janela termina alguns dias antes de hoje.
        val dataFinal = PncpMapper.queryDate(now - RECENT_GAP_DAYS * DAY_MS)

        val agency = CompetitorRanking.digits(query.agencyCnpj).takeIf { it.length == 14 }
        // Concorrentes de um órgão: pregões e dispensas DELE (filtro `cnpj` da API), sem filtro de UF.
        val modalities = if (agency != null) listOf(PncpModalities.PREGAO_ELETRONICO, PncpModalities.DISPENSA) else listOf(PncpModalities.PREGAO_ELETRONICO)
        val ufFilter = if (agency != null) emptyList() else ufs
        val matches = LinkedHashMap<String, PncpContratacao>()
        var partial = false
        try {
            for (modality in modalities) {
                var page = 1
                while (page <= MAX_MARKET_PAGES && matches.size < query.maxContracts) {
                    val result = call {
                        api.contratacoesPorPublicacao(
                            dataInicial = dataInicial, dataFinal = dataFinal, codigoModalidade = modality,
                            uf = ufFilter.singleOrNull(), pagina = page, tamanhoPagina = PncpApi.MAX_PAGE_SIZE, cnpjOrgao = agency,
                        )
                    }
                    result.data
                        .filter { agency == null || CompetitorRanking.digits(it.orgaoEntidade?.cnpj).ifEmpty { agency } == agency }
                        .filter { isMarketCandidate(it, keywords, ufFilter) }
                        .forEach { dto ->
                            val raw = dto.numeroControlePNCP ?: return@forEach
                            if (matches.size < query.maxContracts) matches.putIfAbsent(raw, dto)
                        }
                    if (result.data.isEmpty() || result.paginasRestantes <= 0) break
                    page++
                }
                if (matches.size >= query.maxContracts) break
            }
        } catch (e: RateLimited) {
            partial = true
        }

        val collected = mutableListOf<PublicAwardResult>()
        var checked = 0
        for ((raw, header) in matches) {
            if (partial) break
            val ref = PncpControlNumber.parse(raw) ?: continue
            try {
                val (results, cut) = resultsFor(ref, header, query.maxItemsPerContract, ownTender = false)
                collected += results
                checked++
                if (cut) partial = true
            } catch (e: RateLimited) {
                partial = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: PncpException) {
                // Uma contratação com falha (404, formato) não derruba as demais.
                if (collected.isEmpty() && e.kind == PncpException.Kind.OFFLINE) throw e
            }
        }
        return PublicResultsBatch(collected.distinctBy { it.key }, partial, checked)
    }

    /** Itens com resultado → resultados de cada item (até [maxItems]). `second` = parcial por 429. */
    private suspend fun resultsFor(
        ref: PncpControlNumber,
        header: PncpContratacao?,
        maxItems: Int,
        ownTender: Boolean,
    ): Pair<List<PublicAwardResult>, Boolean> {
        val items = try {
            call { api.itens(ref) }
        } catch (e: PncpException) {
            if (e.httpStatus == 404) return emptyList<PublicAwardResult>() to false
            throw e
        }
        // temResultado == null (campo ausente) → tenta; false → ainda sem resultado publicado.
        val withResult = items.filter { it.numeroItem != null && it.temResultado != false }.take(maxItems.coerceAtLeast(1))
        val out = mutableListOf<PublicAwardResult>()
        for (item in withResult) {
            val results = try {
                call { api.resultados(ref, item.numeroItem!!) }
            } catch (e: RateLimited) {
                return out to true
            } catch (e: PncpException) {
                if (e.httpStatus == 404) continue
                if (out.isEmpty()) throw e else return out to true
            }
            out += PncpResultsMapper.toAwards(ref, item, results, header, ownTender)
        }
        return out to false
    }

    private fun isMarketCandidate(dto: PncpContratacao, keywords: List<String>, ufs: List<String>): Boolean {
        if ((dto.valorTotalHomologado ?: 0.0) <= 0.0) return false
        val situacao = dto.situacaoCompraNome?.lowercase().orEmpty()
        if (CANCELLED.any { situacao.contains(it) }) return false
        if (ufs.isNotEmpty() && dto.unidadeOrgao?.ufSigla?.uppercase() !in ufs) return false
        val text = " " + TextMatch.normalize(dto.objetoCompra.orEmpty()) + " "
        return keywords.any { TextMatch.containsTerm(text, it) }
    }

    /** Fila única + espaçamento mínimo + retentativas em 429. */
    private suspend fun <T> call(block: suspend () -> T): T = gate.withLock {
        var attempt = 0
        while (true) {
            val wait = lastRequestAt + spacingMs - clock()
            if (wait > 0) delay(wait)
            try {
                return@withLock block().also { lastRequestAt = clock() }
            } catch (e: PncpException) {
                lastRequestAt = clock()
                if (e.httpStatus != 429) throw e
                if (attempt >= PncpConnector.MAX_RATE_LIMIT_RETRIES) throw RateLimited(e)
                val backoff = PncpConnector.rateLimitWaitMs(attempt, e.retryAfterMs, retryDelaysMs)
                attempt++
                if (backoff > 0) delay(backoff)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    companion object {
        /** Espaçamento mínimo entre requisições desta fonte (o PNCP corta rajadas de ~5 requisições rápidas). */
        const val DEFAULT_SPACING_MS = 1_200L
        /** Páginas de 50 contratações lidas na busca de mercado. */
        const val MAX_MARKET_PAGES = 3
        /** Dias recentes ignorados na busca de mercado (ainda sem resultado). */
        const val RECENT_GAP_DAYS = 7L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val CANCELLED = listOf("revogad", "anulad", "suspens", "cancelad")
    }
}

/** Conversão dos DTOs de resultado → domínio. Pura e testável. */
internal object PncpResultsMapper {

    fun toAwards(
        ref: PncpControlNumber,
        item: PncpItem,
        results: List<PncpItemResultado>,
        header: PncpContratacao?,
        ownTender: Boolean,
    ): List<PublicAwardResult> = results
        // Resultado cancelado (dataCancelamento/motivo) não é vitória de ninguém.
        .filter { it.dataCancelamento.isNullOrBlank() && !(it.situacaoCompraItemResultadoNome?.contains("cancel", ignoreCase = true) ?: false) }
        .mapNotNull { r ->
            val name = r.nomeRazaoSocialFornecedor?.trim().orEmpty()
            val value = r.valorTotalHomologado
                ?: r.valorUnitarioHomologado?.let { unit -> unit * (r.quantidadeHomologada ?: item.quantidade ?: 1.0) }
                ?: return@mapNotNull null
            if (name.isEmpty() || value <= 0.0) return@mapNotNull null
            PublicAwardResult(
                controlNumber = ref.raw,
                itemNumber = r.numeroItem ?: item.numeroItem ?: 0,
                itemDescription = item.descricao?.trim().orEmpty(),
                agency = header?.orgaoEntidade?.razaoSocial?.trim().orEmpty(),
                objectSummary = header?.objetoCompra?.trim().orEmpty(),
                uf = header?.unidadeOrgao?.ufSigla?.trim()?.uppercase().orEmpty(),
                supplierName = name,
                supplierDocument = CompetitorRanking.digits(r.niFornecedor),
                supplierSize = r.porteFornecedorNome?.trim()?.takeIf { it.isNotEmpty() },
                estimatedValue = if (item.orcamentoSigiloso == true) 0.0 else (item.valorTotal ?: 0.0),
                homologatedValue = value,
                publishedDiscountPct = r.percentualDesconto,
                resultDate = PncpMapper.parseDate(r.dataResultado) ?: PncpMapper.parseDate(r.dataInclusao) ?: 0L,
                ownTender = ownTender,
            )
        }
}
