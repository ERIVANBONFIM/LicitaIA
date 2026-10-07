package com.licitaia.core.data.competition

import com.licitaia.domain.competition.TrackedTender
import com.licitaia.domain.competition.TrackedTenderProvider
import com.licitaia.domain.model.PncpControlNumbers
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.pncpControlNumber
import com.licitaia.domain.portal.PortalRobotRepository
import com.licitaia.domain.repository.TenderRepository
import kotlinx.coroutines.flow.first
import java.util.Optional
import javax.inject.Inject

/** Status em que a empresa efetivamente disputou (proposta enviada em diante). */
internal val PARTICIPATION_STATUSES = setOf(TenderStatus.ENVIADA_SIMULADA, TenderStatus.EM_DISPUTA, TenderStatus.VENCIDA, TenderStatus.PERDIDA)

/** Licitações salvas da empresa (interesse, análise, participação, vencidas/perdidas) com número de controle PNCP. */
class SavedTendersProvider @Inject constructor(
    private val tenders: TenderRepository,
) : TrackedTenderProvider {
    override suspend fun trackedTenders(companyId: Long): List<TrackedTender> =
        tenders.observeTenders(companyId).first()
            .filter { it.status != TenderStatus.DESCARTADA }
            .mapNotNull { t ->
                val control = t.pncpControlNumber ?: return@mapNotNull null
                TrackedTender(
                    tenderId = t.id,
                    controlNumber = control,
                    portal = t.portal,
                    number = t.number,
                    agency = t.agency,
                    segment = t.segment,
                    objectDescription = t.objectDescription,
                    estimatedValue = t.estimatedValue,
                    sessionAt = t.sessionAt.takeIf { it > 0L } ?: t.proposalDeadline,
                    participated = t.status in PARTICIPATION_STATUSES,
                    knownOutcome = when (t.status) {
                        TenderStatus.VENCIDA -> true
                        TenderStatus.PERDIDA -> false
                        else -> null
                    },
                )
            }
}

/**
 * "Minhas licitações" lidas do Comprasnet (quando o módulo do robô de portais estiver disponível): só as que casaram com
 * um número de controle PNCP. Proposta cadastrada = participou.
 */
class PortalMyTendersProvider @Inject constructor(
    private val robot: Optional<PortalRobotRepository>,
) : TrackedTenderProvider {
    override suspend fun trackedTenders(companyId: Long): List<TrackedTender> {
        val repo = robot.orElse(null) ?: return emptyList()
        return repo.getMyTenders(companyId).mapNotNull { m ->
            val control = m.matchedOpportunityId?.let(PncpControlNumbers::fromOpportunityId) ?: return@mapNotNull null
            TrackedTender(
                tenderId = m.matchedTenderId,
                controlNumber = control,
                portal = m.portal.takeIf { it != Portal.PNCP } ?: Portal.COMPRAS_GOV,
                number = "${m.number}/${m.year}",
                agency = "UASG ${m.uasg}",
                segment = Segment.PERSONALIZADO,
                objectDescription = m.objectDescription,
                estimatedValue = 0.0,
                sessionAt = m.openingAt ?: 0L,
                participated = m.hasProposal,
            )
        }
    }
}
