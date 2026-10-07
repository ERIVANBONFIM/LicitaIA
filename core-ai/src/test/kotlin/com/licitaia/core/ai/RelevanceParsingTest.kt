package com.licitaia.core.ai

import com.licitaia.ai.api.AiProviderException
import com.licitaia.ai.api.RelevanceItem
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelevanceParsingTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val items = listOf(
        RelevanceItem("PNCP:11111111000111-1-000398/2026", "Fornecimento de coffee break", "IFPR", "Dispensa Eletrônica"),
        RelevanceItem("COMPRAS_GOV:22222222000122-1-000010/2026", "Link dedicado de acesso à internet em fibra óptica", "UFBA", "Pregão Eletrônico"),
    )

    @Test
    fun leObjetoComNotasEMapeiaIdsCurtos() {
        val raw = """{"notas":[{"id":"1","score":3,"motivo":"Alimentação, não é telecom"},{"id":2,"score":"95","motivo":"Link dedicado"}]}"""
        val scores = RelevanceParsing.parse(raw, items, json)
        assertEquals(listOf(items[0].id, items[1].id), scores.map { it.id })
        assertEquals(listOf(3, 95), scores.map { it.score })
        assertEquals("Alimentação, não é telecom", scores[0].reason)
    }

    @Test
    fun toleraCercaMarkdownArrayNoTopoEChavesAlternativas() {
        val raw = "Segue:\n```json\n[{\"id\":\"2\",\"nota\":88.6,\"reason\":\"ok\"}]\n```"
        val scores = RelevanceParsing.parse(raw, items, json)
        assertEquals(1, scores.size)
        assertEquals(items[1].id, scores[0].id)
        assertEquals(89, scores[0].score)
    }

    @Test
    fun ignoraIdsDesconhecidosNotasForaDaFaixaEDuplicadas() {
        val raw = """{"itens":[{"id":"7","score":90},{"id":"1","score":140},{"id":"2","score":80},{"id":"2","score":10}]}"""
        val scores = RelevanceParsing.parse(raw, items, json)
        assertEquals(listOf(items[1].id to 80), scores.map { it.id to it.score })
    }

    @Test
    fun respostaInvalidaNaoGeraNota() {
        assertTrue(RelevanceParsing.parse("não sei avaliar", items, json).isEmpty())
        assertTrue(RelevanceParsing.parse("{\"erro\":true}", items, json).isEmpty())
    }

    @Test
    fun promptNaoLevaDadosDaEmpresaSoOContextoDoRadar() {
        val prompt = RelevanceParsing.prompt("Segmento de atuação: Telecom / ISP. Palavras do radar: internet, link", items)
        assertTrue(prompt.contains("1) [Dispensa Eletrônica] IFPR — Fornecimento de coffee break"))
        assertTrue(prompt.contains("2) [Pregão Eletrônico] UFBA"))
        assertFalse(prompt.contains("PNCP:"))
    }

    private class FakeLlm(private val answer: String) : LlmBackedProvider(Json { ignoreUnknownKeys = true }) {
        var calls = 0
        override val type = AiProviderType.OPENAI
        override val displayName = "Fake"
        override suspend fun complete(system: String, user: String, expectJson: Boolean): String {
            calls++
            return answer
        }
    }

    private val company = Company(1, "ME Telecom", "ME", "27147548000115", Segment.TELECOM_ISP, "BA", "Salvador")

    @Test
    fun provedorDevolveNotasEFalhaDeFormatoViraErroExplicito() = runBlocking {
        val ok = FakeLlm("""{"notas":[{"id":"1","score":5,"motivo":"coffee break"},{"id":"2","score":96,"motivo":"link"}]}""")
        assertEquals(listOf(5, 96), ok.rateRelevance(company, "Telecom", items).map { it.score })
        assertEquals(1, ok.calls)

        val bad = FakeLlm("desculpe")
        try {
            bad.rateRelevance(company, "Telecom", items)
            fail("esperava AiProviderException")
        } catch (_: AiProviderException) {
        }
    }
}
