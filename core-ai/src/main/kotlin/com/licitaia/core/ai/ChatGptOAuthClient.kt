package com.licitaia.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Erro do fluxo "Entrar com ChatGPT". [message] já é texto em pt-BR para o usuário (sem tokens).
 * [code] é o código OAuth/OpenAI quando houver (ex.: `access_denied`, `invalid_grant`).
 */
class ChatGptAuthException(
    message: String,
    val code: String? = null,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Refresh token inutilizável (doc "Errors and recovery"): limpar os tokens e entrar de novo com o client_id salvo. */
    val requiresSignIn: Boolean get() = code in ChatGptOAuthClient.UNUSABLE_REFRESH_CODES
}

/** Documento de descoberta OpenID (`/.well-known/openid-configuration`). */
data class ChatGptDiscovery(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val jwksUri: String,
)

/** Conjunto de tokens. Nunca é logado; vive só no cofre (Keystore). */
data class ChatGptTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val scope: String,
    val idToken: String?,
)

/** Identidade validada do id_token. [subject] (`sub`) é a identidade; e-mail/nome/plano só para exibição. */
data class ChatGptIdentity(
    val subject: String,
    val email: String?,
    val name: String?,
    val planType: String?,
)

/** Parâmetros devolvidos no retorno `http://127.0.0.1:<porta>/auth/callback`. */
data class ChatGptCallback(
    val code: String?,
    val state: String?,
    val clientId: String?,
    val scope: String?,
    val error: String?,
)

data class ChatGptLoginResult(
    /** client_id emitido (`oaiapp_…`), reutilizado nas próximas entradas desta conta. */
    val clientId: String,
    val tokens: ChatGptTokens,
    val identity: ChatGptIdentity,
    val grantedScopes: Set<String>,
)

/** Dados da tentativa de autorização. [clientId] null = primeiro cadastro (`dynamic_agent_client`). */
data class ChatGptAuthRequest(
    val hostId: String,
    val clientId: String? = null,
    val loginHint: String? = null,
    val idTokenHint: String? = null,
)

/**
 * Cliente OAuth 2.0 + PKCE do "Sign in with ChatGPT" (doc oficial:
 * https://developers.openai.com/siwc/token-sharing-open-source/sign-in). Puro JVM (OkHttp), sem Android,
 * para ser testado com MockWebServer. O [issuer] só é trocado em testes (equivalente ao `MAPA_IA_ISSUER`).
 */
class ChatGptOAuthClient(
    private val http: OkHttpClient,
    private val json: Json,
    issuer: String = DEFAULT_ISSUER,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    val issuer: String = issuer.trimEnd('/')
    private val secureIssuer = this.issuer.startsWith("https://", ignoreCase = true)

    @Volatile private var cachedDiscovery: ChatGptDiscovery? = null
    @Volatile private var cachedJwks: JsonArray? = null

    // ------------------------------------------------------------------ descoberta

    suspend fun discovery(): ChatGptDiscovery {
        cachedDiscovery?.let { return it }
        val (code, body) = send(Request.Builder().url("$issuer/.well-known/openid-configuration").header("Accept", "application/json").get().build())
        val obj = parse(body)
        if (code !in 200..299 || obj == null) throw ChatGptAuthException("Não consegui ler a configuração de entrada da OpenAI.", status = code)
        val d = ChatGptDiscovery(
            issuer = obj.str("issuer")?.trimEnd('/') ?: "",
            authorizationEndpoint = obj.str("authorization_endpoint") ?: "",
            tokenEndpoint = obj.str("token_endpoint") ?: "",
            revocationEndpoint = obj.str("revocation_endpoint"),
            jwksUri = obj.str("jwks_uri") ?: "",
        )
        if (d.issuer != issuer) throw ChatGptAuthException("A configuração de entrada da OpenAI não confere (issuer).")
        val endpoints = listOfNotNull(d.authorizationEndpoint, d.tokenEndpoint, d.jwksUri, d.revocationEndpoint)
        if (endpoints.any { it.toHttpUrlOrNull() == null || (secureIssuer && !it.startsWith("https://", ignoreCase = true)) }) {
            throw ChatGptAuthException("A configuração de entrada da OpenAI está incompleta.")
        }
        cachedDiscovery = d
        return d
    }

    // ------------------------------------------------------------------ autorização

    /**
     * Abre o servidor de retorno em 127.0.0.1 (porta livre) e monta a URL de autorização. O chamador abre a
     * URL no navegador (Custom Tab) e chama [ChatGptLoginAttempt.await].
     */
    suspend fun beginLogin(request: ChatGptAuthRequest, returnLink: String? = null, appName: String = APP_NAME): ChatGptLoginAttempt {
        require(request.hostId.isNotBlank())
        val d = discovery()
        val state = randomToken()
        val nonce = randomToken()
        val verifier = randomToken()
        val issuedClientId = request.clientId?.trim()?.takeIf { it.isNotEmpty() && it != DYNAMIC_CLIENT_ID }
        val server = LoopbackCallbackServer(returnLink)
        try {
            val url = d.authorizationEndpoint.toHttpUrlOrNull()!!.newBuilder().apply {
                addQueryParameter("client_id", issuedClientId ?: DYNAMIC_CLIENT_ID)
                if (issuedClientId == null) addQueryParameter("agent_name_hint", appName)
                addQueryParameter("ext_agent_host_id", request.hostId)
                addQueryParameter("response_type", "code")
                addQueryParameter("redirect_uri", server.redirectUri)
                addQueryParameter("scope", SCOPES)
                addQueryParameter("resource", RESOURCE)
                addQueryParameter("state", state)
                addQueryParameter("nonce", nonce)
                addQueryParameter("code_challenge_method", "S256")
                addQueryParameter("code_challenge", codeChallenge(verifier))
                request.loginHint?.takeIf { it.isNotBlank() }?.let { addQueryParameter("login_hint", it) }
                if (issuedClientId != null) request.idTokenHint?.takeIf { it.isNotBlank() }?.let { addQueryParameter("id_token_hint", it) }
            }.build().toString()
            return ChatGptLoginAttempt(url, server, this, issuedClientId, state, nonce, verifier)
        } catch (e: Throwable) {
            server.close()
            throw e
        }
    }

    /** Valida o retorno, troca o código, valida o id_token e exige o escopo de uso do plano. */
    internal suspend fun complete(
        callback: ChatGptCallback,
        requestedClientId: String?,
        nonce: String,
        verifier: String,
        redirectUri: String,
    ): ChatGptLoginResult {
        callback.error?.let { error ->
            throw if (error == "access_denied") {
                ChatGptAuthException("Você não autorizou o LicitaIA no ChatGPT.", code = error)
            } else {
                ChatGptAuthException("A OpenAI recusou a entrada ($error). Tente de novo.", code = error)
            }
        }
        // O client_id emitido vem no retorno; só se ausente vale o que já tínhamos (nunca o dinâmico).
        val clientId = callback.clientId?.trim()?.takeIf { it.isNotEmpty() } ?: requestedClientId
        if (clientId == null || clientId == DYNAMIC_CLIENT_ID) {
            throw ChatGptAuthException("A OpenAI não completou o cadastro do LicitaIA. Tente entrar de novo.", code = "registration_incomplete")
        }
        val code = callback.code?.takeIf { it.isNotBlank() }
            ?: throw ChatGptAuthException("A OpenAI não devolveu o código de autorização. Tente de novo.")
        val tokens = tokenRequest(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectUri,
                "resource" to RESOURCE,
            ),
            previousRefresh = null,
        )
        val idToken = tokens.idToken ?: throw ChatGptAuthException("A OpenAI não devolveu a identidade da conta.")
        val identity = verifyIdToken(idToken, clientId, nonce)
        val granted = grantedScopes(tokens, callback.scope)
        if (DIRECT_SCOPE !in granted) {
            // Sem permissão de uso do plano os tokens não servem ao app: revoga (melhor esforço) e explica.
            tokens.refreshToken?.let { runCatching { revoke(clientId, it) } }
            throw ChatGptAuthException(PLAN_NOT_SHARED_MESSAGE, code = "plan_not_shared")
        }
        return ChatGptLoginResult(clientId, tokens, identity, granted)
    }

    // ------------------------------------------------------------------ tokens

    /** `grant_type=refresh_token` com `resource`; sem `scope` (mantém a concessão). Devolve o conjunto completo novo. */
    suspend fun refresh(clientId: String, refreshToken: String, previousIdToken: String? = null): ChatGptTokens {
        val tokens = tokenRequest(
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "resource" to RESOURCE,
            ),
            previousRefresh = refreshToken,
        )
        val idToken = tokens.idToken?.also { verifyIdToken(it, clientId, expectedNonce = null) } ?: previousIdToken
        return tokens.copy(idToken = idToken)
    }

    /** Revoga o refresh token no `revocation_endpoint` (melhor esforço; uma nova tentativa em falha de rede/5xx). */
    suspend fun revoke(clientId: String, refreshToken: String): Boolean {
        val endpoint = runCatching { discovery().revocationEndpoint }.getOrNull() ?: return false
        repeat(2) { attempt ->
            // Só falha de rede e 5xx merecem nova tentativa; 4xx é definitivo.
            val definitive = try {
                val body = FormBody.Builder()
                    .add("token", refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", clientId)
                    .build()
                val (code, _) = send(Request.Builder().url(endpoint).post(body).build())
                if (code in 200..299) return true
                code !in 500..599
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (definitive) return false
            if (attempt == 0) delay(REVOKE_RETRY_MS)
        }
        return false
    }

    private suspend fun tokenRequest(params: Map<String, String>, previousRefresh: String?): ChatGptTokens {
        val d = discovery()
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val (code, body) = try {
            send(Request.Builder().url(d.tokenEndpoint).header("Accept", "application/json").post(form).build())
        } catch (e: IOException) {
            throw ChatGptAuthException("Sem conexão com a OpenAI. Verifique a internet e tente de novo.", code = "network", cause = e)
        }
        val obj = parse(body)
        if (code !in 200..299) throw tokenError(code, obj)
        val access = obj?.str("access_token") ?: throw ChatGptAuthException("A OpenAI não devolveu a credencial.")
        val expiresIn = obj.num("expires_in")?.toLong()?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_IN_S
        return ChatGptTokens(
            accessToken = access,
            refreshToken = obj.str("refresh_token") ?: previousRefresh,
            expiresAtMs = clock() + expiresIn * 1000,
            scope = obj.str("scope").orEmpty(),
            idToken = obj.str("id_token"),
        )
    }

    private fun tokenError(status: Int, body: JsonObject?): ChatGptAuthException {
        val errorObj = body?.obj("error")
        val code = errorObj?.str("code") ?: body?.str("error") ?: errorObj?.str("type")
        val message = when {
            code in UNUSABLE_REFRESH_CODES -> "A sessão do ChatGPT venceu ou foi encerrada. Toque em \"Entrar com ChatGPT\" de novo."
            code == "invalid_client" -> "A OpenAI não reconheceu o cadastro do LicitaIA (invalid_client). Desconecte e entre de novo."
            status == 429 -> "A OpenAI pediu para esperar um pouco. Tente de novo em instantes."
            status >= 500 -> "A OpenAI está com problema no servidor dela agora. Tente de novo em instantes."
            else -> "A OpenAI recusou a credencial (HTTP $status${code?.let { ", $it" } ?: ""})."
        }
        return ChatGptAuthException(message, code = code, status = status)
    }

    /** Escopos concedidos: resposta do token; senão o `scope` do retorno; senão a claim `scope` do access token. */
    internal fun grantedScopes(tokens: ChatGptTokens, callbackScope: String?): Set<String> {
        val raw = tokens.scope.takeIf { it.isNotBlank() }
            ?: callbackScope?.takeIf { it.isNotBlank() }
            ?: decodePayload(tokens.accessToken)?.str("scope")
            ?: ""
        return raw.split(' ', '+').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    // ------------------------------------------------------------------ id_token

    /**
     * Valida o id_token: assinatura RS256 com a chave do `jwks_uri`, `iss`, `aud` (= client_id emitido), `exp` e
     * `nonce` (quando [expectedNonce] não é null). Devolve a identidade (`sub`).
     */
    suspend fun verifyIdToken(idToken: String, clientId: String, expectedNonce: String?): ChatGptIdentity {
        val invalid = "A identidade devolvida pela OpenAI não confere. Tente entrar de novo."
        if (idToken.length > MAX_JWT) throw ChatGptAuthException(invalid)
        val parts = idToken.split('.')
        if (parts.size != 3) throw ChatGptAuthException(invalid)
        val decoder = Base64.getUrlDecoder()
        val header = runCatching { json.parseToJsonElement(String(decoder.decode(parts[0]), Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw ChatGptAuthException(invalid)
        if (header.str("alg") != "RS256") throw ChatGptAuthException(invalid)
        val kid = header.str("kid")
        val key = findKey(kid, refresh = false) ?: findKey(kid, refresh = true)
            ?: throw ChatGptAuthException("Chave de assinatura da OpenAI desconhecida. Tente entrar de novo.")
        val valid = runCatching {
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(
                    BigInteger(1, decoder.decode(key.str("n"))),
                    BigInteger(1, decoder.decode(key.str("e"))),
                ),
            )
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(decoder.decode(parts[2]))
            }
        }.getOrDefault(false)
        if (!valid) throw ChatGptAuthException(invalid)
        val claims = decodePayload(idToken) ?: throw ChatGptAuthException(invalid)
        val issuerOk = claims.str("iss")?.trimEnd('/') == discovery().issuer
        val audience = when (val aud = claims["aud"]) {
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOf(aud.content)
            else -> emptyList()
        }
        val exp = claims.num("exp")?.toLong() ?: 0L
        val notExpired = exp * 1000 + CLOCK_SKEW_MS > clock()
        val nonceOk = expectedNonce == null || claims.str("nonce")?.let { constantTimeEquals(it, expectedNonce) } == true
        val subject = claims.str("sub")
        if (!issuerOk || clientId !in audience || !notExpired || !nonceOk || subject == null) throw ChatGptAuthException(invalid)
        val auth = claims.obj(AUTH_CLAIM)
        return ChatGptIdentity(
            subject = subject,
            email = claims.str("email")?.lowercase(),
            name = claims.str("name"),
            planType = auth?.str("chatgpt_plan_type"),
        )
    }

    private suspend fun findKey(kid: String?, refresh: Boolean): JsonObject? {
        val keys = if (!refresh && cachedJwks != null) cachedJwks!! else {
            val (code, body) = try {
                send(Request.Builder().url(discovery().jwksUri).header("Accept", "application/json").get().build())
            } catch (e: IOException) {
                throw ChatGptAuthException("Sem conexão para validar a identidade da OpenAI.", code = "network", cause = e)
            }
            if (code !in 200..299 || body.length > MAX_JWKS) throw ChatGptAuthException("Não consegui validar a identidade da OpenAI.")
            (parse(body)?.arr("keys") ?: throw ChatGptAuthException("Não consegui validar a identidade da OpenAI.")).also { cachedJwks = it }
        }
        return keys.mapNotNull { it as? JsonObject }.firstOrNull { k ->
            k.str("kty") == "RSA" && (kid == null || k.str("kid") == kid) && k.str("use").let { it == null || it == "sig" }
        }
    }

    private fun decodePayload(jwt: String): JsonObject? = runCatching {
        val part = jwt.split('.')[1]
        json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    // ------------------------------------------------------------------ modelos

    /** `GET {apiBase}/v1/models` com o access token: rota do ChatGPT (`models[].slug`, `visibility`) ou `data[].id`. */
    suspend fun listModels(accessToken: String, apiBase: String = DEFAULT_API_BASE): List<String> {
        val (code, body) = send(
            Request.Builder().url("${apiBase.trimEnd('/')}/v1/models")
                .header("Authorization", "Bearer $accessToken").header("Accept", "application/json").get().build(),
        )
        if (code !in 200..299) throw ChatGptAuthException("Não consegui listar os modelos do ChatGPT (HTTP $code).", status = code)
        val obj = parse(body) ?: return emptyList()
        obj.arr("data")?.let { data -> return data.mapNotNull { (it as? JsonObject)?.str("id") } }
        return obj.arr("models").orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("visibility").let { v -> v == null || v == "list" } }
            .mapNotNull { it.str("slug") }
    }

    // ------------------------------------------------------------------ HTTP

    private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private suspend fun send(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use { it.body?.string().orEmpty() }
                    continuation.resume(response.code to text)
                } catch (e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun randomToken(): String = base64Url(ByteArray(32).also { random.nextBytes(it) })

    companion object {
        const val DEFAULT_ISSUER = "https://auth.openai.com"
        const val DEFAULT_API_BASE = "https://api.openai.com"
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        const val APP_NAME = "LicitaPRO"
        const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        const val RESOURCE = "https://api.openai.com/v1"
        const val AUTH_CLAIM = "https://api.openai.com/auth"
        const val PLAN_NOT_SHARED_MESSAGE =
            "Você entrou, mas o ChatGPT não liberou o uso do plano para o LicitaIA. " +
                "Ative o compartilhamento de uso nas configurações do ChatGPT ou use uma chave de API."
        val UNUSABLE_REFRESH_CODES = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
        )
        private const val DEFAULT_EXPIRES_IN_S = 3600L
        private const val CLOCK_SKEW_MS = 60_000L
        private const val REVOKE_RETRY_MS = 1_000L
        private const val MAX_JWT = 32_768
        private const val MAX_JWKS = 200_000

        /** Modelos preferidos quando o atual não está na lista da conta (mesma ordem do app desktop, após o padrão). */
        private val PREFERRED_MODELS = listOf("gpt-5.5", "gpt-5.4", "gpt-5.2", "gpt-5.1", "gpt-5", "gpt-5-mini", "gpt-4.1")
        private val EXCLUDED = Regex("realtime|audio|image|tts|transcribe|search|codex|embedding")

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** PKCE S256: base64url(SHA-256(verifier)) sem padding. */
        fun codeChallenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** `ext_agent_host_id` no formato UUIDv4 `urn:uuid:…`. */
        fun newHostId(): String = "urn:uuid:${UUID.randomUUID()}"

        /** Porta de `escolherModelo` do app desktop: mantém o atual se disponível; senão o padrão/preferido; senão o GPT mais novo. */
        fun chooseModel(available: List<String>, current: String?, defaultModel: String): String {
            if (available.isEmpty()) return current?.takeIf { it.isNotBlank() } ?: defaultModel
            if (current != null && current in available) return current
            (listOf(defaultModel) + PREFERRED_MODELS).firstOrNull { it in available }?.let { return it }
            val gpt = available.filter { Regex("^gpt-\\d").containsMatchIn(it) && !EXCLUDED.containsMatchIn(it) }.sortedDescending()
            return gpt.firstOrNull() ?: available.first()
        }

        internal fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

/** Uma tentativa de entrada: servidor de retorno aberto + segredos de uso único (state, nonce, PKCE). */
class ChatGptLoginAttempt internal constructor(
    val authorizationUrl: String,
    private val server: LoopbackCallbackServer,
    private val client: ChatGptOAuthClient,
    private val requestedClientId: String?,
    private val state: String,
    private val nonce: String,
    private val verifier: String,
) {
    val redirectUri: String get() = server.redirectUri

    /** Espera o retorno do navegador (cancelável), fecha o servidor e conclui a troca do código. */
    suspend fun await(): ChatGptLoginResult {
        val callback = try {
            server.awaitCallback(state)
        } finally {
            server.close()
        }
        return client.complete(callback, requestedClientId, nonce, verifier, server.redirectUri)
    }

    /** Fecha o servidor: um [await] em andamento termina com "Entrada cancelada.". */
    fun cancel() = server.close()
}
