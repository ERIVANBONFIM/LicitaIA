package com.licitaia.core.data.edital

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armazenamento privado dos editais: `filesDir/editais/{companyId}/{tenderId}.pdf` e `.txt`.
 * O mesmo layout é usado pelo backup da empresa (CompanyBackupRepository).
 */
@Singleton
class EditalStore @Inject constructor(@ApplicationContext private val context: Context) {

    fun directory(companyId: Long): File = File(context.filesDir, "editais/$companyId")
    fun pdfFile(companyId: Long, tenderId: Long): File = File(directory(companyId), "$tenderId.pdf")
    fun textFile(companyId: Long, tenderId: Long): File = File(directory(companyId), "$tenderId.txt")

    /** Copia o PDF apontado por [uri] (content:// ou file://) para o armazenamento privado, com limite de tamanho. */
    suspend fun importPdf(companyId: Long, tenderId: Long, uri: String): File = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val target = pdfFile(companyId, tenderId).also { it.parentFile?.mkdirs() }
        val temp = File(target.parentFile, "$tenderId.pdf.part")
        try {
            val input: InputStream = context.contentResolver.openInputStream(parsed)
                ?: throw IOException("Não foi possível abrir o arquivo selecionado. Escolha o PDF novamente.")
            input.use { source ->
                temp.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PDF_BYTES) throw IOException("O PDF excede ${MAX_PDF_BYTES / (1024 * 1024)} MB.")
                        sink.write(buffer, 0, read)
                    }
                    if (total == 0L) throw IOException("O arquivo selecionado está vazio.")
                }
            }
            if (!isPdf(temp)) throw IOException("O arquivo selecionado não é um PDF.")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        } catch (e: SecurityException) {
            temp.delete()
            throw IOException("Sem permissão para ler o arquivo. Selecione o PDF novamente.", e)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    suspend fun writeText(companyId: Long, tenderId: Long, text: String): File = withContext(Dispatchers.IO) {
        val target = textFile(companyId, tenderId).also { it.parentFile?.mkdirs() }
        target.writeText(text, Charsets.UTF_8)
        target
    }

    suspend fun readText(path: String?): String? = withContext(Dispatchers.IO) {
        if (path == null) return@withContext null
        val file = File(path)
        if (!file.exists()) return@withContext null
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun delete(companyId: Long, tenderId: Long) = withContext(Dispatchers.IO) {
        pdfFile(companyId, tenderId).delete()
        textFile(companyId, tenderId).delete()
        Unit
    }

    private fun isPdf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(1024)
            val read = input.read(header)
            read > 4 && String(header, 0, read, Charsets.ISO_8859_1).contains("%PDF-")
        }
    }.getOrDefault(false)

    companion object {
        const val MAX_PDF_BYTES = 50L * 1024 * 1024
    }
}
