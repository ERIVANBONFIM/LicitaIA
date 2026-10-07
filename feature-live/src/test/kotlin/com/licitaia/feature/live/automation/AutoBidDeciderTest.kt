package com.licitaia.feature.live.automation

import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.ProposalItemPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBidDeciderTest {
    private val item = ProposalItemPlan(itemNumber = 1, quantity = 1.0, unitPrice = 1_000.0, floorUnitPrice = 900.0)
    private val auto = BidRobotConfig(mode = BidRobotMode.AUTOMATICO, strategy = BidStrategy.PERSONALIZADA, minDecrement = 0.01, reductionValue = 10.0)
    private val t0 = 1_000_000L

    private fun input(
        room: BidRoomItem?,
        config: BidRobotConfig = auto,
        state: PageState = PageState.OK,
        now: Long = t0,
        lastOwn: Long? = null,
        bestChanged: Long? = null,
        sent: Int = 0,
    ) = AutoBidDecider.Input(config, RobotPlanRules.ruleFor(item, config), state, room, now, lastOwn, bestChanged, sent)

    private fun open(best: Double?, ours: Double? = null, position: Int? = null) = BidRoomItem(1, best, ours, position, DisputePhase.OPEN)

    @Test fun neverBidsBelowFloor() {
        // Melhor lance já no piso: cobrir exigiria ficar abaixo → para (nunca envia).
        val d = AutoBidDecider.decide(input(open(best = 900.0, ours = 950.0, position = 2)))
        assertTrue(d is AutoBidDecider.Decision.Stop)
        // Melhor lance logo acima do piso: o lance proposto é limitado ao piso, nunca menor.
        val d2 = AutoBidDecider.decide(input(open(best = 905.0, ours = 950.0, position = 2)))
        val v = (d2 as AutoBidDecider.Decision.Send).value
        assertTrue(v >= 900.0)
        assertTrue(v < 905.0)
    }

    @Test fun neverBelowFloorAcrossManyReadings() {
        var best = 1_000.0
        repeat(200) { i ->
            best -= 3.7
            val d = AutoBidDecider.decide(input(open(best = best, ours = best + 5, position = 2), now = t0 + i * 60_000L))
            if (d is AutoBidDecider.Decision.Send) {
                assertTrue("lance ${d.value} abaixo do piso", d.value >= BidRuleEngine.effectiveFloor(RobotPlanRules.ruleFor(item, auto)) - 1e-9)
                assertTrue(d.value < best)
            }
        }
    }

    @Test fun respectsTwentySecondsBetweenOwnBids() {
        val d = AutoBidDecider.decide(input(open(990.0, 995.0, 2), lastOwn = t0 - 5_000))
        assertTrue(d is AutoBidDecider.Decision.Wait)
        assertEquals(15_000L, (d as AutoBidDecider.Decision.Wait).retryAfterMillis)
        // Mesmo que a configuração diga 5 s, vale o mínimo de 20 s do portal.
        val d2 = AutoBidDecider.decide(input(open(990.0, 995.0, 2), config = auto.copy(ownIntervalSeconds = 5), lastOwn = t0 - 10_000))
        assertTrue(d2 is AutoBidDecider.Decision.Wait)
        val d3 = AutoBidDecider.decide(input(open(990.0, 995.0, 2), lastOwn = t0 - 20_000))
        assertTrue(d3 is AutoBidDecider.Decision.Send)
    }

    @Test fun waitsThreeSecondsAfterBestBid() {
        val d = AutoBidDecider.decide(input(open(990.0, 995.0, 2), bestChanged = t0 - 1_000, config = auto.copy(afterBestSeconds = 0)))
        assertTrue(d is AutoBidDecider.Decision.Wait)
        assertEquals(2_000L, (d as AutoBidDecider.Decision.Wait).retryAfterMillis)
        assertTrue(AutoBidDecider.decide(input(open(990.0, 995.0, 2), bestChanged = t0 - 3_000)) is AutoBidDecider.Decision.Send)
    }

    @Test fun captchaAndMfaPauseOnlyThisSession() {
        assertTrue(AutoBidDecider.decide(input(open(990.0), state = PageState.CAPTCHA)) is AutoBidDecider.Decision.Pause)
        assertTrue(AutoBidDecider.decide(input(open(990.0), state = PageState.MFA)) is AutoBidDecider.Decision.Pause)
    }

    @Test fun stopsOnLostSessionSuspensionAndAmbiguity() {
        assertTrue(AutoBidDecider.decide(input(open(990.0), state = PageState.LOGGED_OUT)) is AutoBidDecider.Decision.Stop)
        assertTrue(AutoBidDecider.decide(input(open(990.0), state = PageState.UNAUTHORIZED)) is AutoBidDecider.Decision.Stop)
        assertTrue(AutoBidDecider.decide(input(BidRoomItem(1, 990.0, null, null, DisputePhase.SUSPENDED))) is AutoBidDecider.Decision.Stop)
        val closed = AutoBidDecider.decide(input(BidRoomItem(1, 990.0, 990.0, 1, DisputePhase.CLOSED)))
        assertTrue(closed is AutoBidDecider.Decision.Stop && closed.finished)
        // Leitura ambígua: item não encontrado, fase não identificada, dois "melhores lances", melhor lance ausente.
        assertTrue(AutoBidDecider.decide(input(null)) is AutoBidDecider.Decision.Stop)
        assertTrue(AutoBidDecider.decide(input(BidRoomItem(1, 990.0, null, null, DisputePhase.UNKNOWN))) is AutoBidDecider.Decision.Stop)
        assertTrue(AutoBidDecider.decide(input(BidRoomItem(1, null, null, null, DisputePhase.OPEN, ambiguous = true))) is AutoBidDecider.Decision.Stop)
        assertTrue(AutoBidDecider.decide(input(open(best = null, ours = 990.0))) is AutoBidDecider.Decision.Stop)
    }

    @Test fun respectsBidCapAndWinningPosition() {
        assertTrue(AutoBidDecider.decide(input(open(990.0, 995.0, 2), sent = auto.maxBids)) is AutoBidDecider.Decision.Stop)
        assertTrue(AutoBidDecider.decide(input(open(990.0, 990.0, 1))) is AutoBidDecider.Decision.Wait)
    }

    @Test fun manualModeOnlySuggestsAndWaitingPhaseWaits() {
        val manual = auto.copy(mode = BidRobotMode.MANUAL)
        assertTrue(AutoBidDecider.decide(input(open(990.0, 995.0, 2), config = manual)) is AutoBidDecider.Decision.Suggest)
        assertTrue(AutoBidDecider.decide(input(BidRoomItem(1, null, null, null, DisputePhase.WAITING))) is AutoBidDecider.Decision.Wait)
        assertTrue(AutoBidDecider.decide(input(open(990.0), config = auto.copy(mode = BidRobotMode.DESLIGADO))) is AutoBidDecider.Decision.Stop)
    }

    @Test fun totalValueBidsScaleFloorByQuantity() {
        val qty = item.copy(quantity = 10.0)
        val c = auto.copy(bidOnTotal = true)
        val rule = RobotPlanRules.ruleFor(qty, c)
        assertEquals(9_000.0, rule.floorPrice, 1e-9)
        assertEquals(10_000.0, rule.initialPrice, 1e-9)
    }
}
