package com.licitaia.domain.documents

import com.licitaia.domain.model.DocumentType
import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/** Campos que a leitura automática pode sugerir (chaves estáveis usadas na UI e no prompt da IA). */
object DocumentField {
    const val TYPE = "type"
    const val ISSUER = "issuer"
    const val NUMBER = "number"
    const val CNPJ = "cnpj"
    const val ISSUED_AT = "issuedAt"
    const val EXPIRES_AT = "expiresAt"
    val ALL = listOf(TYPE, ISSUER, NUMBER, CNPJ, ISSUED_AT, EXPIRES_AT)
}

/**
 * Sugestões lidas de um documento de habilitação. Nada aqui é gravado automaticamente: a tela de cadastro
 * preenche o formulário e o usuário confirma em "Salvar".
 *
 * @property cnpj somente dígitos (14).
 * @property validityNote regra de validade encontrada no texto (ex.: "válida por 180 dias a partir da emissão").
 * @property aiFields campos (ver [DocumentField]) que vieram do provedor de IA, e não das regras locais.
 */
data class DocumentSuggestion(
    val type: DocumentType? = null,
    val issuer: String? = null,
    val number: String? = null,
    val cnpj: String? = null,
    val issuedAt: Long? = null,
    val expiresAt: Long? = null,
    val validityNote: String? = null,
    val aiFields: Set<String> = emptySet(),
) {
    /** Campos com valor sugerido. */
    val filledFields: Set<String>
        get() = buildSet {
            if (type != null) add(DocumentField.TYPE)
            if (!issuer.isNullOrBlank()) add(DocumentField.ISSUER)
            if (!number.isNullOrBlank()) add(DocumentField.NUMBER)
            if (!cnpj.isNullOrBlank()) add(DocumentField.CNPJ)
            if (issuedAt != null) add(DocumentField.ISSUED_AT)
            if (expiresAt != null) add(DocumentField.EXPIRES_AT)
        }

    val missingFields: Set<String> get() = DocumentField.ALL.toSet() - filledFields

    val isEmpty: Boolean get() = filledFields.isEmpty()

    /** Completa os campos ausentes com [other] (regras primeiro; [other] normalmente vem da IA). */
    fun complementedBy(other: DocumentSuggestion, markAs: Set<String> = other.filledFields): DocumentSuggestion {
        val added = mutableSetOf<String>()
        fun <T> pick(field: String, mine: T?, theirs: T?): T? =
            if (mine != null && !(mine is String && mine.isBlank())) mine
            else theirs?.also { if (field in markAs) added += field }
        val merged = copy(
            type = pick(DocumentField.TYPE, type, other.type),
            issuer = pick(DocumentField.ISSUER, issuer, other.issuer),
            number = pick(DocumentField.NUMBER, number, other.number),
            cnpj = pick(DocumentField.CNPJ, cnpj, other.cnpj),
            issuedAt = pick(DocumentField.ISSUED_AT, issuedAt, other.issuedAt),
            expiresAt = pick(DocumentField.EXPIRES_AT, expiresAt, other.expiresAt),
            validityNote = validityNote ?: other.validityNote,
        )
        // Nunca aceita validade anterior à emissão vinda da IA.
        val sane = if (merged.issuedAt != null && merged.expiresAt != null && merged.expiresAt < merged.issuedAt &&
            DocumentField.EXPIRES_AT in added
        ) {
            added -= DocumentField.EXPIRES_AT
            merged.copy(expiresAt = expiresAt)
        } else merged
        return sane.copy(aiFields = aiFields + added)
    }
}

/**
 * Leitura determinística (regras/regex) de certidões e documentos de habilitação a partir do texto do PDF ou do OCR:
 * tipo, órgão emissor, número/código de controle, CNPJ, emissão e validade. Puro Kotlin, testável em JVM.
 *
 * As datas devolvidas ficam ao meio-dia (emissão) e ao fim do dia (validade) no fuso [timeZone], de modo que um
 * documento "válido até 04/03/2025" continua válido durante todo o dia 04/03.
 */
class DocumentTextAnalyzer(private val timeZone: TimeZone = TimeZone.getDefault()) {

    fun analyze(rawText: String): DocumentSuggestion {
        if (rawText.isBlank()) return DocumentSuggestion()
        val text = normalize(rawText)
        val type = classify(text)
        val issuedAt = findIssuedAt(text)
        val validity = findValidity(text, issuedAt)
        val expiresAt = validity.expiresAt?.takeIf { exp -> (validity.issuedAt ?: issuedAt)?.let { exp >= it } ?: true }
        return DocumentSuggestion(
            type = type,
            issuer = findIssuer(rawText, type),
            number = findNumber(rawText),
            cnpj = findCnpj(rawText),
            issuedAt = issuedAt ?: validity.issuedAt,
            expiresAt = expiresAt,
            validityNote = validity.note,
        )
    }

    // ------------------------------------------------------------------ tipo

    private class Rule(val type: DocumentType, val weight: Int, val pattern: Regex)

    private fun rule(type: DocumentType, weight: Int, pattern: String) = Rule(type, weight, Regex(pattern))

    private val rules = listOf(
        // FGTS (Caixa)
        rule(DocumentType.FGTS, 20, "certificado de regularidade do fgts"),
        rule(DocumentType.FGTS, 12, "\\bcrf\\b"),
        rule(DocumentType.FGTS, 6, "\\bfgts\\b"),
        rule(DocumentType.FGTS, 4, "caixa economica federal"),
        // CNDT (Justiça do Trabalho)
        rule(DocumentType.TRABALHISTA, 20, "certidao (negativa|positiva) de debitos trabalhistas"),
        rule(DocumentType.TRABALHISTA, 12, "\\bcndt\\b"),
        rule(DocumentType.TRABALHISTA, 8, "debitos trabalhistas"),
        rule(DocumentType.TRABALHISTA, 4, "justica do trabalho|tribunal superior do trabalho"),
        // Federal (RFB/PGFN)
        rule(DocumentType.CERTIDAO_FEDERAL, 20, "tributos federais"),
        rule(DocumentType.CERTIDAO_FEDERAL, 12, "divida ativa da uniao"),
        rule(DocumentType.CERTIDAO_FEDERAL, 6, "procuradoria-?geral da fazenda nacional|\\bpgfn\\b"),
        rule(DocumentType.CERTIDAO_FEDERAL, 3, "certidao .{0,40}receita federal"),
        // CNPJ (cartão)
        rule(DocumentType.CNPJ, 25, "comprovante de inscricao e de situacao cadastral"),
        rule(DocumentType.CNPJ, 8, "cadastro nacional da pessoa juridica"),
        rule(DocumentType.CNPJ, 4, "situacao cadastral"),
        rule(DocumentType.CNPJ, 4, "natureza juridica"),
        // Estadual
        rule(DocumentType.CERTIDAO_ESTADUAL, 14, "fazenda (publica )?estadual|receita estadual"),
        rule(DocumentType.CERTIDAO_ESTADUAL, 10, "secretaria (de estado )?da fazenda|secretaria (de estado )?de fazenda|\\bsefaz\\b|secretaria (de estado )?da economia|secretaria da receita"),
        rule(DocumentType.CERTIDAO_ESTADUAL, 8, "\\bicms\\b|tributos estaduais|debitos (tributarios )?estaduais|divida ativa (do estado|estadual)|procuradoria geral do estado"),
        rule(DocumentType.CERTIDAO_ESTADUAL, 3, "governo do estado"),
        // Municipal
        rule(DocumentType.CERTIDAO_MUNICIPAL, 14, "tributos municipais|fazenda (publica )?municipal|debitos (tributarios )?municipais|divida ativa (do municipio|municipal)"),
        rule(DocumentType.CERTIDAO_MUNICIPAL, 10, "prefeitura (municipal )?d[eoa]s? |municipio d[eoa]s? |secretaria municipal d[ea] (fazenda|financas|receita)"),
        rule(DocumentType.CERTIDAO_MUNICIPAL, 6, "\\biss(qn)?\\b|\\biptu\\b|mobiliari[oa]s?|imobiliari[oa]s? municipa"),
        // Contrato social / alterações
        rule(DocumentType.CONTRATO_SOCIAL, 20, "contrato social|alteracao contratual|consolidacao contratual|instrumento particular de (alteracao|constituicao)"),
        rule(DocumentType.CONTRATO_SOCIAL, 12, "ato constitutivo|estatuto social|requerimento de empresario|certificado da condicao de microempreendedor"),
        rule(DocumentType.CONTRATO_SOCIAL, 6, "junta comercial|clausula (primeira|segunda|terceira)|capital social|\\bsocios?\\b"),
        // Balanço
        rule(DocumentType.BALANCO, 20, "balanco patrimonial"),
        rule(DocumentType.BALANCO, 10, "demonstracao do resultado|\\bdre\\b|ativo circulante|passivo circulante|patrimonio liquido"),
        rule(DocumentType.BALANCO, 6, "termo de (abertura|encerramento)|livro diario|indice de liquidez"),
        // SCM / Anatel
        rule(DocumentType.SCM, 20, "servico de comunicacao multimidia"),
        rule(DocumentType.SCM, 10, "\\banatel\\b|agencia nacional de telecomunicacoes"),
        rule(DocumentType.SCM, 8, "\\bscm\\b|outorga|ato de autorizacao"),
        // Atestado
        rule(DocumentType.ATESTADO, 22, "atestado de capacidade tecnica|atestado de qualificacao tecnica"),
        rule(DocumentType.ATESTADO, 10, "\\batestamos\\b|atestamos para os devidos fins|\\batesta(mos)?,? para (os devidos )?fins"),
        rule(DocumentType.ATESTADO, 4, "prestou (os )?servicos|forneceu|executou (os )?servicos"),
        // CREA/CRT
        rule(DocumentType.CREA_CRT, 18, "certidao de registro e quitacao|conselho regional de engenharia|conselho federal dos tecnicos|\\bcrea\\b|\\bcrt\\b|anotacao de responsabilidade tecnica"),
        // Procuração
        rule(DocumentType.PROCURACAO, 18, "\\bprocuracao\\b|outorgante|outorgado"),
    )

    /** Tipo mais provável; null quando nenhuma regra soma o mínimo de confiança. */
    fun classify(normalizedText: String): DocumentType? {
        val head = normalizedText.take(CLASSIFY_WINDOW)
        val scores = HashMap<DocumentType, Int>()
        rules.forEach { r ->
            // O título costuma estar no topo: ocorrência no início do documento vale o dobro.
            val first = r.pattern.find(head) ?: return@forEach
            val bonus = if (first.range.first < TITLE_WINDOW) 2 else 1
            scores[r.type] = (scores[r.type] ?: 0) + r.weight * bonus
        }
        val best = scores.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value >= MIN_SCORE }
    }

    // ------------------------------------------------------------------ emissor

    private fun findIssuer(raw: String, type: DocumentType?): String? {
        when (type) {
            DocumentType.CERTIDAO_FEDERAL -> return "Receita Federal do Brasil / PGFN"
            DocumentType.CNPJ -> return "Receita Federal do Brasil"
            DocumentType.FGTS -> return "Caixa Econômica Federal"
            DocumentType.TRABALHISTA -> return "Justiça do Trabalho (TST)"
            DocumentType.SCM -> return "Anatel"
            else -> Unit
        }
        val patterns = buildList {
            if (type == DocumentType.CONTRATO_SOCIAL) add(Regex("(?i)(junta comercial[^\\n,;]{0,60})"))
            if (type == DocumentType.CERTIDAO_MUNICIPAL || type == null) add(Regex("(?i)(prefeitura(?: municipal)? d[eoa]s? [^\\n,;\\-]{2,50})"))
            if (type == DocumentType.CERTIDAO_ESTADUAL || type == null) {
                add(Regex("(?i)(secretaria (?:de estado )?d[ae] (?:fazenda|economia|receita|finan[cç]as)[^\\n,;]{0,50})"))
                add(Regex("(?i)(procuradoria[- ]geral do estado[^\\n,;]{0,40})"))
                add(Regex("(?i)(governo do estado d[eoa]s? [^\\n,;\\-]{2,40})"))
            }
            if (type == DocumentType.CREA_CRT) add(Regex("(?i)(conselho regional de [^\\n,;]{3,80})"))
            add(Regex("(?i)(prefeitura(?: municipal)? d[eoa]s? [^\\n,;\\-]{2,50})"))
            add(Regex("(?i)(secretaria [^\\n,;]{3,70})"))
        }
        patterns.forEach { p ->
            val m = p.find(raw) ?: return@forEach
            return cleanIssuer(m.groupValues[1])
        }
        return null
    }

    private fun cleanIssuer(raw: String): String {
        val collapsed = raw.replace(Regex("\\s+"), " ").trim().trimEnd('.', ':', '-')
        val cased = if (collapsed == collapsed.uppercase()) titleCase(collapsed) else collapsed
        return cased.take(120)
    }

    private fun titleCase(s: String): String {
        val small = setOf("de", "da", "do", "das", "dos", "e")
        return s.lowercase().split(' ').mapIndexed { i, w ->
            if (i > 0 && w in small) w else w.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
    }

    // ------------------------------------------------------------------ número / código de controle

    private val numberPatterns = listOf(
        Regex("(?i)c[oó]digo de controle(?: da certid[aã]o)?\\s*[:\\-]?\\s*([A-Z0-9]{4}(?:[. ][A-Z0-9]{4}){2,4})(?![A-Za-z0-9])"),
        Regex("(?i)certifica[cç][aã]o\\s*n[uú]mero\\s*[:\\-]?\\s*([0-9]{8,30})"),
        Regex("(?i)certid[aã]o\\s*n[º°o.]*\\s*[:\\-]?\\s*([0-9][0-9./\\-]{4,30}[0-9])"),
        Regex("(?i)c[oó]digo de (?:autenticidade|verifica[cç][aã]o|valida[cç][aã]o)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9.\\-]{5,40}[A-Z0-9])"),
        Regex("(?i)chave de (?:valida[cç][aã]o|autentica[cç][aã]o|acesso)\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9.\\-]{5,60}[A-Z0-9])"),
        Regex("(?i)(?:n[uú]mero|n[º°]|no\\.)\\s*(?:da certid[aã]o|do documento|do atestado|do registro|de controle)?\\s*[:\\-]\\s*([0-9][0-9A-Z./\\-]{3,30}[0-9A-Z])"),
        Regex("(?i)protocolo\\s*(?:n[º°o.]*)?\\s*[:\\-]?\\s*([0-9][0-9./\\-]{5,30}[0-9])"),
    )

    private fun findNumber(raw: String): String? {
        numberPatterns.forEach { p ->
            val value = p.find(raw)?.groupValues?.get(1)?.trim() ?: return@forEach
            // Não confunde CNPJ com número da certidão.
            if (CNPJ_REGEX.matches(value)) return@forEach
            return value.replace(Regex("\\s+"), ".").take(80)
        }
        return null
    }

    // ------------------------------------------------------------------ CNPJ

    private fun findCnpj(raw: String): String? {
        val candidates = CNPJ_REGEX.findAll(raw).map { it.value.filter(Char::isDigit) }.filter { it.length == 14 }.toList()
        return candidates.firstOrNull { CnpjCheck.isValid(it) } ?: candidates.firstOrNull()
    }

    // ------------------------------------------------------------------ datas

    private data class Validity(val issuedAt: Long? = null, val expiresAt: Long? = null, val note: String? = null)

    private val issuedPatterns = listOf(
        Regex("emitid[ao] (?:as|a|em)?\\s*\\d{1,2}:\\d{2}(?::\\d{2})? (?:h )?do dia $DATE"),
        Regex("emitid[ao] no dia $DATE"),
        Regex("(?:data (?:de|da) )?(?:emissao|expedicao)\\s*[:\\-]?\\s*(?:em )?$DATE"),
        Regex("(?:emitid[ao]|expedid[ao]|gerad[ao]|lavrad[ao]) (?:em|no dia|aos?)\\s*[:\\-]?\\s*$DATE"),
        Regex("informacao obtida em $DATE"),
        Regex("certidao (?:emitida|expedida) (?:em|aos?) $DATE"),
    )

    private fun findIssuedAt(text: String): Long? {
        issuedPatterns.forEach { p -> p.find(text)?.let { m -> date(m, 1)?.let { return it } } }
        // "Cidade, 10 de março de 2024" (atestados, declarações) — última ocorrência, que costuma ser a data de assinatura.
        LONG_DATE_LOCAL.findAll(text).lastOrNull()?.let { m -> longDate(m)?.let { return it } }
        return null
    }

    private val rangePatterns = listOf(
        Regex("(?:periodo de )?validade\\s*[:\\-]?\\s*(?:de )?$DATE\\s*(?:a|ate|-|/)\\s*$DATE"),
        Regex("valid[ao] (?:de|desde) $DATE\\s*(?:a|ate)\\s*$DATE"),
    )

    private val untilPatterns = listOf(
        Regex("valid[ao]s? ate\\s*(?:o dia\\s*)?[:\\-]?\\s*$DATE"),
        Regex("validade\\s*(?:ate|:|-|da certidao\\s*:?)\\s*(?:o dia\\s*)?$DATE"),
        Regex("data de (?:validade|vencimento)\\s*[:\\-]?\\s*$DATE"),
        Regex("(?:vencimento|vence em|expira em|valida ate)\\s*[:\\-]?\\s*$DATE"),
        Regex("validade\\s+$DATE"),
    )

    private val periodPatterns = listOf(
        Regex("valid[ao]s? (?:por|pelo prazo de|durante)\\s*(\\d{1,4})\\s*(?:\\([^)]{0,40}\\)\\s*)?(dias|meses)"),
        Regex("prazo de validade (?:de|e de|sera de)?\\s*(\\d{1,4})\\s*(?:\\([^)]{0,40}\\)\\s*)?(dias|meses)"),
        Regex("validade (?:de|e de)\\s*(\\d{1,4})\\s*(?:\\([^)]{0,40}\\)\\s*)?(dias|meses)"),
        Regex("(\\d{1,4})\\s*(?:\\([^)]{0,40}\\)\\s*)?(dias|meses),? (?:contados|a contar|a partir) da (?:data de )?(?:sua )?(?:emissao|expedicao)"),
    )

    private fun findValidity(text: String, issuedAt: Long?): Validity {
        rangePatterns.forEach { p ->
            val m = p.find(text) ?: return@forEach
            val start = date(m, 1)
            val end = date(m, 4)?.let(::endOfDay)
            if (end != null) return Validity(issuedAt = start, expiresAt = end)
        }
        untilPatterns.forEach { p ->
            val m = p.find(text) ?: return@forEach
            date(m, 1)?.let { return Validity(expiresAt = endOfDay(it)) }
        }
        periodPatterns.forEach { p ->
            val m = p.find(text) ?: return@forEach
            val amount = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..3650 } ?: return@forEach
            val months = m.groupValues[2] == "meses"
            val note = "Válida por $amount ${if (months) "meses" else "dias"} a partir da emissão"
            val expires = issuedAt?.let { addPeriod(it, amount, months) }
            return Validity(expiresAt = expires, note = note)
        }
        return Validity()
    }

    private fun addPeriod(issuedAt: Long, amount: Int, months: Boolean): Long {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = issuedAt }
        if (months) cal.add(Calendar.MONTH, amount) else cal.add(Calendar.DAY_OF_MONTH, amount)
        return endOfDay(cal.timeInMillis)
    }

    private fun date(m: MatchResult, firstGroup: Int): Long? {
        val d = m.groupValues.getOrNull(firstGroup)?.toIntOrNull() ?: return null
        val mo = m.groupValues.getOrNull(firstGroup + 1)?.toIntOrNull() ?: return null
        var y = m.groupValues.getOrNull(firstGroup + 2)?.toIntOrNull() ?: return null
        if (y < 100) y += 2000
        return dateMillis(d, mo, y)
    }

    private fun longDate(m: MatchResult): Long? {
        val d = m.groupValues[1].toIntOrNull() ?: return null
        val mo = MONTHS.indexOf(m.groupValues[2]).takeIf { it >= 0 }?.plus(1) ?: return null
        val y = m.groupValues[3].toIntOrNull() ?: return null
        return dateMillis(d, mo, y)
    }

    /** Meio-dia local do dia informado; null se a data não existe ou está fora de uma faixa plausível. */
    fun dateMillis(day: Int, month: Int, year: Int): Long? {
        if (year !in 1990..2100 || month !in 1..12 || day !in 1..31) return null
        val cal = Calendar.getInstance(timeZone).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }
        return runCatching { cal.timeInMillis }.getOrNull()
    }

    private fun endOfDay(millis: Long): Long = Calendar.getInstance(timeZone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val CLASSIFY_WINDOW = 12_000
        private const val TITLE_WINDOW = 600
        private const val MIN_SCORE = 10

        /** dd/mm/aaaa, dd.mm.aaaa ou dd-mm-aaaa, tolerando espaços que o OCR insere em volta dos separadores. */
        private const val DATE = "(\\d{1,2})\\s?[/.\\-]\\s?(\\d{1,2})\\s?[/.\\-]\\s?(\\d{4}|\\d{2})(?!\\d)"

        private val MONTHS = listOf(
            "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
        )
        private val LONG_DATE_LOCAL = Regex("(\\d{1,2})(?:o|º)? de (${MONTHS.joinToString("|")}) de (\\d{4})")

        val CNPJ_REGEX = Regex("\\d{2}\\.?\\s?\\d{3}\\.?\\s?\\d{3}\\s?/?\\s?\\d{4}\\s?-?\\s?\\d{2}")

        /** Minúsculas, sem acentos, espaços colapsados (inclusive quebras de linha). */
        fun normalize(text: String): String {
            val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return noAccents.lowercase().replace('º', 'o').replace('ª', 'a').replace(Regex("\\s+"), " ")
        }
    }
}

/** Situação do CNPJ lido do documento em relação ao CNPJ da empresa ativa. */
enum class CnpjMatch { IGUAL, OUTRA_FILIAL, DIVERGENTE, DESCONHECIDO }

object CnpjCheck {
    fun isValid(raw: String): Boolean {
        val d = raw.filter(Char::isDigit)
        if (d.length != 14 || d.all { it == d[0] }) return false
        fun dv(len: Int): Int {
            val weights = if (len == 12) intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2) else intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
            val sum = (0 until len).sumOf { (d[it] - '0') * weights[it] }
            val r = sum % 11
            return if (r < 2) 0 else 11 - r
        }
        return dv(12) == d[12] - '0' && dv(13) == d[13] - '0'
    }

    /** Compara pelos dígitos: mesma raiz (8 primeiros) e estabelecimento diferente = outra filial. */
    fun compare(documentCnpj: String?, companyCnpj: String?): CnpjMatch {
        val doc = documentCnpj?.filter(Char::isDigit).orEmpty()
        val company = companyCnpj?.filter(Char::isDigit).orEmpty()
        if (doc.length != 14 || company.length != 14) return CnpjMatch.DESCONHECIDO
        return when {
            doc == company -> CnpjMatch.IGUAL
            doc.take(8) == company.take(8) -> CnpjMatch.OUTRA_FILIAL
            else -> CnpjMatch.DIVERGENTE
        }
    }

    /** Alerta exibido na tela de cadastro; null quando não há o que alertar. */
    fun warning(documentCnpj: String?, companyCnpj: String?): String? = when (compare(documentCnpj, companyCnpj)) {
        CnpjMatch.DIVERGENTE -> "O CNPJ deste documento é diferente do CNPJ da empresa ativa. Confira se o arquivo é da empresa certa."
        CnpjMatch.OUTRA_FILIAL -> "O CNPJ deste documento é de outro estabelecimento (filial/matriz) da mesma empresa. Confira se o edital aceita."
        else -> null
    }
}
