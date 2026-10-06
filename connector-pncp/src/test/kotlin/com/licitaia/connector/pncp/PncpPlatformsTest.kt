package com.licitaia.connector.pncp

import com.licitaia.domain.model.Portal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Classificação de `usuarioNome` (valores reais observados na API do PNCP em 06/10/2026). */
class PncpPlatformsTest {

    @Test
    fun `Compras gov br e variantes viram COMPRAS_GOV`() {
        assertEquals(Portal.COMPRAS_GOV, PncpPlatforms.classify("Compras.gov.br"))
        assertEquals(Portal.COMPRAS_GOV, PncpPlatforms.classify("COMPRAS.GOV.BR"))
        assertEquals(Portal.COMPRAS_GOV, PncpPlatforms.classify("Comprasnet"))
        assertEquals(Portal.COMPRAS_GOV, PncpPlatforms.classify("  compras gov  "))
    }

    @Test
    fun `Licitanet BLL e Portal de Compras Publicas`() {
        assertEquals(Portal.LICITANET, PncpPlatforms.classify("Licitanet Licitações Eletrônicas LTDA"))
        assertEquals(Portal.BLL, PncpPlatforms.classify("BLL Compras"))
        assertEquals(Portal.BLL, PncpPlatforms.classify("Bolsa de Licitações e Leilões do Brasil"))
        assertEquals(Portal.PORTAL_COMPRAS_PUBLICAS, PncpPlatforms.classify("Portal de Compras Públicas"))
        assertEquals(Portal.PORTAL_COMPRAS_PUBLICAS, PncpPlatforms.classify("ECustomize - Portal de Compras Publicas"))
        // Empresa do Portal de Compras Públicas: registros reais apontam para portaldecompraspublicas.com.br.
        assertEquals(Portal.PORTAL_COMPRAS_PUBLICAS, PncpPlatforms.classify("ECustomize Consultoria em Software S.A"))
    }

    @Test
    fun `host do sistema de origem desempata quando o nome nao identifica`() {
        assertEquals(Portal.PORTAL_COMPRAS_PUBLICAS, PncpPlatforms.classify("Outro", "https://portaldecompraspublicas.com.br/processos/MG/x"))
        assertEquals(Portal.COMPRAS_GOV, PncpPlatforms.classify(null, "https://www.comprasnet.gov.br/x"))
        assertEquals(Portal.COMPRAS_GOV, PncpPlatforms.classify(null, "https://cnetmobile.compras.gov.br/x"))
        assertEquals(Portal.LICITANET, PncpPlatforms.classify("", "https://licitanet.com.br/sessao/1"))
        assertEquals(Portal.BLL, PncpPlatforms.classify(null, "https://bll.org.br/processo/9"))
        assertEquals(Portal.PNCP, PncpPlatforms.classify("Licitar Digital", "https://app2.licitardigital.com.br/pesquisa/1"))
        assertEquals(Portal.PNCP, PncpPlatforms.classify(null, "https://evil-bll.org.br.example.com/x"))
    }

    @Test
    fun `demais plataformas e ausentes ficam como PNCP`() {
        listOf(
            "Licitar Digital - Plataforma de Licitações Online",
            "IPM Sistemas",
            "Bolsa Nacional De Compras - BNC", // não é a BLL
            "Novo BBMNET Licitações",
            "Licitações-E BB",
            "BAHIA SECRETARIA DA ADMINISTRACAO",
            "Comprasbr", // não é Compras.gov.br
            "",
        ).forEach { assertEquals(it, Portal.PNCP, PncpPlatforms.classify(it)) }
        assertEquals(Portal.PNCP, PncpPlatforms.classify(null))
    }

    @Test
    fun `nome exibivel curto`() {
        assertEquals("Licitar Digital", PncpPlatforms.displayName("Licitar Digital - Plataforma de Licitações Online"))
        assertEquals("Compras.gov.br", PncpPlatforms.displayName("Compras.gov.br"))
        assertEquals("Portal de Compras Públicas", PncpPlatforms.displayName("ECustomize Consultoria em Software S.A"))
        assertEquals("IPM Sistemas", PncpPlatforms.displayName("  IPM   Sistemas "))
        assertNull(PncpPlatforms.displayName("   "))
        assertNull(PncpPlatforms.displayName(null))
    }
}
