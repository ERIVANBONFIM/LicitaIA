package com.licitaia.core.data.crypto

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Conteúdo mínimo do banco em claro capturado durante a exportação, usado para conferir a cópia cifrada
 * antes de qualquer renomeação: `user_version` (versão do schema Room) e contagem de linhas por tabela.
 */
data class PlainSnapshot(val userVersion: Int, val rowCounts: Map<String, Long>)

/** Operações que exigem o SQLCipher nativo. Isoladas para que a lógica de decisão/renomeio seja testável na JVM. */
interface CipherEngine {
    /**
     * Abre [plain] sem chave, faz checkpoint do WAL, exporta tudo (`sqlcipher_export`) para [target] cifrado com
     * [passphrase] e copia `user_version`. Lança exceção em qualquer falha. Nunca altera os dados de [plain].
     */
    fun exportEncrypted(plain: File, target: File, passphrase: ByteArray): PlainSnapshot

    /**
     * Abre [file] com [passphrase], roda `PRAGMA integrity_check` e confere [expected] (quando informado).
     * Devolve `false` (ou lança) se a cópia não for utilizável.
     */
    fun verifyEncrypted(file: File, passphrase: ByteArray, expected: PlainSnapshot?): Boolean
}

/**
 * Migra, uma única vez, o banco Room em texto puro para SQLCipher **sem perder dados**:
 *
 * 1. `licitaia.db` em claro → exporta para `licitaia_enc.db` cifrado (o original não é tocado);
 * 2. confere integridade e contagens da cópia;
 * 3. só então renomeia `licitaia.db` → `licitaia_plain.bak` e `licitaia_enc.db` → `licitaia.db`.
 *
 * Em qualquer falha o original permanece intacto e o app segue abrindo-o em claro ([Result.FAILED_KEPT_PLAIN]).
 * O `.bak` só é apagado por [deleteBackupAfterSuccessfulOpen], depois de o Room abrir o banco cifrado com sucesso.
 * Reentrante após queda no meio do processo (ver [recoverInterrupted]). Deve rodar antes de o Room abrir o banco,
 * protegido por lock pelo chamador.
 */
class DatabaseEncryptionMigrator(
    private val engine: CipherEngine,
    private val log: (String) -> Unit = {},
) {
    enum class Result { ABSENT, ALREADY_ENCRYPTED, MIGRATED, RECOVERED, FAILED_KEPT_PLAIN }

    fun migrateIfNeeded(dbFile: File, passphrase: ByteArray): Result {
        val enc = encryptedFile(dbFile)
        val bak = backupFile(dbFile)
        var result: Result? = null
        if (!dbFile.exists()) {
            result = recoverInterrupted(dbFile, enc, bak, passphrase)
            if (result != null && result != Result.FAILED_KEPT_PLAIN) return result
        }
        if (!dbFile.exists() || dbFile.length() < HEADER.size) {
            deleteWithSidecars(enc)
            return Result.ABSENT
        }
        if (!isPlainSqlite(dbFile)) {
            deleteWithSidecars(enc)
            return Result.ALREADY_ENCRYPTED
        }
        deleteWithSidecars(enc)
        return try {
            val snapshot = engine.exportEncrypted(dbFile, enc, passphrase)
            check(enc.exists() && enc.length() > 0) { "cópia cifrada não foi criada" }
            check(engine.verifyEncrypted(enc, passphrase, snapshot)) { "cópia cifrada não passou na verificação" }
            check(!isPlainSqlite(enc)) { "cópia não está cifrada" }
            swapIn(dbFile, enc, bak)
            log("Banco migrado para SQLCipher (${snapshot.rowCounts.values.sum()} linhas, schema v${snapshot.userVersion}).")
            Result.MIGRATED
        } catch (e: Exception) {
            log("Migração para SQLCipher adiada; banco original mantido em claro: ${e.javaClass.simpleName}: ${e.message}")
            // Desfaz um swap parcial: o original volta a ser licitaia.db; a cópia cifrada é descartada.
            if (!dbFile.exists() && bak.exists()) moveWithSidecars(bak, dbFile)
            deleteWithSidecars(enc)
            Result.FAILED_KEPT_PLAIN
        }
    }

    /** Após a primeira abertura cifrada bem-sucedida: remove `licitaia_plain.bak` (e eventuais -wal/-shm/-journal). */
    fun deleteBackupAfterSuccessfulOpen(dbFile: File) {
        val bak = backupFile(dbFile)
        if (bak.exists()) {
            deleteWithSidecars(bak)
            log("Cópia em claro do banco removida após abertura cifrada.")
        }
    }

    /**
     * O processo caiu entre as duas renomeações de [swapIn]: não há `licitaia.db`, mas existem `.bak` e `_enc`.
     * Se a cópia cifrada ainda abre e passa na integridade, conclui o swap; caso contrário devolve o original.
     * Devolve `null` quando não há nada a recuperar.
     */
    private fun recoverInterrupted(dbFile: File, enc: File, bak: File, passphrase: ByteArray): Result? {
        if (!bak.exists()) {
            deleteWithSidecars(enc)
            return null
        }
        val encOk = enc.exists() && runCatching { engine.verifyEncrypted(enc, passphrase, null) }.getOrDefault(false)
        return if (encOk) {
            moveWithSidecars(enc, dbFile)
            log("Migração SQLCipher interrompida foi concluída na reabertura.")
            Result.RECOVERED
        } else {
            deleteWithSidecars(enc)
            moveWithSidecars(bak, dbFile)
            log("Migração SQLCipher interrompida foi desfeita; banco original restaurado.")
            Result.FAILED_KEPT_PLAIN
        }
    }

    private fun swapIn(dbFile: File, enc: File, bak: File) {
        deleteWithSidecars(bak)
        moveWithSidecars(dbFile, bak)
        try {
            moveWithSidecars(enc, dbFile)
        } catch (e: Exception) {
            moveWithSidecars(bak, dbFile)
            throw e
        }
    }

    companion object {
        /** Cabeçalho de todo arquivo SQLite em claro; um banco SQLCipher começa com o salt aleatório. */
        private val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val SIDECARS = listOf("-wal", "-shm", "-journal")

        fun encryptedFile(dbFile: File) = File(dbFile.parentFile, dbFile.nameWithoutExtension + "_enc.db")
        fun backupFile(dbFile: File) = File(dbFile.parentFile, dbFile.nameWithoutExtension + "_plain.bak")

        /** `true` se os 16 primeiros bytes são o cabeçalho SQLite padrão (arquivo não cifrado). */
        fun isPlainSqlite(file: File): Boolean {
            if (!file.isFile || file.length() < HEADER.size) return false
            val head = ByteArray(HEADER.size)
            RandomAccessFile(file, "r").use { it.readFully(head) }
            return head.contentEquals(HEADER)
        }

        /**
         * Passphrase no formato de chave bruta do SQLCipher (`x'<64 hex>'`): 32 bytes usados diretamente como
         * chave, sem PBKDF2. A mesma forma serve para `sqlite3_key` e para `ATTACH ... KEY ?`.
         */
        fun rawKeyPassphrase(keyHex: String): ByteArray {
            require(keyHex.length == 64 && keyHex.all { it in '0'..'9' || it in 'a'..'f' }) { "chave do banco inválida" }
            return "x'$keyHex'".toByteArray(Charsets.US_ASCII)
        }

        private fun moveWithSidecars(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            SIDECARS.forEach { suffix ->
                val side = File(from.path + suffix)
                if (side.exists()) Files.move(side.toPath(), File(to.path + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun deleteWithSidecars(file: File) {
            file.delete()
            SIDECARS.forEach { File(file.path + it).delete() }
        }
    }
}
