package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.feature.live.web.PortalWebPolicy.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(PortalWebPolicy.isAllowed(Portal.LICITANET, "https://portal.licitanet.com.br/login"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.LICITANET, "https://licita-sso.licitanet.com.br/login"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.LICITANET, "https://licitanet.com.br/"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.PORTAL_COMPRAS_PUBLICAS, "https://www.portaldecompraspublicas.com.br/18/"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.PORTAL_COMPRAS_PUBLICAS, "https://operacao.portaldecompraspublicas.com.br/18/loginext/"))
        assertTrue(PortalWebPolicy.isAllowed(Portal.PORTAL_COMPRAS_PUBLICAS, "https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/protocol/openid-connect/auth?client_id=aspclient"))
        assertFalse(PortalWebPolicy.isAllowed(Portal.PORTAL_COMPRAS_PUBLICAS, "https://portaldecompraspublicas.com.br.evil.com/"))
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

        // Licitanet: login em portal.licitanet.com.br/login; SSO em host dedicado (qualquer caminho).
        assertTrue(PortalWebPolicy.isLoginPage(Portal.LICITANET, PortalWebPolicy.startUrl(Portal.LICITANET)))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.LICITANET, "https://portal.licitanet.com.br/login"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.LICITANET, "https://licita-sso.licitanet.com.br/login"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.LICITANET, "https://licita-sso.licitanet.com.br/callback?code=x"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.LICITANET, "https://licitanet.com.br/acesso"))
        assertFalse(PortalWebPolicy.isLoginPage(Portal.LICITANET, "https://portal.licitanet.com.br/fornecedor-publico"))
        assertFalse(PortalWebPolicy.isLoginPage(Portal.LICITANET, "https://licitanet.com.br/sessao-publica"))

        // Portal de Compras Públicas: /18/loginext/ → Keycloak (iam.secure.*) → /18/loginext/oAuth/ (ainda login) → área logada.
        assertTrue(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, PortalWebPolicy.startUrl(Portal.PORTAL_COMPRAS_PUBLICAS)))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://operacao.portaldecompraspublicas.com.br/18/loginext/"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://operacao.portaldecompraspublicas.com.br/18/loginext/oAuth/?code=abc&state=x"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/protocol/openid-connect/auth?client_id=aspclient"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/login-actions/authenticate?session_code=x"))
        assertTrue(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://www.portaldecompraspublicas.com.br/18/Login/"))
        assertFalse(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://www.portaldecompraspublicas.com.br/18/Processos/"))
        assertFalse(PortalWebPolicy.isLoginPage(Portal.PORTAL_COMPRAS_PUBLICAS, "https://operacao.portaldecompraspublicas.com.br/18/Painel/"))

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
    fun `licitanet and portal de compras publicas connect after their real login flows`() {
        // Licitanet: login → (SSO) → primeira página normal do portal com cookies.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(Portal.LICITANET, "https://licita-sso.licitanet.com.br/login", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.CONNECTED, PortalWebPolicy.evaluate(Portal.LICITANET, "https://portal.licitanet.com.br/fornecedor/painel", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.EXPIRED, PortalWebPolicy.evaluate(Portal.LICITANET, "https://portal.licitanet.com.br/login", true, previousWasLoginPage = false, currentStatus = conn))
        assertNull(PortalWebPolicy.postLoginRedirect(Portal.LICITANET, "https://portal.licitanet.com.br/fornecedor/painel"))

        // PCP: /18/loginext/ → Keycloak → /18/loginext/oAuth/ (callback, ainda login) → área logada em operacao.*.
        val pcp = Portal.PORTAL_COMPRAS_PUBLICAS
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(pcp, "https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/protocol/openid-connect/auth", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(pcp, "https://operacao.portaldecompraspublicas.com.br/18/loginext/oAuth/?code=x", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.CONNECTED, PortalWebPolicy.evaluate(pcp, "https://operacao.portaldecompraspublicas.com.br/18/Painel/", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.EXPIRED, PortalWebPolicy.evaluate(pcp, "https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/protocol/openid-connect/auth", false, previousWasLoginPage = false, currentStatus = conn))
        assertNull(PortalWebPolicy.postLoginRedirect(pcp, "https://operacao.portaldecompraspublicas.com.br/18/Painel/"))
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

class ComprasGovFlowTest {
    private val conn = com.licitaia.domain.model.PortalConnectionStatus.CONECTADO
    private val none = com.licitaia.domain.model.PortalConnectionStatus.DESCONECTADO

    @org.junit.Test
    fun `fluxo real do comprasnet - landing e selecao de empresa sao etapas de login`() {
        val p = Portal.COMPRAS_GOV
        org.junit.Assert.assertTrue(PortalWebPolicy.isLoginPage(p, "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"))
        org.junit.Assert.assertTrue(PortalWebPolicy.isLoginPage(p, "https://sso.acesso.gov.br/login?client_id=comprasnet.gov.br&authorization_id=abc"))
        org.junit.Assert.assertTrue(PortalWebPolicy.isLoginPage(p, "https://www.comprasnet.gov.br/seguro/landing_sso.asp?code=x"))
        org.junit.Assert.assertTrue(PortalWebPolicy.isLoginPage(p, "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp"))
        org.junit.Assert.assertFalse(PortalWebPolicy.isLoginPage(p, "https://www.comprasnet.gov.br/intro.htm"))
        org.junit.Assert.assertFalse(PortalWebPolicy.isLoginPage(p, "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra="))
    }

    @org.junit.Test
    fun `apos selecionar empresa a primeira pagina normal conecta e redireciona ao workspace`() {
        val p = Portal.COMPRAS_GOV
        // landing_sso (com cookies) ainda é login: não conecta nem expira
        org.junit.Assert.assertEquals(PortalWebPolicy.Signal.NONE, PortalWebPolicy.evaluate(p, "https://www.comprasnet.gov.br/seguro/landing_sso.asp", true, true, none))
        val signal = PortalWebPolicy.evaluate(p, "https://www.comprasnet.gov.br/intro.htm", true, previousWasLoginPage = true, currentStatus = none)
        org.junit.Assert.assertEquals(PortalWebPolicy.Signal.CONNECTED, signal)
        org.junit.Assert.assertEquals(
            "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra=",
            PortalWebPolicy.postLoginRedirect(p, "https://www.comprasnet.gov.br/intro.htm"),
        )
        org.junit.Assert.assertNull(PortalWebPolicy.postLoginRedirect(p, "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra=123"))
        org.junit.Assert.assertNull(PortalWebPolicy.postLoginRedirect(Portal.BLL, "https://bllcompras.com/home"))
    }

    @org.junit.Test
    fun `selecao de empresa enquanto conectado nao expira a sessao`() {
        org.junit.Assert.assertEquals(
            PortalWebPolicy.Signal.NONE,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp", true, previousWasLoginPage = true, currentStatus = conn),
        )
    }
}
