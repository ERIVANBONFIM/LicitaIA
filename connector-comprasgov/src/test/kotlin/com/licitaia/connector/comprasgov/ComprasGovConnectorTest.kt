package com.licitaia.connector.comprasgov

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.InMemoryListingRowStore
import com.licitaia.connector.api.ListingRowStore
import com.licitaia.connector.api.ListingSyncMark
import com.licitaia.connector.api.SourceSyncPolicy
import com.licitaia.connector.api.OpportunityScreen
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.SubmissionResult
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/**
 * Conector Compras.gov.br contra um MockWebServer: leitura completa paginada (até `paginasRestantes = 0` ou o teto),
 * cache de linhas, triagem e enriquecimento de prazo pelo PNCP; respostas REAIS capturadas (fixtures) nos detalhes.
 */
class ComprasGovConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var connector: ComprasGovConnector
    private val requests = mutableListOf<RecordedRequest>()

    /** Relógio: 06/10/2026 12:00 em Brasília → janela de 60 dias 2026-08-07..2026-10-06. */
    private val now = brasilia(2026, 10, 6, 12, 0)
    private var clockNow = now

    private fun brasilia(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/comprasgov/$name")) { "fixture $name ausente" }.bufferedReader().readText()

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** Resultado vazio REAL da API: HTTP 200 com lista vazia. */
    private fun emptyPage() = jsonResponse("""{"resultado":[],"totalRegistros":0,"totalPaginas":0,"paginasRestantes":0}""")

    // Intervalo da completa em primeiro plano fixado em 6 h nos testes (o padrão de produção é 30 h, com a diária às 05:30).
    private val fast = ComprasGovConnector.Tuning(
        pageDelayMs = 0, retryDelaysMs = listOf(0, 0, 0), enrichMinIntervalMs = 0, fullSyncIntervalMs = 6L * 60 * 60 * 1000,
    )

    private fun start(
        tuning: ComprasGovConnector.Tuning = fast,
        clock: (() -> Long)? = null,
        store: ListingRowStore = InMemoryListingRowStore(),
        dispatch: (RecordedRequest) -> MockResponse,
    ) {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return dispatch(request)
            }
        }
        server.start()
        connector = ComprasGovConnector(
            OkHttpClient(), ComprasGovConnector.defaultJson(), server.url("/"),
            clock = clock ?: { clockNow }, pncpBaseUrl = server.url("/"), tuning = tuning, store = store,
        )
    }

    @Before
    fun setUp() {
        requests.clear()
        clockNow = now
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
    private val PNCP = "/api/consulta/v1/orgaos/"

    private fun listings() = synchronized(requests) { requests.filter { it.route == CONTRATACOES } }
    private fun pncpCalls() = synchronized(requests) { requests.filter { it.route?.startsWith(PNCP) == true } }

    // ------------------------------------------------------------ dados sintéticos (mesmo formato da API real)

    private fun control(seq: Int, cnpj: String = "11111111000111") = "$cnpj-1-${seq.toString().padStart(6, '0')}/2026"

    private fun row(
        seq: Int,
        objeto: String = "Aquisição de material $seq",
        uf: String = "MG",
        enc: String? = "2026-10-20T10:00:00",
        pub: String = "2026-09-20T10:00:00",
        situacao: String = "Divulgada no PNCP",
        valor: Double = 1000.0,
    ): String {
        val cnpj = "11111111000111"
        val encField = enc?.let { ""","dataEncerramentoPropostaPncp":"$it"""" } ?: ""
        return """{"idCompra":"x$seq","numeroControlePNCP":"${control(seq)}","anoCompraPncp":2026,"sequencialCompraPncp":$seq,""" +
            """"orgaoEntidadeCnpj":"$cnpj","orgaoEntidadeRazaoSocial":"Órgão $seq","unidadeOrgaoUfSigla":"$uf",""" +
            """"unidadeOrgaoMunicipioNome":"Cidade","numeroCompra":"$seq","codigoModalidade":5,"modalidadeIdPncp":6,""" +
            """"modalidadeNome":"Pregão - Eletrônico","objetoCompra":"$objeto","situacaoCompraNomePncp":"$situacao",""" +
            """"valorTotalEstimado":$valor,"dataPublicacaoPncp":"$pub"$encField,"contratacaoExcluida":false}"""
    }

    private val PUB = Regex(""""dataPublicacaoPncp":"([^"]+)"""")

    /**
     * Serve [rowsByCode] paginado como a API real (tamanhoPagina, totalPaginas, paginasRestantes), só as linhas com
     * publicação dentro de dataPublicacaoPncpInicial..Final (como a API).
     */
    private fun serve(req: RecordedRequest, rowsByCode: Map<String, List<String>>): MockResponse {
        val from = req.query("dataPublicacaoPncpInicial").orEmpty()
        val to = req.query("dataPublicacaoPncpFinal") ?: "9999-12-31"
        val rows = rowsByCode[req.query("codigoModalidade")].orEmpty().filter { r ->
            val pub = PUB.find(r)?.groupValues?.get(1)?.take(10) ?: return@filter true
            pub >= from && pub <= to
        }
        val size = req.query("tamanhoPagina")!!.toInt()
        val pagina = req.query("pagina")!!.toInt()
        val totalPages = (rows.size + size - 1) / size
        val slice = rows.drop((pagina - 1) * size).take(size)
        return jsonResponse(
            """{"resultado":[${slice.joinToString(",")}],"totalRegistros":${rows.size},"totalPaginas":$totalPages,""" +
                """"paginasRestantes":${(totalPages - pagina).coerceAtLeast(0)}}""",
        )
    }

    private fun pncpStatus(enc: String?, situacaoId: Int = 1, situacao: String = "Divulgada no PNCP") = jsonResponse(
        """{"numeroControlePNCP":"x","dataAberturaProposta":null,"dataEncerramentoProposta":${enc?.let { "\"$it\"" } ?: "null"},""" +
            """"situacaoCompraId":$situacaoId,"situacaoCompraNome":"$situacao","modalidadeId":8}""",
    )

    // ------------------------------------------------------------ leitura completa

    @Test
    fun `le todas as paginas da janela de 60 dias ate paginasRestantes zero`() = runBlocking {
        val pregao = (1..25).map { row(it) }
        start(fast.copy(pageSize = 10)) { req -> if (req.route == CONTRATACOES) serve(req, mapOf("5" to pregao)) else emptyPage() }

        val listing = connector.listScreened(OpportunityFilter(), OpportunityScreen.ACCEPT_ALL)

        val pregaoPages = listings().filter { it.query("codigoModalidade") == "5" }.map { it.query("pagina") }
        assertEquals(listOf("1", "2", "3"), pregaoPages)
        // Pregão, Dispensa e Concorrência (códigos do Compras.gov.br), sem UF, janela de 60 dias, sem parâmetro de texto
        assertEquals(setOf("5", "6", "3"), listings().map { it.query("codigoModalidade") }.toSet())
        listings().forEach { r ->
            assertEquals("2026-08-07", r.query("dataPublicacaoPncpInicial"))
            assertEquals("2026-10-06", r.query("dataPublicacaoPncpFinal"))
            assertEquals("10", r.query("tamanhoPagina"))
            assertNull(r.query("unidadeOrgaoUfSigla"))
            assertNull(r.query("q"))
        }
        assertEquals(25, listing.diagnostics.read)
        assertEquals(25, listing.diagnostics.candidates)
        assertEquals(25, listing.opportunities.size)
        assertFalse(listing.diagnostics.truncated)
        assertTrue(requests.none { it.route == LEGADO })
    }

    @Test
    fun `teto de linhas le as paginas mais recentes primeiro e marca truncado`() = runBlocking {
        // 100 linhas em 10 páginas; teto 30 → página 1 (sonda/total) + as duas últimas (mais recentes).
        val pregao = (1..100).map { row(it) }
        start(fast.copy(pageSize = 10, maxRows = 30)) { req -> serve(req, mapOf("5" to pregao)) }

        val listing = connector.listScreened(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO), OpportunityScreen.ACCEPT_ALL)

        assertEquals(listOf("1", "10", "9"), listings().map { it.query("pagina") })
        assertEquals(30, listing.diagnostics.read)
        assertTrue(listing.diagnostics.truncated)
        assertTrue(listing.opportunities.any { it.id == "COMPRAS_GOV:${control(100)}" })
        assertTrue(listing.opportunities.none { it.id == "COMPRAS_GOV:${control(50)}" })
    }

    @Test
    fun `teto e dividido entre modalidades e a menor e lida inteira`() = runBlocking {
        val pregao = (1..60).map { row(it) }
        val concorrencia = (1001..1015).map { row(it) }
        start(fast.copy(pageSize = 10, maxRows = 45)) { req -> serve(req, mapOf("5" to pregao, "3" to concorrencia)) }

        val listing = connector.listScreened(OpportunityFilter(), OpportunityScreen.ACCEPT_ALL)

        // Concorrência (15) inteira; Pregão fica com o restante do teto (45 − 15 = 30), mais recentes primeiro.
        assertEquals(listOf("1", "2"), listings().filter { it.query("codigoModalidade") == "3" }.map { it.query("pagina") })
        assertEquals(listOf("1", "6", "5"), listings().filter { it.query("codigoModalidade") == "5" }.map { it.query("pagina") })
        assertEquals(45, listing.diagnostics.read)
    }

    @Test
    fun `primeira leitura e completa e as atualizacoes seguintes so baixam o incremental ate 6 h`() = runBlocking {
        val pregao = (1..15).map { row(it) }.toMutableList()
        start(fast.copy(pageSize = 10)) { req -> serve(req, mapOf("5" to pregao.toList())) }
        val filter = OpportunityFilter(modality = Modality.PREGAO_ELETRONICO)

        connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals("completa: 2 páginas da janela", listOf("1", "2"), listings().map { it.query("pagina") })
        assertTrue(listings().all { it.query("dataPublicacaoPncpInicial") == "2026-08-07" })
        assertEquals(1, connector.lastSync!!.fullSyncs)

        // < 90 s: nem incremental (só o cache).
        requests.clear()
        clockNow = now + 60_000L
        assertEquals(15, connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL).diagnostics.read)
        assertTrue(listings().isEmpty())

        // Atualização de 2 min: só as publicações desde o dia da última sincronização (menos 1 dia de defasagem).
        requests.clear()
        pregao += row(16, objeto = "Publicada hoje", pub = "2026-10-06T11:00:00")
        clockNow = now + 2 * 60_000L
        val again = connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(1, listings().size)
        assertEquals("2026-10-05", listings().single().query("dataPublicacaoPncpInicial"))
        assertEquals("2026-10-06", listings().single().query("dataPublicacaoPncpFinal"))
        assertEquals(1, connector.lastSync!!.downloaded)
        assertEquals(0, connector.lastSync!!.fullSyncs)
        assertEquals(16, again.opportunities.size)
        assertEquals(16, again.diagnostics.read)

        // 5 h depois: ainda incremental. 6 h depois da completa: nova varredura completa.
        requests.clear()
        clockNow = now + 5 * 60 * 60_000L
        connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertTrue(listings().all { it.query("dataPublicacaoPncpInicial") == "2026-10-05" })
        requests.clear()
        clockNow = now + 6 * 60 * 60_000L
        connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(listOf("1", "2"), listings().map { it.query("pagina") })
        assertTrue(listings().all { it.query("dataPublicacaoPncpInicial") == "2026-08-07" })
        assertEquals(1, connector.lastSync!!.fullSyncs)
    }

    @Test
    fun `Worker em modo incremental nunca faz a varredura completa`() = runBlocking {
        val pregao = listOf(row(1, pub = "2026-10-05T10:00:00"), row(2, pub = "2026-09-01T10:00:00"))
        val store = InMemoryListingRowStore()
        start(fast, store = store) { req -> serve(req, mapOf("5" to pregao)) }
        val filter = OpportunityFilter(modality = Modality.PREGAO_ELETRONICO)

        // Cache vazio (nenhuma completa): o Worker não sincroniza nem grava marca — a completa fica para o primeiro plano.
        val first = withContext(SourceSyncPolicy.INCREMENTAL_ONLY) { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
        assertTrue(listings().isEmpty())
        assertTrue(first.opportunities.isEmpty())
        assertNull(store.syncMark(5, ""))
        assertEquals(0, store.size)

        // Última completa há 5 h (< 6 h) e também há 7 h: o Worker continua só incremental.
        for (hoursAgo in listOf(5L, 7L)) {
            requests.clear()
            store.saveSyncMark(ListingSyncMark(5, "", now - hoursAgo * 3_600_000L, now - 10 * 60_000L))
            withContext(SourceSyncPolicy.INCREMENTAL_ONLY) { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
            assertEquals(1, listings().size)
            assertTrue(listings().none { it.query("dataPublicacaoPncpInicial") == "2026-08-07" })
            assertEquals(0, connector.lastSync!!.fullSyncs)
        }

        // Em primeiro plano, a mesma marca de 7 h dispara a completa.
        requests.clear()
        store.saveSyncMark(ListingSyncMark(5, "", now - 7 * 3_600_000L, now - 10 * 60_000L))
        connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals("2026-08-07", listings().single().query("dataPublicacaoPncpInicial"))
    }

    @Test
    fun `apos instalar - Worker com cache vazio e depois a primeira busca em primeiro plano faz a varredura completa`() = runBlocking {
        // Janela com 3 páginas: as mais antigas (página 1) encerradas; as recentes abertas (como a API real, ascendente).
        val old = (1..10).map { row(it, pub = "2026-08-10T10:00:00", enc = "2026-08-20T10:00:00") }
        val recent = (11..25).map { row(it, pub = "2026-10-01T10:00:00", enc = "2026-10-20T10:00:00") }
        val store = InMemoryListingRowStore()
        start(fast.copy(pageSize = 10), store = store) { req -> if (req.route == CONTRATACOES) serve(req, mapOf("5" to old + recent)) else emptyPage() }
        val filter = OpportunityFilter()

        // 1) Worker logo após a instalação: nada sincronizado, nenhuma marca.
        withContext(SourceSyncPolicy.INCREMENTAL_ONLY) { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
        assertTrue(listings().isEmpty())
        assertNull(store.syncMark(5, ""))

        // 2) Primeira busca em primeiro plano (segundos depois): varredura completa da janela de 60 dias.
        requests.clear()
        clockNow = now + 30_000L
        val fg = connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(3, connector.lastSync!!.fullSyncs)
        assertEquals(listOf("1", "2", "3"), listings().filter { it.query("codigoModalidade") == "5" }.map { it.query("pagina") })
        assertTrue(listings().all { it.query("dataPublicacaoPncpInicial") == "2026-08-07" })
        assertEquals(15, fg.diagnostics.read)
        assertEquals(15, fg.opportunities.size)
        assertFalse(fg.diagnostics.syncing)
        assertNull(fg.diagnostics.syncFailure)
        assertTrue(store.syncMark(5, "")!!.lastFullSyncAt > 0)

        // 3) Worker seguinte (15 min): agora sim só o incremental.
        requests.clear()
        clockNow = now + 15 * 60_000L
        withContext(SourceSyncPolicy.INCREMENTAL_ONLY) { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
        assertTrue(listings().isNotEmpty())
        assertTrue(listings().none { it.query("dataPublicacaoPncpInicial") == "2026-08-07" })
    }

    @Test
    fun `varredura completa demorada devolve o parcial sincronizando e continua mesmo se a tela cancelar`() = runBlocking {
        val recent = (1..30).map { row(it, pub = "2026-10-01T10:00:00") }
        val store = InMemoryListingRowStore()
        val release = java.util.concurrent.CountDownLatch(1)
        start(fast.copy(pageSize = 10, foregroundWaitMs = 300), store = store) { req ->
            // Página 1 responde na hora; as demais só depois de "liberadas" (API lenta).
            if (req.query("pagina") != "1") release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            serve(req, mapOf("5" to recent))
        }
        val filter = OpportunityFilter(modality = Modality.PREGAO_ELETRONICO)

        val partial = connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertTrue("resultado parcial marcado", partial.diagnostics.syncing)
        assertEquals(10, partial.diagnostics.read)
        assertNotNull(connector.syncProgress.value)
        assertTrue(partial.diagnostics.summary.contains("sincronização em andamento"))

        // A tela sai (cancela a espera) — a varredura continua no escopo do conector.
        val waiting = CoroutineScope(Dispatchers.IO).launch { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
        delay(50)
        waiting.cancel()
        release.countDown()
        val deadline = System.currentTimeMillis() + 10_000
        while (connector.syncProgress.value != null && System.currentTimeMillis() < deadline) delay(20)
        assertNull(connector.syncProgress.value)
        assertTrue("marca de completa gravada", store.syncMark(5, "")!!.lastFullSyncAt > 0)
        assertEquals(30, store.size)

        // Próxima busca: cache completo, sem nova varredura.
        requests.clear()
        val done = connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertFalse(done.diagnostics.syncing)
        assertEquals(30, done.diagnostics.read)
        assertTrue(listings().isEmpty())
    }

    @Test
    fun `varredura completa que falha com cache parcial aparece no diagnostico e e refeita na proxima`() = runBlocking {
        val rows = (1..30).map { row(it, pub = "2026-10-01T10:00:00") }
        val store = InMemoryListingRowStore()
        var fail = true
        start(fast.copy(pageSize = 10), store = store) { req ->
            if (fail && req.query("pagina") != "1") MockResponse().setResponseCode(503) else serve(req, mapOf("5" to rows))
        }
        val filter = OpportunityFilter(modality = Modality.PREGAO_ELETRONICO)

        val first = connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(10, first.diagnostics.read)
        assertNotNull(first.diagnostics.syncFailure)
        assertTrue(first.diagnostics.summary.contains("não terminou"))
        assertNull("sem marca: a próxima refaz a completa", store.syncMark(5, ""))

        fail = false
        requests.clear()
        clockNow = now + 2 * 60_000L
        val second = connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(listOf("1", "2", "3"), listings().map { it.query("pagina") })
        assertEquals(30, second.diagnostics.read)
        assertNull(second.diagnostics.syncFailure)
    }

    @Test
    fun `consultas de prazo ao PNCP esperam a listagem do PNCP da mesma busca`() = runBlocking {
        start(fast) { req ->
            when {
                req.route == CONTRATACOES -> serve(req, mapOf("5" to listOf(row(1, enc = null))))
                req.route?.startsWith(PNCP) == true -> pncpStatus("2026-10-30T10:00:00")
                else -> emptyPage()
            }
        }
        val gate = com.licitaia.connector.api.PncpTrafficGate()
        val filter = OpportunityFilter(modality = Modality.PREGAO_ELETRONICO)
        val job = async(Dispatchers.IO) {
            withContext(gate) { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
        }
        delay(500)
        assertTrue("nenhuma consulta de prazo antes da listagem do PNCP terminar", pncpCalls().isEmpty())
        gate.listingDone.complete(Unit)
        val listing = job.await()
        assertEquals(1, pncpCalls().size)
        assertTrue(listing.opportunities.single().hasProposalDeadline)
    }

    @Test
    fun `limpeza remove do cache o que saiu da janela ou encerrou`() = runBlocking {
        val rows = listOf(
            row(1, pub = "2026-08-08T10:00:00", enc = "2026-12-01T10:00:00"), // sai da janela em 2 dias
            row(2, pub = "2026-10-01T10:00:00", enc = "2026-10-07T10:00:00"), // encerra amanhã
            row(3, pub = "2026-10-01T10:00:00", enc = null), // sem prazo: fica
            row(4, pub = "2026-10-01T10:00:00", enc = "2026-11-01T10:00:00"),
        )
        val store = InMemoryListingRowStore()
        start(fast, store = store) { req -> serve(req, mapOf("5" to rows)) }
        val filter = OpportunityFilter(modality = Modality.PREGAO_ELETRONICO)
        connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(4, store.size)

        // 3 dias depois (incremental): o 1 saiu da janela de 60 dias e o 2 encerrou → removidos do cache.
        clockNow = now + 3 * 24 * 3_600_000L
        val later = connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(2, store.size)
        assertEquals(setOf(3, 4), later.opportunities.map { it.id.substringAfter("-1-").substringBefore("/").toInt() }.toSet())

        // Revogada vista numa leitura posterior também sai do cache.
        requests.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                serve(request, mapOf("5" to listOf(row(4, pub = "2026-10-08T10:00:00", situacao = "Revogada"))))
        }
        clockNow += 3 * 60_000L
        connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(1, store.size)
        // ...e fica registrada para a limpeza do cache de oportunidades (uma vez só).
        val withdrawn = connector.drainWithdrawnIds()
        assertEquals(1, withdrawn.size)
        assertTrue(withdrawn.single().endsWith(control(4)))
        assertTrue(connector.drainWithdrawnIds().isEmpty())
    }

    @Test
    fun `atualizacao diaria forca a varredura completa e o primeiro plano so baixa o incremental ate 30 h`() = runBlocking {
        val pregao = (1..15).map { row(it) }
        val store = InMemoryListingRowStore()
        start(fast.copy(pageSize = 10, fullSyncIntervalMs = ComprasGovConnector.DEFAULT_FULL_SYNC_INTERVAL_MS), store = store) { req ->
            serve(req, mapOf("5" to pregao))
        }
        val filter = OpportunityFilter(modality = Modality.PREGAO_ELETRONICO)
        // Completa de ontem (25 h atrás): abrir a tela não relê a janela inteira.
        store.saveSyncMark(ListingSyncMark(5, "", now - 25 * 3_600_000L, now - 25 * 3_600_000L))
        connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL)
        assertEquals(0, connector.lastSync!!.fullSyncs)
        assertTrue(listings().none { it.query("dataPublicacaoPncpInicial") == "2026-08-07" })

        // A diária (05:30) com a mesma marca: varredura completa.
        requests.clear()
        clockNow = now + 5 * 60_000L
        withContext(SourceSyncPolicy.DAILY_FULL) { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
        assertEquals(1, connector.lastSync!!.fullSyncs)
        assertEquals(listOf("1", "2"), listings().map { it.query("pagina") })

        // Os radares da mesma execução (minutos depois) reaproveitam a completa recém-feita.
        requests.clear()
        clockNow = now + 10 * 60_000L
        withContext(SourceSyncPolicy.DAILY_FULL) { connector.listScreened(filter, OpportunityScreen.ACCEPT_ALL) }
        assertEquals(0, connector.lastSync!!.fullSyncs)
    }

    @Test
    fun `decisao de sincronizacao pelo tempo`() {
        val h = 3_600_000L
        val six = 6 * h
        val min90 = 90_000L
        fun mark(fullAgo: Long?, syncAgo: Long) = ListingSyncMark(5, "", fullAgo?.let { now - it } ?: 0L, now - syncAgo)
        assertEquals(SyncPlanner.Action.FULL, SyncPlanner.decide(null, now, false, six, min90))
        // Worker sem nenhuma completa anterior: nada (nem incremental).
        assertEquals(SyncPlanner.Action.NONE, SyncPlanner.decide(null, now, true, six, min90))
        assertEquals(SyncPlanner.Action.NONE, SyncPlanner.decide(mark(null, 10 * 60_000L), now, true, six, min90))
        assertEquals(SyncPlanner.Action.INCREMENTAL, SyncPlanner.decide(mark(5 * h, 10 * 60_000L), now, false, six, min90))
        assertEquals(SyncPlanner.Action.NONE, SyncPlanner.decide(mark(5 * h, 60_000L), now, false, six, min90))
        assertEquals(SyncPlanner.Action.FULL, SyncPlanner.decide(mark(six, 60_000L), now, false, six, min90))
        assertEquals(SyncPlanner.Action.INCREMENTAL, SyncPlanner.decide(mark(7 * h, 10 * 60_000L), now, true, six, min90))
        assertEquals("só incrementais até agora", SyncPlanner.Action.FULL, SyncPlanner.decide(mark(null, 10 * 60_000L), now, false, six, min90))
        // Relógio voltou (marca no futuro): refaz a completa em primeiro plano.
        assertEquals(SyncPlanner.Action.FULL, SyncPlanner.decide(mark(-h, -h), now, false, six, min90))
        // Atualização diária forçada: completa mesmo com a última há 2 h; reaproveita a de 30 min atrás; nunca no Worker incremental.
        assertEquals(SyncPlanner.Action.FULL, SyncPlanner.decide(mark(2 * h, 2 * h), now, false, six, min90, forceFull = true))
        assertEquals(SyncPlanner.Action.INCREMENTAL, SyncPlanner.decide(mark(h / 2, 10 * 60_000L), now, false, six, min90, forceFull = true))
        assertEquals(SyncPlanner.Action.INCREMENTAL, SyncPlanner.decide(mark(2 * h, 10 * 60_000L), now, true, six, min90, forceFull = true))

        val day = 24 * h
        val windowStart = now - 60 * day
        assertEquals(now - 2 * day, SyncPlanner.incrementalStart(mark(h, day), now, 1, windowStart))
        assertEquals(now - 2 * day, SyncPlanner.incrementalStart(null, now, 1, windowStart))
        assertEquals(windowStart, SyncPlanner.incrementalStart(mark(h, 90 * day), now, 1, windowStart))
    }

    @Test
    fun `429 e repetido com backoff e a leitura continua`() = runBlocking {
        val pregao = (1..15).map { row(it) }
        var throttled = 0
        start(fast.copy(pageSize = 10)) { req ->
            if (req.query("pagina") == "2" && throttled < 2) {
                throttled++
                MockResponse().setResponseCode(429)
            } else {
                serve(req, mapOf("5" to pregao))
            }
        }
        val listing = connector.listScreened(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO), OpportunityScreen.ACCEPT_ALL)
        assertEquals(listOf("1", "2", "2", "2"), listings().map { it.query("pagina") })
        assertEquals(15, listing.diagnostics.read)
    }

    @Test
    fun `429 persistente depois de ja ter linhas devolve o parcial`() = runBlocking {
        start(fast.copy(pageSize = 10)) { req ->
            when (req.query("codigoModalidade")) {
                "5" -> serve(req, mapOf("5" to (1..5).map { row(it) }))
                else -> MockResponse().setResponseCode(429)
            }
        }
        val result = connector.listOpportunities(OpportunityFilter(ufs = setOf("MG")))
        assertEquals(5, result.size)
    }

    @Test
    fun `filtro triagem e prazo vencido reduzem as candidatas`() = runBlocking {
        val rows = listOf(
            row(1, objeto = "Contratação de link dedicado de internet", uf = "MG"),
            row(2, objeto = "Link de internet fibra óptica", uf = "SP"),
            row(3, objeto = "Aquisição de pneus", uf = "MG"),
            row(4, objeto = "Link de internet", uf = "MG", enc = "2026-09-01T10:00:00"), // encerrada
            row(5, objeto = "Link de internet", uf = "MG", situacao = "Revogada"),
        )
        start { req -> if (req.route == CONTRATACOES) serve(req, mapOf("5" to rows)) else emptyPage() }

        val screen = OpportunityScreen { it.objectDescription.contains("internet", ignoreCase = true) }
        val listing = connector.listScreened(OpportunityFilter(ufs = setOf("MG")), screen)

        assertEquals(listOf("COMPRAS_GOV:${control(1)}"), listing.opportunities.map { it.id })
        assertEquals("só as linhas vivas de MG no cache", 2, listing.diagnostics.read)
        assertEquals(1, listing.diagnostics.candidates)
        assertEquals(1, listing.diagnostics.open)
        assertTrue("UF enviada à API", listings().all { it.query("unidadeOrgaoUfSigla") == "MG" })
        assertTrue(pncpCalls().isEmpty())
    }

    // ------------------------------------------------------------ enriquecimento pelo PNCP

    @Test
    fun `candidatas sem prazo sao conferidas no PNCP - aberta, encerrada, revogada e sem resposta`() = runBlocking {
        val rows = listOf(
            row(1, objeto = "Link de internet A", enc = null), // PNCP: aberta até 20/10
            row(2, objeto = "Link de internet B", enc = null), // PNCP: encerrada
            row(3, objeto = "Link de internet C", enc = null), // PNCP: revogada
            row(4, objeto = "Link de internet D", enc = null), // PNCP: 404
            row(5, objeto = "Link de internet E", enc = null), // PNCP: 500
            row(6, objeto = "Link de internet F", enc = "2026-10-15T10:00:00"), // já tem prazo: não consulta
            row(7, objeto = "Pneus", enc = null), // não é candidata: não consulta
        )
        start { req ->
            when {
                req.route == CONTRATACOES -> serve(req, mapOf("5" to rows))
                req.route == "${PNCP}11111111000111/compras/2026/1" -> pncpStatus("2026-10-20T09:00:00")
                req.route == "${PNCP}11111111000111/compras/2026/2" -> pncpStatus("2026-09-30T09:00:00")
                req.route == "${PNCP}11111111000111/compras/2026/3" -> pncpStatus("2026-10-30T09:00:00", 2, "Revogada")
                req.route == "${PNCP}11111111000111/compras/2026/4" -> MockResponse().setResponseCode(404)
                req.route == "${PNCP}11111111000111/compras/2026/5" -> MockResponse().setResponseCode(500)
                else -> emptyPage()
            }
        }
        val screen = OpportunityScreen { it.objectDescription.contains("internet", ignoreCase = true) }

        val listing = connector.listScreened(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO), screen)

        val byId = listing.opportunities.associateBy { it.id.substringAfter("-1-").substringBefore("/").toInt() }
        assertEquals(setOf(1, 4, 5, 6), byId.keys)
        assertEquals(brasilia(2026, 10, 20, 9, 0), byId.getValue(1).proposalDeadline)
        assertFalse("sem resposta → prazo não informado", byId.getValue(4).hasProposalDeadline)
        assertFalse(byId.getValue(5).hasProposalDeadline)
        assertEquals(6, listing.diagnostics.candidates)
        assertEquals(4, listing.diagnostics.open)
        assertEquals(2, listing.diagnostics.unknownDeadline)
        val consulted = pncpCalls().map { it.route!!.substringAfterLast('/') }.toSet()
        assertEquals(setOf("1", "2", "3", "4", "5"), consulted)
        assertEquals("500 é repetido com backoff", 4, pncpCalls().count { it.route!!.endsWith("/5") })

        // Segunda execução: linhas e situações conhecidas vêm do cache; só as sem resposta são consultadas de novo.
        requests.clear()
        val again = connector.listScreened(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO), screen)
        assertTrue(listings().isEmpty())
        assertEquals(setOf("4", "5"), pncpCalls().map { it.route!!.substringAfterLast('/') }.toSet())
        assertEquals(listing.opportunities.map { it.id }.toSet(), again.opportunities.map { it.id }.toSet())
    }

    @Test
    fun `enriquecimento respeita o maximo por execucao priorizando as publicacoes recentes`() = runBlocking {
        val rows = (1..4).map { row(it, objeto = "Link de internet $it", enc = null, pub = "2026-09-0${it}T10:00:00") }
        start(fast.copy(maxEnrichPerRun = 2)) { req ->
            when {
                req.route == CONTRATACOES -> serve(req, mapOf("5" to rows))
                req.route?.startsWith(PNCP) == true -> pncpStatus("2026-10-20T09:00:00")
                else -> emptyPage()
            }
        }
        val listing = connector.listScreened(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO), OpportunityScreen.ACCEPT_ALL)
        assertEquals(setOf("3", "4"), pncpCalls().map { it.route!!.substringAfterLast('/') }.toSet())
        assertEquals(4, listing.opportunities.size)
        assertEquals(2, listing.diagnostics.unknownDeadline)
    }

    @Test
    fun `429 persistente do PNCP interrompe o enriquecimento e mantem as candidatas sem prazo`() = runBlocking {
        val rows = (1..6).map { row(it, objeto = "Link de internet $it", enc = null) }
        start { req ->
            when {
                req.route == CONTRATACOES -> serve(req, mapOf("5" to rows))
                req.route?.startsWith(PNCP) == true -> MockResponse().setResponseCode(429)
                else -> emptyPage()
            }
        }
        val listing = connector.listScreened(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO), OpportunityScreen.ACCEPT_ALL)
        assertEquals(6, listing.opportunities.size)
        assertEquals(6, listing.diagnostics.unknownDeadline)
        // no máximo as duas vias paralelas esgotam as retentativas (1 + 3 cada); as demais nem são consultadas
        assertTrue("${pncpCalls().size} consultas", pncpCalls().size <= 8)
        assertTrue(connector.lastEnrichment!!.throttled)
    }

    // ------------------------------------------------------------ respostas reais (fixtures)

    @Test
    fun `paginas reais com propostas encerradas sao descartadas e as abertas mantidas`() = runBlocking {
        val p1 = fixture("contratacoes_14133_pregao_mg_p1.json")
            .replace("\"totalRegistros\":399", "\"totalRegistros\":19")
            .replace("\"totalPaginas\":40", "\"totalPaginas\":2")
            .replace("\"paginasRestantes\":39", "\"paginasRestantes\":1")
        start { req ->
            when {
                req.route != CONTRATACOES -> emptyPage()
                req.query("pagina") == "1" -> jsonResponse(p1)
                req.query("pagina") == "2" -> jsonResponse(fixture("contratacoes_14133_pregao_mg_p40.json"))
                else -> emptyPage()
            }
        }
        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("mg")))

        assertEquals(listOf("1", "2"), listings().map { it.query("pagina") })
        assertTrue(listings().all { it.query("codigoModalidade") == "5" && it.query("unidadeOrgaoUfSigla") == "MG" && it.query("tamanhoPagina") == "500" })
        // Pregão é compatível com o legado (Lei 8.666): uma consulta complementar com modalidade=5
        val legado = requests.single { it.route == LEGADO }
        assertEquals("5", legado.query("modalidade"))
        assertEquals("2026-08-07", legado.query("data_publicacao_inicial"))
        // só os 9 da última página estão com propostas abertas em 06/10; os 10 da página 1 já encerraram
        assertEquals(9, result.size)
        assertTrue(result.all { it.portal == Portal.COMPRAS_GOV && it.id.startsWith("COMPRAS_GOV:") && it.uf == "MG" })
        assertTrue(result.all { it.proposalDeadline >= now })
        assertEquals(result.sortedBy { it.proposalDeadline }, result)
        assertTrue(pncpCalls().isEmpty())

        // filtros sem suporte na API (texto e valor) são aplicados localmente
        val byText = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG"), query = "backup imutável"))
        assertEquals(listOf("COMPRAS_GOV:23664303000104-1-000045/2026"), byText.map { it.id })
        val byValue = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG"), minValue = 1_000_000.0))
        assertEquals(listOf("COMPRAS_GOV:00399857000126-1-000334/2026"), byValue.map { it.id })
    }

    @Test
    fun `sem resultados consulta Pregao, Dispensa e Concorrencia uma vez cada`() = runBlocking {
        start { emptyPage() }
        assertTrue(connector.listOpportunities(OpportunityFilter()).isEmpty())
        assertEquals(listOf("5", "6", "3"), requests.map { it.query("codigoModalidade") })
        assertTrue(requests.all { it.route == CONTRATACOES && it.query("pagina") == "1" })
    }

    @Test
    fun `204 sem corpo e tratado como vazio`() = runBlocking {
        start { MockResponse().setResponseCode(204) }
        assertTrue(connector.listOpportunities(OpportunityFilter(modality = Modality.CONCORRENCIA, ufs = setOf("SP"))).isEmpty())
        assertEquals("3", listings().first().query("codigoModalidade"))
        assertEquals("SP", listings().first().query("unidadeOrgaoUfSigla"))
    }

    @Test
    fun `credenciamento nao tem codigo nesta API e nao gera requisicao`() = runBlocking {
        start { fail("não deveria chamar a rede"); emptyPage() }
        assertTrue(connector.listOpportunities(OpportunityFilter(modality = Modality.CREDENCIAMENTO)).isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `mais de tres UFs nao sao enviadas a API e o filtro e aplicado localmente`() = runBlocking {
        val rows = listOf(row(1, uf = "MG"), row(2, uf = "BA"))
        start { req -> if (req.route == CONTRATACOES) serve(req, mapOf("5" to rows)) else emptyPage() }
        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG", "SP", "RJ", "ES")))
        assertTrue(listings().all { it.query("unidadeOrgaoUfSigla") == null })
        assertEquals(listOf("COMPRAS_GOV:${control(1)}"), result.map { it.id })
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
        start(clock = { brasilia(2023, 11, 10, 12, 0) }) { req ->
            when (req.route) {
                LEGADO -> jsonResponse(fixture("legado_licitacao_pregao_2023-11_p1.json"))
                else -> emptyPage()
            }
        }
        val result = connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO))

        val legado = requests.single { it.route == LEGADO }
        assertEquals("2023-09-11", legado.query("data_publicacao_inicial"))
        assertEquals("2023-11-10", legado.query("data_publicacao_final"))
        assertEquals("5", legado.query("modalidade"))
        assertEquals("50", legado.query("tamanhoPagina"))

        assertEquals(6, result.size) // 10 registros − 4 pertence14133 (duplicatas do módulo 14.133)
        assertTrue(result.all { it.modality == Modality.PREGAO_ELETRONICO && it.uf.isEmpty() && it.agency.startsWith("UASG ") })
        assertTrue(result.any { it.id == "COMPRAS_GOV:152005-05-00027/2023" })
    }

    @Test
    fun `falha no legado nao derruba a busca principal`() = runBlocking {
        start { req ->
            when (req.route) {
                LEGADO -> MockResponse().setResponseCode(500)
                CONTRATACOES -> serve(req, mapOf("5" to (1..3).map { row(it) }))
                else -> emptyPage()
            }
        }
        assertEquals(3, connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO, ufs = setOf("MG"))).size)
    }

    @Test
    fun `erro HTTP persistente vira ComprasGovException com mensagem em portugues`() = runBlocking {
        start { MockResponse().setResponseCode(503).setBody("indisponível") }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.PREGAO_ELETRONICO))
            fail("deveria falhar")
        } catch (e: ComprasGovException) {
            assertEquals(ComprasGovException.Kind.HTTP, e.kind)
            assertEquals(503, e.httpStatus)
            assertTrue(e.message!!, e.message!!.contains("indisponível"))
        }
        assertEquals("1 + 3 retentativas", 4, listings().size)
    }

    @Test
    fun `erro de validacao 400 (problem json real) expoe o detalhe da API sem retentar`() = runBlocking {
        val problem = """{"type":"about:blank","title":"Erro de Validação","status":400,"detail":"tamanhoPagina: O tamanho da página deve ser no mínimo 10","instance":"$CONTRATACOES","timestamp":"2026-10-06T06:25:24.081646532Z"}"""
        start { jsonResponse(problem, 400) }
        try {
            connector.listOpportunities(OpportunityFilter(modality = Modality.DISPENSA_ELETRONICA))
            fail("deveria falhar")
        } catch (e: ComprasGovException) {
            assertEquals(400, e.httpStatus)
            assertTrue(e.message!!, e.message!!.contains("O tamanho da página deve ser no mínimo 10"))
        }
        assertEquals(1, requests.size)
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
        val offline = ComprasGovConnector(OkHttpClient(), ComprasGovConnector.defaultJson(), url, clock = { now }, tuning = fast)
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
