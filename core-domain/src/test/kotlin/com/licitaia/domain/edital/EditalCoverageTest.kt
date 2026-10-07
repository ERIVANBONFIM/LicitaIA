package com.licitaia.domain.edital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Ler tudo": Edital e TR inteiros, anexos com teto, cobertura e base completa x recorte com seções inteiras. */
class EditalCoverageTest {

    private fun pages(n: Int, prefix: String) = (1..n).map { "$prefix página $it com texto suficiente para contar." }

    @Test
    fun `edital e termo de referencia entram inteiros, anexos respeitam o teto`() {
        val docs = listOf(
            EditalSourceDocument("edital.pdf", EditalDocKind.EDITAL, pages(400, "Edital")),
            EditalSourceDocument("tr.pdf", EditalDocKind.TERMO_REFERENCIA, pages(350, "TR")),
            EditalSourceDocument("anexo.pdf", EditalDocKind.ANEXO, pages(EditalDocumentBase.MAX_PAGES_PER_DOC + 20, "Anexo")),
        )
        val base = EditalDocumentBase.build(docs)
        val edital = base.documents.first { it.kind == EditalDocKind.EDITAL }
        val tr = base.documents.first { it.kind == EditalDocKind.TERMO_REFERENCIA }
        val anexo = base.documents.first { it.kind == EditalDocKind.ANEXO }
        assertEquals(400, edital.pagesIncluded)
        assertFalse(edital.truncated)
        assertEquals(350, tr.pagesIncluded)
        assertFalse(tr.truncated)
        assertEquals(EditalDocumentBase.MAX_PAGES_PER_DOC, anexo.pagesIncluded)
        assertTrue(anexo.truncated)
        assertEquals("Edital 400 pág ✓ · Termo de Referência 350 pág ✓ · 1 anexo", EditalDocumentBase.coverage(base.documents))
    }

    @Test
    fun `limites de paginas e OCR por tipo`() {
        assertEquals(Int.MAX_VALUE, EditalDocumentBase.maxPagesFor(EditalDocKind.EDITAL))
        assertEquals(Int.MAX_VALUE, EditalDocumentBase.maxPagesFor(EditalDocKind.TERMO_REFERENCIA))
        assertEquals(EditalDocumentBase.MAX_PAGES_PER_DOC, EditalDocumentBase.maxPagesFor(EditalDocKind.ANEXO))
        assertEquals(Int.MAX_VALUE, EditalDocumentBase.ocrAllowance(EditalDocKind.TERMO_REFERENCIA, annexOcrUsed = 10_000))
        assertEquals(EditalDocumentBase.OCR_PAGES_PER_ANNEX, EditalDocumentBase.ocrAllowance(EditalDocKind.ANEXO, 0))
        assertEquals(0, EditalDocumentBase.ocrAllowance(EditalDocKind.ANEXO, EditalDocumentBase.MAX_OCR_PAGES_ANNEXES))
        assertTrue(EditalDocumentBase.MAX_DOCUMENTS >= 20)
    }

    @Test
    fun `cobertura com anexos e parcial`() {
        val entries = listOf(
            EditalBaseEntry("", EditalDocKind.EDITAL, 56, 56),
            EditalBaseEntry("", EditalDocKind.TERMO_REFERENCIA, 23, 30, truncated = true),
            EditalBaseEntry("a", EditalDocKind.ANEXO, 3, 3),
            EditalBaseEntry("b", EditalDocKind.ETP, 3, 3),
            EditalBaseEntry("c", EditalDocKind.MINUTA, 3, 3),
        )
        assertEquals("Edital 56 pág ✓ · Termo de Referência 23 pág (parcial) · 3 anexos", EditalDocumentBase.coverage(entries))
        assertEquals("", EditalDocumentBase.coverage(emptyList()))
    }

    @Test
    fun `base que cabe vai inteira e e rotulada como base completa`() {
        val base = EditalDocumentBase.build(
            listOf(
                EditalSourceDocument("edital.pdf", EditalDocKind.EDITAL, pages(3, "Edital")),
                EditalSourceDocument("tr.pdf", EditalDocKind.TERMO_REFERENCIA, pages(3, "TR")),
            ),
        ).text
        val excerpt = EditalExcerptSelector.select(base, "Qual o prazo de entrega?", EditalExcerptSelector.FULL_BASE_MAX_CHARS)
        assertTrue(excerpt.complete)
        assertEquals(base, excerpt.text)
        assertEquals("lido: base completa", excerpt.coverageLabel)
        assertEquals(EditalExcerptSelector.FULL_BASE_MAX_CHARS, 200_000)
    }

    @Test
    fun `base grande inclui a secao inteira cujo titulo casa com a pergunta`() {
        val filler = (1..400).joinToString("\n") { "Linha de enchimento $it sobre assuntos gerais do certame e do objeto." }
        val habilitacao = "10. DA HABILITAÇÃO\n" + (1..40).joinToString("\n") { "10.$it Exigência número $it de documentos da empresa licitante." }
        val text = EditalDocumentBase.marker("Edital", 1) + "\n" + filler + "\n" + habilitacao + "\n11. DO PAGAMENTO\nO pagamento será em 30 dias.\n" + filler
        val excerpt = EditalExcerptSelector.select(text, "Quais os requisitos de habilitação?", maxChars = 12_000)
        assertFalse(excerpt.complete)
        assertTrue(excerpt.fullSections >= 1)
        assertTrue(excerpt.text.contains("10.40 Exigência número 40"))
        assertTrue(excerpt.coverageLabel.startsWith("trechos de "))
    }
}
