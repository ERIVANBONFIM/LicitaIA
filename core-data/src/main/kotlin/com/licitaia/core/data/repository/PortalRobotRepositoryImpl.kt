package com.licitaia.core.data.repository

import com.licitaia.core.data.db.PortalMyTenderEntity
import com.licitaia.core.data.db.PortalRobotDao
import com.licitaia.core.data.db.PortalRobotPlanEntity
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalItemReading
import com.licitaia.domain.portal.PortalItemState
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalProposalReading
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.portal.RobotProposalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Minhas licitações" do Comprasnet + planos do robô (v11). Toda leitura/escrita confere a posse da empresa
 * ([RepositoryAccess]); listas de outra empresa voltam vazias.
 */
@Singleton
class PortalRobotRepositoryImpl @Inject constructor(
    private val dao: PortalRobotDao,
    private val access: RepositoryAccess,
) : PortalRobotRepository {

    override fun observeMyTenders(companyId: Long): Flow<List<PortalMyTender>> =
        dao.observeMyTenders(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain() } else emptyList() }

    override suspend fun getMyTenders(companyId: Long): List<PortalMyTender> = withContext(Dispatchers.IO) {
        if (!access.owns(companyId)) emptyList() else dao.getMyTenders(companyId).map { it.toDomain() }
    }

    override suspend fun upsertMyTenders(companyId: Long, items: List<PortalMyTender>) {
        withContext(Dispatchers.IO) {
            access.requireCompany(companyId)
            val existing = dao.getMyTenders(companyId).associateBy { it.tenderKey }
            val merged = items.filter { it.companyId == companyId }.map { new ->
                val old = existing[new.tenderKey]?.toDomain()
                PortalRobotMerge.merge(old, new).toEntity()
            }
            if (merged.isNotEmpty()) dao.upsertMyTenders(merged)
        }
    }

    override suspend fun deleteMyTender(companyId: Long, tenderKey: String) {
        withContext(Dispatchers.IO) {
            access.requireCompany(companyId)
            dao.deleteMyTender(companyId, tenderKey)
        }
    }

    override fun observePlans(companyId: Long): Flow<List<PortalRobotPlan>> =
        dao.observePlans(companyId).map { list -> if (access.owns(companyId)) list.mapNotNull { it.toDomainOrNull() } else emptyList() }

    override fun observePlan(companyId: Long, tenderKey: String): Flow<PortalRobotPlan?> =
        dao.observePlan(companyId, tenderKey).map { e -> e?.takeIf { access.owns(companyId) }?.toDomainOrNull() }

    override suspend fun getPlan(companyId: Long, tenderKey: String): PortalRobotPlan? = withContext(Dispatchers.IO) {
        if (!access.owns(companyId)) null else dao.getPlan(companyId, tenderKey)?.toDomainOrNull()
    }

    override suspend fun savePlan(plan: PortalRobotPlan) {
        withContext(Dispatchers.IO) {
            access.requireCompany(plan.companyId)
            dao.upsertPlan(PortalRobotPlanCodec.encode(plan.copy(updatedAt = System.currentTimeMillis())))
        }
    }

    private fun PortalRobotPlanEntity.toDomainOrNull(): PortalRobotPlan? = runCatching { PortalRobotPlanCodec.decode(this) }.getOrNull()
}

/** Mescla de uma nova leitura com a linha existente (pura, testável). */
object PortalRobotMerge {
    fun merge(old: PortalMyTender?, new: PortalMyTender): PortalMyTender {
        if (old == null) return new
        return new.copy(
            uasg = new.uasg.ifBlank { old.uasg },
            modality = new.modality.ifBlank { old.modality },
            objectDescription = new.objectDescription.ifBlank { old.objectDescription },
            openingAt = new.openingAt ?: old.openingAt,
            situation = new.situation.ifBlank { old.situation },
            // "Tem proposta" vindo de qualquer fonte vale até uma leitura da MESMA fonte dizer o contrário.
            hasProposal = new.hasProposal || (old.hasProposal && (old.sources - new.sources).isNotEmpty()),
            sources = old.sources + new.sources,
            pncpControl = new.pncpControl ?: old.pncpControl,
            matchedOpportunityId = new.matchedOpportunityId ?: old.matchedOpportunityId,
            matchedTenderId = new.matchedTenderId ?: old.matchedTenderId,
            firstSeenAt = minOf(old.firstSeenAt, new.firstSeenAt),
        )
    }
}

internal fun PortalMyTenderEntity.toDomain() = PortalMyTender(
    companyId = companyId, tenderKey = tenderKey, portal = portal, uasg = uasg, number = number, year = year, modality = modality,
    objectDescription = objectDescription, openingAt = openingAt, situation = situation, hasProposal = hasProposal,
    sources = sources.toSet(), pncpControl = pncpControl, matchedOpportunityId = matchedOpportunityId, matchedTenderId = matchedTenderId,
    firstSeenAt = firstSeenAt, updatedAt = updatedAt,
)

internal fun PortalMyTender.toEntity() = PortalMyTenderEntity(
    companyId = companyId, tenderKey = tenderKey, portal = portal, uasg = uasg, number = number, year = year, modality = modality,
    objectDescription = objectDescription, openingAt = openingAt, situation = situation, hasProposal = hasProposal,
    sources = sources.sorted(), pncpControl = pncpControl, matchedOpportunityId = matchedOpportunityId, matchedTenderId = matchedTenderId,
    firstSeenAt = firstSeenAt, updatedAt = updatedAt,
)

/** JSON das colunas do plano do robô (DTOs próprios: o domínio não depende de kotlinx.serialization). */
object PortalRobotPlanCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    internal data class ItemDto(
        val itemNumber: Int,
        val description: String = "",
        val quantity: Double,
        val unitPrice: Double,
        val brand: String = "",
        val manufacturer: String = "",
        val modelVersion: String = "",
        val detailedDescription: String = "",
        val floorUnitPrice: Double? = null,
        /** Participação no item (planos antigos sem o campo: todos participam). */
        val selected: Boolean = true,
        /**
         * Última leitura da situação no portal ([PortalProposalReading]), guardada no próprio JSON dos itens (sem
         * mudar o schema): data/hora da leitura (em todos os itens), estado ("LANCADO"/"NAO_CADASTRADO"/
         * "DESCONHECIDO"; null = item não apareceu no portal), "Meu valor (unitário)", grupo e exclusividade ME/EPP.
         */
        val portalReadAt: Long? = null,
        val portalState: String? = null,
        val portalUnitPrice: Double? = null,
        val portalGroup: String? = null,
        val portalMeEppExclusive: Boolean = false,
    )

    @Serializable
    internal data class BidDto(
        val mode: String = BidRobotMode.DESLIGADO.name,
        val strategy: String = BidStrategy.CONSERVADORA.name,
        val minDecrement: Double = 0.01,
        val reductionValue: Double = 1.0,
        val ownIntervalSeconds: Int = BidRobotConfig.MIN_OWN_INTERVAL_SECONDS,
        val afterBestSeconds: Int = BidRobotConfig.MIN_AFTER_BEST_SECONDS,
        val maxBids: Int = 30,
        val bidOnTotal: Boolean = false,
    )

    fun encode(plan: PortalRobotPlan) = PortalRobotPlanEntity(
        companyId = plan.companyId,
        tenderKey = plan.tenderKey,
        itemsJson = json.encodeToString(ListSerializer(ItemDto.serializer()), plan.items.map {
            val r = plan.portalReading
            val p = r?.of(it.itemNumber)
            ItemDto(
                it.itemNumber, it.description, it.quantity, it.unitPrice, it.brand, it.manufacturer, it.modelVersion, it.detailedDescription, it.floorUnitPrice, it.selected,
                portalReadAt = r?.readAt, portalState = p?.state?.name, portalUnitPrice = p?.portalUnitPrice, portalGroup = p?.group,
                portalMeEppExclusive = p?.meEppExclusive ?: false,
            )
        }),
        proposalStatus = plan.proposalStatus.name,
        proposalLogJson = json.encodeToString(ListSerializer(String.serializer()), plan.proposalLog.takeLast(MAX_LOG_LINES)),
        bidJson = json.encodeToString(BidDto.serializer(), plan.bid.let {
            BidDto(it.mode.name, it.strategy.name, it.minDecrement, it.reductionValue, it.ownIntervalSeconds, it.afterBestSeconds, it.maxBids, it.bidOnTotal)
        }),
        bidArmedAt = plan.bidArmedAt,
        sessionAt = plan.sessionAt,
        liveSessionId = plan.liveSessionId,
        updatedAt = plan.updatedAt,
    )

    fun decode(e: PortalRobotPlanEntity): PortalRobotPlan {
        val dtos = json.decodeFromString(ListSerializer(ItemDto.serializer()), e.itemsJson)
        val items = dtos.map {
            ProposalItemPlan(it.itemNumber, it.description, it.quantity, it.unitPrice, it.brand, it.manufacturer, it.modelVersion, it.detailedDescription, it.floorUnitPrice, it.selected)
        }
        val reading = dtos.mapNotNull { it.portalReadAt }.maxOrNull()?.let { at ->
            PortalProposalReading(
                readAt = at,
                items = dtos.mapNotNull { d ->
                    val state = PortalItemState.entries.firstOrNull { it.name == d.portalState } ?: return@mapNotNull null
                    PortalItemReading(d.itemNumber, state, d.portalUnitPrice, d.portalGroup, d.portalMeEppExclusive)
                },
            )
        }
        val bid = json.decodeFromString(BidDto.serializer(), e.bidJson).let { b ->
            BidRobotConfig(
                mode = BidRobotMode.entries.firstOrNull { it.name == b.mode } ?: BidRobotMode.DESLIGADO,
                strategy = BidStrategy.entries.firstOrNull { it.name == b.strategy } ?: BidStrategy.CONSERVADORA,
                minDecrement = b.minDecrement,
                reductionValue = b.reductionValue,
                // Regras do portal: nunca menos que 20 s entre lances próprios e 3 s após o melhor lance.
                ownIntervalSeconds = b.ownIntervalSeconds.coerceAtLeast(BidRobotConfig.MIN_OWN_INTERVAL_SECONDS),
                afterBestSeconds = b.afterBestSeconds.coerceAtLeast(BidRobotConfig.MIN_AFTER_BEST_SECONDS),
                maxBids = b.maxBids.coerceAtLeast(1),
                bidOnTotal = b.bidOnTotal,
            )
        }
        return PortalRobotPlan(
            companyId = e.companyId,
            tenderKey = e.tenderKey,
            items = items,
            proposalStatus = RobotProposalStatus.entries.firstOrNull { it.name == e.proposalStatus } ?: RobotProposalStatus.NAO_CONFIGURADA,
            proposalLog = runCatching { json.decodeFromString(ListSerializer(String.serializer()), e.proposalLogJson) }.getOrDefault(emptyList()),
            bid = bid,
            bidArmedAt = e.bidArmedAt,
            sessionAt = e.sessionAt,
            liveSessionId = e.liveSessionId,
            updatedAt = e.updatedAt,
            portalReading = reading,
        )
    }

    private const val MAX_LOG_LINES = 200
}

/**
 * Limpeza diária ([ListingMaintenance]): as oportunidades casadas com "minhas licitações" do Comprasnet (inclusive as
 * com robô de proposta/lance configurado, que sempre partem de uma licitação desta tabela) nunca são apagadas.
 */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object PortalRobotProtectionModule {
    @dagger.Provides
    @dagger.multibindings.IntoSet
    fun myTendersProtected(dao: PortalRobotDao): ProtectedOpportunitySource =
        ProtectedOpportunitySource { runCatching { dao.matchedOpportunityIds().toSet() }.getOrDefault(emptySet()) }
}