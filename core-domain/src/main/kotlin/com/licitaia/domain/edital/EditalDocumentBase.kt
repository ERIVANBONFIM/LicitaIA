package com.licitaia.domain.edital

/** Tipo de documento da licitação, com a prioridade na base de perguntas (menor = primeiro). */
enum class EditalDocKind(val label: String, val priority: Int) {
    EDITAL("Edital", 0),
    TERMO_REFERENCIA("Termo de Referência", 1),
    PROJETO_BASICO("Projeto Básico", 2),
    ANEXO("Anexo", 2),
    MINUTA("Minuta do Contrato", 3),
    ETP("Estudo Técnico Preliminar", 4),
    OUTRO("Outro documento", 5),
}

/** Documento já baixado e com o texto extraído (índice 0 = página 1). [totalPages] = páginas do PDF. */
data class EditalSourceDocument(
    val title: String,
    val kind: EditalDocKind,
    val pages: List<String>,
    val totalPages: Int = pages.size,
    /** Texto obtido por OCR (PDF escaneado). */
    val ocr: Boolean = false,
)

/** Documento que entrou na base: título, tipo, páginas incluídas (com texto) e se foi cortado por limite. */
data class EditalBaseEntry(
    val title: String,
    val kind: EditalDocKind,
    val pagesIncluded: Int,
    val totalPages: Int,
    val truncated: Boolean = false,
    val ocr: Boolean = false,
) {
    val displayName: String get() = if (title.isBlank() || title.equals(kind.label, ignoreCase = true)) kind.label else "${kind.label} — $title"
}

/** Base de perguntas montada: texto único com marcadores, documentos incluídos e os que ficaram de fora (com motivo). */
data class EditalDocumentBaseResult(
    val text: String,
    val documents: List<EditalBaseEntry>,
    val skipped: List<String>,
)

/**
 * "Base de perguntas" de uma licitação: TODOS os documentos publicados (edital, termo de referência, anexos, ETP...)
 * num texto único, cada página precedida por `=== DOCUMENTO: <tipo — título> (página N) ===`, na ordem de prioridade
 * Edital → Termo de Referência → Anexos/Projeto Básico → Minuta → ETP → outros. O marcador deixa a IA citar documento e
 * página e permite listar, a partir do próprio texto salvo, quais documentos estão na base. Puro: testável em JVM.
 */
object EditalDocumentBase {
    /** Teto do texto da base (~300 mil tokens; o recorte por pergunta envia só os trechos relevantes). */
    const val DEFAULT_MAX_CHARS = 1_200_000

    /** Páginas lidas por documento (o restante é ignorado e o documento fica marcado como cortado). */
    const val MAX_PAGES_PER_DOC = 120

    /** Documentos baixados por licitação. */
    const val MAX_DOCUMENTS = 12

    /** Sobra mínima para valer a pena incluir uma página cortada. */
    private const val MIN_PARTIAL_PAGE_CHARS = 1_500

    private const val MARKER_PREFIX = "=== DOCUMENTO: "

    /** Marcador de página: `=== DOCUMENTO: Termo de Referência — tr.pdf (página 3) ===`. */
    fun marker(entryName: String, page: Int): String = "$MARKER_PREFIX$entryName (página $page) ==="

    private val MARKER = Regex("^=== DOCUMENTO: (.+) \\(página (\\d+)\\) ===$", RegexOption.MULTILINE)

    /** Tipos de documento do PNCP (`/v1/tipos-documentos`). */
    private val TYPE_IDS: Map<Long, EditalDocKind> = mapOf(
        1L to EditalDocKind.EDITAL, // Aviso de Contratação Direta: faz as vezes do edital
        2L to EditalDocKind.EDITAL,
        3L to EditalDocKind.MINUTA,
        4L to EditalDocKind.TERMO_REFERENCIA,
        5L to EditalDocKind.PROJETO_BASICO, // Anteprojeto
        6L to EditalDocKind.PROJETO_BASICO,
        7L to EditalDocKind.ETP,
    )

    /**
     * Classifica pelo tipo informado pela fonte (id/nome) e, quando genérico ("Outros Documentos") ou ausente, pelo
     * título do arquivo. ETP é testado antes de "edital" para um "ETP - anexo do edital.pdf" não virar o edital.
     */
    fun classify(title: String, typeName: String? = null, typeId: Long? = null): EditalDocKind {
        typeId?.let { TYPE_IDS[it] }?.let { return it }
        byText(typeName.orEmpty())?.let { return it }
        return byText(title) ?: EditalDocKind.OUTRO
    }

    private fun byText(raw: String): EditalDocKind? {
        val t = " " + EditalExcerptSelector.fold(raw).replace('_', ' ').replace('-', ' ').replace('.', ' ') + " "
        return when {
            t.isBlank() -> null
            t.contains("estudo tecnico") || t.contains(" etp ") -> EditalDocKind.ETP
            t.contains("termo de referencia") || t.contains(" tr ") -> EditalDocKind.TERMO_REFERENCIA
            t.trimStart().startsWith("edital") -> EditalDocKind.EDITAL
            t.contains("minuta") || t.contains("contrato") -> EditalDocKind.MINUTA
            t.contains("projeto basico") || t.contains("anteprojeto") -> EditalDocKind.PROJETO_BASICO
            t.contains("edital") || t.contains("aviso de contratacao") || t.contains("aviso de dispensa") -> EditalDocKind.EDITAL
            t.contains("anexo") -> EditalDocKind.ANEXO
            else -> null
        }
    }

    /** Ordem de prioridade estável (empate: ordem original). */
    fun <T> prioritize(items: List<T>, kind: (T) -> EditalDocKind): List<T> =
        items.withIndex().sortedWith(compareBy<IndexedValue<T>> { kind(it.value).priority }.thenBy { it.index }).map { it.value }

    fun build(
        documents: List<EditalSourceDocument>,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxPagesPerDoc: Int = MAX_PAGES_PER_DOC,
    ): EditalDocumentBaseResult {
        val text = StringBuilder()
        val included = mutableListOf<EditalBaseEntry>()
        val skipped = mutableListOf<String>()
        val usedNames = HashSet<String>()
        for (doc in prioritize(documents) { it.kind }) {
            val baseName = EditalBaseEntry(doc.title.trim(), doc.kind, 0, doc.totalPages).displayName
            // Dois arquivos com o mesmo nome: o marcador precisa distinguir para a citação apontar o documento certo.
            var name = baseName
            var n = 2
            while (!usedNames.add(name)) name = "$baseName (${n++})"
            if (text.length >= maxChars) {
                skipped += "$baseName (limite de tamanho da base)"
                continue
            }
            var pagesIncluded = 0
            var truncated = doc.pages.size > maxPagesPerDoc || doc.totalPages > doc.pages.size
            for ((index, raw) in doc.pages.take(maxPagesPerDoc).withIndex()) {
                val pageText = raw.trim()
                if (pageText.isEmpty() || pageText == EMPTY_OCR_PAGE) continue
                val header = marker(name, index + 1)
                val room = maxChars - text.length - header.length - 3
                if (room < pageText.length) {
                    truncated = true
                    if (room >= MIN_PARTIAL_PAGE_CHARS) {
                        text.append(header).append('\n').append(pageText, 0, room).append("\n\n")
                        pagesIncluded++
                    }
                    break
                }
                text.append(header).append('\n').append(pageText).append("\n\n")
                pagesIncluded++
            }
            if (pagesIncluded == 0) {
                skipped += "$baseName (${if (truncated && text.length >= maxChars - MIN_PARTIAL_PAGE_CHARS) "limite de tamanho da base" else "sem texto"})"
            } else {
                included += EditalBaseEntry(titleOf(name, doc.kind), doc.kind, pagesIncluded, doc.totalPages, truncated, doc.ocr)
            }
        }
        return EditalDocumentBaseResult(text.toString().trimEnd(), included, skipped)
    }

    /** Documentos presentes num texto salvo (pelos marcadores), na ordem em que aparecem, com as páginas incluídas. */
    fun documentsIn(text: String?): List<EditalBaseEntry> {
        if (text.isNullOrEmpty() || !text.contains(MARKER_PREFIX)) return emptyList()
        val pages = LinkedHashMap<String, Int>()
        MARKER.findAll(text).forEach { match -> pages.merge(match.groupValues[1].trim(), 1, Int::plus) }
        return pages.map { (name, count) ->
            val kind = EditalDocKind.entries.firstOrNull { name == it.label || name.startsWith("${it.label} — ") } ?: EditalDocKind.OUTRO
            EditalBaseEntry(titleOf(name, kind), kind, pagesIncluded = count, totalPages = count)
        }
    }

    /** Título a partir do nome do marcador ("Tipo — título"): vazio quando o nome é só o tipo. */
    private fun titleOf(name: String, kind: EditalDocKind): String = when {
        name == kind.label -> ""
        name.startsWith("${kind.label} — ") -> name.removePrefix("${kind.label} — ")
        else -> name
    }

    /** Nota que o OCR grava em página sem texto reconhecido (não entra na base). */
    private const val EMPTY_OCR_PAGE = "[página sem texto reconhecido]"
}
