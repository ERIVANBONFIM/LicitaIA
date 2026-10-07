package com.licitaia.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Tenho interesse" sem análise automática, links "Abrir no portal"/"Ver no PNCP" e arquivadas. */
class InterestAndPortalLinksTest {

    @Test
    fun `interesse nao analisa nem baixa nada com a configuracao desligada (padrao)`() {
        assertFalse(AppSettings().autoAnalyzeOnInterest)
        val plan = InterestFollowUp.plan(autoAnalyzeOnInterest = AppSettings().autoAnalyzeOnInterest, hasPncpControl = true)
        assertTrue(plan.idle)
        assertFalse(plan.analyze)
        assertFalse(plan.downloadOfficialEdital)
        assertEquals(TenderStatus.INTERESSE, InterestFollowUp.INITIAL_STATUS)
    }

    @Test
    fun `configuracao ligada volta o comportamento antigo`() {
        assertEquals(InterestFollowUp.Plan(downloadOfficialEdital = true, analyze = true), InterestFollowUp.plan(true, hasPncpControl = true))
        assertEquals(InterestFollowUp.Plan(downloadOfficialEdital = false, analyze = true), InterestFollowUp.plan(true, hasPncpControl = false))
    }

    @Test
    fun `analise interrompida nao e retomada sozinha`() {
        assertEquals(TenderStatus.INTERESSE, InterestFollowUp.recoverInterrupted(hasAnalysis = false))
        assertEquals(TenderStatus.ANALISADA, InterestFollowUp.recoverInterrupted(hasAnalysis = true))
    }

    @Test
    fun `pagina do PNCP a partir do numero de controle`() {
        assertEquals("https://pncp.gov.br/app/editais/20918579000183/2025/16", PortalLinks.pncpPageUrl("PNCP:20918579000183-1-000016/2025"))
        assertEquals("https://pncp.gov.br/app/editais/20918579000183/2025/16", PortalLinks.pncpPageUrl("COMPRAS_GOV:20918579000183-1-000016/2025"))
        assertNull(PortalLinks.pncpPageUrl("COMPRAS_GOV:160123-05-90012/2026"))
        assertNull(PortalLinks.pncpPageUrl("MANUAL:BLL:123/2026"))
    }

    @Test
    fun `compras gov abre no navegador interno pesquisando UASG e numero`() {
        val target = PortalLinks.comprasTarget(Portal.COMPRAS_GOV, "160123", "90012/2026", Modality.PREGAO_ELETRONICO)!!
        assertEquals("160123", target.uasg)
        assertEquals("90012", target.number)
        assertEquals(2026, target.year)
        assertEquals("90012/2026", target.numberYear)
        val open = PortalLinks.openTarget(Portal.COMPRAS_GOV, "https://cnetmobile.estaleiro.serpro.gov.br/x", target, null)
        assertEquals(PortalLinks.Target.Internal(Portal.COMPRAS_GOV, url = null, compras = target), open)
        assertEquals("Abrir no Compras.gov.br · UASG 160123 · nº 90012/2026", PortalLinks.buttonLabel(Portal.COMPRAS_GOV, "160123", "90012/2026"))
        // UASG curta recebe zeros; sem ano não há alvo de pesquisa.
        assertEquals("001234", PortalLinks.comprasTarget(Portal.COMPRAS_GOV, "1234", "5/2026", Modality.DISPENSA_ELETRONICA)!!.uasg)
        assertNull(PortalLinks.comprasTarget(Portal.COMPRAS_GOV, "160123", "90012", Modality.PREGAO_ELETRONICO))
        assertNull(PortalLinks.comprasTarget(Portal.BLL, "160123", "90012/2026", Modality.PREGAO_ELETRONICO))
    }

    @Test
    fun `demais portais abrem a URL oficial da compra no navegador interno so quando o host e do portal`() {
        val bll = "https://bllcompras.com/Process/ProcessView?param1=abc"
        assertEquals(PortalLinks.Target.Internal(Portal.BLL, url = bll), PortalLinks.openTarget(Portal.BLL, bll, null, null))
        val outside = "https://outro-sistema.com.br/compra/1"
        assertEquals(PortalLinks.Target.External(outside), PortalLinks.openTarget(Portal.LICITANET, outside, null, null))
        assertEquals(PortalLinks.Target.Internal(Portal.PORTAL_COMPRAS_PUBLICAS), PortalLinks.openTarget(Portal.PORTAL_COMPRAS_PUBLICAS, null, null, null))
        val pncp = "https://pncp.gov.br/app/editais/1/2025/1"
        assertEquals(PortalLinks.Target.External(pncp), PortalLinks.openTarget(Portal.PNCP, null, null, pncp))
        assertTrue(PortalLinks.belongsTo(Portal.LICITANET, "https://portal.licitanet.com.br/compra/9"))
        assertFalse(PortalLinks.belongsTo(Portal.LICITANET, "http://portal.licitanet.com.br/compra/9"))
        assertFalse(PortalLinks.belongsTo(Portal.BLL, "https://bll.org.br.golpe.com/x"))
    }

    @Test
    fun `nome de arquivo seguro para Downloads`() {
        assertEquals("Edital_ 90012_2026.pdf", OfficialFileNames.sanitize("Edital: 90012/2026", "Edital", 1))
        assertEquals("Termo de Referência.docx", OfficialFileNames.sanitize("Termo de Referência.docx", null, 2))
        assertEquals("Anexo.pdf", OfficialFileNames.sanitize("   ", "Anexo", 3))
        assertEquals("documento-4.pdf", OfficialFileNames.sanitize("", null, 4))
        assertTrue(OfficialFileNames.sanitize("x".repeat(300), null, null).length <= 120)
    }

    private fun tender(id: Long, opp: String) = Tender(
        id = id, companyId = 1, opportunityId = opp, portal = Portal.COMPRAS_GOV, number = "$id/2026", agency = "Órgão $id",
        objectDescription = "Link de internet", modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP, uf = "MG",
        city = "BH", estimatedValue = 1.0, proposalDeadline = 0, sessionAt = 0, uasg = "160123",
    )

    @Test
    fun `arquivadas somem das listas e aparecem na tela de arquivadas com busca`() {
        val a = tender(1, "PNCP:a")
        val b = tender(2, "PNCP:b")
        assertEquals(listOf(b), ArchiveRules.visibleTenders(listOf(a, b), setOf("PNCP:a")))
        val entries = ArchiveRules.sort(
            listOf(
                ArchivedEntry("PNCP:x", 10, null, null),
                ArchivedEntry("PNCP:a", 20, a, null),
            ),
        )
        assertEquals("PNCP:a", entries.first().opportunityId)
        assertTrue(entries.first().matches("160123"))
        assertTrue(entries.first().matches("orgao 1"))
        assertFalse(entries.first().matches("radiologia"))
        assertEquals("x", entries.last().number)
    }
}
