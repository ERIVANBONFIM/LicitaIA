package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidStrategyConfigTest {

    @Test
    fun `codec preserva todos os campos`() {
        val c = BidStrategyConfig(
            strategy = BidStrategy.ACOMPANHAR_CONCORRENTE, decrementMode = DecrementMode.PERCENTUAL,
            decrementValue = 2.5, decrementPct = 0.75, minMarginPct = 8.0, reactOnlyWhenLosingFirst = false,
            finalBidEnabled = true, finalBidSecondsBefore = 7, minIntervalSeconds = 25, authorizationThresholdPct = 3.0,
            updatedAt = 1_700_000_000_000, updatedBy = "Ana = Diretoria\nLinha",
        )
        val decoded = BidStrategyConfigCodec.decode(BidStrategyConfigCodec.encode(c))
        assertEquals(c.copy(updatedBy = "Ana   Diretoria Linha"), decoded)
    }

    @Test
    fun `texto vazio ou corrompido cai no padrao`() {
        assertEquals(BidStrategyConfig(), BidStrategyConfigCodec.decode(null))
        val partial = BidStrategyConfigCodec.decode("strategy=INEXISTENTE\nminMarginPct=abc\ndecrementValue=3.0\nlixo")
        assertEquals(BidStrategy.CONSERVADORA, partial.strategy)
        assertEquals(5.0, partial.minMarginPct, 0.0)
        assertEquals(3.0, partial.decrementValue, 0.0)
    }

    @Test
    fun `validacao e decremento em valor ou percentual`() {
        assertTrue(BidStrategyConfig().isValid)
        assertFalse(BidStrategyConfig(decrementValue = 0.0).isValid)
        assertFalse(BidStrategyConfig(decrementMode = DecrementMode.PERCENTUAL, decrementPct = 25.0).isValid)
        assertFalse(BidStrategyConfig(finalBidEnabled = true, finalBidSecondsBefore = 1).isValid)
        assertFalse(BidStrategyConfig(minMarginPct = -1.0).isValid)
        assertEquals(2.0, BidStrategyConfig(decrementValue = 2.0).decrementFor(1_000.0), 0.0)
        assertEquals(5.0, BidStrategyConfig(decrementMode = DecrementMode.PERCENTUAL, decrementPct = 0.5).decrementFor(1_000.0), 0.0001)
    }
}
