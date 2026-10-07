package com.licitaia.feature.live.automation

import com.licitaia.domain.model.Portal
import com.licitaia.feature.live.web.PortalWebPolicy
import kotlinx.coroutines.delay

/** Estado geral da página (pura). Heurística por URL + texto visível + indicadores de CAPTCHA/código. */
enum class PageState(val label: String, val blocking: Boolean) {
    OK("Página carregada", false),
    LOGGED_OUT("Sessão do Comprasnet encerrada", true),
    UNAUTHORIZED("Portal respondeu \"Não autorizado\"", true),
    CAPTCHA("CAPTCHA na página", true),
    MFA("Código de verificação (MFA) na página", true),
    PORTAL_ERROR("Portal fora do ar / erro do servidor", true),
}

/** Fase da disputa lida no texto de um item/página (heurística, a validar nas telas reais). */
enum class DisputePhase(val label: String) {
    OPEN("Fase de lances aberta"),
    RANDOM("Tempo aleatório / iminência de encerramento"),
    WAITING("Aguardando abertura"),
    SUSPENDED("Suspensa pelo pregoeiro"),
    CLOSED("Encerrada"),
    UNKNOWN("Não identificada"),
}

object PortalPageClassifier {
    private val unauthorized = listOf("nao autorizado")
    private val unauthorizedCtx = listOf("sessao pode ter expirado", "efetuar login", "permissoes nao permitem")
    private val mfa = listOf("codigo de verificacao", "codigo de seguranca", "autenticacao em duas etapas", "verificacao em duas etapas", "informe o codigo enviado")
    private val serverError = listOf("service unavailable", "servico indisponivel", "erro 503", "http error 503", "502 bad gateway", "gateway time-out")

    fun classify(portal: Portal, probe: PageProbe): PageState {
        val url = probe.url
        if (url.isNotBlank() && (PortalWebPolicy.isLoginPage(portal, url) || PortalWebPolicy.isPublicLanding(portal, url))) return PageState.LOGGED_OUT
        val t = TextNorm.norm(probe.text)
        val all = (listOf(t) + probe.dialogs.map(TextNorm::norm)).joinToString(" ")
        if (unauthorized.any { all.contains(it) } && unauthorizedCtx.any { all.contains(it) }) return PageState.UNAUTHORIZED
        if (probe.hasCaptcha) return PageState.CAPTCHA
        if (probe.hasOtpField || mfa.any { all.contains(it) }) return PageState.MFA
        if (serverError.any { all.contains(it) } && t.length < 2_000) return PageState.PORTAL_ERROR
        return PageState.OK
    }

    /** Fase da disputa num trecho de texto (linha do item ou página). Ordem: encerrada/suspensa vencem "aberta". */
    fun phase(text: String): DisputePhase {
        val t = TextNorm.norm(text)
        return when {
            Regex("""\b(encerrad[oa]s?|disputa finalizada|fase de lances (foi )?encerrada|aguardando (o )?julgamento|em julgamento|em habilitacao|cancelad[oa]|fracassad[oa]|desert[oa])\b""").containsMatchIn(t) -> DisputePhase.CLOSED
            Regex("""\b(suspens[oa]s?|suspensao|sessao suspensa|disputa suspensa)\b""").containsMatchIn(t) -> DisputePhase.SUSPENDED
            Regex("""\b(tempo aleatorio|iminencia|encerramento iminente|prorrogacao automatica|em prorrogacao)\b""").containsMatchIn(t) -> DisputePhase.RANDOM
            Regex("""\b(em disputa|aberto para lances|aberta para lances|fase de lances|disputa aberta|em lances|enviar lance|registrar lance|dar lance)\b""").containsMatchIn(t) -> DisputePhase.OPEN
            Regex("""\b(aguardando (a )?abertura|aguardando inicio|nao iniciad[oa]|agendad[oa]|aguardando disputa)\b""").containsMatchIn(t) -> DisputePhase.WAITING
            else -> DisputePhase.UNKNOWN
        }
    }
}

/** Resultado de um passo do motor. */
sealed interface StepOutcome {
    val ok: Boolean get() = this is Ok

    data class Ok(val find: FindResult, val value: String? = null, val usedLearned: Boolean = false) : StepOutcome
    data class NotFound(val target: Target, val state: PageState) : StepOutcome
    data class Ambiguous(val target: Target, val count: Int) : StepOutcome
    data class Blocked(val target: Target?, val state: PageState) : StepOutcome
    data class Failed(val target: Target, val message: String) : StepOutcome

    /** Texto curto para log/tela (PT-BR). */
    fun describe(): String = when (this) {
        is Ok -> "ok"
        is NotFound -> "não encontrei “${target.label}”" + if (state != PageState.OK) " (${state.label})" else ""
        is Ambiguous -> "“${target.label}” ambíguo: $count elementos iguais na página"
        is Blocked -> state.label + (target?.let { " ao procurar “${it.label}”" } ?: "")
        is Failed -> "“${target.label}”: $message"
    }
}

/**
 * Motor de passos declarativos sobre um [PageDriver] (lógica pura + corrotinas; testável com driver falso).
 *
 * Cada passo: tenta o seletor do mapa aprendido (se houver) e depois os candidatos do alvo, repetindo até o
 * tempo-limite do alvo (a SPA monta a tela depois da navegação). Achou por texto/rótulo → aprende o CSS estável.
 * Seletor aprendido que achou mais de um elemento num alvo crítico é esquecido e a busca segue pelos candidatos.
 * No tempo-limite, classifica a página ([PortalPageClassifier]) para diferenciar "não achei" de "deslogado/CAPTCHA".
 */
class PortalAutomationRunner(
    private val driver: PageDriver,
    private val learned: LearnedSelectors,
    private val portal: Portal = Portal.COMPRAS_GOV,
    private val onLearned: suspend () -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val pollMs: Long = 600,
) {
    data class Located(val find: FindResult, val query: ElementQuery, val usedLearned: Boolean)

    /** Procura o alvo até o tempo-limite. Devolve [Located] ou o motivo da falha. */
    suspend fun locate(target: Target, timeoutMs: Long = target.timeoutMs): Pair<Located?, StepOutcome?> {
        val deadline = clock() + timeoutMs
        var skipLearned = false
        while (true) {
            val page = LearnedSelectors.pageKey(driver.currentUrl())
            val learnedCss = if (skipLearned) null else learned.get(page, target.key)
            val locators = listOfNotNull(learnedCss?.let { Locator(LocatorKind.CSS, it) }) + target.candidates
            val query = ElementQuery(target.kind, locators, target.scopeText, target.critical)
            val r = driver.find(query)
            if (r.found && r.locatorIndex in locators.indices) {
                val usedLearned = learnedCss != null && r.locatorIndex == 0
                if (target.critical && r.count > 1) {
                    if (usedLearned) {
                        learned.forget(page, target.key)
                        skipLearned = true
                        continue
                    }
                    return null to StepOutcome.Ambiguous(target, r.count)
                }
                val winner = locators[r.locatorIndex]
                if (!usedLearned && winner.kind != LocatorKind.CSS && r.count == 1 && LearnedSelectors.isStableCss(r.css)) {
                    learned.learn(page, target.key, r.css!!, clock())
                    runCatching { onLearned() }
                }
                return Located(r, ElementQuery(target.kind, listOf(winner), target.scopeText, target.critical), usedLearned) to null
            }
            if (learnedCss != null && !skipLearned) {
                // O seletor aprendido não vale mais nesta página: segue só com os candidatos (e esquece se eles acharem).
                skipLearned = true
            }
            if (clock() >= deadline) {
                val state = PortalPageClassifier.classify(portal, driver.probe())
                return null to if (state.blocking) StepOutcome.Blocked(target, state) else StepOutcome.NotFound(target, state)
            }
            sleep(pollMs)
        }
    }

    suspend fun waitFor(target: Target, timeoutMs: Long = target.timeoutMs): StepOutcome {
        val (loc, fail) = locate(target, timeoutMs)
        return fail ?: StepOutcome.Ok(loc!!.find, usedLearned = loc.usedLearned)
    }

    /** Espera o PRIMEIRO de vários alvos aparecer; devolve o índice ou -1 no tempo-limite. */
    suspend fun waitForAny(targets: List<Target>, timeoutMs: Long): Int {
        val deadline = clock() + timeoutMs
        while (true) {
            targets.forEachIndexed { i, t ->
                val (loc, _) = locate(t, timeoutMs = 0)
                if (loc != null) return i
            }
            if (clock() >= deadline) return -1
            sleep(pollMs)
        }
    }

    suspend fun click(target: Target): StepOutcome {
        val (loc, fail) = locate(target)
        if (fail != null) return fail
        val r = driver.click(loc!!.query)
        return when {
            r.ok -> StepOutcome.Ok(r.find.takeIf { it.found } ?: loc.find, usedLearned = loc.usedLearned)
            r.error == "ambiguous" -> StepOutcome.Ambiguous(target, r.find.count)
            else -> StepOutcome.Failed(target, r.error ?: "clique não executado")
        }
    }

    /**
     * Preenche e confere lendo de volta. [matches] compara o valor lido com o pretendido (padrão: texto normalizado
     * igual, ignorando espaços). Falha se o campo não aceitou o valor.
     */
    suspend fun fill(target: Target, value: String, matches: (expected: String, actual: String?) -> Boolean = ValueMatch::text): StepOutcome {
        val (loc, fail) = locate(target)
        if (fail != null) return fail
        val r = driver.fill(loc!!.query, value)
        if (!r.ok) return if (r.error == "ambiguous") StepOutcome.Ambiguous(target, r.find.count) else StepOutcome.Failed(target, r.error ?: "campo não aceitou o valor")
        if (!matches(value, r.readBack)) return StepOutcome.Failed(target, "o campo ficou com “${r.readBack.orEmpty().take(60)}” em vez de “${value.take(60)}”")
        return StepOutcome.Ok(r.find.takeIf { it.found } ?: loc.find, r.readBack, loc.usedLearned)
    }

    suspend fun read(target: Target): StepOutcome {
        val (loc, fail) = locate(target)
        if (fail != null) return fail
        return StepOutcome.Ok(loc!!.find, driver.read(loc.query), loc.usedLearned)
    }

    suspend fun pageState(): Pair<PageState, PageProbe> {
        val probe = driver.probe()
        return PortalPageClassifier.classify(portal, probe) to probe
    }

    companion object {
        fun sameText(expected: String, actual: String?): Boolean = ValueMatch.text(expected, actual)
        fun sameNumber(expected: String, actual: String?): Boolean = ValueMatch.number(expected, actual)
    }
}

/** Comparações de conferência (valor pretendido × lido de volta). */
object ValueMatch {
    fun text(expected: String, actual: String?): Boolean =
        TextNorm.norm(expected).replace(" ", "") == TextNorm.norm(actual).replace(" ", "")

    /** Moeda/quantidade: "1.234,56" == "1234,56" == "R$ 1.234,56" (tolerância de meio centavo). */
    fun number(expected: String, actual: String?): Boolean {
        val a = TextNorm.parseMoney(expected) ?: return false
        val b = TextNorm.parseMoney(actual) ?: return false
        return kotlin.math.abs(a - b) < 0.005
    }
}
