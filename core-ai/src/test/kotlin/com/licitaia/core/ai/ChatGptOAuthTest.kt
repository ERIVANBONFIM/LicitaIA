package com.licitaia.core.ai

import com.licitaia.ai.api.AiProviderException
import com.licitaia.core.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.io.StringReader
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Servidor OAuth de mentira (MockWebServer): descoberta, token, refresh, revogação, JWKS com chave RSA gerada
 * no teste e id_token assinado. A "autorização" é simulada chamando o retorno em 127.0.0.1.
 */
class ChatGptOAuthTest {

    private class FakeSecretStore : SecretStore {
        val map = linkedMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun contains(key: String): Boolean = key in map
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private lateinit var server: MockWebServer
    private lateinit var issuer: String
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val forms = CopyOnWriteArrayList<Map<String, String>>()
    private val revoked = CopyOnWriteArrayList<Map<String, String>>()

    // Comportamento configurável do servidor falso.
    @Volatile private var nonceForIdToken: String? = null
    @Volatile private var audOverride: String? = null
    @Volatile private var grantedScope = ChatGptOAuthClient.SCOPES
    @Volatile private var refreshError: String? = null
    @Volatile private var tokenCounter = 0

    private var now = System.currentTimeMillis()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
        server.start()
        issuer = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun route(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath
        return when (path) {
            "/.well-known/openid-configuration" -> jsonResponse(
                buildJsonObject {
                    put("issuer", issuer)
                    put("authorization_endpoint", "$issuer/oauth/authorize")
                    put("token_endpoint", "$issuer/oauth/token")
                    put("revocation_endpoint", "$issuer/oauth/revoke")
                    put("jwks_uri", "$issuer/jwks")
                },
            )
            "/jwks" -> jsonResponse(buildJsonObject { put("keys", buildJsonArray { add(jwk()) }) })
            "/oauth/token" -> {
                val form = parseForm(request.body.readUtf8())
                forms += form
                when (form["grant_type"]) {
                    "authorization_code" -> {
                        tokenCounter++
                        jsonResponse(tokenBody(form.getValue("client_id"), "access-$tokenCounter", "refresh-$tokenCounter", withIdToken = true))
                    }
                    "refresh_token" -> refreshError?.let {
                        MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                            .setBody(buildJsonObject { put("error", it) }.toString())
                    } ?: run {
                        tokenCounter++
                        jsonResponse(tokenBody(form.getValue("client_id"), "access-$tokenCounter", "refresh-$tokenCounter", withIdToken = false))
                    }
                    else -> MockResponse().setResponseCode(400)
                }
            }
            "/oauth/revoke" -> {
                revoked += parseForm(request.body.readUtf8())
                MockResponse().setResponseCode(200)
            }
            "/v1/models" -> jsonResponse(
                buildJsonObject {
                    put(
                        "models",
                        buildJsonArray {
                            add(buildJsonObject { put("slug", "gpt-6-luna"); put("visibility", "list") })
                            add(buildJsonObject { put("slug", "gpt-5.5"); put("visibility", "list") })
                            add(buildJsonObject { put("slug", "secret-internal"); put("visibility", "hide") })
                        },
                    )
                },
            )
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun tokenBody(clientId: String, access: String, refresh: String, withIdToken: Boolean): JsonObject = buildJsonObject {
        put("access_token", access)
        put("refresh_token", refresh)
        put("token_type", "Bearer")
        put("expires_in", 3600)
        put("scope", grantedScope)
        if (withIdToken) put("id_token", idToken(clientId))
    }

    private fun idToken(clientId: String): String {
        val header = buildJsonObject { put("alg", "RS256"); put("kid", "k1"); put("typ", "JWT") }
        val payload = buildJsonObject {
            put("iss", issuer)
            put("aud", audOverride ?: clientId)
            put("sub", "user-sub-1")
            put("email", "Pessoa@Exemplo.com")
            put("name", "Pessoa")
            put("exp", now / 1000 + 3600)
            put("iat", now / 1000)
            nonceForIdToken?.let { put("nonce", it) }
            put("https://api.openai.com/auth", buildJsonObject { put("chatgpt_plan_type", "plus") })
        }
        val enc = Base64.getUrlEncoder().withoutPadding()
        val signingInput = enc.encodeToString(header.toString().toByteArray()) + "." + enc.encodeToString(payload.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return signingInput + "." + enc.encodeToString(sig)
    }

    private fun jwk(): JsonObject {
        val pub = keys.public as RSAPublicKey
        val enc = Base64.getUrlEncoder().withoutPadding()
        fun BigInteger.unsigned(): ByteArray = toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return buildJsonObject {
            put("kty", "RSA"); put("kid", "k1"); put("use", "sig"); put("alg", "RS256")
            put("n", enc.encodeToString(pub.modulus.unsigned()))
            put("e", enc.encodeToString(pub.publicExponent.unsigned()))
        }
    }

    private fun jsonResponse(body: JsonObject) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun client() = ChatGptOAuthClient(http, json, issuer = issuer, clock = { now })

    /** Simula o navegador chamando o retorno loopback. Devolve (status, Cache-Control). */
    private suspend fun callback(redirectUri: String, params: Map<String, String>, path: String? = null): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            val base = redirectUri.toHttpUrl().newBuilder().apply {
                if (path != null) encodedPath(path)
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            http.newCall(Request.Builder().url(base).build()).execute().use { it.code to it.header("Cache-Control") }
        }

    private fun HttpUrl.q(name: String) = queryParameter(name)

    /** Faz uma entrada completa: autorização → retorno com [returnedClientId] → resultado. */
    private suspend fun login(
        oauth: ChatGptOAuthClient,
        request: ChatGptAuthRequest,
        returnedClientId: String? = "oaiapp_123",
        extra: Map<String, String> = emptyMap(),
        stateOverride: String? = null,
    ): ChatGptLoginResult = kotlinx.coroutines.coroutineScope {
        val attempt = oauth.beginLogin(request)
        val url = attempt.authorizationUrl.toHttpUrl()
        nonceForIdToken = url.q("nonce")
        val pending = async(Dispatchers.IO) { attempt.await() }
        val params = buildMap {
            put("code", "code-xyz")
            put("state", stateOverride ?: url.q("state")!!)
            returnedClientId?.let { put("client_id", it) }
            putAll(extra)
        }
        val (status, cache) = callback(url.q("redirect_uri")!!, params)
        assertEquals("no-store", cache)
        assertEquals(200, status)
        pending.await()
    }

    // ------------------------------------------------------------------ testes

    @Test fun dynamicRegistrationSavesAndReusesIssuedClientId() = runBlocking {
        withTimeout(30_000) {
            val secrets = FakeSecretStore()
            val oauth = client()
            val store = ChatGptSessionStore(secrets, oauth, json) { now }
            val first = store.authRequest(5L)
            assertNull(first.clientId)
            assertTrue(first.hostId.startsWith("urn:uuid:"))
            assertEquals("host id persistido", first.hostId, store.hostId())

            val attempt = oauth.beginLogin(first)
            val url = attempt.authorizationUrl.toHttpUrl()
            assertEquals("/oauth/authorize", url.encodedPath)
            assertEquals("dynamic_agent_client", url.q("client_id"))
            assertEquals("LicitaIA", url.q("agent_name_hint"))
            assertEquals(first.hostId, url.q("ext_agent_host_id"))
            assertEquals("code", url.q("response_type"))
            assertEquals(ChatGptOAuthClient.SCOPES, url.q("scope"))
            assertEquals("https://api.openai.com/v1", url.q("resource"))
            assertEquals("S256", url.q("code_challenge_method"))
            val redirect = url.q("redirect_uri")!!
            assertTrue(redirect.matches(Regex("http://127\\.0\\.0\\.1:\\d+/auth/callback")))
            nonceForIdToken = url.q("nonce")
            val pending = async(Dispatchers.IO) { attempt.await() }
            callback(redirect, mapOf("code" to "code-1", "state" to url.q("state")!!, "client_id" to "oaiapp_123", "scope" to ChatGptOAuthClient.SCOPES))
            val result = pending.await()

            assertEquals("oaiapp_123", result.clientId)
            assertEquals("user-sub-1", result.identity.subject)
            assertEquals("pessoa@exemplo.com", result.identity.email)
            assertEquals("plus", result.identity.planType)
            val exchange = forms.last()
            assertEquals("authorization_code", exchange["grant_type"])
            assertEquals("oaiapp_123", exchange["client_id"])
            assertEquals(redirect, exchange["redirect_uri"])
            assertEquals("https://api.openai.com/v1", exchange["resource"])
            assertNull("sem client secret", exchange["client_secret"])
            assertEquals("PKCE S256", url.q("code_challenge"), ChatGptOAuthClient.codeChallenge(exchange.getValue("code_verifier")))

            store.save(5L, result)
            assertTrue(store.hasSession(5L))
            val info = store.accountInfo(5L)!!
            assertTrue(info.planShared)
            assertFalse("tokens não ficam em texto puro fora do cofre", secrets.map.keys.any { it.contains("access-1") })

            // Segunda entrada: reaproveita o client_id emitido, sem agent_name_hint, com dicas.
            val second = store.authRequest(5L)
            assertEquals("oaiapp_123", second.clientId)
            val url2 = oauth.beginLogin(second).also { it.cancel() }.authorizationUrl.toHttpUrl()
            assertEquals("oaiapp_123", url2.q("client_id"))
            assertNull(url2.q("agent_name_hint"))
            assertEquals("pessoa@exemplo.com", url2.q("login_hint"))
            assertNotNull(url2.q("id_token_hint"))
            assertEquals(first.hostId, url2.q("ext_agent_host_id"))
            // Escopos são isolados: outra empresa não herda o client_id.
            assertNull(store.authRequest(6L).clientId)
        }
    }

    @Test fun invalidStateIsRejectedAndLoginKeepsWaiting() = runBlocking {
        withTimeout(30_000) {
            val attempt = client().beginLogin(ChatGptAuthRequest(hostId = "urn:uuid:1"))
            val url = attempt.authorizationUrl.toHttpUrl()
            val pending = async(Dispatchers.IO) { runCatching { attempt.await() } }
            val (status, cache) = callback(url.q("redirect_uri")!!, mapOf("code" to "c", "state" to "outro", "client_id" to "oaiapp_1"))
            assertEquals(400, status)
            assertEquals("no-store", cache)
            val (notFound, _) = callback(url.q("redirect_uri")!!, mapOf("x" to "1"), path = "/favicon.ico")
            assertEquals(404, notFound)
            attempt.cancel()
            val error = pending.await().exceptionOrNull() as ChatGptAuthException
            assertEquals("cancelled", error.code)
            assertTrue("nenhum código foi trocado", forms.isEmpty())
        }
    }

    @Test fun accessDeniedIsReported() = runBlocking {
        withTimeout(30_000) {
            try {
                login(client(), ChatGptAuthRequest("urn:uuid:1"), extra = mapOf("error" to "access_denied"))
                fail()
            } catch (e: ChatGptAuthException) {
                assertEquals("access_denied", e.code)
                assertEquals("Você não autorizou o LicitaIA no ChatGPT.", e.message)
            }
            assertTrue(forms.isEmpty())
        }
    }

    @Test fun missingOrDynamicClientIdMeansRegistrationIncomplete() = runBlocking {
        withTimeout(30_000) {
            for (returned in listOf(null, "dynamic_agent_client")) {
                try {
                    login(client(), ChatGptAuthRequest("urn:uuid:1"), returnedClientId = returned)
                    fail()
                } catch (e: ChatGptAuthException) {
                    assertEquals("registration_incomplete", e.code)
                }
            }
        }
    }

    @Test fun missingDirectScopeBlocksPlanUsageAndRevokes() = runBlocking {
        withTimeout(30_000) {
            grantedScope = "openid profile email offline_access"
            try {
                login(client(), ChatGptAuthRequest("urn:uuid:1"))
                fail()
            } catch (e: ChatGptAuthException) {
                assertEquals(ChatGptOAuthClient.PLAN_NOT_SHARED_MESSAGE, e.message)
            }
            assertEquals("refresh-1", revoked.single()["token"])
        }
    }

    @Test fun wrongNonceOrAudienceIsRejected() = runBlocking {
        withTimeout(30_000) {
            val oauth = client()
            // nonce errado
            val attempt = oauth.beginLogin(ChatGptAuthRequest("urn:uuid:1"))
            val url = attempt.authorizationUrl.toHttpUrl()
            nonceForIdToken = "nonce-de-outro-pedido"
            val pending = async(Dispatchers.IO) { runCatching { attempt.await() } }
            callback(url.q("redirect_uri")!!, mapOf("code" to "c", "state" to url.q("state")!!, "client_id" to "oaiapp_1"))
            assertTrue(pending.await().exceptionOrNull() is ChatGptAuthException)

            // audiência diferente do client_id emitido
            audOverride = "oaiapp_outro"
            try {
                login(oauth, ChatGptAuthRequest("urn:uuid:1"))
                fail()
            } catch (e: ChatGptAuthException) {
                assertTrue(e.message!!.contains("não confere"))
            }
        }
    }

    @Test fun refreshRenewsWithMarginAndReplacesTokensTogether() = runBlocking {
        withTimeout(30_000) {
            val secrets = FakeSecretStore()
            val oauth = client()
            val store = ChatGptSessionStore(secrets, oauth, json) { now }
            store.save(0L, login(oauth, ChatGptAuthRequest(store.hostId())))
            assertEquals("access-1", store.accessToken(0L))

            now += 3600_000 - 30_000 // faltam 30 s: dentro da margem de 60 s
            assertEquals("access-2", store.accessToken(0L))
            val refresh = forms.last()
            assertEquals("refresh_token", refresh["grant_type"])
            assertEquals("refresh-1", refresh["refresh_token"])
            assertEquals("oaiapp_123", refresh["client_id"])
            assertEquals("https://api.openai.com/v1", refresh["resource"])
            assertNull(refresh["scope"])
            val saved = store.session(0L)!!
            assertEquals("refresh-2", saved.refreshToken)
            assertEquals(now + 3600_000, saved.expiresAtMs)

            // 401 com o token atual: renova uma vez.
            assertEquals("access-3", store.refreshAfterRejection(0L, "access-2"))

            // refresh inutilizável: apaga a sessão, mantém o registro (client_id) para entrar de novo.
            refreshError = "invalid_grant"
            try {
                store.refreshAfterRejection(0L, "access-3"); fail()
            } catch (e: AiProviderException) {
                assertTrue(e.message!!.contains("Entrar com ChatGPT"))
            }
            assertFalse(store.hasSession(0L))
            assertEquals("oaiapp_123", store.authRequest(0L).clientId)
        }
    }

    @Test fun signOutRevokesRefreshTokenAndClearsEverything() = runBlocking {
        withTimeout(30_000) {
            val secrets = FakeSecretStore()
            val oauth = client()
            val store = ChatGptSessionStore(secrets, oauth, json) { now }
            val host = store.hostId()
            store.save(3L, login(oauth, ChatGptAuthRequest(host)))
            store.signOut(3L)
            val r = revoked.single()
            assertEquals("refresh-1", r["token"])
            assertEquals("refresh_token", r["token_type_hint"])
            assertEquals("oaiapp_123", r["client_id"])
            assertFalse(store.hasSession(3L))
            assertNull(store.authRequest(3L).clientId)
            assertEquals("o host id da instalação é mantido", host, store.hostId())
        }
    }

    @Test fun listModelsUsesVisibleSlugsAndChoosesDefault() = runBlocking {
        val models = client().listModels("tok", apiBase = issuer)
        assertEquals(listOf("gpt-6-luna", "gpt-5.5"), models)
        assertEquals("gpt-5.5", ChatGptOAuthClient.chooseModel(models, "gpt-5.5", "gpt-6-luna"))
        assertEquals("gpt-6-luna", ChatGptOAuthClient.chooseModel(models, "modelo-velho", "gpt-6-luna"))
        assertEquals("gpt-5.5", ChatGptOAuthClient.chooseModel(listOf("gpt-4.1", "gpt-5.5"), "x", "gpt-6-luna"))
        assertEquals("gpt-7", ChatGptOAuthClient.chooseModel(listOf("gpt-7", "gpt-7-realtime"), "x", "y"))
    }

    // ------------------------------------------------------------------ Responses API

    @Test fun parsesStreamedResponsesText() {
        val sse = """
            event: response.created
            data: {"type":"response.created"}

            data: {"type":"response.output_text.delta","delta":"{\"ok\":"}

            data: {"type":"response.output_text.delta","delta":" true}"}

            data: {"type":"response.completed","response":{"usage":{"input_tokens":3}}}

        """.trimIndent()
        assertEquals("{\"ok\": true}", ChatGptResponses.parseStream(StringReader(sse), json))
    }

    @Test fun streamWithoutCompletedFails() {
        val sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"meio\"}\n\n"
        try {
            ChatGptResponses.parseStream(StringReader(sse), json); fail()
        } catch (e: AiProviderException) {
            assertTrue(e.message!!.contains("caiu"))
        }
    }

    @Test fun failedEventMapsUsageLimit() {
        val sse = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
        try {
            ChatGptResponses.parseStream(StringReader(sse), json); fail()
        } catch (e: AiProviderException) {
            assertTrue(e.message!!.contains("limite de uso"))
            assertTrue(e.message!!.contains("chave de API"))
        }
    }

    @Test fun textFromNonStreamedResponse() {
        val body = json.parseToJsonElement(
            """{"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"Olá"},{"type":"output_text","text":" mundo"}]}]}""",
        ) as JsonObject
        assertEquals("Olá mundo", ChatGptResponses.textFromResponse(body))
        val direct = json.parseToJsonElement("""{"output_text":"direto"}""") as JsonObject
        assertEquals("direto", ChatGptResponses.textFromResponse(direct))
    }

    @Test fun httpErrorsGetClearMessages() {
        fun err(status: Int, code: String) = ChatGptResponses.apiError(
            status, json.parseToJsonElement("""{"error":{"code":"$code"}}""") as JsonObject,
        )
        assertTrue(err(403, "subscription_sharing_user_not_eligible").message!!.contains("não pode ser usado"))
        assertTrue(err(403, "chatpass_v2_scope_not_authorized").message!!.contains("compartilhamento"))
        assertEquals(401, err(401, "subscription_sharing_invalid_user").httpStatus)
        assertTrue(err(429, "subscription_sharing_usage_limit_exceeded").message!!.contains("chatgpt.com/settings/usage"))
    }

    @Test fun requestBodyFollowsPreviewRequirements() {
        val body = ChatGptResponses.requestBody("gpt-6-luna", "sistema", "pergunta")
        assertEquals("false", body["store"].toString())
        assertEquals("true", body["stream"].toString())
        assertEquals("\"sistema\"", body["instructions"].toString())
        assertTrue(body["input"].toString().contains("\"role\":\"user\""))
    }
}
