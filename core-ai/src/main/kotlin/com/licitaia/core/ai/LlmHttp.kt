package com.licitaia.core.ai

import com.licitaia.ai.api.AiProviderException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * POST JSON assíncrono (não bloqueia a thread chamadora) com tradução de erros para pt-BR.
 * As mensagens de erro nunca incluem cabeçalhos nem a chave de API.
 */
internal suspend fun OkHttpClient.postJson(
    providerLabel: String,
    url: String,
    headers: Map<String, String>,
    body: JsonObject,
    json: Json,
): JsonObject {
    val request = try {
        Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    } catch (e: IllegalArgumentException) {
        throw AiProviderException("URL ou credencial inválida para $providerLabel. Revise as configurações de IA.")
    }

    val (code, payload) = suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(networkError(providerLabel, e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use { it.body?.string().orEmpty() }
                    continuation.resume(response.code to text)
                } catch (e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(networkError(providerLabel, e))
                }
            }
        })
    }

    val parsed = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
    if (code !in 200..299) throw httpError(providerLabel, code, parsed)
    return parsed ?: throw AiProviderException("$providerLabel devolveu uma resposta em formato inesperado.")
}

private fun networkError(providerLabel: String, e: IOException): AiProviderException {
    val message = when (e) {
        is UnknownHostException -> "Sem conexão com $providerLabel. Verifique a internet e a URL configurada."
        is SocketTimeoutException -> "$providerLabel demorou demais para responder. Tente novamente."
        is SSLException -> "Falha na conexão segura (HTTPS) com $providerLabel."
        else -> "Não foi possível conectar a $providerLabel. Verifique a internet e tente novamente."
    }
    return AiProviderException(message, e)
}

/** Causa anexada ao [AiProviderException] para quem precisa reagir ao status HTTP (ex.: 401 em OAuth). Sem corpo nem cabeçalhos. */
internal class HttpStatusCause(val code: Int) : RuntimeException("HTTP $code")

internal val Throwable.httpStatus: Int? get() = (cause as? HttpStatusCause)?.code

private fun httpError(providerLabel: String, code: Int, body: JsonObject?): AiProviderException {
    // 401/403 podem ecoar parte da credencial: nesses casos o detalhe do servidor é descartado.
    val detail = (body?.get("error") as? JsonObject)?.str("message")?.take(180)
    val message = when (code) {
        401, 403 -> "$providerLabel recusou a chave de API (HTTP $code). Verifique a chave e as permissões."
        404 -> "$providerLabel: modelo ou endpoint não encontrado (HTTP 404). Revise o modelo e a URL base."
        400, 422 -> "$providerLabel rejeitou a requisição (HTTP $code)" + (detail?.let { ": $it" } ?: ".")
        408 -> "$providerLabel demorou demais para responder. Tente novamente."
        413 -> "O conteúdo enviado a $providerLabel é grande demais."
        429 -> "Limite de uso de $providerLabel atingido (HTTP 429). Aguarde e tente novamente ou revise o plano."
        in 500..599 -> "$providerLabel está indisponível no momento (HTTP $code). Tente novamente em instantes."
        else -> "$providerLabel respondeu com erro HTTP $code" + (detail?.let { ": $it" } ?: ".")
    }
    return AiProviderException(message, HttpStatusCause(code))
}

// ------------------------------------------------------------------ leitura tolerante de JSON

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.num(key: String): Double? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    return primitive.doubleOrNull
        ?: primitive.content.replace("R$", "").replace("%", "").trim().let { raw ->
            raw.toDoubleOrNull() ?: raw.replace(".", "").replace(",", ".").toDoubleOrNull()
        }
}

internal fun JsonObject.bool(key: String): Boolean? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return null
    return primitive.booleanOrNull ?: when (primitive.content.lowercase()) {
        "sim", "true", "yes" -> true
        "nao", "não", "false", "no" -> false
        else -> null
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.strList(key: String): List<String>? =
    arr(key)?.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }?.takeIf { it.isNotEmpty() }

/** Localiza o primeiro objeto JSON dentro do texto do modelo (tolerando cercas de markdown). */
internal fun extractJsonObject(text: String, json: Json): JsonObject? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val element: JsonElement = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
        ?: return null
    return element as? JsonObject
}
