package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.RobotMode
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

enum class CompetitorBehavior(val label: String, val description: String) {
    CONSERVADOR("Conservador", "Reduções pequenas, reagem devagar e desistem cedo"),
    AGRESSIVO("Agressivo", "Reduções grandes, reagem rápido e aceitam margens baixas"),
    MISTO("Misto", "Parte dos concorrentes é agressiva, parte é conservadora"),
}

data class SimulationParams(
    val competitors: Int,
    val initialPrice: Double,
    val floorPrice: Double,
    val costPrice: Double,
    val reductionValue: Double,
    val durationSeconds: Int,
    val strategy: BidStrategy,
    val behavior: CompetitorBehavior,
    val minIntervalSeconds: Int = 5,
    /** Mesma semente + mesmos parâmetros = mesmo resultado. */
    val seed: Long = 42L,
)

data class SimulatedBid(
    val second: Int,
    val actor: String,
    val value: Double,
    val ours: Boolean,
)

enum class SimulationStopReason(val label: String) {
    VENCEU_NO_TEMPO("Tempo encerrado com a nossa empresa em 1º lugar"),
    TEMPO_ESGOTADO("Tempo encerrado fora da 1ª posição"),
    PARADO_NO_PISO("Robô parou no piso: cobrir o concorrente violaria o preço mínimo"),
    SEM_LANCES("Nenhum lance foi possível com os parâmetros informados"),
}

data class SimulationResult(
    val params: SimulationParams,
    /** Melhor lance da disputa ao final. */
    val finalPrice: Double?,
    val ourFinalBid: Double?,
    /** 1 = vencedor; 0 = não ofertou. */
    val position: Int,
    val marginPct: Double,
    val totalBids: Int,
    val ourBids: Int,
    val stopReason: SimulationStopReason,
    val recommendation: String,
    val series: List<SimulatedBid>,
) {
    val won: Boolean get() = position == 1
}

/** Simulador de pregão: 100% local, sem portal e determinístico por semente. */
object AuctionSimulator {

    const val OUR_ALIAS = "Nossa empresa"

    private class Competitor(
        val alias: String,
        val floor: Double,
        val step: Double,
        val reactProbability: Double,
        var lastBid: Double? = null,
    )

    fun validate(p: SimulationParams): List<String> = buildList {
        if (p.competitors !in 1..30) add("Informe de 1 a 30 concorrentes.")
        if (p.initialPrice <= 0.0) add("O preço inicial deve ser maior que zero.")
        if (p.floorPrice <= 0.0) add("O piso deve ser maior que zero.")
        if (p.floorPrice > p.initialPrice) add("O piso não pode ser maior que o preço inicial.")
        if (p.reductionValue <= 0.0) add("A redução deve ser maior que zero.")
        if (p.reductionValue >= p.initialPrice && p.initialPrice > 0.0) add("A redução deve ser menor que o preço inicial.")
        if (p.durationSeconds !in 30..7200) add("A duração deve ficar entre 30 segundos e 2 horas.")
        if (p.costPrice < 0.0) add("O custo não pode ser negativo.")
    }

    fun run(p: SimulationParams): SimulationResult {
        if (validate(p).isNotEmpty()) {
            return SimulationResult(
                p, null, null, 0, 0.0, 0, 0, SimulationStopReason.SEM_LANCES,
                "Parâmetros inválidos: revise os valores informados.", emptyList(),
            )
        }
        val rnd = Random(p.seed)
        // No simulador o piso informado é o piso efetivo e não há autorização humana.
        val rule = BidRule(
            mode = RobotMode.AUTOMATICO_LIMITADO,
            strategy = p.strategy,
            initialPrice = p.initialPrice,
            floorPrice = p.floorPrice,
            costPrice = p.costPrice,
            reductionValue = p.reductionValue,
            minMarginPct = -1.0e12,
            lossLimit = 1.0e15,
            minIntervalSeconds = p.minIntervalSeconds.coerceAtLeast(1),
            authorizationThresholdPct = 0.0,
        )
        val competitors = List(p.competitors) { index ->
            val aggressive = when (p.behavior) {
                CompetitorBehavior.AGRESSIVO -> true
                CompetitorBehavior.CONSERVADOR -> false
                CompetitorBehavior.MISTO -> index % 2 == 0
            }
            if (aggressive) {
                Competitor(
                    alias = "Concorrente ${index + 1}",
                    floor = p.initialPrice * rnd.nextDouble(0.68, 0.86),
                    step = p.reductionValue * rnd.nextDouble(1.0, 2.5),
                    reactProbability = rnd.nextDouble(0.10, 0.22),
                )
            } else {
                Competitor(
                    alias = "Concorrente ${index + 1}",
                    floor = p.initialPrice * rnd.nextDouble(0.86, 0.97),
                    step = p.reductionValue * rnd.nextDouble(0.4, 1.0),
                    reactProbability = rnd.nextDouble(0.03, 0.09),
                )
            }
        }

        val series = ArrayList<SimulatedBid>()
        var best: Double? = null
        var bestHolder: String? = null
        var ours: Double? = null
        var ourBids = 0
        var lastBidAt: Long? = null
        var stoppedAtFloor = false

        for (second in 1..p.durationSeconds) {
            for (c in competitors) {
                if (bestHolder == c.alias) continue
                if (rnd.nextDouble() >= c.reactProbability) continue
                val reference = best ?: (p.initialPrice * rnd.nextDouble(0.985, 1.0) + c.step)
                val value = cents(reference - c.step * rnd.nextDouble(0.6, 1.4))
                val currentBest = best
                if (value < c.floor || value <= 0.0) continue
                if (currentBest != null && value > currentBest - 0.01) continue
                c.lastBid = value
                best = value
                bestHolder = c.alias
                series += SimulatedBid(second, c.alias, value, ours = false)
            }

            if (!stoppedAtFloor) {
                val position = when {
                    ours == null -> 0
                    bestHolder == OUR_ALIAS -> 1
                    else -> 2
                }
                val decision = BidRuleEngine.decide(
                    BidContext(
                        rule = rule, ourLastBid = ours, bestBid = best, position = position,
                        nowMillis = second * 1000L, lastBidAtMillis = lastBidAt,
                        minDecrement = max(0.01, cents(p.reductionValue * 0.05)),
                    ),
                )
                val value = when (decision) {
                    is BidDecision.Place -> decision.value
                    is BidDecision.RequestAuthorization -> decision.value
                    is BidDecision.StopAtFloor -> { stoppedAtFloor = true; null }
                    else -> null
                }
                if (value != null && value >= p.floorPrice) {
                    ours = value
                    best = value
                    bestHolder = OUR_ALIAS
                    ourBids++
                    lastBidAt = second * 1000L
                    series += SimulatedBid(second, OUR_ALIAS, value, ours = true)
                }
            }
        }

        val ourFinal = ours
        val position = if (ourFinal == null) 0 else 1 + competitors.count { c -> c.lastBid?.let { it < ourFinal } == true }
        val margin = if (ourFinal == null) 0.0 else rule.marginPct(ourFinal)
        val reason = when {
            series.isEmpty() || ourFinal == null -> if (stoppedAtFloor) SimulationStopReason.PARADO_NO_PISO else SimulationStopReason.SEM_LANCES
            position == 1 -> SimulationStopReason.VENCEU_NO_TEMPO
            stoppedAtFloor -> SimulationStopReason.PARADO_NO_PISO
            else -> SimulationStopReason.TEMPO_ESGOTADO
        }
        return SimulationResult(
            params = p,
            finalPrice = best,
            ourFinalBid = ourFinal,
            position = position,
            marginPct = margin,
            totalBids = series.size,
            ourBids = ourBids,
            stopReason = reason,
            recommendation = recommend(p, reason, position, margin, ourFinal, best),
            series = series,
        )
    }

    private fun recommend(
        p: SimulationParams, reason: SimulationStopReason, position: Int, margin: Double, ours: Double?, best: Double?,
    ): String = when (reason) {
        SimulationStopReason.SEM_LANCES ->
            "Nenhum lance ocorreu. Revise o preço inicial, o piso e a duração da disputa."
        SimulationStopReason.PARADO_NO_PISO -> {
            val gap = if (ours != null && best != null) cents(ours - best) else null
            "O piso protegeu a margem: o robô parou em vez de vender abaixo do mínimo" +
                (if (gap != null && gap > 0) " (o vencedor ficou R$ ${fmt(gap)} abaixo do nosso último lance)." else ".") +
                " Só reduza o piso se o custo permitir; caso contrário, este cenário não compensa."
        }
        SimulationStopReason.TEMPO_ESGOTADO ->
            "O tempo acabou na ${position}ª posição com folga até o piso. Considere uma estratégia mais rápida " +
                "(Agressiva ou Acompanhar concorrente) ou um intervalo mínimo menor."
        SimulationStopReason.VENCEU_NO_TEMPO -> when {
            margin < 5.0 -> "Vitória com margem apertada (${fmt(margin)}%). Avalie subir o piso ou usar a estratégia Conservadora."
            p.strategy == BidStrategy.AGRESSIVA && margin < 15.0 ->
                "Vitória, mas a estratégia Agressiva consumiu margem. Compare com Acompanhar concorrente neste mesmo cenário."
            else -> "Vitória com margem saudável (${fmt(margin)}%). Estratégia adequada para este cenário."
        }
    }

    private fun cents(v: Double): Double = (v * 100.0).roundToLong() / 100.0
    private fun fmt(v: Double): String = String.format(java.util.Locale("pt", "BR"), "%,.2f", v)
}
