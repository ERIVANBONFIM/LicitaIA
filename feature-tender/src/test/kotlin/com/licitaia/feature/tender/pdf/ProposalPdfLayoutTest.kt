package com.licitaia.feature.tender.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalPdfLayoutTest {

    /** Fonte "monoespaçada" de 5 pt por caractere. */
    private val measure: (String) -> Float = { it.length * 5f }

    @Test
    fun `quebra sem perder texto e respeita a largura`() {
        val text = "Fornecimento de switch gerenciável com 24 portas Gigabit Ethernet, 4 portas SFP+ e garantia de 36 meses on-site"
        val lines = ProposalPdfLayout.wrap(text, 100f, measure)
        assertTrue(lines.size > 1)
        lines.forEach { assertTrue("linha larga: $it", measure(it) <= 100f) }
        assertEquals(text.split(' ').filter { it.isNotEmpty() }, lines.flatMap { it.split(' ') })
    }

    @Test
    fun `palavra maior que a coluna e partida sem cortar`() {
        val word = "A".repeat(53)
        val lines = ProposalPdfLayout.wrap("x $word y", 50f, measure)
        lines.forEach { assertTrue(measure(it) <= 50f) }
        assertEquals("x$word" + "y", lines.joinToString("").replace(" ", ""))
    }

    @Test
    fun `respeita quebras de linha e texto vazio`() {
        assertEquals(listOf("um", "dois"), ProposalPdfLayout.wrap("um\ndois", 500f, measure))
        assertEquals(listOf(""), ProposalPdfLayout.wrap("", 500f, measure))
    }

    @Test
    fun `paginacao leva item inteiro para a proxima pagina e repete cabecalho`() {
        // página: topo 100, cabeçalho 20, fim 300 → 15 linhas por página nova (linha 10, padding 5 → (300-120-5)/10 = 17)
        val segments = ProposalPdfLayout.paginateTable(List(10) { 3 }, 10f, 5f, startY = 120f, pageTop = 100f, bottom = 300f, headerHeight = 20f)
        assertEquals(10, segments.size) // nenhum item partido
        assertTrue(segments.all { it.fromLine == 0 && it.toLine == 3 })
        assertTrue(ProposalPdfLayout.pagesUsed(segments) >= 2)
        // cada página comporta (180 / 35) = 5 itens de 3 linhas
        assertEquals(listOf(5), segments.mapIndexedNotNull { i, s -> if (s.newPageBefore) i else null }.take(1))
    }

    @Test
    fun `item maior que a pagina e partido sem perder linhas`() {
        val segments = ProposalPdfLayout.paginateTable(listOf(2, 60, 1), 10f, 5f, startY = 120f, pageTop = 100f, bottom = 300f, headerHeight = 20f)
        val big = segments.filter { it.item == 1 }
        assertTrue(big.size > 1)
        assertEquals(0, big.first().fromLine)
        assertEquals(60, big.last().toLine)
        big.zipWithNext().forEach { (a, b) -> assertEquals(a.toLine, b.fromLine) }
        // todos os itens aparecem, na ordem
        assertEquals(listOf(0, 1, 2), segments.map { it.item }.distinct())
    }

    @Test
    fun `muitos itens geram varias paginas`() {
        val segments = ProposalPdfLayout.paginateTable(List(300) { 2 }, 11f, 7f, startY = 400f, pageTop = 112f, bottom = 786f, headerHeight = 22f)
        assertEquals(300, segments.size)
        assertTrue(ProposalPdfLayout.pagesUsed(segments) > 10)
        assertFalse(segments.first().newPageBefore)
    }

    @Test
    fun `nome do arquivo sanitizado`() {
        assertEquals(
            "Proposta_90012-2026_Prefeitura-Municipal-de-Sao-Joao_v3.pdf",
            ProposalPdfLayout.fileName("90012/2026", "Prefeitura Municipal de São João", 3),
        )
        assertEquals("Proposta_sem-numero_orgao_v1.pdf", ProposalPdfLayout.fileName("  ", "///", 1))
        val long = ProposalPdfLayout.fileName("1".repeat(80), "Ç".repeat(80), 2)
        assertTrue(long.length < 90)
        assertTrue(long.matches(Regex("""[A-Za-z0-9_.-]+""")))
    }
}
