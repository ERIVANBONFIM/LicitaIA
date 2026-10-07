package com.licitaia.core.data.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.licitaia.ai.api.AiGateway
import com.licitaia.ai.api.EditalQuestionRequest
import com.licitaia.core.data.edital.EditalOcrSupport
import com.licitaia.core.data.edital.PdfExtractionException
import com.licitaia.core.data.edital.PdfOcrEngine
import com.licitaia.core.data.edital.PdfTextExtractor
import com.licitaia.domain.documents.DocumentAiPrompt
import com.licitaia.domain.documents.DocumentReadMethod
import com.licitaia.domain.documents.DocumentReader
import com.licitaia.domain.documents.DocumentReading
import com.licitaia.domain.documents.DocumentSuggestion
import com.licitaia.domain.documents.DocumentTextAnalyzer
import com.licitaia.domain.model.AiProviderType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Leitura automática do anexo de um documento da empresa, reaproveitando o pipeline dos editais:
 * PDF com texto → [PdfTextExtractor]; PDF escaneado → [PdfOcrEngine]; imagem → ML Kit (local, sem rede).
 * As regras de [DocumentTextAnalyzer] preenchem as sugestões; o provedor de IA ativo (quando há um real
 * configurado) só complementa campos que as regras não acharam. Nada é gravado aqui.
 */
@Singleton
class DocumentReaderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfText: PdfTextExtractor,
    private val pdfOcr: PdfOcrEngine,
    private val gateway: AiGateway,
) : DocumentReader {

    private val analyzer = DocumentTextAnalyzer()

    override suspend fun read(uri: String, mimeType: String?, useAi: Boolean, onStage: suspend (String) -> Unit): DocumentReading {
        val parsed = Uri.parse(uri)
        val mime = mimeType ?: runCatching { context.contentResolver.getType(parsed) }.getOrNull()
        val temp = withContext(Dispatchers.IO) { copyToCache(parsed) }
        try {
            val isPdf = mime == "application/pdf" || (mime == null && isPdfHeader(temp))
            onStage("Lendo o arquivo")
            var method = DocumentReadMethod.TEXTO_PDF
            var pages = 1
            val text: String = if (isPdf) {
                val extraction = pdfText.extract(temp, maxPages = MAX_PDF_PAGES)
                pages = extraction.totalPages
                val chars = EditalOcrSupport.meaningfulChars(extraction.text)
                if (extraction.scanned || chars < MIN_TEXT_CHARS) {
                    method = DocumentReadMethod.OCR
                    onStage("PDF escaneado: reconhecendo o texto (OCR)")
                    pdfOcr.recognize(temp, maxPages = MAX_OCR_PAGES) { done, total -> onStage("Reconhecendo o texto (OCR) — página $done de $total") }.text
                        .replace(Regex("--- Página \\d+ ---"), "")
                        .replace(EditalOcrSupport.EMPTY_PAGE_NOTE, "")
                } else extraction.text
            } else {
                method = DocumentReadMethod.OCR
                onStage("Reconhecendo o texto da imagem (OCR)")
                recognizeImage(temp)
            }
            onStage("Identificando tipo, emissor e validade")
            val rules = analyzer.analyze(text)
            val chars = EditalOcrSupport.meaningfulChars(text)
            val warning: String? = when {
                chars == 0 -> "Nenhum texto foi reconhecido no arquivo. Preencha os campos manualmente."
                chars < MIN_TEXT_CHARS -> "Pouco texto reconhecido (imagem pequena, tremida ou escura?). Confira os campos."
                else -> null
            }
            var suggestion = rules
            var aiName: String? = null
            val ask = DocumentAiPrompt.fieldsToAsk(rules)
            if (useAi && ask.isNotEmpty() && chars >= MIN_AI_CHARS) {
                val result = complementWithAi(text, ask, onStage)
                if (result != null) {
                    aiName = result.first
                    suggestion = rules.complementedBy(result.second.restrictTo(ask))
                }
            }
            return DocumentReading(
                method = method, pages = pages, chars = chars, suggestion = suggestion,
                aiProvider = aiName?.takeIf { suggestion.aiFields.isNotEmpty() }, warning = warning,
            )
        } finally {
            withContext(Dispatchers.IO) { temp.delete() }
        }
    }

    /** Provedor real ativo → (nome, sugestões). Indisponível, mock, erro ou tempo esgotado → null (só regras). */
    private suspend fun complementWithAi(text: String, fields: Set<String>, onStage: suspend (String) -> Unit): Pair<String, DocumentSuggestion>? {
        return try {
            if (gateway.activeType() == AiProviderType.MOCK) return null
            val provider = gateway.current()
            if (provider.type == AiProviderType.MOCK) return null
            onStage("Complementando com ${provider.displayName}")
            val prompt = DocumentAiPrompt.build(text, fields)
            val answer = withTimeoutOrNull(AI_TIMEOUT_MS) {
                provider.askEdital(
                    EditalQuestionRequest(
                        system = DocumentAiPrompt.SYSTEM, prompt = prompt,
                        question = "Metadados do documento de habilitação", editalExcerpt = text.take(DocumentAiPrompt.MAX_TEXT_CHARS),
                    ),
                )
            } ?: return null
            provider.displayName to DocumentAiPrompt.parse(answer.text, analyzer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun DocumentSuggestion.restrictTo(fields: Set<String>): DocumentSuggestion = DocumentSuggestion(
        type = type.takeIf { "type" in fields },
        issuer = issuer.takeIf { "issuer" in fields },
        number = number.takeIf { "number" in fields },
        cnpj = cnpj.takeIf { "cnpj" in fields },
        issuedAt = issuedAt.takeIf { "issuedAt" in fields },
        expiresAt = expiresAt.takeIf { "expiresAt" in fields },
    )

    private fun copyToCache(uri: Uri): File {
        val dir = File(context.cacheDir, "document_reader").also { it.mkdirs() }
        val out = File(dir, UUID.randomUUID().toString())
        val input = context.contentResolver.openInputStream(uri) ?: throw PdfExtractionException("Não foi possível abrir o anexo.")
        input.use { src ->
            out.outputStream().use { dst ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw PdfExtractionException("Arquivo grande demais para leitura automática (máx. 40 MB).")
                    dst.write(buffer, 0, n)
                }
            }
        }
        return out
    }

    private fun isPdfHeader(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(5); it.read(b) == 5 && String(b, Charsets.US_ASCII) == "%PDF-" }
    }.getOrDefault(false)

    private suspend fun recognizeImage(file: File): String = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PdfExtractionException("A imagem não pôde ser lida. Use JPG ou PNG.")
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > EditalOcrSupport.MAX_PIXELS_PER_PAGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw PdfExtractionException("A imagem não pôde ser lida. Use JPG ou PNG.")
        // PNG com transparência: fundo branco ajuda o OCR.
        val bitmap = if (decoded.hasAlpha()) {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.eraseColor(Color.WHITE)
                android.graphics.Canvas(bmp).drawBitmap(decoded, 0f, 0f, null)
                decoded.recycle()
            }
        } else decoded
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(PdfExtractionException("Falha no reconhecimento de texto da imagem.", it)) }
                    .addOnCanceledListener { cont.cancel() }
            }
            buildString {
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line -> append(line.text.trim()).append('\n') }
                    append('\n')
                }
            }.trim()
        } finally {
            runCatching { recognizer.close() }
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_PDF_PAGES = 20
        const val MAX_OCR_PAGES = 4
        const val MIN_TEXT_CHARS = 120
        const val MIN_AI_CHARS = 60
        const val AI_TIMEOUT_MS = 45_000L
        const val MAX_BYTES = 40L * 1024 * 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentReaderModule {
    @Binds abstract fun documentReader(impl: DocumentReaderImpl): DocumentReader
}
