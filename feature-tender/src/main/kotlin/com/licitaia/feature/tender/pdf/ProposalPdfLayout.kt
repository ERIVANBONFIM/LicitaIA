package com.licitaia.feature.tender.pdf

import com.licitaia.domain.model.Company
import com.licitaia.domain.util.Formatters
import com.licitaia.domain.util.bankLine
import com.licitaia.domain.util.contactLine
import com.licitaia.domain.util.fullAddress
import com.licitaia.domain.util.streetLine
import java.text.Normalizer

/**
 * Regras puras do layout do PDF da proposta (sem android.graphics), testadas na JVM:
 * quebra de linha sem cortar texto, paginação da planilha de itens e nome do arquivo.
 */
internal object ProposalPdfLayout {

    /**
     * Quebra [text] em linhas de até [maxWidth] (medidas por [measure]). Respeita quebras de linha do texto e nunca
     * descarta conteúdo: palavras maiores que a largura são partidas por caractere.
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val result = mutableListOf<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').split('\n').forEach { paragraph ->
            var current = ""
            paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (measure(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) result += current
                    current = ""
                    if (measure(word) <= maxWidth) {
                        current = word
                    } else {
                        // palavra/sequência sem espaço maior que a coluna: parte por caractere
                        var chunk = ""
                        word.forEach { ch ->
                            if (chunk.isNotEmpty() && measure(chunk + ch) > maxWidth) {
                                result += chunk
                                chunk = ""
                            }
                            chunk += ch
                        }
                        current = chunk
                    }
                }
            }
            result += current
        }
        // remove linhas vazias no fim (parágrafo final vazio), mantém ao menos uma
        while (result.size > 1 && result.last().isEmpty()) result.removeAt(result.lastIndex)
        return result.ifEmpty { listOf("") }
    }

    /** Trecho de uma linha da planilha desenhado em uma página: linhas [fromLine, toLine) do item [item]. */
    data class Segment(val item: Int, val fromLine: Int, val toLine: Int, val newPageBefore: Boolean)

    /**
     * Distribui as linhas da planilha pelas páginas. [lineCounts] = linhas de cada item; a primeira página da planilha
     * começa em [startY]; páginas seguintes começam em [pageTop] + [headerHeight] (cabeçalho da tabela repetido).
     * Um item que não cabe é levado inteiro para a próxima página; um item maior que a página é partido em trechos.
     */
    fun paginateTable(
        lineCounts: List<Int>,
        lineHeight: Float,
        rowPadding: Float,
        startY: Float,
        pageTop: Float,
        bottom: Float,
        headerHeight: Float,
    ): List<Segment> {
        require(lineHeight > 0f)
        val freshY = pageTop + headerHeight
        val freshCapacity = ((bottom - freshY - rowPadding) / lineHeight).toInt().coerceAtLeast(1)
        val segments = mutableListOf<Segment>()
        var y = startY
        lineCounts.forEachIndexed { index, count ->
            val total = count.coerceAtLeast(1)
            var from = 0
            while (from < total) {
                val remaining = total - from
                var available = ((bottom - y - rowPadding) / lineHeight).toInt()
                var newPage = false
                // não cabe inteiro: quebra a página se o item (ou o resto) cabe numa página nova ou se sobraria pouco aqui
                val fitsWhole = remaining <= available
                if (!fitsWhole && (remaining <= freshCapacity || available < MIN_LINES_PER_SEGMENT)) {
                    newPage = true
                    y = freshY
                    available = freshCapacity
                }
                val take = minOf(remaining, available.coerceAtLeast(1))
                segments += Segment(index, from, from + take, newPage)
                y += take * lineHeight + rowPadding
                from += take
            }
        }
        return segments
    }

    /** Menor trecho de um item longo deixado no fim de uma página antes de continuar na próxima. */
    private const val MIN_LINES_PER_SEGMENT = 3

    /** Número de páginas de uma lista de segmentos (a primeira página da planilha conta como 1). */
    fun pagesUsed(segments: List<Segment>): Int = 1 + segments.count { it.newPageBefore }

    // ------------------------------------------------------------------ dados da proponente (campos vazios não aparecem)

    /**
     * Linhas do cabeçalho abaixo da razão social, por prioridade de exibição: CNPJ, endereço completo, contato e nome
     * fantasia. Se só couberem [maxLines], o nome fantasia (já presente na seção "Proponente") é o primeiro a sair.
     */
    fun headerLines(company: Company, maxLines: Int): List<String> {
        val trade = company.tradeName.trim().takeIf { it.isNotEmpty() && !it.equals(company.name.trim(), ignoreCase = true) }?.let { "Nome fantasia: $it" }
        val essential = listOfNotNull(
            "CNPJ ${Formatters.cnpj(company.cnpj)}",
            company.fullAddress().takeIf { it.isNotEmpty() },
            company.contactLine().takeIf { it.isNotEmpty() },
        )
        val all = listOfNotNull(trade) + essential
        return (if (all.size <= maxLines) all else essential).take(maxLines.coerceAtLeast(0))
    }

    /** Pares rótulo → valor da seção "Proponente", na ordem, já formatados. */
    fun bidderFields(company: Company): List<Pair<String, String>> = buildList {
        add("Razão social" to company.name)
        company.tradeName.trim().takeIf { it.isNotEmpty() && !it.equals(company.name.trim(), ignoreCase = true) }?.let { add("Nome fantasia" to it) }
        add("CNPJ" to Formatters.cnpj(company.cnpj))
        company.streetLine().takeIf { it.isNotEmpty() }?.let { add("Endereço" to it) }
        listOf(company.city.trim(), company.uf.trim()).filter { it.isNotEmpty() }.joinToString("/").takeIf { it.isNotEmpty() }?.let { add("Município/UF" to it) }
        company.zipCode.takeIf { it.isNotBlank() }?.let { add("CEP" to Formatters.cep(it)) }
        company.phone.takeIf { it.isNotBlank() }?.let { add("Telefone" to Formatters.phone(it)) }
        company.email.trim().takeIf { it.isNotEmpty() }?.let { add("E-mail" to it) }
        company.bankLine().takeIf { it.isNotEmpty() }?.let { add("Dados bancários" to it) }
        company.legalRepName.trim().takeIf { it.isNotEmpty() }?.let { name ->
            add("Representante legal" to listOf(name, company.legalRepRole.trim()).filter { it.isNotEmpty() }.joinToString(" – "))
        }
        company.legalRepCpf.takeIf { it.isNotBlank() }?.let { add("CPF do representante" to Formatters.cpf(it)) }
    }

    /**
     * Linhas do bloco de assinatura (abaixo da linha): nome do representante legal (ou [fallbackName], quem aprovou/criou),
     * cargo e CPF — com linha em branco para o CPF quando ele não está no cadastro —, razão social e CNPJ.
     */
    fun signatureLines(company: Company, fallbackName: String?): List<String> {
        val name = company.legalRepName.trim().ifBlank { fallbackName?.trim().orEmpty() }.ifBlank { "Representante legal" }
        val role = company.legalRepRole.trim().ifBlank { "Representante legal" }
        val cpf = company.legalRepCpf.takeIf { it.isNotBlank() }?.let { Formatters.cpf(it) } ?: "____________________"
        return listOf(name, "$role · CPF: $cpf", company.name, "CNPJ ${Formatters.cnpj(company.cnpj)}")
    }

    /** `Proposta_<numero>_<orgao>_v<versao>.pdf` sem acentos, espaços ou caracteres inválidos em nomes de arquivo. */
    fun fileName(number: String, agency: String, version: Int): String {
        val n = sanitize(number, 30).ifEmpty { "sem-numero" }
        val a = sanitize(agency, 40).ifEmpty { "orgao" }
        return "Proposta_${n}_${a}_v$version.pdf"
    }

    fun sanitize(text: String, max: Int): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return plain.replace(Regex("""[^A-Za-z0-9]+"""), "-").trim('-').take(max).trim('-')
    }
}
