package com.licitaia.core.data.crypto

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/** [CipherEngine] real, sobre `net.zetetic:sqlcipher-android`. Só pode ser usado em runtime Android. */
class SqlCipherEngine : CipherEngine {

    override fun exportEncrypted(plain: File, target: File, passphrase: ByteArray): PlainSnapshot {
        SqlCipherLoader.ensureLoaded()
        // Sem chave e sem ENABLE_WRITE_AHEAD_LOGGING: uma única conexão, logo o ATTACH vale para todas as instruções.
        val db = SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, null)
        return db.use {
            // Traz para o arquivo principal tudo que estiver só no -wal e volta ao modo DELETE para que
            // -wal/-shm sejam removidos no fechamento (o Room reativa WAL ao abrir o banco cifrado).
            it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> c.moveToFirst() }
            it.rawQuery("PRAGMA journal_mode = DELETE", null).use { c -> c.moveToFirst() }
            val snapshot = snapshot(it)
            it.rawExecSQL("ATTACH DATABASE ? AS enc KEY ?", target.path, String(passphrase, Charsets.US_ASCII))
            try {
                it.rawQuery("SELECT sqlcipher_export('enc')", null).use { c -> c.moveToFirst() }
                it.rawExecSQL("PRAGMA enc.user_version = ${snapshot.userVersion}")
            } finally {
                it.rawExecSQL("DETACH DATABASE enc")
            }
            snapshot
        }
    }

    override fun verifyEncrypted(file: File, passphrase: ByteArray, expected: PlainSnapshot?): Boolean {
        SqlCipherLoader.ensureLoaded()
        val db = SQLiteDatabase.openDatabase(file.path, passphrase.copyOf(), null, SQLiteDatabase.OPEN_READWRITE, null)
        return db.use {
            val integrity = it.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (integrity != "ok") return@use false
            if (expected == null) return@use true
            val actual = snapshot(it)
            actual.userVersion == expected.userVersion && expected.rowCounts.all { (table, count) -> actual.rowCounts[table] == count }
        }
    }

    private fun snapshot(db: SQLiteDatabase): PlainSnapshot {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val counts = tables.associateWith { table ->
            db.rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
        return PlainSnapshot(userVersion = db.version, rowCounts = counts)
    }
}

/** Carrega a biblioteca nativa `libsqlcipher.so` uma única vez (obrigatório antes de qualquer classe do SQLCipher). */
object SqlCipherLoader {
    @Volatile private var loaded = false
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }
}
