package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.HttpStatusFailure
import com.licitaia.connector.api.ListingSyncProgressSource
import com.licitaia.connector.api.OpportunityScreen
import com.licitaia.connector.api.PncpTrafficGate
import com.licitaia.connector.api.ScreenedOpportunitySource
import com.licitaia.connector.api.PortalConnector
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.RadarDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.model.AiScoreUpdate
import com.licitaia.domain.model.AiScoringRequest
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ProposalWindows
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.ScoreSource
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.SearchOutcome
import com.licitaia.domain.model.SourceDiagnostics
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.network.OfflineException
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.scoring.AiScoreMerge
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import com.licitaia.domain.scoring.OpportunityScorer
import com.licitaia.domain.scoring.RadarMatcher
import com.licitaia.domain.scoring.RelevanceContext
import com.licitaia.domain.scoring.ScoredOrder
import com.licitaia.domain.security.Permission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Busca de oportunidades em fontes REAIS (conectores com `isMock = false`, hoje PNCP e Compras.gov.br),
 * cache em Room para uso offline, filtros/radares e score de aderência.
 *
 * Conectores mock são ignorados por completo: nenhum resultado fictício é devolvido nem misturado
 * aos reais. Linhas antigas de portais simulados que ainda existam no cache também são filtradas.
 *
 * Portais × fontes: o PNCP agrega publicações de várias plataformas e o conector classifica cada uma
 * (`usuarioNome`) em [Portal.COMPRAS_GOV], [Portal.LICITANET], [Portal.BLL], [Portal.PORTAL_COMPRAS_PUBLICAS]
 * ou [Portal.PNCP] ([PortalConnector.searchablePortals]). Assim, filtrar "Licitanet" consulta o PNCP e devolve
 * as contratações publicadas pela Licitanet; filtrar "Compras.gov" consulta o conector Compras.gov.br E o PNCP.
 *
 * Deduplicação: a mesma contratação chega dos dois conectores com o mesmo número de controle PNCP no sufixo
 * do id (`PNCP:<número>` / `COMPRAS_GOV:<número>`). [OpportunityDeduplicator] mantém só uma, preferindo o
 * registro do conector Compras.gov.br (dados mais ricos).
 *
 * Limites das APIs: no máximo uma execução simultânea por radar ([runRadar]) e backoff exponencial por fonte
 * após HTTP 429/5xx ([SourceBackoff]); durante o backoff a fonte é pulada e o cache cobre a lacuna.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OpportunityRepositoryImpl @Inject constructor(
    private val registry: ConnectorRegistry,
    private val opportunityDao: OpportunityDao,
    private val tenderDao: TenderDao,
    private val radarDao: RadarDao,
    private val companyDao: CompanyDao,
    private val access: RepositoryAccess,
    private val connectivity: ConnectivityMonitor,
    private val relevance: AiRelevanceScorer,
) : OpportunityRepository {

    private val backoff = SourceBackoff()
    private val radarLocks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun search(companyId: Long, filter: OpportunityFilter): Result<List<ScoredOpportunity>> =
        searchWithSources(companyId, filter).map { it.items }

    override suspend fun searchWithSources(companyId: Long, filter: OpportunityFilter): Result<SearchOutcome> =
        searchInternal(companyId, filter, cacheOnly = false)

    override suspend fun searchCached(companyId: Long, filter: OpportunityFilter): Result<SearchOutcome> =
        searchInternal(companyId, filter, cacheOnly = true)

    /**
     * [cacheOnly] = abertura da tela: só o que já está salvo no aparelho (a atualização diária baixou as novas), sem
     * consultar as fontes nem pedir notas por IA; sem nada salvo, consulta as fontes normalmente.
     */
    private suspend fun searchInternal(companyId: Long, filter: OpportunityFilter, cacheOnly: Boolean): Result<SearchOutcome> =
        withContext(Dispatchers.IO) {
            runCatching { access.requireCompany(companyId, Permission.BUSCAR) }.onFailure { return@withContext Result.failure(it) }
            val company = companyDao.getById(companyId)?.toDomain()
                ?: return@withContext Result.failure(IllegalArgumentException("Empresa não encontrada."))
            val radars = radarDao.getActive(companyId).map { it.toDomain() }
            val portals = filter.portals.ifEmpty { Portal.entries.toSet() }
            // As fontes devolvem também as dispensas sem disputa (para contar as ocultas); o filtro real vem depois.
            val withNoDispute = filter.copy(showNoDispute = true)
            fetch(portals, withNoDispute, CandidateScreens.forSearch(withNoDispute, company, radars), cacheOnly).map { fetched ->
                val interested = tenderDao.opportunityIds(companyId).toSet()
                val split = NoDisputeVisibility.split(fetched.opportunities, filter.showNoDispute) { OpportunityFilterMatcher.matches(withNoDispute, it) }
                // Só abertas e as que vão abrir; sem prazo confiável ficam ocultas (contadas).
                val window = ProposalWindows.visible(split.visible, System.currentTimeMillis(), filter.showNoDispute)
                val (items, aiRequest) = scoreAll(
                    company, radars, radarId = null, minScore = filter.minScore,
                    opportunities = window.items,
                    // IA só na busca focada (texto/segmento); a geral fica na heurística + notas já salvas.
                    interested = interested, allowAi = filter.focused && !fetched.snapshot,
                )
                SearchOutcome(
                    items, fetched.sourceCounts, fetched.fromCache, fetched.failedSources, aiRequest, fetched.diagnostics, split.hidden,
                    hiddenNoDeadline = window.hiddenNoDeadline, cacheSnapshot = fetched.snapshot,
                )
            }
        }

    override suspend fun runRadar(radarId: Long): Result<List<ScoredOpportunity>> = runRadarWithSources(radarId).map { it.items }

    override suspend fun runRadarWithSources(radarId: Long): Result<SearchOutcome> = runRadarInternal(radarId, cacheOnly = false)

    override suspend fun runRadarCached(radarId: Long): Result<SearchOutcome> = runRadarInternal(radarId, cacheOnly = true)

    private suspend fun runRadarInternal(radarId: Long, cacheOnly: Boolean): Result<SearchOutcome> = withContext(Dispatchers.IO) {
        // Uma busca por vez para cada radar (tela aberta + atualização automática + Worker não se sobrepõem).
        radarLocks.getOrPut(radarId) { Mutex() }.withLock {
            val radar = radarDao.getById(radarId)?.toDomain()
                ?: return@withContext Result.failure(IllegalArgumentException("Radar não encontrado."))
            runCatching { access.requireCompany(radar.companyId, Permission.BUSCAR) }.onFailure { return@withContext Result.failure(it) }
            val company = companyDao.getById(radar.companyId)?.toDomain()
                ?: return@withContext Result.failure(IllegalArgumentException("Empresa não encontrada."))
            // Triagem com as dispensas sem disputa incluídas (para contar as ocultas); o radar real decide depois.
            val withNoDispute = radar.copy(showNoDispute = true)
            fetch(radarPortals(radar), radarFilter(radar), CandidateScreens.forRadars(listOf(withNoDispute), company), cacheOnly).map { fetched ->
                val interested = tenderDao.opportunityIds(company.id).toSet()
                val hidden = NoDisputeVisibility.split(fetched.opportunities, radar.showNoDispute) {
                    RadarMatcher.matches(withNoDispute, it, company.uf)
                }.hidden
                // Só abertas e as que vão abrir; sem prazo confiável ficam ocultas (contadas).
                val window = withContext(Dispatchers.Default) {
                    ProposalWindows.visible(
                        fetched.opportunities.filter { RadarMatcher.matches(radar, it, company.uf) }, System.currentTimeMillis(), radar.showNoDispute,
                    )
                }
                val (items, aiRequest) = matchRadar(radar, company, window.items, interested, allowAi = !fetched.snapshot)
                SearchOutcome(
                    items, fetched.sourceCounts, fetched.fromCache, fetched.failedSources, aiRequest, fetched.diagnostics, hidden,
                    hiddenNoDeadline = window.hiddenNoDeadline, cacheSnapshot = fetched.snapshot,
                )
            }
        }
    }

    override fun scoreWithAi(request: AiScoringRequest): Flow<AiScoreUpdate> = flow {
        if (request.candidates.isEmpty()) return@flow
        access.requireCompany(request.companyId, Permission.BUSCAR)
        val company = companyDao.getById(request.companyId)?.toDomain() ?: return@flow
        emitAll(relevance.rate(request, company))
    }.flowOn(Dispatchers.IO)

    override suspend fun runRadarWithAi(radarId: Long, aiLimit: Int, skipIds: Set<String>): Result<List<ScoredOpportunity>> {
        val outcome = runRadarWithSources(radarId).getOrElse { return Result.failure(it) }
        val request = outcome.aiRequest ?: return Result.success(outcome.items)
        val chosen = request.candidates.filter { it.opportunity.id !in skipIds }.take(aiLimit.coerceAtLeast(0))
        if (chosen.isEmpty()) return Result.success(outcome.items)
        var current = outcome.items
        scoreWithAi(request.copy(candidates = chosen)).collect { update ->
            current = AiScoreMerge.merge(current, update.rated, request.minScore)
        }
        return Result.success(current)
    }

    override suspend fun getOpportunity(id: String): Opportunity? = withContext(Dispatchers.IO) {
        opportunityDao.getById(id)?.toDomain()?.let { return@withContext it }
        // O prefixo do id identifica o CONECTOR de origem ("PNCP:" / "COMPRAS_GOV:"), não a plataforma classificada.
        val portal = Portal.entries.firstOrNull { id.startsWith(it.name + ":") } ?: return@withContext null
        val connector = realConnector(portal) ?: return@withContext null
        if (!connectivity.hasNetwork) return@withContext null
        val fetched = runCatching { connector.getTenderDetails(id)?.opportunity }.getOrNull()
        fetched?.also { opportunityDao.upsertAll(listOf(it.toEntity(System.currentTimeMillis()))) }
    }

    /**
     * Contagem para o painel, SÓ com o cache local (nenhuma consulta às fontes ao abrir o app: a atualização diária das
     * 05:30 e as atualizações pedidas na busca alimentam o cache). Recalcula ao mudar os radares e a cada
     * [COUNT_REFRESH_MS] (leitura local: o que encerrou sai e o que a atualização diária trouxe entra).
     */
    override fun observeRadarMatchCount(companyId: Long): Flow<Int> {
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(COUNT_REFRESH_MS)
            }
        }
        return combine(radarDao.observeActive(companyId), ticker) { radars, _ -> radars }
            .mapLatest { radarEntities ->
                if (!access.owns(companyId)) return@mapLatest 0
                if (radarEntities.isEmpty()) return@mapLatest 0
                val company = companyDao.getById(companyId)?.toDomain() ?: return@mapLatest 0
                val radars = radarEntities.map { it.toDomain() }
                val portals = radars.flatMap { radarPortals(it) }.toSet()
                if (searchSources(portals).isEmpty()) return@mapLatest 0
                val opportunities = cachedFor(portals)
                val interested = tenderDao.opportunityIds(companyId).toSet()
                val now = System.currentTimeMillis()
                radars.flatMap { radar ->
                    val visible = ProposalWindows.visible(opportunities, now, radar.showNoDispute).items
                    matchRadar(radar, company, visible, interested, allowAi = false).first
                }
                    .distinctBy { it.opportunity.id }
                    .size
            }
            .catch { emit(0) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    override fun observeSourceSync(): Flow<String?> {
        val sources = runCatching { registry.all() }.getOrDefault(emptyList())
            .filter { !it.capabilities.isMock }
            .mapNotNull { c -> (c as? ListingSyncProgressSource)?.let { c.portal to it.syncProgress } }
        if (sources.isEmpty()) return kotlinx.coroutines.flow.flowOf(null)
        return combine(sources.map { it.second }) { progress ->
            sources.indices.firstNotNullOfOrNull { i -> progress[i]?.let { SourceSyncLabel.of(sources[i].first, it.rows) } }
        }.distinctUntilChanged()
    }

    // ------------------------------------------------------------------ apoio

    private fun radarPortals(radar: Radar): Set<Portal> =
        if (radar.allPortals || radar.portals.isEmpty()) Portal.entries.toSet() else radar.portals.toSet()

    /** Parte do radar que o conector consegue aplicar na própria API (UF, modalidade); o resto fica no RadarMatcher. */
    private fun radarFilter(radar: Radar): OpportunityFilter = OpportunityFilter(
        ufs = radar.ufs.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet(),
        modality = radar.modality,
        minValue = radar.minValue,
        maxValue = radar.maxValue,
        // As fontes devolvem as dispensas sem disputa; o RadarMatcher aplica [Radar.showNoDispute] (e conta as ocultas).
        showNoDispute = true,
    )

    private suspend fun matchRadar(
        radar: Radar,
        company: Company,
        opportunities: List<Opportunity>,
        interested: Set<String>,
        allowAi: Boolean,
    ): Pair<List<ScoredOpportunity>, AiScoringRequest?> = scoreAll(
        company, listOf(radar), radarId = radar.id, minScore = radar.minScore,
        opportunities = withContext(Dispatchers.Default) { opportunities.filter { RadarMatcher.matches(radar, it, company.uf) } },
        interested = interested, allowAi = allowAi,
    )

    /**
     * Pipeline de nota: heurística -> notas por IA já salvas (cache) -> score mínimo. Devolve a lista visível e, com
     * provedor real e [allowAi], o pedido de notas por IA para os candidatos que passaram no filtro de palavras e
     * ainda não têm nota (até [AiRelevanceScorer.MAX_PER_RUN], prazos mais próximos primeiro). Candidatos abaixo do
     * mínimo pela heurística ficam fora da lista até a IA avaliá-los.
     */
    private suspend fun scoreAll(
        company: Company,
        radars: List<Radar>,
        radarId: Long?,
        minScore: Int,
        opportunities: List<Opportunity>,
        interested: Set<String>,
        allowAi: Boolean,
    ): Pair<List<ScoredOpportunity>, AiScoringRequest?> = withContext(Dispatchers.Default) {
        // Score/ordenação são CPU: Dispatchers.Default (nunca Main; o IO fica para rede/banco).
        val context = RelevanceContext.of(company, radars)
        val assessments = opportunities.map { OpportunityScorer.assess(it, company, radars) }
        val heuristic = opportunities.mapIndexed { i, o -> ScoredOpportunity(o, assessments[i].score, o.id in interested) }
        val scored = relevance.applyCached(company.id, context.signature, heuristic)
        val visible = scored.filter { it.score >= minScore }.sortedWith(OpportunityDeadlines.ORDER)
        if (!allowAi) return@withContext visible to null
        val eligible = scored.filterIndexed { i, _ -> assessments[i].isCandidate }
        val cachedCount = eligible.count { it.scoreSource == ScoreSource.AI }
        val pending = eligible.filter { it.scoreSource == ScoreSource.HEURISTIC }
        if ((pending.isEmpty() && cachedCount == 0) || !relevance.aiAvailable()) return@withContext visible to null
        val request = AiScoringRequest(
            companyId = company.id, radarId = radarId, minScore = minScore,
            radarSignature = context.signature, radarHint = context.hint,
            candidates = AiScoreMerge.prioritize(pending, AiRelevanceScorer.MAX_PER_RUN),
            alreadyRated = cachedCount,
        )
        visible to request
    }

    /** Conector do portal somente se for REAL (isMock = false); mocks nunca alimentam a busca. */
    private fun realConnector(portal: Portal): PortalConnector? =
        runCatching { registry.get(portal) }.getOrNull()?.takeIf { !it.capabilities.isMock }

    /** Conectores reais capazes de listar ao menos um dos portais pedidos (o PNCP cobre todas as plataformas). */
    private fun searchSources(portals: Set<Portal>): List<PortalConnector> =
        SearchSources.select(runCatching { registry.all() }.getOrDefault(emptyList()), portals)

    private suspend fun cachedFor(portals: Set<Portal>): List<Opportunity> {
        val rows = opportunityDao.getAll()
        return withContext(Dispatchers.Default) {
            OpportunityDeadlines.dropClosed(
                OpportunityDeduplicator.dedupe(rows.map { it.toDomain() }.filter { it.portal in portals }),
                System.currentTimeMillis(),
            )
        }
    }

    /**
     * Consulta as fontes reais em paralelo. Fontes com falha (ou em backoff) são ignoradas; se todas falharem,
     * usa o cache local (modo offline) ou devolve erro amigável. Sem internet, falha na hora (sem esperar
     * timeout) e usa o cache.
     */
    /** O que as fontes devolveram: lista deduplicada + itens por fonte (antes da dedup) ou cache. */
    private data class Fetched(
        val opportunities: List<Opportunity>,
        val sourceCounts: Map<Portal, Int> = emptyMap(),
        val fromCache: Boolean = false,
        val failedSources: Set<Portal> = emptySet(),
        /** Funil das fontes com triagem local (Compras.gov.br): lidas → candidatas → abertas. */
        val diagnostics: Map<Portal, SourceDiagnostics> = emptyMap(),
        /** Leitura proposital só do cache (abertura da tela), não por falha/sem internet. */
        val snapshot: Boolean = false,
    )

    /**
     * [screen] = triagem das candidatas (palavras/UF/valor/modalidade/relevância) que as fontes de leitura completa
     * ([ScreenedOpportunitySource], hoje o Compras.gov.br) aplicam ANTES de enriquecer prazos no PNCP.
     */
    private suspend fun fetch(
        portals: Set<Portal>,
        filter: OpportunityFilter,
        screen: OpportunityScreen = OpportunityScreen.ACCEPT_ALL,
        cacheOnly: Boolean = false,
    ): Result<Fetched> = coroutineScope {
        // Abertura da tela: mostra na hora o que já está salvo (a atualização diária baixou as novas); só sem nada
        // salvo (primeiro uso) consulta as fontes.
        if (cacheOnly) {
            val cached = cachedFor(portals)
            if (cached.isNotEmpty()) return@coroutineScope Result.success(Fetched(cached, fromCache = true, snapshot = true))
        }
        val sources = searchSources(portals)
        // Os conectores recebem os portais pedidos (também no radar): com só "Compras.gov" eles leem mais páginas
        // dessa plataforma (PNCP classificado por usuarioNome + dados abertos do Compras.gov.br).
        val sourceFilter = filter.copy(portals = if (portals.containsAll(Portal.entries)) emptySet() else portals)
        if (sources.isEmpty()) {
            return@coroutineScope Result.failure(
                IOException(
                    "A busca de licitações usa as consultas públicas do PNCP e do Compras.gov.br, que não estão disponíveis " +
                        "nesta instalação. Selecione \"Todos os portais\" e tente novamente.",
                ),
            )
        }
        if (!connectivity.hasNetwork) {
            val cached = cachedFor(portals)
            return@coroutineScope if (cached.isNotEmpty()) {
                Result.success(Fetched(cached, fromCache = true))
            } else {
                Result.failure(OfflineException("Sem internet — a busca fica indisponível até reconectar e não há oportunidades salvas para estes filtros."))
            }
        }
        val now = System.currentTimeMillis()
        val diagnostics = ConcurrentHashMap<Portal, SourceDiagnostics>()
        // Listagem do PNCP e consultas de prazo do Compras.gov.br no mesmo host: as de prazo esperam a listagem.
        val gate = PncpTrafficGate()
        val gated = sources.any { it !is ScreenedOpportunitySource && it.portal == Portal.PNCP }
        if (!gated) gate.listingDone.complete(Unit)
        val results = sources.map { connector ->
            async {
                try {
                    val wait = backoff.remainingMs(connector.portal, now)
                    if (wait > 0) {
                        return@async Result.failure<List<Opportunity>>(
                            IOException(
                                "${connector.portal.displayName} limitou as consultas; nova tentativa automática em " +
                                    "${(wait / 60_000L).coerceAtLeast(1)} min.",
                            ),
                        )
                    }
                    val list = if (connector is ScreenedOpportunitySource) {
                        withContext(gate) { connector.listScreened(sourceFilter, screen) }
                            .also { diagnostics[connector.portal] = it.diagnostics }.opportunities
                    } else {
                        connector.listOpportunities(sourceFilter)
                    }
                    Result.success(list).also { backoff.onSuccess(connector.portal) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    backoff.onFailure(connector.portal, e, System.currentTimeMillis())
                    Result.failure(e)
                } finally {
                    if (connector.portal == Portal.PNCP && connector !is ScreenedOpportunitySource) gate.listingDone.complete(Unit)
                }
            }
        }.awaitAll()
        val perSource = sources.zip(results).mapNotNull { (connector, r) ->
            r.getOrNull()?.let { list -> connector.portal to list.count { it.portal in portals } }
        }.toMap()
        // Dedup ANTES do corte por prazo: o registro do Compras.gov.br sem encerramento herda o prazo do PNCP.
        val fetched = withContext(Dispatchers.Default) {
            OpportunityDeadlines.dropClosed(
                OpportunityDeduplicator.dedupe(results.mapNotNull { it.getOrNull() }.flatten().filter { it.portal in portals }),
                System.currentTimeMillis(),
            )
        }
        val allFailed = results.all { it.isFailure }
        if (!allFailed) {
            if (fetched.isNotEmpty()) {
                opportunityDao.upsertAll(fetched.map { it.toEntity(System.currentTimeMillis()) })
                runCatching { opportunityDao.prune(System.currentTimeMillis() - CACHE_TTL_MS) }
            }
            val failed = sources.zip(results).filter { it.second.isFailure }.map { it.first.portal }.toSet()
            return@coroutineScope Result.success(Fetched(fetched, perSource, failedSources = failed, diagnostics = diagnostics.toMap()))
        }
        val cached = cachedFor(portals)
        val cause = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        when {
            cached.isNotEmpty() -> Result.success(Fetched(cached, fromCache = true))
            else -> Result.failure(
                IOException(
                    cause?.message?.takeIf { it.isNotBlank() }
                        ?: "Não foi possível consultar ${sources.joinToString(" e ") { it.portal.displayName }} e não há oportunidades em cache. Verifique a conexão.",
                    cause,
                ),
            )
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 45L * 24 * 60 * 60 * 1000
        const val COUNT_REFRESH_MS = 5L * 60 * 1000
    }
}

/**
 * Triagem das candidatas aplicada pelas fontes de leitura completa antes do enriquecimento (lógica pura, testável):
 * filtros do radar/busca + heurística de relevância (nota < [MIN_RELEVANCE] é descartada). Busca com texto digitado
 * não sofre o corte de relevância: o termo do usuário já define o que interessa.
 */
internal object CandidateScreens {
    const val MIN_RELEVANCE = 20

    fun forRadars(radars: List<Radar>, company: Company): OpportunityScreen {
        val active = radars.filter { it.active }.ifEmpty { radars }
        return OpportunityScreen { o ->
            active.any { r -> RadarMatcher.matches(r, o, company.uf) && OpportunityScorer.score(o, company, listOf(r)) >= MIN_RELEVANCE }
        }
    }

    fun forSearch(filter: OpportunityFilter, company: Company, radars: List<Radar>): OpportunityScreen {
        val typed = filter.query.isNotBlank()
        return OpportunityScreen { o ->
            OpportunityFilterMatcher.matches(filter, o) && (typed || OpportunityScorer.score(o, company, radars) >= MIN_RELEVANCE)
        }
    }
}

/**
 * Dispensas sem disputa ([Opportunity.noDispute]) ocultas por padrão (lógica pura, testável): [split] aplica o
 * filtro da busca/radar com as dispensas incluídas ([matchesIncluding]) e separa as que ficam visíveis das ocultas.
 */
internal object NoDisputeVisibility {
    data class Split(val visible: List<Opportunity>, val hidden: Int)

    fun split(opportunities: List<Opportunity>, show: Boolean, matchesIncluding: (Opportunity) -> Boolean): Split {
        val matched = opportunities.filter(matchesIncluding)
        if (show) return Split(matched, 0)
        val (hidden, visible) = matched.partition { it.noDispute }
        return Split(visible, hidden.size)
    }
}

/** Texto do andamento da sincronização (lógica pura, testável). */
internal object SourceSyncLabel {
    fun of(portal: Portal, rows: Int): String =
        "Sincronizando ${portal.displayName}… " + String.format(java.util.Locale.forLanguageTag("pt-BR"), "%,d", rows) + " linhas"
}

/** Seleção das fontes de busca para um conjunto de portais (lógica pura, testável). */
internal object SearchSources {
    fun select(all: List<PortalConnector>, portals: Set<Portal>): List<PortalConnector> =
        all.filter { !it.capabilities.isMock && it.searchablePortals.any { p -> p in portals } }
            .distinctBy { it.portal }
}

/**
 * Backoff exponencial por fonte após HTTP 429 (limite de requisições) ou 5xx: 1, 2, 4, 8... min, até 30 min.
 * Sucesso zera o contador. Outras falhas (sem rede, 4xx, formato) não geram backoff.
 */
internal class SourceBackoff(
    private val baseMs: Long = 60_000L,
    private val maxMs: Long = 30L * 60_000L,
) {
    private data class State(val failures: Int, val until: Long)

    private val states = ConcurrentHashMap<Portal, State>()

    fun remainingMs(portal: Portal, now: Long): Long = ((states[portal]?.until ?: 0L) - now).coerceAtLeast(0L)

    fun onSuccess(portal: Portal) {
        states.remove(portal)
    }

    /** Registra a falha; devolve true quando ela colocou a fonte em backoff. */
    fun onFailure(portal: Portal, error: Throwable, now: Long): Boolean {
        val status = generateSequence(error) { it.cause }.take(5).firstNotNullOfOrNull { (it as? HttpStatusFailure)?.httpStatus }
        if (status == null || (status != 429 && status !in 500..599)) return false
        val failures = (states[portal]?.failures ?: 0) + 1
        val delay = (baseMs shl (failures - 1).coerceAtMost(10)).coerceAtMost(maxMs)
        states[portal] = State(failures, now + delay)
        return true
    }
}

/**
 * Remove duplicatas da mesma contratação vinda de conectores diferentes.
 *
 * A chave é o número de controle PNCP (`<cnpj 14>-1-<sequencial>/<ano>`) que PNCP e Compras.gov.br
 * colocam no sufixo do id (`PNCP:<número>` / `COMPRAS_GOV:<número>`). Ids fora desse padrão
 * (ex.: licitações legadas `COMPRAS_GOV:<uasg>-<mod>-<num>/<ano>`) só são deduplicados por igualdade.
 * A ordem de entrada é preservada; quando há duplicata, fica o registro do CONECTOR Compras.gov.br (id com
 * prefixo `COMPRAS_GOV:`, dados mais ricos); senão o primeiro (o do PNCP, já com o portal classificado).
 * Quando o registro mantido não tem nome de plataforma, herda o da duplicata descartada.
 */
internal object OpportunityDeduplicator {
    private val PNCP_CONTROL_NUMBER = Regex("""^\d{14}-1-\d{1,6}/\d{4}$""")
    private val COMPRAS_GOV_PREFIX = Portal.COMPRAS_GOV.name + ":"

    /** Chave de deduplicação: número de controle PNCP quando o id o contém; senão o próprio id. */
    fun key(opportunity: Opportunity): String = keyOfId(opportunity.id)

    fun keyOfId(id: String): String {
        val ref = id.substringAfter(':', missingDelimiterValue = "")
        return if (PNCP_CONTROL_NUMBER.matches(ref)) "pncp:$ref" else id
    }

    private fun fromComprasGovConnector(o: Opportunity) = o.id.startsWith(COMPRAS_GOV_PREFIX)

    fun dedupe(opportunities: List<Opportunity>): List<Opportunity> {
        if (opportunities.size < 2) return opportunities
        val byKey = LinkedHashMap<String, Opportunity>(opportunities.size)
        for (candidate in opportunities) {
            val k = key(candidate)
            val current = byKey[k]
            byKey[k] = when {
                current == null -> candidate
                fromComprasGovConnector(candidate) && !fromComprasGovConnector(current) ->
                    fillDeadline(if (candidate.platformName == null) candidate.copy(platformName = current.platformName) else candidate, current)
                current.platformName == null && candidate.platformName != null ->
                    fillDeadline(current.copy(platformName = candidate.platformName), candidate)
                else -> fillDeadline(current, candidate)
            }
        }
        return byKey.values.toList()
    }

    /**
     * O registro mantido sem prazo de propostas (ex.: Compras.gov.br com encerramento nulo) herda o prazo da duplicata
     * descartada (o PNCP do mesmo número de controle), quando ela tem um.
     */
    private fun fillDeadline(kept: Opportunity, other: Opportunity): Opportunity =
        if (!kept.hasProposalDeadline && other.hasProposalDeadline) {
            kept.copy(
                proposalDeadline = other.proposalDeadline,
                sessionAt = if (kept.sessionAt > Opportunity.DEADLINE_UNKNOWN) kept.sessionAt else other.sessionAt,
                // Com prazo de propostas conhecido há disputa: só continua "sem disputa" se as duas fontes disserem.
                noDispute = kept.noDispute && other.noDispute,
                proposalOpening = kept.proposalOpening ?: other.proposalOpening,
            )
        } else if (kept.proposalOpening == null && other.proposalOpening != null) {
            kept.copy(proposalOpening = other.proposalOpening)
        } else {
            kept
        }
}

/**
 * Regras de validade de prazo aplicadas a TUDO o que chega ao usuário (fontes e cache):
 * prazo de propostas conhecido e já vencido → descartado; sem prazo informado → mantido ("prazo não informado"),
 * ordenado depois dos que têm prazo.
 */
internal object OpportunityDeadlines {
    fun dropClosed(opportunities: List<Opportunity>, now: Long): List<Opportunity> =
        opportunities.filterNot { it.isProposalClosed(now) }

    /** Score desc; depois prazo mais próximo; "prazo não informado" por último. */
    val ORDER: Comparator<ScoredOpportunity> = ScoredOrder.ORDER
}
