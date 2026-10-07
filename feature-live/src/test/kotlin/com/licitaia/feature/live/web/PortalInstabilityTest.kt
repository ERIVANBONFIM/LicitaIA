package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.feature.live.web.CertAutoLogin.Decision
import com.licitaia.feature.live.web.CertAutoLogin.ScriptResult
import com.licitaia.feature.live.web.CertAutoLogin.Step
import com.licitaia.feature.live.web.CertAutoLogin.StopReason
import com.licitaia.feature.live.web.PortalInstability.AfterAttempt
import com.licitaia.feature.live.web.PortalInstability.AttemptResult
import com.licitaia.feature.live.web.PortalInstability.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Compras.gov.br instável" (503 após o retorno do gov.br): detecção, parada do login automático e agenda. */
class PortalInstabilityTest {
    private val p = Portal.COMPRAS_GOV
    private val entry = "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"
    private val landing = "https://www.comprasnet.gov.br/seguro/landing_sso.asp?code=abc&state=F"
    private val msg = "Operação não realizada. Tente novamente mais tarde. (503)"

    // ------------------------------------------------------------ detecção

    @Test
    fun `503 na entrada do Comprasnet e instabilidade`() {
        assertTrue(PortalInstability.isUnstablePage(p, entry, msg, afterLandingSso = true))
        // Só o texto basta (sem a passagem pelo landing_sso).
        assertTrue(PortalInstability.isUnstablePage(p, entry, msg))
        assertTrue(PortalInstability.isUnstablePage(p, entry, "Erro (503)"))
        assertTrue(PortalInstability.isUnstablePage(p, entry, "OPERAÇÃO NÃO REALIZADA"))
        assertTrue(PortalInstability.isUnstablePage(p, "https://comprasnet.gov.br/seguro/loginPortal.asp", msg))
    }

    @Test
    fun `sem o texto ou fora da entrada nao e instabilidade`() {
        assertFalse(PortalInstability.isUnstablePage(p, entry, "Acesse sua conta · Fornecedor Brasileiro · Entrar com gov.br"))
        assertFalse(PortalInstability.isUnstablePage(p, entry, "CNPJ 12.503.678/0001-90")) // "503" sem parênteses
        assertFalse(PortalInstability.isUnstablePage(p, "https://sso.acesso.gov.br/login", msg))
        assertFalse(PortalInstability.isUnstablePage(p, "https://www.comprasnet.gov.br/intro.htm", msg))
        assertFalse(PortalInstability.isUnstablePage(Portal.BLL, "https://bllcompras.com/home/login", msg))
        assertFalse(PortalInstability.isUnstablePage(p, "http://www.comprasnet.gov.br/seguro/loginPortal.asp", msg))
    }

    @Test
    fun `landing_sso e entrada sao reconhecidos`() {
        assertTrue(PortalInstability.isLandingSso(p, landing))
        assertFalse(PortalInstability.isLandingSso(p, entry))
        assertTrue(PortalInstability.isEntryLoginPage(p, entry))
        assertFalse(PortalInstability.isEntryLoginPage(p, landing))
    }

    @Test
    fun `probe so vale na entrada e devolve so booleano`() {
        assertTrue(PortalInstability.isUnstableProbe(p, entry, PortalInstability.parseProbe("true")))
        assertFalse(PortalInstability.isUnstableProbe(p, entry, PortalInstability.parseProbe("false")))
        assertFalse(PortalInstability.isUnstableProbe(p, landing, true))
        val js = PortalInstability.probeScript()
        assertTrue(js.contains("'operacao nao realizada'"))
        assertTrue(js.contains("'(503)'"))
        assertFalse(js.contains(".value"))
        assertFalse(js.contains("cookie"))
    }

    // ------------------------------------------------------------ login automático para com o motivo específico

    @Test
    fun `login automatico para com PORTAL_UNSTABLE e nao com etapa repetida`() {
        val run = CertAutoLogin.Run()
        assertEquals(Decision.Execute(Step.PROFILE_SELECT), run.onPage(Step.PROFILE_SELECT))
        assertEquals(Decision.Wait, run.onScriptResult(ScriptResult.CLICKED))
        assertEquals(Decision.Execute(Step.GOVBR_LOGIN), run.onPage(Step.GOVBR_LOGIN))
        assertEquals(Decision.Wait, run.onScriptResult(ScriptResult.CLICKED))
        assertEquals(Decision.Wait, run.onPage(Step.OTHER)) // landing_sso
        // Volta à entrada com o 503: para na hora (mesmo que a etapa ainda tivesse "nova tentativa").
        assertEquals(Decision.Stop(StopReason.PORTAL_UNSTABLE), run.onPage(Step.PROFILE_SELECT, unstable = true))
        assertTrue(run.finished)
    }

    @Test
    fun `script devolve unstable antes de error`() {
        assertEquals(ScriptResult.PORTAL_UNSTABLE, CertAutoLogin.parseResult("\"unstable\""))
        val run = CertAutoLogin.Run()
        run.onPage(Step.PROFILE_SELECT)
        assertEquals(Decision.Stop(StopReason.PORTAL_UNSTABLE), run.onScriptResult(ScriptResult.PORTAL_UNSTABLE))
        val js = CertAutoLogin.script(Step.PROFILE_SELECT, "")!!
        assertTrue(js.indexOf("return 'unstable'") in 0 until js.indexOf("return 'error'"))
    }

    @Test
    fun `mensagem de parada explica o 503`() {
        val text = StopReason.PORTAL_UNSTABLE.userText
        assertEquals(
            "O portal recusou o login (erro 503 do Compras.gov.br). Não é problema do app; tentaremos de novo.",
            text,
        )
        assertFalse(text.contains("http"))
        assertFalse(text.contains("repetiu"))
        assertTrue(StopReason.PORTAL_UNSTABLE.auditText.contains("503"))
        assertFalse(StopReason.PORTAL_UNSTABLE.auditText.contains("etapa repetida"))
        assertEquals("Compras.gov.br instável", PortalInstability.BADGE_LABEL)
        assertTrue(PortalInstability.auditText(afterLandingSso = true).contains("retorno do gov.br"))
    }

    // ------------------------------------------------------------ agenda de novas tentativas

    @Test
    fun `sem manter sessao ativa so marca instavel e nao agenda`() {
        val r = PortalInstability.Retry()
        assertFalse(r.onDetected(retryEnabled = false))
        assertTrue(r.unstable)
        assertFalse(r.running)
        assertNull(r.nextDelay())
        assertFalse(r.justExhausted)
        assertNotNull(r.snapshot())
    }

    @Test
    fun `agenda a cada 5 min e para em 12 tentativas com notificacao unica`() {
        val r = PortalInstability.Retry()
        assertTrue(r.onDetected(retryEnabled = true))
        // Nova detecção com o laço ativo não cria outro laço.
        assertFalse(r.onDetected(retryEnabled = true))
        repeat(PortalInstability.MAX_ATTEMPTS) {
            assertEquals(5 * 60_000L, r.nextDelay())
            assertTrue(r.beginAttempt())
            assertEquals(AfterAttempt.CONTINUE, r.onAttemptResult(AttemptResult.UNSTABLE))
        }
        assertEquals(12, r.attempts)
        assertNull(r.nextDelay())
        assertTrue(r.justExhausted)
        assertTrue(r.exhausted)
        assertFalse(r.running)
        // Esgotado: nova detecção não reabre o laço e não repete a notificação.
        assertFalse(r.onDetected(retryEnabled = true))
        assertNull(r.nextDelay())
        assertFalse(r.justExhausted)
        assertEquals(PortalInstability.State(12, 12, retrying = false, exhausted = true), r.snapshot())
    }

    @Test
    fun `tentativa ocupada ou inconclusiva nao conta`() {
        val r = PortalInstability.Retry(maxAttempts = 2)
        r.onDetected(true)
        r.nextDelay(); r.beginAttempt()
        assertEquals(AfterAttempt.CONTINUE, r.onAttemptResult(AttemptResult.BUSY))
        r.nextDelay(); r.beginAttempt()
        assertEquals(AfterAttempt.CONTINUE, r.onAttemptResult(AttemptResult.INCONCLUSIVE))
        assertEquals(0, r.attempts)
        assertNotNull(r.nextDelay())
    }

    @Test
    fun `cancelamento ao desligar ou sair do portal`() {
        val r = PortalInstability.Retry()
        r.onDetected(true)
        assertNotNull(r.nextDelay())
        r.stop() // "Manter sessão ativa" desligado
        assertFalse(r.beginAttempt())
        assertNull(r.nextDelay())
        assertTrue(r.unstable) // o aviso continua
        assertFalse(r.justExhausted)
        // Religou: volta a agendar.
        assertTrue(r.onDetected(true))
        r.reset() // "Sair do portal"
        assertFalse(r.unstable)
        assertFalse(r.running)
        assertNull(r.snapshot())
    }

    @Test
    fun `recuperacao encerra o episodio`() {
        val r = PortalInstability.Retry()
        r.onDetected(true); r.nextDelay(); r.beginAttempt()
        assertEquals(AfterAttempt.RECOVERED_CONNECTED, r.onAttemptResult(AttemptResult.LOGGED_IN))
        assertFalse(r.unstable); assertFalse(r.running)
        // Sem login automático: a entrada abriu sem o 503.
        r.onDetected(true); r.nextDelay(); r.beginAttempt()
        assertEquals(AfterAttempt.RECOVERED_AVAILABLE, r.onAttemptResult(AttemptResult.AVAILABLE))
        // CAPTCHA/2FA: o portal respondeu; o usuário conclui.
        r.onDetected(true); r.nextDelay(); r.beginAttempt()
        assertEquals(AfterAttempt.STOP, r.onAttemptResult(AttemptResult.STOPPED))
        // Login pela tela: avisa "voltou" só se havia instabilidade.
        r.onDetected(false)
        assertTrue(r.onConnected())
        assertFalse(r.onConnected())
    }

    @Test
    fun `texto do aviso na tela`() {
        val retrying = PortalInstability.State(attempts = 2, maxAttempts = 12, retrying = true, exhausted = false)
        assertTrue(PortalInstability.bannerText(retrying, keepAliveOn = true).startsWith(PortalInstability.USER_TEXT))
        assertTrue(PortalInstability.bannerText(retrying, keepAliveOn = true).contains("2 de 12"))
        val idle = retrying.copy(retrying = false)
        assertTrue(PortalInstability.bannerText(idle, keepAliveOn = false).contains("Manter sessão ativa"))
        assertTrue(PortalInstability.bannerText(idle.copy(exhausted = true), keepAliveOn = true).contains("tente mais tarde"))
    }
}
