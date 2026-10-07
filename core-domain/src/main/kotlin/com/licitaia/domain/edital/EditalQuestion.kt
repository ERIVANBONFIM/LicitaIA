package com.licitaia.domain.edital

import com.licitaia.domain.model.Tender
import com.licitaia.domain.proposal.OfficialProposalBuilder
import com.licitaia.domain.proposal.OfficialTenderItem
import com.licitaia.domain.util.Formatters

/** Situação de uma pergunta ao edital. PENDENTE = gravada e aguardando a IA (ou interrompida). */
enum class EditalQuestionStatus(val label: String) {
    PENDENTE("Respondendo"),
    OK("Respondida"),
    ERRO("Erro"),
}

/**
 * Pergunta feita ao edital de uma licitação e a resposta da IA. Gravada SEMPRE (inclusive com erro) no histórico
 * da licitação; o histórico é por empresa e some junto com a licitação.
 */
data class EditalQuestion(
    val id: Long = 0,
    val companyId: Long,
    val tenderId: Long,
    val question: String,
    /** Resposta sem as linhas "Fonte:"; com [EditalQuestionStatus.ERRO], o motivo da falha. */
    val answer: String = "",
    /** Nome do provedor que respondeu ("ChatGPT", "Heurística local (sem IA)"...). */
    val provider: String = "",
    val model: String? = null,
    /** Citações extraídas das linhas "Fonte: ..." da resposta (item, seção, página, anexo). */
    val sources: List<String> = emptyList(),
    val createdAt: Long,
    val status: EditalQuestionStatus = EditalQuestionStatus.PENDENTE,
) {
    /**
     * Pós-checagem: resposta "respondida" sem nenhuma linha "Fonte:" (e que não é o "não encontrei"). A tela marca
     * com "Sem fonte — confira", porque toda informação do edital deve vir com documento/página.
     */
    val unsourced: Boolean
        get() = status == EditalQuestionStatus.OK && sources.isEmpty() && !EditalQuestionPrompt.isNotFound(answer)

    /** Texto completo para copiar: resposta + fontes. */
    val shareText: String
        get() = buildString {
            append(answer.trim())
            sources.forEach { append("\nFonte: ").append(it) }
        }
}

/**
 * Prompt do "Pergunte ao edital": instrui a IA a responder SÓ com base no edital, citar a fonte (item/seção/página) e
 * dizer claramente quando a informação não está no texto. Também extrai as citações da resposta. Puro: testável em JVM.
 */
object EditalQuestionPrompt {
    /** Pergunta maior que isso é recusada (o campo da tela também limita). */
    const val MAX_QUESTION_CHARS = 1_000

    /** Itens oficiais enviados no prompt (os demais são resumidos numa linha). */
    const val MAX_ITEMS = 60
    private const val MAX_ITEM_DESCRIPTION = 300

    /** Frase EXATA (e procurada nos testes/pós-checagem) para informação ausente nos documentos. */
    const val NOT_FOUND = "Não encontrei essa informação nos documentos da licitação."

    /** A resposta é o "não encontrei" (com ou sem a sugestão de onde procurar). */
    fun isNotFound(answer: String): Boolean {
        val folded = EditalExcerptSelector.fold(answer.trim())
        return folded.startsWith("nao encontrei")
    }

    val SYSTEM: String =
        "Você responde perguntas sobre os DOCUMENTOS OFICIAIS de UMA licitação pública brasileira (edital, termo de " +
            "referência, anexos, estudo técnico preliminar, minuta etc.). Cada página do material começa com o marcador " +
            "\"=== DOCUMENTO: <documento> (página N) ===\" e cada trecho recortado traz \"[Documento: <documento> · página N]\". " +
            "REGRAS OBRIGATÓRIAS: " +
            "1) Responda SOMENTE com informação escrita literalmente nos trechos enviados e nos itens oficiais listados. Não use " +
            "conhecimento externo, não presuma cláusulas \"padrão\" da Lei nº 14.133/2021 e não complete lacunas. " +
            "2) NUNCA infira, calcule, arredonde ou estime valores, datas, prazos, quantidades ou percentuais: transcreva-os exatamente " +
            "como estão no texto. Se dois documentos divergirem, mostre os dois, cada um com sua fonte. " +
            "3) Toda afirmação precisa de fonte. Termine com uma ou mais linhas no formato " +
            "\"Fonte: <documento> — <item/cláusula, se houver> — página N\", usando o nome do documento e a página dos marcadores " +
            "(ex.: \"Fonte: Termo de Referência — tr.pdf — item 5.1 — página 4\"). " +
            "4) Se a informação NÃO estiver nos trechos, responda exatamente \"$NOT_FOUND\" e, na linha seguinte, " +
            "\"Sugestão: procure em <documento provável>\" (ex.: Termo de Referência para local/prazo de entrega, Edital para " +
            "habilitação e sessão, Minuta para pagamento e sanções); nesse caso não escreva linha \"Fonte:\". Quando o material for " +
            "só um recorte, diga também que a informação pode estar em trecho não enviado. " +
            "Escreva em português do Brasil, de forma concisa e direta (até 8 linhas ou uma lista curta), sem tabelas nem títulos."

    /**
     * Pergunta do botão "Buscar no edital" (detalhe do item): endereço/local de entrega ou execução do item. O texto é
     * fixo por item, para a tela reencontrar a resposta já gravada no histórico de perguntas.
     */
    fun deliveryQuestion(item: OfficialTenderItem): String {
        val summary = OfficialProposalBuilder.summarize(item.description, 90).ifBlank { "sem descrição" }
        val text = "Qual é o endereço/local de entrega ou de execução do item ${item.number} ($summary)? " +
            "Transcreva o endereço exatamente como consta nos documentos e, se estiver no mesmo trecho, o prazo de entrega."
        // Mesma normalização do repositório ao gravar a pergunta (espaços colapsados), para reencontrá-la.
        return text.trim().replace(Regex("\\s+"), " ").take(MAX_QUESTION_CHARS)
    }

    /** A pergunta fala de itens/quantidades/valores: vale enviar os itens oficiais junto. */
    fun wantsItems(question: String): Boolean {
        val folded = EditalExcerptSelector.fold(question)
        return ITEM_TERMS.any { folded.contains(it) }
    }

    private val ITEM_TERMS = listOf(
        "item", "itens", "lote", "quantidade", "quantos", "unidade", "valor", "preco", "estimad", "especifica", "produto",
        "material", "materiais", "servico", "descri", "sigilos", "catmat", "catser", "marca", "total", "orcamento",
    )

    /**
     * Mensagem do usuário: pergunta, metadados da licitação, itens oficiais (quando houver) e o texto/recorte do edital.
     */
    fun build(
        tender: Tender,
        question: String,
        excerpt: EditalExcerpt,
        items: List<OfficialTenderItem> = emptyList(),
        /** Documentos da base de perguntas (nomes dos marcadores), para a IA saber o que existe e sugerir onde procurar. */
        documents: List<String> = emptyList(),
    ): String = buildString {
        appendLine("PERGUNTA: ${question.trim()}")
        appendLine()
        appendLine("LICITAÇÃO (metadados do cadastro; prefira o texto do edital quando divergirem):")
        appendLine("- ${tender.modality.label} nº ${tender.number} — ${tender.agency} (${tender.city}/${tender.uf}), portal ${tender.portal.displayName}")
        appendLine("- Objeto: ${tender.objectDescription}")
        appendLine("- Valor estimado: ${Formatters.brl(tender.estimatedValue)} | Propostas até ${Formatters.dateTime(tender.proposalDeadline)} | Sessão em ${Formatters.dateTime(tender.sessionAt)}")
        if (items.isNotEmpty()) {
            appendLine()
            appendLine("ITENS OFICIAIS PUBLICADOS (PNCP/Compras.gov.br):")
            items.sortedBy { it.number }.take(MAX_ITEMS).forEach { appendLine(describeItem(it)) }
            if (items.size > MAX_ITEMS) appendLine("- (+${items.size - MAX_ITEMS} item(ns) não listados)")
        }
        appendLine()
        if (documents.isNotEmpty()) appendLine("DOCUMENTOS NA BASE: ${documents.joinToString("; ")}")
        appendLine(
            if (excerpt.complete) {
                "TEXTO DOS DOCUMENTOS (completo):"
            } else {
                "TEXTO DOS DOCUMENTOS (recorte: cabeçalho + ${excerpt.sections} trecho(s) selecionado(s) pelos termos da pergunta, de " +
                    "${excerpt.originalChars} caracteres; \"[...trecho omitido...]\" marca cortes):"
            },
        )
        append(excerpt.text)
    }

    private fun describeItem(item: OfficialTenderItem): String = buildString {
        append("- Item ${item.number}: ")
        append(OfficialProposalBuilder.summarize(item.description, MAX_ITEM_DESCRIPTION).ifBlank { "(sem descrição)" })
        append(" | ${formatQuantity(item.quantity)} ${item.unit.ifBlank { "un" }}")
        when {
            item.confidentialBudget -> append(" | valor sigiloso")
            item.estimatedUnitPrice != null -> append(" | unitário estimado ${Formatters.brl(item.estimatedUnitPrice)}")
        }
        item.referenceTotal?.takeIf { !item.confidentialBudget }?.let { append(" | total ${Formatters.brl(it)}") }
        item.benefit?.takeIf { it.isNotBlank() }?.let { append(" | $it") }
        item.judgingCriterion?.takeIf { it.isNotBlank() }?.let { append(" | critério: $it") }
        item.complementaryInfo?.takeIf { it.isNotBlank() }?.let { append(" | inf. complementar: ${OfficialProposalBuilder.summarize(it, 200)}") }
    }

    private fun formatQuantity(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(java.util.Locale("pt", "BR"), "%.2f", value)

    /** Linha "Fonte: ..." / "Fontes: ..." (com marcador de lista ou negrito opcionais). */
    private val SOURCE_LINE = Regex("^\\s*(?:[-•*]\\s*)?\\**\\s*fontes?\\s*\\**\\s*:\\s*\\**\\s*(.*)$", RegexOption.IGNORE_CASE)

    /** Separa a resposta em corpo (sem as linhas de fonte) e citações. */
    fun splitSources(answer: String): Pair<String, List<String>> {
        val body = mutableListOf<String>()
        val sources = mutableListOf<String>()
        answer.trim().lines().forEach { line ->
            val match = SOURCE_LINE.find(line)
            if (match != null) {
                match.groupValues[1].trim().trimEnd('*').trim().takeIf { it.isNotEmpty() }?.let { sources += it }
            } else {
                body += line
            }
        }
        return body.joinToString("\n").trim() to sources.distinct().take(MAX_SOURCES)
    }

    private const val MAX_SOURCES = 8
}
