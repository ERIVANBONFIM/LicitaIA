package com.licitaia.connector.pncp

import com.licitaia.connector.api.OfficialDocument
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escolha do edital oficial na lista `/arquivos` do PNCP (resposta real capturada em 06/10/2026). */
class PncpEditalSelectorTest {

    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        server?.shutdown()
    }

    private fun doc(
        seq: Int, titulo: String, tipoId: Long?, tipoNome: String?, data: String = "2026-09-24T09:49:48", ativo: Boolean = true,
    ) = PncpDocumento(
        url = "https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/$seq",
        sequencialDocumento = seq, statusAtivo = ativo, dataPublicacaoPncp = data, titulo = titulo,
        tipoDocumentoId = tipoId, tipoDocumentoNome = tipoNome,
    )

    @Test
    fun `resposta real - escolhe o Edital mais recente com titulo edital e ignora outros documentos`() = runBlocking {
        val mock = MockWebServer().also { server = it }
        mock.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(REAL_ARQUIVOS))
        mock.start()
        val connector = PncpConnector(OkHttpClient(), PncpConnector.defaultJson(), mock.url("/"), pageDelayMs = 0, retryDelaysMs = listOf(0L))

        val docs = connector.officialEditalDocuments("45132495000140-1-000942/2024")

        val request = mock.takeRequest()
        assertEquals("/api/pncp/v1/orgaos/45132495000140/compras/2024/942/arquivos", request.requestUrl!!.encodedPath)
        // Todos os documentos ativos entram na base de perguntas; a outra versão do "Edital" (republicação) fica de fora.
        assertEquals(3, docs.size)
        assertEquals(listOf("lucindakuhl_orc_dc2_cro_053_23_r04.pdf", "lucindakuhl_orc_dci_po_053_23_r04.pdf"), docs.drop(1).map { it.title })
        val main = docs.first()
        assertEquals(OfficialDocument.Role.EDITAL, main.role)
        assertEquals("edital.200-24-pre.180-24-seguranca.incendio.pdf", main.title)
        assertEquals("https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/2", main.url)
        assertEquals("Edital", main.typeName)
    }

    @Test
    fun `tipo Edital vence titulo e os demais documentos vem por prioridade com TR primeiro`() {
        val docs = PncpEditalSelector.select(
            listOf(
                doc(1, "aviso.pdf", 16, "Outros Documentos"),
                doc(2, "\"Anexo I - Planilha.pdf\"", 16, "Outros Documentos"),
                doc(3, "documento_convocatorio.pdf", 2, "Edital"),
                doc(4, "tr.pdf", 4, "Termo de Referência"),
                doc(5, "edital antigo.pdf", 2, "Edital", ativo = false),
            ),
        )
        assertEquals(listOf("documento_convocatorio.pdf", "tr.pdf", "Anexo I - Planilha.pdf", "aviso.pdf"), docs.map { it.title })
        assertEquals(4L, docs[1].typeId)
        assertEquals(OfficialDocument.Role.EDITAL, docs[0].role)
        assertTrue(docs.drop(1).all { it.role == OfficialDocument.Role.ANEXO })
    }

    @Test
    fun `sem tipo Edital prefere titulo com edital e depois o primeiro PDF`() {
        val byTitle = PncpEditalSelector.select(
            listOf(doc(1, "planilha.xlsx", 16, "Outros"), doc(2, "Edital_Pregao_12.pdf", 16, "Outros")),
        )
        assertEquals("Edital_Pregao_12.pdf", byTitle.first().title)

        val firstPdf = PncpEditalSelector.select(
            listOf(doc(1, "planilha.xlsx", 16, "Outros"), doc(2, "aviso.pdf", 1, "Aviso de Contratação Direta")),
        )
        assertEquals("aviso.pdf", firstPdf.first().title)
    }

    @Test
    fun `lista vazia ou sem URL https nao produz documentos`() {
        assertTrue(PncpEditalSelector.select(emptyList()).isEmpty())
        assertTrue(PncpEditalSelector.select(listOf(doc(1, "e.pdf", 2, "Edital").copy(url = "http://x/1", uri = null))).isEmpty())
    }

    @Test
    fun `sem tipo Edital nunca escolhe o ETP como principal e o ETP vem depois do TR`() {
        val docs = PncpEditalSelector.select(
            listOf(
                doc(1, "ETP - Estudo Tecnico Preliminar.pdf", 7, "Estudo Técnico Preliminar"),
                doc(2, "Termo de Referencia.pdf", 16, "Outros Documentos"),
                doc(3, "Aviso de dispensa.pdf", 16, "Outros Documentos"),
            ),
        )
        assertEquals("Aviso de dispensa.pdf", docs.first().title)
        assertEquals(listOf("Termo de Referencia.pdf", "ETP - Estudo Tecnico Preliminar.pdf"), docs.drop(1).map { it.title })

        val onlyEtpAndPlanilha = PncpEditalSelector.select(
            listOf(doc(1, "etp.pdf", 7, "Estudo Técnico Preliminar"), doc(2, "planilha.pdf", 16, "Outros Documentos")),
        )
        assertEquals("planilha.pdf", onlyEtpAndPlanilha.first().title)
    }

    @Test
    fun `limita a quantidade de anexos`() {
        val docs = PncpEditalSelector.select(
            listOf(doc(1, "edital.pdf", 2, "Edital")) + (2..20).map { doc(it, "anexo $it.pdf", 16, "Outros Documentos") },
        )
        assertEquals(1 + PncpEditalSelector.MAX_ANNEXES, docs.size)
    }

    private companion object {
        /** Trecho real de GET /api/pncp/v1/orgaos/45132495000140/compras/2024/942/arquivos (06/10/2026). */
        val REAL_ARQUIVOS = """
            [{"uri":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/1","url":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/1","tipoDocumentoId":2,"titulo":"\"pre180-24instalacao.pdf\"","statusAtivo":true,"dataPublicacaoPncp":"2024-11-05T09:02:18","cnpj":"45132495000140","anoCompra":2024,"sequencialCompra":942,"sequencialDocumento":1,"tipoDocumentoNome":"Edital","tipoDocumentoDescricao":"Edital"},
             {"uri":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/2","url":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/2","tipoDocumentoId":2,"titulo":"edital.200-24-pre.180-24-seguranca.incendio.pdf","statusAtivo":true,"dataPublicacaoPncp":"2026-09-24T09:49:48","cnpj":"45132495000140","anoCompra":2024,"sequencialCompra":942,"sequencialDocumento":2,"tipoDocumentoNome":"Edital","tipoDocumentoDescricao":"Edital"},
             {"uri":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/3","url":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/3","tipoDocumentoId":16,"titulo":"lucindakuhl_orc_dc2_cro_053_23_r04.pdf","statusAtivo":true,"dataPublicacaoPncp":"2026-09-24T09:49:54","cnpj":"45132495000140","anoCompra":2024,"sequencialCompra":942,"sequencialDocumento":3,"tipoDocumentoNome":"Outros Documentos","tipoDocumentoDescricao":"Outros Documentos"},
             {"uri":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/9","url":"https://pncp.gov.br/pncp-api/v1/orgaos/45132495000140/compras/2024/942/arquivos/9","tipoDocumentoId":16,"titulo":"lucindakuhl_orc_dci_po_053_23_r04.pdf","statusAtivo":true,"dataPublicacaoPncp":"2026-09-24T09:50:09","cnpj":"45132495000140","anoCompra":2024,"sequencialCompra":942,"sequencialDocumento":9,"tipoDocumentoNome":"Outros Documentos","tipoDocumentoDescricao":"Outros Documentos"}]
        """.trimIndent()
    }
}
