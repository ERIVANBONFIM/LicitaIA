package com.licitaia.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Cofre de segredos (chaves de API, senhas de portal, hash do PIN). Nunca guarda texto puro. */
interface SecretStore {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun contains(key: String): Boolean
    suspend fun remove(key: String)
}

/** SharedPreferences privado cujos valores são cifrados pelo [KeystoreCipher]. */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) : SecretStore {

    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override suspend fun put(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val encrypted = cipher.encrypt(value)
            prefs.edit().putString(key, encrypted).commit()
        }
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        try {
            cipher.decrypt(stored)
        } catch (e: Exception) {
            // Chave do Keystore invalidada (ex.: restauração de backup): o segredo é irrecuperável.
            prefs.edit().remove(key).commit()
            null
        }
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(key)
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(key).commit()
        }
    }

    private companion object {
        const val FILE = "licitaia_secure_store"
    }
}
