package com.licitaia.core.data.edital

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Resultado da extração: texto (possivelmente vazio), páginas lidas/total e indicação de PDF escaneado. */
data class PdfExtraction(
    val text: String,
    val totalPages: Int,
    val pagesRead: Int,
    /** true = praticamente nenhum texto por página: PDF de imagem (precisa de OCR, indisponível). */
    val scanned: Boolean,
    /** Texto de cada página lida (índice 0 = página 1), para a base de perguntas marcar documento/página. */
    val pageTexts: List<String> = emptyList(),
) {
    val truncated: Boolean get() = pagesRead < totalPages
}

class PdfExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Extrai o texto de PDFs com pdfbox-android. Toda a leitura acontece em [Dispatchers.IO] e
 * respeita um limite de páginas para não travar o aparelho com editais gigantes.
 */
@Singleton
class PdfTextExtractor @Inject constructor(@ApplicationContext private val context: Context) {

    private val initialized by lazy { PDFBoxResourceLoader.init(context); true }

    suspend fun extract(file: File, maxPages: Int = MAX_PAGES): PdfExtraction = withContext(Dispatchers.IO) {
        check(initialized)
        val document = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw PdfExtractionException("O PDF está protegido por senha; remova a proteção antes de importar.", e)
        } catch (e: Exception) {
            throw PdfExtractionException("Não foi possível abrir o PDF. Verifique se o arquivo é um PDF válido.", e)
        }
        document.use { doc ->
            if (doc.isEncrypted) {
                try {
                    doc.setAllSecurityToBeRemoved(true)
                } catch (e: Exception) {
                    throw PdfExtractionException("O PDF está protegido por senha; remova a proteção antes de importar.", e)
                }
            }
            val total = doc.numberOfPages
            if (total <= 0) throw PdfExtractionException("O PDF não contém páginas.")
            val pages = minOf(total, maxPages.coerceAtLeast(1))
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
                paragraphStart = "\n"
            }
            val builder = StringBuilder()
            val pageTexts = ArrayList<String>(pages)
            var pagesWithText = 0
            // Página a página: permite cancelamento e mede quantas têm texto de verdade.
            for (page in 1..pages) {
                coroutineContext.ensureActive()
                stripper.startPage = page
                stripper.endPage = page
                val pageText = try {
                    stripper.getText(doc)
                } catch (e: Exception) {
                    throw PdfExtractionException("Falha ao ler a página $page do PDF.", e)
                }
                if (pageText.count { !it.isWhitespace() } >= MIN_CHARS_PER_TEXT_PAGE) pagesWithText++
                builder.append(pageText)
                pageTexts += pageText
                if (page < pages) builder.append("\n\n")
                if (builder.length > HARD_CHAR_LIMIT) break
            }
            val text = builder.toString()
            val meaningful = text.count { !it.isWhitespace() }
            val scanned = meaningful < MIN_TOTAL_CHARS || pagesWithText * 100 / pages < MIN_TEXT_PAGE_RATIO_PCT
            PdfExtraction(text = text, totalPages = total, pagesRead = pageTexts.size, scanned = scanned, pageTexts = pageTexts)
        }
    }

    companion object {
        const val MAX_PAGES = 300
        /** Teto absoluto do texto extraído (o preparador ainda recorta para o prompt). */
        const val HARD_CHAR_LIMIT = 2_000_000
        private const val MIN_CHARS_PER_TEXT_PAGE = 80
        private const val MIN_TOTAL_CHARS = 200
        private const val MIN_TEXT_PAGE_RATIO_PCT = 20
    }
}
