package com.licitaia.connector.comprasgov

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.LiveSessionHandle
import com.licitaia.connector.api.OpportunityScreen
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalBidState
import com.licitaia.connector.api.PortalConnector
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.PortalMessage
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.ScreenedListing
import com.licitaia.connector.api.ScreenedOpportunitySource
import com.licitaia.connector.api.SubmissionResult
import com.licitaia.connector.api.TenderDetails
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.SourceDiagnostics
import com.licitaia.domain.model.Tender
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Conector REAL do Compras.gov.br — somente consulta pública via API de Dados Abertos, sem chave/login.
 *
 * Fonte: https://dadosabertos.compras.gov.br (OpenAPI em /v3/api-docs). A API expõe as contratações da
 * Lei 14.133 publicadas no PNCP pelo Compras.gov.br (módulo contratações) e as licitações da Lei 8.666
 * (módulo legado). Nenhuma ação autenticada (proposta, lance, mensagem) existe nessa API: os métodos
 * correspondentes devolvem falha explícita e nunca simulam sucesso.
 *
 * Busca (medido em 06/10/2026: 60 dias = ~16 mil pregões + ~19,5 mil dispensas + ~1,6 mil concorrências):
 * 1. lê TODAS as páginas (500 linhas) da janela de publicação para cada modalidade, em sequência, com pausa curta e
 *    backoff em 429/5xx, até o teto de segurança [Tuning.maxRows]; acima do teto, as páginas MAIS RECENTES têm
 *    prioridade (a listagem é ascendente por publicação). Cache em memória por (modalidade, UF, janela) por
 *    [Tuning.rowsCacheTtlMs], para as atualizações automáticas não repetirem a leitura;
 * 2. triagem local ANTES de qualquer enriquecimento: filtro da busca/radar ([OpportunityFilterMatcher] + o
 *    [OpportunityScreen] do repositório: palavras do radar, UF, valor, modalidade e heurística de relevância);
 * 3. o Compras.gov.br deixa `dataEncerramentoPropostaPncp` vazio em boa parte das contratações: só para as
 *    candidatas sem prazo, consulta-se o detalhe no PNCP pelo número de controle (até [Tuning.maxEnrichPerRun] por
 *    execução, paralelismo [Tuning.enrichParallelism], cache de [Tuning.enrichCacheTtlMs]). Revogadas, anuladas,
 *    suspensas e com prazo vencido são descartadas; sem resposta do PNCP, a candidata fica com "prazo não informado".
 *
 * O módulo legado (Lei 8.666) não tem registros em 2026 e só é consultado quando o filtro fixa Pregão ou Concorrência.
 */
class ComprasGovConnector internal constructor(
    private val api: ComprasGovApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tuning: Tuning = Tuning(),
) : PortalConnector, ScreenedOpportunitySource {

    constructor(
        client: OkHttpClient,
        json: Json = defaultJson(),
        baseUrl: HttpUrl = ComprasGovApi.DEFAULT_BASE_URL.toHttpUrl(),
        clock: () -> Long = System::currentTimeMillis,
        pncpBaseUrl: HttpUrl = ComprasGovApi.PNCP_BASE_URL.toHttpUrl(),
        tuning: Tuning = Tuning(),
    ) : this(ComprasGovApi(client, json, baseUrl, pncpBaseUrl), clock, tuning)

    /** Parâmetros da leitura completa e do enriquecimento (testes reduzem pausas e tetos). */
    data class Tuning(
        /** Janela de publicação consultada. */
        val windowDays: Long = DEFAULT_WINDOW_DAYS,
        /** Teto de segurança de linhas lidas da API por execução (somando as modalidades). */
        val maxRows: Int = DEFAULT_MAX_ROWS,
        val pageSize: Int = ComprasGovApi.MAX_PAGE_SIZE,
        /** Pausa entre páginas (leitura sequencial). */
        val pageDelayMs: Long = 250L,
        /** Esperas antes de cada retentativa após 429/5xx/timeout (sem `Retry-After`). */
        val retryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 10_000L),
        /** Validade do cache de linhas por (modalidade, UF, janela). */
        val rowsCacheTtlMs: Long = 10L * 60 * 1000,
        /** Máximo de consultas de prazo ao PNCP por execução. */
        val maxEnrichPerRun: Int = 60,
        val enrichParallelism: Int = 2,
        /** Intervalo mínimo entre consultas de prazo ao PNCP (limite real ≈ 1 a cada 3 s após 5 em rajada). */
        val enrichMinIntervalMs: Long = 3_000L,
        /** Tempo máximo gasto consultando prazos por execução; o restante fica para as próximas atualizações. */
        val enrichBudgetMs: Long = 30_000L,
        /** Validade do cache de prazo/situação por número de controle. */
        val enrichCacheTtlMs: Long = 24L * 60 * 60 * 1000,
    )

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
            "A busca lê todas as contratações da Lei 14.133 publicadas nos últimos ${tuning.windowDays} dias (até " +
                "${tuning.maxRows} linhas por atualização, as mais recentes primeiro; cache de 10 min); os dados abertos " +
                "têm defasagem de alguns dias em relação ao PNCP.",
            "Modalidades representadas: Pregão Eletrônico, Dispensa e Concorrência Eletrônica. " +
                "Credenciamento não tem código de consulta nesta API; inexigibilidade e presenciais não são listadas.",
            "Licitações da Lei 8.666 (módulo legado) só são consultadas para Pregão/Concorrência e não trazem UF, município nem nome do órgão (apenas UASG).",
            "A API não oferece busca por texto: palavras-chave, valores e relevância são filtrados no aparelho sobre as linhas lidas.",
            "O Compras.gov.br muitas vezes não informa o fim das propostas: para as candidatas, o prazo e a situação são " +
                "conferidos no PNCP (até ${tuning.maxEnrichPerRun} por atualização); sem resposta, aparece \"prazo não informado\".",
            "Segmento é inferido por palavras do objeto (heurística); valor estimado 0 indica orçamento sigiloso ou não informado.",
            "A data da sessão de disputa não é publicada; o app usa o fim do recebimento de propostas.",
            "O payload não traz URL da compra no Compras.gov.br: o link aponta para a página pública do PNCP do mesmo número de controle.",
        ),
    )

    // ------------------------------------------------------------ consulta pública

    /** Linhas já lidas de uma combinação (modalidade, UF, janela): oportunidades ainda vivas + total de linhas. */
    private class RowsSnapshot(val fetchedAt: Long, val rows: Int, val opportunities: List<Opportunity>, val truncated: Boolean)

    private data class Combo(val code: Int, val uf: String?)

    private class StatusEntry(val fetchedAt: Long, val status: PncpCompraStatus)

    /** Consultas de prazo ao PNCP na última execução (diagnóstico). */
    data class EnrichmentStats(val fromCache: Int, val requested: Int, val answered: Int, val failed: Int, val throttled: Boolean)

    @Volatile
    var lastEnrichment: EnrichmentStats? = null
        private set

    private val rowsCache = ConcurrentHashMap<String, RowsSnapshot>()
    private val statusCache = ConcurrentHashMap<String, StatusEntry>()

    /** Uma leitura completa por vez: radares simultâneos esperam e reaproveitam o cache em vez de repetir a leitura. */
    private val readLock = Mutex()

    override suspend fun listOpportunities(filter: OpportunityFilter): List<Opportunity> =
        listScreened(filter, OpportunityScreen.ACCEPT_ALL).opportunities

    override suspend fun listScreened(filter: OpportunityFilter, screen: OpportunityScreen): ScreenedListing {
        if (filter.portals.isNotEmpty() && Portal.COMPRAS_GOV !in filter.portals) return ScreenedListing(emptyList(), SourceDiagnostics(0, 0, 0))

        val now = clock()
        val dataFinal = ComprasGovMapper.queryDate(now)
        val dataInicial = ComprasGovMapper.queryDate(now - tuning.windowDays * DAY_MS)
        val codes: List<Int> = filter.modality?.let { m -> listOfNotNull(ComprasGovModalities.codeOf(m)) } ?: ComprasGovModalities.SEARCHED_CODES
        val ufs: List<String?> = filter.ufs.map { it.trim().uppercase() }.filter { it.length == 2 }.distinct()
            .takeIf { it.isNotEmpty() && it.size <= MAX_UF_QUERIES } ?: listOf(null)
        val combos = ufs.flatMap { uf -> codes.map { Combo(it, uf) } }

        // 1) Leitura completa da janela (cache de 10 min).
        val snapshots = if (combos.isEmpty()) emptyList() else readWindow(combos, dataInicial, dataFinal, now)
        val pool = LinkedHashMap<String, Opportunity>()
        snapshots.forEach { s -> s.opportunities.forEach { pool.putIfAbsent(it.id, it) } }
        var rowsRead = snapshots.sumOf { it.rows }

        // Legado (Lei 8.666): complemento opcional, só para modalidades compatíveis; falha nele não derruba a busca.
        val legacyCode = filter.modality?.let(ComprasGovModalities::legacyCodeOf)
        if (legacyCode != null) {
            try {
                val legacy = api.licitacoesLegado(dataInicial, dataFinal, legacyCode, pagina = 1, tamanhoPagina = LEGACY_PAGE_SIZE).resultado
                rowsRead += legacy.size
                legacy.filterNot { isClosed(it.data_abertura_proposta, now) }
                    .mapNotNull(ComprasGovMapper::toOpportunity)
                    .forEach { pool.putIfAbsent(it.id, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ComprasGovException) {
                // complemento: a busca principal (14.133) já respondeu; o legado indisponível não é erro para o usuário
            }
        }

        // 2) Triagem local antes do enriquecimento (a API não filtra por texto/valor).
        val candidates = pool.values.filter {
            !it.isProposalClosed(now) && OpportunityFilterMatcher.matches(filter, it) && screen.accept(it)
        }

        // 3) Prazo/situação reais no PNCP só para as candidatas sem encerramento.
        val open = enrich(candidates, now)
        return ScreenedListing(
            opportunities = open.sortedBy { it.proposalDeadline },
            diagnostics = SourceDiagnostics(
                read = rowsRead,
                candidates = candidates.size,
                open = open.size,
                unknownDeadline = open.count { !it.hasProposalDeadline },
                truncated = snapshots.any { it.truncated },
            ),
        )
    }

    // ------------------------------------------------------------ 1) leitura completa

    private fun cacheKey(c: Combo, dataInicial: String, dataFinal: String) = "${c.code}|${c.uf.orEmpty()}|$dataInicial|$dataFinal"

    /** Estado da leitura de uma combinação durante a execução. */
    private class Reading(val combo: Combo) {
        var total: Long = 0
        var totalPages: Long = 0
        var remainingAfterFirst: Long = 0
        var rows: Int = 0
        val opportunities = LinkedHashMap<String, Opportunity>()
        var truncated = false
        var complete = false
    }

    /**
     * Lê todas as páginas de cada combinação (sequencial). Fase A: primeira página de cada uma (dá o total).
     * Fase B: as combinações menores primeiro, cada uma com uma fatia justa do teto restante; cabendo, lê para a frente
     * até `paginasRestantes = 0`; não cabendo, lê de trás para a frente (mais recentes) até a fatia.
     * 429/5xx persistente com linhas já lidas: devolve o parcial (sem guardar no cache); sem nada lido, propaga.
     */
    private suspend fun readWindow(combos: List<Combo>, dataInicial: String, dataFinal: String, now: Long): List<RowsSnapshot> =
        readLock.withLock {
            val result = LinkedHashMap<Combo, RowsSnapshot>()
            val pending = mutableListOf<Combo>()
            for (c in combos) {
                val cached = rowsCache[cacheKey(c, dataInicial, dataFinal)]
                if (cached != null && now - cached.fetchedAt in 0 until tuning.rowsCacheTtlMs) result[c] = cached else pending += c
            }
            if (pending.isEmpty()) return@withLock combos.mapNotNull { result[it] }

            var requests = 0
            var failure: ComprasGovException? = null
            suspend fun page(c: Combo, pagina: Int): ComprasGovPage<ComprasGovContratacao> {
                currentCoroutineContext().ensureActive()
                if (requests > 0 && tuning.pageDelayMs > 0) delay(tuning.pageDelayMs)
                requests++
                return withRetry { api.contratacoes14133(dataInicial, dataFinal, c.code, c.uf, pagina, tuning.pageSize) }
            }

            val readings = pending.map { Reading(it) }
            var budget = tuning.maxRows
            // Fase A: primeira página de cada combinação.
            for (r in readings) {
                if (failure != null) break
                try {
                    val first = page(r.combo, 1)
                    r.total = first.totalRegistros
                    r.totalPages = first.totalPaginas
                    r.remainingAfterFirst = first.paginasRestantes
                    absorb(r, first.resultado, now)
                    budget -= first.resultado.size
                    r.complete = first.resultado.isEmpty() || first.paginasRestantes <= 0
                } catch (e: ComprasGovException) {
                    failure = e
                }
            }
            // Fase B: as menores primeiro, cada uma com fatia justa do teto restante.
            val ordered = readings.filter { !it.complete && it.rows > 0 }.sortedBy { it.total }
            for ((i, r) in ordered.withIndex()) {
                if (failure != null) break
                val share = (budget / (ordered.size - i)).coerceAtLeast(0)
                val before = r.rows
                try {
                    if (r.total - r.rows <= share) {
                        // Cabe: para a frente até paginasRestantes = 0.
                        var pagina = 2
                        while (true) {
                            if (r.rows - before >= share) { r.truncated = true; break }
                            val p = page(r.combo, pagina)
                            absorb(r, p.resultado, now)
                            if (p.resultado.isEmpty() || p.paginasRestantes <= 0) { r.complete = true; break }
                            if (pagina >= r.totalPages + SAFETY_EXTRA_PAGES) { r.complete = true; break }
                            pagina++
                        }
                    } else {
                        // Não cabe: de trás para a frente (publicações mais recentes primeiro).
                        r.truncated = true
                        var pagina = r.totalPages.toInt()
                        while (pagina >= 2 && r.rows - before < share) {
                            val p = page(r.combo, pagina)
                            absorb(r, p.resultado, now)
                            pagina--
                        }
                    }
                } catch (e: ComprasGovException) {
                    failure = e
                }
                budget -= r.rows - before
            }

            val anyRows = readings.any { it.rows > 0 } || result.isNotEmpty()
            failure?.let { if (!anyRows) throw it }
            for (r in readings) {
                val snapshot = RowsSnapshot(now, r.rows, r.opportunities.values.toList(), r.truncated)
                result[r.combo] = snapshot
                // Só leituras sem falha vão para o cache (o parcial de um 429 não deve durar 10 min).
                if (failure == null) rowsCache[cacheKey(r.combo, dataInicial, dataFinal)] = snapshot
            }
            pruneRowsCache(now)
            combos.mapNotNull { result[it] }
        }

    /** Conta as linhas e guarda só o que ainda pode ser oportunidade (sem excluídas/revogadas/anuladas/suspensas/encerradas). */
    private fun absorb(r: Reading, rows: List<ComprasGovContratacao>, now: Long) {
        r.rows += rows.size
        for (dto in rows) {
            if (isCancelledSituation(dto.situacaoCompraNomePncp)) continue
            if (isClosed(dto.dataEncerramentoPropostaPncp, now)) continue
            val o = ComprasGovMapper.toOpportunity(dto) ?: continue
            r.opportunities.putIfAbsent(o.id, o)
        }
    }

    private fun pruneRowsCache(now: Long) {
        rowsCache.entries.removeIf { now - it.value.fetchedAt >= tuning.rowsCacheTtlMs }
    }

    /** 429/5xx/timeout: espera `Retry-After` (limitado) ou a tabela de backoff e tenta de novo; o resto sobe direto. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ComprasGovException) {
                val retryable = e.httpStatus == 429 || (e.httpStatus ?: 0) in 500..599 || e.kind == ComprasGovException.Kind.TIMEOUT
                if (!retryable || attempt >= tuning.retryDelaysMs.size) throw e
                val wait = e.retryAfterMs?.coerceIn(0, MAX_RETRY_AFTER_MS) ?: tuning.retryDelaysMs[attempt]
                attempt++
                if (wait > 0) delay(wait)
            }
        }
    }

    // ------------------------------------------------------------ 3) enriquecimento pelo PNCP

    /**
     * Candidatas com prazo conhecido passam direto. As sem prazo recebem prazo/situação do PNCP (cache de 24 h;
     * até [Tuning.maxEnrichPerRun] consultas novas, publicações mais recentes primeiro e as "sem disputa" por último).
     * Descarta revogadas/anuladas/suspensas e prazo vencido; sem resposta, mantém com "prazo não informado".
     *
     * Limite real do PNCP (medido em 06/10/2026): ~5 requisições seguidas e depois HTTP 429 com `Retry-After: 15`;
     * sustentado ≈ 1 a cada 3 s, e insistir durante o 429 prolonga o bloqueio. Por isso as consultas têm intervalo
     * mínimo global ([Tuning.enrichMinIntervalMs]), orçamento de tempo por execução ([Tuning.enrichBudgetMs]) e param no
     * 429 persistente; o que faltar é consultado nas próximas atualizações (o cache guarda o que já foi respondido).
     */
    private suspend fun enrich(candidates: List<Opportunity>, now: Long): List<Opportunity> {
        val missing = candidates.filter { !it.hasProposalDeadline && ComprasGovPncpRef.fromOpportunityId(it.id) != null }
        if (missing.isEmpty()) {
            lastEnrichment = EnrichmentStats(0, 0, 0, 0, false)
            return candidates
        }
        statusCache.entries.removeIf { now - it.value.fetchedAt >= tuning.enrichCacheTtlMs }

        val statuses = HashMap<String, PncpCompraStatus>()
        val toFetch = mutableListOf<Pair<Opportunity, ComprasGovPncpRef>>()
        val priority = compareBy<Opportunity> { o -> o.noDispute || o.keywords.any { it.equals(NO_DISPUTE, ignoreCase = true) } }
            .thenByDescending { it.publishedAt }
        for (o in missing.sortedWith(priority)) {
            val ref = ComprasGovPncpRef.fromOpportunityId(o.id) ?: continue
            val cached = statusCache[ref.raw]
            if (cached != null) statuses[o.id] = cached.status else if (toFetch.size < tuning.maxEnrichPerRun) toFetch += o to ref
        }
        val fromCache = statuses.size
        var answered = 0
        val throttled = AtomicBoolean(false)
        if (toFetch.isNotEmpty()) {
            val budgetEnd = monotonicMs() + tuning.enrichBudgetMs
            val semaphore = Semaphore(tuning.enrichParallelism.coerceAtLeast(1))
            val fetched = coroutineScope {
                toFetch.map { (o, ref) ->
                    async {
                        semaphore.withPermit {
                            fetchStatus(ref, budgetEnd, throttled)?.also { statusCache[ref.raw] = StatusEntry(now, it) }?.let { o.id to it }
                        }
                    }
                }.awaitAll()
            }
            fetched.filterNotNull().forEach { (id, s) -> statuses[id] = s }
            answered = fetched.count { it != null }
        }
        lastEnrichment = EnrichmentStats(fromCache, toFetch.size, answered, toFetch.size - answered, throttled.get())
        return candidates.mapNotNull { o ->
            val s = statuses[o.id] ?: return@mapNotNull o
            if (isDropped(s, now)) null else applyStatus(o, s)
        }
    }

    private val pncpGate = Mutex()
    private var lastPncpStart = Long.MIN_VALUE / 2

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

    /** Intervalo mínimo entre o início de duas consultas ao PNCP (vale para as duas vias paralelas). */
    private suspend fun pacePncp() = pncpGate.withLock {
        val wait = lastPncpStart + tuning.enrichMinIntervalMs - monotonicMs()
        if (wait > 0) delay(wait)
        lastPncpStart = monotonicMs()
    }

    /** null = sem resposta (orçamento esgotado, 404, falha ou 429 persistente). */
    private suspend fun fetchStatus(ref: ComprasGovPncpRef, budgetEnd: Long, throttled: AtomicBoolean): PncpCompraStatus? {
        var attempt = 0
        while (true) {
            if (throttled.get() || monotonicMs() >= budgetEnd) return null
            pacePncp()
            if (throttled.get() || monotonicMs() >= budgetEnd) return null
            try {
                return api.pncpCompra(ref)
            } catch (e: ComprasGovException) {
                val status = e.httpStatus
                val retryable = status == 429 || (status ?: 0) in 500..599 || e.kind == ComprasGovException.Kind.TIMEOUT
                if (!retryable) return null
                if (attempt >= tuning.retryDelaysMs.size) {
                    if (status == 429) throttled.set(true)
                    return null
                }
                val wait = e.retryAfterMs?.coerceIn(0, MAX_RETRY_AFTER_MS) ?: tuning.retryDelaysMs[attempt]
                attempt++
                if (monotonicMs() + wait >= budgetEnd) {
                    if (status == 429) throttled.set(true)
                    return null
                }
                if (wait > 0) delay(wait)
            }
        }
    }

    private fun isDropped(s: PncpCompraStatus, now: Long): Boolean {
        if (s.situacaoCompraId in CANCELLED_SITUATION_IDS || isCancelledSituation(s.situacaoCompraNome)) return true
        val deadline = ComprasGovMapper.parseDate(s.dataEncerramentoProposta) ?: return false
        return deadline < now
    }

    /** Aplica o prazo real do PNCP (já sabido que não foi descartada por [isDropped]). */
    private fun applyStatus(o: Opportunity, s: PncpCompraStatus): Opportunity {
        val deadline = ComprasGovMapper.parseDate(s.dataEncerramentoProposta) ?: return o
        // Com prazo publicado no PNCP há recebimento de propostas: deixa de ser "sem disputa".
        return o.copy(proposalDeadline = deadline, sessionAt = deadline, noDispute = false)
    }

    private fun isCancelledSituation(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return CANCELLED_SITUATION_WORDS.any { n.contains(it) }
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

        /** Janela padrão de publicação consultada. */
        const val DEFAULT_WINDOW_DAYS = 60L

        /** Teto de segurança de linhas lidas por execução (60 dias ≈ 37 mil linhas em 06/10/2026). */
        const val DEFAULT_MAX_ROWS = 20_000

        /** Acima disso, a UF não é enviada à API e o filtro é aplicado localmente. */
        const val MAX_UF_QUERIES = 3

        const val LEGACY_PAGE_SIZE = 50

        /** Teto para `Retry-After` (a busca não fica presa esperando minutos). */
        const val MAX_RETRY_AFTER_MS = 15_000L

        /** Páginas além de `totalPaginas` toleradas na leitura para a frente (registros novos durante a leitura). */
        private const val SAFETY_EXTRA_PAGES = 2

        /** `situacaoCompraId` do PNCP: 2 Revogada, 3 Anulada, 4 Suspensa (1 = Divulgada no PNCP). */
        private val CANCELLED_SITUATION_IDS = setOf(2, 3, 4)
        private val CANCELLED_SITUATION_WORDS = listOf("revogad", "anulad", "suspens", "cancelad", "desert", "fracassad")

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Modo de disputa das contratações diretas sem disputa (Compras.gov.br e PNCP não publicam prazo nelas). */
        private const val NO_DISPUTE = "Não se aplica"

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}
