package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistedBiddingTest {

    private val rule = BidRule(
        mode = RobotMode.MANUAL, strategy = BidStrategy.CONSERVADORA,
        initialPrice = 100_000.0, floorPrice = 80_000.0, costPrice = 70_000.0,
        reductionValue = 1_000.0, minMarginPct = 15.0, authorizationThresholdPct = 5.0,
    )

    private fun session(
        ourLastBid: Double? = null,
        bestBid: Double? = null,
        position: Int = 0,
        status: LiveStatus = LiveStatus.EM_DISPUTA,
        remainingSeconds: Int? = null,
        timerRunning: Boolean = false,
        tenderId: Long? = null,
    ) = LiveSession(
        id = "ls-test", companyId = 7, tenderId = tenderId, portal = Portal.COMPRAS_GOV, tenderNumber = "PE 1/2026",
        agency = "Órgão", itemLabel = "Item 1", objectDescription = "Objeto", status = status, robotStatus = RobotStatus.INATIVO,
        position = position, competitors = 3, ourLastBid = ourLastBid, bestBid = bestBid, rule = rule,
        remainingSeconds = remainingSeconds, startedAt = 1L, updatedAt = 1L, timerRunning = timerRunning,
    )

    // ------------------------------------------------------------ validação de registro vs piso

    @Test
    fun `registro abaixo do piso e bloqueado com mensagem`() {
        val reason = AssistedBidding.validateOurBid(rule, 79_999.99)
        assertNotNull(reason)
        assertTrue(reason!!.contains("abaixo do piso"))
    }

    @Test
    fun `registro exatamente no piso e aceito`() {
        assertNull(AssistedBidding.validateOurBid(rule, 80_000.0))
    }

    @Test
    fun `registro acima do piso e aceito mesmo sem cobrir o melhor lance`() {
        assertNull(AssistedBidding.validateOurBid(rule, 95_000.0))
    }

    @Test
    fun `piso efetivo endurecido pelo custo quando limite de perda e zero`() {
        val r = rule.copy(floorPrice = 60_000.0) // abaixo do custo 70k
        assertEquals(70_000.0, BidRuleEngine.effectiveFloor(r), 1e-9)
        assertNotNull(AssistedBidding.validateOurBid(r, 65_000.0))
        assertNull(AssistedBidding.validateOurBid(r, 70_000.0))
    }

    @Test
    fun `valores invalidos e captcha pendente sao recusados`() {
        assertNotNull(AssistedBidding.validateOurBid(rule, 0.0))
        assertNotNull(AssistedBidding.validateOurBid(rule, Double.NaN))
        assertNotNull(AssistedBidding.validateOurBid(rule, 90_000.0, captchaPending = true))
        assertNotNull(AssistedBidding.validateCompetitorBid(-1.0))
        assertNull(AssistedBidding.validateCompetitorBid(1.0))
    }

    // ------------------------------------------------------------ margem e diferença para o piso

    @Test
    fun `margem e diferenca para o piso sao calculadas sobre o custo e o piso efetivo`() {
        assertEquals(30.0, AssistedBidding.marginPct(rule, 100_000.0), 1e-9)
        assertEquals(12.5, AssistedBidding.marginPct(rule, 80_000.0), 1e-9)
        assertEquals(10_000.0, AssistedBidding.distanceToFloor(rule, 90_000.0), 1e-9)
        assertEquals(12.5, AssistedBidding.distanceToFloorPct(rule, 90_000.0), 1e-9)
        assertEquals(0.0, AssistedBidding.distanceToFloor(rule, 80_000.0), 1e-9)
        assertEquals(-500.0, AssistedBidding.distanceToFloor(rule, 79_500.0), 1e-9)
    }

    @Test
    fun `alertas de margem minima e proximidade do piso`() {
        assertTrue(AssistedBidding.isMarginBelowMin(rule, 80_000.0)) // 12,5% < 15%
        assertFalse(AssistedBidding.isMarginBelowMin(rule, 90_000.0)) // 22,2%
        assertTrue(AssistedBidding.isNearFloor(rule, 84_000.0)) // 5% do piso
        assertFalse(AssistedBidding.isNearFloor(rule, 84_000.01))

        val s = session(ourLastBid = 82_000.0, bestBid = 82_000.0, position = 1) // margem 14,6% < 15%; 2,5% do piso
        val alerts = AssistedBidding.activeAlerts(s)
        assertTrue(AssistedBidding.AlertKind.MARGEM_ABAIXO_MINIMA in alerts)
        assertTrue(AssistedBidding.AlertKind.PROXIMO_DO_PISO in alerts)
        assertFalse(AssistedBidding.AlertKind.PERDEMOS_POSICAO in alerts)
    }

    @Test
    fun `cronometro abaixo de 60s gera alerta apenas em contagem`() {
        assertTrue(AssistedBidding.AlertKind.TEMPO_CRITICO in AssistedBidding.activeAlerts(session(remainingSeconds = 59, timerRunning = true)))
        assertFalse(AssistedBidding.AlertKind.TEMPO_CRITICO in AssistedBidding.activeAlerts(session(remainingSeconds = 59, timerRunning = false)))
        assertFalse(AssistedBidding.AlertKind.TEMPO_CRITICO in AssistedBidding.activeAlerts(session(remainingSeconds = 61, timerRunning = true)))
        assertTrue(AssistedBidding.activeAlerts(session(remainingSeconds = 10, timerRunning = true, status = LiveStatus.ENCERRADA)).isEmpty())
    }

    // ------------------------------------------------------------ transições de estado

    @Test
    fun `nosso lance abaixo do melhor assume a primeira posicao e inicia a disputa`() {
        val s = AssistedBidding.applyOurBid(session(bestBid = 95_000.0, position = 0, status = LiveStatus.AGUARDANDO), 94_000.0)
        assertEquals(1, s.position)
        assertEquals(94_000.0, s.bestBid!!, 1e-9)
        assertEquals(94_000.0, s.ourLastBid!!, 1e-9)
        assertEquals(LiveStatus.EM_DISPUTA, s.status)
    }

    @Test
    fun `nosso lance intermediario nao assume a primeira posicao`() {
        val s = AssistedBidding.applyOurBid(session(bestBid = 90_000.0, position = 3), 92_000.0)
        assertEquals(3, s.position)
        assertEquals(90_000.0, s.bestBid!!, 1e-9)
    }

    @Test
    fun `lance concorrente menor que o nosso nos tira da lideranca`() {
        val s = AssistedBidding.applyCompetitorBid(session(ourLastBid = 90_000.0, bestBid = 90_000.0, position = 1), 89_000.0)
        assertEquals(2, s.position)
        assertEquals(89_000.0, s.bestBid!!, 1e-9)
        assertTrue(AssistedBidding.AlertKind.PERDEMOS_POSICAO in AssistedBidding.activeAlerts(s))
        val still = AssistedBidding.applyCompetitorBid(session(ourLastBid = 90_000.0, bestBid = 90_000.0, position = 1), 91_000.0)
        assertEquals(1, still.position)
    }

    @Test
    fun `sugestao nunca propoe enviar e respeita o piso`() {
        val d = AssistedBidding.suggest(session(ourLastBid = 90_000.0, bestBid = 89_000.0, position = 2), 0L)
        assertTrue(d is BidDecision.Suggest)
        val value = (d as BidDecision.Suggest).value
        assertTrue(value < 89_000.0)
        assertTrue(value >= BidRuleEngine.effectiveFloor(rule))

        val floor = AssistedBidding.suggest(session(ourLastBid = 80_010.0, bestBid = 80_000.0, position = 2), 0L)
        assertTrue(floor is BidDecision.StopAtFloor)

        val winning = AssistedBidding.suggest(session(ourLastBid = 90_000.0, bestBid = 90_000.0, position = 1), 0L)
        assertTrue(winning is BidDecision.Wait)
    }

    // ------------------------------------------------------------ fechamento → registro de concorrência

    @Test
    fun `fechamento com vitoria gera registro de concorrencia coerente`() {
        val s = session(ourLastBid = 88_000.0, bestBid = 88_000.0, position = 1, tenderId = 42)
        val record = AssistedBidding.closingRecord(s, won = true, finalValue = 88_000.0, bidsCount = 4, segment = Segment.TI, now = 123L)
        assertEquals(7L, record.companyId)
        assertEquals(Portal.COMPRAS_GOV, record.portal)
        assertEquals("PE 1/2026", record.tenderNumber)
        assertTrue(record.won)
        assertEquals(88_000.0, record.closingValue, 1e-9)
        assertEquals(88_000.0, record.ourFinalBid, 1e-9)
        assertEquals(rule.marginPct(88_000.0), record.ourMarginPct, 1e-9)
        assertEquals(4, record.bidsCount)
        assertEquals(3, record.competitors)
        assertEquals(100_000.0, record.estimatedValue, 1e-9)
        assertEquals(Segment.TI, record.segment)
        assertEquals(123L, record.date)
        assertTrue(record.objectSummary.contains("Item 1"))
    }

    @Test
    fun `fechamento com derrota mantem nosso ultimo lance e o valor vencedor do concorrente`() {
        val s = session(ourLastBid = 86_000.0, bestBid = 85_000.0, position = 2)
        val record = AssistedBidding.closingRecord(s, won = false, finalValue = 85_000.0, bidsCount = 2, segment = Segment.SERVICOS, now = 1L)
        assertFalse(record.won)
        assertEquals(85_000.0, record.closingValue, 1e-9)
        assertEquals(86_000.0, record.ourFinalBid, 1e-9)
        assertEquals(rule.marginPct(86_000.0), record.ourMarginPct, 1e-9)
    }
}
