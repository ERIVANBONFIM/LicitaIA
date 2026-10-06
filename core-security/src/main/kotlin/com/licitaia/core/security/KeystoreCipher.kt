package com.licitaia.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** AES-256-GCM com chave não exportável guardada no Android Keystore. */
@Singleton
class KeystoreCipher @Inject constructor() {

    private val lock = Any()

    /** Devolve Base64(tamanhoIv || iv || ciphertext+tag). */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + encrypted.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(encrypted, 0, out, 1 + iv.size, encrypted.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Lança exceção se o conteúdo foi adulterado ou a chave não existe mais. */
    fun decrypt(payload: String): String {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        require(raw.size > 1) { "Conteúdo cifrado inválido" }
        val ivSize = raw[0].toInt()
        require(ivSize in 12..16 && raw.size > 1 + ivSize) { "Conteúdo cifrado inválido" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, raw, 1, ivSize))
        val plain = cipher.doFinal(raw, 1 + ivSize, raw.size - 1 - ivSize)
        return String(plain, Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return@synchronized existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "licitaia_master_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
