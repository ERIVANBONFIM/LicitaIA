package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.feature.live.web.PortalWebPolicy.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalWebPolicyTest {

    // ------------------------------------------------------------ allowlist

    @Test
    fun `compras gov allowlist covers official login and supplier hosts`() {
        listOf(
            "https://www.gov.br/compras/pt-br/login",
            "https://acesso.gov.br/",
            "https://sso.acesso.gov.br/login?client_id=x",
            "https://comprasnet.gov.br/seguro/loginPortal.asp",
            "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/",
            "https://pncp.gov.br/app/editais",
        ).forEach { assertTrue(it, PortalWebPolicy.isAllowed(Portal.COMPRAS_GOV, it)) }
    }

    @Test
    fun `allowlist rejects lookalike hosts, other portals and non https`() {
        assertFalse(PortalWebPolicy.isAllowed(Portal.COMPRAS_GOV, "https://gov.br.evil.com/login"))
        assertFalse(PortalWebPolicy.isAllowed(Portal.COMPRAS_GOV, "https://fakegov.br/"))
        assertFalse(PortalWebPolicy.isAllowed(Portal.COMPRAS_GOV, "http://www.gov.br/compras/pt-br"))
        assertFalse(PortalWebPolicy.isAllowed(Portal.BLL, "https://www.gov.br/compras"))
        assertFalse(PortalWebPolicy.isAllowed(Portal.PNCP, "https://www.google.com"))
        assertFalse(PortalWebPolicy.isAllowed(Portal.LICITANET, "not a url"))
    }

    @Test
    fun `each portal allows its own start url and domains`() {
        Portal.entries.forEach { p -> assertTrue(p.name, PortalWebPolicy.isAllowed(p, PortalWebPolicy.startUrl(p))) }
        assertTrue(PortalWebPolicy.isAllowed(Portal.BLL, "https://bll.org.br/"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.BLL, "https://bllcompras.com/Home/Login"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.LICITANET, "https://app.licitanet.com.br/"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.PORTAL_COMPRAS_PUBLICAS, "https://www.portaldecompraspublicas.com.br/18/"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.PNCP, "https://pncp.gov.br/api/consulta/v1/x"))
    }

    // ------------------------------------------------------------ login pages

    @Test
    fun `login detection per portal`() {
        assertTrue(PortalWebPolicy.isLoginPage(Portal.COMPRAS_GOV, "https://www.gov.br/compras/pt-br/login"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.COMPRAS_GOV, "https://sso.acesso.gov.br/authorize?x=1"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.COMPRAS_GOV, "https://acesso.gov.br/"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.COMPRAS_GOV, "https://comprasnet.gov.br/seguro/loginPortal.asp"))
        // "acesso" no caminho do gov.br é página pública (acesso à informação), não login.
        assertFalse(PortalWebPolicy.isLoginPage(Portal.COMPRAS_GOV, "https://www.gov.br/compras/pt-br/acesso-a-informacao"))
        assertFalse(PortalWebPolicy.isLoginPage(Portal.COMPRAS_GOV, "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor"))

        assertTrue(PortalWebPolicy.isLoginPage(Portal.BLL, "https://bllcompras.com/home/login"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.BLL, "https://bllcompras.com/Home/Login?ReturnUrl=x")) // case-insensitive
        assertFalse(PortalWebPolicy.isLoginPage(Portal.BLL, "https://bllcompras.com/Process/ProcessSearchPublic"))

        assertTrue(PortalWebPolicy.isLoginPage(Portal.LICITANET, "https://licitanet.com.br/acesso"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://www.portaldecompraspublicas.com.br/18/Login/"))
        assertFalse(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://www.portaldecompraspublicas.com.br/18/Processos/"))

        // PNCP não tem login.
        assertFalse(PortalWebPolicy.isLoginPage(Portal.PNCP, "https://pncp.gov.br/login"))
    }

    // ------------------------------------------------------------ session heuristic

    private val disc = PortalConnectionStatus.DESCONECTADO
    private val conn = PortalConnectionStatus.CONECTADO
    private val exp = PortalConnectionStatus.SESSAO_EXPIRADA

    @Test
    fun `outside allowlist is BLOCKED regardless of status`() {
        assertEquals(Signal.BLOCKED, PortalWebPolicy.evaluate(Portal.BLL, "https://evil.com/", true, true, disc))
        assertEquals(Signal.BLOCKED, PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, "http://www.gov.br/", true, true, conn))
    }

    @Test
    fun `login then logged area with cookies becomes CONNECTED`() {
        val url = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor"
        assertEquals(Signal.CONNECTED, PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, url, hasCookies = true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.CONNECTED, PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, url, true, true, exp))
        assertEquals(Signal.CONNECTED, PortalWebPolicy.evaluate(Portal.BLL, "https://bllcompras.com/Home/Index", true, true, disc))
    }

    @Test
    fun `logged area without cookies or without coming from login is NONE`() {
        val url = "https://bllcompras.com/Home/Index"
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.BLL, url, hasCookies = false, previousWasLoginPage = true, currentStatus = disc))
        // Home pública com cookies de analytics, sem passar pelo login: não é sessão.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.BLL, url, hasCookies = true, previousWasLoginPage = false, currentStatus = disc))
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.BLL, url, hasCookies = true, previousWasLoginPage = null, currentStatus = disc))
        // Já conectado: nada a mudar.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.BLL, url, true, true, conn))
    }

    @Test
    fun `login page while CONNECTED after a normal page is EXPIRED`() {
        assertEquals(Signal.EXPIRED, PortalWebPolicy.evaluate(Portal.BLL, "https://bllcompras.com/home/login", true, previousWasLoginPage = false, currentStatus = conn))
        assertEquals(Signal.EXPIRED, PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, "https://sso.acesso.gov.br/login", false, false, conn))
    }

    @Test
    fun `login page on first load or inside sso flow does not expire`() {
        // Primeira carga da aba: a URL inicial é a própria página de login.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, "https://www.gov.br/compras/pt-br/login", true, previousWasLoginPage = null, currentStatus = conn))
        // Redirecionamentos entre páginas de login/SSO.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, "https://sso.acesso.gov.br/authorize", true, previousWasLoginPage = true, currentStatus = conn))
        // Não conectado numa página de login: nada a registrar.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.BLL, "https://bllcompras.com/home/login", false, false, disc))
    }

    @Test
    fun `PNCP never signals a session`() {
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.PNCP, "https://pncp.gov.br/app/editais", true, true, disc))
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.PNCP, "https://pncp.gov.br/login", true, false, conn))
        assertEquals(Signal.BLOCKED, PortalWebPolicy.evaluate(Portal.PNCP, "https://www.gov.br/compras", true, true, disc))
    }

    // ------------------------------------------------------------ helpers

    @Test
    fun `host and cookie urls`() {
        assertEquals("sso.acesso.gov.br", PortalWebPolicy.host("https://SSO.Acesso.gov.br/x?y=1"))
        assertEquals(null, PortalWebPolicy.host("::not-a-url"))
        val urls = PortalWebPolicy.cookieUrls(Portal.BLL)
        assertTrue(urls.containsAll(listOf("https://bll.org.br/", "https://www.bll.org.br/", "https://bllcompras.com/", "https://www.bllcompras.com/")))
    }

    @Test
    fun `parent domains used to expire cookies`() {
        assertEquals(listOf("sso.acesso.gov.br", "acesso.gov.br", "gov.br"), PortalWebSessions.parentDomains("sso.acesso.gov.br"))
        assertEquals(listOf("bllcompras.com"), PortalWebSessions.parentDomains("bllcompras.com"))
    }
}
