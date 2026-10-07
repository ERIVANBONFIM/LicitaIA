package com.licitaia.domain.proposal

import com.licitaia.domain.model.PriceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialProposalBuilderTest {

    private fun item(n: Int, qty: Double, unit: Double?, sigiloso: Boolean = false, desc: String = "Item oficial $n") =
        OfficialTenderItem(number = n, description = desc, quantity = qty, unit = "UN", estimatedUnitPrice = unit, confidentialBudget = sigiloso)

    @Test
    fun `sem analise aplica desconto padrao e arredonda a centavos`() {
        val draft = OfficialProposalBuilder.build(listOf(item(1, 10.0, 100.0), item(2, 3.0, 33.33)))
        assertFalse(draft.fromAnalysis)
        assertEquals(99.50, draft.items[0].unitPrice, 0.0)
        // 33,33 × 0,995 = 33,16335 → 33,16
        assertEquals(33.16, draft.items[1].unitPrice, 0.0)
        assertEquals(1, draft.items[0].itemNumber)
        assertEquals("UN", draft.items[0].unit)
        assertEquals(10.0, draft.items[0].quantity, 0.0)
        assertEquals(100.0, draft.items[0].estimatedUnitPrice!!, 0.0)
    }

    @Test
    fun `faixa da analise vira fator proporcional ao estimado`() {
        val official = listOf(item(1, 2.0, 500.0), item(2, 1.0, 1000.0)) // estimado total 2.000
        val draft = OfficialProposalBuilder.build(official, PriceRange(min = 1500.0, suggested = 1800.0, max = 2000.0))
        assertTrue(draft.fromAnalysis)
        assertEquals(0.9, draft.priceFactor, 1e-9)
        assertEquals(450.0, draft.items[0].unitPrice, 0.0)
        assertEquals(900.0, draft.items[1].unitPrice, 0.0)
    }

    @Test
    fun `preco nunca fica acima do estimado`() {
        // sugerido acima do estimado: ignora a faixa e usa o desconto padrão
        val draft = OfficialProposalBuilder.build(listOf(item(1, 1.0, 10.0)), PriceRange(0.0, 50.0, 60.0))
        assertFalse(draft.fromAnalysis)
        assertTrue(draft.items[0].unitPrice <= 10.0)
        // estimado com 4 casas: o teto é arredondado para baixo
        assertEquals(0.12, OfficialProposalBuilder.unitPriceFor(0.1234, 1.0), 0.0)
        assertEquals(0.12, OfficialProposalBuilder.unitPriceFor(0.1299, 0.999), 0.0)
        listOf(0.01, 0.07, 1.005, 3.3333, 151000.0, 9_999_999.99).forEach { est ->
            listOf(0.3, 0.9, 0.995, 1.0, 1.5).forEach { f ->
                val p = OfficialProposalBuilder.unitPriceFor(est, f)
                assertTrue("est=$est f=$f p=$p", p <= est + 1e-9)
                assertEquals("centavos est=$est f=$f", Math.round(p * 100) / 100.0, p, 0.0)
            }
        }
    }

    @Test
    fun `orcamento sigiloso fica a definir`() {
        val draft = OfficialProposalBuilder.build(listOf(item(1, 5.0, 20.0), item(2, 1.0, null, sigiloso = true), item(3, 1.0, null)))
        assertEquals(listOf(2, 3), draft.confidentialItems)
        assertTrue(draft.hasPendingPrices)
        assertEquals(0.0, draft.items[1].unitPrice, 0.0)
        assertTrue(draft.items[1].confidentialBudget)
        assertEquals(19.90, draft.items[0].unitPrice, 0.0)
    }

    @Test
    fun `itens ordenados pelo numero do edital e descricao enorme resumida`() {
        val long = "Notebook com processador de 8 núcleos, 16 GB de memória. " + "Especificação detalhada ".repeat(60)
        val draft = OfficialProposalBuilder.build(listOf(item(3, 1.0, 10.0), item(1, 1.0, 10.0, desc = long)))
        assertEquals(listOf(1, 3), draft.items.map { it.itemNumber })
        val desc = draft.items[0].description
        assertTrue(desc.length <= OfficialProposalBuilder.MAX_DESCRIPTION_CHARS + 4)
        assertTrue(desc.startsWith("Notebook com processador"))
        assertTrue(desc.endsWith("…") || desc.endsWith("(…)"))
    }

    @Test
    fun `descricao curta fica intacta`() {
        assertEquals("Cabo de rede CAT6", OfficialProposalBuilder.summarize("  Cabo de rede   CAT6 "))
    }

    @Test
    fun `total da proposta soma itens arredondados`() {
        val draft = OfficialProposalBuilder.build(listOf(item(1, 3.0, 0.11), item(2, 7.0, 1.37)))
        val proposal = com.licitaia.domain.model.Proposal(tenderId = 1, companyId = 1, items = draft.items, deliveryDays = 30, createdBy = "x")
        val expected = draft.items.sumOf { Math.round(it.quantity * it.unitPrice * 100) / 100.0 }
        assertEquals(expected, proposal.totalValue, 1e-9)
    }
}
