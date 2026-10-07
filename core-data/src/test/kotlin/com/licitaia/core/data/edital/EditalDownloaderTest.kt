package com.licitaia.core.data.edital

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Download do edital oficial contra MockWebServer: PDF, ZIP, HTML, limite de tamanho e hosts permitidos. */
class EditalDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var target: File

    /** Política de teste: MockWebServer é http://localhost (a política oficial exige HTTPS + hosts do governo). */
    private val localPolicy = EditalDownloadPolicy(requireHttps = false, hostAllowed = { true })

    private val pdfBytes = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        target = File(tmp.root, "editais/7/42.pdf")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader(maxBytes: Long = 50L * 1024 * 1024) = EditalDownloader(OkHttpClient(), localPolicy, maxBytes)

    private fun body(bytes: ByteArray) = Buffer().write(bytes)

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun expectFailure(url: String, d: EditalDownloader = downloader()): EditalDownloadException = try {
        d.download(url, target)
        fail("esperava EditalDownloadException")
        throw AssertionError()
    } catch (e: EditalDownloadException) {
        e
    }

    @Test
    fun `PDF com content-type octet-stream e detectado pela assinatura`() = runBlocking {
        // O PNCP real responde application/octet-stream + content-disposition.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Disposition", "attachment; filename=\"edital.pdf\"").setBody(body(pdfBytes)),
        )
        val file = downloader().download(server.url("/pncp-api/v1/orgaos/1/compras/2026/1/arquivos/1").toString(), target)
        assertEquals(target, file)
        assertArrayEquals(pdfBytes, target.readBytes())
        assertFalse(File(target.parentFile, "42.pdf.download").exists())
    }

    @Test
    fun `ZIP extrai o primeiro PDF`() = runBlocking {
        val zip = zipOf("leia-me.txt" to "x".toByteArray(), "docs/edital.pdf" to pdfBytes, "anexo.pdf" to "%PDF-1.7 outro".toByteArray())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(body(zip)))
        downloader().download(server.url("/arquivo").toString(), target)
        assertArrayEquals(pdfBytes, target.readBytes())
    }

    @Test
    fun `ZIP sem PDF falha`() = runBlocking {
        server.enqueue(MockResponse().setBody(body(zipOf("planilha.xlsx" to byteArrayOf(1, 2, 3)))))
        val e = expectFailure(server.url("/arquivo").toString())
        assertTrue(e.message!!.contains("não contém nenhum PDF"))
        assertFalse(target.exists())
    }

    @Test
    fun `HTML falha com mensagem clara`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<!DOCTYPE html><html><body>Login</body></html>"))
        val e = expectFailure(server.url("/arquivo").toString())
        assertTrue(e.message!!, e.message!!.contains("o portal não forneceu o PDF diretamente", ignoreCase = true))
        assertFalse(target.exists())
    }

    @Test
    fun `arquivo acima do limite e recusado pelo Content-Length e pelo fluxo`() = runBlocking {
        val big = ByteArray(4096) { 'a'.code.toByte() }.also { pdfBytes.copyInto(it) }
        server.enqueue(MockResponse().setBody(body(big))) // Content-Length declarado = 4096
        val declared = expectFailure(server.url("/a").toString(), downloader(maxBytes = 1024))
        assertTrue(declared.message!!.contains("excede"))

        server.enqueue(MockResponse().setChunkedBody(body(big), 512)) // sem Content-Length
        val streamed = expectFailure(server.url("/b").toString(), downloader(maxBytes = 1024))
        assertTrue(streamed.message!!.contains("excede"))
        assertFalse(target.exists())
    }

    @Test
    fun `HTTP de erro vira mensagem amigavel`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = expectFailure(server.url("/x").toString())
        assertTrue(e.message!!.contains("HTTP 404"))
    }

    @Test
    fun `host nao permitido e recusado sem acessar a rede`() = runBlocking {
        val official = EditalDownloader(OkHttpClient(), EditalDownloadPolicy.OFFICIAL, 1024)
        val e = expectFailure("https://evil.example.com/edital.pdf", official)
        assertTrue(e.message!!.contains("não é um portal oficial"))
        val http = expectFailure("http://pncp.gov.br/pncp-api/v1/orgaos/1/compras/2026/1/arquivos/1", official)
        assertTrue(http.message!!.contains("HTTPS"))
        // O servidor local (localhost) também não é host oficial.
        val local = expectFailure(server.url("/x").toString().replace("http://", "https://"), official)
        assertTrue(local.message!!.contains("não é um portal oficial"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redirecionamento para host nao permitido e recusado`() = runBlocking {
        val policy = EditalDownloadPolicy(requireHttps = false, hostAllowed = { it != "evil.example.com" })
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example.com/edital.pdf"))
        val d = EditalDownloader(OkHttpClient(), policy, 1024)
        val e = expectFailure(server.url("/r").toString(), d)
        assertTrue(e.message!!.contains("não é um portal oficial"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `hosts oficiais permitidos`() {
        listOf("pncp.gov.br", "treina.pncp.gov.br", "dadosabertos.compras.gov.br", "www.comprasnet.gov.br", "cnetmobile.estaleiro.serpro.gov.br")
            .forEach { assertTrue(it, EditalDownloadPolicy.isOfficialHost(it)) }
        listOf("pncp.gov.br.evil.com", "evilpncp.gov.br", "compras.gov.br", "comprasnet.gov.br")
            .forEach { assertFalse(it, EditalDownloadPolicy.isOfficialHost(it)) }
    }
}
