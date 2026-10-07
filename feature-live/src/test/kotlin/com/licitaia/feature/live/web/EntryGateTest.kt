package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.feature.live.web.PortalWebPolicy.EntryAction
import com.licitaia.feature.live.web.PortalWebPolicy.KeepAliveAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compras.gov.br: o app NUNCA abre o cnetmobile por loadUrl (só o link "Licitação e Dispensa (novo)" do menu do
 * Comprasnet entrega o token); "Não autorizado" → volta à área de trabalho (intro.htm) e repete o clique uma vez.
 */
class EntryGateTest {
    private val p = Portal.COMPRAS_GOV
    private val entryUrl = "https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1"
    private val intro = "https://www.comprasnet.gov.br/intro.htm"
    private val cnetHome = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra="
    private val last = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/participacoes?compra=90001"
    private val sso = "https://sso.acesso.gov.br/login?client_id=comprasnet.gov.br"
    private val comprasnetArea = "https://www.comprasnet.gov.br/seguro/fornecedor/menu.asp"
    private val conn = PortalConnectionStatus.CONECTADO

    private fun isCnet(url: String?) = url != null && PortalWebPolicy.host(url) == "cnetmobile.estaleiro.serpro.gov.br"

    @Test
    fun `nenhuma decisao devolve loadUrl do cnetmobile`() {
        assertEquals(intro, PortalWebPolicy.workspaceUrl(p))
        assertTrue(PortalWebPolicy.needsEntryFirst(p, cnetHome))
        assertFalse(PortalWebPolicy.canLoadDirectly(p, last))
        assertTrue(PortalWebPolicy.canLoadDirectly(p, intro))
        // Abertura (aba sem estado), mesmo com sessão: SEMPRE a entrada oficial (nem cnetmobile, nem intro, nem última URL).
        listOf(cnetHome, last, null, intro, comprasnetArea, "https://sso.acesso.gov.br/login").forEach { l ->
            assertEquals(entryUrl, PortalWebPolicy.openUrl(p, conn, l))
        }
        assertEquals(entryUrl, PortalWebPolicy.openUrl(p, PortalConnectionStatus.SESSAO_EXPIRADA, last))
        // Última URL / keep-alive / primeira navegação / safeLoadUrl.
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, last))
        assertNull(PortalWebPolicy.sanitizeResumeUrl(p, cnetHome))
        assertEquals(intro, PortalWebPolicy.keepAliveUrl(p, last))
        assertEquals(intro, PortalWebPolicy.keepAliveUrl(p, null))
        listOf(cnetHome, last).forEach { t ->
            assertEquals(entryUrl, PortalWebPolicy.firstNavigationUrl(p, t))
            assertEquals(entryUrl, PortalWebPolicy.safeLoadUrl(p, t))
            // Aba que já esteve na área logada: o cnetmobile vira a área de trabalho.
            assertEquals(intro, PortalWebPolicy.safeLoadUrl(p, t, reachedLoggedArea = true))
            val gate = PortalWebPolicy.EntryGate(p)
            gate.onPageStarted(intro) // mesmo já tendo passado pela entrada
            val first = gate.firstLoad(t)
            assertEquals(entryUrl, first)
            assertTrue(gate.entering)
            // keep-alive com aba vazia/fora da área logada e fallback cnetmobile: entrada (sem estado) ou intro (com estado).
            listOf(null, "about:blank", sso, "https://www.gov.br/compras/pt-br").forEach { cur ->
                val a = PortalWebPolicy.keepAliveAction(p, cur, visible = false, fallbackUrl = t)
                assertTrue("$cur → $a", a is KeepAliveAction.Load && !isCnet(a.url) && a.url == entryUrl)
                val b = PortalWebPolicy.keepAliveAction(p, cur, visible = false, fallbackUrl = t, reachedLoggedArea = true)
                assertTrue("$cur → $b", b is KeepAliveAction.Load && !isCnet(b.url) && b.url == intro)
            }
        }
        // Destino que pode ser aberto por URL não muda.
        val gate = PortalWebPolicy.EntryGate(p)
        assertEquals(entryUrl, gate.firstLoad(entryUrl))
        assertFalse(gate.entering)
        // Nem o "Não autorizado", nem a chegada à área logada devolvem Navigate para o cnetmobile.
        val g = PortalWebPolicy.EntryGate(p)
        val actions = listOf(
            g.onUnauthorized(last, autoLoginOn = true),
            g.onPage(intro, settled = false, autoLoginOn = true, autoLoginRunning = false),
            g.onPage(cnetHome, settled = false, autoLoginOn = true, autoLoginRunning = false),
            g.onUnauthorized(last, autoLoginOn = true),
            g.onPage(sso, settled = true, autoLoginOn = true, autoLoginRunning = false),
        )
        actions.forEach { a -> assertFalse("$a", a is EntryAction.Navigate && isCnet(a.url)) }
    }

    @Test
    fun `nao autorizado - volta para intro htm e repete o clique uma vez`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onPageStarted(intro)
        assertEquals(EntryAction.Navigate(intro), gate.onUnauthorized(last, autoLoginOn = false))
        assertTrue(gate.entering)
        assertEquals(1, gate.reentries)
        // Avisos repetidos da página antiga antes de a área de trabalho carregar: ignorados.
        assertEquals(EntryAction.None, gate.onUnauthorized(last, autoLoginOn = false))
        gate.onPageStarted(intro)
        // Chegou logado à área de trabalho: repete o clique no link do portal (mesmo com o login automático desligado).
        assertEquals(EntryAction.OpenElectronicPurchases, gate.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))
        assertFalse(gate.entering)
        // onPageFinished duplicado da intro: não clica de novo.
        assertEquals(EntryAction.None, gate.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))
    }

    @Test
    fun `sem loop - no maximo uma volta e dois cliques automaticos por abertura`() {
        val gate = PortalWebPolicy.EntryGate(p)
        // Login automático ligado: 1º clique ao chegar logado na intro.
        assertEquals(EntryAction.OpenElectronicPurchases, gate.onPage(intro, settled = false, autoLoginOn = true, autoLoginRunning = false))
        // Voltar (botão) para a intro não dispara outro clique automático.
        assertEquals(EntryAction.None, gate.onPage(intro, settled = false, autoLoginOn = true, autoLoginRunning = false))
        // "Não autorizado": uma volta à intro + 1 repetição do clique.
        assertEquals(EntryAction.Navigate(intro), gate.onUnauthorized(last, autoLoginOn = false))
        gate.onPageStarted(intro)
        assertEquals(EntryAction.OpenElectronicPurchases, gate.onPage(intro, settled = false, autoLoginOn = true, autoLoginRunning = false))
        assertEquals(PortalWebPolicy.MAX_AUTO_ELECTRONIC_CLICKS, gate.autoElectronicClicks)
        // De novo "Não autorizado": não volta mais (sem login automático) → expira; nunca mais navega.
        assertEquals(EntryAction.Expire, gate.onUnauthorized(last, autoLoginOn = false))
        assertEquals(EntryAction.Expire, gate.onUnauthorized(last, autoLoginOn = false))
        assertEquals(EntryAction.None, gate.onPage(intro, settled = false, autoLoginOn = true, autoLoginRunning = false))
        assertEquals(1, gate.reentries)
        // Com login automático: uma tentativa dele, depois expira.
        val g2 = PortalWebPolicy.EntryGate(p)
        g2.onUnauthorized(last, autoLoginOn = true)
        g2.onPageStarted(intro)
        assertEquals(EntryAction.StartAutoLogin, g2.onUnauthorized(last, autoLoginOn = true))
        assertEquals(EntryAction.Expire, g2.onUnauthorized(last, autoLoginOn = true))
        // Nova abertura da tela: zera os contadores (a aba continua "tendo passado pela entrada").
        gate.onScreenOpened()
        assertEquals(0, gate.reentries)
        assertEquals(0, gate.autoElectronicClicks)
        assertTrue(gate.passedEntry)
        assertEquals(EntryAction.Navigate(intro), gate.onUnauthorized(last, autoLoginOn = false))
    }

    @Test
    fun `clique automatico sempre (com ou sem login automatico), so com sessao aberta e na area de trabalho do comprasnet`() {
        val off = PortalWebPolicy.EntryGate(p)
        assertEquals(EntryAction.OpenElectronicPurchases, off.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))
        assertEquals(EntryAction.None, off.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))
        val closed = PortalWebPolicy.EntryGate(p)
        assertEquals(EntryAction.None, closed.onPage(intro, settled = false, autoLoginOn = true, autoLoginRunning = false, sessionOpen = false))
        assertEquals(0, closed.autoElectronicClicks)
        val cnet = PortalWebPolicy.EntryGate(p)
        assertEquals(EntryAction.None, cnet.onPage(cnetHome, settled = false, autoLoginOn = true, autoLoginRunning = false))
        val seguro = PortalWebPolicy.EntryGate(p)
        assertEquals(EntryAction.OpenElectronicPurchases, seguro.onPage(comprasnetArea, settled = false, autoLoginOn = true, autoLoginRunning = false))
        // Outro portal: nunca.
        val bll = PortalWebPolicy.EntryGate(Portal.BLL)
        assertEquals(EntryAction.None, bll.onPage("https://bllcompras.com/Home/Index", settled = false, autoLoginOn = true, autoLoginRunning = false))
    }

    @Test
    fun `intro redirecionou ao login - segue login automatico ou expira, so depois de assentar`() {
        val gate = PortalWebPolicy.EntryGate(p)
        assertEquals(EntryAction.Navigate(intro), gate.onUnauthorized(last, autoLoginOn = false))
        gate.onPageStarted(entryUrl)
        assertEquals(EntryAction.None, gate.onPage(sso, settled = false, autoLoginOn = false, autoLoginRunning = false))
        assertEquals(EntryAction.Expire, gate.onPage(sso, settled = true, autoLoginOn = false, autoLoginRunning = false))
        assertFalse(gate.entering)
        // Seleção de perfil ("Acesse sua Conta") também conta como login exigido.
        val g2 = PortalWebPolicy.EntryGate(p)
        g2.onUnauthorized(last, autoLoginOn = false)
        assertEquals(EntryAction.Expire, g2.onPage(entryUrl, settled = true, autoLoginOn = false, autoLoginRunning = false))
        // Seleção de empresa não é falha de login.
        val g3 = PortalWebPolicy.EntryGate(p)
        g3.onUnauthorized(last, autoLoginOn = false)
        assertEquals(
            EntryAction.None,
            g3.onPage("https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp", settled = true, autoLoginOn = false, autoLoginRunning = false),
        )
        // Login automático ligado: tenta uma vez e só expira quando ele para.
        val g4 = PortalWebPolicy.EntryGate(p)
        g4.onUnauthorized(last, autoLoginOn = true)
        assertEquals(EntryAction.StartAutoLogin, g4.onPage(sso, settled = true, autoLoginOn = true, autoLoginRunning = false))
        assertEquals(EntryAction.None, g4.onPage(sso, settled = true, autoLoginOn = true, autoLoginRunning = true))
        assertEquals(EntryAction.Expire, g4.onAutoLoginStopped(sso))
        assertEquals(EntryAction.None, PortalWebPolicy.EntryGate(p).onAutoLoginStopped(sso))
    }

    @Test
    fun `ao abrir a tela - na intro clica o link, no cnetmobile nao faz nada`() {
        // Aba retida na área de trabalho logada: clica (1x por abertura), sem login automático.
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onScreenOpened()
        assertEquals(EntryAction.OpenElectronicPurchases, gate.onScreenReattached(intro, loading = false, sessionOpen = true))
        // O onPageFinished da mesma intro / o usuário voltando à intro dentro da tela: não força de novo.
        assertEquals(EntryAction.None, gate.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))
        assertEquals(EntryAction.None, gate.onScreenReattached(intro, loading = false, sessionOpen = true))
        // Sair e entrar de novo: nova abertura → clica de novo.
        gate.onScreenOpened()
        assertEquals(EntryAction.OpenElectronicPurchases, gate.onScreenReattached(comprasnetArea, loading = false, sessionOpen = true))

        // Já no cnetmobile (ex.: sala de disputa): não navega, e voltar à intro dentro da tela não redireciona.
        val cnet = PortalWebPolicy.EntryGate(p)
        cnet.onScreenOpened()
        listOf(cnetHome, last).forEach { assertEquals(EntryAction.None, cnet.onScreenReattached(it, loading = false, sessionOpen = true)) }
        assertEquals(EntryAction.None, cnet.onPage(intro, settled = false, autoLoginOn = true, autoLoginRunning = false))
        assertEquals(0, cnet.autoElectronicClicks)
        // ...mas o "Não autorizado" continua podendo repetir o clique uma vez.
        assertEquals(EntryAction.Navigate(intro), cnet.onUnauthorized(last, autoLoginOn = false))
        cnet.onPageStarted(intro)
        assertEquals(EntryAction.OpenElectronicPurchases, cnet.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))

        // Ainda carregando / sessão fechada / fora da área logada / aba vazia / outro portal: nada agora.
        val g = PortalWebPolicy.EntryGate(p)
        assertEquals(EntryAction.None, g.onScreenReattached(intro, loading = true, sessionOpen = true))
        assertEquals(EntryAction.None, g.onScreenReattached(intro, loading = false, sessionOpen = false))
        listOf(null, "", "about:blank", sso, entryUrl).forEach {
            assertEquals("$it", EntryAction.None, g.onScreenReattached(it, loading = false, sessionOpen = true))
        }
        // Não consumiu: ao terminar de carregar a intro, o onPage clica.
        assertEquals(EntryAction.OpenElectronicPurchases, g.onPage(intro, settled = false, autoLoginOn = false, autoLoginRunning = false))
        assertEquals(EntryAction.None, PortalWebPolicy.EntryGate(Portal.BLL).onScreenReattached("https://bllcompras.com/Home/Index", loading = false, sessionOpen = true))
    }

    @Test
    fun `passou pela entrada so ao carregar o Comprasnet`() {
        val gate = PortalWebPolicy.EntryGate(p)
        gate.onPageStarted("https://www.gov.br/compras/pt-br")
        gate.onPageStarted(sso)
        assertFalse(gate.passedEntry)
        gate.onPageStarted(entryUrl)
        assertTrue(gate.passedEntry)
    }

    @Test
    fun `reconhecimento do texto do link licitacao e dispensa (novo)`() {
        val menu = listOf(
            "Dados Cadastrais", "Compras", "SICAF", "Contratos", "Sair",
            "Pregão e Concorrência (legado)", "Dispensa e Cotação Eletrônica (legado)",
            "Compras  Licitação e Dispensa (novo)  Pregão e Concorrência (legado)",
            "Licitação  e Dispensa (novo)", "RDC Eletrônico", "Aviso de Licitações por e-mail", "Serviço de download de Editais",
        )
        assertEquals(8, PortalWebPolicy.pickElectronicLink(p, menu))
        assertEquals(1, PortalWebPolicy.pickElectronicLink(p, listOf("Sair", "LICITACAO E DISPENSA")))
        assertNull(PortalWebPolicy.pickElectronicLink(p, listOf("Pregão e Concorrência (legado)", "Licitação e Dispensa (legado)", "Compras")))
        assertNull(PortalWebPolicy.pickElectronicLink(Portal.BLL, menu))
        val js = PortalWebPolicy.electronicLinkScript(p)
        assertTrue(js.contains("'licitacao e dispensa (novo)'"))
        assertTrue(js.contains("'legado'"))
        assertTrue(js.contains(".click()"))
        assertFalse(js.contains("cnetmobile"))
        assertFalse(js.contains("cookie", ignoreCase = true))
        assertFalse(js.contains("input", ignoreCase = true))
        assertTrue(PortalWebPolicy.parseElectronicLinkResult("\"clicked\""))
        assertFalse(PortalWebPolicy.parseElectronicLinkResult("\"notfound\""))
        assertFalse(PortalWebPolicy.parseElectronicLinkResult(null))
        // Botão "Compras eletrônicas": só na área de trabalho logada do Comprasnet.
        assertTrue(PortalWebPolicy.canGoToElectronicPurchases(p, intro))
        assertTrue(PortalWebPolicy.canGoToElectronicPurchases(p, comprasnetArea))
        assertFalse(PortalWebPolicy.canGoToElectronicPurchases(p, entryUrl))
        assertFalse(PortalWebPolicy.canGoToElectronicPurchases(p, "https://www.comprasnet.gov.br/seguro/loginFornecedorSelecEmpresa.asp"))
        assertFalse(PortalWebPolicy.canGoToElectronicPurchases(p, cnetHome))
        assertFalse(PortalWebPolicy.canGoToElectronicPurchases(p, "https://www.gov.br/compras/pt-br"))
        assertFalse(PortalWebPolicy.canGoToElectronicPurchases(p, null))
        assertFalse(PortalWebPolicy.canGoToElectronicPurchases(Portal.BLL, "https://bllcompras.com/Home/Index"))
    }

    @Test
    fun `intro htm e area logada (conecta vindo do login) e nao e pagina de passagem`() {
        assertTrue(PortalWebPolicy.isLoggedArea(p, intro))
        assertFalse(PortalWebPolicy.isLoginTransitPage(p, intro))
        assertFalse(PortalWebPolicy.isLoginTransitPage(p, "https://www.gov.br/compras/pt-br"))
        assertFalse(PortalWebPolicy.isLoginTransitPage(p, entryUrl))
        assertEquals(
            PortalWebPolicy.Signal.CONNECTED,
            PortalWebPolicy.evaluate(p, intro, hasCookies = true, previousWasLoginPage = true, currentStatus = PortalConnectionStatus.DESCONECTADO),
        )
        assertEquals(intro, PortalWebPolicy.sanitizeResumeUrl(p, intro))
        // Keep-alive na intro (ou no cnetmobile já aberto): reload da página atual, sem navegar.
        assertEquals(KeepAliveAction.Reload, PortalWebPolicy.keepAliveAction(p, intro, visible = false, fallbackUrl = intro))
        assertEquals(KeepAliveAction.Reload, PortalWebPolicy.keepAliveAction(p, last, visible = false, fallbackUrl = intro))
    }
}
