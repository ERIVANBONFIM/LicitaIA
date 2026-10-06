package com.licitaia.connector.mock

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.LiveSessionHandle
import com.licitaia.connector.api.PortalBidState
import com.licitaia.connector.api.PortalLiveEvent
import com.licitaia.connector.api.PortalMessage
import com.licitaia.domain.model.LiveSessionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Disputa mock de UMA sessão. Todo o estado (melhor lance, concorrentes, cronômetro, CAPTCHA,
 * gerador aleatório, fila de eventos e corrotina) pertence a esta instância — nada é compartilhado
 * com outras sessões, nem do mesmo portal.
 */
internal class MockLiveSession(
    override val sessionId: String,
    private val spec: LiveSessionSpec,
    private val profile: PortalProfile,
    private val onClosed: (MockLiveSession) -> Unit,
) : LiveSessionHandle {

    private class Competitor(
        val alias: String,
        val floor: Double,
        val step: Double,
        val bidChancePerSecond: Double,
        var lastBid: Double? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<PortalLiveEvent>(Channel.UNLIMITED)
    private val random = Random(sessionId.hashCode().toLong() * 31 + System.nanoTime())
    private val lock = Any()

    // --- estado protegido por [lock]
    private val reference = spec.rule.initialPrice
    private val competitors: List<Competitor> = List(spec.competitors.coerceIn(1, 12)) { i ->
        Competitor(
            alias = "Fornecedor ${(i + 2).toString().padStart(2, '0')}",
            floor = reference * random.nextDouble(0.78, 0.96),
            step = reference * random.nextDouble(0.0010, 0.0042),
            bidChancePerSecond = 1.0 / (random.nextDouble(20.0, 60.0) * profile.pace),
        )
    }
    private var bestBid: Double? = null
    private var bestHolder: String? = null
    private var ourLastBid: Double? = null
    private var remaining: Int = random.nextInt(profile.durationSeconds.first, profile.durationSeconds.last + 1)
    private var captchaPending = false
    private var closed = false
    private val messages = ArrayList<PortalMessage>()

    private val producer = scope.launch(start = CoroutineStart.LAZY) { runAuction() }

    /** Fila própria desta sessão; a disputa mock só começa quando alguém passa a coletar. */
    override val events: Flow<PortalLiveEvent> = queue.receiveAsFlow().onStart { producer.start() }

    override suspend fun close() {
        shutdown()
        onClosed(this)
    }

    fun shutdown() {
        synchronized(lock) { closed = true }
        queue.close()
        scope.cancel()
    }

    private suspend fun runAuction() {
        delay(1200)
        var second = 0
        val captchaAt = if (profile.demoCaptcha) random.nextInt(45, 76) else -1
        var nextMessageAt = random.nextInt(profile.messageEverySeconds.first, profile.messageEverySeconds.last + 1)
        while (scope.isActive) {
            delay(1000)
            second++
            val out = ArrayList<PortalLiveEvent>(4)
            var finished = false
            synchronized(lock) {
                if (closed) return
                remaining = max(0, remaining - 1)

                for (c in competitors) {
                    if (bestHolder == c.alias) continue
                    if (random.nextDouble() >= c.bidChancePerSecond) continue
                    val base = bestBid ?: (reference * random.nextDouble(0.985, 1.0) + c.step)
                    val value = cents(base - c.step * random.nextDouble(0.6, 1.4))
                    val best = bestBid
                    if (value < c.floor || (best != null && value > best - 0.01)) continue
                    c.lastBid = value
                    bestBid = value
                    bestHolder = c.alias
                    // Prorrogação automática: lance nos 2 minutos finais reabre a janela.
                    if (remaining in 1 until 120) remaining = 120
                    out += PortalLiveEvent.CompetitorBid(c.alias, value)
                }

                val spontaneous = profile.captchaChancePerSecond > 0 && random.nextDouble() < profile.captchaChancePerSecond
                if (!captchaPending && (second == captchaAt || spontaneous)) {
                    captchaPending = true
                    out += PortalLiveEvent.CaptchaRequired
                }

                if (second >= nextMessageAt && remaining > 30) {
                    nextMessageAt = second + random.nextInt(profile.messageEverySeconds.first, profile.messageEverySeconds.last + 1)
                    val message = buildMessage()
                    messages += message
                    out += PortalLiveEvent.Message(message)
                }

                out += PortalLiveEvent.TimerTick(remaining)
                if (remaining <= 0) {
                    closed = true
                    finished = true
                    out += PortalLiveEvent.Closed(bestHolder, bestBid)
                }
            }
            out.forEach { queue.trySend(it) }
            if (finished) {
                // A sessão encerrada continua consultável até o close() explícito.
                queue.close()
                return
            }
        }
    }

    private fun buildMessage(): PortalMessage {
        val now = System.currentTimeMillis()
        val item = spec.itemLabel.substringBefore(" —").ifBlank { "o item" }
        return when (random.nextInt(6)) {
            0 -> PortalMessage(
                profile.auctioneer,
                "Sr. licitante, favor confirmar pelo chat, em até 10 minutos, a exequibilidade do último lance ofertado para $item.",
                now, urgent = true, responseDeadline = now + 10 * 60_000L,
            )
            1 -> PortalMessage(
                profile.auctioneer,
                "Atenção: o licitante melhor classificado deverá enviar a proposta readequada em até 2 horas após o encerramento da etapa de lances.",
                now, urgent = true, responseDeadline = now + 2 * 60 * 60_000L,
            )
            2 -> PortalMessage(profile.auctioneer, "Senhores licitantes, a etapa de lances de $item segue aberta. Mantenham-se conectados.", now, urgent = false)
            3 -> PortalMessage(profile.auctioneer, "Lembramos que lances manifestamente inexequíveis poderão ser objeto de diligência, conforme o edital.", now, urgent = false)
            4 -> PortalMessage(profile.auctioneer, "Informamos que a documentação de habilitação será analisada logo após a fase de lances.", now, urgent = false)
            else -> PortalMessage("Sistema", "A sessão pública do pregão nº ${spec.tenderNumber} permanece em andamento.", now, urgent = false)
        }
    }

    fun submitOurBid(itemLabel: String, value: Double): BidSubmission = synchronized(lock) {
        when {
            closed -> BidSubmission.Rejected("A disputa deste item já foi encerrada.")
            itemLabel != spec.itemLabel -> BidSubmission.Rejected("Item divergente do configurado para esta sessão.")
            captchaPending -> BidSubmission.CaptchaRequired
            value.isNaN() || value <= 0.0 -> BidSubmission.Rejected("Valor de lance inválido.")
            bestBid?.let { value > it - 0.01 + 1e-6 } == true -> BidSubmission.Rejected("O lance deve ser inferior ao melhor lance atual.")
            else -> {
                val accepted = cents(value)
                ourLastBid = accepted
                bestBid = accepted
                bestHolder = OUR_ALIAS
                if (remaining in 1 until 120) remaining = 120
                BidSubmission.Accepted(accepted, position())
            }
        }
    }

    fun snapshot(): PortalBidState = synchronized(lock) {
        PortalBidState(
            sessionId = sessionId,
            itemLabel = spec.itemLabel,
            bestBid = bestBid,
            ourLastBid = ourLastBid,
            ourPosition = position(),
            competitors = competitors.size,
            remainingSeconds = remaining,
            captchaPending = captchaPending,
            closed = closed,
        )
    }

    fun messagesSnapshot(): List<PortalMessage> = synchronized(lock) { messages.toList() }

    fun forceCaptcha(): Boolean {
        synchronized(lock) {
            if (closed || captchaPending) return !closed
            captchaPending = true
        }
        queue.trySend(PortalLiveEvent.CaptchaRequired)
        return true
    }

    fun clearCaptcha() {
        synchronized(lock) { captchaPending = false }
    }

    fun restore(best: Double?, ours: Double?, remainingSeconds: Int?, captcha: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        ourLastBid = ours
        bestBid = best
        bestHolder = when {
            best == null -> null
            ours != null && ours <= best + 0.001 -> OUR_ALIAS
            else -> competitors.first().also { it.lastBid = best }.alias
        }
        if (remainingSeconds != null && remainingSeconds > 0) remaining = max(remainingSeconds, 120)
        captchaPending = captcha
    }

    /** 1 = vencendo; 0 = ainda sem lance. Chamar com [lock] adquirido. */
    private fun position(): Int {
        val ours = ourLastBid ?: return 0
        return 1 + competitors.count { c -> c.lastBid?.let { it < ours } == true }
    }

    private fun cents(v: Double): Double = (v * 100.0).roundToLong() / 100.0

    private companion object {
        const val OUR_ALIAS = "__nossa_empresa__"
    }
}
