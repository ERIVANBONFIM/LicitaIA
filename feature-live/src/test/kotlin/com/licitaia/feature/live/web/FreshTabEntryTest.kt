package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.feature.live.web.PortalWebPolicy.EntryAction
import com.licitaia.feature.live.web.PortalWebPolicy.KeepAliveAction
import com.licitaia.feature.live.web.PortalWebPolicy.SessionCheck
import com.licitaia.feature.live.web.PortalWebPolicy.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug do aparelho: processo reiniciado (WebView retido vazio) com status CONECTADO abriu intro.htm, que sem sessão
 * redireciona a aba para www.gov.br/compras/pt-br, e a tela ficou ali com "Sessão aberta".
 */
class FreshTabEntryTest {
    private val p = Portal.COMPRAS_GOV
    private val entryUrl = "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"
    private val intro = "https://www.comprasnet.gov.br/intro.htm"
    private val main2 = "https://www.comprasnet.gov.br/main2.asp"
    private val govbr = "https://www.gov.br/compras/pt-br"
    private val sso = "https://sso.acesso.gov.br/login?client_id=comprasnet.gov.br"
    private val cnet = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra="
    private val conn = PortalConnectionStatus.CONECTADO
    private val disc = PortalConnectionStatus.DESCONECTADO

    private fun isIntro(url: String?) = url != null && PortalWebPolicy.isWorkspaceUrl(p, url)

    // ------------------------------------------------------------------ 1. aba vazia → entrada oficial

    @Test
    fun `aba vazia nunca carrega intro htm - em nenhum caminho`() {
        assertEquals(entryUrl, PortalWebPolicy.startUrl(p))
        assertTrue(PortalWebPolicy.requiresEntryOnFreshTab(p))
        val lasts = listOf(null, intro, "https://comprasnet.gov.br/intro.htm", cnet, "https://www.comprasnet.gov.br/seguro/fornecedor/menu.asp")
        PortalConnectionStatus.entries.forEach { st ->
            lasts.forEach { l ->
                // Abrir a tela / "Tentar de novo" / offline adiado (todos usam openUrl + firstLoad).
                val open = PortalWebPolicy.openUrl(p, st, l)
                assertEquals("$st $l", entryUrl, open)
                val gate = PortalWebPolicy.EntryGate(p)
                assertEquals(entryUrl, gate.firstLoad(open, verify = st == conn))
                assertFalse(gate.reachedLoggedArea)
                assertFalse(isIntro(gate.firstLoad(l ?: intro)))
            }
        }
        // Keep-alive com aba vazia (processo recriado): entrada, mesmo com o fallback intro.htm.
        listOf(null, "about:blank", "").forEach { cur ->
            val a = PortalWebPolicy.keepAliveAction(p, cur, visible = false, fallbackUrl = PortalWebPolicy.keepAliveUrl(p, null))
            assertEquals(KeepAliveAction.Load(entryUrl, viaEntry = true), a)
        }
        // safeLoadUrl / reentryUrl sem estado: intro.htm (com ou sem www) vira a entrada.
        assertEquals(entryUrl, PortalWebPolicy.safeLoadUrl(p, intro))
        assertEquals(entryUrl, PortalWebPolicy.safeLoadUrl(p, "https://comprasnet.gov.br/intro.htm?x=1"))
        assertEquals(entryUrl, PortalWebPolicy.reentryUrl(p, reachedLoggedArea = false))
        // Outros destinos carregáveis não mudam.
        assertEquals(entryUrl, PortalWebPolicy.safeLoadUrl(p, entryUrl))
        assertEquals(sso, PortalWebPolicy.safeLoadUrl(p, sso))
    }

    @Test
    fun `intro htm so depois de o WebView ter estado na area logada nesta vida`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.firstLoad(entryUrl)
        // Fluxo de login (entrada → SSO → seleção de empresa) ainda não é área logada.
        listOf(entryUrl, sso, "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp").forEach {
            gate.onPageStarted(it); gate.onPageLoaded(it)
        }
        assertFalse(gate.reachedLoggedArea)
        assertEquals(entryUrl, gate.reentryTarget())
        // Chegou à área de trabalho logada pela entrada.
        gate.onPageStarted(intro); gate.onPageLoaded(intro)
        assertTrue(gate.reachedLoggedArea)
        assertEquals(intro, gate.reentryTarget())
        assertEquals(intro, PortalWebPolicy.safeLoadUrl(p, intro, gate.reachedLoggedArea))
        // "Não autorizado" no cnetmobile → volta à intro.
        gate.onPageStarted(cnet); gate.onPageLoaded(cnet)
        assertEquals(EntryAction.Navigate(intro), gate.onUnauthorized(cnet, autoLoginOn = false))
        // Caiu no www.gov.br: perdeu o estado → a próxima volta é pela entrada.
        gate.onPageStarted(govbr); gate.onPageLoaded(govbr)
        assertFalse(gate.reachedLoggedArea)
        assertEquals(entryUrl, gate.reentryTarget())
        assertEquals(
            KeepAliveAction.Load(entryUrl, viaEntry = true),
            PortalWebPolicy.keepAliveAction(p, govbr, visible = false, fallbackUrl = intro, reachedLoggedArea = gate.reachedLoggedArea),
        )
        // Nova aba (firstLoad) também zera.
        val g2 = PortalWebPolicy.EntryGate(p)
        g2.onPageLoaded(intro)
        assertTrue(g2.reachedLoggedArea)
        g2.firstLoad(entryUrl)
        assertFalse(g2.reachedLoggedArea)
    }

    @Test
    fun `outros portais mantem a ultima pagina com sessao`() {
        assertFalse(PortalWebPolicy.requiresEntryOnFreshTab(Portal.BLL))
        assertFalse(PortalWebPolicy.requiresEntryOnFreshTab(Portal.PNCP))
        assertEquals("https://bllcompras.com/Process/List", PortalWebPolicy.firstNavigationUrl(Portal.BLL, "https://bllcompras.com/Process/List"))
        assertFalse(PortalWebPolicy.isPublicLanding(Portal.BLL, "https://bllcompras.com/"))
    }

    // ------------------------------------------------------------------ 2. www.gov.br vindo do portal = não logado

    @Test
    fun `pagina publica do gov br - reconhecimento`() {
        assertTrue(PortalWebPolicy.isPublicLanding(p, govbr))
        assertTrue(PortalWebPolicy.isPublicLanding(p, "https://www.gov.br/compras/pt-br/acesso-a-informacao"))
        assertFalse(PortalWebPolicy.isPublicLanding(p, "https://www.gov.br/compras/pt-br/login")) // login
        assertFalse(PortalWebPolicy.isPublicLanding(p, sso))
        assertFalse(PortalWebPolicy.isPublicLanding(p, intro))
        assertFalse(PortalWebPolicy.isPublicLanding(p, cnet))
        assertFalse(PortalWebPolicy.isPublicLanding(p, "http://www.gov.br/compras/pt-br"))
        assertTrue(PortalWebPolicy.isPortalHostPage(p, intro))
        assertTrue(PortalWebPolicy.isPortalHostPage(p, cnet))
        assertFalse(PortalWebPolicy.isPortalHostPage(p, govbr))
        assertFalse(PortalWebPolicy.isPortalHostPage(p, sso))
    }

    @Test
    fun `gov br apos comprasnet - expira e uma ida a entrada oficial`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onScreenOpened()
        gate.firstLoad(intro) // (o bug: intro.htm) — mesmo assim, o redirecionamento ao gov.br é tratado
        gate.onPageStarted(intro); gate.onPageLoaded(intro)
        // main2.asp: window.top.location → www.gov.br/compras/pt-br (status DESCONECTADO: vale por ter vindo do portal).
        gate.onPageStarted(govbr); gate.onPageLoaded(govbr)
        assertEquals(EntryAction.LoginRequired(entryUrl), gate.onPublicLanding(govbr, sessionOpen = false))
        assertTrue(gate.entering)
        assertFalse(gate.reachedLoggedArea)
        // A entrada assenta no login: segue o login automático (se ligado) — senão expira; nunca intro.htm.
        gate.onPageStarted(entryUrl)
        assertEquals(EntryAction.StartAutoLogin, gate.onPage(entryUrl, settled = true, autoLoginOn = true, autoLoginRunning = false))

        // Vindo do cnetmobile (status CONECTADO) também.
        val g2 = PortalWebPolicy.EntryGate(p)
        g2.onPageStarted(cnet); g2.onPageLoaded(cnet)
        g2.onPageStarted(govbr)
        assertEquals(EntryAction.LoginRequired(entryUrl), g2.onPublicLanding(govbr, sessionOpen = true))
        g2.onPageStarted(entryUrl)
        assertEquals(EntryAction.Expire, g2.onPage(sso, settled = true, autoLoginOn = false, autoLoginRunning = false))
    }

    @Test
    fun `gov br sem vir do portal e sem sessao aberta - nada`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onPageStarted(govbr)
        assertEquals(EntryAction.None, gate.onPublicLanding(govbr, sessionOpen = false))
        // Saiu do SSO pelo logo do gov.br (entrada → SSO → gov.br): o vínculo com o portal foi desfeito.
        val g2 = PortalWebPolicy.EntryGate(p)
        g2.onPageStarted(entryUrl); g2.onPageStarted(sso); g2.onPageStarted(govbr)
        assertEquals(EntryAction.None, g2.onPublicLanding(govbr, sessionOpen = false))
        // ...mas com a sessão marcada como aberta, gov.br = não logado.
        assertEquals(EntryAction.LoginRequired(entryUrl), g2.onPublicLanding(govbr, sessionOpen = true))
        // Página que não é a pública: nunca.
        val g3 = PortalWebPolicy.EntryGate(p)
        g3.onPageStarted(intro)
        listOf(intro, sso, cnet, entryUrl).forEach { assertEquals(it, EntryAction.None, g3.onPublicLanding(it, sessionOpen = true)) }
        // Outro portal: nunca.
        assertEquals(EntryAction.None, PortalWebPolicy.EntryGate(Portal.BLL).onPublicLanding("https://bllcompras.com/", sessionOpen = true))
    }

    @Test
    fun `gov br - sem loop - uma ida a entrada por abertura`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onScreenOpened()
        gate.onPageStarted(intro); gate.onPageStarted(govbr)
        assertEquals(EntryAction.LoginRequired(entryUrl), gate.onPublicLanding(govbr, sessionOpen = true))
        // onPageFinished duplicado do mesmo gov.br (sessão já marcada expirada): nada.
        assertEquals(EntryAction.None, gate.onPublicLanding(govbr, sessionOpen = false))
        // A entrada voltou ao gov.br de novo: só marca, não navega mais.
        gate.onPageStarted(entryUrl); gate.onPageStarted(govbr)
        assertEquals(EntryAction.LoginRequired(null), gate.onPublicLanding(govbr, sessionOpen = false))
        assertFalse(gate.entering)
        repeat(5) {
            gate.onPageStarted(intro); gate.onPageStarted(govbr)
            val a = gate.onPublicLanding(govbr, sessionOpen = true)
            assertEquals(EntryAction.LoginRequired(null), a)
        }
        assertEquals(PortalWebPolicy.MAX_REENTRIES, gate.landingRedirects)
        // Nova abertura da tela: pode ir à entrada uma vez de novo.
        gate.onScreenOpened()
        gate.onPageStarted(intro); gate.onPageStarted(govbr)
        assertEquals(EntryAction.LoginRequired(entryUrl), gate.onPublicLanding(govbr, sessionOpen = true))
    }

    @Test
    fun `aba retida parada no gov br ao reabrir com sessao aberta - entrada`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onScreenOpened()
        assertEquals(EntryAction.LoginRequired(entryUrl), gate.onScreenReattached(govbr, loading = false, sessionOpen = true))
        val g2 = PortalWebPolicy.EntryGate(p)
        assertEquals(EntryAction.None, g2.onScreenReattached(govbr, loading = false, sessionOpen = false))
        assertEquals(EntryAction.None, g2.onScreenReattached(govbr, loading = true, sessionOpen = true))
    }

    @Test
    fun `gov br nunca conecta e com status conectado sai da area logada`() {
        assertEquals(Signal.NONE, PortalWebPolicy.evaluate(p, govbr, hasCookies = true, previousWasLoginPage = true, currentStatus = disc))
        assertTrue(PortalWebPolicy.leftLoggedArea(p, govbr))
        assertFalse(PortalWebPolicy.isLoginTransitPage(p, govbr))
        // Frame main2.asp do intro.htm não é a página pública (é do Comprasnet).
        assertFalse(PortalWebPolicy.isPublicLanding(p, main2))
    }

    // ------------------------------------------------------------------ 3. "Verificando sessão…"

    @Test
    fun `status conectado com aba vazia - verificando ate a primeira pagina conclusiva`() {
        assertEquals(SessionCheck.VERIFYING, PortalWebPolicy.initialSessionCheck(p, conn, freshTab = true))
        assertEquals(SessionCheck.NONE, PortalWebPolicy.initialSessionCheck(p, conn, freshTab = false))
        assertEquals(SessionCheck.NONE, PortalWebPolicy.initialSessionCheck(p, disc, freshTab = true))
        assertEquals(SessionCheck.NONE, PortalWebPolicy.initialSessionCheck(p, PortalConnectionStatus.SESSAO_EXPIRADA, freshTab = true))
        assertEquals(SessionCheck.NONE, PortalWebPolicy.initialSessionCheck(Portal.BLL, conn, freshTab = true))
        assertEquals("Verificando sessão…", PortalWebPolicy.sessionBadgeLabel(conn, SessionCheck.VERIFYING))

        val v = SessionCheck.VERIFYING
        // Páginas intermediárias da entrada (login, SSO, landing): continua verificando.
        listOf(entryUrl, sso, "https://www.comprasnet.gov.br/seguro/landing_sso.asp").forEach {
            assertEquals(it, v, PortalWebPolicy.resolveSessionCheck(p, v, it, Signal.NONE, contentExpired = false))
        }
        // Área logada → sessão aberta.
        assertEquals(SessionCheck.NONE, PortalWebPolicy.resolveSessionCheck(p, v, intro, Signal.NONE, false))
        assertEquals(SessionCheck.NONE, PortalWebPolicy.resolveSessionCheck(p, v, cnet, Signal.CONNECTED, false))
        assertEquals("Sessão aberta", PortalWebPolicy.sessionBadgeLabel(conn, SessionCheck.NONE))
        // gov.br público / expirado → login necessário.
        assertEquals(SessionCheck.LOGIN_REQUIRED, PortalWebPolicy.resolveSessionCheck(p, v, govbr, Signal.NONE, false))
        assertEquals(SessionCheck.LOGIN_REQUIRED, PortalWebPolicy.resolveSessionCheck(p, v, sso, Signal.EXPIRED, false))
        assertEquals(SessionCheck.LOGIN_REQUIRED, PortalWebPolicy.resolveSessionCheck(p, v, intro, Signal.NONE, contentExpired = true))
        assertEquals("Faça login no portal", PortalWebPolicy.sessionBadgeLabel(PortalConnectionStatus.SESSAO_EXPIRADA, SessionCheck.LOGIN_REQUIRED))
        assertEquals("Faça login no portal", PortalWebPolicy.sessionBadgeLabel(conn, SessionCheck.LOGIN_REQUIRED))
        // Fora da verificação, nada muda.
        assertEquals(SessionCheck.NONE, PortalWebPolicy.resolveSessionCheck(p, SessionCheck.NONE, govbr, Signal.NONE, false))
        assertEquals(SessionCheck.LOGIN_REQUIRED, PortalWebPolicy.resolveSessionCheck(p, SessionCheck.LOGIN_REQUIRED, intro, Signal.NONE, false))
        assertEquals("Sessão expirada", PortalWebPolicy.sessionBadgeLabel(PortalConnectionStatus.SESSAO_EXPIRADA, SessionCheck.NONE))
        // Verificando só vale enquanto o status persistido é CONECTADO.
        assertEquals("Faça login no portal", PortalWebPolicy.sessionBadgeLabel(disc, SessionCheck.VERIFYING))
    }

    @Test
    fun `verificacao - a entrada que assenta no login conclui login necessario`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onScreenOpened()
        assertEquals(entryUrl, gate.firstLoad(entryUrl, verify = true))
        assertTrue(gate.entering)
        gate.onPageStarted(entryUrl)
        assertEquals(EntryAction.None, gate.onPage(entryUrl, settled = false, autoLoginOn = false, autoLoginRunning = false))
        assertEquals(EntryAction.Expire, gate.onPage(entryUrl, settled = true, autoLoginOn = false, autoLoginRunning = false))
        // Com login automático: ele segue a partir da entrada (1x).
        val g2 = PortalWebPolicy.EntryGate(p)
        g2.firstLoad(entryUrl, verify = true)
        assertEquals(EntryAction.StartAutoLogin, g2.onPage(entryUrl, settled = true, autoLoginOn = true, autoLoginRunning = false))
        // Sem verificação (status não CONECTADO): a página de login da abertura não é "falha".
        val g3 = PortalWebPolicy.EntryGate(p)
        g3.firstLoad(entryUrl)
        assertEquals(EntryAction.None, g3.onPage(entryUrl, settled = true, autoLoginOn = false, autoLoginRunning = false))
        // A entrada levou à área logada: fim da ida, clique em compras eletrônicas.
        val g4 = PortalWebPolicy.EntryGate(p)
        g4.firstLoad(entryUrl, verify = true)
        assertEquals(EntryAction.OpenElectronicPurchases, g4.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))
        assertFalse(g4.entering)
        assertTrue(g4.reachedLoggedArea)
    }
}
