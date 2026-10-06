package com.licitaia.core.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated encryption: unlike Keystore-only backups, survives device loss. */
object PortableBackupCipher {
    private val magic = "LICITAIA1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 12) { "Use uma senha de backup com pelo menos 12 caracteres." }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }
    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        require(bytes.size >= magic.size + 16 + 12 + 16 && bytes.take(magic.size).toByteArray().contentEquals(magic)) { "Arquivo de backup inválido." }
        val buffer = ByteBuffer.wrap(bytes)
        val prefix = ByteArray(magic.size).also(buffer::get)
        val salt = ByteArray(16).also(buffer::get)
        val iv = ByteArray(12).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(prefix + salt + iv)
        return cipher.doFinal(bytes.copyOfRange(buffer.position(), bytes.size))
    }
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
}
