package com.licitaia.feature.documents.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** FileProvider próprio do cofre (classe distinta para não colidir com os outros providers no manifesto). */
class DocumentFileProvider : FileProvider()

/** Anexo copiado para o armazenamento privado do app. */
data class StoredAttachment(
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val pages: Int = 1,
)

/** Metadados exibidos na tela (nome, tipo e tamanho), para anexos próprios ou externos antigos. */
data class AttachmentInfo(val name: String, val mime: String?, val size: Long?) {
    val isPdf: Boolean get() = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
    val isImage: Boolean get() = mime?.startsWith("image/") == true
}

class AttachmentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Armazenamento privado dos anexos do cofre, isolado por empresa: `filesDir/documents/<companyId>/`.
 * O banco guarda o `content://` deste provider em `attachmentUri` (o backup da empresa lê o arquivo por esse URI).
 *
 * - PDF/arquivo: copiado como está;
 * - foto/imagem: girada conforme o EXIF e reduzida (lado maior até [MAX_IMAGE_SIDE] px, JPEG);
 * - várias imagens (páginas): unidas em um único PDF, já que o documento tem um anexo só.
 */
@Singleton
class DocumentFileStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val authority get() = context.packageName + AUTHORITY_SUFFIX
    private val root get() = File(context.filesDir, ROOT_DIR)

    /** Destino da foto da câmera (cache); depois de tirada, passa por [import] e é apagada. */
    fun createCameraTarget(): Uri {
        val dir = File(context.cacheDir, CAMERA_DIR).also { it.mkdirs() }
        // Fotos antigas que ficaram para trás (câmera cancelada / app encerrado).
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val file = File(dir, "foto-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun discardCameraTarget(uri: Uri) {
        cameraFile(uri)?.delete()
    }

    suspend fun import(companyId: Long, sources: List<Uri>, suggestedName: String? = null): StoredAttachment = withContext(Dispatchers.IO) {
        require(companyId > 0) { "Empresa inválida." }
        if (sources.isEmpty()) throw AttachmentException("Nenhum arquivo selecionado.")
        if (sources.size > MAX_PAGES) throw AttachmentException("Selecione no máximo $MAX_PAGES imagens por documento.")
        val dir = File(root, companyId.toString()).also { it.mkdirs() }
        val metas = sources.map { it to sourceMeta(it) }
        val stored = if (sources.size == 1) {
            val (uri, meta) = metas.first()
            when {
                meta.mime?.startsWith("image/") == true -> {
                    val out = File(dir, fileName(suggestedName ?: meta.name, "jpg"))
                    writeNormalizedJpeg(uri, out)
                    StoredAttachment(uriFor(out), out.name, "image/jpeg", out.length())
                }
                else -> {
                    val ext = extensionOf(meta.name, meta.mime)
                    val out = File(dir, fileName(suggestedName ?: meta.name, ext))
                    copy(uri, out)
                    val mime = meta.mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    StoredAttachment(uriFor(out), out.name, mime, out.length())
                }
            }
        } else {
            if (metas.any { it.second.mime?.startsWith("image/") != true }) {
                throw AttachmentException("Para juntar várias páginas, selecione apenas imagens. PDFs são anexados um por vez.")
            }
            val out = File(dir, fileName(suggestedName ?: metas.first().second.name, "pdf"))
            imagesToPdf(sources, out)
            StoredAttachment(uriFor(out), out.name, "application/pdf", out.length(), pages = sources.size)
        }
        if (stored.size > MAX_BYTES) {
            ownedFile(stored.uri)?.delete()
            throw AttachmentException("Arquivo maior que ${MAX_BYTES / (1024 * 1024)} MB. Reduza o arquivo (ex.: PDF comprimido) e tente de novo.")
        }
        if (stored.size == 0L) {
            ownedFile(stored.uri)?.delete()
            throw AttachmentException("O arquivo selecionado está vazio.")
        }
        stored
    }

    /** Arquivo privado correspondente ao URI, se for deste cofre; null para anexos externos (content:// de outros apps). */
    fun ownedFile(uri: String?): File? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        if (parsed.scheme != "content" || parsed.authority != authority) return null
        val segments = parsed.pathSegments
        if (segments.size < 2 || segments.first() != PATH_NAME) return null
        val file = File(root, segments.drop(1).joinToString(File.separator))
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        return file.takeIf { canonical.startsWith(root.canonicalPath + File.separator) }
    }

    /** O anexo pertence à pasta da empresa [companyId]? (Isolamento: nunca apaga/abre arquivos de outra empresa.) */
    fun belongsTo(uri: String?, companyId: Long): Boolean {
        val file = ownedFile(uri) ?: return false
        return file.parentFile?.name == companyId.toString()
    }

    suspend fun delete(uri: String?, companyId: Long) = withContext(Dispatchers.IO) {
        if (belongsTo(uri, companyId)) ownedFile(uri)?.delete()
    }

    /**
     * Remove cópias órfãs da pasta da empresa (edição abandonada com o app encerrado no meio): arquivos com mais de
     * um dia que nenhum documento referencia. Os em uso ([referencedUris]) nunca são tocados.
     */
    suspend fun sweepOrphans(companyId: Long, referencedUris: Collection<String?>) = withContext(Dispatchers.IO) {
        val dir = File(root, companyId.toString())
        val referenced = referencedUris.mapNotNull { ownedFile(it)?.canonicalPath }.toSet()
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff && it.canonicalPath !in referenced }?.forEach { it.delete() }
    }

    suspend fun info(uri: String): AttachmentInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "content") {
                val mime = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
                var name: String? = null
                var size: Long? = null
                context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        size = if (c.isNull(1)) null else c.getLong(1)
                    }
                }
                AttachmentInfo(name ?: parsed.lastPathSegment ?: "Arquivo anexado", mime, size)
            } else {
                val file = File(parsed.path ?: uri)
                AttachmentInfo(file.name, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()), file.length().takeIf { file.exists() })
            }
        }.getOrNull()
    }

    /** O arquivo do anexo ainda pode ser lido? (anexos externos perdem a permissão se o arquivo original sumir). */
    suspend fun isReadable(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ apoio

    private data class SourceMeta(val name: String?, val mime: String?)

    private fun sourceMeta(uri: Uri): SourceMeta {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        val inferred = mime ?: name?.substringAfterLast('.', "")?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return SourceMeta(name, inferred)
    }

    private fun uriFor(file: File): String = FileProvider.getUriForFile(context, authority, file).toString()

    private fun cameraFile(uri: Uri): File? {
        if (uri.authority != authority) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments.first() != "document_camera") return null
        return File(File(context.cacheDir, CAMERA_DIR), segments[1])
    }

    private fun extensionOf(name: String?, mime: String?): String {
        val fromName = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
        return fromName ?: mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
    }

    /** Nome legível + sufixo aleatório (evita colisão e não expõe caminhos de origem). */
    private fun fileName(original: String?, ext: String): String {
        val base = original?.substringBeforeLast('.')
            ?.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") }
            ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")?.trim('_', '.')
            ?.take(48)?.takeIf { it.isNotBlank() } ?: "documento"
        return "$base-${UUID.randomUUID().toString().take(8)}.$ext"
    }

    private fun copy(source: Uri, out: File) {
        val input = context.contentResolver.openInputStream(source) ?: throw AttachmentException("Não foi possível ler o arquivo selecionado.")
        try {
            input.use { src ->
                out.outputStream().use { dst ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw AttachmentException("Arquivo maior que ${MAX_BYTES / (1024 * 1024)} MB. Reduza o arquivo e tente de novo.")
                        dst.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw if (e is AttachmentException) e else AttachmentException("Não foi possível copiar o arquivo selecionado.", e)
        }
    }

    /** Decodifica com amostragem, aplica a rotação do EXIF e limita o lado maior a [maxSide]. */
    private fun decodeUpright(source: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AttachmentException("A imagem selecionada não pôde ser lida.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = try {
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } catch (e: OutOfMemoryError) {
            throw AttachmentException("Imagem grande demais para a memória do aparelho.", e)
        } ?: throw AttachmentException("A imagem selecionada não pôde ser lida.")
        val rotation = runCatching {
            resolver.openInputStream(source)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val longest = maxOf(decoded.width, decoded.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (rotation == 0f && scale == 1f) return decoded
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result != decoded) decoded.recycle()
        return result
    }

    private fun writeNormalizedJpeg(source: Uri, out: File) {
        val bitmap = decodeUpright(source, MAX_IMAGE_SIDE)
        try {
            val opaque = flattenOnWhite(bitmap)
            out.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (opaque != bitmap) opaque.recycle()
        } catch (e: Exception) {
            out.delete()
            throw AttachmentException("Não foi possível salvar a imagem.", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            Canvas(it).drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    /** Une imagens (uma por página, largura A4 = 595 pt) em um PDF. */
    private fun imagesToPdf(sources: List<Uri>, out: File) {
        val pdf = PdfDocument()
        try {
            sources.forEachIndexed { index, uri ->
                val bitmap = decodeUpright(uri, MAX_PAGE_IMAGE_SIDE)
                try {
                    val width = PAGE_WIDTH_PT
                    val height = (PAGE_WIDTH_PT * bitmap.height.toFloat() / bitmap.width).toInt().coerceIn(200, PAGE_WIDTH_PT * 3)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val matrix = Matrix().apply { setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) }
                    page.canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                    pdf.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            out.outputStream().use { pdf.writeTo(it) }
        } catch (e: Exception) {
            out.delete()
            throw if (e is AttachmentException) e else AttachmentException("Não foi possível montar o PDF com as imagens.", e)
        } finally {
            pdf.close()
        }
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".documents.fileprovider"
        private const val ROOT_DIR = "documents"
        private const val PATH_NAME = "company_documents"
        private const val CAMERA_DIR = "document_camera"
        const val MAX_PAGES = 15
        private const val MAX_BYTES = 20L * 1024 * 1024
        private const val MAX_IMAGE_SIDE = 2400
        private const val MAX_PAGE_IMAGE_SIDE = 1800
        private const val JPEG_QUALITY = 85
        private const val PAGE_WIDTH_PT = 595
    }
}
