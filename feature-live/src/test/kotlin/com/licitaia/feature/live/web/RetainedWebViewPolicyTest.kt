package com.licitaia.feature.live.web

import com.licitaia.domain.model.Portal
import com.licitaia.feature.live.web.PortalWebPolicy.KeepAliveAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras puras do WebView retido (keep-alive na mesma aba) e do certificado digital (KeyChain). */
class RetainedWebViewPolicyTest {

    private val p = Portal.COMPRAS_GOV
    private val area = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras"
    private val fallback = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/participacoes"

    @Test
    fun `area logada fora da tela - reload na mesma aba (sessionStorage sobrevive)`() {
        assertEquals(KeepAliveAction.Reload, PortalWebPolicy.keepAliveAction(p, area, visible = false, fallbackUrl = fallback))
    }

    @Test
    fun `usuario vendo a tela - nao mexe na pagina`() {
        assertEquals(KeepAliveAction.Skip, PortalWebPolicy.keepAliveAction(p, area, visible = true, fallbackUrl = fallback))
        assertEquals(KeepAliveAction.Skip, PortalWebPolicy.keepAliveAction(p, null, visible = true, fallbackUrl = fallback))
    }

    private val workspace = KeepAliveAction.Load("https://www.comprasnet.gov.br/intro.htm", viaEntry = true)
    private val entry = KeepAliveAction.Load("https://www.comprasnet.gov.br/seguro/loginPortal.asp?perfil=1", viaEntry = true)

    @Test
    fun `processo recriado (aba vazia) - abre a entrada oficial, nunca intro htm nem o cnetmobile`() {
        assertEquals(entry, PortalWebPolicy.keepAliveAction(p, null, visible = false, fallbackUrl = fallback))
        assertEquals(entry, PortalWebPolicy.keepAliveAction(p, "about:blank", visible = false, fallbackUrl = fallback))
        assertEquals(entry, PortalWebPolicy.keepAliveAction(p, null, visible = false, fallbackUrl = PortalWebPolicy.keepAliveUrl(p, fallback)))
        assertEquals(entry, PortalWebPolicy.keepAliveAction(p, null, visible = false, fallbackUrl = "https://www.comprasnet.gov.br/intro.htm"))
        assertEquals(KeepAliveAction.Skip, PortalWebPolicy.keepAliveAction(p, null, visible = false, fallbackUrl = null))
        // Aba que já esteve na área logada nesta vida: pode voltar à área de trabalho.
        assertEquals(workspace, PortalWebPolicy.keepAliveAction(p, null, visible = false, fallbackUrl = fallback, reachedLoggedArea = true))
        // Portal sem área de trabalho fixa nem host "só pelo link": abre a última URL normalmente.
        assertEquals(
            KeepAliveAction.Load("https://bllcompras.com/Process/List"),
            PortalWebPolicy.keepAliveAction(Portal.BLL, null, false, "https://bllcompras.com/Process/List"),
        )
    }

    @Test
    fun `aba parada no login ou fora da allowlist - entrada (sem estado) ou area de trabalho (com estado)`() {
        assertEquals(entry, PortalWebPolicy.keepAliveAction(p, "https://sso.acesso.gov.br/login?x=1", false, fallback))
        assertEquals(entry, PortalWebPolicy.keepAliveAction(p, "https://evil.example.com/", false, fallback))
        assertEquals(workspace, PortalWebPolicy.keepAliveAction(p, "https://sso.acesso.gov.br/login?x=1", false, fallback, reachedLoggedArea = true))
        // fallback fora da allowlist nunca é carregado
        assertEquals(KeepAliveAction.Skip, PortalWebPolicy.keepAliveAction(p, null, false, "https://evil.example.com/"))
    }

    @Test
    fun `certificado so para hosts https da allowlist do portal`() {
        assertTrue(PortalWebPolicy.clientCertAllowed(p, "sso.acesso.gov.br"))
        assertTrue(PortalWebPolicy.clientCertAllowed(p, "certificado.sso.acesso.gov.br"))
        assertTrue(PortalWebPolicy.clientCertAllowed(p, "cnetmobile.estaleiro.serpro.gov.br"))
        assertFalse(PortalWebPolicy.clientCertAllowed(p, "evil.example.com"))
        assertFalse(PortalWebPolicy.clientCertAllowed(p, "acesso.gov.br.evil.com"))
        assertFalse(PortalWebPolicy.clientCertAllowed(p, null))
        assertFalse(PortalWebPolicy.clientCertAllowed(p, ""))
        assertFalse(PortalWebPolicy.clientCertAllowed(p, "sso.acesso.gov.br/../x"))
    }

    @Test
    fun `alias lembrado por empresa e host, com indice para trocar`() {
        assertEquals("portal.clientcert.7.sso.acesso.gov.br", PortalWebPolicy.clientCertAliasKey(7, "SSO.Acesso.gov.br"))
        assertTrue(PortalWebPolicy.clientCertAliasKey(7, "a.gov.br") != PortalWebPolicy.clientCertAliasKey(8, "a.gov.br"))
        val idx = PortalWebPolicy.formatHostIndex(setOf("b.gov.br", "a.gov.br"))
        assertEquals("a.gov.br,b.gov.br", idx)
        assertEquals(setOf("a.gov.br", "b.gov.br"), PortalWebPolicy.parseHostIndex(idx))
        assertEquals(emptySet<String>(), PortalWebPolicy.parseHostIndex(null))
    }
}
