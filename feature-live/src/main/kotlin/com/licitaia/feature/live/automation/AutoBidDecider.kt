package com.licitaia.feature.live.automation

import com.licitaia.domain.bidding.BidContext
import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode

/**
 * Decisão do robô de lance para UM item, a cada leitura da sala de disputa. Função pura e determinística.
 *
 * Garantias (testadas):
 * - nunca propõe lance abaixo do piso efetivo ([BidRuleEngine.effectiveFloor]) nem acima/igual ao melhor lance;
 * - respeita 20 s entre lances PRÓPRIOS e 3 s após o registro do melhor lance (regras do Compras.gov.br; valores
 *   menores na configuração são elevados a esses mínimos);
 * - respeita o teto de lances;
 * - CAPTCHA/MFA → pausa SÓ esta sessão (o usuário resolve no portal e retoma);
 * - deslogado, "Não autorizado", disputa suspensa/encerrada ou leitura ambígua → para.
 * As estratégias (Conservadora/Agressiva/Acompanhar/Personalizada) são as do motor de regras (tela Estratégias).
 */
object AutoBidDecider {

    data class Input(
        val config: BidRobotConfig,
        /** Regra da sessão (piso, custo, redução, estratégia) — a mesma de Pregões ao Vivo. */
        val rule: BidRule,
        val pageState: PageState,
        val item: BidRoomItem?,
        val nowMillis: Long,
        val lastOwnBidAt: Long?,
        /** Quando o melhor lance atual foi visto mudar pela última vez. */
        val bestChangedAt: Long?,
        val bidsSent: Int,
    )

    sealed interface Decision {
        /** Modo automático: enviar agora. */
        data class Send(val value: Double, val reason: String) : Decision
        /** Modo manual: mostrar a sugestão; o usuário toca em Enviar (ou dá o lance no portal). */
        data class Suggest(val value: Double, val reason: String) : Decision
        data class Wait(val reason: String, val retryAfterMillis: Long = 0) : Decision
        /** Pausa SÓ esta sessão até o usuário resolver (CAPTCHA/MFA). */
        data class Pause(val reason: String) : Decision
        /** Para o robô desta sessão. [finished] = disputa encerrada (não é erro). */
        data class Stop(val reason: String, val finished: Boolean = false) : Decision
    }

    fun ownIntervalMs(c: BidRobotConfig) = c.ownIntervalSeconds.coerceAtLeast(BidRobotConfig.MIN_OWN_INTERVAL_SECONDS) * 1000L
    fun afterBestMs(c: BidRobotConfig) = c.afterBestSeconds.coerceAtLeast(BidRobotConfig.MIN_AFTER_BEST_SECONDS) * 1000L

    fun decide(input: Input): Decision {
        val c = input.config
        if (c.mode == BidRobotMode.DESLIGADO) return Decision.Stop("Robô de lance desligado.")
        when (input.pageState) {
            PageState.CAPTCHA -> return Decision.Pause("CAPTCHA na página: resolva no portal e toque em Retomar.")
            PageState.MFA -> return Decision.Pause("O portal pediu código de verificação: conclua no portal e toque em Retomar.")
            PageState.LOGGED_OUT, PageState.UNAUTHORIZED -> return Decision.Stop("Sessão do Comprasnet perdida (${input.pageState.label}).")
            PageState.PORTAL_ERROR -> return Decision.Stop("Portal com erro/instável: robô parado por segurança.")
            PageState.OK -> Unit
        }
        val item = input.item ?: return Decision.Stop("Leitura ambígua: o item não foi encontrado na sala de disputa.")
        when (item.phase) {
            DisputePhase.CLOSED -> return Decision.Stop("Disputa do item ${item.itemNumber} encerrada.", finished = true)
            DisputePhase.SUSPENDED -> return Decision.Stop("Pregoeiro suspendeu a disputa do item ${item.itemNumber}.")
            DisputePhase.WAITING -> return Decision.Wait("Aguardando a abertura da fase de lances.", 5_000)
            DisputePhase.UNKNOWN -> return Decision.Stop("Leitura ambígua: fase da disputa do item ${item.itemNumber} não identificada.")
            DisputePhase.OPEN, DisputePhase.RANDOM -> Unit
        }
        if (item.ambiguous) return Decision.Stop("Leitura ambígua: mais de um “melhor lance” para o item ${item.itemNumber}.")
        val best = item.bestBid
        val ours = item.ourBid
        if (input.bidsSent >= c.maxBids) return Decision.Stop("Teto de ${c.maxBids} lance(s) atingido.")
        if (best != null && ours != null && ours <= best + EPS && (item.position == null || item.position == 1)) {
            return Decision.Wait("Nosso lance é o melhor — sem necessidade de novo lance.", 2_000)
        }
        if (item.position == 1) return Decision.Wait("Em 1º lugar.", 2_000)
        if (best == null && ours != null) return Decision.Stop("Leitura ambígua: melhor lance do item ${item.itemNumber} não identificado.")

        val ctx = BidContext(
            rule = input.rule.copy(mode = RobotMode.AUTOMATICO_LIMITADO),
            ourLastBid = ours, bestBid = best, position = item.position ?: 0,
            nowMillis = input.nowMillis, minDecrement = c.minDecrement.coerceAtLeast(0.01),
        )
        val value = BidRuleEngine.proposeValue(ctx)
            ?: return Decision.Stop("Piso atingido: cobrir ${best?.let { "R$ %.2f".format(it) } ?: "o melhor lance"} exigiria valor abaixo do piso.")
        BidRuleEngine.validateBid(input.rule, value, best, captchaPending = false)?.let { return Decision.Stop(it) }
        if (value < BidRuleEngine.effectiveFloor(input.rule) - EPS) return Decision.Stop("Lance abaixo do piso bloqueado.")

        // Intervalos do portal (também valem no modo manual: o botão Enviar respeita a mesma espera).
        input.lastOwnBidAt?.let { last ->
            val wait = ownIntervalMs(c) - (input.nowMillis - last)
            if (wait > 0) return Decision.Wait("Intervalo mínimo de ${ownIntervalMs(c) / 1000} s entre nossos lances.", wait)
        }
        input.bestChangedAt?.let { changed ->
            val wait = afterBestMs(c) - (input.nowMillis - changed)
            if (wait > 0) return Decision.Wait("Aguardando ${afterBestMs(c) / 1000} s após o melhor lance.", wait)
        }
        val reason = "Estratégia ${input.rule.strategy.label}: cobre ${best?.let { "R$ %.2f".format(it) } ?: "a abertura"} (piso R$ %.2f).".format(BidRuleEngine.effectiveFloor(input.rule))
        return if (c.mode == BidRobotMode.AUTOMATICO) Decision.Send(value, reason) else Decision.Suggest(value, reason)
    }

    private const val EPS = 1e-6
}
