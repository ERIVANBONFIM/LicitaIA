package com.licitaia.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test
    fun hashVerifiesOnlyTheRightPassword() {
        val stored = hasher.hash("demo1234", iterations = 2_000)
        assertTrue(hasher.verify("demo1234", stored))
        assertFalse(hasher.verify("demo12345", stored))
        assertFalse(stored.contains("demo1234"))
    }

    @Test
    fun samePasswordGetsDifferentSalts() {
        assertNotEquals(hasher.hash("abc12345", 2_000), hasher.hash("abc12345", 2_000))
    }

    @Test
    fun malformedHashNeverVerifies() {
        assertFalse(hasher.verify("x", ""))
        assertFalse(hasher.verify("x", "pbkdf2-sha256:abc:!!:!!"))
        assertFalse(hasher.verify("x", "pbkdf2-sha256:2000::"))
    }
}
