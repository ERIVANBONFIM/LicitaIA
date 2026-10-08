package com.licitaia.feature.live.automation

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** FileProvider próprio (classe distinta para não colidir com o provider de atualização do app no manifest). */
class PortalMapFileProvider : FileProvider()

/**
 * Armazenamento local do motor de automação (arquivos privados do app, nunca na nuvem):
 * - `portal_automation/learned.json`: mapa aprendido (alvo → seletor CSS estável);
 * - `portal_map/snapshots.jsonl`: snapshots do MODO MAPEAR (estrutura das telas, sem valores digitados, sanitizada),
 *   exportáveis por "Compartilhar" para validarmos as telas reais.
 */
@Singleton
class PortalAutomationStorage @Inject constructor(@ApplicationContext private val app: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { app.getSharedPreferences("portal_automation", Context.MODE_PRIVATE) }

    // ------------------------------------------------------------ mapa aprendido

    private val learnedFile get() = File(File(app.filesDir, "portal_automation").apply { mkdirs() }, "learned.json")

    val learned: LearnedSelectors by lazy { LearnedSelectors(runCatching { decodeLearned(learnedFile.readText()) }.getOrDefault(emptyMap())) }

    suspend fun saveLearned() = withContext(Dispatchers.IO) {
        runCatching { learnedFile.writeText(encodeLearned(learned.snapshot())) }
        Unit
    }

    fun encodeLearned(map: Map<String, LearnedSelectors.Entry>): String = buildJsonObject {
        put("version", 1)
        put("entries", JsonObject(map.mapValues { (_, e) -> buildJsonObject { put("css", e.css); put("hits", e.hits); put("at", e.updatedAt) } }))
    }.toString()

    fun decodeLearned(text: String): Map<String, LearnedSelectors.Entry> {
        val root = json.parseToJsonElement(text).jsonObject
        val entries = root["entries"]?.jsonObject ?: return emptyMap()
        return entries.mapNotNull { (k, v) ->
            runCatching {
                val o = v.jsonObject
                val css = o["css"]!!.jsonPrimitive.content
                if (!LearnedSelectors.isStableCss(css)) null else k to LearnedSelectors.Entry(css, o["hits"]?.jsonPrimitive?.int ?: 1, o["at"]?.jsonPrimitive?.long ?: 0L)
            }.getOrNull()
        }.toMap()
    }

    suspend fun clearLearned() = withContext(Dispatchers.IO) {
        learned.snapshot().keys.forEach { k -> learned.forget(k.substringBefore('|'), k.substringAfter('|')) }
        runCatching { learnedFile.delete() }
        Unit
    }

    // ------------------------------------------------------------ MODO MAPEAR

    private val _mapMode = MutableStateFlow(prefs.getBoolean(KEY_MAP_MODE, false))
    /** Modo mapear ligado: cada página do portal aberta no navegador interno grava um snapshot estrutural. */
    val mapMode: StateFlow<Boolean> = _mapMode.asStateFlow()

    private val _snapshotCount = MutableStateFlow(0)
    val snapshotCount: StateFlow<Int> = _snapshotCount.asStateFlow()

    fun setMapMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_MAP_MODE, on).apply()
        _mapMode.value = on
    }

    private val mapDir get() = File(app.filesDir, "portal_map").apply { mkdirs() }
    private val mapFile get() = File(mapDir, "snapshots.jsonl")

    /** Último snapshot por página nesta execução (evita gravar a mesma tela repetidamente). */
    private val lastByPage = HashMap<String, Int>()

    /**
     * Grava um snapshot (JSON do [AutomationScripts.snapshot]) já sanitizado. [reason] = "auto" (modo mapear) ou
     * "manual" ("Mapear esta tela"). Arquivo limitado a ~3 MB (descarta os mais antigos).
     */
    suspend fun recordSnapshot(url: String, snapshotJson: String, reason: String, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        if (snapshotJson.isBlank()) return@withContext false
        val page = LearnedSelectors.pageKey(url)
        val clean = SnapshotSanitizer.clean(snapshotJson)
        val hash = clean.hashCode()
        if (reason == "auto" && lastByPage[page] == hash) return@withContext false
        lastByPage[page] = hash
        val parsed = runCatching { json.parseToJsonElement(clean) }.getOrNull() ?: return@withContext false
        val line = buildJsonObject {
            put("at", now); put("page", page); put("reason", reason); put("snapshot", parsed)
        }.toString()
        runCatching {
            val f = mapFile
            f.appendText(line + "\n")
            if (f.length() > MAX_MAP_BYTES) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
            _snapshotCount.value = f.useLines { it.count() }
        }.isSuccess
    }

    fun refreshCount() { _snapshotCount.value = runCatching { if (mapFile.exists()) mapFile.useLines { it.count() } else 0 }.getOrDefault(0) }

    suspend fun clearSnapshots() = withContext(Dispatchers.IO) { runCatching { mapFile.delete() }; lastByPage.clear(); _snapshotCount.value = 0 }

    /** Intent de compartilhamento do arquivo de snapshots (JSON). null = nada gravado. */
    suspend fun exportIntent(): Intent? = withContext(Dispatchers.IO) {
        val src = mapFile.takeIf { it.exists() && it.length() > 0 } ?: return@withContext null
        val out = File(mapDir, "comprasnet-mapa-telas.json")
        val body = src.readLines().filter { it.isNotBlank() }.joinToString(",\n", "[\n", "\n]\n")
        out.writeText(body)
        val uri = FileProvider.getUriForFile(app, app.packageName + ".portalmap.fileprovider", out)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "LicitaPRO — mapa das telas do Comprasnet")
            putExtra(Intent.EXTRA_TEXT, "Snapshots estruturais (sem valores digitados, sem tokens e com CPF/CNPJ/e-mails mascarados).")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        const val KEY_MAP_MODE = "map_mode"
        const val MAX_MAP_BYTES = 3L * 1024 * 1024
    }
}
