package com.licitaia.domain.edital

import kotlin.math.ln

/** Recorte do edital enviado à IA para responder uma pergunta. */
data class EditalExcerpt(
    val text: String,
    /** true = o edital inteiro coube no limite; false = cabeçalho + trechos selecionados pela pergunta. */
    val complete: Boolean,
    /** Termos (sem acento) buscados no edital: os da pergunta e os sinônimos do assunto. */
    val terms: List<String>,
    /** Quantidade de trechos selecionados além do cabeçalho (0 quando [complete]). */
    val sections: Int,
    /** Tamanho do texto original do edital. */
    val originalChars: Int,
    /** Seções inteiras incluídas porque o título casa com a pergunta (só no recorte). */
    val fullSections: Int = 0,
) {
    /** Documentos da base presentes no texto enviado (pelos marcadores/rótulos de documento). */
    val documents: Int
        get() = (Regex("(?m)^=== DOCUMENTO: (.+?)(?: \\(\\d+\\))? \\(página \\d+\\) ===$").findAll(text).map { it.groupValues[1] } +
            Regex("\\[Documento: (.+?) · página \\d+]").findAll(text).map { it.groupValues[1] }).toSet().size

    /** Metadado da resposta: "lido: base completa" ou "trechos de N documento(s)". */
    val coverageLabel: String
        get() = if (complete) "lido: base completa" else "trechos de ${documents.coerceAtLeast(1)} documento(s)"
}

/**
 * Seleção de trechos do edital para "Pergunte ao edital". Editais que cabem no limite vão inteiros; os grandes viram
 * cabeçalho (objeto, órgão, datas — sempre incluído) + janelas de contexto com os termos da pergunta (busca simples
 * por palavras, acento/caixa ignorados, sinônimos dos assuntos mais comuns), na ordem do documento e marcadas com a
 * página/anexo quando o texto traz esses marcadores. Puro e sem I/O: testável em JVM.
 */
object EditalExcerptSelector {
    /** ~20 mil tokens: cobre o cabeçalho e dezenas de trechos sem encarecer cada pergunta. */
    const val DEFAULT_MAX_CHARS = 80_000

    /**
     * "Pergunte ao edital": até este tamanho (~50 mil tokens, cabe nos provedores atuais) a base INTEIRA (todos os
     * documentos, Edital e TR primeiro) vai para a IA; acima, recorte por pergunta com as seções inteiras cujo título casa.
     */
    const val FULL_BASE_MAX_CHARS = 200_000

    /** Teto de cada seção inteira incluída pelo título e quantas no máximo. */
    const val MAX_SECTION_CHARS = 15_000
    const val MAX_FULL_SECTIONS = 4

    /** Início do documento (objeto, órgão, datas, valor) sempre enviado. */
    const val HEADER_CHARS = 6_000

    /** Tamanho de cada janela de contexto em torno das ocorrências. */
    const val WINDOW_CHARS = 1_600

    /** Rótulo entre trechos não contíguos, para o modelo saber que houve corte. */
    const val GAP_MARKER = "\n[...trecho omitido...]\n"

    /** Contexto antes da primeira ocorrência (título da seção) e depois da última. */
    private const val CONTEXT_BEFORE = 300
    private const val CONTEXT_AFTER = 500

    private const val MIN_TERM_LENGTH = 3
    private const val ROOT_LENGTH = 6

    /** Palavras sem valor de busca (já sem acento). */
    private val STOPWORDS = setOf(
        "que", "qual", "quais", "quando", "onde", "como", "quem", "para", "por", "pela", "pelo", "pelas", "pelos", "com",
        "sem", "dos", "das", "nos", "nas", "uma", "umas", "uns", "este", "esta", "esse", "essa", "isso", "isto", "aquele",
        "edital", "licitacao", "pregao", "sobre", "existe", "existem", "tem", "ter", "sao", "ser", "esta", "estao", "deve",
        "devem", "precisa", "preciso", "pode", "podem", "algum", "alguma", "alguns", "algumas", "mais", "menos", "muito",
        "the", "and", "ha", "nao", "sim", "seu", "sua", "seus", "suas", "ele", "ela", "eles", "elas", "entre", "ate", "apos",
        "obrigatoria", "obrigatorio", "exige", "exigido", "exigida", "necessario", "necessaria", "empresa", "contratada",
    )

    /**
     * Sinônimos por raiz (6 letras, sem acento) dos assuntos que mais aparecem nas perguntas. A raiz que dispara pode
     * vir da pergunta; os valores entram na busca com peso menor que os termos digitados.
     */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "habili" to listOf("habilitacao", "documentacao", "certidao", "regularidade", "qualificacao"),
        "docume" to listOf("habilitacao", "documentacao", "certidao", "regularidade"),
        "prazo" to listOf("prazo", "dias", "entrega", "execucao", "vigencia"),
        "entreg" to listOf("entrega", "prazo", "recebimento", "fornecimento"),
        "execuc" to listOf("execucao", "prazo", "cronograma", "inicio dos servicos"),
        "pagame" to listOf("pagamento", "fatura", "nota fiscal", "liquidacao", "ordem bancaria"),
        "pagar" to listOf("pagamento", "fatura", "nota fiscal"),
        "penali" to listOf("penalidade", "sancao", "sancoes", "multa", "advertencia", "impedimento"),
        "sancao" to listOf("sancao", "sancoes", "penalidade", "multa"),
        "sancoe" to listOf("sancao", "sancoes", "penalidade", "multa"),
        "multa" to listOf("multa", "penalidade", "sancao"),
        "visita" to listOf("visita tecnica", "vistoria"),
        "vistor" to listOf("vistoria", "visita tecnica"),
        "atesta" to listOf("atestado", "capacidade tecnica", "qualificacao tecnica", "acervo tecnico"),
        "capaci" to listOf("capacidade tecnica", "atestado", "qualificacao tecnica"),
        "exclus" to listOf("exclusiva", "exclusivo", "microempresa", "pequeno porte", "me/epp", "lei complementar n 123"),
        "microe" to listOf("microempresa", "pequeno porte", "me/epp", "exclusiva"),
        "epp" to listOf("me/epp", "microempresa", "pequeno porte", "exclusiva", "cota reservada"),
        "sessao" to listOf("sessao publica", "abertura", "horario", "data"),
        "abertu" to listOf("abertura", "sessao publica", "horario"),
        "hora" to listOf("horario", "sessao publica", "abertura", "horas"),
        "data" to listOf("sessao publica", "abertura", "horario"),
        "garant" to listOf("garantia", "caucao", "seguro-garantia", "fianca"),
        "amostr" to listOf("amostra", "prova de conceito"),
        "impugn" to listOf("impugnacao", "esclarecimento"),
        "esclar" to listOf("esclarecimento", "impugnacao"),
        "recurs" to listOf("recurso", "intencao de recorrer"),
        "reajus" to listOf("reajuste", "repactuacao", "indice"),
        "vigenc" to listOf("vigencia", "prorrogacao"),
        "subcon" to listOf("subcontratacao", "subcontratar"),
        "consor" to listOf("consorcio"),
        "criter" to listOf("criterio de julgamento", "menor preco", "maior desconto"),
        "julgam" to listOf("criterio de julgamento", "menor preco", "maior desconto"),
        "valor" to listOf("valor estimado", "valor global", "valor maximo", "orcamento"),
        "estima" to listOf("valor estimado", "orcamento estimado"),
        "propos" to listOf("proposta", "validade da proposta"),
        "validad" to listOf("validade"),
    )

    /**
     * @param text texto integral do edital (já normalizado).
     * @param question pergunta do usuário.
     * @param maxChars limite do recorte (>= 2.000).
     */
    fun select(text: String, question: String, maxChars: Int = DEFAULT_MAX_CHARS): EditalExcerpt {
        val limit = maxChars.coerceAtLeast(2_000)
        val weighted = terms(question)
        val termList = weighted.keys.toList()
        if (text.length <= limit) return EditalExcerpt(text, complete = true, terms = termList, sections = 0, originalChars = text.length)

        val headerEnd = cutBoundary(text, minOf(HEADER_CHARS, limit / 3))
        val header = text.substring(0, headerEnd).trimEnd()
        // Os marcadores de documento ("=== DOCUMENTO: Termo de Referência ...") não contam como ocorrência dos termos.
        val folded = maskDocumentMarkers(fold(text))

        // Pontuação por janela (a partir do fim do cabeçalho): peso do termo atenuado pela frequência no documento.
        val windows = ((text.length - headerEnd) + WINDOW_CHARS - 1) / WINDOW_CHARS
        val scores = DoubleArray(windows)
        val distinct = Array(windows) { HashSet<String>() }
        val firstHit = IntArray(windows) { Int.MAX_VALUE }
        val lastHit = IntArray(windows) { -1 }
        weighted.forEach { (term, base) ->
            val positions = occurrences(folded, term, from = headerEnd)
            if (positions.isEmpty()) return@forEach
            val weight = base / ln(2.0 + positions.size / 4.0)
            positions.forEach { p ->
                val k = ((p - headerEnd) / WINDOW_CHARS).coerceIn(0, windows - 1)
                scores[k] += weight
                distinct[k] += term
                firstHit[k] = minOf(firstHit[k], p)
                lastHit[k] = maxOf(lastHit[k], p)
            }
        }
        val ranked = (0 until windows)
            .filter { scores[it] > 0 }
            .sortedWith(compareByDescending<Int> { scores[it] + 1.5 * distinct[it].size }.thenBy { it })

        // Seções inteiras cujo TÍTULO casa com os termos digitados (ex.: "10. DA HABILITAÇÃO" para "habilitação").
        val typed = weighted.filterValues { it >= 2.0 }.keys
        val sectionRanges = matchingSections(text, folded, typed, headerEnd, limit / 2)

        // Sem nenhum termo encontrado: cabeçalho + continuação do documento até o limite.
        if (ranked.isEmpty() && sectionRanges.isEmpty()) {
            val head = text.substring(0, cutBoundary(text, limit - GAP_MARKER.length)).trimEnd()
            return EditalExcerpt(head + GAP_MARKER.trimEnd(), complete = false, terms = termList, sections = 0, originalChars = text.length)
        }

        // Escolhe as melhores janelas que cabem no orçamento; depois ordena pela posição e une as contíguas.
        var budget = limit - header.length - GAP_MARKER.length
        val chosen = mutableListOf<IntRange>()
        for (range in sectionRanges) {
            val cost = range.last - range.first + 1 + GAP_MARKER.length + LABEL_ALLOWANCE
            if (cost > budget) continue
            chosen += range
            budget -= cost
        }
        // Início de cada página na base multi-documento: a janela não recua para a página anterior, para que o trecho
        // comece no marcador "=== DOCUMENTO: ... (página N) ===" da página onde está a informação (citação correta).
        val pageStarts = DOC_MARKER.findAll(text).map { it.range.first }.toList()
        for (k in ranked) {
            // Janela ancorada nas ocorrências: um pouco antes da primeira (título/número do item) e o contexto que segue.
            val pageStart = pageStarts.lastOrNull { it <= firstHit[k] } ?: -1
            val start = maxOf(headerEnd, firstHit[k] - CONTEXT_BEFORE, pageStart)
            val end = minOf(text.length, maxOf(lastHit[k] + CONTEXT_AFTER, start + WINDOW_CHARS))
            val range = expand(text, start, end)
            val cost = range.last - range.first + 1 + GAP_MARKER.length + LABEL_ALLOWANCE
            if (cost > budget) continue
            chosen += range
            budget -= cost
        }
        val merged = mergeRanges(chosen.sortedBy { it.first })
        val markers = Markers(
            pages = PAGE_MARKER.findAll(text).map { it.range.first to it.groupValues[1] }.toList(),
            annexes = ANNEX_MARKER.findAll(text).map { it.range.first to it.groupValues[1].trim().take(60) }.toList(),
            documents = DOC_MARKER.findAll(text).map { it.range.first to "${it.groupValues[1].trim()} · página ${it.groupValues[2]}" }.toList(),
        )
        val body = buildString {
            append(header)
            var previousEnd = headerEnd
            merged.forEach { range ->
                if (range.first > previousEnd) append(GAP_MARKER) else append('\n')
                markers.label(range.first)?.let { append(it).append('\n') }
                append(text, maxOf(range.first, previousEnd), range.last + 1)
                previousEnd = range.last + 1
            }
            if (previousEnd < text.length) append(GAP_MARKER.trimEnd())
        }
        return EditalExcerpt(
            body.take(limit), complete = false, terms = termList, sections = merged.size, originalChars = text.length,
            fullSections = sectionRanges.count { s -> chosen.any { it == s } },
        )
    }

    /** Título de seção: "10. DA HABILITAÇÃO", "10.2 Qualificação técnica", "CLÁUSULA QUINTA – ...", "ANEXO I – ...". */
    private val HEADING = Regex("""(?m)^[ \t]*((\d{1,2}(?:\.\d{1,2}){0,3})[.)]?\s+\S[^\n]{1,140}|(?:CL[AÁ]USULA|ANEXO|CAP[IÍ]TULO|SE[CÇ][AÃ]O)\b[^\n]{0,140}|[A-ZÁÉÍÓÚÂÊÔÃÕÇ][A-ZÁÉÍÓÚÂÊÔÃÕÇ0-9 ,.;:/()–-]{5,120})$""")

    /**
     * Intervalos das seções cujo título contém algum termo digitado: do título até o próximo título de mesmo nível ou
     * superior (numerados) ou até o próximo título (demais), cada um até [MAX_SECTION_CHARS], no máximo
     * [MAX_FULL_SECTIONS] e [budget] caracteres no total.
     */
    internal fun matchingSections(text: String, folded: String, terms: Set<String>, from: Int, budget: Int): List<IntRange> {
        if (terms.isEmpty()) return emptyList()
        val headings = HEADING.findAll(text).filter { it.range.first >= from }.toList()
        if (headings.isEmpty()) return emptyList()
        fun depth(m: MatchResult): Int? = m.groupValues[2].takeIf { it.isNotEmpty() }?.count { it == '.' }?.plus(1)
        val out = mutableListOf<IntRange>()
        var used = 0
        for ((i, h) in headings.withIndex()) {
            if (out.size >= MAX_FULL_SECTIONS) break
            val title = folded.substring(h.range.first, h.range.last + 1)
            if (terms.none { title.contains(it) }) continue
            val d = depth(h)
            val next = headings.drop(i + 1).firstOrNull { n ->
                val nd = depth(n)
                if (d == null) true else nd != null && nd <= d
            }
            val end = minOf(next?.range?.first ?: text.length, h.range.first + MAX_SECTION_CHARS, text.length)
            if (end - h.range.first < 40) continue
            if (out.any { h.range.first in it }) continue
            if (used + (end - h.range.first) > budget) break
            out += h.range.first until end
            used += end - h.range.first
        }
        return out
    }

    /**
     * Frases/linhas do texto que mais citam os termos da pergunta (heurística sem IA, usada pelo provedor de
     * demonstração). Cada trecho vem aparado em [maxChars] e na ordem do documento.
     */
    fun keySentences(text: String, question: String, limit: Int = 3, maxChars: Int = 320): List<String> {
        val weighted = terms(question)
        if (weighted.isEmpty() || text.isBlank()) return emptyList()
        val sentences = text.split(Regex("(?<=[.;:])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length >= 20 && !it.startsWith("--- ") && !it.startsWith("=== ") && !it.startsWith("[Documento: ") && it != GAP_MARKER.trim() }
        return sentences.mapIndexedNotNull { index, sentence ->
            val folded = fold(sentence)
            val hits = weighted.entries.filter { folded.contains(it.key) }
            if (hits.isEmpty()) null else Triple(index, sentence, hits.sumOf { it.value } + hits.size)
        }
            .sortedWith(compareByDescending<Triple<Int, String, Double>> { it.third }.thenBy { it.first })
            .take(limit.coerceAtLeast(1))
            .sortedBy { it.first }
            .map { (_, sentence, _) -> if (sentence.length <= maxChars) sentence else sentence.take(maxChars - 1).trimEnd() + "…" }
    }

    /** Termos de busca da pergunta → peso (2 = digitado; 1 = sinônimo do assunto). */
    fun terms(question: String): Map<String, Double> {
        val words = Regex("[a-z0-9/]+").findAll(fold(question)).map { it.value.trim('/') }
            .filter { it.length >= MIN_TERM_LENGTH && it !in STOPWORDS && !it.all(Char::isDigit) }
            .toList()
        val result = LinkedHashMap<String, Double>()
        words.forEach { word ->
            val root = root(word)
            result[root] = maxOf(result[root] ?: 0.0, 2.0)
            SYNONYMS.entries.filter { (trigger, _) -> root.startsWith(trigger) || trigger.startsWith(root) && root.length >= 4 }
                .flatMap { it.value }
                .forEach { synonym -> if (synonym !in result) result[synonym] = 1.0 }
        }
        return result
    }

    /** Raiz de busca: a palavra inteira até 6 letras; acima disso, as 6 primeiras (cobre plural/gênero/derivações). */
    internal fun root(word: String): String = if (word.length <= ROOT_LENGTH) word else word.take(ROOT_LENGTH)

    /** Minúsculas e sem acento, preservando o comprimento (índices do texto dobrado = índices do original). */
    fun fold(text: String): String {
        val chars = CharArray(text.length)
        for (i in text.indices) {
            val c = text[i].lowercaseChar()
            val index = ACCENTED.indexOf(c)
            chars[i] = if (index >= 0) PLAIN[index] else c
        }
        return String(chars)
    }

    private const val ACCENTED = "áàâãäéèêëíìîïóòôõöúùûüçñ"
    private const val PLAIN = "aaaaaeeeeiiiiooooouuuucn"

    /** Espaço reservado para o rótulo de página/anexo de cada trecho. */
    private const val LABEL_ALLOWANCE = 48

    private fun occurrences(folded: String, term: String, from: Int): List<Int> {
        val positions = mutableListOf<Int>()
        var index = folded.indexOf(term, from)
        while (index >= 0) {
            // Termo curto só conta no início de palavra ("epp" não casa dentro de outra palavra).
            if (term.length > 4 || index == 0 || !folded[index - 1].isLetterOrDigit()) positions += index
            index = folded.indexOf(term, index + term.length)
        }
        return positions
    }

    /** Estende a janela até a quebra de linha mais próxima (até 300 caracteres) para não cortar o número do item. */
    private fun expand(text: String, start: Int, end: Int): IntRange {
        val before = text.lastIndexOf('\n', start).takeIf { it >= 0 && start - it <= 300 }?.plus(1) ?: start
        val after = text.indexOf('\n', end).takeIf { it >= 0 && it - end <= 300 } ?: end
        return before until minOf(after, text.length)
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        val merged = mutableListOf<IntRange>()
        ranges.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * Marcadores já localizados no texto — página (OCR), anexo (download antigo) e documento+página da base de
     * documentos (`=== DOCUMENTO: ... (página N) ===`): (posição, rótulo).
     */
    private class Markers(
        val pages: List<Pair<Int, String>>,
        val annexes: List<Pair<Int, String>>,
        val documents: List<Pair<Int, String>> = emptyList(),
    ) {
        /**
         * "[Documento: Termo de Referência — tr.pdf · página 3]" (base multi-documento) ou "[Página 12]" /
         * "[Anexo: Termo de Referência]" conforme o último marcador antes de [position]. Para a IA citar a fonte.
         */
        fun label(position: Int): String? {
            documents.lastOrNull { it.first <= position }?.let { return "[Documento: ${it.second}]" }
            val page = pages.lastOrNull { it.first < position }
            val annex = annexes.lastOrNull { it.first < position }
            val parts = buildList {
                if (annex != null) add("Anexo: ${annex.second}")
                if (page != null && (annex == null || page.first > annex.first)) add("Página ${page.second}")
            }
            return if (parts.isEmpty()) null else "[${parts.joinToString(" · ")}]"
        }
    }

    private val PAGE_MARKER = Regex("--- Página (\\d+) ---")
    private val ANNEX_MARKER = Regex("--- Anexo: (.+?) ---")
    private val DOC_MARKER = Regex("(?m)^=== DOCUMENTO: (.+) \\(página (\\d+)\\) ===$")
    private val FOLDED_DOC_MARKER = Regex("(?m)^=== documento: .+ \\(pagina \\d+\\) ===$")

    /** Troca por espaços (mesmo comprimento) as linhas de marcador de documento do texto já dobrado. */
    private fun maskDocumentMarkers(folded: String): String {
        if (!folded.contains("=== documento: ")) return folded
        val chars = folded.toCharArray()
        FOLDED_DOC_MARKER.findAll(folded).forEach { match -> for (i in match.range) chars[i] = ' ' }
        return String(chars)
    }

    private fun cutBoundary(text: String, max: Int): Int {
        if (text.length <= max) return text.length
        val slice = text.substring(0, max)
        val boundary = maxOf(slice.lastIndexOf('\n'), slice.lastIndexOf(". "))
        return if (boundary > max * 0.6) boundary + 1 else max
    }
}
