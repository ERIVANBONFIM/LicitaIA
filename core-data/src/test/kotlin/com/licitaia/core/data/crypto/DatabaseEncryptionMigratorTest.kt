package com.licitaia.core.data.crypto

import com.licitaia.core.data.crypto.DatabaseEncryptionMigrator.Result
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseEncryptionMigratorTest {
    @get:Rule val folder = TemporaryFolder()

    private val plainHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val plainContent = plainHeader + "dados em claro".toByteArray()
    private val encryptedContent = ByteArray(64) { (it * 37 + 11).toByte() } // sem cabeçalho SQLite
    private val passphrase = DatabaseEncryptionMigrator.rawKeyPassphrase("ab".repeat(32))
    private lateinit var db: File
    private lateinit var enc: File
    private lateinit var bak: File
    private val logs = mutableListOf<String>()

    /** Motor falso: "cifrar" = escrever bytes sem cabeçalho SQLite; configurável para falhar. */
    private inner class FakeEngine(
        var failExport: Boolean = false,
        var verifyResult: Boolean = true,
        var writeTarget: Boolean = true,
    ) : CipherEngine {
        var exports = 0
        var verifications = 0
        override fun exportEncrypted(plain: File, target: File, passphrase: ByteArray): PlainSnapshot {
            exports++
            assertArrayEquals(this@DatabaseEncryptionMigratorTest.passphrase, passphrase)
            if (failExport) throw IllegalStateException("falha simulada")
            if (writeTarget) target.writeBytes(encryptedContent)
            return PlainSnapshot(userVersion = 5, rowCounts = mapOf("tenders" to 3L))
        }
        override fun verifyEncrypted(file: File, passphrase: ByteArray, expected: PlainSnapshot?): Boolean {
            verifications++
            return verifyResult && file.readBytes().contentEquals(encryptedContent)
        }
    }

    @Before fun setUp() {
        db = File(folder.root, "licitaia.db")
        enc = DatabaseEncryptionMigrator.encryptedFile(db)
        bak = DatabaseEncryptionMigrator.backupFile(db)
        assertEquals("licitaia_enc.db", enc.name)
        assertEquals("licitaia_plain.bak", bak.name)
    }

    private fun migrator(engine: CipherEngine) = DatabaseEncryptionMigrator(engine, logs::add)

    @Test fun rawKeyPassphraseUsesSqlcipherRawKeyForm() {
        val hex = "0f".repeat(32)
        assertEquals("x'$hex'", String(DatabaseEncryptionMigrator.rawKeyPassphrase(hex), Charsets.US_ASCII))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawKeyPassphraseRejectsShortOrNonHexKeys() {
        DatabaseEncryptionMigrator.rawKeyPassphrase("zz".repeat(32))
    }

    @Test fun detectsPlainSqliteByHeader() {
        db.writeBytes(plainContent)
        assertTrue(DatabaseEncryptionMigrator.isPlainSqlite(db))
        db.writeBytes(encryptedContent)
        assertFalse(DatabaseEncryptionMigrator.isPlainSqlite(db))
        db.writeBytes(ByteArray(0))
        assertFalse(DatabaseEncryptionMigrator.isPlainSqlite(db))
    }

    @Test fun absentDatabaseNeedsNoMigrationAndClearsStaleCopy() {
        enc.writeBytes(encryptedContent)
        assertEquals(Result.ABSENT, migrator(FakeEngine()).migrateIfNeeded(db, passphrase))
        assertFalse(db.exists())
        assertFalse(enc.exists())
    }

    @Test fun plainDatabaseIsExportedVerifiedAndSwappedKeepingBackup() {
        db.writeBytes(plainContent)
        File(db.path + "-wal").writeBytes(byteArrayOf(1, 2, 3))
        File(db.path + "-shm").writeBytes(byteArrayOf(4))
        val engine = FakeEngine()

        assertEquals(Result.MIGRATED, migrator(engine).migrateIfNeeded(db, passphrase))

        assertEquals(1, engine.exports)
        assertEquals(1, engine.verifications)
        assertArrayEquals(encryptedContent, db.readBytes())
        assertArrayEquals(plainContent, bak.readBytes())
        assertFalse(enc.exists())
        // Arquivos auxiliares do banco em claro acompanham o .bak e não ficam ao lado do banco cifrado.
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
        assertTrue(File(bak.path + "-wal").exists())
        assertTrue(File(bak.path + "-shm").exists())
    }

    @Test fun encryptedDatabaseIsLeftAloneAndBackupSurvivesUntilConfirmedOpen() {
        db.writeBytes(encryptedContent)
        bak.writeBytes(plainContent)
        val engine = FakeEngine()
        assertEquals(Result.ALREADY_ENCRYPTED, migrator(engine).migrateIfNeeded(db, passphrase))
        assertEquals(0, engine.exports)
        assertArrayEquals(encryptedContent, db.readBytes())
        assertTrue(bak.exists())
    }

    @Test fun backupIsDeletedOnlyAfterSuccessfulEncryptedOpen() {
        bak.writeBytes(plainContent)
        File(bak.path + "-wal").writeBytes(byteArrayOf(1))
        migrator(FakeEngine()).deleteBackupAfterSuccessfulOpen(db)
        assertFalse(bak.exists())
        assertFalse(File(bak.path + "-wal").exists())
    }

    @Test fun exportFailureKeepsOriginalIntactAndRemovesPartialCopy() {
        db.writeBytes(plainContent)
        val engine = FakeEngine(failExport = true)
        assertEquals(Result.FAILED_KEPT_PLAIN, migrator(engine).migrateIfNeeded(db, passphrase))
        assertArrayEquals(plainContent, db.readBytes())
        assertFalse(enc.exists())
        assertFalse(bak.exists())
        assertTrue(logs.any { it.contains("original mantido") })
    }

    @Test fun verificationFailureKeepsOriginalIntact() {
        db.writeBytes(plainContent)
        val engine = FakeEngine(verifyResult = false)
        assertEquals(Result.FAILED_KEPT_PLAIN, migrator(engine).migrateIfNeeded(db, passphrase))
        assertArrayEquals(plainContent, db.readBytes())
        assertFalse(enc.exists())
        assertFalse(bak.exists())
    }

    @Test fun missingCopyAfterExportKeepsOriginalIntact() {
        db.writeBytes(plainContent)
        assertEquals(Result.FAILED_KEPT_PLAIN, migrator(FakeEngine(writeTarget = false)).migrateIfNeeded(db, passphrase))
        assertArrayEquals(plainContent, db.readBytes())
    }

    @Test fun exportThatProducesPlaintextIsRejected() {
        db.writeBytes(plainContent)
        val engine = object : CipherEngine {
            override fun exportEncrypted(plain: File, target: File, passphrase: ByteArray): PlainSnapshot {
                target.writeBytes(plainContent); return PlainSnapshot(5, emptyMap())
            }
            override fun verifyEncrypted(file: File, passphrase: ByteArray, expected: PlainSnapshot?) = true
        }
        assertEquals(Result.FAILED_KEPT_PLAIN, migrator(engine).migrateIfNeeded(db, passphrase))
        assertArrayEquals(plainContent, db.readBytes())
        assertFalse(enc.exists())
    }

    @Test fun interruptedSwapIsCompletedWhenEncryptedCopyIsValid() {
        // Queda entre "licitaia.db -> .bak" e "_enc.db -> licitaia.db".
        bak.writeBytes(plainContent)
        enc.writeBytes(encryptedContent)
        val engine = FakeEngine()
        assertEquals(Result.RECOVERED, migrator(engine).migrateIfNeeded(db, passphrase))
        assertEquals(0, engine.exports)
        assertArrayEquals(encryptedContent, db.readBytes())
        assertTrue(bak.exists())
        assertFalse(enc.exists())
    }

    @Test fun interruptedSwapIsRolledBackAndRetriedWhenCopyIsInvalid() {
        bak.writeBytes(plainContent)
        enc.writeBytes(byteArrayOf(9, 9, 9))
        val engine = FakeEngine()
        // Restaura o original e, no mesmo passo, refaz a migração a partir dele.
        assertEquals(Result.MIGRATED, migrator(engine).migrateIfNeeded(db, passphrase))
        assertEquals(1, engine.exports)
        assertArrayEquals(encryptedContent, db.readBytes())
        assertArrayEquals(plainContent, bak.readBytes())
    }

    @Test fun interruptedSwapWithFailingEngineRestoresPlainDatabase() {
        bak.writeBytes(plainContent)
        enc.writeBytes(byteArrayOf(9, 9, 9))
        assertEquals(Result.FAILED_KEPT_PLAIN, migrator(FakeEngine(failExport = true)).migrateIfNeeded(db, passphrase))
        assertArrayEquals(plainContent, db.readBytes())
        assertFalse(enc.exists())
    }

    @Test fun secondRunAfterMigrationIsNoOp() {
        db.writeBytes(plainContent)
        val engine = FakeEngine()
        val migrator = migrator(engine)
        assertEquals(Result.MIGRATED, migrator.migrateIfNeeded(db, passphrase))
        assertEquals(Result.ALREADY_ENCRYPTED, migrator.migrateIfNeeded(db, passphrase))
        assertEquals(1, engine.exports)
    }
}
