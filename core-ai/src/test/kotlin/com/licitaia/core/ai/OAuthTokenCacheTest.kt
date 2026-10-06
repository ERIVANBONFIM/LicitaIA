package com.licitaia.core.ai

import com.licitaia.core.security.SecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthTokenCacheTest {
    /** Cofre em memória: o teste só exercita a lógica de expiração, não o Keystore. */
    private class FakeSecretStore : SecretStore {
        val map = linkedMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun contains(key: String): Boolean = key in map
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private var now = 1_000_000L
    private val secrets = FakeSecretStore()
    private val cache = OAuthTokenCache(secrets, "tok", "exp") { now }

    @Test fun storedTokenIsValidUntilTtlMinusSkew() = runTest {
        cache.store("abc", ttlMs = 10 * 60_000)
        assertEquals("abc", cache.valid())
        now += 8 * 60_000
        assertEquals("abc", cache.valid())
        now += 1 * 60_000 + 1          // dentro da margem de 60 s antes do vencimento
        assertNull(cache.valid())
        assertTrue("o consentimento continua registrado", cache.exists())
    }

    @Test fun defaultTtlIsShorterThanGoogleHour() {
        assertTrue(OAuthTokenCache.DEFAULT_TTL_MS < 60 * 60_000)
        assertTrue(OAuthTokenCache.DEFAULT_TTL_MS >= 30 * 60_000)
    }

    @Test fun isExpiredAppliesSkew() {
        val expiresAt = 100_000L
        assertFalse(OAuthTokenCache.isExpired(expiresAt, nowMs = expiresAt - OAuthTokenCache.SKEW_MS - 1))
        assertTrue(OAuthTokenCache.isExpired(expiresAt, nowMs = expiresAt - OAuthTokenCache.SKEW_MS))
        assertTrue(OAuthTokenCache.isExpired(expiresAt, nowMs = expiresAt + 1))
    }

    @Test fun invalidateKeepsConsentButRejectsToken() = runTest {
        cache.store("abc")
        cache.invalidate()
        assertNull(cache.valid())
        assertTrue(cache.exists())
        assertEquals("abc", secrets.map["tok"])
    }

    @Test fun clearRemovesTokenAndExpiry() = runTest {
        cache.store("abc")
        cache.clear()
        assertFalse(cache.exists())
        assertNull(cache.valid())
        assertTrue(secrets.map.isEmpty())
    }

    @Test fun missingOrCorruptExpiryIsTreatedAsExpired() = runTest {
        secrets.put("tok", "abc")
        assertNull(cache.valid())
        secrets.put("exp", "not-a-number")
        assertNull(cache.valid())
        assertTrue(cache.exists())
    }

    @Test fun blankTokenIsRejected() = runTest {
        var failed = false
        try { cache.store("  ") } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertFalse(cache.exists())
    }
}
