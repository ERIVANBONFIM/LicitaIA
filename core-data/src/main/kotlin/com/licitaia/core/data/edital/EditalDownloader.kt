package com.licitaia.core.data.edital

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Falha no download do edital oficial, com mensagem amigável em pt-BR. */
class EditalDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Política de URLs aceitas para download do edital. Padrão ([OFFICIAL]): somente HTTPS e somente hosts oficiais
 * (pncp.gov.br e subdomínios, dadosabertos.compras.gov.br, www.comprasnet.gov.br, cnetmobile.estaleiro.serpro.gov.br).
 * Vale também para cada redirecionamento.
 */
class EditalDownloadPolicy(
    val requireHttps: Boolean = true,
    private val hostAllowed: (String) -> Boolean = ::isOfficialHost,
) {
    fun check(url: HttpUrl) {
        if (requireHttps && !url.isHttps) throw EditalDownloadException("Download recusado: o endereço do edital não usa HTTPS.")
        if (!hostAllowed(url.host.lowercase(Locale.ROOT))) {
            throw EditalDownloadException("Download recusado: ${url.host} não é um portal oficial permitido (PNCP/Compras.gov.br).")
        }
    }

    companion object {
        val EXACT_HOSTS = setOf("dadosabertos.compras.gov.br", "www.comprasnet.gov.br", "cnetmobile.estaleiro.serpro.gov.br")

        fun isOfficialHost(host: String): Boolean =
            host == "pncp.gov.br" || host.endsWith(".pncp.gov.br") || host in EXACT_HOSTS

        val OFFICIAL = EditalDownloadPolicy()
    }
}

/**
 * Baixa o PDF do edital publicado em portal oficial. Regras: HTTPS + hosts permitidos (também nos redirecionamentos,
 * seguidos manualmente), limite de [maxBytes] (padrão 50 MB, mesmo da importação manual), tipo detectado pela
 * assinatura (`%PDF`) além do Content-Type — o PNCP responde `application/octet-stream`. ZIP: extrai o primeiro PDF.
 * HTML: falha clara ("o portal não forneceu o PDF diretamente"). Lógica sem Android: testável com MockWebServer.
 */
@Singleton
class EditalDownloader internal constructor(
    client: OkHttpClient,
    private val policy: EditalDownloadPolicy,
    private val maxBytes: Long,
) {
    @Inject
    constructor(client: OkHttpClient) : this(client, EditalDownloadPolicy.OFFICIAL, EditalStore.MAX_PDF_BYTES)

    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    /** Baixa [url] e grava o PDF em [target] (substituindo). Devolve [target]. */
    suspend fun download(url: String, target: File): File = withContext(Dispatchers.IO) {
        var current = url.trim().toHttpUrlOrNull() ?: throw EditalDownloadException("Endereço do edital inválido.")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".download")
        try {
            var hops = 0
            while (true) {
                policy.check(current)
                val response = execute(current)
                val next = response.use { r ->
                    if (r.isRedirect) {
                        val location = r.header("Location")?.let { current.resolve(it) }
                            ?: throw EditalDownloadException("O portal redirecionou o download sem informar o destino.")
                        return@use location
                    }
                    if (!r.isSuccessful) throw EditalDownloadException(httpMessage(r.code))
                    val declared = r.body?.contentLength() ?: -1L
                    if (declared > maxBytes) throw tooLarge()
                    val contentType = r.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                    val body = r.body ?: throw EditalDownloadException("O portal devolveu uma resposta vazia.")
                    body.byteStream().use { copyLimited(it, temp) }
                    finish(temp, target, contentType)
                    null
                }
                if (next == null) break
                if (++hops > MAX_REDIRECTS) throw EditalDownloadException("O portal redirecionou o download vezes demais.")
                current = next
            }
            target
        } finally {
            temp.delete()
        }
    }

    private suspend fun execute(url: HttpUrl): Response {
        val request = Request.Builder().url(url)
            .header("Accept", "application/pdf, application/zip, application/octet-stream;q=0.9, */*;q=0.5")
            .header("User-Agent", USER_AGENT)
            .get().build()
        return try {
            client.newCall(request).await()
        } catch (e: EditalDownloadException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw EditalDownloadException("O portal demorou demais para enviar o edital. Tente novamente.", e)
        } catch (e: UnknownHostException) {
            throw EditalDownloadException("Sem conexão com a internet para baixar o edital.", e)
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            throw EditalDownloadException(
                when {
                    msg.contains("timeout", ignoreCase = true) -> "O portal demorou demais para enviar o edital. Tente novamente."
                    msg.contains("internet", ignoreCase = true) -> msg
                    else -> "Não foi possível conectar ao portal para baixar o edital."
                },
                e,
            )
        }
    }

    private suspend fun copyLimited(input: InputStream, out: File) {
        out.outputStream().use { sink ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw tooLarge()
                sink.write(buffer, 0, read)
            }
            if (total == 0L) throw EditalDownloadException("O portal devolveu um arquivo vazio.")
        }
    }

    /** Classifica o arquivo baixado pela assinatura e move/extrai o PDF para [target]. */
    private suspend fun finish(temp: File, target: File, contentType: String) {
        val head = readHead(temp)
        when {
            isPdf(head) -> moveInto(temp, target)
            isZip(head) -> extractFirstPdf(temp, target)
            isHtml(head) || contentType.contains("html") ->
                throw EditalDownloadException("O portal não forneceu o PDF diretamente (devolveu uma página HTML). Baixe o edital pelo navegador e use \"Importar PDF\".")
            else -> throw EditalDownloadException("O arquivo do edital não é um PDF (tipo: ${contentType.ifBlank { "desconhecido" }}).")
        }
    }

    private suspend fun extractFirstPdf(zip: File, target: File) {
        val extracted = File(target.parentFile, target.name + ".unzip")
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = try {
                        zin.nextEntry
                    } catch (e: ZipException) {
                        throw EditalDownloadException("O arquivo ZIP do edital está corrompido.", e)
                    } ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                    if (!name.endsWith(".pdf") && name.contains('.')) continue
                    copyLimited(zin, extracted)
                    if (isPdf(readHead(extracted))) {
                        moveInto(extracted, target)
                        return
                    }
                }
            }
            throw EditalDownloadException("O ZIP do edital não contém nenhum PDF.")
        } finally {
            extracted.delete()
        }
    }

    private fun moveInto(source: File, target: File) {
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun tooLarge() = EditalDownloadException("O edital excede ${maxBytes / (1024 * 1024)} MB e não foi baixado.")

    private fun httpMessage(code: Int): String = when (code) {
        404, 410 -> "O arquivo do edital não está mais disponível no portal (HTTP $code)."
        401, 403 -> "O portal negou o acesso ao arquivo do edital (HTTP $code)."
        429 -> "O portal limitou os downloads. Aguarde alguns minutos e tente novamente."
        in 500..599 -> "O portal está indisponível no momento (HTTP $code). Tente novamente mais tarde."
        else -> "Falha ao baixar o edital (HTTP $code)."
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "LicitaIA-Android (download de edital publico)"

        internal fun readHead(file: File): ByteArray = file.inputStream().use { input ->
            val buffer = ByteArray(1024)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }

        internal fun isPdf(head: ByteArray): Boolean = String(head, Charsets.ISO_8859_1).contains("%PDF-")

        internal fun isZip(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

        internal fun isHtml(head: ByteArray): Boolean {
            val text = String(head, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t').lowercase(Locale.ROOT)
            return text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head") ||
                text.startsWith("<body") || (text.startsWith("<") && text.contains("<html"))
        }
    }
}
