package com.licitaia.core.ai

import com.licitaia.ai.api.AiProviderException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.Reader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Responses API (`POST /v1/responses`) no modo "Entrar com ChatGPT". A doc exige `store: false`,
 * `stream: true`, `input` como lista e instruções em `instructions` (não mensagem `system`); a resposta só vale
 * depois de `response.completed` (https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference).
 */
internal object ChatGptResponses {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val LABEL = "ChatGPT"

    fun requestBody(model: String, instructions: String, user: String): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", instructions)
        putJsonArray("input") {
            add(buildJsonObject { put("role", "user"); put("content", user) })
        }
        put("store", false)
        put("stream", true)
    }

    /** Envia e devolve o texto final. Erros viram [AiProviderException] com [HttpStatusCause] (401 → renovar). */
    suspend fun call(http: OkHttpClient, url: String, accessToken: String, body: JsonObject, json: Json): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(networkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching { response.use { read(it, json) } }
                        .recoverCatching { if (it is IOException) throw networkError(it) else throw it }
                    if (continuation.isCancelled) return
                    outcome.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }

    private fun read(response: Response, json: Json): String {
        val body = response.body ?: throw AiProviderException("$LABEL não devolveu conteúdo na resposta.")
        if (!response.isSuccessful) {
            val parsed = runCatching { json.parseToJsonElement(body.string()) as? JsonObject }.getOrNull()
            throw apiError(response.code, parsed)
        }
        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim()
        if (type.equals("application/json", ignoreCase = true)) {
            // Alguns servidores respondem sem fluxo mesmo pedindo stream.
            val obj = json.parseToJsonElement(body.string()) as? JsonObject
                ?: throw AiProviderException("$LABEL devolveu uma resposta em formato inesperado.")
            return textFromResponse(obj).ifBlank { throw AiProviderException("$LABEL não devolveu conteúdo na resposta.") }
        }
        return parseStream(body.charStream(), json)
    }

    /** Lê os eventos SSE e junta o texto; só aceita o resultado após `response.completed`. */
    fun parseStream(reader: Reader, json: Json): String {
        val text = StringBuilder()
        var completedText: String? = null
        var completed = false
        val data = StringBuilder()

        fun dispatch() {
            if (data.isEmpty()) return
            val payload = data.toString()
            data.setLength(0)
            if (payload == "[DONE]") return
            val event = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
            when (event.str("type")) {
                "response.output_text.delta" -> (event["delta"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { text.append(it) }
                "response.completed" -> {
                    completed = true
                    completedText = event.obj("response")?.let { textFromResponse(it) }?.takeIf { it.isNotBlank() }
                }
                "response.failed", "error" -> {
                    val err = event.obj("response")?.obj("error") ?: event.obj("error") ?: event
                    throw apiError(null, buildJsonObject { put("error", err) })
                }
                "response.incomplete" -> throw AiProviderException(
                    "A resposta do $LABEL veio pela metade (limite do modelo). Tente um pedido menor.",
                )
            }
        }

        reader.buffered().useLines { lines ->
            for (raw in lines) {
                val line = raw.removeSuffix("\r")
                when {
                    line.isEmpty() -> { dispatch(); if (completed) break }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
        }
        if (!completed) dispatch()
        if (!completed) throw AiProviderException("A conexão com o $LABEL caiu antes da resposta terminar. Tente de novo.")
        val result = text.toString().ifBlank { completedText.orEmpty() }
        if (result.isBlank()) throw AiProviderException("$LABEL não devolveu conteúdo na resposta.")
        return result
    }

    /** Porta de `textoDaResposta`: `output_text` ou `output[].content[]` do tipo `output_text`. */
    fun textFromResponse(d: JsonObject): String {
        d.str("output_text")?.let { return it }
        return d.arr("output").orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "message" }
            .flatMap { it.arr("content").orEmpty().mapNotNull { c -> c as? JsonObject } }
            .filter { it.str("type") == "output_text" }
            .joinToString("") { (it["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
    }

    /**
     * Erros documentados em "Errors and recovery" → mensagens em pt-BR com sugestão de chave de API.
     * [status] null = erro dentro do fluxo (`response.failed`).
     */
    fun apiError(status: Int?, body: JsonObject?): AiProviderException {
        val error = body?.obj("error")
        val code = error?.str("code") ?: error?.str("type")
        val param = error?.str("param")
        val useKey = " Se preferir, use uma chave de API em Configurações > IA."
        val message = when (code) {
            "subscription_sharing_user_not_eligible" ->
                "O plano do ChatGPT desta conta (ou do workspace) não pode ser usado pelo LicitaPRO.$useKey"
            "subscription_sharing_usage_limit_exceeded" ->
                "O limite de uso do seu plano do ChatGPT foi atingido por enquanto. Veja em chatgpt.com/settings/usage e tente mais tarde.$useKey"
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
                "O ChatGPT não conseguiu verificar seu uso agora. Tente de novo em instantes."
            "subscription_sharing_unsupported_capability" ->
                "Este pedido não é aceito pelo plano do ChatGPT" + (param?.let { " (parâmetro: ${it.take(60)})" } ?: "") +
                    ". Escolha outro modelo em Configurações > IA.$useKey"
            "subscription_sharing_route_not_supported" ->
                "O ChatGPT não aceita esta rota da API no uso pelo plano.$useKey"
            "subscription_sharing_invalid_user" ->
                "O ChatGPT não aceitou sua sessão. Toque em \"Entrar com ChatGPT\" de novo em Configurações > IA."
            "chatpass_v2_scope_not_authorized" ->
                "O ChatGPT não liberou o uso do plano para o LicitaPRO. Ative o compartilhamento de uso nas configurações do ChatGPT e entre de novo.$useKey"
            "model_not_found" -> "O modelo escolhido não está disponível na sua conta do ChatGPT. Troque o modelo em Configurações > IA."
            else -> when (status) {
                401 -> "O ChatGPT não aceitou a sessão ou a permissão de uso do plano. Entre de novo com o ChatGPT.$useKey"
                403 -> "O ChatGPT recusou o pedido (política, região ou permissão da conta).$useKey"
                404 -> "O modelo escolhido não está disponível na sua conta do ChatGPT. Troque o modelo em Configurações > IA."
                429 -> "O ChatGPT pediu para esperar (muitos pedidos ou limite do plano). Tente de novo em instantes.$useKey"
                400, 422 -> "O ChatGPT rejeitou o pedido (HTTP $status)" + (error?.str("message")?.take(160)?.let { ": $it" } ?: ".")
                null -> "O ChatGPT não concluiu a resposta" + (code?.let { " ($it)" } ?: "") + ". Tente de novo."
                in 500..599 -> "O ChatGPT está indisponível no momento (HTTP $status). Tente de novo em instantes."
                else -> "O ChatGPT respondeu com erro HTTP $status."
            }
        }
        val effectiveStatus = status ?: when (code) {
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> 503
            "subscription_sharing_usage_limit_exceeded" -> 429
            else -> 0
        }
        return AiProviderException(message, HttpStatusCause(effectiveStatus))
    }

    private fun networkError(e: IOException): AiProviderException = AiProviderException(
        when (e) {
            is UnknownHostException -> "Sem conexão com o $LABEL. Verifique a internet."
            is SocketTimeoutException -> "O $LABEL demorou demais para responder. Tente novamente."
            else -> "Não foi possível conectar ao $LABEL. Verifique a internet e tente novamente."
        },
        e,
    )
}
