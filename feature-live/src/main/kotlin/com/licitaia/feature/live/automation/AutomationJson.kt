package com.licitaia.feature.live.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Leitura das respostas de `evaluateJavascript` (pura). O WebView devolve o valor JS codificado em JSON: os scripts
 * retornam `JSON.stringify(...)`, então o callback recebe uma STRING JSON contendo outro JSON.
 */
object AutomationJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Desembrulha o retorno do WebView até o objeto; null se vazio/"null"/inválido. */
    fun obj(raw: String?): JsonObject? {
        val r = raw?.trim().orEmpty()
        if (r.isEmpty() || r == "null" || r == "undefined") return null
        return runCatching {
            var el: JsonElement = json.parseToJsonElement(r)
            if (el is JsonPrimitive && el.isString) el = json.parseToJsonElement(el.content)
            el as? JsonObject
        }.getOrNull()
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    fun findResult(o: JsonObject?): FindResult {
        if (o == null || !o.bool("found")) return FindResult(false)
        return FindResult(
            found = true,
            locatorIndex = o.int("i") ?: 0,
            count = o.int("n") ?: 1,
            css = o.str("css")?.takeIf { it.isNotBlank() },
            text = o.str("text").orEmpty(),
            tag = o.str("tag").orEmpty(),
        )
    }

    fun parseFind(raw: String?): FindResult = findResult(obj(raw))

    fun parseAction(raw: String?): ActionResult {
        val o = obj(raw) ?: return ActionResult(false, error = "sem resposta da página")
        if (!o.bool("found")) return ActionResult(false, FindResult(false), error = o.str("error") ?: "não encontrado")
        val res = findResult((o["res"] as? JsonObject)?.let { JsonObject(it + ("found" to JsonPrimitive(true))) })
        val ok = o.bool("ok")
        return ActionResult(ok, res, readBack = o.str("readBack"), error = if (ok) null else o.str("error") ?: "falhou")
    }

    fun parseRead(raw: String?): String? = obj(raw)?.takeIf { it.bool("found") }?.str("value")

    fun parseProbe(raw: String?): PageProbe {
        val o = obj(raw) ?: return PageProbe()
        return PageProbe(
            url = o.str("url").orEmpty(),
            title = o.str("title").orEmpty(),
            text = o.str("text").orEmpty(),
            hasCaptcha = o.bool("cap"),
            hasOtpField = o.bool("otp"),
            dialogs = o.strings("dialogs"),
        )
    }

    fun parseRows(raw: String?): List<String> = obj(raw)?.strings("rows") ?: emptyList()

    fun parseTablesHtml(raw: String?): String = obj(raw)?.str("html").orEmpty()

    /** Objeto do snapshot já desembrulhado (texto JSON) ou null. */
    fun snapshotText(raw: String?): String? = obj(raw)?.takeIf { it["error"] == null }?.toString()
}
