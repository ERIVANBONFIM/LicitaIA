package com.licitaia.core.data.edital

import java.text.Normalizer
import java.util.Locale

/**
 * Prepara o texto do edital para o prompt da IA: normaliza espaços e, quando o texto excede o
 * limite, mantém o início (objeto, datas, valor) e prioriza os trechos com as seções mais
 * relevantes para a decisão (habilitação, documentos, prazos, garantias, penalidades).
 * Sem I/O: testável em JVM.
 */
object EditalTextPreparer {

    const val DEFAULT_MAX_CHARS = 60_000

    /** Rótulo inserido entre trechos não contíguos para o modelo saber que houve corte. */
    const val GAP_MARKER = "\n[...trecho omitido...]\n"

    /** Palavras-chave (sem acento, minúsculas) que elevam a prioridade de um bloco. */
    val PRIORITY_TERMS: List<String> = listOf(
        "habilitacao", "documentos", "documentacao", "qualificacao tecnica", "qualificacao economico",
        "prazo", "garantia", "penalidade", "sancoes", "sancao", "multa", "atestado", "certidao",
        "proposta", "julgamento", "criterio", "visita tecnica", "amostra", "vigencia", "pagamento", "reajuste",
    )

    /**
     * @param text texto bruto extraído/colado.
     * @param maxChars limite de caracteres do resultado (>= 2.000).
     */
    fun prepare(text: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val limit = maxChars.coerceAtLeast(2_000)
        val clean = normalize(text)
        if (clean.length <= limit) return clean

        // Cabeça: ~40% do limite com o início do documento (objeto, órgão, datas, valor estimado).
        val headBudget = (limit * 0.4).toInt()
        val head = cutAtBoundary(clean, headBudget)
        val remaining = limit - head.length - GAP_MARKER.length
        if (remaining <= 0) return head

        // Restante do texto dividido em blocos (parágrafos) com pontuação por relevância.
        val rest = clean.substring(head.length)
        val blocks = splitBlocks(rest)
        if (blocks.isEmpty()) return head

        val scored = blocks.mapIndexed { index, block -> ScoredBlock(index, block, score(block)) }
        val selected = mutableListOf<ScoredBlock>()
        var used = 0
        // Primeiro os blocos com termos prioritários (maior score, depois ordem original), depois o que couber.
        for (candidate in scored.sortedWith(compareByDescending<ScoredBlock> { it.score }.thenBy { it.index })) {
            val cost = candidate.text.length + 1
            if (used + cost > remaining) {
                // Bloco grande e relevante: corta para caber, se ainda há espaço útil.
                val room = remaining - used
                if (candidate.score > 0 && room > 400) {
                    selected += candidate.copy(text = cutAtBoundary(candidate.text, room - 1))
                    used = remaining
                }
                if (used >= remaining) break
                continue
            }
            selected += candidate
            used += cost
        }
        if (selected.isEmpty()) return head

        // Reordena na ordem do documento e marca descontinuidades.
        val ordered = selected.sortedBy { it.index }
        val body = buildString {
            var previous = -1
            ordered.forEach { block ->
                if (previous >= 0 && block.index != previous + 1) append(GAP_MARKER) else if (previous >= 0) append('\n')
                append(block.text)
                previous = block.index
            }
        }
        val gapBeforeBody = if (ordered.first().index == 0) "\n" else GAP_MARKER
        return (head + gapBeforeBody + body).take(limit)
    }

    /** Pontuação de relevância do bloco: ocorrências de termos prioritários (acento/caixa ignorados). */
    fun score(block: String): Int {
        val folded = fold(block)
        var total = 0
        PRIORITY_TERMS.forEach { term ->
            var index = folded.indexOf(term)
            while (index >= 0) {
                total++
                index = folded.indexOf(term, index + term.length)
            }
        }
        // Títulos de seção ("7. DA HABILITAÇÃO") pesam mais que menções soltas.
        if (Regex("^\\s*(\\d+(\\.\\d+)*\\s*[-–.)]?\\s*)?(d[ao]s?\\s+)?[A-ZÇÃÕÁÉÍÓÚÂÊÔ ]{6,}\\s*$", RegexOption.MULTILINE).containsMatchIn(block) && total > 0) total += 2
        return total
    }

    /** Remove caracteres de controle, normaliza quebras e colapsa espaços repetidos preservando parágrafos. */
    fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFD]"), "")
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /** Divide em blocos por parágrafo; parágrafos muito longos são fatiados em ~1.500 caracteres. */
    internal fun splitBlocks(text: String): List<String> = text.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { paragraph -> if (paragraph.length <= 1_500) listOf(paragraph) else paragraph.chunked(1_500) }

    private fun cutAtBoundary(text: String, max: Int): String {
        if (text.length <= max) return text
        val slice = text.substring(0, max)
        val boundary = maxOf(slice.lastIndexOf('\n'), slice.lastIndexOf(". "))
        return if (boundary > max * 0.6) slice.substring(0, boundary + 1).trimEnd() else slice.trimEnd()
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    private data class ScoredBlock(val index: Int, val text: String, val score: Int)
}
