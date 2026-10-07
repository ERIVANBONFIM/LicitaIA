package com.licitaia.connector.pncp

import com.licitaia.domain.competition.MarketQuery
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Resultados públicos do PNCP (itens → resultados) contra MockWebServer com fixtures no formato da API. */
class PncpResultsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: PncpResultsSource
    private val requests = mutableListOf<RecordedRequest>()
    private val now = LocalDateTime.of(2026, 10, 7, 12, 0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/pncp/resultados/$name")) { "fixture $name ausente" }.bufferedReader().readText()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun start(dispatch: (RecordedRequest) -> MockResponse) {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return dispatch(request)
            }
        }
        server.start()
        source = PncpResultsSource(OkHttpClient(), PncpConnector.defaultJson(), server.url("/"), clock = { now }, spacingMs = 0, retryDelaysMs = listOf(0L))
    }

    @After
    fun tearDown() {
        if (this::server.isInitialized) server.shutdown()
    }

    @Test
    fun `parse resultados do item - fornecedor, CNPJ, valor homologado e desconto - ignorando cancelados e itens sem resultado`() = runBlocking {
        start { r ->
            val path = r.requestUrl!!.encodedPath
            when {
                path == "/api/pncp/v1/orgaos/20918579000183/compras/2025/16/itens" -> json(fixture("itens_com_resultado.json"))
                path == "/api/pncp/v1/orgaos/20918579000183/compras/2025/16/itens/1/resultados" -> json(fixture("resultados_item1.json"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val batch = source.awardResults("20918579000183-1-000016/2025")

        assertFalse(batch.partial)
        assertEquals(1, batch.results.size)
        val r = batch.results.single()
        assertEquals("20918579000183-1-000016/2025", r.controlNumber)
        assertEquals(1, r.itemNumber)
        assertEquals("FIBRA NORTE TELECOM LTDA", r.supplierName)
        assertEquals("12345678000190", r.supplierDocument)
        assertEquals("ME", r.supplierSize)
        assertEquals(54_000.0, r.estimatedValue, 0.001)
        assertEquals(46_800.0, r.homologatedValue, 0.001)
        assertEquals(13.33, r.discountPct!!, 0.01)
        assertTrue(r.ownTender)
        assertTrue(r.resultDate > 0)
        // Item 2 (temResultado=false) não gera chamada de resultados.
        assertTrue(requests.none { it.requestUrl!!.encodedPath.endsWith("/itens/2/resultados") })
    }

    @Test
    fun `contratacao sem resultado publicado devolve lista vazia`() = runBlocking {
        start { r ->
            if (r.requestUrl!!.encodedPath.endsWith("/itens")) json("""[{"numeroItem":1,"descricao":"x","temResultado":false}]""")
            else MockResponse().setResponseCode(404)
        }
        val batch = source.awardResults("20918579000183-1-000016/2025")
        assertTrue(batch.results.isEmpty())
        assertFalse(batch.partial)
        assertEquals(1, requests.size)
    }

    @Test
    fun `429 persistente vira lote parcial apos as retentativas, sem inventar dados`() = runBlocking {
        start { MockResponse().setResponseCode(429) }
        val batch = source.awardResults("20918579000183-1-000016/2025")
        assertTrue(batch.partial)
        assertTrue(batch.results.isEmpty())
        assertEquals(1 + PncpConnector.MAX_RATE_LIMIT_RETRIES, requests.size)
    }

    @Test
    fun `numero de controle invalido nao consulta o PNCP`() = runBlocking {
        start { MockResponse().setResponseCode(500) }
        assertTrue(source.awardResults("MANUAL:PE 10/2026").results.isEmpty())
        assertEquals(0, requests.size)
    }

    @Test
    fun `mercado - so contratacoes homologadas que casam com as palavras, sem revogadas`() = runBlocking {
        start { r ->
            val path = r.requestUrl!!.encodedPath
            when {
                path == "/api/consulta/v1/contratacoes/publicacao" -> json(fixture("publicacao_homologadas.json"))
                path == "/api/pncp/v1/orgaos/11111111000111/compras/2026/10/itens" -> json(fixture("itens_com_resultado.json"))
                path == "/api/pncp/v1/orgaos/11111111000111/compras/2026/10/itens/1/resultados" -> json(fixture("resultados_item1.json"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val batch = source.recentAwardsLike(MarketQuery(keywords = listOf("link dedicado", "fibra"), ufs = listOf("MG")))

        assertEquals(1, batch.checkedContracts)
        assertEquals(1, batch.results.size)
        val r = batch.results.single()
        assertEquals("MUNICIPIO DE EXEMPLO", r.agency)
        assertEquals("MG", r.uf)
        assertFalse(r.ownTender)
        val publicacao = requests.first { it.requestUrl!!.encodedPath.endsWith("/contratacoes/publicacao") }.requestUrl!!
        assertEquals("6", publicacao.queryParameter("codigoModalidadeContratacao"))
        assertEquals("MG", publicacao.queryParameter("uf"))
        // Sem homologado, sem palavra ou revogada: não detalha.
        listOf("22222222000122", "33333333000133", "44444444000144").forEach { cnpj ->
            assertTrue(requests.none { it.requestUrl!!.encodedPath.contains(cnpj) })
        }
    }
}
