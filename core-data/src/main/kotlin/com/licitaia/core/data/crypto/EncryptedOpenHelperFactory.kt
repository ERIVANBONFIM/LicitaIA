package com.licitaia.core.data.crypto

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Factory do Room que, na **primeira abertura** do banco (já em thread de I/O, não no `build()`):
 * 1. carrega a chave do [DatabaseKeyProvider] (Keystore);
 * 2. roda o [DatabaseEncryptionMigrator] (texto puro → SQLCipher, sem perda de dados), sob lock;
 * 3. delega ao `SupportOpenHelperFactory` do SQLCipher — ou, se a migração falhou, ao helper padrão
 *    (banco em claro, como antes), para nunca deixar o usuário sem acesso aos dados.
 *
 * O `.bak` em claro é apagado só depois da primeira abertura cifrada bem-sucedida.
 */
class EncryptedOpenHelperFactory(
    private val context: Context,
    private val keyProvider: DatabaseKeyProvider,
    private val migrator: DatabaseEncryptionMigrator = DatabaseEncryptionMigrator(SqlCipherEngine()) { Log.w(TAG, it) },
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = LazyHelper(configuration)

    private inner class LazyHelper(private val configuration: SupportSQLiteOpenHelper.Configuration) : SupportSQLiteOpenHelper {
        private val lock = Any()
        private var walEnabled = false
        private var delegate: SupportSQLiteOpenHelper? = null
        private var encrypted = false
        private var backupCleaned = false

        override val databaseName: String? get() = configuration.name

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            synchronized(lock) {
                walEnabled = enabled
                delegate?.setWriteAheadLoggingEnabled(enabled)
            }
        }

        override val writableDatabase: SupportSQLiteDatabase
            get() = open().writableDatabase.also { afterOpen() }

        override val readableDatabase: SupportSQLiteDatabase
            get() = open().readableDatabase.also { afterOpen() }

        override fun close() {
            synchronized(lock) { delegate?.close() }
        }

        private fun open(): SupportSQLiteOpenHelper = synchronized(lock) {
            delegate ?: createDelegate().also { delegate = it }
        }

        private fun createDelegate(): SupportSQLiteOpenHelper {
            val name = configuration.name ?: return FrameworkSQLiteOpenHelperFactory().create(configuration).also { it.setWriteAheadLoggingEnabled(walEnabled) }
            val dbFile = context.getDatabasePath(name)
            val key = keyProvider.load()
            val factory: SupportSQLiteOpenHelper.Factory = when (key) {
                is DatabaseKeyProvider.Key.Lost -> {
                    if (dbFile.exists() && !DatabaseEncryptionMigrator.isPlainSqlite(dbFile)) {
                        // Banco cifrado sem chave recuperável: não há como ler. Falhar alto é melhor do que apagar dados.
                        throw IllegalStateException("Chave do banco perdida no Keystore; banco cifrado não pode ser aberto.")
                    }
                    Log.w(TAG, "Chave do banco indisponível; abrindo banco em claro.")
                    FrameworkSQLiteOpenHelperFactory()
                }
                is DatabaseKeyProvider.Key.Available -> {
                    val result = migrator.migrateIfNeeded(dbFile, key.passphrase)
                    if (result == DatabaseEncryptionMigrator.Result.FAILED_KEPT_PLAIN) {
                        key.passphrase.fill(0)
                        FrameworkSQLiteOpenHelperFactory()
                    } else {
                        encrypted = true
                        SqlCipherLoader.ensureLoaded()
                        // O SQLCipher guarda a referência da passphrase para abrir novas conexões do pool; não pode ser zerada aqui.
                        SupportOpenHelperFactory(key.passphrase)
                    }
                }
            }
            return factory.create(configuration).also { it.setWriteAheadLoggingEnabled(walEnabled) }
        }

        /** Chamado após cada abertura; na primeira abertura cifrada bem-sucedida remove o `.bak` em claro. */
        private fun afterOpen() {
            synchronized(lock) {
                if (!encrypted || backupCleaned) return
                backupCleaned = true
            }
            val name = configuration.name ?: return
            runCatching { migrator.deleteBackupAfterSuccessfulOpen(context.getDatabasePath(name)) }
                .onFailure { Log.w(TAG, "Não foi possível remover a cópia em claro do banco: ${it.javaClass.simpleName}") }
        }
    }

    private companion object {
        const val TAG = "LicitaIA.DbCrypto"
    }
}
