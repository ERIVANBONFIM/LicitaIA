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
 * Busca de oportunidades em fontes REAIS (conectores com `isMock = false`, hoje PNCP e Compras.gov.br),
 * cache em Room para uso offline, filtros/radares e score de aderência.
 *
 * Conectores mock são ignorados por completo: nenhum resultado fictício é devolvido nem misturado
 * aos reais. Linhas antigas de portais simulados que ainda existam no cache também são filtradas.
 *
 * Deduplicação: as contratações da Lei 14.133 do Compras.gov.br também são publicadas no PNCP; a mesma
 * contratação chega dos dois conectores com o mesmo número de controle PNCP no sufixo do id
 * (`PNCP:<número>` / `COMPRAS_GOV:<número>`). [OpportunityDeduplicator] mantém só uma.
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

    private suspend fun cachedFor(portals: Set<Portal>, preferred: Portal = Portal.PNCP): List<Opportunity> =
        OpportunityDeduplicator.dedupe(opportunityDao.getAll().map { it.toDomain() }.filter { it.portal in portals }, preferred)

    /**
     * Portal preferido quando a mesma contratação vem do PNCP e do Compras.gov.br: o Compras.gov.br se o
     * usuário selecionou explicitamente esse portal; caso contrário (inclusive "Todos os portais"), o PNCP.
     */
    private fun preferredDuplicatePortal(selected: Set<Portal>): Portal =
        if (selected.size < Portal.entries.size && Portal.COMPRAS_GOV in selected) Portal.COMPRAS_GOV else Portal.PNCP

    /**
     * Consulta as fontes reais em paralelo. Fontes com falha são ignoradas; se todas falharem,
     * usa o cache local (modo offline) ou devolve erro amigável. Portais sem conector real são
     * simplesmente excluídos — e, se nenhum sobrar, o erro explica isso ao usuário.
     */
    private suspend fun fetch(portals: Set<Portal>, filter: OpportunityFilter): Result<List<Opportunity>> = coroutineScope {
        val sources = portals.mapNotNull { portal -> realConnector(portal)?.let { portal to it } }
        if (sources.isEmpty()) {
            val realNames = Portal.entries.filter { realConnector(it) != null }.map { it.displayName }
                .ifEmpty { listOf(Portal.PNCP.displayName, Portal.COMPRAS_GOV.displayName) }
            return@coroutineScope Result.failure(
                IOException(
                    "A busca de licitações está disponível apenas em ${realNames.joinToString(" e ")} (consulta pública). " +
                        "Os demais portais não possuem API pública integrada: selecione um desses portais ou \"Todos os portais\".",
                ),
            )
        }
        val realPortals = sources.map { it.first }.toSet()
        val preferred = preferredDuplicatePortal(filter.portals.ifEmpty { portals })
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
        val fetched = OpportunityDeduplicator.dedupe(
            results.mapNotNull { it.getOrNull() }.flatten().filter { it.portal in realPortals },
            preferred,
        )
        val allFailed = results.all { it.isFailure }
        if (!allFailed) {
            if (fetched.isNotEmpty()) {
                opportunityDao.upsertAll(fetched.map { it.toEntity(System.currentTimeMillis()) })
                runCatching { opportunityDao.prune(System.currentTimeMillis() - CACHE_TTL_MS) }
            }
            return@coroutineScope Result.success(fetched)
        }
        val cached = cachedFor(realPortals, preferred)
        val cause = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        when {
            cached.isNotEmpty() -> Result.success(cached)
            else -> Result.failure(
                IOException(
                    cause?.message?.takeIf { it.isNotBlank() }
                        ?: "Não foi possível consultar ${realPortals.joinToString(" e ") { it.displayName }} e não há oportunidades em cache. Verifique a conexão.",
                    cause,
                ),
            )
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 45L * 24 * 60 * 60 * 1000
    }
}

/**
 * Remove duplicatas da mesma contratação vinda de portais diferentes.
 *
 * A chave é o número de controle PNCP (`<cnpj 14>-1-<sequencial>/<ano>`) que PNCP e Compras.gov.br
 * colocam no sufixo do id (`PNCP:<número>` / `COMPRAS_GOV:<número>`). Ids fora desse padrão
 * (ex.: licitações legadas `COMPRAS_GOV:<uasg>-<mod>-<num>/<ano>`) só são deduplicados por igualdade.
 * A ordem de entrada é preservada; quando há duplicata, fica a do portal [preferred] (ou a primeira, se
 * nenhuma for do preferido).
 */
internal object OpportunityDeduplicator {
    private val PNCP_CONTROL_NUMBER = Regex("""^\d{14}-1-\d{1,6}/\d{4}$""")

    /** Chave de deduplicação: número de controle PNCP quando o id o contém; senão o próprio id. */
    fun key(opportunity: Opportunity): String {
        val ref = opportunity.id.substringAfter(':', missingDelimiterValue = "")
        return if (PNCP_CONTROL_NUMBER.matches(ref)) "pncp:$ref" else opportunity.id
    }

    fun dedupe(opportunities: List<Opportunity>, preferred: Portal): List<Opportunity> {
        if (opportunities.size < 2) return opportunities
        val byKey = LinkedHashMap<String, Opportunity>(opportunities.size)
        for (candidate in opportunities) {
            val k = key(candidate)
            val current = byKey[k]
            if (current == null || (current.portal != preferred && candidate.portal == preferred)) byKey[k] = candidate
        }
        return byKey.values.toList()
    }
}
