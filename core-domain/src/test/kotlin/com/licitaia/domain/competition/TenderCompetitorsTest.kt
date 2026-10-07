package com.licitaia.domain.competition

import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lista de concorrentes de uma licitação montada a partir dos resultados homologados do PNCP. */
class TenderCompetitorsTest {

    private val agency = "20918579000183"
    private val other = "11111111000111"

    private fun award(control: String, doc: String, name: String, hom: Double, est: Double, obj: String, item: Int = 1, at: Long = 1_000L) =
        PublicAwardResult(
            controlNumber = control, itemNumber = item, objectSummary = obj, supplierName = name, supplierDocument = doc,
            estimatedValue = est, homologatedValue = hom, resultDate = at,
        )

    @Test
    fun `agrupa mesmo orgao com objeto semelhante e separa o segmento`() {
        val results = listOf(
            award("$agency-1-000010/2025", "98765432000155", "Fibra Norte", 80_000.0, 100_000.0, "Link dedicado de internet", at = 5_000),
            award("$agency-1-000011/2025", "98765432000155", "Fibra Norte", 40_000.0, 50_000.0, "Link de internet 200 Mbps", at = 9_000),
            award("$agency-1-000012/2025", "22333444000155", "Papelaria X", 1_000.0, 2_000.0, "Material de escritório"),
            award("$other-1-000001/2025", "55666777000188", "Net Sul", 30_000.0, 40_000.0, "Acesso a internet banda larga"),
            award("$other-1-000002/2025", "12345678000190", "Nossa Empresa", 10_000.0, 12_000.0, "Link de internet"),
        )
        val view = TenderCompetitors.build(
            results, agency, TenderCompetitors.objectKeywords("Contratação de link dedicado de internet"),
            TenderCompetitors.segmentKeywords(Segment.TELECOM_ISP), ourDocument = "12.345.678/0001-90",
        )
        val agencyList = view.agencyCompetitors
        assertEquals(1, agencyList.size)
        val fibra = agencyList.single()
        assertEquals("Fibra Norte", fibra.name)
        assertEquals(2, fibra.wins)
        assertEquals(60_000.0, TenderCompetitors.averageHomologated(fibra), 0.01)
        assertEquals(20.0, fibra.avgDiscountPct!!, 0.01)
        assertEquals(9_000L, fibra.lastWinAt)
        assertEquals("98.765.432/0001-55", CompetitorRanking.formatDocument(fibra.document))
        // Segmento: a própria empresa não conta como concorrente.
        assertEquals(listOf("Net Sul"), view.segmentCompetitors.map { it.name })
        assertTrue(view.segment.ranking.any { it.isUs })
        assertEquals(1, view.publicCount)
        assertTrue(view.countFromAgency)
    }

    @Test
    fun `sem dados do orgao o numero vem do segmento e sem nada e estimativa`() {
        val seg = listOf(award("$other-1-000001/2025", "55666777000188", "Net Sul", 30_000.0, 40_000.0, "Link de internet"))
        val view = TenderCompetitors.build(seg, agency, listOf("internet"), emptyList(), null)
        assertEquals(1, view.publicCount)
        assertFalse(view.countFromAgency)
        val empty = TenderCompetitors.build(emptyList(), agency, listOf("internet"), emptyList(), null)
        assertNull(empty.publicCount)
        assertFalse(empty.hasData)
    }

    @Test
    fun `CNPJ do orgao e palavras do objeto`() {
        assertEquals(agency, TenderCompetitors.agencyCnpj("PNCP:$agency-1-000016/2025"))
        assertNull(TenderCompetitors.agencyCnpj("COMPRAS_GOV:160123-05-90012/2026"))
        val words = TenderCompetitors.objectKeywords("Contratação de empresa especializada para fornecimento de link dedicado de internet")
        assertTrue("dedicado" in words)
        assertTrue("internet" in words)
        assertFalse("contratacao" in words)
        assertFalse("empresa" in words)
    }
}
