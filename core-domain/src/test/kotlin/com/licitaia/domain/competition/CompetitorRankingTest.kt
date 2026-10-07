package com.licitaia.domain.competition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitorRankingTest {

    private fun award(
        control: String, item: Int, name: String, doc: String, hom: Double, est: Double = 0.0,
        at: Long = 1L, discount: Double? = null,
    ) = PublicAwardResult(
        controlNumber = control, itemNumber = item, supplierName = name, supplierDocument = doc,
        estimatedValue = est, homologatedValue = hom, publishedDiscountPct = discount, resultDate = at,
    )

    @Test
    fun `ranking por vitorias, desempate por valor, com duplicatas removidas`() {
        val results = listOf(
            award("A", 1, "Fibra Norte", "12.345.678/0001-90", 40_000.0, est = 50_000.0),
            award("A", 1, "Fibra Norte", "12345678000190", 40_000.0, est = 50_000.0), // duplicata (mesma chave)
            award("B", 1, "Fibra Norte", "12345678000190", 30_000.0, est = 40_000.0),
            award("B", 2, "Rede Sul", "11222333000144", 90_000.0, est = 100_000.0),
            award("C", 1, "Conecta", "99888777000166", 10_000.0),
        )
        val summary = CompetitorRanking.summarize(results, ourDocument = "99.888.777/0001-66")

        assertEquals(4, summary.results)
        assertEquals(3, summary.contracts)
        assertEquals(listOf("Fibra Norte", "Rede Sul", "Conecta"), summary.ranking.map { it.name })
        val fibra = summary.ranking.first()
        assertEquals(2, fibra.wins)
        assertEquals(2, fibra.contracts)
        assertEquals(70_000.0, fibra.totalHomologated, 0.001)
        // (20% + 25%) / 2
        assertEquals(22.5, fibra.avgDiscountPct!!, 0.001)
        assertFalse(fibra.isUs)
        assertTrue(summary.ranking.last().isUs)
        assertNull(summary.ranking.last().avgDiscountPct)
        assertEquals((40_000.0 + 30_000.0 + 90_000.0 + 10_000.0) / 4, summary.avgHomologated, 0.001)
        // Média dos descontos calculáveis: 20, 25, 10.
        assertEquals(55.0 / 3, summary.avgDiscountPct!!, 0.001)
    }

    @Test
    fun `fornecedor sem documento agrupa pelo nome e valores invalidos sao ignorados`() {
        val results = listOf(
            award("A", 1, "Empresa X", "", 1_000.0, at = 5),
            award("B", 1, "empresa x ", "", 2_000.0, at = 9),
            award("C", 1, "Zero", "123", 0.0),
            award("D", 1, "  ", "456", 500.0),
        )
        val summary = CompetitorRanking.summarize(results, ourDocument = null)
        assertEquals(1, summary.ranking.size)
        assertEquals(2, summary.ranking.single().wins)
        assertEquals("empresa x", summary.ranking.single().name)
        assertEquals("", summary.ranking.single().document)
    }

    @Test
    fun `sem resultados devolve resumo vazio`() {
        assertEquals(MarketSummary.EMPTY, CompetitorRanking.summarize(emptyList(), "123"))
    }

    @Test
    fun `desconto usa o publicado quando nao ha estimado e formata CNPJ`() {
        assertEquals(7.5, award("A", 1, "X", "1", 100.0, discount = 7.5).discountPct!!, 0.0)
        assertEquals("12.345.678/0001-90", CompetitorRanking.formatDocument("12345678000190"))
        assertEquals("CPF ***.456.***-**", CompetitorRanking.formatDocument("12345678901"))
    }
}
