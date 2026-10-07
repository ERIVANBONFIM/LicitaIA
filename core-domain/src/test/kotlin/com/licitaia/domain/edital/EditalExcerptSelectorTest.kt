package com.licitaia.domain.edital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditalExcerptSelectorTest {

    private val header = "EDITAL DE PREGÃO ELETRÔNICO Nº 12/2026 — PREFEITURA DE EXEMPLO\n" +
        "OBJETO: contratação de link dedicado de internet para as escolas municipais.\n"

    /** Edital grande: cabeçalho + muito texto irrelevante + seções específicas espalhadas. */
    private fun bigEdital(): String = buildString {
        append(header)
        repeat(400) { append("Cláusula genérica número $it sobre disposições gerais do contrato administrativo.\n") }
        append("\n9. DO PAGAMENTO\n9.1 O pagamento será efetuado em até 30 dias após o atesto da nota fiscal.\n")
        repeat(400) { append("Texto de preenchimento $it sem relação com a pergunta feita pelo licitante.\n") }
        append("\n12. DAS SANÇÕES\n12.1 Multa de 10% sobre o valor do contrato em caso de inexecução.\n")
        repeat(400) { append("Mais conteúdo neutro $it para aumentar o tamanho do documento.\n") }
    }

    @Test fun `small edital goes complete`() {
        val text = header + "9.1 O pagamento será efetuado em 30 dias."
        val excerpt = EditalExcerptSelector.select(text, "Qual a forma de pagamento?", maxChars = 10_000)
        assertTrue(excerpt.complete)
        assertEquals(text, excerpt.text)
        assertEquals(0, excerpt.sections)
    }

    @Test fun `big edital keeps header and the relevant section within the limit`() {
        val text = bigEdital()
        val limit = 8_000
        assertTrue(text.length > limit * 3)
        val excerpt = EditalExcerptSelector.select(text, "Qual a forma de pagamento?", maxChars = limit)
        assertFalse(excerpt.complete)
        assertTrue("limite respeitado (${excerpt.text.length})", excerpt.text.length <= limit)
        assertTrue("cabeçalho/objeto sempre incluído", excerpt.text.startsWith("EDITAL DE PREGÃO ELETRÔNICO"))
        assertTrue(excerpt.text.contains("OBJETO: contratação de link dedicado"))
        assertTrue("trecho relevante incluído", excerpt.text.contains("9.1 O pagamento será efetuado em até 30 dias"))
        assertTrue(excerpt.text.contains(EditalExcerptSelector.GAP_MARKER.trim()))
        assertTrue(excerpt.sections >= 1)
        assertEquals(text.length, excerpt.originalChars)
    }

    @Test fun `relevance follows the question topic including synonyms`() {
        val text = bigEdital()
        // "penalidades" não aparece no texto: o sinônimo "multa"/"sanções" encontra a seção 12.
        val penalties = EditalExcerptSelector.select(text, "Quais as penalidades?", maxChars = 6_000)
        assertTrue(penalties.text.contains("12.1 Multa de 10%"))
        assertFalse("seção sem relação fica de fora", penalties.text.contains("9.1 O pagamento"))
        val payment = EditalExcerptSelector.select(text, "Qual a forma de pagamento?", maxChars = 6_000)
        assertFalse(payment.text.contains("12.1 Multa de 10%"))
    }

    @Test fun `accents and case are ignored`() {
        val text = bigEdital().replace("DAS SANÇÕES", "DAS SANCOES")
        val excerpt = EditalExcerptSelector.select(text, "SANÇÕES APLICÁVEIS", maxChars = 6_000)
        assertTrue(excerpt.text.contains("DAS SANCOES"))
    }

    @Test fun `tiny limit still respects size and keeps header start`() {
        val excerpt = EditalExcerptSelector.select(bigEdital(), "pagamento", maxChars = 2_000)
        assertTrue(excerpt.text.length <= 2_000)
        assertTrue(excerpt.text.startsWith("EDITAL DE PREGÃO"))
    }

    @Test fun `question without matches falls back to the beginning within the limit`() {
        val excerpt = EditalExcerptSelector.select(bigEdital(), "xyzw qwerty", maxChars = 5_000)
        assertFalse(excerpt.complete)
        assertTrue(excerpt.text.length <= 5_000)
        assertTrue(excerpt.text.startsWith("EDITAL DE PREGÃO"))
    }

    @Test fun `ocr page markers label the selected excerpts`() {
        val text = buildString {
            append(header)
            repeat(300) { append("Linha neutra $it de preenchimento do edital digitalizado.\n") }
            append("--- Página 7 ---\n")
            repeat(5) { append("Linha neutra da página sete.\n") }
            append("15. DA VISITA TÉCNICA\n15.1 A visita técnica é facultativa.\n")
            repeat(300) { append("Linha neutra final $it do edital digitalizado.\n") }
        }
        val excerpt = EditalExcerptSelector.select(text, "Há visita técnica obrigatória?", maxChars = 5_000)
        assertTrue(excerpt.text.contains("15.1 A visita técnica é facultativa."))
        assertTrue(excerpt.text.contains("[Página 7]") || excerpt.text.contains("--- Página 7 ---"))
    }

    @Test fun `terms drop stopwords and add topic synonyms`() {
        val terms = EditalExcerptSelector.terms("Quais são as penalidades do edital?")
        assertTrue(terms.containsKey("penali"))
        assertTrue(terms.containsKey("multa"))
        assertFalse(terms.containsKey("quais"))
        assertFalse(terms.containsKey("edital"))
        assertEquals(2.0, terms.getValue("penali"), 0.0)
        assertEquals(1.0, terms.getValue("multa"), 0.0)
    }

    @Test fun `fold keeps length so indices map to the original text`() {
        val original = "Habilitação Jurídica — CERTIDÃO"
        val folded = EditalExcerptSelector.fold(original)
        assertEquals(original.length, folded.length)
        assertEquals("habilitacao juridica — certidao", folded)
    }

    @Test fun `key sentences return the most relevant lines in document order`() {
        val text = "Introdução do edital sem nada.\n9.1 O pagamento será feito em 30 dias após a nota fiscal.\n" +
            "Outra linha sem relação nenhuma aqui.\n9.2 O pagamento ocorrerá por ordem bancária na conta da contratada."
        val sentences = EditalExcerptSelector.keySentences(text, "forma de pagamento", limit = 2)
        assertEquals(2, sentences.size)
        assertTrue(sentences[0].startsWith("9.1"))
        assertTrue(sentences[1].startsWith("9.2"))
        assertTrue(EditalExcerptSelector.keySentences(text, "visita técnica").isEmpty())
    }
}
