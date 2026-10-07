package com.licitaia.core.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.security.PortableBackupCipher
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
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
    private val companyTables = listOf("radars", "tenders", "documents", "proposals", "competition_records", "audit_events", "edital_questions", "opportunity_flags", "tender_status_watch")
    private val limit = 64 * 1024 * 1024

    private companion object {
        /**
         * Versão de schema gravada no backup = versão atual do Room; restauração aceita versões anteriores.
         * v8 só acrescentou o cache `relevance_scores`, que NÃO entra no backup (é refeito pela IA).
         * v9 acrescentou `radars.showNoDispute` e `opportunities.noDispute` (backups antigos recebem o DEFAULT 0).
         * v10 só acrescentou o cache do Compras.gov.br (`comprasgov_rows`, `comprasgov_sync`), que NÃO entra no backup
         * (é baixado de novo das fontes públicas).
         * v11 só acrescentou `portal_my_tenders` (lidas de novo da sessão do Comprasnet) e `portal_robot_plans`
         * (configuração operacional do robô); nenhuma das duas entra no backup.
         * v12 só acrescentou colunas em `companies` (endereço, contato, representante legal, banco), tabela que NÃO
         * entra no backup (o cadastro da empresa fica no aparelho de destino); backups 2..11 continuam aceitos.
         * v13 acrescentou `edital_questions` (histórico do "Pergunte ao edital"), que ENTRA no backup e é restaurada com o
         * novo id da licitação; backups 2..12 não têm a tabela e são restaurados sem ela.
         * v14 acrescentou `opportunities.uasg`/`officialSituation`, `tenders.uasg`/`officialSituation` (backups antigos
         * recebem NULL) e as tabelas por empresa `opportunity_flags` (descartadas/vistas; restauradas sem sobrescrever as
         * locais) e `tender_status_watch` (último estado oficial, com o novo id da licitação), que ENTRAM no backup;
         * `opportunity_first_seen` é global e NÃO entra. Backups 2..13 continuam aceitos.
         */
        const val BACKUP_SCHEMA = 14

        /** Tabelas que podem faltar em backups de versões anteriores. */
        val OPTIONAL_TABLES = setOf("edital_questions", "opportunity_flags", "tender_status_watch")
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
            // Nome e MIME originais de cada anexo, para restaurar com a extensão certa (formato 1, campo opcional).
            val fileMeta = JSONObject()
            listOf("documents" to "attachmentUri", "proposals" to "pdfPath").forEach { (table, column) ->
                val records = tables.getJSONArray(table)
                for (i in 0 until records.length()) {
                    val row = records.getJSONObject(i)
                    if (!row.isNull(column)) {
                        val path = row.getString(column)
                        files.put(path, Base64.encodeToString(readAttachment(path), Base64.NO_WRAP))
                        attachmentMeta(path)?.let { fileMeta.put(path, it) }
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
                .put("createdAt", System.currentTimeMillis()).put("tables", tables).put("files", files).put("fileMeta", fileMeta).put("editais", editais).toString().toByteArray()
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
            // Backups anteriores a este campo não têm metadados: a extensão é inferida do caminho ou do conteúdo.
            val fileMeta = payload.optJSONObject("fileMeta") ?: JSONObject()
            val db = room.openHelper.writableDatabase
            val prefix = "restored:${UUID.randomUUID()}:"
            val opportunityIds = mutableMapOf<String, String>()
            val tenderIds = mutableMapOf<Long, Long>()
            val root = File(context.filesDir, "restored/${UUID.randomUUID()}").also { it.mkdirs() }
            var count = 0
            db.beginTransaction()
            try {
                val order = listOf(
                    "opportunities", "radars", "tenders", "tender_analyses", "tender_status_watch", "edital_questions", "opportunity_flags", "documents", "proposals", "competition_records", "audit_events",
                )
                order.forEach { table ->
                    val columns = db.query("PRAGMA table_info($table)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
                    // Tabelas criadas depois do backup (ex.: edital_questions, v13) não existem em backups antigos.
                    val records = if (table in OPTIONAL_TABLES) tables.optJSONArray(table) ?: JSONArray() else tables.getJSONArray(table)
                    require(records.length() <= 50_000) { "Backup excede limite de registros." }
                    val auditRows = mutableListOf<Pair<Long, JSONObject>>()
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
                            val original = row.getString(column)
                            val data = Base64.decode(attachments.getString(original), Base64.DEFAULT)
                            val file = File(root, restoredFileName(original, fileMeta.optJSONObject(original), data)).also { createdFiles.add(it); it.writeBytes(data) }
                            row.put(column, if (table == "documents") androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.licitaia.fileprovider", file).toString() else file.absolutePath)
                        }
                        if (table == "audit_events") {
                            // Inseridos depois, re-encadeados a partir da cabeça local (ver AuditRestoreChain).
                            auditRows.add(oldId to row)
                            continue
                        }
                        val values = values(row)
                        // Marcas de oportunidade: não sobrescreve as do aparelho (restauração por adição).
                        val conflict = if (table == "opportunity_flags") SQLiteDatabase.CONFLICT_IGNORE else SQLiteDatabase.CONFLICT_ABORT
                        val newId = db.insert(table, conflict, values)
                        if (table == "tenders") tenderIds[oldId] = newId
                        count++
                    }
                    if (auditRows.isNotEmpty()) count += insertRechainedAudit(db, auditRows)
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
    /** `{name, mime}` do anexo original; `null` se nada puder ser descoberto (nunca falha a exportação). */
    private fun attachmentMeta(path: String): JSONObject? = runCatching {
        val uri = Uri.parse(path)
        var name: String? = null
        var mime: String? = null
        if (uri.scheme == "content") {
            mime = context.contentResolver.getType(uri)
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } else {
            name = (uri.path ?: path).substringAfterLast('/')
            mime = RestoredAttachmentNaming.mimeOf(name)
        }
        if (name == null && mime == null) null
        else JSONObject().apply { if (name != null) put("name", name); if (mime != null) put("mime", mime) }
    }.getOrNull()

    /** `UUID.ext` preservando a extensão/MIME originais (metadados do backup, caminho de origem ou assinatura do conteúdo). */
    private fun restoredFileName(originalPath: String, meta: JSONObject?, data: ByteArray): String {
        val scheme = Uri.parse(originalPath).scheme
        val fallbackName = if (scheme == null || scheme == "file") originalPath else null
        return RestoredAttachmentNaming.fileName(
            base = UUID.randomUUID().toString(),
            originalName = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: fallbackName,
            mime = meta?.optString("mime")?.takeIf { it.isNotBlank() },
            head = data.copyOf(minOf(data.size, 16)),
        )
    }

    /**
     * Eventos de auditoria do backup, na ordem original, re-encadeados a partir da cabeça atual da cadeia local
     * (os hashes de origem são descartados: a cadeia é por aparelho). Deve rodar dentro da transação de restauração.
     */
    private fun insertRechainedAudit(db: SupportSQLiteDatabase, rows: List<Pair<Long, JSONObject>>): Int {
        val head = db.query("SELECT hash FROM audit_events ORDER BY id DESC LIMIT 1").use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val ordered = rows.sortedBy { it.first }.map { it.second }
        val events = ordered.map { row ->
            runCatching {
                AuditEvent(
                    timestamp = row.getLong("timestamp"),
                    user = row.getString("user"),
                    companyId = if (row.isNull("companyId")) null else row.getLong("companyId"),
                    companyName = row.getString("companyName"),
                    portal = row.optStringOrNull("portal"),
                    tenderNumber = row.optStringOrNull("tenderNumber"),
                    item = row.optStringOrNull("item"),
                    action = AuditAction.valueOf(row.getString("action")),
                    previousValue = row.optStringOrNull("previousValue"),
                    newValue = row.optStringOrNull("newValue"),
                    reason = row.optStringOrNull("reason"),
                    origin = AuditOrigin.valueOf(row.getString("origin")),
                    result = AuditResult.valueOf(row.getString("result")),
                    details = row.optString("details", ""),
                )
            }.getOrElse { throw IllegalArgumentException("Estrutura incompatível: audit_events") }
        }
        AuditRestoreChain.rechain(head, events).forEachIndexed { i, chained ->
            val row = ordered[i].put("prevHash", chained.prevHash).put("hash", chained.hash)
            db.insert("audit_events", SQLiteDatabase.CONFLICT_ABORT, values(row))
        }
        return ordered.size
    }
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

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
