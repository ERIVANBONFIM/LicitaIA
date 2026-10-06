package com.licitaia.connector.comprasgov

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.SubmissionResult
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

/** Conector Compras.gov.br contra um MockWebServer que devolve respostas REAIS capturadas da API (fixtures). */
class ComprasGovConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var connector: ComprasGovConnector
    private val requests = mutableListOf<RecordedRequest>()

    /** Relógio fixo: 06/10/2026 12:00 em Brasília → janela 2026-09-06..2026-10-06. */
    private val now = brasilia(2026, 10, 6, 12, 0)

    private fun brasilia(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/comprasgov/$name")) { "fixture $name ausente" }.bufferedReader().readText()

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** Resultado vazio REAL da API: HTTP 200 com lista vazia. */
    private fun emptyPage() = jsonResponse("""{"resultado":[],"totalRegistros":0,"totalPaginas":0,"paginasRestantes":0}""")

    private val PAGE_P1 = "contratacoes_14133_pregao_mg_p1.json" // totalRegistros 399, 10 itens (todos com propostas já encerradas em 06/10)
    private val PAGE_P40 = "contratacoes_14133_pregao_mg_p40.json" // última página real, 9 itens com propostas abertas

    private fun start(clock: Long = now, dispatch: (RecordedRequest) -> MockResponse) {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return dispatch(request)
            }
        }
        server.start()
        connector = ComprasGovConnector(OkHttpClient(), ComprasGovConnector.defaultJson(), server.url("/"), clock = { clock })
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
    /**
     * Caminho SEM query string. Não usar `RecordedRequest.path` (membro do MockWebServer 4.x): ele devolve a
     * request-line inteira, com a query, e sombrearia uma extensão de mesmo nome — as rotas nunca casariam.
     */
    private val RecordedRequest.route: String? get() = requestUrl?.encodedPath

    private val CONTRATACOES = "/modulo-contratacoes/1_consultarContratacoes_PNCP_14133"
    private val LEGADO = "/modulo-legado/1_consultarLicitacao"

    /** Despacho padrão que simula a API real para Pregão/MG: sonda e páginas 1 e 2 (tamanho 200). */
    private fun pregaoMg(req: RecordedRequest): MockResponse = when (req.route) {
        CONTRATACOES -> when (req.query("pagina") to req.query("tamanhoPagina")) {
            "1" to "10" -> jsonResponse(fixture(PAGE_P1)) // sonda: só interessa totalRegistros = 399
            "2" to "200" -> jsonResponse(fixture(PAGE_P40)) // última página (mais recentes)
            "1" to "200" -> jsonResponse(fixture(PAGE_P1))
            else -> emptyPage()
        }
        else -> emptyPage()
    }

    // ------------------------------------------------------------ listOpportunities

    @Test
    fun `busca sonda o total e le as ultimas paginas (mais recentes) com os parametros documentados`() = runBlocking {
        start(dispatch = ::pregaoMg)

        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("mg")))

        val contratacoes = requests.filter { it.route == CONTRATACOES }
        assertEquals(3, contratacoes.size)
        contratacoes.forEach { r ->
            assertEquals("2026-09-06", r.query("dataPublicacaoPncpInicial"))
            assertEquals("2026-10-06", r.query("dataPublicacaoPncpFinal"))
            assertEquals("5", r.query("codigoModalidade"))
            assertEquals("MG", r.query("unidadeOrgaoUfSigla"))
            assertNull("não há parâmetro de texto na API", r.query("q"))
        }
        // sonda (10 itens) → última página de 200 → página anterior; a ordem ASC da API exige ler de trás para a frente
        assertEquals(listOf("1" to "10", "2" to "200", "1" to "200"), contratacoes.map { it.query("pagina") to it.query("tamanhoPagina") })
        // Pregão é compatível com o legado (Lei 8.666): uma consulta complementar com modalidade=5
        val legado = requests.filter { it.route == LEGADO }
        assertEquals(1, legado.size)
        assertEquals("5", legado.single().query("modalidade"))
        assertEquals("2026-09-06", legado.single().query("data_publicacao_inicial"))
        assertEquals("2026-10-06", legado.single().query("data_publicacao_final"))

        // só os 9 da última página estão com propostas abertas em 06/10; os 10 da página 1 já encerraram
        assertEquals(9, result.size)
        assertTrue(result.all { it.portal == Portal.COMPRAS_GOV && it.id.startsWith("COMPRAS_GOV:") && it.uf == "MG" })
        assertTrue(result.all { it.proposalDeadline >= now })
        assertEquals(result.sortedBy { it.proposalDeadline }, result)
    }

    @Test
    fun `contratacoes com prazo de propostas encerrado nao sao listadas`() = runBlocking {
        start { req ->
            if (req.route == CONTRATACOES) jsonResponse(fixture(PAGE_P1).replace("\"totalRegistros\":399", "\"totalRegistros\":10")) else emptyPage()
        }
        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.DISPENSA_ELETRONICA))
        assertTrue(result.isEmpty())
        // sonda + única página (10 cabe em uma página de 200); Dispensa não tem legado
        assertEquals(listOf("1" to "10", "1" to "200"), requests.map { it.query("pagina") to it.query("tamanhoPagina") })
        assertTrue(requests.none { it.route == LEGADO })
    }

    @Test
    fun `sem modalidade consulta Pregao, Dispensa e Concorrencia, sem UF e sem legado`() = runBlocking {
        start { emptyPage() }

        val result = connector.listOpportunities(OpportunityFilter())

        assertTrue(result.isEmpty())
        // Cada sonda vazia (30 dias) é repetida uma vez com a janela ampliada (60 dias).
        assertEquals(listOf("5", "5", "6", "6", "3", "3"), requests.map { it.query("codigoModalidade") })
        assertTrue(requests.all { it.route == CONTRATACOES && it.query("unidadeOrgaoUfSigla") == null && it.query("tamanhoPagina") == "10" })
    }

    @Test
    fun `sonda com total zero ou 204 amplia a janela uma vez e encerra sem pedir paginas`() = runBlocking {
        start { MockResponse().setResponseCode(204) }
        assertTrue(connector.listOpportunities(OpportunityFilter(modality = Modality.CONCORRENCIA, ufs = setOf("SP"))).isEmpty())
        val probes = requests.filter { it.route == CONTRATACOES }
        assertEquals(listOf("2026-09-06", "2026-08-07"), probes.map { it.query("dataPublicacaoPncpInicial") })
        assertTrue(probes.all { it.query("tamanhoPagina") == "10" })
        assertEquals("3", requests.first().query("codigoModalidade"))
        assertEquals("SP", requests.first().query("unidadeOrgaoUfSigla"))
    }

    @Test
    fun `chip so Compras gov br le paginas maiores que a busca geral`() = runBlocking {
        start { req ->
            if (req.route == CONTRATACOES) jsonResponse(fixture(PAGE_P1)) else emptyPage() // total 399
        }
        connector.listOpportunities(OpportunityFilter(portals = setOf(Portal.COMPRAS_GOV)))
        val focusedSizes = requests.filter { it.query("tamanhoPagina") != "10" }.map { it.query("tamanhoPagina") }.toSet()
        assertEquals(setOf("300"), focusedSizes) // 900 itens / 3 modalidades

        requests.clear()
        connector.listOpportunities(OpportunityFilter())
        val generalSizes = requests.filter { it.query("tamanhoPagina") != "10" }.map { it.query("tamanhoPagina") }.toSet()
        assertEquals(setOf("66"), generalSizes) // 200 itens / 3 modalidades
    }

    @Test
    fun `429 depois de ja ter resultados devolve o parcial`() = runBlocking {
        start { req ->
            when {
                req.route != CONTRATACOES -> emptyPage()
                req.query("codigoModalidade") == "5" ->
                    jsonResponse(fixture(if (req.query("tamanhoPagina") == "10") PAGE_P1 else PAGE_P40))
                else -> MockResponse().setResponseCode(429)
            }
        }
        val result = connector.listOpportunities(OpportunityFilter(ufs = setOf("MG")))
        assertEquals(9, result.size)
    }

    @Test
    fun `credenciamento nao tem codigo nesta API e nao gera requisicao`() = runBlocking {
        start { fail("não deveria chamar a rede"); emptyPage() }
        assertTrue(connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO)).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `mais de tres UFs nao sao enviadas a API e o filtro e aplicado localmente`() = runBlocking {
        start(dispatch = ::pregaoMg)
        val result = connector.listOpportunities(
            OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG", "SP", "RJ", "ES")),
        )
        assertTrue(requests.all { it.query("unidadeOrgaoUfSigla") == null })
        assertEquals(9, result.size) // fixture é toda MG
        // UF não contemplada → filtro local descarta tudo
        requests.clear()
        val none = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("SP", "RJ", "ES", "BA")))
        assertTrue(none.isEmpty())
    }

    @Test
    fun `filtros sem suporte na API (texto e valor) sao aplicados localmente`() = runBlocking {
        start(dispatch = ::pregaoMg)

        val byText = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG"), query = "backup imutável"))
        assertEquals(listOf("COMPRAS_GOV:23664303000104-1-000045/2026"), byText.map { it.id })

        requests.clear()
        val byValue = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG"), minValue = 1_000_000.0))
        assertEquals(listOf("COMPRAS_GOV:00399857000126-1-000334/2026"), byValue.map { it.id })
        assertTrue(byValue.all { it.estimatedValue >= 1_000_000.0 })
    }

    @Test
    fun `filtro de portais que exclui o Compras gov br nao gera requisicao`() = runBlocking {
        start { fail("não deveria chamar a rede"); emptyPage() }
        assertTrue(connector.listOpportunities(OpportunityFilter(portals = setOf(Portal.PNCP))).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `legado complementa Pregao com licitacoes 8666 abertas e descarta as pertence14133`() = runBlocking {
        // relógio em 10/11/2023: as licitações da fixture legada (abertura 14..21/11/2023) ainda estão abertas
        start(clock = brasilia(2023, 11, 10, 12, 0)) { req ->
            when (req.route) {
                LEGADO -> jsonResponse(fixture("legado_licitacao_pregao_2023-11_p1.json"))
                else -> emptyPage()
            }
        }
        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO))

        val legado = requests.single { it.route == LEGADO }
        assertEquals("2023-10-11", legado.query("data_publicacao_inicial"))
        assertEquals("2023-11-10", legado.query("data_publicacao_final"))
        assertEquals("5", legado.query("modalidade"))
        assertEquals("50", legado.query("tamanhoPagina"))

        assertEquals(6, result.size) // 10 registros − 4 pertence14133 (duplicatas do módulo 14.133)
        assertTrue(result.all { it.modality == Modality.PREGAO_ELETRONICO && it.uf.isEmpty() && it.agency.startsWith("UASG ") })
        assertTrue(result.any { it.id == "COMPRAS_GOV:152005-05-00027/2023" })
    }

    @Test
    fun `falha no legado nao derruba a busca principal`() = runBlocking {
        start { req -> if (req.route == LEGADO) MockResponse().setResponseCode(500) else pregaoMg(req) }
        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG")))
        assertEquals(9, result.size)
    }

    @Test
    fun `erro HTTP vira ComprasGovException com mensagem em portugues`() = runBlocking {
        start { MockResponse().setResponseCode(503).setBody("indisponível") }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO))
            fail("deveria falhar")
        } catch (e: ComprasGovException) {
            assertEquals(ComprasGovException.Kind.HTTP, e.kind)
            assertEquals(503, e.httpStatus)
            assertTrue(e.message!!, e.message!!.contains("indisponível"))
        }
    }

    @Test
    fun `erro de validacao 400 (problem json real) expoe o detalhe da API`() = runBlocking {
        val problem = """{"type":"about:blank","title":"Erro de Validação","status":400,"detail":"tamanhoPagina: O tamanho da página deve ser no mínimo 10","instance":"$CONTRATACOES","timestamp":"2026-10-06T06:25:24.081646532Z"}"""
        start { jsonResponse(problem, 400) }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.DISPENSA_ELETRONICA))
            fail("deveria falhar")
        } catch (e: ComprasGovException) {
            assertEquals(400, e.httpStatus)
            assertTrue(e.message!!, e.message!!.contains("O tamanho da página deve ser no mínimo 10"))
        }
    }

    @Test
    fun `limite de consultas (429) e corpo invalido tem mensagens proprias`() = runBlocking {
        start { MockResponse().setResponseCode(429) }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.DISPENSA_ELETRONICA))
            fail("deveria falhar")
        } catch (e: ComprasGovException) {
            assertEquals(429, e.httpStatus)
            assertTrue(e.message!!.contains("Aguarde"))
        }
        server.shutdown()

        start { jsonResponse("<html>não é json</html>") }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.DISPENSA_ELETRONICA))
            fail("deveria falhar")
        } catch (e: ComprasGovException) {
            assertEquals(ComprasGovException.Kind.INVALID_RESPONSE, e.kind)
        }
    }

    @Test
    fun `servidor inacessivel vira ComprasGovException de conexao`() = runBlocking {
        start { emptyPage() }
        val url = server.url("/")
        server.shutdown()
        val offline = ComprasGovConnector(OkHttpClient(), ComprasGovConnector.defaultJson(), url, clock = { now })
        try {
            offline.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO))
            fail("deveria falhar")
        } catch (e: ComprasGovException) {
            assertTrue(e.kind == ComprasGovException.Kind.OFFLINE || e.kind == ComprasGovException.Kind.TIMEOUT)
            assertTrue(e.message!!.contains("Compras.gov.br"))
        }
    }

    // ------------------------------------------------------------ getTenderDetails

    @Test
    fun `detalhe 14133 consulta por numero de controle PNCP e traz itens`() = runBlocking {
        start { req ->
            when (req.route) {
                "/modulo-contratacoes/1.1_consultarContratacoes_PNCP_14133_Id" -> jsonResponse(fixture("contratacao_14133_05055128000176-1-000108_2026.json"))
                "/modulo-contratacoes/2.1_consultarItensContratacoes_PNCP_14133_Id" -> jsonResponse(fixture("itens_14133_05055128000176-1-000108_2026.json"))
                else -> MockResponse().setResponseCode(404).setBody("""{ "statusCode": 404, "message": "Resource not found" }""")
            }
        }

        val details = connector.getTenderDetails("COMPRAS_GOV:05055128000176-1-000108/2026")

        assertNotNull(details)
        assertEquals(2, requests.size)
        requests.forEach { r ->
            assertEquals("numeroControlePNCPCompra", r.query("tipo"))
            assertEquals("05055128000176-1-000108/2026", r.query("codigo"))
        }
        assertEquals("COMPRAS_GOV:05055128000176-1-000108/2026", details!!.opportunity.id)
        assertEquals(Modality.DISPENSA_ELETRONICA, details.opportunity.modality)
        assertEquals("UNIVERSIDADE FEDERAL DE CAMPINA GRANDE — CENTRO DE CIENCIAS E TECNOLOGIA AGROALIMENTAR", details.opportunity.agency)
        assertNull("a API de dados abertos não publica o edital", details.editalText)
        assertEquals("https://pncp.gov.br/app/editais/05055128000176/2026/108", details.opportunity.editalUrl)
        assertEquals(7, details.items.size)
        assertTrue(details.items.first().startsWith("Item 1 — Tubo Cobre"))
    }

    @Test
    fun `detalhe legado consulta por id_compra e usa o nome da UASG dos itens`() = runBlocking {
        start { req ->
            when (req.route) {
                "/modulo-legado/1.1_consultarLicitacao_Id" -> jsonResponse(fixture("legado_licitacao_15200505000272023.json"))
                "/modulo-legado/2.1_consultarItemLicitacao_Id" -> jsonResponse(fixture("legado_itens_15200505000272023.json"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val details = connector.getTenderDetails("COMPRAS_GOV:152005-05-00027/2023")

        assertNotNull(details)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.query("id_compra") == "15200505000272023" })
        assertEquals("COMPRAS_GOV:152005-05-00027/2023", details!!.opportunity.id)
        assertEquals("MEC-INES-INST.NAC.DE EDUCACAO DE SURDOS/RJ (UASG 152005)", details.opportunity.agency)
        assertEquals(1, details.items.size)
        assertTrue(details.items.first().contains("1 UNIDADE"))
    }

    @Test
    fun `detalhe sem itens disponiveis mantem a oportunidade`() = runBlocking {
        start { req ->
            when (req.route) {
                "/modulo-contratacoes/1.1_consultarContratacoes_PNCP_14133_Id" -> jsonResponse(fixture("contratacao_14133_05055128000176-1-000108_2026.json"))
                else -> MockResponse().setResponseCode(500)
            }
        }
        val details = connector.getTenderDetails("COMPRAS_GOV:05055128000176-1-000108/2026")
        assertNotNull(details)
        assertTrue(details!!.items.isEmpty())
    }

    @Test
    fun `detalhe de id invalido ou inexistente devolve null`() = runBlocking {
        start { emptyPage() }
        assertNull(connector.getTenderDetails("PNCP:05055128000176-1-000108/2026"))
        assertNull(connector.getTenderDetails("COMPRAS_GOV:abc"))
        assertTrue(requests.isEmpty())
        assertNull(connector.getTenderDetails("COMPRAS_GOV:05055128000176-1-999999/2026"))
        assertEquals(1, requests.size)
    }

    // ------------------------------------------------------------ capacidades / não suportado

    @Test
    fun `capacidades declaram consulta publica real e metodos de acao falham explicitamente`() = runBlocking {
        start { fail("não deveria chamar a rede"); emptyPage() }
        val caps = connector.capabilities
        assertFalse(caps.isMock)
        assertTrue(caps.supportsOfficialApi)
        assertTrue(caps.supportsWebView)
        assertFalse(caps.supportsBrowserAutomation)
        assertFalse(caps.supportsPersistentSession)
        assertTrue(caps.limitations.any { it.contains("lances, propostas e mensagens são manuais no portal") })

        val auth = connector.authenticate(PortalCredentials(1, "u", "s", "k"))
        assertTrue(auth is PortalAuthResult.Failure && auth.message.contains("Não suportado pelo Compras.gov.br"))
        assertTrue(connector.restoreSession("k") is PortalAuthResult.Failure)
        val submission = connector.submitProposal(
            ProposalPreparation(Portal.COMPRAS_GOV, "1", "item", 1, 10.0, emptyList()), HumanConfirmation("user", now),
        )
        assertTrue(submission is SubmissionResult.Failure)
        assertTrue(connector.submitBid("s", "item", 1.0, null) is BidSubmission.Rejected)
        assertNull(connector.readCurrentBidState("s"))
        assertTrue(connector.getMessages("s").isEmpty())
        try {
            connector.openLiveSession("s", com.licitaia.domain.bidding.DemoSessionSpecs.initial(1).first())
            fail("deveria falhar")
        } catch (_: UnsupportedOperationException) {
        }
        assertTrue(requests.isEmpty())
    }
}
