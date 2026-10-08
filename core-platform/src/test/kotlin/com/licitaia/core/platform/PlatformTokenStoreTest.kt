package com.licitaia.core.platform

import com.licitaia.core.platform.net.EmpresaDto
import com.licitaia.core.platform.net.UserDto
import com.licitaia.core.platform.session.PlatformTokenStore
import com.licitaia.core.security.SecretStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cofre em memória (os valores reais são cifrados por Keystore em produção). */
private class FakeSecretStore : SecretStore {
    val map = mutableMapOf<String, String>()
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun contains(key: String): Boolean = map.containsKey(key)
    override suspend fun remove(key: String) { map.remove(key) }
}

class PlatformTokenStoreTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `save then read token and user`() = runTest {
        val store = PlatformTokenStore(FakeSecretStore(), json)
        val user = UserDto(id = "u1", nome = "F", email = "f@e.com", role = "admin", empresa = EmpresaDto("e1", "00000000000191", "Emp"))
        store.save("jwt-123", user)

        assertTrue(store.hasSession())
        assertEquals("jwt-123", store.token())
        assertEquals("admin", store.user()?.role)
        assertEquals("Emp", store.user()?.empresa?.razaoSocial)
    }

    @Test fun `clear removes everything`() = runTest {
        val store = PlatformTokenStore(FakeSecretStore(), json)
        store.save("jwt", UserDto(id = "u1"))
        store.clear()
        assertFalse(store.hasSession())
        assertNull(store.token())
        assertNull(store.user())
    }
}
