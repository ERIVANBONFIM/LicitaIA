package com.licitaia.core.ai

import com.licitaia.ai.api.RelevanceItem
import com.licitaia.ai.api.RelevanceScore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * Prompt e leitura tolerante da nota de relevância em lote. O modelo recebe ids curtos ("1".."n") em vez dos ids
 * longos das oportunidades (menos tokens e nada a "corrigir"); a resposta é mapeada de volta pelo [prompt].
 */
internal object RelevanceParsing {

    const val SYSTEM =
        "Você classifica licitações públicas brasileiras para uma empresa fornecedora. Seja rigoroso: nota alta só quando " +
            "o OBJETO é algo que a empresa fornece (internet/web citada apenas como meio de execução não conta). " +
            "Responda SOMENTE com um objeto JSON válido, sem markdown."

    private const val MAX_OBJECT_CHARS = 400
    private const val MAX_REASON_CHARS = 90

    fun prompt(radarHint: String, items: List<RelevanceItem>): String = buildString {
        appendLine("EMPRESA: $radarHint.")
        appendLine("Para cada licitação, responda: o objeto é algo que esta empresa fornece?")
        appendLine("Nota 0-100 (90-100 = é exatamente o que fornece; 60-89 = parte relevante do objeto; 30-59 = marginal; 0-29 = não fornece).")
        appendLine("Devolva {\"notas\":[{\"id\":\"1\",\"score\":0,\"motivo\":\"até 12 palavras\"}]} com TODOS os ids.")
        appendLine()
        items.forEachIndexed { index, item ->
            val obj = item.objectDescription.replace(Regex("\\s+"), " ").trim().take(MAX_OBJECT_CHARS)
            appendLine("${index + 1}) [${item.modality}] ${item.agency.trim().take(80)} — $obj")
        }
    }

    /** Lê a resposta e devolve as notas com os ids ORIGINAIS de [items]; ids desconhecidos/duplicados são ignorados. */
    fun parse(raw: String, items: List<RelevanceItem>, json: Json): List<RelevanceScore> {
        val entries = entries(raw, json) ?: return emptyList()
        val seen = HashSet<String>()
        return entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val shortId = (obj.str("id") ?: obj.str("i") ?: obj.str("item"))?.trim()?.trimEnd(')', '.')
                ?: return@mapNotNull null
            val index = shortId.toIntOrNull()?.minus(1)
            val original = when {
                index != null && index in items.indices -> items[index]
                else -> items.firstOrNull { it.id == shortId }
            } ?: return@mapNotNull null
            val score = (obj.num("score") ?: obj.num("nota") ?: obj.num("relevancia"))?.roundToInt()
                ?.takeIf { it in 0..100 } ?: return@mapNotNull null
            if (!seen.add(original.id)) return@mapNotNull null
            val reason = (obj.str("motivo") ?: obj.str("reason") ?: obj.str("justificativa")).orEmpty()
                .replace(Regex("\\s+"), " ").trim().take(MAX_REASON_CHARS)
            RelevanceScore(original.id, score, reason)
        }
    }

    /** Aceita {"notas":[...]}, qualquer array dentro de um objeto, um array no topo ou um único objeto. */
    private fun entries(raw: String, json: Json): List<JsonElement>? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: extractArray(text, json)
            ?: extractJsonObject(text, json)
            ?: return null
        return when (element) {
            is JsonArray -> element
            is JsonObject -> {
                val named = listOf("notas", "itens", "items", "results", "resultados", "scores")
                    .firstNotNullOfOrNull { element[it] as? JsonArray }
                named ?: element.values.firstOrNull { it is JsonArray } as? JsonArray
                    ?: if (element.containsKey("id")) listOf(element) else null
            }
            else -> null
        }
    }

    private fun extractArray(text: String, json: Json): JsonElement? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        // Só quando o array vem antes de qualquer objeto externo (senão o objeto é que é o envelope).
        val firstBrace = text.indexOf('{')
        if (firstBrace in 0 until start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
    }
}
