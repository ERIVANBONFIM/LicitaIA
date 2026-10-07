package com.licitaia.connector.pncp

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.SubmissionResult
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Conector PNCP contra um MockWebServer que devolve respostas REAIS capturadas da API (fixtures). */
class PncpConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var connector: PncpConnector
    private val requests = mutableListOf<RecordedRequest>()

    /** Relógio fixo: 06/10/2026 12:00 em Brasília → dataFinal=20261205 (hoje + 60 dias). */
    private val now = LocalDateTime.of(2026, 10, 6, 12, 0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/pncp/$name")) { "fixture $name ausente" }.bufferedReader().readText()

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun noContent() = MockResponse().setResponseCode(204)

    private fun start(dispatch: (RecordedRequest) -> MockResponse) {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return dispatch(request)
            }
        }
        server.start()
        connector = PncpConnector(
            OkHttpClient(), PncpConnector.defaultJson(), server.url("/"), clock = { now }, pageDelayMs = 0, retryDelaysMs = listOf(0L),
        )
    }

    @Before
    fun setUp() {
        requests.clear()
    }

    @After
    fun tearDown() {
        if (this::server.isInitialized) server.shutdown()
    }

    private fun RecordedRequest.query(name: String): String? = requestUrl?.queryParameter(name)

    // ------------------------------------------------------------ listOpportunities

    @Test
    fun `busca com modalidade e UF consulta propostas abertas e pagina ate acabar`() = runBlocking {
        val page1 = fixture("contratacoes_proposta_mg_p1.json") // paginasRestantes = 37
        start { req ->
            when (req.query("pagina")) {
                "1" -> jsonResponse(page1)
                else -> noContent() // página 2 vazia (204, conforme OpenAPI) encerra a paginação
            }
        }

        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, ufs = setOf("mg")))

        assertEquals(2, requests.size)
        requests.forEach { r ->
            assertEquals("/api/consulta/v1/contratacoes/proposta", r.requestUrl?.encodedPath)
            // Horizonte de 60 dias: com "hoje" a API só devolve o que encerra hoje.
            assertEquals("20261205", r.query("dataFinal"))
            assertEquals("12", r.query("codigoModalidadeContratacao"))
            assertEquals("MG", r.query("uf"))
            assertEquals("50", r.query("tamanhoPagina"))
            assertNull("não há parâmetro de texto na API", r.query("q"))
        }
        assertEquals(listOf("1", "2"), requests.map { it.query("pagina") })

        assertEquals(10, result.size)
        assertTrue(result.all { it.id.startsWith("PNCP:") && it.uf == "MG" })
        assertEquals(setOf(Portal.PNCP, Portal.PORTAL_COMPRAS_PUBLICAS), result.map { it.portal }.toSet())
        assertEquals(result.sortedBy { it.proposalDeadline }, result)
    }

    @Test
    fun `paginacao para quando nao restam paginas`() = runBlocking {
        val lastPage = fixture("contratacoes_proposta_mg_p1.json").replace("\"paginasRestantes\":37", "\"paginasRestantes\":0")
        check(lastPage != fixture("contratacoes_proposta_mg_p1.json")) { "fixture mudou: ajustar substituição" }
        start { jsonResponse(lastPage) }

        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, ufs = setOf("MG")))

        assertEquals(1, requests.size)
        assertEquals(10, result.size)
    }

    @Test
    fun `sem modalidade consulta somente os codigos representados pelo app`() = runBlocking {
        start { noContent() }

        val result = connector.listOpportunities(OpportunityFilter())

        assertTrue(result.isEmpty())
        assertEquals(
            listOf("6", "8", "4", "12"),
            requests.map { it.query("codigoModalidadeContratacao") },
        )
        assertTrue(requests.all { it.query("uf") == null })
    }

    @Test
    fun `filtros sem suporte na API (texto e valor) sao aplicados localmente`() = runBlocking {
        start { req -> if (req.query("pagina") == "1") jsonResponse(fixture("contratacoes_proposta_mg_p1.json")) else noContent() }

        val byText = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, query = "laboratório análises"))
        assertEquals(
            setOf("PNCP:20918579000183-1-000016/2025", "PNCP:18134056000102-1-000076/2025"),
            byText.map { it.id }.toSet(),
        )

        requests.clear()
        val byValue = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, minValue = 1_000_000.0))
        assertEquals(2, byValue.size)
        assertTrue(byValue.all { it.estimatedValue >= 1_000_000.0 })
    }

    @Test
    fun `filtro por portal de plataforma busca no PNCP e devolve so a plataforma classificada`() = runBlocking {
        val page = fixture("contratacoes_proposta_mg_p1.json")
            .replace("\"usuarioNome\":\"IPM Sistemas\"", "\"usuarioNome\":\"BLL Compras\"")
        check(page != fixture("contratacoes_proposta_mg_p1.json")) { "fixture mudou: ajustar substituição" }
        start { req -> if (req.query("pagina") == "1") jsonResponse(page) else noContent() }

        val bll = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, portals = setOf(Portal.BLL)))
        assertEquals(1, bll.size)
        assertEquals(Portal.BLL, bll.single().portal)
        assertTrue("id continua com prefixo PNCP", bll.single().id.startsWith("PNCP:"))
        assertEquals("BLL Compras", bll.single().platformName)
        assertTrue(requests.isNotEmpty())

        requests.clear()
        val pncp = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, portals = setOf(Portal.PNCP)))
        assertEquals(7, pncp.size)
        assertTrue(pncp.all { it.portal == Portal.PNCP })

        requests.clear()
        val pcp = connector.listOpportunities(
            OpportunityFilter(modality = Modality.CREDENCIAMENTO, portals = setOf(Portal.PORTAL_COMPRAS_PUBLICAS)),
        )
        assertEquals(2, pcp.size)
        assertTrue(pcp.all { it.platformName == "Portal de Compras Públicas" })
    }

    @Test
    fun `filtro so por plataforma le mais paginas por modalidade que a busca geral`() = runBlocking {
        val page1 = fixture("contratacoes_proposta_mg_p1.json") // paginasRestantes = 37 (sempre há mais)
        start { jsonResponse(page1) }

        connector.listOpportunities(OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV)))
        val focused = requests.groupBy { it.query("codigoModalidadeContratacao") }.mapValues { it.value.size }
        // 400 itens / (50 × 4 modalidades) = 2 páginas por modalidade (a fixture repete ids, então o teto não corta antes).
        assertEquals(mapOf("6" to 2, "8" to 2, "4" to 2, "12" to 2), focused)

        requests.clear()
        connector.listOpportunities(OpportunityFilter())
        assertEquals("busca geral: 1 página por modalidade", 4, requests.size)
    }

    @Test
    fun `429 depois de ja ter resultados devolve o parcial em vez de falhar`() = runBlocking {
        val page1 = fixture("contratacoes_proposta_mg_p1.json")
        start { req -> if (req.query("pagina") == "1") jsonResponse(page1) else MockResponse().setResponseCode(429) }

        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, ufs = setOf("MG")))

        assertEquals(10, result.size)
        // Página 2: requisição original + 3 retentativas, depois devolve o parcial.
        assertEquals(listOf("1", "2", "2", "2", "2"), requests.map { it.query("pagina") })
    }

    @Test
    fun `429 transitorio e retentado e a busca continua`() = runBlocking {
        val page1 = fixture("contratacoes_proposta_mg_p1.json")
        var hits = 0
        start { req ->
            when (req.query("pagina")) {
                "1" -> if (++hits <= 2) MockResponse().setResponseCode(429).setHeader("Retry-After", "0") else jsonResponse(page1)
                else -> noContent()
            }
        }

        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO, ufs = setOf("MG")))

        assertEquals(10, result.size)
        assertEquals(listOf("1", "1", "1", "2"), requests.map { it.query("pagina") })
    }

    @Test
    fun `espera do 429 usa Retry-After limitado ou 2 a 5 s`() {
        assertEquals(3_000L, PncpConnector.rateLimitWaitMs(0, 3_000L))
        assertEquals(PncpConnector.MAX_RETRY_AFTER_MS, PncpConnector.rateLimitWaitMs(0, 120_000L))
        assertEquals(2_000L, PncpConnector.rateLimitWaitMs(0, null))
        assertEquals(3_500L, PncpConnector.rateLimitWaitMs(1, null))
        assertEquals(5_000L, PncpConnector.rateLimitWaitMs(2, null))
        assertEquals(5_000L, PncpConnector.rateLimitWaitMs(7, null))
    }

    @Test
    fun `erro HTTP vira PncpException com mensagem em portugues`() = runBlocking {
        start { MockResponse().setResponseCode(503).setBody("indisponível") }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO))
            fail("deveria falhar")
        } catch (e: PncpException) {
            assertEquals(PncpException.Kind.HTTP, e.kind)
            assertEquals(503, e.httpStatus)
            assertTrue(e.message!!, e.message!!.contains("indisponível"))
        }
    }

    @Test
    fun `limite de consultas (429) e corpo invalido tem mensagens proprias`() = runBlocking {
        start { req -> if (req.query("pagina") == "1") MockResponse().setResponseCode(429) else noContent() }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.DISPENSA_ELETRONICA))
            fail("deveria falhar")
        } catch (e: PncpException) {
            assertEquals(429, e.httpStatus)
            assertTrue(e.message!!.contains("Aguarde"))
        }
        server.shutdown()

        start { jsonResponse("<html>não é json</html>") }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.DISPENSA_ELETRONICA))
            fail("deveria falhar")
        } catch (e: PncpException) {
            assertEquals(PncpException.Kind.INVALID_RESPONSE, e.kind)
        }
    }

    @Test
    fun `servidor inacessivel vira PncpException de conexao`() = runBlocking {
        start { noContent() }
        val url = server.url("/")
        server.shutdown()
        val offline = PncpConnector(OkHttpClient(), PncpConnector.defaultJson(), url, clock = { now })
        try {
            offline.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO))
            fail("deveria falhar")
        } catch (e: PncpException) {
            assertTrue(e.kind == PncpException.Kind.OFFLINE || e.kind == PncpException.Kind.TIMEOUT)
            assertTrue(e.message!!.contains("PNCP"))
        }
    }

    // ------------------------------------------------------------ getTenderDetails

    @Test
    fun `detalhe combina contratacao, documentos (edital) e itens`() = runBlocking {
        start { req ->
            when (req.requestUrl?.encodedPath) {
                "/api/consulta/v1/orgaos/20918579000183/compras/2025/16" -> jsonResponse(fixture("contratacao_20918579000183_2025_16.json"))
                "/api/pncp/v1/orgaos/20918579000183/compras/2025/16/arquivos" -> jsonResponse(fixture("arquivos_20918579000183_2025_16.json"))
                "/api/pncp/v1/orgaos/20918579000183/compras/2025/16/itens" -> jsonResponse(fixture("itens_20918579000183_2025_16.json"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val details = connector.getTenderDetails("PNCP:20918579000183-1-000016/2025")

        assertNotNull(details)
        assertEquals(3, requests.size)
        assertEquals("PNCP:20918579000183-1-000016/2025", details!!.opportunity.id)
        assertNull("o PDF não é lido pelo conector", details.editalText)
        assertEquals("https://pncp.gov.br/pncp-api/v1/orgaos/20918579000183/compras/2025/16/arquivos/1", details.opportunity.editalUrl)
        assertTrue(details.opportunity.keywords.contains("documento: EDITAL.pdf"))
        assertTrue(details.items.isNotEmpty())
        assertTrue(details.items.first().startsWith("Item 1 — ANTI TRANSGLUTAMINASE"))
    }

    @Test
    fun `detalhe sem documentos mantem a pagina publica como editalUrl`() = runBlocking {
        start { req ->
            when {
                req.requestUrl?.encodedPath?.startsWith("/api/consulta/") == true -> jsonResponse(fixture("contratacao_20918579000183_2025_16.json"))
                else -> noContent()
            }
        }
        val details = connector.getTenderDetails("PNCP:20918579000183-1-000016/2025")
        assertEquals("https://pncp.gov.br/app/editais/20918579000183/2025/16", details!!.opportunity.editalUrl)
        assertTrue(details.items.isEmpty())
    }

    @Test
    fun `detalhe de id invalido ou inexistente devolve null`() = runBlocking {
        start { noContent() }
        assertNull(connector.getTenderDetails("COMPRAS_GOV:90045/2026"))
        assertTrue(requests.isEmpty())
        assertNull(connector.getTenderDetails("PNCP:20918579000183-1-000999/2025"))
        assertEquals(1, requests.size)
    }

    // ------------------------------------------------------------ capacidades / não suportado

    @Test
    fun `capacidades declaram consulta publica real e metodos autenticados falham explicitamente`() = runBlocking {
        start { fail("não deveria chamar a rede"); noContent() }
        val caps = connector.capabilities
        assertFalse(caps.isMock)
        assertTrue(caps.supportsOfficialApi)
        assertFalse(caps.supportsBrowserAutomation)
        assertFalse(caps.supportsPersistentSession)
        assertTrue(caps.limitations.any { it.contains("sem lances, propostas ou mensagens") })

        val auth = connector.authenticate(PortalCredentials(1, "u", "s", "k"))
        assertTrue(auth is PortalAuthResult.Failure && auth.message.contains("Não suportado pelo PNCP"))
        assertTrue(connector.restoreSession("k") is PortalAuthResult.Failure)
        val submission = connector.submitProposal(
            ProposalPreparation(Portal.PNCP, "1", "item", 1, 10.0, emptyList()), HumanConfirmation("user", now),
        )
        assertTrue(submission is SubmissionResult.Failure)
        assertTrue(connector.submitBid("s", "item", 1.0, null) is BidSubmission.Rejected)
        assertNull(connector.readCurrentBidState("s"))
        try {
            connector.openLiveSession("s", com.licitaia.domain.bidding.DemoSessionSpecs.initial(1).first())
            fail("deveria falhar")
        } catch (_: UnsupportedOperationException) {
        }
        assertTrue(requests.isEmpty())
    }
}
