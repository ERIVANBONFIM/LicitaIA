package com.licitaia.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PBKDF2WithHmacSHA256 com salt aleatório por senha.
 * Formato armazenado: `pbkdf2-sha256:<iterações>:<salt b64>:<hash b64>`.
 */
@Singleton
class PasswordHasher @Inject constructor() {

    private val random = SecureRandom()

    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt, iterations, KEY_BITS)
        val enc = Base64.getEncoder().withoutPadding()
        return listOf(PREFIX, iterations.toString(), enc.encodeToString(salt), enc.encodeToString(derived))
            .joinToString(SEPARATOR)
    }

    /** Comparação em tempo constante. Hash malformado nunca confere. */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1_000..5_000_000 } ?: return false
        return try {
            val dec = Base64.getDecoder()
            val salt = dec.decode(parts[2])
            val expected = dec.decode(parts[3])
            if (expected.isEmpty()) return false
            val actual = derive(password, salt, iterations, expected.size * 8)
            MessageDigest.isEqual(expected, actual)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, bits)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 120_000
        private const val PREFIX = "pbkdf2-sha256"
        private const val SEPARATOR = ":"
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}
