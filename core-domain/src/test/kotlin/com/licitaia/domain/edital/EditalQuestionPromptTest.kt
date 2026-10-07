package com.licitaia.domain.edital

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import com.licitaia.domain.proposal.OfficialTenderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditalQuestionPromptTest {
    private val tender = Tender(
        id = 7, companyId = 1, opportunityId = "PNCP:1", portal = Portal.PNCP, number = "90012/2026", agency = "Prefeitura de Exemplo",
        objectDescription = "Link dedicado de internet", modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP,
        uf = "BA", city = "Salvador", estimatedValue = 120_000.0, proposalDeadline = 0, sessionAt = 0,
    )

    @Test fun `system prompt demands edital-only answers, citations and not-found wording`() {
        val system = EditalQuestionPrompt.SYSTEM
        assertTrue(system.contains("SOMENTE com informação escrita literalmente"))
        assertTrue(system.contains("NUNCA infira"))
        assertTrue(system.contains("Sugestão: procure em"))
        assertTrue(system.contains("Fonte:"))
        assertTrue(system.contains(EditalQuestionPrompt.NOT_FOUND))
        assertTrue(system.contains("português do Brasil"))
        assertTrue(system.contains("concisa"))
    }

    @Test fun `prompt carries question, tender metadata and complete edital`() {
        val excerpt = EditalExcerpt("8.2 Documentos de habilitação: certidões.", complete = true, terms = listOf("docume"), sections = 0, originalChars = 40)
        val prompt = EditalQuestionPrompt.build(tender, "  Quais documentos?  ", excerpt)
        assertTrue(prompt.startsWith("PERGUNTA: Quais documentos?"))
        assertTrue(prompt.contains("Pregão Eletrônico nº 90012/2026") || prompt.contains("nº 90012/2026"))
        assertTrue(prompt.contains("Objeto: Link dedicado de internet"))
        assertTrue(prompt.contains("TEXTO DOS DOCUMENTOS (completo):"))
        assertTrue(prompt.endsWith("8.2 Documentos de habilitação: certidões."))
        assertFalse(prompt.contains("ITENS OFICIAIS"))
    }

    @Test fun `prompt explains a partial excerpt and lists official items`() {
        val excerpt = EditalExcerpt("cabeçalho\n[...trecho omitido...]\ntrecho", complete = false, terms = emptyList(), sections = 3, originalChars = 500_000)
        val items = listOf(
            OfficialTenderItem(2, "Roteador", 5.0, "un", 800.0, benefit = "Participação exclusiva para ME/EPP"),
            OfficialTenderItem(1, "Link dedicado 200 Mbps", 12.0, "mês", null, confidentialBudget = true),
        )
        val prompt = EditalQuestionPrompt.build(tender, "Qual o valor do item 2?", excerpt, items)
        assertTrue(prompt.contains("recorte: cabeçalho + 3 trecho(s)"))
        assertTrue(prompt.contains("500000 caracteres"))
        assertTrue(prompt.contains("ITENS OFICIAIS PUBLICADOS"))
        // Ordenados pelo número do item; sigiloso marcado; benefício ME/EPP incluído.
        assertTrue(prompt.indexOf("Item 1:") < prompt.indexOf("Item 2:"))
        assertTrue(prompt.contains("Item 1: Link dedicado 200 Mbps | 12 mês | valor sigiloso"))
        assertTrue(prompt.contains("Participação exclusiva para ME/EPP"))
    }

    @Test fun `items are wanted only for item related questions`() {
        assertTrue(EditalQuestionPrompt.wantsItems("Qual o valor estimado do item 3?"))
        assertTrue(EditalQuestionPrompt.wantsItems("Quantidade de unidades do lote 1"))
        assertFalse(EditalQuestionPrompt.wantsItems("Há visita técnica obrigatória?"))
        assertFalse(EditalQuestionPrompt.wantsItems("Data e hora da sessão?"))
    }

    @Test fun `sources are split from the answer body`() {
        val (body, sources) = EditalQuestionPrompt.splitSources(
            "O pagamento ocorre em até 30 dias.\nAtesto pelo fiscal.\n\nFonte: item 9.1 — Do pagamento\n- **Fonte:** Anexo I, página 12\nfontes: item 9.1 — Do pagamento",
        )
        assertEquals("O pagamento ocorre em até 30 dias.\nAtesto pelo fiscal.", body)
        assertEquals(listOf("item 9.1 — Do pagamento", "Anexo I, página 12"), sources)
    }

    @Test fun `answer without sources keeps everything in the body`() {
        val (body, sources) = EditalQuestionPrompt.splitSources("Não encontrei essa informação no edital.")
        assertEquals("Não encontrei essa informação no edital.", body)
        assertTrue(sources.isEmpty())
    }

    @Test fun `share text joins answer and sources`() {
        val question = EditalQuestion(companyId = 1, tenderId = 7, question = "Prazo?", answer = "30 dias.", sources = listOf("item 5.1"), createdAt = 0)
        assertEquals("30 dias.\nFonte: item 5.1", question.shareText)
    }

    @Test fun `prompt lists the documents in the base`() {
        val excerpt = EditalExcerpt("=== DOCUMENTO: Edital (página 1) ===\nObjeto", complete = true, terms = emptyList(), sections = 0, originalChars = 40)
        val prompt = EditalQuestionPrompt.build(tender, "Local de entrega?", excerpt, documents = listOf("Edital", "Termo de Referência — tr.pdf"))
        assertTrue(prompt.contains("DOCUMENTOS NA BASE: Edital; Termo de Referência — tr.pdf"))
    }

    @Test fun `answers without source are flagged, not-found answers are not`() {
        val base = EditalQuestion(companyId = 1, tenderId = 7, question = "Prazo?", createdAt = 0, status = EditalQuestionStatus.OK)
        assertTrue(base.copy(answer = "30 dias.").unsourced)
        assertFalse(base.copy(answer = "30 dias.", sources = listOf("Edital — página 3")).unsourced)
        assertFalse(base.copy(answer = EditalQuestionPrompt.NOT_FOUND + "\nSugestão: procure em Termo de Referência").unsourced)
        assertFalse(base.copy(answer = "Falhou", status = EditalQuestionStatus.ERRO).unsourced)
        assertTrue(EditalQuestionPrompt.isNotFound("Não encontrei essa informação nos documentos da licitação."))
        assertFalse(EditalQuestionPrompt.isNotFound("O prazo é de 30 dias."))
    }
}