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

        // PCP: /18/loginext/ → Keycloak → /18/loginext/oAuth/ (callback, ainda login) → área logada em operacao.*.
        val pcp = Portal.PORTAL_COMPRAS_PUBLICAS
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(pcp, "https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/protocol/openid-connect/auth", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(pcp, "https://operacao.portaldecompraspublicas.com.br/18/loginext/oAuth/?code=x", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.CONNECTED, PortalWebPolicy.evaluate(pcp, "https://operacao.portaldecompraspublicas.com.br/18/Painel/", true, previousWasLoginPage = true, currentStatus = disc))
        assertEquals(Signal.EXPIRED, PortalWebPolicy.evaluate(pcp, "https://iam.secure.portaldecompraspublicas.com.br/realms/Portal/protocol/openid-connect/auth", false, previousWasLoginPage = false, currentStatus = conn))
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

class PortalContentAndResumeTest {
    private val p = Portal.COMPRAS_GOV
    private val conn = PortalConnectionStatus.CONECTADO
    private val disc = PortalConnectionStatus.DESCONECTADO
    private val exp = PortalConnectionStatus.SESSAO_EXPIRADA
    private val area = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra="

    private val naoAutorizado = """
        Não autorizado
        Sua sessão pode ter expirado ou suas permissões não permitem o acesso ao recurso solicitado.
        Efetuar Login
    """.trimIndent()

    // ------------------------------------------------------------ marcadores de conteúdo

    @Test
    fun `compras gov - pagina nao autorizado do cnetmobile indica sessao encerrada`() {
        assertTrue(PortalWebPolicy.contentIndicatesExpired(p, naoAutorizado))
        assertTrue(PortalWebPolicy.contentIndicatesExpired(p, "NÃO AUTORIZADO   ...   efetuar   login"))
        assertTrue(PortalWebPolicy.contentIndicatesExpired(p, "Nao autorizado. Sua sessao pode ter expirado"))
        assertTrue(PortalWebPolicy.contentIndicatesExpired(p, "Sua sessão expirou. Entre novamente."))
    }

    @Test
    fun `compras gov - paginas normais e de login nao batem`() {
        assertFalse(PortalWebPolicy.contentIndicatesExpired(p, "Compras eletrônicas · Minhas participações · Pregão 90001/2026"))
        // "Não autorizado" sozinho (ex.: item de lista) não basta: precisa do complemento.
        assertFalse(PortalWebPolicy.contentIndicatesExpired(p, "Status: não autorizado pelo órgão"))
        // Tela de login do gov.br: "Efetuar Login"/"Acesse sua Conta" sem "Não autorizado".
        assertFalse(PortalWebPolicy.contentIndicatesExpired(p, "Acesse sua Conta gov.br · Efetuar Login · Número do CPF"))
        assertFalse(PortalWebPolicy.contentIndicatesExpired(p, ""))
    }

    @Test
    fun `marcadores por portal - genericos e PNCP sem marcadores`() {
        assertTrue(PortalWebPolicy.contentIndicatesExpired(Portal.BLL, "Sua sessão expirou, faça login novamente"))
        assertFalse(PortalWebPolicy.contentIndicatesExpired(Portal.BLL, "Faça login para continuar"))
        assertTrue(PortalWebPolicy.contentIndicatesExpired(Portal.LICITANET, "Sessão expirada"))
        assertTrue(PortalWebPolicy.contentIndicatesExpired(Portal.PORTAL_COMPRAS_PUBLICAS, "Sessão encerrada por inatividade"))
        // BLL não usa a regra específica do Compras.gov.br.
        assertFalse(PortalWebPolicy.contentIndicatesExpired(Portal.BLL, naoAutorizado.replace("Sua sessão pode ter expirado ou ", "")))
        assertFalse(PortalWebPolicy.contentIndicatesExpired(Portal.PNCP, "Sua sessão expirou"))
        assertFalse(PortalWebPolicy.hasContentMarkers(Portal.PNCP))
        assertTrue(PortalWebPolicy.hasContentMarkers(p))
    }

    @Test
    fun `probe script devolve so booleano e nao toca em inputs nem cookies`() {
        val js = PortalWebPolicy.contentProbeScript(p)
        assertTrue(js.contains("nao autorizado"))
        assertTrue(js.contains("efetuar login"))
        assertFalse(js.contains("cookie", ignoreCase = true))
        assertFalse(js.contains("input", ignoreCase = true))
        assertFalse(js.contains(".value"))
        assertFalse(js.contains("form", ignoreCase = true))
        assertTrue(PortalWebPolicy.parseProbeResult("true"))
        assertFalse(PortalWebPolicy.parseProbeResult("false"))
        assertFalse(PortalWebPolicy.parseProbeResult("null"))
        assertFalse(PortalWebPolicy.parseProbeResult(null))
    }

    // ------------------------------------------------------------ evaluate com sinal de conteúdo

    @Test
    fun `area logada com aviso de sessao encerrada enquanto CONECTADO expira`() {
        assertEquals(Signal.EXPIRED, PortalWebPolicy.evaluate(p, area, true, previousWasLoginPage = false, currentStatus = conn, contentExpired = true))
        assertEquals(Signal.EXPIRED, PortalWebPolicy.evaluate(p, area, true, previousWasLoginPage = null, currentStatus = conn, contentExpired = true))
        // Sem aviso: segue aberta.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, area, true, false, conn, contentExpired = false))
    }

    @Test
    fun `aviso de conteudo nunca conecta e nao alerta de novo quando ja desconectado ou expirado`() {
        // Vindo do login com cookies, mas a página diz "Não autorizado": não é login bem-sucedido.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, area, true, previousWasLoginPage = true, currentStatus = disc, contentExpired = true))
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, area, true, previousWasLoginPage = false, currentStatus = exp, contentExpired = true))
    }

    @Test
    fun `fluxo de login nao gera alerta - acesse sua conta e selecao de empresa`() {
        val sso = "https://sso.acesso.gov.br/login?client_id=comprasnet.gov.br"
        val selec = "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp"
        listOf(disc, exp).forEach { st ->
            listOf(true, false, null).forEach { prev ->
                listOf(true, false).forEach { content ->
                    assertEquals("$st $prev $content", Signal.NONE, PortalWebPolicy.evaluate(p, sso, true, prev, st, content))
                    assertEquals("$st $prev $content", Signal.NONE, PortalWebPolicy.evaluate(p, selec, true, prev, st, content))
                }
            }
        }
        // Mesmo CONECTADO, dentro do fluxo (veio de página de login), o conteúdo não expira.
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, selec, true, previousWasLoginPage = true, currentStatus = conn, contentExpired = true))
    }

    // ------------------------------------------------------------ última URL (voltar para onde estava)

    @Test
    fun `sanitizacao da ultima url`() {
        // cnetmobile NUNCA é guardado (reabri-lo por URL dá "Não autorizado"), com ou sem token.
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, area))
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra=90001&access_token=abc"))
        // Área de trabalho e Comprasnet seguro: guardados, sem parâmetros sensíveis nem jsessionid/fragmento com token.
        assertEquals("https://www.comprasnet.gov.br/intro.htm", PortalWebPolicy.sanitizeResumeUrl(p, "https://www.comprasnet.gov.br/intro.htm"))
        assertEquals(
            "https://www.comprasnet.gov.br/seguro/fornecedor/menu.asp?x=1",
            PortalWebPolicy.sanitizeResumeUrl(p, "https://www.comprasnet.gov.br/seguro/fornecedor/menu.asp;jsessionid=ABC?x=1&access_token=abc&code=x&state=y#id_token=zzz"),
        )
        // Login, HTTP e fora da allowlist: não guarda.
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, "https://sso.acesso.gov.br/login?client_id=x"))
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"))
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, "http://www.comprasnet.gov.br/intro.htm"))
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, "https://evil.com/comprasnet-web/"))
        // Outros portais: qualquer página não-login da allowlist, sem parâmetros sensíveis.
        assertEquals(
            "https://bllcompras.com/Process/List?page=2",
            PortalWebPolicy.sanitizeResumeUrl(Portal.BLL, "https://bllcompras.com/Process/List?page=2&Token=1&senha=x&SessionId=9"),
        )
        assertNull(PortalWebPolicy.sanitizeResumeUrl(Portal.BLL, "https://bllcompras.com/home/login?ReturnUrl=x"))
        assertNull(PortalWebPolicy.sanitizeResumeUrl(Portal.PNCP, "https://pncp.gov.br/app/editais"))
    }

    @Test
    fun `url de abertura e do keep-alive`() {
        val intro = "https://www.comprasnet.gov.br/intro.htm"
        val menu = "https://www.comprasnet.gov.br/seguro/fornecedor/menu.asp"
        val last = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/participacoes"
        // Compras.gov.br, aba sem estado: SEMPRE a entrada oficial (intro.htm sem sessão cai no www.gov.br público).
        val entry = PortalWebPolicy.startUrl(p)
        listOf(last, null, menu, intro, "https://sso.acesso.gov.br/login").forEach { l ->
            assertEquals(entry, PortalWebPolicy.openUrl(p, conn, l))
        }
        assertEquals(entry, PortalWebPolicy.openUrl(p, exp, last))
        assertEquals(entry, PortalWebPolicy.openUrl(p, disc, last))
        assertEquals(PortalWebPolicy.startUrl(Portal.BLL), PortalWebPolicy.openUrl(Portal.BLL, conn, null))
        // Portal sem área de trabalho: com sessão, a última página da área logada.
        assertEquals("https://bllcompras.com/Process/List", PortalWebPolicy.openUrl(Portal.BLL, conn, "https://bllcompras.com/Process/List"))
        assertEquals(intro, PortalWebPolicy.keepAliveUrl(p, last))
        assertEquals(intro, PortalWebPolicy.keepAliveUrl(p, null))
        assertNull(PortalWebPolicy.keepAliveUrl(Portal.BLL, null))
        assertEquals("https://bllcompras.com/Process/List", PortalWebPolicy.keepAliveUrl(Portal.BLL, "https://bllcompras.com/Process/List"))
    }

    @Test
    fun `parametros sensiveis`() {
        listOf("token", "access_token", "id_token", "code", "state", "SESSIONID", "JSESSIONID", "senha", "password", "client_secret", "ticket")
            .forEach { assertTrue(it, PortalWebPolicy.isSensitiveParam(it)) }
        listOf("compra", "page", "uasg", "numero", "ReturnUrl").forEach { assertFalse(it, PortalWebPolicy.isSensitiveParam(it)) }
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
    fun `apos selecionar empresa a area de trabalho intro htm conecta (sem redirecionar ao cnetmobile)`() {
        val p = Portal.COMPRAS_GOV
        // landing_sso (com cookies) ainda é login: não conecta nem expira
        org.junit.Assert.assertEquals(PortalWebPolicy.Signal.NONE, PortalWebPolicy.evaluate(p, "https://www.comprasnet.gov.br/seguro/landing_sso.asp", true, true, none))
        // Área de trabalho do fornecedor (intro.htm) vinda do login: sessão aberta.
        org.junit.Assert.assertEquals(
            PortalWebPolicy.Signal.CONNECTED,
            PortalWebPolicy.evaluate(p, "https://www.comprasnet.gov.br/intro.htm", true, previousWasLoginPage = true, currentStatus = none),
        )
        org.junit.Assert.assertEquals(
            PortalWebPolicy.Signal.CONNECTED,
            PortalWebPolicy.evaluate(p, "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra=", true, previousWasLoginPage = true, currentStatus = none),
        )
        org.junit.Assert.assertEquals(
            PortalWebPolicy.Signal.CONNECTED,
            PortalWebPolicy.evaluate(p, "https://www.comprasnet.gov.br/seguro/fornecedor/menu.asp", true, previousWasLoginPage = true, currentStatus = none),
        )
        // Página pública do Comprasnet logo após o login mantém o vínculo com o login.
        org.junit.Assert.assertTrue(PortalWebPolicy.carriesLoginFlag(p, "https://www.comprasnet.gov.br/aviso.htm", previous = true))
        org.junit.Assert.assertFalse(PortalWebPolicy.carriesLoginFlag(p, "https://www.comprasnet.gov.br/intro.htm", previous = true))
    }

    @org.junit.Test
    fun `selecao de empresa enquanto conectado nao expira a sessao`() {
        org.junit.Assert.assertEquals(
            PortalWebPolicy.Signal.NONE,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp", true, previousWasLoginPage = true, currentStatus = conn),
        )
    }

    // ------------------------------------------------------------ sem internet / erro de carga

    private val loginUrl = "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"
    private val areaUrl = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras"

    @Test
    fun `redirect ao login sem internet nao expira a sessao`() {
        assertEquals(
            Signal.NONE,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, loginUrl, true, previousWasLoginPage = false, currentStatus = PortalConnectionStatus.CONECTADO, online = false),
        )
        // Mesma navegação com rede: aí sim é sessão encerrada.
        assertEquals(
            Signal.EXPIRED,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, loginUrl, true, previousWasLoginPage = false, currentStatus = PortalConnectionStatus.CONECTADO, online = true),
        )
    }

    @Test
    fun `pagina de erro de carga ou HTTP 5xx nao expira`() {
        assertEquals(
            Signal.NONE,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, loginUrl, true, previousWasLoginPage = false, currentStatus = PortalConnectionStatus.CONECTADO, loadFailed = true),
        )
        assertTrue(PortalWebPolicy.isServerFailure(500))
        assertTrue(PortalWebPolicy.isServerFailure(503))
        assertTrue(PortalWebPolicy.isServerFailure(408))
        assertTrue(PortalWebPolicy.isServerFailure(429))
        assertFalse(PortalWebPolicy.isServerFailure(401))
        assertFalse(PortalWebPolicy.isServerFailure(200))
    }

    @Test
    fun `aviso de sessao na SPA sem rede (API inacessivel) nao expira`() {
        assertEquals(
            Signal.NONE,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, areaUrl, true, previousWasLoginPage = false, currentStatus = PortalConnectionStatus.CONECTADO, contentExpired = true, online = false),
        )
        assertEquals(
            Signal.NONE,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, areaUrl, true, previousWasLoginPage = false, currentStatus = PortalConnectionStatus.CONECTADO, contentExpired = true, loadFailed = true),
        )
    }

    @Test
    fun `sem rede tambem nao marca login nem libera dominio fora da allowlist`() {
        assertEquals(
            Signal.NONE,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, areaUrl, true, previousWasLoginPage = true, currentStatus = PortalConnectionStatus.DESCONECTADO, online = false),
        )
        assertEquals(
            Signal.BLOCKED,
            PortalWebPolicy.evaluate(Portal.COMPRAS_GOV, "https://evil.example.com/", true, previousWasLoginPage = false, currentStatus = PortalConnectionStatus.CONECTADO, online = false),
        )
    }

    @Test
    fun `canConclude exige rede e carga sem erro`() {
        assertTrue(PortalWebPolicy.canConclude(online = true, loadFailed = false))
        assertFalse(PortalWebPolicy.canConclude(online = false, loadFailed = false))
        assertFalse(PortalWebPolicy.canConclude(online = true, loadFailed = true))
    }

    @Test
    fun `keep-alive so confirma expiracao apos probes seguidos`() {
        assertFalse(PortalWebPolicy.keepAliveConfirmsExpired(0))
        assertFalse(PortalWebPolicy.keepAliveConfirmsExpired(1))
        assertTrue(PortalWebPolicy.keepAliveConfirmsExpired(PortalWebPolicy.EXPIRED_CONFIRMATIONS))
    }
}