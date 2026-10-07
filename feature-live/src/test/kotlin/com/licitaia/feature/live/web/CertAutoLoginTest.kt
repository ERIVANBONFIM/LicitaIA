package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.feature.live.web.CertAutoLogin.Decision
import com.licitaia.feature.live.web.CertAutoLogin.ScriptResult
import com.licitaia.feature.live.web.CertAutoLogin.Step
import com.licitaia.feature.live.web.CertAutoLogin.StopReason
import com.licitaia.feature.live.web.PortalWebPolicy.KeepAliveAction
import com.licitaia.feature.live.web.PortalWebPolicy.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Área logada do Compras.gov.br (falso "Sessão aberta" no www.gov.br) e o login automático com certificado. */
class ComprasGovLoggedAreaTest {
    private val p = Portal.COMPRAS_GOV
    private val disc = PortalConnectionStatus.DESCONECTADO
    private val conn = PortalConnectionStatus.CONECTADO
    private val cnet = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra="
    private val govbr = "https://www.gov.br/compras/pt-br/acesso-a-informacao/compras-gov-br"

    @Test
    fun `area logada so cnetmobile seguro e comprasnet seguro fora do login`() {
        assertTrue(PortalWebPolicy.isLoggedArea(p, cnet))
        assertTrue(PortalWebPolicy.isLoggedArea(p, "https://www.comprasnet.gov.br/seguro/fornecedor/menu.asp"))
        assertFalse(PortalWebPolicy.isLoggedArea(p, "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"))
        assertFalse(PortalWebPolicy.isLoggedArea(p, "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp"))
        assertFalse(PortalWebPolicy.isLoggedArea(p, "https://www.comprasnet.gov.br/seguro/landing_sso.asp"))
        assertFalse(PortalWebPolicy.isLoggedArea(p, "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/public/home"))
        assertFalse(PortalWebPolicy.isLoggedArea(p, govbr))
        assertFalse(PortalWebPolicy.isLoggedArea(p, "https://www.gov.br/compras/pt-br"))
        assertFalse(PortalWebPolicy.isLoggedArea(p, "https://sso.acesso.gov.br/login"))
        assertFalse(PortalWebPolicy.isLoggedArea(Portal.PNCP, "https://pncp.gov.br/app/editais"))
        // Portais sem área declarada: qualquer página fora do login.
        assertTrue(PortalWebPolicy.isLoggedArea(Portal.BLL, "https://bllcompras.com/Home/Index"))
    }

    @Test
    fun `www gov br publico nunca vira sessao aberta mesmo vindo do SSO com cookies`() {
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, govbr, hasCookies = true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, "https://www.gov.br/compras/pt-br", true, true, disc))
        assertFalse(PortalWebPolicy.carriesLoginFlag(p, govbr, previous = true))
        assertEquals(Signal.CONNECTED, PortalWebPolicy.evaluate(p, cnet, true, true, disc))
    }

    @Test
    fun `keep-alive so recarrega na area logada - senao abre a area de trabalho`() {
        val last = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/participacoes"
        val intro = "https://www.comprasnet.gov.br/intro.htm"
        assertEquals(KeepAliveAction.Reload, PortalWebPolicy.keepAliveAction(p, cnet, visible = false, fallbackUrl = last))
        assertEquals(KeepAliveAction.Reload, PortalWebPolicy.keepAliveAction(p, intro, visible = false, fallbackUrl = last))
        // Fora da área logada: nunca o cnetmobile guardado — a entrada oficial (aba sem estado) ou a área de trabalho.
        assertEquals(
            KeepAliveAction.Load(PortalWebPolicy.startUrl(p), viaEntry = true),
            PortalWebPolicy.keepAliveAction(p, govbr, visible = false, fallbackUrl = last),
        )
        assertEquals(
            KeepAliveAction.Load(intro, viaEntry = true),
            PortalWebPolicy.keepAliveAction(p, govbr, visible = false, fallbackUrl = last, reachedLoggedArea = true),
        )
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, govbr))
        assertTrue(PortalWebPolicy.leftLoggedArea(p, govbr))
        assertFalse(PortalWebPolicy.leftLoggedArea(p, cnet))
        assertFalse(PortalWebPolicy.leftLoggedArea(p, "https://sso.acesso.gov.br/login"))
        assertFalse(PortalWebPolicy.leftLoggedArea(Portal.BLL, "https://bllcompras.com/"))
        // Já CONECTADO no www.gov.br: nada muda (nem conecta, nem expira).
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, govbr, true, false, conn))
    }
}

class CertAutoLoginTest {
    private val p = Portal.COMPRAS_GOV
    private val cnpj = "27.147.548/0001-15"

    @Test
    fun `deteccao da etapa pela url`() {
        assertEquals(Step.PROFILE_SELECT, CertAutoLogin.detectStep(p, "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"))
        assertEquals(Step.GOVBR_LOGIN, CertAutoLogin.detectStep(p, "https://sso.acesso.gov.br/login?client_id=comprasnet.gov.br&authorization_id=x"))
        assertEquals(Step.CERTIFICATE, CertAutoLogin.detectStep(p, "https://certificado.sso.acesso.gov.br/"))
        assertEquals(Step.COMPANY_SELECT, CertAutoLogin.detectStep(p, "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp"))
        assertEquals(Step.LOGGED_IN, CertAutoLogin.detectStep(p, "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra="))
        assertEquals(Step.OTHER, CertAutoLogin.detectStep(p, "https://www.comprasnet.gov.br/seguro/landing_sso.asp"))
        assertEquals(Step.OTHER, CertAutoLogin.detectStep(p, "https://www.gov.br/compras/pt-br"))
        assertEquals(Step.OTHER, CertAutoLogin.detectStep(p, "https://evil.example.com/seguro/loginPortal.asp"))
        assertEquals(Step.OTHER, CertAutoLogin.detectStep(p, "http://www.comprasnet.gov.br/seguro/loginPortal.asp"))
        assertEquals(Step.OTHER, CertAutoLogin.detectStep(Portal.BLL, "https://bllcompras.com/home/login"))
        assertTrue(CertAutoLogin.supports(p))
        assertFalse(CertAutoLogin.supports(Portal.BLL))
    }

    @Test
    fun `casamento de CNPJ so por digitos e nunca com CPF`() {
        val rows = listOf(
            "ERIVAN FULANO DE TAL - CPF - 123.456.789-01",
            "ME TELECOM SERVICOS DE INTERNET LTDA - CNPJ - 27.147.548/0001-15",
        )
        assertEquals(1, CertAutoLogin.pickCompanyRow(rows, cnpj))
        assertEquals(1, CertAutoLogin.pickCompanyRow(rows, "27147548000115"))
        assertTrue(CertAutoLogin.rowMatchesCnpj("EMPRESA 27147548000115", cnpj))
        assertNull(CertAutoLogin.pickCompanyRow(rows, "11.222.333/0001-81"))
        assertNull(CertAutoLogin.pickCompanyRow(listOf("ERIVAN - CPF - 271.475.480-00"), cnpj))
        assertFalse(CertAutoLogin.rowMatchesCnpj("ME TELECOM - CNPJ - 27.147.548/0001-15", "123"))
    }

    @Test
    fun `marcadores de bloqueio - 2FA erro e captcha`() {
        assertTrue(CertAutoLogin.textIndicatesMfa("Digite o código enviado para o seu celular"))
        assertTrue(CertAutoLogin.textIndicatesMfa("Verificação em duas etapas"))
        assertFalse(CertAutoLogin.textIndicatesMfa("Seu certificado digital · Login com QR code · Número do CPF"))
        assertTrue(CertAutoLogin.textIndicatesError("Ocorreu um erro ao processar sua solicitação"))
        assertFalse(CertAutoLogin.textIndicatesError("Selecione o perfil desejado"))
        assertTrue(CertAutoLogin.looksLikeCaptcha(null, "h-captcha", null))
        assertTrue(CertAutoLogin.looksLikeCaptcha(null, null, "https://www.google.com/recaptcha/api2/anchor"))
        assertTrue(CertAutoLogin.looksLikeCaptcha("captchaImg", null, null))
        assertFalse(CertAutoLogin.looksLikeCaptcha(null, "grecaptcha-badge", null))
        assertFalse(CertAutoLogin.looksLikeCaptcha("btn", "br-button", null))
    }

    @Test
    fun `fluxo completo - perfil gov br certificado empresa e area logada`() {
        val run = CertAutoLogin.Run()
        assertEquals(Decision.Execute(Step.PROFILE_SELECT), run.onPage(Step.PROFILE_SELECT))
        assertEquals(Decision.Wait, run.onScriptResult(ScriptResult.CLICKED))
        assertEquals(Decision.Execute(Step.GOVBR_LOGIN), run.onPage(Step.GOVBR_LOGIN))
        assertEquals(Decision.Wait, run.onScriptResult(ScriptResult.CLICKED))
        assertEquals(Decision.Wait, run.onPage(Step.CERTIFICATE))
        assertEquals(Decision.Wait, run.onPage(Step.OTHER)) // landing_sso
        assertEquals(Decision.Execute(Step.COMPANY_SELECT), run.onPage(Step.COMPANY_SELECT))
        assertEquals(Decision.Wait, run.onScriptResult(ScriptResult.CLICKED))
        assertEquals(Decision.Success, run.onPage(Step.LOGGED_IN))
        assertTrue(run.finished)
        assertEquals(Decision.Wait, run.onPage(Step.PROFILE_SELECT)) // terminado: não age mais
    }

    @Test
    fun `ja na area logada nao faz nada`() {
        val run = CertAutoLogin.Run()
        assertEquals(Decision.NotNeeded, run.onPage(Step.LOGGED_IN))
        assertEquals(0, run.actions)
    }

    @Test
    fun `para em captcha 2FA erro e CNPJ ausente`() {
        mapOf(
            ScriptResult.CAPTCHA to StopReason.CAPTCHA,
            ScriptResult.MFA to StopReason.MFA,
            ScriptResult.ERROR_PAGE to StopReason.ERROR_PAGE,
            ScriptResult.NO_COMPANY_MATCH to StopReason.NO_COMPANY_MATCH,
            ScriptResult.NOT_FOUND to StopReason.NOT_FOUND,
            ScriptResult.UNKNOWN to StopReason.NOT_FOUND,
        ).forEach { (result, reason) ->
            val run = CertAutoLogin.Run()
            run.onPage(Step.GOVBR_LOGIN)
            assertEquals(result.name, Decision.Stop(reason), run.onScriptResult(result))
            assertEquals(Decision.Wait, run.onPage(Step.GOVBR_LOGIN))
        }
    }

    @Test
    fun `mesma etapa repetida para (sem laco) e falha de carga para`() {
        val run = CertAutoLogin.Run()
        assertEquals(Decision.Execute(Step.PROFILE_SELECT), run.onPage(Step.PROFILE_SELECT))
        assertEquals(Decision.Execute(Step.PROFILE_SELECT), run.onPage(Step.PROFILE_SELECT))
        assertEquals(Decision.Stop(StopReason.LOOP), run.onPage(Step.PROFILE_SELECT))

        val failed = CertAutoLogin.Run()
        assertEquals(Decision.Stop(StopReason.LOAD_FAILED), failed.onPage(Step.OTHER, loadFailed = true))
    }

    @Test
    fun `nao autorizado - uma navegacao para a entrada (sem clique) e depois SESSION_REJECTED`() {
        val run = CertAutoLogin.Run()
        assertEquals(Decision.NotNeeded, run.onPage(Step.LOGGED_IN))
        // Área logada mostrou "Não autorizado": navegar para a entrada oficial (etapa sem script).
        assertEquals(Decision.Execute(Step.UNAUTHORIZED), run.onUnauthorized())
        assertNull(CertAutoLogin.script(Step.UNAUTHORIZED, cnpj))
        assertFalse(run.finished)
        // Fluxo continua pela entrada e chega à área logada: sucesso.
        assertEquals(Decision.Wait, run.onPage(Step.OTHER))
        assertEquals(Decision.Success, run.onPage(Step.LOGGED_IN))
        // De novo "Não autorizado": não repete (sem laço).
        assertEquals(Decision.Stop(StopReason.SESSION_REJECTED), run.onUnauthorized())
        assertEquals(Decision.Wait, run.onPage(Step.PROFILE_SELECT))
    }

    @Test
    fun `tempo esgotado - silencioso sem acao e TIMEOUT depois de agir`() {
        assertEquals(Decision.NotNeeded, CertAutoLogin.Run().onTimeout())
        val run = CertAutoLogin.Run()
        run.onPage(Step.PROFILE_SELECT)
        assertEquals(Decision.Stop(StopReason.TIMEOUT), run.onTimeout())
    }

    @Test
    fun `resultado do script e scripts revisaveis`() {
        assertEquals(ScriptResult.CLICKED, CertAutoLogin.parseResult("\"clicked\""))
        assertEquals(ScriptResult.CAPTCHA, CertAutoLogin.parseResult("\"captcha\""))
        assertEquals(ScriptResult.NO_COMPANY_MATCH, CertAutoLogin.parseResult("\"nomatch\""))
        assertEquals(ScriptResult.UNKNOWN, CertAutoLogin.parseResult("null"))
        assertEquals(ScriptResult.UNKNOWN, CertAutoLogin.parseResult(null))

        val company = assertNotNull(CertAutoLogin.script(Step.COMPANY_SELECT, cnpj)).let { CertAutoLogin.script(Step.COMPANY_SELECT, cnpj)!! }
        assertTrue(company.contains("'27147548000115'"))
        assertTrue(company.contains("return 'nomatch'"))
        val profile = CertAutoLogin.script(Step.PROFILE_SELECT, cnpj)!!
        assertTrue(profile.contains("fornecedor brasileiro") && profile.contains("entrar com gov.br"))
        val govbr = CertAutoLogin.script(Step.GOVBR_LOGIN, cnpj)!!
        assertTrue(govbr.contains("certificado digital") && govbr.contains("nuvem"))
        listOf(company, profile, govbr).forEach { js ->
            // Bloqueios checados antes de qualquer clique; nada de cookies, digitação ou envio de formulário.
            assertTrue(js.indexOf("var bl=B();if(bl)return bl;") in 0 until js.indexOf(".click()"))
            assertFalse(js.contains("cookie", ignoreCase = true))
            assertFalse(js.contains(".value="))
            assertFalse(js.contains("submit()"))
            assertFalse(js.contains("password", ignoreCase = true))
            assertFalse(js.contains("localStorage") || js.contains("sessionStorage"))
            assertTrue(js.contains("captcha"))
        }
        assertNull(CertAutoLogin.script(Step.LOGGED_IN, cnpj))
        assertNull(CertAutoLogin.script(Step.OTHER, cnpj))
    }
}
