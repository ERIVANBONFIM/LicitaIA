package com.licitaia.core.data.repository

import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.portal.RobotProposalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalRobotRepositoryTest {
    @Test fun planRoundTripAndPortalMinimumIntervals() {
        val plan = PortalRobotPlan(
            companyId = 3, tenderKey = "160123-90005-2026",
            items = listOf(ProposalItemPlan(1, "Roteador", 2.0, 1500.0, "Marca", "Fab", "M1", "Desc", 1200.0)),
            proposalStatus = RobotProposalStatus.PRONTA, proposalLog = listOf("a", "b"),
            bid = BidRobotConfig(BidRobotMode.AUTOMATICO, BidStrategy.AGRESSIVA, 0.5, 5.0, ownIntervalSeconds = 20, afterBestSeconds = 3, maxBids = 12, bidOnTotal = true),
            bidArmedAt = 99, sessionAt = 1000, liveSessionId = "ls-1", updatedAt = 5,
        )
        assertEquals(plan, PortalRobotPlanCodec.decode(PortalRobotPlanCodec.encode(plan)))
        // Valor gravado abaixo das regras do portal (20 s / 3 s) é elevado ao mínimo ao ler.
        val weak = PortalRobotPlanCodec.encode(plan).let { it.copy(bidJson = it.bidJson.replace("\"ownIntervalSeconds\":20", "\"ownIntervalSeconds\":2").replace("\"afterBestSeconds\":3", "\"afterBestSeconds\":0")) }
        val decoded = PortalRobotPlanCodec.decode(weak).bid
        assertEquals(20, decoded.ownIntervalSeconds)
        assertEquals(3, decoded.afterBestSeconds)
    }

    @Test fun itemSelectionRoundTripAndOldPlansParticipateInAllItems() {
        val plan = PortalRobotPlan(
            companyId = 3, tenderKey = "160192-00048-2026",
            items = listOf(ProposalItemPlan(1, quantity = 12.0, unitPrice = 209.25), ProposalItemPlan(7, quantity = 1.0, unitPrice = 0.0, selected = false)),
        )
        val encoded = PortalRobotPlanCodec.encode(plan)
        assertEquals(listOf(true, false), PortalRobotPlanCodec.decode(encoded).items.map { it.selected })
        // Plano gravado antes do campo "selected": todos os itens participam.
        val old = encoded.copy(itemsJson = encoded.itemsJson.replace(",\"selected\":true", "").replace(",\"selected\":false", ""))
        assertEquals(listOf(true, true), PortalRobotPlanCodec.decode(old).items.map { it.selected })
    }

    /** “Ler situação no portal”: a leitura (com data/hora) fica no JSON dos itens, sem mudar o schema. */
    @Test fun portalReadingRoundTripInItemsJson() {
        val reading = com.licitaia.domain.portal.PortalProposalReading(
            readAt = 1_700_000_000_000,
            items = listOf(
                com.licitaia.domain.portal.PortalItemReading(1, com.licitaia.domain.portal.PortalItemState.LANCADO, 209.25, "GRUPO 1"),
                com.licitaia.domain.portal.PortalItemReading(11, com.licitaia.domain.portal.PortalItemState.NAO_CADASTRADO, null, "GRUPO 1"),
            ),
        )
        val plan = PortalRobotPlan(
            companyId = 3, tenderKey = "160192-00048-2026",
            items = listOf(ProposalItemPlan(1, quantity = 12.0, unitPrice = 209.25), ProposalItemPlan(11, quantity = 12.0, unitPrice = 209.25), ProposalItemPlan(99, quantity = 1.0, unitPrice = 1.0)),
            portalReading = reading,
        )
        val back = PortalRobotPlanCodec.decode(PortalRobotPlanCodec.encode(plan))
        assertEquals(reading, back.portalReading)
        assertEquals(209.25, back.portalReading!!.of(1)!!.portalUnitPrice!!, 1e-9)
        // Sem leitura: nada muda.
        assertEquals(null, PortalRobotPlanCodec.decode(PortalRobotPlanCodec.encode(plan.copy(portalReading = null))).portalReading)
    }

    @Test fun mergeKeepsFirstSeenSourcesAndProposalFlag() {
        val old = PortalMyTender(1, "k", uasg = "160123", number = "1", year = 2026, modality = "Pregão", objectDescription = "Objeto antigo",
            openingAt = 10, situation = "", hasProposal = true, sources = setOf(PortalMyTender.SOURCE_PARTICIPOU), matchedTenderId = 9, firstSeenAt = 1, updatedAt = 1)
        val new = old.copy(objectDescription = "", openingAt = null, hasProposal = false, sources = setOf(PortalMyTender.SOURCE_ANDAMENTO), matchedTenderId = null, firstSeenAt = 50, updatedAt = 50)
        val m = PortalRobotMerge.merge(old, new)
        assertEquals("Objeto antigo", m.objectDescription)
        assertEquals(10L, m.openingAt)
        assertTrue(m.hasProposal)
        assertEquals(setOf(PortalMyTender.SOURCE_PARTICIPOU, PortalMyTender.SOURCE_ANDAMENTO), m.sources)
        assertEquals(1L, m.firstSeenAt)
        assertEquals(9L, m.matchedTenderId)
    }
}
