package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.RobotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BidRuleEngineTest {

    private fun rule(
        mode: RobotMode = RobotMode.AUTOMATICO_LIMITADO,
        strategy: BidStrategy = BidStrategy.PERSONALIZADA,
        initial: Double = 100_000.0,
        floor: Double = 80_000.0,
        cost: Double = 70_000.0,
        reduction: Double = 500.0,
        minMargin: Double = 5.0,
        interval: Int = 5,
        threshold: Double = 5.0,
        lossLimit: Double = 0.0,
    ) = BidRule(mode, strategy, initial, floor, cost, reduction, minMargin, lossLimit, interval, threshold)

    private fun ctx(
        rule: BidRule, ours: Double? = null, best: Double? = null, position: Int = 2,
        captcha: Boolean = false, now: Long = 100_000, lastBidAt: Long? = null,
    ) = BidContext(rule, ours, best, position, captcha, now, lastBidAt)

    private fun valueOf(d: BidDecision): Double? = when (d) {
        is BidDecision.Place -> d.value
        is BidDecision.Suggest -> d.value
        is BidDecision.RequestAuthorization -> d.value
        else -> null
    }

    @Test
    fun `nunca propoe valor abaixo do piso em contextos aleatorios`() {
        val rnd = Random(7)
        repeat(20_000) {
            val initial = rnd.nextDouble(1_000.0, 500_000.0)
            val floor = initial * rnd.nextDouble(0.3, 1.0)
            val r = rule(
                mode = RobotMode.entries[rnd.nextInt(RobotMode.entries.size)],
                strategy = BidStrategy.entries[rnd.nextInt(BidStrategy.entries.size)],
                initial = initial, floor = floor,
                cost = initial * rnd.nextDouble(0.2, 1.1),
                reduction = initial * rnd.nextDouble(0.0005, 0.2),
                lossLimit = if (rnd.nextBoolean()) 0.0 else initial * rnd.nextDouble(0.0, 0.3),
            )
            val best = if (rnd.nextInt(10) == 0) null else initial * rnd.nextDouble(0.2, 1.05)
            val ours = if (rnd.nextBoolean()) null else initial * rnd.nextDouble(0.2, 1.0)
            val value = valueOf(BidRuleEngine.decide(ctx(r, ours, best, position = rnd.nextInt(0, 4))))
            if (value != null) {
                assertTrue("valor $value abaixo do piso ${r.floorPrice}", value >= r.floorPrice)
                assertTrue("valor $value abaixo do piso efetivo", value >= BidRuleEngine.effectiveFloor(r))
                if (best != null) assertTrue("valor $value não cobre o melhor $best", value < best)
            }
        }
    }

    @Test
    fun `para no piso quando cobrir o concorrente violaria o piso`() {
        val r = rule(floor = 80_000.0)
        val d = BidRuleEngine.decide(ctx(r, ours = 80_000.0, best = 79_900.0))
        assertTrue(d is BidDecision.StopAtFloor)
        // melhor lance exatamente no piso: também não há lance válido
        assertTrue(BidRuleEngine.decide(ctx(r, ours = 81_000.0, best = 80_000.0)) is BidDecision.StopAtFloor)
    }

    @Test
    fun `limita o ultimo lance exatamente ao piso`() {
        val r = rule(floor = 80_000.0, reduction = 500.0, threshold = 0.0, minMargin = -100.0)
        val d = BidRuleEngine.decide(ctx(r, ours = 80_900.0, best = 80_200.0))
        assertEquals(80_000.0, valueOf(d)!!, 0.0001)
    }

    @Test
    fun `captcha pendente bloqueia qualquer decisao`() {
        for (mode in RobotMode.entries) {
            val d = BidRuleEngine.decide(ctx(rule(mode = mode), ours = 95_000.0, best = 94_000.0, captcha = true))
            assertTrue("modo $mode deveria bloquear", d is BidDecision.Blocked)
        }
        assertNotNull(BidRuleEngine.validateBid(rule(), 90_000.0, 95_000.0, captchaPending = true))
    }

    @Test
    fun `respeita o intervalo minimo`() {
        val r = rule(interval = 10)
        val d = BidRuleEngine.decide(ctx(r, ours = 95_000.0, best = 94_000.0, now = 20_000, lastBidAt = 15_000))
        assertTrue(d is BidDecision.Wait)
        assertEquals(5_000, (d as BidDecision.Wait).retryAfterMillis)
        val later = BidRuleEngine.decide(ctx(r, ours = 95_000.0, best = 94_000.0, now = 25_000, lastBidAt = 15_000))
        assertTrue(later is BidDecision.Place)
    }

    @Test
    fun `modo manual apenas sugere e supervisionado pede autorizacao`() {
        assertTrue(BidRuleEngine.decide(ctx(rule(mode = RobotMode.MANUAL), best = 95_000.0)) is BidDecision.Suggest)
        assertTrue(
            BidRuleEngine.decide(ctx(rule(mode = RobotMode.SUPERVISIONADO), best = 95_000.0)) is BidDecision.RequestAuthorization,
        )
    }

    @Test
    fun `automatico pede autorizacao perto do piso e com margem baixa`() {
        val nearFloor = BidRuleEngine.decide(ctx(rule(threshold = 5.0), ours = 84_500.0, best = 84_000.0))
        assertTrue(nearFloor is BidDecision.RequestAuthorization)
        val lowMargin = BidRuleEngine.decide(
            ctx(rule(cost = 90_000.0, lossLimit = 20_000.0, minMargin = 10.0, threshold = 0.0), ours = 96_000.0, best = 95_500.0),
        )
        assertTrue(lowMargin is BidDecision.RequestAuthorization)
        val free = BidRuleEngine.decide(ctx(rule(), ours = 96_000.0, best = 95_500.0))
        assertTrue(free is BidDecision.Place)
        assertEquals(95_000.0, (free as BidDecision.Place).value, 0.0001)
    }

    @Test
    fun `vencendo nao gera lance contra si mesmo`() {
        val d = BidRuleEngine.decide(ctx(rule(), ours = 95_000.0, best = 95_000.0, position = 1))
        assertTrue(d is BidDecision.Wait)
    }

    @Test
    fun `estrategias aplicam reducoes diferentes`() {
        fun v(s: BidStrategy) = valueOf(BidRuleEngine.decide(ctx(rule(strategy = s), ours = 96_000.0, best = 95_000.0)))!!
        assertEquals(94_750.0, v(BidStrategy.CONSERVADORA), 0.0001)
        assertEquals(94_000.0, v(BidStrategy.AGRESSIVA), 0.0001)
        assertEquals(94_999.99, v(BidStrategy.ACOMPANHAR_CONCORRENTE), 0.0001)
        assertEquals(94_500.0, v(BidStrategy.PERSONALIZADA), 0.0001)
    }

    @Test
    fun `limite de perda zero impede lance abaixo do custo`() {
        val r = rule(floor = 60_000.0, cost = 75_000.0, lossLimit = 0.0)
        assertEquals(75_000.0, BidRuleEngine.effectiveFloor(r), 0.0001)
        assertTrue(BidRuleEngine.decide(ctx(r, ours = 75_000.0, best = 74_000.0)) is BidDecision.StopAtFloor)
        assertNotNull(BidRuleEngine.validateBid(r, 74_000.0, null, captchaPending = false))
    }

    @Test
    fun `validacao de lance manual`() {
        val r = rule()
        assertNotNull(BidRuleEngine.validateBid(r, 79_999.99, null, false))
        assertNotNull(BidRuleEngine.validateBid(r, 90_000.0, 90_000.0, false))
        assertNull(BidRuleEngine.validateBid(r, 80_000.0, 90_000.0, false))
        assertFalse(BidRuleEngine.isOperable(rule(floor = 120_000.0)))
        assertTrue(BidRuleEngine.validateRule(rule(floor = 120_000.0)).isNotEmpty())
        assertTrue(BidRuleEngine.validateRule(rule()).isEmpty())
    }
}
