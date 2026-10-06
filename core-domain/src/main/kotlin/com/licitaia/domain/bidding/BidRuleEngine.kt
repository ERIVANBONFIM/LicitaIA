package com.licitaia.domain.bidding

import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.RobotMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Fotografia do estado de UMA sessão no momento da decisão. Nada aqui é compartilhado entre sessões. */
data class BidContext(
    val rule: BidRule,
    val ourLastBid: Double?,
    val bestBid: Double?,
    /** 1 = vencendo; 0 = ainda sem lance. */
    val position: Int,
    val captchaPending: Boolean = false,
    val nowMillis: Long,
    val lastBidAtMillis: Long? = null,
    /** Decremento mínimo aceito pelo portal. */
    val minDecrement: Double = 0.01,
)

sealed interface BidDecision {
    /** O robô pode enviar sozinho (somente AUTOMATICO_LIMITADO, fora das condições críticas). */
    data class Place(val value: Double, val reason: String) : BidDecision
    /** Modo MANUAL: apenas sugestão; quem envia é o operador. */
    data class Suggest(val value: Double, val reason: String) : BidDecision
    /** Exige autorização humana antes do envio. */
    data class RequestAuthorization(val value: Double, val reason: String) : BidDecision
    data class Wait(val reason: String, val retryAfterMillis: Long = 0) : BidDecision
    /** Não há lance possível sem violar o piso: o robô deve parar (PARADO_NO_PISO). */
    data class StopAtFloor(val reason: String) : BidDecision
    /** CAPTCHA/MFA pendente ou regra inválida: nenhuma ação é permitida. */
    data class Blocked(val reason: String) : BidDecision
}

/**
 * Decisão do próximo lance. Função pura e determinística.
 *
 * Garantias: nunca propõe valor abaixo do piso efetivo; respeita o intervalo mínimo;
 * para no piso; bloqueia com CAPTCHA pendente.
 */
object BidRuleEngine {

    /**
     * Piso efetivo: o piso configurado, endurecido pelo limite de perda
     * (com limite de perda 0 o robô nunca vende abaixo do custo).
     */
    fun effectiveFloor(rule: BidRule): Double =
        max(rule.floorPrice, rule.costPrice - max(rule.lossLimit, 0.0))

    /** Problemas de configuração; lista vazia = regra válida. */
    fun validateRule(rule: BidRule): List<String> = buildList {
        if (rule.initialPrice <= 0.0) add("O preço inicial deve ser maior que zero.")
        if (rule.floorPrice <= 0.0) add("O piso deve ser maior que zero.")
        if (rule.floorPrice > rule.initialPrice) add("O piso não pode ser maior que o preço inicial.")
        if (rule.costPrice < 0.0) add("O custo não pode ser negativo.")
        if (rule.reductionValue <= 0.0) add("A redução por lance deve ser maior que zero.")
        if (rule.reductionValue > 0.0 && rule.initialPrice > 0.0 && rule.reductionValue >= rule.initialPrice) {
            add("A redução por lance deve ser menor que o preço inicial.")
        }
        if (rule.lossLimit < 0.0) add("O limite de perda não pode ser negativo.")
        if (rule.minIntervalSeconds < 1) add("O intervalo mínimo deve ser de pelo menos 1 segundo.")
        if (rule.minMarginPct >= 100.0) add("A margem mínima deve ser menor que 100%.")
        if (rule.authorizationThresholdPct < 0.0 || rule.authorizationThresholdPct > 100.0) {
            add("O limiar de autorização deve ficar entre 0% e 100%.")
        }
        if (rule.floorPrice > 0.0 && rule.floorPrice < rule.costPrice - max(rule.lossLimit, 0.0)) {
            add("O piso está abaixo do custo além do limite de perda: o robô usará o piso efetivo (custo − limite de perda).")
        }
    }

    /** Erros que impedem o robô de operar (o último aviso de [validateRule] é só informativo). */
    fun isOperable(rule: BidRule): Boolean =
        rule.initialPrice > 0.0 && rule.floorPrice > 0.0 && rule.floorPrice <= rule.initialPrice &&
            rule.reductionValue > 0.0 && effectiveFloor(rule) <= rule.initialPrice

    /** Redução aplicada pela estratégia sobre o melhor lance. */
    fun stepFor(rule: BidRule, minDecrement: Double = 0.01): Double {
        val dec = max(minDecrement, 0.01)
        return when (rule.strategy) {
            BidStrategy.CONSERVADORA -> max(dec, rule.reductionValue * 0.5)
            BidStrategy.AGRESSIVA -> max(dec, rule.reductionValue * 2.0)
            BidStrategy.ACOMPANHAR_CONCORRENTE -> dec
            BidStrategy.PERSONALIZADA -> max(dec, rule.reductionValue)
        }
    }

    /**
     * Próximo valor que a estratégia enviaria, já limitado ao piso efetivo.
     * null = não existe lance válido (qualquer melhora violaria o piso).
     */
    fun proposeValue(ctx: BidContext): Double? {
        val rule = ctx.rule
        val floor = ceilCents(effectiveFloor(rule))
        val best = ctx.bestBid
        val ours = ctx.ourLastBid
        var target = when {
            best == null && ours == null -> rule.initialPrice
            best == null -> return null
            else -> min(best - stepFor(rule, ctx.minDecrement), ours ?: rule.initialPrice)
        }
        target = roundCents(target)
        if (target < floor) target = floor
        if (best != null && target > best - 0.01 + EPS) return null
        if (ours != null && target > ours - 0.01 + EPS) return null
        if (target < floor) return null
        return target
    }

    fun decide(ctx: BidContext): BidDecision {
        val rule = ctx.rule
        if (ctx.captchaPending) return BidDecision.Blocked("CAPTCHA/MFA pendente: aguardando resolução manual.")
        if (!isOperable(rule)) return BidDecision.Blocked("Regra inválida: revise preço inicial, piso e redução.")
        if (ctx.position == 1 && ctx.ourLastBid != null) return BidDecision.Wait("Vencendo — sem necessidade de novo lance.")

        val value = proposeValue(ctx)
            ?: return BidDecision.StopAtFloor("Piso atingido: cobrir o melhor lance exigiria valor abaixo do piso.")
        val floor = effectiveFloor(rule)

        if (rule.mode == RobotMode.MANUAL) {
            return BidDecision.Suggest(value, "Sugestão da estratégia ${rule.strategy.label}.")
        }

        val last = ctx.lastBidAtMillis
        if (last != null) {
            val wait = rule.minIntervalSeconds.coerceAtLeast(0) * 1000L - (ctx.nowMillis - last)
            if (wait > 0) return BidDecision.Wait("Respeitando o intervalo mínimo entre lances.", wait)
        }

        if (rule.mode == RobotMode.SUPERVISIONADO) {
            return BidDecision.RequestAuthorization(value, "Modo supervisionado: cada lance exige autorização.")
        }

        val nearFloor = value <= floor * (1.0 + rule.authorizationThresholdPct / 100.0) + EPS
        if (nearFloor) {
            return BidDecision.RequestAuthorization(
                value, "Lance a menos de ${trim(rule.authorizationThresholdPct)}% do piso.",
            )
        }
        val margin = rule.marginPct(value)
        if (margin < rule.minMarginPct) {
            return BidDecision.RequestAuthorization(
                value, "Margem de ${trim(margin)}% abaixo da mínima de ${trim(rule.minMarginPct)}%.",
            )
        }
        return BidDecision.Place(value, "Estratégia ${rule.strategy.label}.")
    }

    /**
     * Validação final de QUALQUER lance (robô, autorizado ou manual) imediatamente antes do envio.
     * Devolve o motivo do bloqueio ou null se o lance pode seguir.
     */
    fun validateBid(rule: BidRule, value: Double, bestBid: Double?, captchaPending: Boolean): String? = when {
        captchaPending -> "CAPTCHA/MFA pendente nesta sessão. Resolva no portal antes de enviar lances."
        value.isNaN() || value.isInfinite() || value <= 0.0 -> "Valor de lance inválido."
        value < effectiveFloor(rule) - EPS -> "Lance abaixo do piso configurado. Operação bloqueada."
        bestBid != null && value > bestBid - 0.01 + EPS -> "O lance deve ser inferior ao melhor lance atual."
        else -> null
    }

    private const val EPS = 1e-6

    private fun roundCents(v: Double): Double = (v * 100.0).roundToLong() / 100.0
    private fun ceilCents(v: Double): Double = ceil(v * 100.0 - EPS) / 100.0
    private fun trim(v: Double): String {
        val r = (v * 10.0).roundToLong() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().replace('.', ',')
    }
}
