package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuctionSimulatorTest {

    private fun params(
        strategy: BidStrategy = BidStrategy.ACOMPANHAR_CONCORRENTE,
        behavior: CompetitorBehavior = CompetitorBehavior.MISTO,
        floor: Double = 150_000.0,
        seed: Long = 42,
        competitors: Int = 5,
    ) = SimulationParams(
        competitors = competitors, initialPrice = 200_000.0, floorPrice = floor, costPrice = 130_000.0,
        reductionValue = 800.0, durationSeconds = 600, strategy = strategy, behavior = behavior, seed = seed,
    )

    @Test
    fun `mesma semente produz o mesmo resultado`() {
        val a = AuctionSimulator.run(params())
        val b = AuctionSimulator.run(params())
        assertEquals(a, b)
        assertTrue(a.series.isNotEmpty())
    }

    @Test
    fun `sementes diferentes produzem disputas diferentes`() {
        assertNotEquals(AuctionSimulator.run(params(seed = 1)).series, AuctionSimulator.run(params(seed = 2)).series)
    }

    @Test
    fun `nossos lances nunca ficam abaixo do piso`() {
        for (strategy in BidStrategy.entries) for (behavior in CompetitorBehavior.entries) for (seed in 1L..40L) {
            val p = params(strategy, behavior, floor = 185_000.0, seed = seed)
            val r = AuctionSimulator.run(p)
            r.series.filter { it.ours }.forEach {
                assertTrue("lance ${it.value} abaixo do piso ($strategy/$behavior/$seed)", it.value >= p.floorPrice)
            }
            r.ourFinalBid?.let { assertTrue(it >= p.floorPrice) }
        }
    }

    @Test
    fun `contra concorrentes agressivos com piso alto o robo para no piso`() {
        val r = AuctionSimulator.run(params(behavior = CompetitorBehavior.AGRESSIVO, floor = 195_000.0, competitors = 8))
        assertEquals(SimulationStopReason.PARADO_NO_PISO, r.stopReason)
        assertTrue(r.position != 1)
    }

    @Test
    fun `serie e contadores sao consistentes`() {
        val r = AuctionSimulator.run(params())
        assertEquals(r.series.size, r.totalBids)
        assertEquals(r.series.count { it.ours }, r.ourBids)
        // a série é estritamente decrescente (todo lance cobre o anterior)
        r.series.zipWithNext().forEach { (a, b) -> assertTrue(b.value < a.value) }
        assertEquals(r.series.last().value, r.finalPrice!!, 0.0001)
    }

    @Test
    fun `parametros invalidos nao geram lances`() {
        val r = AuctionSimulator.run(params().copy(floorPrice = 300_000.0))
        assertEquals(SimulationStopReason.SEM_LANCES, r.stopReason)
        assertTrue(r.series.isEmpty())
    }
}
