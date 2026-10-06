package com.licitaia.core.ai

import com.licitaia.ai.api.AiProviderException
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Modelos de raciocínio podem levar mais que o timeout padrão de leitura. */
private fun OkHttpClient.forLlm(): OkHttpClient = newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

private fun chatCompletionsBody(model: String, system: String, user: String, jsonMode: Boolean): JsonObject =
    buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            add(buildJsonObject { put("role", "user"); put("content", user) })
        }
        if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
    }

private fun chatCompletionsText(label: String, response: JsonObject): String {
    val choice = response.arr("choices")?.firstOrNull() as? JsonObject
    val message = choice?.obj("message")
    message?.str("refusal")?.let { throw AiProviderException("$label recusou a solicitação: ${it.take(160)}") }
    return message?.str("content") ?: throw AiProviderException("$label não devolveu conteúdo na resposta.")
}

/**
 * OpenAI. Chave de API: `POST /v1/chat/completions` com `Authorization: Bearer <chave>`.
 * Conta ChatGPT ("Entrar com ChatGPT"): Responses API `POST https://api.openai.com/v1/responses` com o access
 * token OAuth (renovado com margem de 60 s; uma nova tentativa após 401; até 2 novas tentativas em 5xx).
 */
@Singleton
class OpenAiProvider @Inject constructor(
    client: OkHttpClient,
    json: Json,
    private val credentials: AiCredentials,
    private val chatGpt: ChatGptAuthorizer,
) : LlmBackedProvider(json) {
    private val http by lazy { client.forLlm() }
    override val type = AiProviderType.OPENAI
    override val displayName = type.label

    override suspend fun complete(system: String, user: String, expectJson: Boolean): String {
        val cfg = credentials.resolve(type)
        if (cfg.authMode == AiAuthMode.OAUTH) return completeWithChatGpt(cfg, system, user)
        val response = http.postJson(
            providerLabel = displayName,
            url = "${cfg.baseUrl}/v1/chat/completions",
            headers = mapOf("Authorization" to "Bearer ${cfg.apiKey}"),
            body = chatCompletionsBody(cfg.model, system, user, jsonMode = expectJson),
            json = json,
        )
        return chatCompletionsText(displayName, response)
    }

    private suspend fun completeWithChatGpt(cfg: ResolvedAi, system: String, user: String): String {
        val url = "${cfg.baseUrl}/v1/responses"
        val body = ChatGptResponses.requestBody(cfg.model, system, user)
        var token = chatGpt.accessToken(cfg.companyId)
        var refreshed = false
        var attempt = 0
        while (true) {
            try {
                return ChatGptResponses.call(http, url, token, body, json)
            } catch (e: AiProviderException) {
                val status = e.httpStatus
                when {
                    status == 401 && !refreshed -> {
                        refreshed = true
                        token = chatGpt.refreshAfterRejection(cfg.companyId, token)
                    }
                    status != null && status in 500..599 && attempt < SERVER_RETRIES -> {
                        attempt++
                        kotlinx.coroutines.delay(attempt * RETRY_BACKOFF_MS)
                    }
                    else -> throw e
                }
            }
        }
    }

    private companion object {
        const val SERVER_RETRIES = 2
        const val RETRY_BACKOFF_MS = 2_000L
    }
}

/** Anthropic — `POST /v1/messages` com `x-api-key` e `anthropic-version: 2023-06-01`. */
@Singleton
class AnthropicProvider @Inject constructor(
    client: OkHttpClient,
    json: Json,
    private val credentials: AiCredentials,
) : LlmBackedProvider(json) {
    private val http by lazy { client.forLlm() }
    override val type = AiProviderType.ANTHROPIC
    override val displayName = type.label

    override suspend fun complete(system: String, user: String, expectJson: Boolean): String {
        val cfg = credentials.resolve(type)
        // Sem `temperature`/`thinking`: os modelos atuais rejeitam amostragem customizada e já
        // raciocinam de forma adaptativa por padrão. `max_tokens` folgado cobre raciocínio + resposta.
        val body = buildJsonObject {
            put("model", cfg.model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }
        val response = http.postJson(
            providerLabel = displayName,
            url = "${cfg.baseUrl}/v1/messages",
            headers = mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to API_VERSION),
            body = body,
            json = json,
        )
        if (response.str("stop_reason") == "refusal") {
            throw AiProviderException("$displayName recusou a solicitação por política de segurança do modelo.")
        }
        // A resposta pode trazer blocos `thinking` antes do texto: só os blocos `text` interessam.
        val text = response.arr("content").orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
        if (text.isBlank()) throw AiProviderException("$displayName não devolveu conteúdo na resposta.")
        return text
    }

    private companion object {
        const val API_VERSION = "2023-06-01"
        const val MAX_TOKENS = 16_000
    }
}

/**
 * Google Gemini — `POST v1beta/models/{model}:generateContent`.
 * Autenticação: `x-goog-api-key` (chave) ou `Authorization: Bearer` com token OAuth de escopo
 * `cloud-platform` (+ `x-goog-user-project` se o projeto estiver configurado) — https://ai.google.dev/gemini-api/docs/oauth.
 */
@Singleton
class GeminiProvider @Inject constructor(
    client: OkHttpClient,
    json: Json,
    private val credentials: AiCredentials,
    private val googleAuth: GoogleAiAuthorizer,
) : LlmBackedProvider(json) {
    private val http by lazy { client.forLlm() }
    override val type = AiProviderType.GEMINI
    override val displayName = type.label

    override suspend fun complete(system: String, user: String, expectJson: Boolean): String {
        val cfg = credentials.resolve(type)
        val model = cfg.model.removePrefix("models/")
        if (!MODEL_NAME.matches(model)) throw AiProviderException("Nome de modelo inválido para $displayName.")
        val body = requestBody(system, user, expectJson)
        val url = "${cfg.baseUrl}/v1beta/models/$model:generateContent"
        val response = if (cfg.authMode == AiAuthMode.OAUTH) {
            try {
                http.postJson(displayName, url, oauthHeaders(googleAuth.accessToken(cfg.companyId), cfg.cloudProject), body, json)
            } catch (e: AiProviderException) {
                // Token recusado: renova uma vez sem UI e repete. Se o Google exigir interação, a renovação explica.
                if (e.httpStatus != 401) throw e.withOAuthWording()
                try {
                    http.postJson(displayName, url, oauthHeaders(googleAuth.refreshSilently(cfg.companyId), cfg.cloudProject), body, json)
                } catch (retry: AiProviderException) {
                    throw retry.withOAuthWording()
                }
            }
        } else {
            http.postJson(displayName, url, mapOf("x-goog-api-key" to cfg.apiKey), body, json)
        }
        return extractText(response)
    }

    private fun oauthHeaders(token: String, cloudProject: String?): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        if (!cloudProject.isNullOrBlank()) put("x-goog-user-project", cloudProject)
    }

    /** No modo conta, 401/403 não é "chave inválida": é autorização/permissão do projeto Google Cloud. */
    private fun AiProviderException.withOAuthWording(): AiProviderException = when (httpStatus) {
        401 -> AiProviderException(GoogleAiAuthorizer.EXPIRED_MESSAGE, cause)
        403 -> AiProviderException(
            "$displayName recusou a conta Google (HTTP 403). Ative a \"Generative Language API\" no projeto Google Cloud, " +
                "confira o campo \"Projeto Google Cloud\" e se a conta tem permissão nele.",
            cause,
        )
        else -> this
    }

    private fun requestBody(system: String, user: String, expectJson: Boolean): JsonObject {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) })
                    },
                )
            }
            if (expectJson) putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
        }
        return body
    }

    private fun extractText(response: JsonObject): String {
        val candidate = response.arr("candidates")?.firstOrNull() as? JsonObject
        val text = candidate?.obj("content")?.arr("parts").orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("text") }
            .joinToString("\n")
        if (text.isBlank()) {
            val reason = response.obj("promptFeedback")?.str("blockReason") ?: candidate?.str("finishReason")
            throw AiProviderException(
                "$displayName não devolveu conteúdo" + (reason?.let { " (motivo: $it)." } ?: "."),
            )
        }
        return text
    }

    private companion object {
        val MODEL_NAME = Regex("[A-Za-z0-9._-]+")
    }
}

/** API personalizada compatível com OpenAI chat-completions em URL base configurável (HTTPS). */
@Singleton
class CustomProvider @Inject constructor(
    client: OkHttpClient,
    json: Json,
    private val credentials: AiCredentials,
) : LlmBackedProvider(json) {
    private val http by lazy { client.forLlm() }
    override val type = AiProviderType.CUSTOM
    override val displayName = type.label

    override suspend fun complete(system: String, user: String, expectJson: Boolean): String {
        val cfg = credentials.resolve(type)
        val response = http.postJson(
            providerLabel = displayName,
            url = endpointUrl(cfg.baseUrl),
            headers = mapOf("Authorization" to "Bearer ${cfg.apiKey}"),
            // `response_format` não é universal entre servidores compatíveis: o JSON é pedido só via prompt.
            body = chatCompletionsBody(cfg.model, system, user, jsonMode = false),
            json = json,
        )
        return chatCompletionsText(displayName, response)
    }

    internal companion object {
        /** Aceita a URL completa do endpoint, uma base terminada em `/v1` ou só o host. */
        fun endpointUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return when {
                base.endsWith("/chat/completions") -> base
                Regex("/v\\d+[a-z0-9]*$").containsMatchIn(base) -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }
        }
    }
}
