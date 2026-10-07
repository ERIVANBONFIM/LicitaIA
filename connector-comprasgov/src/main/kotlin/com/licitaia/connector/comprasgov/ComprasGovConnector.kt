package com.licitaia.connector.comprasgov

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.InMemoryListingRowStore
import com.licitaia.connector.api.ListingRowQuery
import com.licitaia.connector.api.ListingRowStore
import com.licitaia.connector.api.ListingSyncMark
import com.licitaia.connector.api.ListingSyncProgress
import com.licitaia.connector.api.ListingSyncProgressSource
import com.licitaia.connector.api.LiveSessionHandle
import com.licitaia.connector.api.PncpTrafficGate
import com.licitaia.connector.api.OfficialItemsSource
import com.licitaia.domain.proposal.OfficialTenderItem
import com.licitaia.connector.api.OpportunityScreen
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalBidState
import com.licitaia.connector.api.PortalConnector
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.PortalMessage
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.ScreenedListing
import com.licitaia.connector.api.ScreenedOpportunitySource
import com.licitaia.connector.api.SourceSyncPolicy
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
import com.licitaia.domain.model.SourceDiagnostics
import com.licitaia.domain.model.Tender
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
 * 1. sincroniza um cache PERSISTENTE ([ListingRowStore], Room) por partição (modalidade + UF): a varredura completa
 *    (TODAS as páginas de 500 linhas da janela, sequencial, pausa curta e backoff em 429/5xx, até o teto
 *    [Tuning.maxRows], mais recentes primeiro) acontece na atualização diária ([SourceSyncPolicy.DAILY_FULL], 05:30),
 *    com o cache vazio ou se a última tem [Tuning.fullSyncIntervalMs] (30 h) ou mais; fora isso, só as publicações desde a última sincronização (incremental, poucas páginas). O que sai
 *    da janela ou encerra é removido do cache. O Worker ([SourceSyncPolicy.INCREMENTAL_ONLY]) nunca faz a completa
 *    e, antes da primeira completa, não sincroniza nada; em primeiro plano a varredura roda desacoplada da tela (não é
 *    interrompida ao sair/atualizar) e, passado [Tuning.foregroundWaitMs], a busca devolve o parcial "sincronizando";
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
    /** Cache persistente das linhas (Room em produção; memória nos testes). */
    private val store: ListingRowStore = InMemoryListingRowStore(),
) : PortalConnector, ScreenedOpportunitySource, ListingSyncProgressSource, WithdrawnListingSource, OfficialItemsSource {

    constructor(
        client: OkHttpClient,
        json: Json = defaultJson(),
        baseUrl: HttpUrl = ComprasGovApi.DEFAULT_BASE_URL.toHttpUrl(),
        clock: () -> Long = System::currentTimeMillis,
        pncpBaseUrl: HttpUrl = ComprasGovApi.PNCP_BASE_URL.toHttpUrl(),
        tuning: Tuning = Tuning(),
        store: ListingRowStore = InMemoryListingRowStore(),
    ) : this(ComprasGovApi(client, json, baseUrl, pncpBaseUrl), clock, tuning, store)

    /** Parâmetros da sincronização e do enriquecimento (testes reduzem pausas e tetos). */
    data class Tuning(
        /** Janela de publicação consultada. */
        val windowDays: Long = DEFAULT_WINDOW_DAYS,
        /** Teto de segurança de linhas lidas da API por sincronização (somando as modalidades). */
        val maxRows: Int = DEFAULT_MAX_ROWS,
        val pageSize: Int = ComprasGovApi.MAX_PAGE_SIZE,
        /** Pausa entre páginas (leitura sequencial). */
        val pageDelayMs: Long = 250L,
        /** Esperas antes de cada retentativa após 429/5xx/timeout (sem `Retry-After`). */
        val retryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 10_000L),
        /** Validade do cache em memória do módulo legado (Lei 8.666). */
        val rowsCacheTtlMs: Long = 10L * 60 * 1000,
        /** Intervalo mínimo entre varreduras completas da janela por partição (modalidade + UF). */
        val fullSyncIntervalMs: Long = DEFAULT_FULL_SYNC_INTERVAL_MS,
        /** Intervalo mínimo entre leituras incrementais da mesma partição (abaixo disso, só o cache). */
        val incrementalMinIntervalMs: Long = 90_000L,
        /** Dias a mais relidos no incremental (os dados abertos chegam com alguns dias de defasagem). */
        val incrementalOverlapDays: Long = 1L,
        /** Linhas lidas do cache persistente por bloco. */
        val storeBlockSize: Int = 1_000,
        /** Máximo de consultas de prazo ao PNCP por execução. */
        val maxEnrichPerRun: Int = 60,
        val enrichParallelism: Int = 2,
        /** Intervalo mínimo entre consultas de prazo ao PNCP (limite real ≈ 1 a cada 3 s após 5 em rajada). */
        val enrichMinIntervalMs: Long = 3_000L,
        /** Tempo máximo gasto consultando prazos por execução; o restante fica para as próximas atualizações. */
        val enrichBudgetMs: Long = 30_000L,
        /** Validade do cache de prazo/situação por número de controle. */
        val enrichCacheTtlMs: Long = 24L * 60 * 60 * 1000,
        /**
         * Quanto a busca em primeiro plano espera pela sincronização (que roda desacoplada de quem pediu) antes de
         * devolver o resultado PARCIAL do cache com "sincronização em andamento"; a varredura continua e a tela refaz a
         * lista quando ela termina.
         */
        val foregroundWaitMs: Long = 15_000L,
        /** Espera máxima pela listagem do PNCP da mesma busca antes de consultar prazos no PNCP ([PncpTrafficGate]). */
        val pncpGateWaitMs: Long = 60_000L,
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
                "${tuning.maxRows} linhas por leitura completa, as mais recentes primeiro), guardadas no aparelho: a leitura completa " +
                "se repete uma vez por dia (atualização automática, padrão 05:30) e, entre elas, só as publicações novas são baixadas; os dados abertos " +
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

    private data class Combo(val code: Int, val uf: String?)

    private class StatusEntry(val fetchedAt: Long, val status: PncpCompraStatus)

    private class LegacyEntry(val key: String, val fetchedAt: Long, val opportunities: List<Opportunity>)

    /** Consultas de prazo ao PNCP na última execução (diagnóstico). */
    data class EnrichmentStats(val fromCache: Int, val requested: Int, val answered: Int, val failed: Int, val throttled: Boolean)

    /** O que a última sincronização baixou (diagnóstico/testes): linhas da API e quantas leituras completas/incrementais. */
    data class SyncStats(val downloaded: Int, val fullSyncs: Int, val incrementalSyncs: Int)

    @Volatile
    var lastEnrichment: EnrichmentStats? = null
        private set

    @Volatile
    var lastSync: SyncStats? = null
        private set

    private val statusCache = ConcurrentHashMap<String, StatusEntry>()

    @Volatile
    private var legacyCache: LegacyEntry? = null

    /** Uma sincronização por vez: radares simultâneos esperam e reaproveitam o cache persistente em vez de repetir a leitura. */
    private val readLock = Mutex()

    /**
     * A sincronização em primeiro plano roda neste escopo, DESACOPLADA de quem pediu: sair da tela, recompor, o
     * `mapLatest` do painel ou uma nova atualização cancelam só a espera, nunca a varredura completa no meio (que
     * deixaria o cache com as primeiras páginas — as publicações mais antigas, quase todas encerradas — e sem marca).
     */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobLock = Mutex()

    @Volatile
    private var running: Deferred<SyncOutcome>? = null

    private val progress = MutableStateFlow<ListingSyncProgress?>(null)

    /** Contratações vistas como retiradas (canceladas/revogadas/...) para a limpeza do cache de oportunidades. */
    private val withdrawn = WithdrawnIds()

    override fun drainWithdrawnIds(): Set<String> = withdrawn.drain()

    /** Andamento da varredura completa (linhas baixadas); null sem varredura em andamento. */
    override val syncProgress: StateFlow<ListingSyncProgress?> = progress.asStateFlow()

    override suspend fun listOpportunities(filter: OpportunityFilter): List<Opportunity> =
        listScreened(filter, OpportunityScreen.ACCEPT_ALL).opportunities

    /**
     * 1) sincroniza o cache persistente ([store]) — varredura completa no máximo a cada [Tuning.fullSyncIntervalMs] (ou
     *    cache vazio); fora isso, só as publicações desde a última sincronização (incremental, poucas páginas);
     * 2) lê do cache, em blocos, só a modalidade/UF/janela pedidas e aplica palavras/triagem ([Dispatchers.Default]);
     * 3) enriquece o prazo no PNCP só das candidatas sem encerramento.
     * Falha da API com linhas no cache: devolve o cache (a próxima atualização tenta de novo); cache vazio: propaga.
     */
    override suspend fun listScreened(filter: OpportunityFilter, screen: OpportunityScreen): ScreenedListing {
        if (filter.portals.isNotEmpty() && Portal.COMPRAS_GOV !in filter.portals) return ScreenedListing(emptyList(), SourceDiagnostics(0, 0, 0))

        val now = clock()
        val windowStart = now - tuning.windowDays * DAY_MS
        val dataFinal = ComprasGovMapper.queryDate(now)
        val dataInicial = ComprasGovMapper.queryDate(windowStart)
        val codes: List<Int> = filter.modality?.let { m -> listOfNotNull(ComprasGovModalities.codeOf(m)) } ?: ComprasGovModalities.SEARCHED_CODES
        val ufFilter: Set<String> = filter.ufs.map { it.trim().uppercase() }.filter { it.length == 2 }.toSet()
        val ufs: List<String?> = ufFilter.toList().sorted().takeIf { it.isNotEmpty() && it.size <= MAX_UF_QUERIES } ?: listOf(null)
        val combos = ufs.flatMap { uf -> codes.map { Combo(it, uf) } }
        val policy = currentCoroutineContext()[SourceSyncPolicy]
        val incrementalOnly = policy?.incrementalOnly == true
        val forceFull = policy?.forceFull == true

        // 1) Sincronização do cache persistente (completa na atualização diária ou cache velho; senão incremental).
        val sync = if (combos.isEmpty()) SyncOutcome(null) else sync(combos, dataInicial, dataFinal, windowStart, now, incrementalOnly, forceFull)

        // Legado (Lei 8.666): complemento opcional, só para modalidades compatíveis; falha nele não derruba a busca.
        val legacy = filter.modality?.let(ComprasGovModalities::legacyCodeOf)?.let { legacyOpen(it, dataInicial, dataFinal, now) }.orEmpty()

        // 2) Leitura do cache por modalidade/UF/janela (SQL) e triagem local fora da main thread, em blocos.
        val (scanned, candidates) = withContext(Dispatchers.Default) {
            var count = 0
            val pool = LinkedHashMap<String, Opportunity>()
            fun consider(o: Opportunity) {
                if (!o.isProposalClosed(now) && OpportunityFilterMatcher.matches(filter, o) && screen.accept(o)) pool.putIfAbsent(o.id, o)
            }
            for (code in codes) {
                val query = ListingRowQuery(code, ufFilter, windowStart, now)
                var after = ""
                while (true) {
                    ensureActive()
                    val block = store.page(query, after, tuning.storeBlockSize)
                    if (block.isEmpty()) break
                    count += block.size
                    block.forEach(::consider)
                    after = block.last().id
                    if (block.size < tuning.storeBlockSize) break
                }
            }
            count += legacy.size
            legacy.forEach(::consider)
            count to pool.values.toList()
        }
        // Cache vazio e a fonte falhou: erro (a tela mostra a causa). Com a varredura ainda em andamento: lista vazia
        // marcada "sincronizando" (não é um vazio definitivo). Com linhas no cache: parcial, com a falha no diagnóstico.
        sync.failure?.let { if (scanned == 0 && !sync.inProgress) throw it }

        // 3) Prazo/situação reais no PNCP só para as candidatas sem encerramento.
        val open = enrich(candidates, now)
        return ScreenedListing(
            opportunities = withContext(Dispatchers.Default) { open.sortedBy { it.proposalDeadline } },
            diagnostics = SourceDiagnostics(
                read = scanned,
                candidates = candidates.size,
                open = open.size,
                unknownDeadline = open.count { !it.hasProposalDeadline },
                truncated = combos.isNotEmpty() && truncated(combos, now),
                syncing = sync.inProgress,
                syncFailure = sync.fullFailure?.message,
            ),
        )
    }

    /** Legado (1 página de [LEGACY_PAGE_SIZE]) com cache em memória de [Tuning.rowsCacheTtlMs]. */
    private suspend fun legacyOpen(legacyCode: Int, dataInicial: String, dataFinal: String, now: Long): List<Opportunity> {
        val key = "$legacyCode|$dataInicial|$dataFinal"
        legacyCache?.takeIf { it.key == key && now - it.fetchedAt in 0 until tuning.rowsCacheTtlMs }?.let { return it.opportunities }
        return try {
            val rows = api.licitacoesLegado(dataInicial, dataFinal, legacyCode, pagina = 1, tamanhoPagina = LEGACY_PAGE_SIZE).resultado
            rows.filterNot { isClosed(it.data_abertura_proposta, now) }
                .mapNotNull(ComprasGovMapper::toOpportunity)
                .also { legacyCache = LegacyEntry(key, now, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ComprasGovException) {
            // complemento: a busca principal (14.133) já respondeu; o legado indisponível não é erro para o usuário
            emptyList()
        }
    }

    // ------------------------------------------------------------ 1) sincronização (completa / incremental)

    /**
     * [failure] = falha da API nesta sincronização; [inProgress] = a varredura (completa) continua em segundo plano e o
     * cache ainda é parcial; [fullFailure] = a varredura completa NECESSÁRIA falhou (o cache é parcial).
     */
    private class SyncOutcome(
        val failure: ComprasGovException?,
        val inProgress: Boolean = false,
        val fullFailure: ComprasGovException? = null,
    )

    /**
     * Worker ([SourceSyncPolicy.INCREMENTAL_ONLY]): sincroniza na própria coroutine (curta, cancelável) e nunca espera
     * nem dispara a varredura completa; com outra sincronização em andamento, só lê o cache.
     * Primeiro plano: a sincronização roda em [syncScope] (desacoplada do chamador) — junta-se à que estiver em
     * andamento em vez de começar outra — e o chamador espera até [Tuning.foregroundWaitMs]; passado isso, devolve o
     * cache parcial com [SyncOutcome.inProgress] e a varredura segue até o fim (marca gravada só quando termina).
     * Atualização diária ([forceFull]): mesma via desacoplada, mas espera a varredura terminar (o Worker tem o próprio
     * tempo máximo).
     */
    private suspend fun sync(
        combos: List<Combo>,
        dataInicial: String,
        dataFinal: String,
        windowStart: Long,
        now: Long,
        incrementalOnly: Boolean,
        forceFull: Boolean = false,
    ): SyncOutcome {
        if (incrementalOnly) {
            if (running?.isActive == true) return SyncOutcome(null, inProgress = true)
            return readLock.withLock { synchronize(combos, dataInicial, dataFinal, windowStart, now, incrementalOnly = true) }
        }
        val deadline = if (forceFull) Long.MAX_VALUE / 2 else monotonicMs() + tuning.foregroundWaitMs
        while (true) {
            val (job, mine) = jobLock.withLock {
                val current = running
                if (current != null && current.isActive) {
                    current to false
                } else {
                    syncScope.async {
                        readLock.withLock { synchronize(combos, dataInicial, dataFinal, windowStart, now, incrementalOnly = false, forceFull = forceFull) }
                    }.also { running = it } to true
                }
            }
            val remaining = (deadline - monotonicMs()).coerceAtLeast(1L)
            // Cancelar a espera (tela fechada, nova atualização) não cancela [job].
            val outcome = withTimeoutOrNull(remaining) { job.await() } ?: return SyncOutcome(null, inProgress = true)
            if (mine) return outcome
            // Era a sincronização de outra busca (outras partições): agora planeja a desta.
        }
    }

    /** Pausa entre requisições sequenciais e contagem do que foi baixado na sincronização. */
    private inner class Pacer(private val full: Boolean) {
        var requests = 0
        var downloaded = 0

        suspend fun page(c: Combo, dataInicial: String, dataFinal: String, pagina: Int): ComprasGovPage<ComprasGovContratacao> {
            currentCoroutineContext().ensureActive()
            if (requests > 0 && tuning.pageDelayMs > 0) delay(tuning.pageDelayMs)
            requests++
            return withRetry { api.contratacoes14133(dataInicial, dataFinal, c.code, c.uf, pagina, tuning.pageSize) }
                .also {
                    downloaded += it.resultado.size
                    if (full) progress.value = ListingSyncProgress(downloaded, full = true)
                }
        }
    }

    /**
     * Partição efetiva de cada combinação: a partição por UF reaproveita a nacional da mesma modalidade quando esta
     * teve varredura completa há menos de [Tuning.fullSyncIntervalMs].
     */
    private suspend fun resolveTargets(combos: List<Combo>, now: Long): LinkedHashMap<Combo, ListingSyncMark?> {
        val targets = LinkedHashMap<Combo, ListingSyncMark?>()
        for (c in combos) {
            val national = if (c.uf != null) store.syncMark(c.code, "") else null
            if (national != null && national.lastFullSyncAt > 0 && now - national.lastFullSyncAt in 0 until tuning.fullSyncIntervalMs) {
                targets[Combo(c.code, null)] = national
            } else {
                targets[c] = store.syncMark(c.code, c.uf.orEmpty())
            }
        }
        return targets
    }

    private suspend fun truncated(combos: List<Combo>, now: Long): Boolean =
        resolveTargets(combos, now).keys.any { c -> store.syncMark(c.code, c.uf.orEmpty())?.truncated == true }

    /** Decide e executa a sincronização de cada partição (modalidade + UF); o chamador segura [readLock]. */
    private suspend fun synchronize(
        combos: List<Combo>,
        dataInicial: String,
        dataFinal: String,
        windowStart: Long,
        now: Long,
        incrementalOnly: Boolean,
        forceFull: Boolean = false,
    ): SyncOutcome {
        val targets = resolveTargets(combos, now)
        val full = mutableListOf<Combo>()
        val incremental = mutableListOf<Pair<Combo, ListingSyncMark?>>()
        for ((c, mark) in targets) {
            when (SyncPlanner.decide(mark, now, incrementalOnly, tuning.fullSyncIntervalMs, tuning.incrementalMinIntervalMs, forceFull)) {
                SyncPlanner.Action.FULL -> full += c
                SyncPlanner.Action.INCREMENTAL -> incremental += c to mark
                SyncPlanner.Action.NONE -> Unit
            }
        }
        val pacer = Pacer(full = full.isNotEmpty())
        var failure: ComprasGovException? = null
        var fullFailure: ComprasGovException? = null
        if (full.isNotEmpty()) progress.value = ListingSyncProgress(0, full = true)
        try {
            if (full.isNotEmpty()) {
                failure = readWindow(full, dataInicial, dataFinal, now, pacer)
                fullFailure = failure
            }
            for ((c, mark) in incremental) {
                if (failure != null) break
                try {
                    readIncremental(c, mark, windowStart, dataFinal, now, pacer)
                } catch (e: ComprasGovException) {
                    failure = e
                }
            }
            if (pacer.requests > 0) runCatching { store.prune(windowStart, now) }
        } finally {
            if (full.isNotEmpty()) progress.value = null
        }
        lastSync = SyncStats(pacer.downloaded, full.size, incremental.size)
        return SyncOutcome(failure, fullFailure = fullFailure)
    }

    /**
     * Incremental: só `dataPublicacaoPncpInicial = dia da última sincronização` (menos [Tuning.incrementalOverlapDays],
     * pela defasagem dos dados abertos) até hoje, todas as páginas (poucas). Só acontece depois de uma varredura
     * completa ([SyncPlanner]); sem marca, desde ontem (salvaguarda).
     */
    private suspend fun readIncremental(c: Combo, mark: ListingSyncMark?, windowStart: Long, dataFinal: String, now: Long, pacer: Pacer) {
        val since = SyncPlanner.incrementalStart(mark, now, tuning.incrementalOverlapDays, windowStart)
        val dataInicial = ComprasGovMapper.queryDate(since)
        var pagina = 1
        var rows = 0
        while (true) {
            val p = pacer.page(c, dataInicial, dataFinal, pagina)
            rows += p.resultado.size
            persist(c, p.resultado, now)
            if (p.resultado.isEmpty() || p.paginasRestantes <= 0 || rows >= tuning.maxRows) break
            pagina++
        }
        store.saveSyncMark(ListingSyncMark(c.code, c.uf.orEmpty(), mark?.lastFullSyncAt ?: 0L, now, mark?.truncated ?: false))
    }

    /** Estado da leitura de uma combinação durante a varredura completa. */
    private class Reading(val combo: Combo) {
        var total: Long = 0
        var totalPages: Long = 0
        var rows: Int = 0
        var truncated = false
        var complete = false
        /** Terminou sem falha (fica com marca de varredura completa). */
        var ok = false
    }

    /**
     * Varredura completa (sequencial) gravando cada página no cache persistente. Fase A: primeira página de cada
     * combinação (dá o total). Fase B: as menores primeiro, cada uma com uma fatia justa do teto [Tuning.maxRows];
     * cabendo, lê para a frente até `paginasRestantes = 0`; não cabendo, de trás para a frente (mais recentes).
     * Só as combinações lidas sem falha recebem a marca de varredura completa; devolve a falha (se houve).
     */
    private suspend fun readWindow(combos: List<Combo>, dataInicial: String, dataFinal: String, now: Long, pacer: Pacer): ComprasGovException? {
        var failure: ComprasGovException? = null
        val readings = combos.map { Reading(it) }
        var budget = tuning.maxRows
        // Fase A: primeira página de cada combinação.
        for (r in readings) {
            if (failure != null) break
            try {
                val first = pacer.page(r.combo, dataInicial, dataFinal, 1)
                r.total = first.totalRegistros
                r.totalPages = first.totalPaginas
                r.rows += first.resultado.size
                persist(r.combo, first.resultado, now)
                budget -= first.resultado.size
                r.complete = first.resultado.isEmpty() || first.paginasRestantes <= 0
                r.ok = r.complete
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
                        val p = pacer.page(r.combo, dataInicial, dataFinal, pagina)
                        r.rows += p.resultado.size
                        persist(r.combo, p.resultado, now)
                        if (p.resultado.isEmpty() || p.paginasRestantes <= 0) { r.complete = true; break }
                        if (pagina >= r.totalPages + SAFETY_EXTRA_PAGES) { r.complete = true; break }
                        pagina++
                    }
                } else {
                    // Não cabe: de trás para a frente (publicações mais recentes primeiro).
                    r.truncated = true
                    var pagina = r.totalPages.toInt()
                    while (pagina >= 2 && r.rows - before < share) {
                        val p = pacer.page(r.combo, dataInicial, dataFinal, pagina)
                        r.rows += p.resultado.size
                        persist(r.combo, p.resultado, now)
                        pagina--
                    }
                }
                r.ok = true
            } catch (e: ComprasGovException) {
                failure = e
            }
            budget -= r.rows - before
        }
        for (r in readings) {
            if (r.ok) store.saveSyncMark(ListingSyncMark(r.combo.code, r.combo.uf.orEmpty(), now, now, r.truncated))
        }
        return failure
    }

    /**
     * Grava uma página no cache: o que ainda pode ser oportunidade entra/atualiza; excluídas, revogadas, anuladas,
     * suspensas e encerradas saem (se estavam lá).
     */
    private suspend fun persist(c: Combo, rows: List<ComprasGovContratacao>, now: Long) {
        if (rows.isEmpty()) return
        val live = ArrayList<Opportunity>(rows.size)
        val dropped = ArrayList<String>()
        for (dto in rows) {
            val cancelled = WithdrawnSituation.isWithdrawn(null, dto.situacaoCompraNomePncp)
            val o = if (cancelled || isClosed(dto.dataEncerramentoPropostaPncp, now)) null
            else ComprasGovMapper.toOpportunity(dto)
            if (o != null) live += o else rowId(dto)?.let { id ->
                dropped += id
                // Retirada: também sai do cache de oportunidades na limpeza diária.
                if (cancelled) withdrawn.record(id)
            }
        }
        if (live.isNotEmpty()) store.upsert(c.code, live, now)
        if (dropped.isNotEmpty()) store.delete(dropped)
    }

    private fun rowId(dto: ComprasGovContratacao): String? =
        ComprasGovPncpRef.parse(dto.numeroControlePNCP)?.opportunityId ?: ComprasGovLegacyRef.fromIdCompra(dto.idCompra)?.opportunityId

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
            // Mesma busca lendo a listagem do PNCP (mesmo host e limite de consultas): espera ela terminar antes, para
            // que as duas não disputem o limite e a listagem do PNCP não volte parcial (429).
            currentCoroutineContext()[PncpTrafficGate]?.listingDone?.let { gate ->
                withTimeoutOrNull(tuning.pncpGateWaitMs) { gate.await() }
            }
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
            if (WithdrawnSituation.isWithdrawn(s.situacaoCompraId, s.situacaoCompraNome)) withdrawn.record(o.id)
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
        if (WithdrawnSituation.isWithdrawn(s.situacaoCompraId, s.situacaoCompraNome)) return true
        val deadline = ComprasGovMapper.parseDate(s.dataEncerramentoProposta) ?: return false
        return deadline < now
    }

    /** Aplica o prazo real do PNCP (já sabido que não foi descartada por [isDropped]). */
    private fun applyStatus(o: Opportunity, s: PncpCompraStatus): Opportunity {
        val deadline = ComprasGovMapper.parseDate(s.dataEncerramentoProposta) ?: return o
        // Com prazo publicado no PNCP há recebimento de propostas: deixa de ser "sem disputa".
        return o.copy(
            proposalDeadline = deadline, sessionAt = deadline, noDispute = false,
            proposalOpening = ComprasGovMapper.parseDate(s.dataAberturaProposta) ?: o.proposalOpening,
        )
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

    /** Itens oficiais (dados abertos `2.1_consultarItensContratacoes_PNCP_14133_Id`): fallback quando o PNCP falha. */
    override suspend fun officialItems(pncpControlNumber: String): List<OfficialTenderItem> {
        val ref = ComprasGovPncpRef.parse(pncpControlNumber)
            ?: throw IllegalArgumentException("Número de controle PNCP inválido: $pncpControlNumber")
        return api.itens14133(ref.raw).mapNotNull(ComprasGovMapper::toOfficialItem).distinctBy { it.number }
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

        /** Teto de segurança de linhas lidas por sincronização (60 dias ≈ 37 mil linhas em 06/10/2026). */
        const val DEFAULT_MAX_ROWS = 20_000

        /**
         * Em primeiro plano, a varredura completa só acontece se a última tem 30 h ou mais (a atualização diária das
         * 05:30 não rodou); fora isso, abrir/atualizar a tela só baixa o incremental.
         */
        const val DEFAULT_FULL_SYNC_INTERVAL_MS = 30L * 60 * 60 * 1000

        /** Acima disso, a UF não é enviada à API e o filtro é aplicado localmente. */
        const val MAX_UF_QUERIES = 3

        const val LEGACY_PAGE_SIZE = 50

        /** Teto para `Retry-After` (a busca não fica presa esperando minutos). */
        const val MAX_RETRY_AFTER_MS = 15_000L

        /** Páginas além de `totalPaginas` toleradas na leitura para a frente (registros novos durante a leitura). */
        private const val SAFETY_EXTRA_PAGES = 2

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

/**
 * Decisão de sincronização por partição (lógica pura, testável): completa, incremental ou nada (só o cache).
 * - atualização diária (`forceFull`): completa, salvo se a última terminou há menos de [FORCED_FULL_MIN_INTERVAL_MS];
 * - completa: sem marca, sem varredura completa anterior ou a última tem [fullIntervalMs] (30 h: a diária não rodou) ou
 *   mais — nunca com `incrementalOnly` (Worker em segundo plano);
 * - Worker sem NENHUMA varredura completa anterior: nada (não grava marca nem linhas soltas; a primeira busca em
 *   primeiro plano faz a completa);
 * - nada: a última leitura (qualquer) tem menos de [incrementalMinIntervalMs];
 * - incremental: o resto.
 */
internal object SyncPlanner {
    enum class Action { NONE, INCREMENTAL, FULL }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Na atualização diária forçada, uma completa terminada há menos disso é reaproveitada (retentativa/radares seguintes). */
    const val FORCED_FULL_MIN_INTERVAL_MS = 60L * 60 * 1000

    fun decide(
        mark: ListingSyncMark?,
        now: Long,
        incrementalOnly: Boolean,
        fullIntervalMs: Long,
        incrementalMinIntervalMs: Long,
        forceFull: Boolean = false,
    ): Action {
        val neverFull = mark == null || mark.lastFullSyncAt <= 0L
        if (forceFull && !incrementalOnly && (neverFull || now - mark!!.lastFullSyncAt !in 0 until FORCED_FULL_MIN_INTERVAL_MS)) return Action.FULL
        val fullDue = neverFull || now - mark!!.lastFullSyncAt !in 0 until fullIntervalMs
        if (fullDue && !incrementalOnly) return Action.FULL
        if (incrementalOnly && neverFull) return Action.NONE
        if (mark != null && now - mark.lastSyncAt in 0 until incrementalMinIntervalMs) return Action.NONE
        return Action.INCREMENTAL
    }

    /** Início do incremental: dia da última sincronização (sem marca: ontem) menos [overlapDays], nunca antes da janela. */
    fun incrementalStart(mark: ListingSyncMark?, now: Long, overlapDays: Long, windowStart: Long): Long {
        val base = mark?.lastSyncAt?.takeIf { it in 1..now } ?: (now - DAY_MS)
        return (base - overlapDays.coerceAtLeast(0) * DAY_MS).coerceIn(windowStart, now)
    }
}