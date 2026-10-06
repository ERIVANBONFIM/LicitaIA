package com.licitaia.core.data.crypto

import com.licitaia.core.security.SecretStore
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chave do banco SQLCipher: 32 bytes aleatórios gerados uma única vez e guardados **cifrados pelo Android Keystore**
 * no [SecretStore] (`db.key`, em hex). Nunca em texto puro em disco.
 */
@Singleton
class DatabaseKeyProvider @Inject constructor(private val secretStore: SecretStore) {

    sealed interface Key {
        /** Passphrase no formato de chave bruta do SQLCipher; pertence ao chamador, que deve zerá-la quando possível. */
        class Available(val passphrase: ByteArray) : Key
        /** Havia uma chave gravada, mas o Keystore não consegue mais decifrá-la (chave mestra invalidada). */
        data object Lost : Key
    }

    /** Síncrono: chamado na primeira abertura do banco, já fora da thread principal. */
    fun load(): Key = runBlocking {
        val existed = secretStore.contains(ENTRY)
        val stored = secretStore.get(ENTRY)
        when {
            stored != null -> Key.Available(DatabaseEncryptionMigrator.rawKeyPassphrase(stored))
            existed -> Key.Lost
            else -> {
                val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val hex = raw.joinToString("") { "%02x".format(it) }
                raw.fill(0)
                secretStore.put(ENTRY, hex)
                Key.Available(DatabaseEncryptionMigrator.rawKeyPassphrase(hex))
            }
        }
    }

    private companion object {
        const val ENTRY = "db.key"
    }
}
