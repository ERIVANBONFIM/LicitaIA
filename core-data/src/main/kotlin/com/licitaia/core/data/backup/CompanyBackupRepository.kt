package com.licitaia.core.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.security.PortableBackupCipher
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Company-only, additive restore. Never imports identities, credentials or live portal sessions. */
@Singleton
class CompanyBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val room: LicitaDatabase,
    private val holder: SessionHolder,
) : BackupRepository {
    private val companyTables = listOf("radars", "tenders", "documents", "proposals", "competition_records", "audit_events")
    private val limit = 64 * 1024 * 1024

    private companion object {
        /** Versão de schema gravada no backup = versão atual do Room; restauração aceita versões anteriores. */
        const val BACKUP_SCHEMA = 5
    }

    private fun session() = requireNotNull(holder.current) { "Entre em sua conta." }.also {
        require(!it.user.demo && it.user.role == UserRole.ADMIN && it.activeCompany.id in it.user.companyIds) { "Backup e restauração exigem administrador da empresa ativa." }
    }

    override suspend fun exportBackup(destination: String, password: CharArray) = withContext(Dispatchers.IO) {
        runCatching {
            val auth = session()
            val db = room.openHelper.writableDatabase
            val tables = JSONObject()
            db.beginTransaction()
            try {
                companyTables.forEach { table -> tables.put(table, rows(db, "SELECT * FROM $table WHERE companyId = ?", arrayOf(auth.activeCompany.id))) }
                tables.put("tender_analyses", rows(db, "SELECT * FROM tender_analyses WHERE tenderId IN (SELECT id FROM tenders WHERE companyId = ?)", arrayOf(auth.activeCompany.id)))
                tables.put("opportunities", rows(db, "SELECT * FROM opportunities WHERE id IN (SELECT opportunityId FROM tenders WHERE companyId = ?)", arrayOf(auth.activeCompany.id)))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val files = JSONObject()
            listOf("documents" to "attachmentUri", "proposals" to "pdfPath").forEach { (table, column) ->
                val records = tables.getJSONArray(table)
                for (i in 0 until records.length()) {
                    val row = records.getJSONObject(i)
                    if (!row.isNull(column)) {
                        val path = row.getString(column)
                        files.put(path, Base64.encodeToString(readAttachment(path), Base64.NO_WRAP))
                    }
                }
            }
            val editais = JSONObject()
            val tenders = tables.getJSONArray("tenders")
            for (i in 0 until tenders.length()) {
                val id = tenders.getJSONObject(i).getLong("id")
                val filesForTender = JSONObject()
                listOf("pdf", "txt").forEach { extension ->
                    val file = File(context.filesDir, "editais/${auth.activeCompany.id}/$id.$extension")
                    if (file.exists()) filesForTender.put(extension, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                }
                editais.put(id.toString(), filesForTender)
            }
            val payload = JSONObject().put("format", 1).put("schema", BACKUP_SCHEMA).put("cnpj", auth.activeCompany.cnpj.filter(Char::isDigit))
                .put("createdAt", System.currentTimeMillis()).put("tables", tables).put("files", files).put("editais", editais).toString().toByteArray()
            require(payload.size <= limit) { "Backup excede 64 MB. Reduza o tamanho dos anexos." }
            require(holder.current == auth) { "Sessão alterada; tente novamente." }
            val encrypted = PortableBackupCipher.encrypt(payload, password)
            context.contentResolver.openOutputStream(Uri.parse(destination), "wt")!!.use { it.write(encrypted) }
            "Backup cifrado da empresa criado. Guarde o arquivo e a senha em locais seguros."
        }.also { password.fill('\u0000') }
    }

    override suspend fun restoreBackup(source: String, password: CharArray) = withContext(Dispatchers.IO) {
        val createdFiles = mutableListOf<File>()
        runCatching {
            val auth = session()
            val encrypted = context.contentResolver.openInputStream(Uri.parse(source))!!.use { it.readBytesBounded(limit + 1024) }
            val payload = JSONObject(String(PortableBackupCipher.decrypt(encrypted, password), Charsets.UTF_8))
            require(payload.getInt("format") == 1 && payload.getInt("schema") in 2..BACKUP_SCHEMA) { "Versão de backup não suportada." }
            require(payload.getString("cnpj") == auth.activeCompany.cnpj.filter(Char::isDigit)) { "O backup pertence a outro CNPJ. Crie ou selecione a empresa correspondente." }
            val tables = payload.getJSONObject("tables")
            val attachments = payload.getJSONObject("files")
            val db = room.openHelper.writableDatabase
            val prefix = "restored:${UUID.randomUUID()}:"
            val opportunityIds = mutableMapOf<String, String>()
            val tenderIds = mutableMapOf<Long, Long>()
            val root = File(context.filesDir, "restored/${UUID.randomUUID()}").also { it.mkdirs() }
            var count = 0
            db.beginTransaction()
            try {
                val order = listOf("opportunities", "radars", "tenders", "tender_analyses", "documents", "proposals", "competition_records", "audit_events")
                order.forEach { table ->
                    val columns = db.query("PRAGMA table_info($table)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
                    val records = tables.getJSONArray(table)
                    require(records.length() <= 50_000) { "Backup excede limite de registros." }
                    for (i in 0 until records.length()) {
                        val row = records.getJSONObject(i)
                        // Backups de versões anteriores podem não ter colunas novas: elas recebem o DEFAULT da tabela.
                        require(columns.containsAll(row.keys().asSequence().toSet())) { "Estrutura incompatível: $table" }
                        val oldId = if (row.has("id") && table != "opportunities") row.getLong("id") else 0L
                        if (table == "opportunities") {
                            val old = row.getString("id")
                            val new = prefix + old
                            opportunityIds[old] = new
                            row.put("id", new)
                        } else if (row.has("id")) row.remove("id")
                        if (row.has("companyId")) row.put("companyId", auth.activeCompany.id)
                        if (table == "tenders") row.put("opportunityId", opportunityIds[row.getString("opportunityId")] ?: (prefix + row.getString("opportunityId")))
                        if (row.has("tenderId")) row.put("tenderId", requireNotNull(tenderIds[row.getLong("tenderId")]) { "Referência de licitação inválida." })
                        if (table == "proposals") {
                            row.put("status", "RASCUNHO").put("approvedBy", JSONObject.NULL).put("approvedAt", JSONObject.NULL)
                        }
                        val column = when (table) { "documents" -> "attachmentUri"; "proposals" -> "pdfPath"; else -> null }
                        if (column != null && !row.isNull(column)) {
                            val data = Base64.decode(attachments.getString(row.getString(column)), Base64.DEFAULT)
                            val file = File(root, UUID.randomUUID().toString()).also { createdFiles.add(it); it.writeBytes(data) }
                            row.put(column, if (table == "documents") androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.licitaia.fileprovider", file).toString() else file.absolutePath)
                        }
                        val values = values(row)
                        val newId = db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                        if (table == "tenders") tenderIds[oldId] = newId
                        count++
                    }
                }
                val editais = payload.getJSONObject("editais")
                tenderIds.forEach { (old, new) ->
                    val item = editais.optJSONObject(old.toString()) ?: JSONObject()
                    listOf("pdf", "txt").forEach { extension ->
                        if (item.has(extension)) {
                            val dir = File(context.filesDir, "editais/${auth.activeCompany.id}").also { it.mkdirs() }
                            File(dir, "$new.$extension").also { createdFiles.add(it); it.writeBytes(Base64.decode(item.getString(extension), Base64.DEFAULT)) }
                        }
                    }
                }
                require(holder.current == auth) { "Sessão alterada; restauração cancelada." }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            room.invalidationTracker.refreshVersionsAsync()
            "Restaurados $count registros por adição. Dados anteriores preservados; propostas voltam a rascunho."
        }.onFailure { createdFiles.forEach { it.delete() } }.also { password.fill('\u0000') }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String, args: Array<out Any>): JSONArray = db.query(sql, args).use { cursor ->
        JSONArray().apply {
            while (cursor.moveToNext()) put(JSONObject().apply {
                cursor.columnNames.forEachIndexed { i, name -> put(name, when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    else -> cursor.getString(i)
                }) }
            })
        }
    }
    private fun values(row: JSONObject) = ContentValues().apply {
        row.keys().forEach { key -> when (val value = row.get(key)) {
            JSONObject.NULL -> putNull(key)
            is Number -> if (value is Double || value is Float) put(key, value.toDouble()) else put(key, value.toLong())
            else -> put(key, value.toString())
        } }
    }
    private fun readAttachment(path: String): ByteArray {
        val uri = Uri.parse(path)
        return if (uri.scheme == "content" || uri.scheme == "file") context.contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(limit) }
        else File(path).inputStream().use { it.readBytesBounded(limit) }
    }
    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= max) { "Arquivo excede 64 MB." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
