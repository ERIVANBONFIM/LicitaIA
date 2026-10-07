package com.licitaia.domain.documents

import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.DocumentType

// ---------------------------------------------------------------- Validade / status

/** Rótulos de validade ("Válido", "Vence em X dias", "Vencido") e alertas locais antes do vencimento. */
object DocumentValidity {

    /** Avisos antes do vencimento (dias); 0 = vencido. */
    val ALERT_THRESHOLDS = listOf(15, 3)

    /** Texto do selo de status: "Vence em X dias" no lugar do genérico "Vence em breve". */
    fun badge(status: DocumentStatus, daysToExpire: Long?): String = when (status) {
        DocumentStatus.VENCE_EM_BREVE -> when (daysToExpire) {
            null -> status.label
            0L -> "Vence hoje"
            1L -> "Vence amanhã"
            else -> "Vence em $daysToExpire dias"
        }
        DocumentStatus.VENCIDO -> "Vencido"
        DocumentStatus.VALIDO -> "Válido"
        DocumentStatus.AUSENTE -> "Ausente"
    }

    /** Ordenação da lista: vencidos, vencendo (mais próximo primeiro), ausentes, válidos por vencimento, sem validade no fim. */
    fun sortKey(doc: CompanyDocument, now: Long): Pair<Int, Long> {
        val status = doc.status(now)
        val order = when (status) {
            DocumentStatus.VENCIDO -> 0
            DocumentStatus.VENCE_EM_BREVE -> 1
            DocumentStatus.AUSENTE -> 2
            DocumentStatus.VALIDO -> 3
        }
        return order to (doc.expiresAt ?: Long.MAX_VALUE)
    }

    /**
     * Etapa de alerta em que o documento está agora: 0 = vencido; 3 / 15 = faltam até esse número de dias;
     * null = sem validade, ausente ou ainda longe do vencimento.
     */
    fun alertStage(doc: CompanyDocument, now: Long, thresholds: List<Int> = ALERT_THRESHOLDS): Int? {
        val expiresAt = doc.expiresAt ?: return null
        if (doc.status(now) == DocumentStatus.AUSENTE) return null
        if (expiresAt < now) return 0
        val days = doc.daysToExpire(now) ?: return null
        return thresholds.sorted().firstOrNull { days <= it }
    }

    /** Chave de deduplicação: muda se a validade mudar (documento renovado volta a avisar). */
    fun alertKey(doc: CompanyDocument, stage: Int): String = "doc:${doc.id}:${doc.expiresAt}:$stage"

    data class Alert(val document: CompanyDocument, val stage: Int, val key: String, val daysToExpire: Long)

    /** Alertas ainda não enviados (ver [alertKey]); o mais urgente primeiro. */
    fun dueAlerts(docs: List<CompanyDocument>, now: Long, alreadySent: Set<String>): List<Alert> = docs.mapNotNull { doc ->
        val stage = alertStage(doc, now) ?: return@mapNotNull null
        val key = alertKey(doc, stage)
        if (key in alreadySent) null else Alert(doc, stage, key, doc.daysToExpire(now) ?: 0)
    }.sortedBy { it.document.expiresAt ?: Long.MAX_VALUE }

    /** Título e corpo da notificação local para [alerts] (não vazio). */
    fun notificationText(alerts: List<Alert>): Pair<String, String> {
        require(alerts.isNotEmpty())
        fun line(a: Alert): String {
            val name = a.document.title.ifBlank { a.document.type.label }
            return when {
                a.stage == 0 -> "$name venceu"
                a.daysToExpire <= 0L -> "$name vence hoje"
                a.daysToExpire == 1L -> "$name vence amanhã"
                else -> "$name vence em ${a.daysToExpire} dias"
            }
        }
        if (alerts.size == 1) {
            val a = alerts.first()
            return line(a) to if (a.stage == 0) "Renove o documento para não ser inabilitado na próxima licitação."
            else "Renove antes do vencimento para manter a habilitação em dia."
        }
        val expired = alerts.count { it.stage == 0 }
        val title = if (expired > 0) "$expired documento(s) vencido(s) e ${alerts.size - expired} vencendo"
        else "${alerts.size} documentos vencendo"
        return title to alerts.take(5).joinToString("; ") { line(it) } + if (alerts.size > 5) "…" else "."
    }
}

// ---------------------------------------------------------------- Metadados nas observações

/**
 * O banco não tem colunas para número/código de controle e CNPJ do documento: eles são guardados nas primeiras
 * linhas de [CompanyDocument.notes] com prefixos fixos e separados de volta ao carregar.
 */
object DocumentNotesCodec {
    const val NUMBER_PREFIX = "Nº/código de controle: "
    const val CNPJ_PREFIX = "CNPJ no documento: "

    data class Decoded(val number: String, val cnpj: String, val notes: String)

    fun decode(notes: String): Decoded {
        var number = ""
        var cnpj = ""
        val rest = mutableListOf<String>()
        var header = true
        notes.lines().forEach { line ->
            when {
                header && line.startsWith(NUMBER_PREFIX) -> number = line.removePrefix(NUMBER_PREFIX).trim()
                header && line.startsWith(CNPJ_PREFIX) -> cnpj = line.removePrefix(CNPJ_PREFIX).trim()
                else -> { header = false; rest += line }
            }
        }
        return Decoded(number, cnpj, rest.joinToString("\n").trim())
    }

    fun encode(number: String, cnpj: String, notes: String): String = buildString {
        if (number.isNotBlank()) append(NUMBER_PREFIX).append(number.trim().replace('\n', ' ')).append('\n')
        if (cnpj.isNotBlank()) append(CNPJ_PREFIX).append(cnpj.trim().replace('\n', ' ')).append('\n')
        append(notes.trim())
    }.trim()
}

// ---------------------------------------------------------------- Complemento por IA

/**
 * Prompt e leitura da resposta do provedor de IA para os campos que as regras locais não encontraram. A resposta é
 * texto livre no formato `CAMPO: valor`; qualquer coisa fora do esperado é ignorada (nunca sobrescreve as regras).
 */
object DocumentAiPrompt {
    const val MAX_TEXT_CHARS = 6_000
    private const val NOT_FOUND = "nao encontrado"

    val SYSTEM = """
        Você lê documentos de habilitação de licitações brasileiras (certidões, contrato social, cartão CNPJ, balanço,
        atestados, licenças) e extrai metadados. Responda SOMENTE com as linhas pedidas, no formato CAMPO: valor, sem
        comentários. Use "$NOT_FOUND" quando o documento não informar o campo. Datas sempre em dd/mm/aaaa. Nunca invente.
    """.trimIndent()

    private val labels = mapOf(
        DocumentField.TYPE to "TIPO",
        DocumentField.ISSUER to "EMISSOR",
        DocumentField.NUMBER to "NUMERO",
        DocumentField.CNPJ to "CNPJ",
        DocumentField.ISSUED_AT to "EMISSAO",
        DocumentField.EXPIRES_AT to "VALIDADE",
    )

    fun build(text: String, fields: Set<String>): String = buildString {
        appendLine("Campos pedidos:")
        fields.sortedBy { DocumentField.ALL.indexOf(it) }.forEach { f ->
            val hint = when (f) {
                DocumentField.TYPE -> "um destes códigos: ${DocumentType.entries.joinToString(", ") { it.name }}"
                DocumentField.ISSUER -> "órgão ou entidade que emitiu o documento"
                DocumentField.NUMBER -> "número da certidão, código de controle ou de autenticidade"
                DocumentField.CNPJ -> "CNPJ da empresa a que o documento se refere"
                DocumentField.ISSUED_AT -> "data de emissão (dd/mm/aaaa)"
                else -> "data final de validade (dd/mm/aaaa); se o documento disser 'válido por N dias', calcule a partir da emissão"
            }
            appendLine("${labels.getValue(f)}: <$hint>")
        }
        appendLine()
        appendLine("Texto do documento:")
        appendLine("<<<")
        appendLine(text.take(MAX_TEXT_CHARS))
        append(">>>")
    }

    fun parse(answer: String, analyzer: DocumentTextAnalyzer): DocumentSuggestion {
        val values = HashMap<String, String>()
        answer.lines().forEach { raw ->
            val line = raw.trim().trimStart('-', '*', '•').trim()
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = DocumentTextAnalyzer.normalize(line.substring(0, idx)).trim().uppercase()
            val value = line.substring(idx + 1).trim().trim('"', '\'', '*').trim()
            if (value.isEmpty() || DocumentTextAnalyzer.normalize(value).startsWith(NOT_FOUND)) return@forEach
            labels.entries.firstOrNull { it.value == key }?.let { values[it.key] = value }
        }
        fun date(v: String?, endOfDay: Boolean): Long? {
            val m = v?.let { Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(it) } ?: return null
            val base = analyzer.dateMillis(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: return null
            return if (endOfDay) base + (12L * 60 * 60 - 1) * 1000 else base
        }
        val type = values[DocumentField.TYPE]?.let { v ->
            val code = v.trim().uppercase().replace(' ', '_')
            DocumentType.entries.firstOrNull { it.name == code }
                ?: DocumentType.entries.firstOrNull { DocumentTextAnalyzer.normalize(it.label) == DocumentTextAnalyzer.normalize(v) }
        }?.takeIf { it != DocumentType.OUTROS }
        val cnpj = values[DocumentField.CNPJ]?.filter(Char::isDigit)?.takeIf { it.length == 14 }
        return DocumentSuggestion(
            type = type,
            issuer = values[DocumentField.ISSUER]?.take(120),
            number = values[DocumentField.NUMBER]?.take(80),
            cnpj = cnpj,
            issuedAt = date(values[DocumentField.ISSUED_AT], endOfDay = false),
            expiresAt = date(values[DocumentField.EXPIRES_AT], endOfDay = true),
        )
    }

    /** Vale consultar a IA? Só quando faltam campos relevantes para o tipo identificado. */
    fun fieldsToAsk(rules: DocumentSuggestion): Set<String> {
        val missing = rules.missingFields.toMutableSet()
        // Documentos sem validade por natureza: não pede validade.
        if (rules.type in NO_EXPIRY_TYPES) missing -= DocumentField.EXPIRES_AT
        // Número e CNPJ sozinhos não justificam uma chamada ao provedor.
        val relevant = missing - DocumentField.NUMBER - DocumentField.CNPJ
        return if (relevant.isEmpty()) emptySet() else missing
    }

    val NO_EXPIRY_TYPES = setOf(DocumentType.CONTRATO_SOCIAL, DocumentType.CNPJ, DocumentType.ATESTADO, DocumentType.PROCURACAO)
}

// ---------------------------------------------------------------- Leitura do anexo

/** Como o texto foi obtido. */
enum class DocumentReadMethod(val label: String) {
    TEXTO_PDF("texto do PDF"),
    OCR("OCR (reconhecimento de imagem)"),
}

data class DocumentReading(
    val method: DocumentReadMethod,
    val pages: Int,
    val chars: Int,
    val suggestion: DocumentSuggestion,
    /** Nome do provedor de IA consultado para complementar; null = só regras locais. */
    val aiProvider: String? = null,
    /** Aviso não bloqueante (ex.: IA indisponível, texto curto). */
    val warning: String? = null,
)

/**
 * Lê o anexo de um documento (PDF com texto → texto direto; PDF escaneado ou imagem → OCR local) e devolve as
 * sugestões. Nada é gravado. Implementação no módulo de dados.
 */
interface DocumentReader {
    /** @param uri content:// do anexo (cópia privada do app). @param onStage etapa atual para a UI. */
    suspend fun read(uri: String, mimeType: String?, useAi: Boolean = true, onStage: suspend (String) -> Unit = {}): DocumentReading
}
