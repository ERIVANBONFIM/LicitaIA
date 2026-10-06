package com.licitaia.core.data.repository

import com.licitaia.connector.api.ConnectorRegistry
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Busca de oportunidades em fontes REAIS (conectores com `isMock = false`, hoje o PNCP), cache em Room
 * para uso offline, filtros/radares e score de aderência.
 *
 * Conectores mock são ignorados por completo: nenhum resultado fictício é devolvido nem misturado
 * aos reais. Linhas antigas de portais simulados que ainda existam no cache também são filtradas.
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
) : OpportunityRepository {

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

    override suspend fun getOpportunity(id: String): Opportunity? = withContext(Dispatchers.IO) {
        opportunityDao.getById(id)?.toDomain()?.let { return@withContext it }
        val portal = Portal.entries.firstOrNull { id.startsWith(it.name + ":") } ?: return@withContext null
        val connector = realConnector(portal) ?: return@withContext null
        val fetched = runCatching { connector.getTenderDetails(id)?.opportunity }.getOrNull()
        fetched?.also { opportunityDao.upsertAll(listOf(it.toEntity(System.currentTimeMillis()))) }
    }

    /**
     * Contagem para o painel. Usa o cache local quando ele já tem dados de fontes reais (evita
     * rajadas de rede a cada mudança de radar); só consulta a API quando o cache está vazio.
     */
    override fun observeRadarMatchCount(companyId: Long): Flow<Int> =
        radarDao.observeActive(companyId)
            .mapLatest { radarEntities ->
                if (!access.owns(companyId)) return@mapLatest 0
                if (radarEntities.isEmpty()) return@mapLatest 0
                val company = companyDao.getById(companyId)?.toDomain() ?: return@mapLatest 0
                val radars = radarEntities.map { it.toDomain() }
                val portals = radars.flatMap { radarPortals(it) }.toSet()
                val real = portals.filter { realConnector(it) != null }.toSet()
                if (real.isEmpty()) return@mapLatest 0
                val cached = cachedFor(real)
                val opportunities = if (cached.isNotEmpty()) cached else fetch(real, OpportunityFilter()).getOrDefault(emptyList())
                val interested = tenderDao.opportunityIds(companyId).toSet()
                radars.flatMap { radar -> matchRadar(radar, company, opportunities, interested) }
                    .distinctBy { it.opportunity.id }
                    .size
            }
            .catch { emit(0) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

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

    private suspend fun cachedFor(portals: Set<Portal>): List<Opportunity> =
        opportunityDao.getAll().map { it.toDomain() }.filter { it.portal in portals }

    /**
     * Consulta as fontes reais em paralelo. Fontes com falha são ignoradas; se todas falharem,
     * usa o cache local (modo offline) ou devolve erro amigável. Portais sem conector real são
     * simplesmente excluídos — e, se nenhum sobrar, o erro explica isso ao usuário.
     */
    private suspend fun fetch(portals: Set<Portal>, filter: OpportunityFilter): Result<List<Opportunity>> = coroutineScope {
        val sources = portals.mapNotNull { portal -> realConnector(portal)?.let { portal to it } }
        if (sources.isEmpty()) {
            return@coroutineScope Result.failure(
                IOException(
                    "A busca de licitações está disponível apenas no PNCP (consulta pública). " +
                        "Os demais portais não possuem API pública integrada: selecione o PNCP ou \"Todos os portais\".",
                ),
            )
        }
        val realPortals = sources.map { it.first }.toSet()
        val results = sources.map { (_, connector) ->
            async {
                try {
                    Result.success(connector.listOpportunities(filter))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.awaitAll()
        val fetched = results.mapNotNull { it.getOrNull() }.flatten()
            .filter { it.portal in realPortals }
            .distinctBy { it.id }
        val allFailed = results.all { it.isFailure }
        if (!allFailed) {
            if (fetched.isNotEmpty()) {
                opportunityDao.upsertAll(fetched.map { it.toEntity(System.currentTimeMillis()) })
                runCatching { opportunityDao.prune(System.currentTimeMillis() - CACHE_TTL_MS) }
            }
            return@coroutineScope Result.success(fetched)
        }
        val cached = cachedFor(realPortals)
        val cause = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        when {
            cached.isNotEmpty() -> Result.success(cached)
            else -> Result.failure(
                IOException(
                    cause?.message?.takeIf { it.isNotBlank() }
                        ?: "Não foi possível consultar o PNCP e não há oportunidades em cache. Verifique a conexão.",
                    cause,
                ),
            )
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 45L * 24 * 60 * 60 * 1000
    }
}
