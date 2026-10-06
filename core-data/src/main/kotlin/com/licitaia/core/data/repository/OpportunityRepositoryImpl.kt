package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.HttpStatusFailure
import com.licitaia.connector.api.PortalConnector
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.RadarDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.network.ConnectivityMonitor
import com.licitaia.domain.network.OfflineException
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.scoring.OpportunityFilterMatcher
import com.licitaia.domain.scoring.OpportunityScorer
import com.licitaia.domain.scoring.RadarMatcher
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
) : OpportunityRepository {

    private val backoff = SourceBackoff()
    private val radarLocks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun search(companyId: Long, filter: OpportunityFilter): Result<List<ScoredOpportunity>> =
        withContext(Dispatchers.IO) {
            runCatching { access.requireCompany(companyId, Permission.BUSCAR) }.onFailure { return@withContext Result.failure(it) }
            val company = companyDao.getById(companyId)?.toDomain()
                ?: return@withContext Result.failure(IllegalArgumentException("Empresa não encontrada."))
            val radars = radarDao.getActive(companyId).map { it.toDomain() }
            val portals = filter.portals.ifEmpty { Portal.entries.toSet() }
            fetch(portals, filter).map { opportunities ->
                val interested = tenderDao.opportunityIds(companyId).toSet()
                opportunities
                    .filter { OpportunityFilterMatcher.matches(filter, it) }
                    .map { ScoredOpportunity(it, OpportunityScorer.score(it, company, radars), it.id in interested) }
                    .filter { it.score >= filter.minScore }
                    .sortedWith(compareByDescending<ScoredOpportunity> { it.score }.thenBy { it.opportunity.proposalDeadline })
            }
        }

    override suspend fun runRadar(radarId: Long): Result<List<ScoredOpportunity>> = withContext(Dispatchers.IO) {
        // Uma busca por vez para cada radar (tela aberta + atualização automática + Worker não se sobrepõem).
        radarLocks.getOrPut(radarId) { Mutex() }.withLock {
            val radar = radarDao.getById(radarId)?.toDomain()
                ?: return@withContext Result.failure(IllegalArgumentException("Radar não encontrado."))
            runCatching { access.requireCompany(radar.companyId, Permission.BUSCAR) }.onFailure { return@withContext Result.failure(it) }
            val company = companyDao.getById(radar.companyId)?.toDomain()
                ?: return@withContext Result.failure(IllegalArgumentException("Empresa não encontrada."))
            fetch(radarPortals(radar), radarFilter(radar)).map { opportunities ->
                val interested = tenderDao.opportunityIds(company.id).toSet()
                matchRadar(radar, company, opportunities, interested)
            }
        }
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
     * Contagem para o painel. Consulta as fontes reais ao começar a observar (abrir o app/painel), a cada
     * [COUNT_REFRESH_MS] enquanto houver alguém observando (primeiro plano) e quando a internet volta;
     * mudanças nos radares entre essas consultas usam o cache local (evita rajadas de rede).
     */
    override fun observeRadarMatchCount(companyId: Long): Flow<Int> {
        var lastFetchedTick = -1L
        var lastOnline = false
        val ticker = flow {
            var tick = 0L
            while (true) {
                emit(tick++)
                delay(COUNT_REFRESH_MS)
            }
        }
        return combine(radarDao.observeActive(companyId), ticker, connectivity.online) { radars, tick, online -> Triple(radars, tick, online) }
            .mapLatest { (radarEntities, tick, online) ->
                val reconnected = online && !lastOnline
                lastOnline = online
                if (!access.owns(companyId)) return@mapLatest 0
                if (radarEntities.isEmpty()) return@mapLatest 0
                val company = companyDao.getById(companyId)?.toDomain() ?: return@mapLatest 0
                val radars = radarEntities.map { it.toDomain() }
                val portals = radars.flatMap { radarPortals(it) }.toSet()
                if (searchSources(portals).isEmpty()) return@mapLatest 0
                val cached = cachedFor(portals)
                val shouldFetch = online && (tick != lastFetchedTick || reconnected || cached.isEmpty())
                val opportunities = if (shouldFetch) {
                    lastFetchedTick = tick
                    fetch(portals, OpportunityFilter()).getOrNull() ?: cached
                } else {
                    cached
                }
                val interested = tenderDao.opportunityIds(companyId).toSet()
                radars.flatMap { radar -> matchRadar(radar, company, opportunities, interested) }
                    .distinctBy { it.opportunity.id }
                    .size
            }
            .catch { emit(0) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
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
    )

    private fun matchRadar(
        radar: Radar,
        company: Company,
        opportunities: List<Opportunity>,
        interested: Set<String>,
    ): List<ScoredOpportunity> = opportunities
        .filter { RadarMatcher.matches(radar, it, company.uf) }
        .map { ScoredOpportunity(it, OpportunityScorer.score(it, company, listOf(radar)), it.id in interested) }
        .filter { it.score >= radar.minScore }
        .sortedWith(compareByDescending<ScoredOpportunity> { it.score }.thenBy { it.opportunity.proposalDeadline })

    /** Conector do portal somente se for REAL (isMock = false); mocks nunca alimentam a busca. */
    private fun realConnector(portal: Portal): PortalConnector? =
        runCatching { registry.get(portal) }.getOrNull()?.takeIf { !it.capabilities.isMock }

    /** Conectores reais capazes de listar ao menos um dos portais pedidos (o PNCP cobre todas as plataformas). */
    private fun searchSources(portals: Set<Portal>): List<PortalConnector> =
        SearchSources.select(runCatching { registry.all() }.getOrDefault(emptyList()), portals)

    private suspend fun cachedFor(portals: Set<Portal>): List<Opportunity> =
        OpportunityDeduplicator.dedupe(opportunityDao.getAll().map { it.toDomain() }.filter { it.portal in portals })

    /**
     * Consulta as fontes reais em paralelo. Fontes com falha (ou em backoff) são ignoradas; se todas falharem,
     * usa o cache local (modo offline) ou devolve erro amigável. Sem internet, falha na hora (sem esperar
     * timeout) e usa o cache.
     */
    private suspend fun fetch(portals: Set<Portal>, filter: OpportunityFilter): Result<List<Opportunity>> = coroutineScope {
        val sources = searchSources(portals)
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
                Result.success(cached)
            } else {
                Result.failure(OfflineException("Sem internet — a busca fica indisponível até reconectar e não há oportunidades salvas para estes filtros."))
            }
        }
        val now = System.currentTimeMillis()
        val results = sources.map { connector ->
            async {
                val wait = backoff.remainingMs(connector.portal, now)
                if (wait > 0) {
                    return@async Result.failure<List<Opportunity>>(
                        IOException(
                            "${connector.portal.displayName} limitou as consultas; nova tentativa automática em " +
                                "${(wait / 60_000L).coerceAtLeast(1)} min.",
                        ),
                    )
                }
                try {
                    Result.success(connector.listOpportunities(filter)).also { backoff.onSuccess(connector.portal) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    backoff.onFailure(connector.portal, e, System.currentTimeMillis())
                    Result.failure(e)
                }
            }
        }.awaitAll()
        val fetched = OpportunityDeduplicator.dedupe(
            results.mapNotNull { it.getOrNull() }.flatten().filter { it.portal in portals },
        )
        val allFailed = results.all { it.isFailure }
        if (!allFailed) {
            if (fetched.isNotEmpty()) {
                opportunityDao.upsertAll(fetched.map { it.toEntity(System.currentTimeMillis()) })
                runCatching { opportunityDao.prune(System.currentTimeMillis() - CACHE_TTL_MS) }
            }
            return@coroutineScope Result.success(fetched)
        }
        val cached = cachedFor(portals)
        val cause = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        when {
            cached.isNotEmpty() -> Result.success(cached)
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
    fun key(opportunity: Opportunity): String {
        val ref = opportunity.id.substringAfter(':', missingDelimiterValue = "")
        return if (PNCP_CONTROL_NUMBER.matches(ref)) "pncp:$ref" else opportunity.id
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
                    if (candidate.platformName == null) candidate.copy(platformName = current.platformName) else candidate
                current.platformName == null && candidate.platformName != null -> current.copy(platformName = candidate.platformName)
                else -> current
            }
        }
        return byKey.values.toList()
    }
}
