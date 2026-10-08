package com.licitaia.core.platform

import com.licitaia.core.platform.net.PlatformApi
import com.licitaia.core.platform.net.PlatformException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PlatformApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PlatformApi

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = PlatformApi(OkHttpClient(), json, server.url("/api/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `login success parses token and user`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"token":"eyJ.abc","user":{"id":"u1","nome":"Fulano","email":"f@e.com","role":"admin",
                   "empresa":{"id":"e1","cnpj":"00000000000191","razaoSocial":"Empresa LTDA"}}}""",
            ),
        )
        val resp = api.login("f@e.com", "segredo")
        assertEquals("eyJ.abc", resp.token)
        assertEquals("admin", resp.user.role)
        assertEquals("Empresa LTDA", resp.user.empresa?.razaoSocial)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/login", req.path)
        assertTrue(req.body.readUtf8().contains("\"email\":\"f@e.com\""))
    }

    @Test fun `login 401 maps to UNAUTHORIZED with server message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Credenciais inválidas"}"""))
        try {
            api.login("x@x.com", "wrong")
            fail("deveria lançar")
        } catch (e: PlatformException) {
            assertEquals(PlatformException.Kind.UNAUTHORIZED, e.kind)
            assertEquals("Credenciais inválidas", e.message)
        }
    }

    @Test fun `health parses status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","version":"1.0.0"}"""))
        assertEquals("ok", api.health().status)
    }

    @Test fun `licitacoes leve sends bearer and parses page`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":[{"id":"t1","numero":"PE 1/2026","orgao":"Org","objeto":"Obj",
                   "valorEstimado":"1641602.39","updatedAt":"2026-10-08T01:00:00.000Z"}],
                   "total":1,"page":1,"totalPages":1}""",
            ),
        )
        val page = api.licitacoesLeve("tok123", page = 1)
        assertEquals(1, page.data.size)
        assertEquals("1641602.39", page.data.first().valorEstimado)

        val req = server.takeRequest()
        assertEquals("Bearer tok123", req.getHeader("Authorization"))
        assertTrue(req.path!!.contains("leve=true"))
        assertTrue(req.path!!.contains("ordenar=updatedAt"))
    }

    @Test fun `me returns null on 404 (rota ainda inexistente)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not found"}"""))
        assertNull(api.me("tok"))
    }
}
