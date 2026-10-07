package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import java.net.URI

/**
 * "Compras.gov.br instável" (regras puras, sem Android).
 *
 * Fato verificado no aparelho: `loginPortal.asp?perfil=1` → "Entrar com Gov.br" → `sso.acesso.gov.br/authorize` (gov.br
 * já logado devolve o code na hora) → `/seguro/landing_sso.asp?code=…` → 302 → `loginPortal.asp?perfil=1` exibindo
 * "Operação não realizada. Tente novamente mais tarde. (503)". Acontece também no Chrome e no PC: instabilidade do portal
 * (Comunicado 42/26), não do app.
 *
 * A detecção é feita DENTRO da página por [probeScript] (devolve só true/false; nenhum texto sai dela). Com "Manter
 * sessão ativa" ligado, o [PortalKeepAliveController][com.licitaia.feature.live.keepalive.PortalKeepAliveController]
 * — o ÚNICO agendador — tenta de novo a cada [RETRY_INTERVAL_MS] (no máximo [MAX_ATTEMPTS], ≈ 1 h), sob o controle de
 * [Retry].
 */
object PortalInstability {

    /** Marcadores (normalizados como [PortalWebPolicy.normalizeText]); basta um deles. */
    val TEXT_MARKERS = listOf("operacao nao realizada", "(503)")

    /** Nova tentativa a cada 5 min… */
    const val RETRY_INTERVAL_MS = 5 * 60_000L

    /** …no máximo 12 vezes (≈ 1 h). */
    const val MAX_ATTEMPTS = 12

    const val BADGE_LABEL = "Compras.gov.br instável"
    const val USER_TEXT = "O portal recusou o login (erro 503 do Compras.gov.br). Não é problema do app; tentaremos de novo."
    /** Mesmo aviso, quando "Manter sessão ativa" está desligado (não há nova tentativa automática). */
    const val USER_TEXT_NO_RETRY = "O portal recusou o login (erro 503 do Compras.gov.br). Não é problema do app. " +
        "Ligue \"Manter sessão ativa\" para o app tentar de novo sozinho, ou toque em Tentar agora."
    const val RECOVERED_TITLE = "Compras.gov.br voltou — você está conectado"
    const val EXHAUSTED_TITLE = "Compras.gov.br continua instável — tente mais tarde"

    /** Só o Compras.gov.br tem o fluxo mapeado. */
    fun supports(portal: Portal): Boolean = portal == Portal.COMPRAS_GOV

    private fun isComprasnet(host: String?) = host == "comprasnet.gov.br" || host == "www.comprasnet.gov.br"

    private fun path(url: String): String = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("").lowercase()

    /** Página de entrada do Comprasnet (`/seguro/loginPortal.asp`), onde o 503 aparece. */
    fun isEntryLoginPage(portal: Portal, url: String): Boolean =
        supports(portal) && PortalWebPolicy.isAllowed(portal, url) && isComprasnet(PortalWebPolicy.host(url)) &&
            path(url).contains("loginportal")

    /** Retorno do gov.br para o Comprasnet (`/seguro/landing_sso.asp`). */
    fun isLandingSso(portal: Portal, url: String): Boolean =
        supports(portal) && PortalWebPolicy.isAllowed(portal, url) && isComprasnet(PortalWebPolicy.host(url)) &&
            path(url).contains("landing_sso")

    /** O texto visível tem "operação não realizada" e/ou "(503)". */
    fun textIndicatesUnstable(visibleText: String): Boolean =
        PortalWebPolicy.normalizeText(visibleText).let { t -> TEXT_MARKERS.any { t.contains(it) } }

    /**
     * A página é o 503 do portal: `loginPortal.asp` com o texto de [TEXT_MARKERS] — logo após o retorno do gov.br
     * (`landing_sso.asp`, [afterLandingSso]) ou só pelo texto. [afterLandingSso] não muda o resultado: entra na auditoria.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isUnstablePage(portal: Portal, url: String, visibleText: String, afterLandingSso: Boolean = false): Boolean =
        isEntryLoginPage(portal, url) && textIndicatesUnstable(visibleText)

    /** Versão do [isUnstablePage] para o resultado do [probeScript] (o texto não sai da página). */
    fun isUnstableProbe(portal: Portal, url: String, probeMatched: Boolean): Boolean = probeMatched && isEntryLoginPage(portal, url)

    /**
     * Script para `evaluateJavascript`: o texto visível contém algum de [TEXT_MARKERS]? Devolve só `true`/`false`
     * (mesma lógica de [textIndicatesUnstable]). Não lê formulários, inputs nem cookies.
     */
    fun probeScript(): String {
        val list = TEXT_MARKERS.joinToString(",", "[", "]") { m -> "'" + m.filter { it.isLetterOrDigit() || it == ' ' || it == '(' || it == ')' } + "'" }
        return "(function(){try{var b=document.body;if(!b)return false;" +
            "var t=String(b.innerText||b.textContent||'').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').toLowerCase().replace(/\\s+/g,' ');" +
            "var M=" + list + ";for(var i=0;i<M.length;i++){if(t.indexOf(M[i])>=0)return true;}return false;" +
            "}catch(e){return false;}})()"
    }

    fun parseProbe(raw: String?): Boolean = PortalWebPolicy.parseProbeResult(raw)

    /** Texto da auditoria (sem URLs). */
    fun auditText(afterLandingSso: Boolean): String =
        "Compras.gov.br instável: o portal recusou o login com \"Operação não realizada (503)\"" +
            (if (afterLandingSso) " logo após o retorno do gov.br" else "") + " — instabilidade do portal, não do app"

    // ------------------------------------------------------------------ agenda de novas tentativas

    /** Resultado de UMA nova tentativa (entrada oficial + login automático, se ligado). */
    enum class AttemptResult {
        /** Chegou à área logada. */
        LOGGED_IN,
        /** Sem login automático: a entrada carregou sem o 503 (o portal responde; o usuário entra). */
        AVAILABLE,
        /** O 503 apareceu de novo / erro do servidor. */
        UNSTABLE,
        /** Sem conclusão (sem rede, tempo esgotado): não conta como recuperado. */
        INCONCLUSIVE,
        /** O login automático parou por outro motivo (CAPTCHA, 2FA…): o portal respondeu; o usuário conclui. */
        STOPPED,
        /** Usuário usando o portal na tela / outra tentativa em andamento: nada foi feito. */
        BUSY,
    }

    /** O que o agendador faz depois de uma tentativa. */
    enum class AfterAttempt { CONTINUE, RECOVERED_CONNECTED, RECOVERED_AVAILABLE, STOP }

    /**
     * Estado da instabilidade de UMA empresa/portal + agenda das novas tentativas. Puro e testável; quem espera e
     * executa é o keep-alive controller (um único agendador por portal: enquanto [running], o keep-alive normal não roda).
     */
    class Retry(val maxAttempts: Int = MAX_ATTEMPTS, val intervalMs: Long = RETRY_INTERVAL_MS) {
        /** Instabilidade em aberto (badge "Compras.gov.br instável"). */
        var unstable = false
            private set
        /** Tentativas feitas neste episódio. */
        var attempts = 0
            private set
        /** Há um laço de novas tentativas ativo. */
        var running = false
            private set
        /** Esgotou as tentativas (a notificação "continua instável" já foi enviada neste episódio). */
        var exhausted = false
            private set

        /**
         * Instabilidade detectada (página 503). true = iniciar o laço agora (só com "Manter sessão ativa" ligado,
         * se não há laço ativo e se o episódio não esgotou).
         */
        fun onDetected(retryEnabled: Boolean): Boolean {
            unstable = true
            if (!retryEnabled || running || exhausted) return false
            running = true
            return true
        }

        /**
         * Antes de cada espera: o atraso até a próxima tentativa, ou null se acabou — esgotou (marca [exhausted]; o
         * chamador notifica UMA vez: [justExhausted]) ou o laço foi parado.
         */
        fun nextDelay(): Long? {
            justExhausted = false
            if (!running) return null
            if (attempts >= maxAttempts) {
                running = false
                justExhausted = !exhausted
                exhausted = true
                return null
            }
            return intervalMs
        }

        /** true só na chamada de [nextDelay] que esgotou o episódio (notificação única). */
        var justExhausted = false
            private set

        /** A espera terminou: true = executar a tentativa (conta uma); false = o laço foi parado. */
        fun beginAttempt(): Boolean {
            if (!running) return false
            attempts++
            return true
        }

        /** Resultado da tentativa → o que fazer. BUSY/INCONCLUSIVE não contam (a tentativa é devolvida). */
        fun onAttemptResult(result: AttemptResult): AfterAttempt = when (result) {
            AttemptResult.LOGGED_IN -> { reset(); AfterAttempt.RECOVERED_CONNECTED }
            AttemptResult.AVAILABLE -> { reset(); AfterAttempt.RECOVERED_AVAILABLE }
            AttemptResult.STOPPED -> { reset(); AfterAttempt.STOP }
            AttemptResult.UNSTABLE -> { unstable = true; AfterAttempt.CONTINUE }
            AttemptResult.BUSY, AttemptResult.INCONCLUSIVE -> { if (attempts > 0) attempts--; AfterAttempt.CONTINUE }
        }

        /**
         * Chegou à área logada por qualquer caminho (tela, login manual, keep-alive): true = havia instabilidade
         * (notificar "voltou — você está conectado"). Zera tudo.
         */
        fun onConnected(): Boolean {
            val was = unstable
            reset()
            return was
        }

        /** "Manter sessão ativa" desligado / troca de empresa: para o laço (o aviso de instável continua). */
        fun stop() {
            running = false
        }

        /** "Sair do portal": esquece tudo. */
        fun reset() {
            unstable = false
            attempts = 0
            running = false
            exhausted = false
            justExhausted = false
        }
    }

    /** Estado exibido na tela do portal. */
    data class State(
        val attempts: Int,
        val maxAttempts: Int,
        val retrying: Boolean,
        val exhausted: Boolean,
    )

    fun Retry.snapshot(): State? = if (!unstable) null else State(attempts, maxAttempts, running, exhausted)

    /** Texto do aviso na tela. */
    fun bannerText(state: State, keepAliveOn: Boolean): String = when {
        state.exhausted -> "O portal recusou o login (erro 503 do Compras.gov.br). Não é problema do app. " +
            "Já tentamos ${state.maxAttempts} vezes; tente mais tarde."
        state.retrying -> USER_TEXT + " Próxima tentativa em até 5 min (${state.attempts} de ${state.maxAttempts})."
        keepAliveOn -> USER_TEXT
        else -> USER_TEXT_NO_RETRY
    }
}
