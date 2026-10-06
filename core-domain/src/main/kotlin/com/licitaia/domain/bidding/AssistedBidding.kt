package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.Segment
import kotlin.math.max
import kotlin.math.min

/**
 * Regras puras do MODO ASSISTIDO de pregão: o usuário opera no portal oficial e registra aqui o que
 * fez; o app calcula margem, distância ao piso, sugestões e alertas. Nenhuma função desta classe
 * envia nada a portal algum.
 */
object AssistedBidding {

    /** Cronômetro abaixo deste valor gera alerta de fechamento iminente. */
    const val TIMER_ALERT_SECONDS = 60

    enum class AlertKind(val label: String) {
        MARGEM_ABAIXO_MINIMA("Margem abaixo da mínima"),
        PROXIMO_DO_PISO("Lance próximo do piso"),
        TEMPO_CRITICO("Fechamento iminente"),
        PERDEMOS_POSICAO("Perdemos a 1ª posição"),
    }

    /**
     * Validação do registro do NOSSO lance (já dado no portal). Caminho único: [BidRuleEngine.validateBid].
     * Bloqueia abaixo do piso efetivo, CAPTCHA pendente e valores inválidos. Não exige ser inferior ao
     * melhor lance: portais aceitam lances intermediários — o app apenas não nos coloca em 1º nesse caso.
     */
    fun validateOurBid(rule: BidRule, value: Double, captchaPending: Boolean = false): String? =
        BidRuleEngine.validateBid(rule, value, bestBid = null, captchaPending = captchaPending)

    fun validateCompetitorBid(value: Double): String? = when {
        value.isNaN() || value.isInfinite() || value <= 0.0 -> "Valor de lance inválido."
        else -> null
    }

    fun marginPct(rule: BidRule, value: Double): Double = rule.marginPct(value)

    /** Diferença absoluta (BRL) entre [value] e o piso efetivo; negativa = abaixo do piso. */
    fun distanceToFloor(rule: BidRule, value: Double): Double = value - BidRuleEngine.effectiveFloor(rule)

    /** Diferença percentual para o piso efetivo (0 = exatamente no piso). */
    fun distanceToFloorPct(rule: BidRule, value: Double): Double {
        val floor = BidRuleEngine.effectiveFloor(rule)
        return if (floor <= 0.0) 0.0 else (value - floor) / floor * 100.0
    }

    fun isNearFloor(rule: BidRule, value: Double, thresholdPct: Double = rule.authorizationThresholdPct): Boolean =
        distanceToFloorPct(rule, value) <= thresholdPct + EPS

    fun isMarginBelowMin(rule: BidRule, value: Double): Boolean = marginPct(rule, value) < rule.minMarginPct - EPS

    /** Novo estado após registrar o nosso lance (já validado). */
    fun applyOurBid(session: LiveSession, value: Double): LiveSession {
        val best = session.bestBid
        val leads = best == null || value < best - EPS
        return session.copy(
            ourLastBid = value,
            bestBid = if (best == null) value else min(best, value),
            position = if (leads) 1 else max(session.position, 2),
            status = if (session.status == LiveStatus.AGUARDANDO) LiveStatus.EM_DISPUTA else session.status,
        )
    }

    /** Novo estado após registrar o melhor lance de um concorrente. */
    fun applyCompetitorBid(session: LiveSession, value: Double): LiveSession {
        val ours = session.ourLastBid
        val beatsUs = ours == null || value < ours - EPS
        val best = session.bestBid
        return session.copy(
            bestBid = if (best == null) value else min(best, value),
            position = when {
                ours == null -> 0
                beatsUs -> max(session.position, 2)
                else -> session.position
            },
            status = if (session.status == LiveStatus.AGUARDANDO) LiveStatus.EM_DISPUTA else session.status,
        )
    }

    /**
     * Sugestão do próximo lance pela estratégia da sessão. Força modo MANUAL: a decisão é sempre
     * [BidDecision.Suggest], [BidDecision.Wait], [BidDecision.StopAtFloor] ou [BidDecision.Blocked] — nunca "enviar".
     */
    fun suggest(session: LiveSession, nowMillis: Long): BidDecision = BidRuleEngine.decide(
        BidContext(
            rule = session.rule.copy(mode = RobotMode.MANUAL),
            ourLastBid = session.ourLastBid,
            bestBid = session.bestBid,
            position = session.position,
            captchaPending = session.captchaPending,
            nowMillis = nowMillis,
        ),
    )

    /** Alertas ativos para o estado atual (sem memória: quem chama decide quando notificar). */
    fun activeAlerts(session: LiveSession): Set<AlertKind> {
        if (session.status == LiveStatus.ENCERRADA || session.status == LiveStatus.ERRO) return emptySet()
        val out = linkedSetOf<AlertKind>()
        val ours = session.ourLastBid
        if (ours != null) {
            if (isMarginBelowMin(session.rule, ours)) out += AlertKind.MARGEM_ABAIXO_MINIMA
            if (isNearFloor(session.rule, ours)) out += AlertKind.PROXIMO_DO_PISO
            if (session.position >= 2) out += AlertKind.PERDEMOS_POSICAO
        }
        val remaining = session.remainingSeconds
        if (session.timerRunning && remaining != null && remaining in 1..TIMER_ALERT_SECONDS) out += AlertKind.TEMPO_CRITICO
        return out
    }

    /** Registro de concorrência gerado ao encerrar a sessão com resultado informado pelo usuário. */
    fun closingRecord(
        session: LiveSession,
        won: Boolean,
        finalValue: Double,
        bidsCount: Int,
        segment: Segment,
        now: Long,
    ): CompetitionRecord {
        val ourFinal = if (won) finalValue else (session.ourLastBid ?: session.rule.initialPrice)
        return CompetitionRecord(
            companyId = session.companyId,
            portal = session.portal,
            tenderNumber = session.tenderNumber,
            agency = session.agency,
            segment = segment,
            objectSummary = listOf(session.itemLabel, session.objectDescription).filter { it.isNotBlank() }.joinToString(" — "),
            date = now,
            competitors = session.competitors,
            estimatedValue = session.rule.initialPrice,
            closingValue = finalValue,
            ourFinalBid = ourFinal,
            won = won,
            ourMarginPct = session.rule.marginPct(ourFinal),
            bidsCount = bidsCount,
            behavior = if (won) "Vencemos em modo assistido com ${bidsCount} lance(s) registrado(s)."
            else "Perdemos: lance vencedor ${"%.2f".format(java.util.Locale.US, finalValue)} contra nosso ${"%.2f".format(java.util.Locale.US, ourFinal)} (${bidsCount} lance(s) registrado(s)).",
        )
    }

    private const val EPS = 1e-6
}
