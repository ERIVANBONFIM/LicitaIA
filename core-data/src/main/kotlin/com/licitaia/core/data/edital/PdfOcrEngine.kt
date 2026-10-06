package com.licitaia.core.data.edital

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resultado do OCR: texto montado com marcadores de página, páginas processadas/total e quantas tinham texto. */
data class OcrResult(
    val text: String,
    val totalPages: Int,
    val pagesProcessed: Int,
    val pagesWithText: Int,
) {
    val truncated: Boolean get() = pagesProcessed < totalPages
    val usable: Boolean get() = EditalOcrSupport.isUsable(text)
}

/**
 * OCR local de PDFs escaneados: `PdfRenderer` (Android) renderiza cada página em bitmap (escala ~2x,
 * limitada por memória) e o ML Kit Text Recognition v2 (script latino, modelo embutido, sem rede)
 * reconhece o texto. Roda em [Dispatchers.Default]; bitmaps são reciclados página a página e o
 * loop respeita cancelamento.
 */
@Singleton
class PdfOcrEngine @Inject constructor() {

    /**
     * @param onProgress chamado após cada página reconhecida com (páginas concluídas, total a reconhecer).
     * @throws PdfExtractionException PDF protegido, corrompido ou sem páginas; falha do reconhecedor.
     */
    suspend fun recognize(
        file: File,
        maxPages: Int = EditalOcrSupport.MAX_OCR_PAGES,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OcrResult = withContext(Dispatchers.Default) {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw PdfExtractionException("Não foi possível abrir o PDF para o OCR.", e)
        }
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            descriptor.close()
            throw PdfExtractionException("O PDF está protegido por senha; remova a proteção antes de reconhecer o texto.", e)
        } catch (e: Exception) {
            descriptor.close()
            throw PdfExtractionException("Não foi possível abrir o PDF para o OCR. Verifique se o arquivo é um PDF válido.", e)
        }
        val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val total = renderer.pageCount
            if (total <= 0) throw PdfExtractionException("O PDF não contém páginas.")
            val pages = minOf(total, maxPages.coerceAtLeast(1))
            val texts = ArrayList<String>(pages)
            onProgress(0, pages)
            for (index in 0 until pages) {
                coroutineContext.ensureActive()
                val pageText = try {
                    recognizePage(renderer, index, recognizer)
                } catch (e: PdfExtractionException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    throw PdfExtractionException("Memória insuficiente para reconhecer a página ${index + 1}. Tente um PDF menor.", e)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    throw PdfExtractionException("Falha ao reconhecer o texto da página ${index + 1}.", e)
                }
                texts += pageText
                onProgress(index + 1, pages)
            }
            OcrResult(
                text = EditalOcrSupport.assemble(texts),
                totalPages = total,
                pagesProcessed = pages,
                pagesWithText = EditalOcrSupport.pagesWithText(texts),
            )
        } finally {
            runCatching { recognizer.close() }
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    private suspend fun recognizePage(renderer: PdfRenderer, index: Int, recognizer: TextRecognizer): String {
        val page = renderer.openPage(index)
        val bitmap: Bitmap = try {
            val scale = EditalOcrSupport.renderScale(page.width, page.height)
            val (width, height) = EditalOcrSupport.bitmapSize(page.width, page.height, scale)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                // PdfRenderer desenha sobre transparente; fundo branco é essencial para o OCR.
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
            flatten(result)
        } finally {
            bitmap.recycle()
        }
    }

    /** Blocos → linhas, preservando a ordem que o ML Kit inferiu para a página. */
    private fun flatten(text: Text): String = buildString {
        text.textBlocks.forEach { block ->
            block.lines.forEach { line -> append(line.text.trim()).append('\n') }
            append('\n')
        }
    }.trim()

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
