package com.licitaia.core.data.edital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditalTextPreparerTest {

    private fun filler(words: Int, seed: String = "lorem"): String =
        (1..words).joinToString(" ") { "$seed$it" } + "."

    @Test
    fun shortTextIsOnlyNormalized() {
        val raw = "EDITAL\r\n\r\n\r\n\r\nObjeto:   contratação  de   link.\t\n  Prazo: 30 dias."
        val out = EditalTextPreparer.prepare(raw, 10_000)
        assertEquals("EDITAL\n\nObjeto: contratação de link.\nPrazo: 30 dias.", out)
    }

    @Test
    fun longTextRespectsLimitAndKeepsBeginning() {
        val head = "PREGÃO ELETRÔNICO Nº 90012/2026 — Prefeitura de Uberaba. Objeto: link dedicado 1 Gbps."
        val body = (1..200).joinToString("\n\n") { filler(60, "palavra${it}_") }
        val out = EditalTextPreparer.prepare("$head\n\n$body", 20_000)
        assertTrue("limite estourado: ${out.length}", out.length <= 20_000)
        assertTrue(out.startsWith(head))
    }

    @Test
    fun prioritySectionsSurviveTruncationOverNoise() {
        val head = "EDITAL Nº 1/2026\n\nObjeto: contratação de serviços de TI."
        val noise = (1..150).joinToString("\n\n") { filler(80, "ruido${it}_") }
        val habilitacao = "9. DA HABILITAÇÃO\n\n9.1 Serão exigidos: Certidão Negativa de Débitos Trabalhistas (CNDT), Certificado de Regularidade do FGTS, balanço patrimonial e atestado de capacidade técnica."
        val penalidades = "15. DAS PENALIDADES\n\n15.1 Multa de 0,5% por dia de atraso, limitada a 10%."
        val garantia = "12. DA GARANTIA\n\n12.1 Garantia de execução de 5% do valor do contrato."
        val tail = (1..150).joinToString("\n\n") { filler(80, "cauda${it}_") }
        val text = listOf(head, noise, habilitacao, penalidades, tail, garantia).joinToString("\n\n")

        val out = EditalTextPreparer.prepare(text, 12_000)
        assertTrue(out.length <= 12_000)
        assertTrue("habilitação deveria sobreviver", out.contains("DA HABILITAÇÃO"))
        assertTrue("CNDT deveria sobreviver", out.contains("CNDT"))
        assertTrue("penalidades deveriam sobreviver", out.contains("DAS PENALIDADES"))
        assertTrue("garantia deveria sobreviver", out.contains("DA GARANTIA"))
        assertTrue("deve sinalizar cortes", out.contains(EditalTextPreparer.GAP_MARKER.trim()))
        // Nem todo o ruído cabe: a maior parte foi descartada.
        val noiseKept = (1..150).count { out.contains("ruido${it}_1 ") }
        assertTrue("ruído demais preservado: $noiseKept", noiseKept < 150)
    }

    @Test
    fun selectedBlocksKeepDocumentOrder() {
        val head = "EDITAL\n\nObjeto: X."
        val a = "3. DO PRAZO\n\n3.1 Prazo de entrega de 30 dias."
        val noise = (1..60).joinToString("\n\n") { filler(90, "n${it}_") }
        val b = "11. DAS PENALIDADES\n\n11.1 Multa de 1%."
        val out = EditalTextPreparer.prepare(listOf(head, a, noise, b).joinToString("\n\n"), 6_000)
        val ia = out.indexOf("DO PRAZO")
        val ib = out.indexOf("DAS PENALIDADES")
        assertTrue(ia >= 0 && ib >= 0)
        assertTrue("ordem do documento deve ser preservada", ia < ib)
    }

    @Test
    fun scoreIgnoresAccentsAndCase() {
        assertTrue(EditalTextPreparer.score("DA HABILITAÇÃO E DOS DOCUMENTOS") > 0)
        assertTrue(EditalTextPreparer.score("da habilitacao") > 0)
        assertEquals(0, EditalTextPreparer.score("texto qualquer sem termos relevantes"))
    }

    @Test
    fun minimumLimitIsEnforced() {
        val text = (1..100).joinToString("\n\n") { filler(50, "p${it}_") }
        val out = EditalTextPreparer.prepare(text, 10)
        assertTrue(out.length <= 2_000)
        assertFalse(out.isBlank())
    }
}
