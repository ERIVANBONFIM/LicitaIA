package com.licitaia.domain.edital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditalDocumentBaseTest {

    private fun doc(title: String, kind: EditalDocKind, vararg pages: String, total: Int = pages.size) =
        EditalSourceDocument(title, kind, pages.toList(), total)

    @Test
    fun `classifica pelo tipo do PNCP e, se generico, pelo titulo - ETP nunca vira edital`() {
        assertEquals(EditalDocKind.EDITAL, EditalDocumentBase.classify("x.pdf", "Edital", 2))
        assertEquals(EditalDocKind.TERMO_REFERENCIA, EditalDocumentBase.classify("x.pdf", "Termo de Referência", 4))
        assertEquals(EditalDocKind.ETP, EditalDocumentBase.classify("x.pdf", "Estudo Técnico Preliminar", 7))
        assertEquals(EditalDocKind.ETP, EditalDocumentBase.classify("ETP - anexo do edital.pdf", "Outros Documentos", 16))
        assertEquals(EditalDocKind.TERMO_REFERENCIA, EditalDocumentBase.classify("Anexo I - Termo de Referência.pdf", "Outros Documentos", 16))
        assertEquals(EditalDocKind.TERMO_REFERENCIA, EditalDocumentBase.classify("TR.pdf", null, null))
        assertEquals(EditalDocKind.ANEXO, EditalDocumentBase.classify("Anexo II - Planilha.pdf", "Outros Documentos", 16))
        assertEquals(EditalDocKind.MINUTA, EditalDocumentBase.classify("minuta_contrato.pdf", null, null))
        assertEquals(EditalDocKind.EDITAL, EditalDocumentBase.classify("Edital PE 12-2026 com anexos.pdf", null, null))
        assertEquals(EditalDocKind.OUTRO, EditalDocumentBase.classify("orcamento.pdf", "Outros Documentos", 16))
    }

    @Test
    fun `monta texto unico com marcadores por pagina na ordem de prioridade`() {
        val base = EditalDocumentBase.build(
            listOf(
                doc("etp.pdf", EditalDocKind.ETP, "Estudo: necessidade de 10 notebooks."),
                doc("anexo.pdf", EditalDocKind.ANEXO, "Planilha de custos."),
                doc("tr.pdf", EditalDocKind.TERMO_REFERENCIA, "5.1 Local de entrega: Rua A, 100.", "6.1 Prazo: 30 dias."),
                doc("edital.pdf", EditalDocKind.EDITAL, "1. Objeto: notebooks.", "", "8. Habilitação."),
            ),
        )
        val text = base.text
        val edital = text.indexOf("=== DOCUMENTO: Edital — edital.pdf (página 1) ===")
        val tr = text.indexOf("=== DOCUMENTO: Termo de Referência — tr.pdf (página 1) ===")
        val anexo = text.indexOf("=== DOCUMENTO: Anexo — anexo.pdf (página 1) ===")
        val etp = text.indexOf("=== DOCUMENTO: Estudo Técnico Preliminar — etp.pdf (página 1) ===")
        assertTrue(edital == 0)
        assertTrue(edital < tr && tr < anexo && anexo < etp)
        // Página vazia não gera marcador; a numeração das demais é preservada.
        assertFalse(text.contains("edital.pdf (página 2)"))
        assertTrue(text.contains("=== DOCUMENTO: Edital — edital.pdf (página 3) ===\n8. Habilitação."))
        assertTrue(text.contains("=== DOCUMENTO: Termo de Referência — tr.pdf (página 2) ===\n6.1 Prazo: 30 dias."))
        assertEquals(listOf(EditalDocKind.EDITAL, EditalDocKind.TERMO_REFERENCIA, EditalDocKind.ANEXO, EditalDocKind.ETP), base.documents.map { it.kind })
        assertEquals(2, base.documents.first().pagesIncluded)
        assertTrue(base.skipped.isEmpty())

        // A lista de documentos é reconstruída a partir do texto salvo.
        val listed = EditalDocumentBase.documentsIn(text)
        assertEquals(listOf("Edital — edital.pdf", "Termo de Referência — tr.pdf", "Anexo — anexo.pdf", "Estudo Técnico Preliminar — etp.pdf"), listed.map { it.displayName })
        assertEquals(2, listed[1].pagesIncluded)
        assertTrue(EditalDocumentBase.documentsIn("texto antigo sem marcadores").isEmpty())
    }

    @Test
    fun `respeita limite total e de paginas por documento - edital primeiro, o resto fica de fora com motivo`() {
        val big = "x".repeat(3_500)
        val base = EditalDocumentBase.build(
            listOf(
                doc("etp.pdf", EditalDocKind.ETP, big),
                doc("edital.pdf", EditalDocKind.EDITAL, big, big, big, big),
                doc("vazio.pdf", EditalDocKind.ANEXO, "   "),
            ),
            maxChars = 8_000,
            maxPagesPerDoc = 2,
        )
        assertTrue(base.text.length <= 8_000)
        val edital = base.documents.single()
        assertEquals(EditalDocKind.EDITAL, edital.kind)
        assertEquals(2, edital.pagesIncluded)
        assertTrue(edital.truncated) // 4 páginas, limite de 2 por documento
        assertTrue(base.skipped.any { it.startsWith("Anexo — vazio.pdf") && it.contains("sem texto") })
        assertTrue(base.skipped.any { it.startsWith("Estudo Técnico Preliminar — etp.pdf") && it.contains("limite") })
    }

    @Test
    fun `nomes repetidos ganham sufixo para a citacao apontar o documento certo`() {
        val base = EditalDocumentBase.build(
            listOf(doc("anexo.pdf", EditalDocKind.ANEXO, "A"), doc("anexo.pdf", EditalDocKind.ANEXO, "B")),
        )
        assertTrue(base.text.contains("=== DOCUMENTO: Anexo — anexo.pdf (página 1) ===\nA"))
        assertTrue(base.text.contains("=== DOCUMENTO: Anexo — anexo.pdf (2) (página 1) ===\nB"))
    }

    @Test
    fun `seletor cita documento e pagina dos trechos em base com varios documentos`() {
        val filler = "Cláusula genérica sem relação com o assunto. ".repeat(60)
        val docs = listOf(
            doc("edital.pdf", EditalDocKind.EDITAL, "Pregão eletrônico 12/2026. Objeto: notebooks.", filler, filler),
            doc("tr.pdf", EditalDocKind.TERMO_REFERENCIA, filler, filler, "5.1 O local de entrega dos bens é o Almoxarifado Central, Rua das Flores, 100, Centro.", filler),
            doc("etp.pdf", EditalDocKind.ETP, filler, filler),
        )
        val text = EditalDocumentBase.build(docs).text
        val excerpt = EditalExcerptSelector.select(text, "Qual o local de entrega dos notebooks?", maxChars = 4_000)
        assertFalse(excerpt.complete)
        assertTrue(excerpt.text, excerpt.text.contains("Almoxarifado Central, Rua das Flores, 100"))
        assertTrue(excerpt.text, excerpt.text.contains("[Documento: Termo de Referência — tr.pdf · página 3]"))
        // O marcador de documento não conta como ocorrência dos termos da pergunta.
        val markerOnly = EditalExcerptSelector.select(text, "termo de referência", maxChars = 4_000)
        assertTrue(markerOnly.sections == 0)
    }
}
