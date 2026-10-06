package com.licitaia.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Servidor HTTP mínimo de retorno do OAuth em `http://127.0.0.1:<porta livre>/auth/callback` (só loopback
 * IPv4, porta escolhida pelo sistema). Responde apenas a `GET /auth/callback` com `state` correto; o resto
 * recebe 404/400 e o servidor continua esperando. Toda resposta leva `Cache-Control: no-store`.
 * Nada do pedido (código, state) é logado.
 */
internal class LoopbackCallbackServer(private val returnLink: String? = null) : Closeable {
    private val socket = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), BACKLOG)
    }

    val port: Int = socket.localPort
    val redirectUri: String = "http://127.0.0.1:$port$CALLBACK_PATH"

    @Volatile private var closed = false

    /** Espera o retorno com o [expectedState]. Cancelar a corrotina (ou [close]) fecha o socket e encerra a espera. */
    suspend fun awaitCallback(expectedState: String): ChatGptCallback = coroutineScope {
        val watcher = launch {
            try { awaitCancellation() } finally { close() }
        }
        try {
            withContext(Dispatchers.IO) { acceptLoop(expectedState) }
        } finally {
            watcher.cancel()
        }
    }

    private fun acceptLoop(expectedState: String): ChatGptCallback {
        while (true) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                throw ChatGptAuthException("Entrada cancelada.", code = "cancelled", cause = e)
            }
            val result = try {
                connection.use { handle(it, expectedState) }
            } catch (e: IOException) {
                null // conexão quebrada/lenta: ignora e continua esperando
            }
            if (result != null) return result
        }
    }

    private fun handle(connection: Socket, expectedState: String): ChatGptCallback? {
        connection.soTimeout = READ_TIMEOUT_MS
        val requestLine = readRequestHead(connection.getInputStream())
        val parts = requestLine?.split(' ')
        if (parts == null || parts.size < 2 || parts[0] != "GET") {
            respond(connection, 400, "Bad Request", page("Pedido inválido", "Volte ao LicitaIA e tente de novo."))
            return null
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        if (path != CALLBACK_PATH) {
            respond(connection, 404, "Not Found", page("Nada aqui", "Esta porta só recebe o retorno da entrada no ChatGPT."))
            return null
        }
        val query = parseQuery(target.substringAfter('?', ""))
        val state = query["state"]
        if (state == null || !ChatGptOAuthClient.constantTimeEquals(state, expectedState)) {
            respond(connection, 400, "Bad Request", page("Pedido de entrada não confere", "Volte ao LicitaIA e tente entrar de novo."))
            return null
        }
        val error = query["error"]
        if (error != null) {
            respond(connection, 200, "OK", page("Entrada não concluída", "Pode voltar ao LicitaIA para ver o motivo. Esta aba pode ser fechada."))
        } else {
            respond(
                connection, 200, "OK",
                page("Pronto, pode voltar ao LicitaIA", "O LicitaIA está terminando de conectar sua conta do ChatGPT. Esta aba pode ser fechada."),
            )
        }
        return ChatGptCallback(
            code = query["code"],
            state = state,
            clientId = query["client_id"],
            scope = query["scope"],
            error = error,
        )
    }

    /** Lê a linha de pedido e descarta os cabeçalhos (até [MAX_HEAD] bytes). */
    private fun readRequestHead(input: InputStream): String? {
        val head = StringBuilder()
        var total = 0
        while (total < MAX_HEAD) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
            total++
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
        }
        return head.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            runCatching { URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8") }.getOrNull()
        }.toMap()

    private fun respond(connection: Socket, code: Int, reason: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Pragma: no-cache\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        connection.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun page(title: String, text: String): String {
        val link = returnLink?.let { "<p><a class=\"b\" href=\"${escape(it)}\">Voltar ao LicitaIA</a></p>" }.orEmpty()
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escape(title)}</title>" +
            "<style>body{font:17px system-ui,sans-serif;max-width:32rem;margin:12vh auto;padding:24px;color:#1b2333}" +
            "h1{font-size:22px}.b{display:inline-block;margin-top:12px;padding:12px 18px;background:#1d4ed8;color:#fff;" +
            "border-radius:8px;text-decoration:none}</style></head><body><h1>${escape(title)}</h1><p>${escape(text)}</p>$link</body></html>"
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        const val CALLBACK_PATH = "/auth/callback"
        private const val BACKLOG = 8
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 8_192
    }
}
