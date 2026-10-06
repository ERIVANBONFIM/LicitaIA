package com.licitaia.app.update

import com.licitaia.domain.update.AppUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Versão extraída de uma tag `v<versionName>+<versionCode>`; `versionCode` é opcional. */
data class ReleaseVersion(val versionName: String, val versionCode: Int?) {
    /** Partes numéricas do nome (ex.: `0.2.0-personal` → `[0, 2, 0]`). */
    val semver: List<Int> = parseSemver(versionName)

    companion object {
        private val TAG = Regex("""^[vV]?([0-9]+(?:\.[0-9]+)*(?:-[0-9A-Za-z.-]+)?)(?:\+([0-9]+))?$""")

        /** Aceita `v0.2.0+3`, `0.2.0+3`, `v0.2.0`, `v0.2.0-beta.1+7`. Retorna null para tags fora do padrão. */
        fun parseTag(tag: String): ReleaseVersion? {
            val m = TAG.matchEntire(tag.trim()) ?: return null
            val code = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return ReleaseVersion(m.groupValues[1], code)
        }

        /** `0.2.0-personal-debug` → `[0, 2, 0]`; ignora qualquer sufixo após `-` ou `+`. */
        fun parseSemver(name: String): List<Int> =
            name.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-').substringBefore('+')
                .split('.')
                .map { it.toIntOrNull() ?: 0 }

        /** Comparação semântica posicional; partes ausentes valem 0. */
        fun compareSemver(a: List<Int>, b: List<Int>): Int {
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val diff = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }

    /**
     * Mais nova que a versão instalada? Com `versionCode` na tag, só ele decide (fonte da verdade do Android);
     * sem ele, cai para a comparação semântica do nome.
     */
    fun isNewerThan(installedVersionCode: Int, installedVersionName: String): Boolean {
        val code = versionCode
        if (code != null) return code > installedVersionCode
        return compareSemver(semver, parseSemver(installedVersionName)) > 0
    }
}

/** Asset `.apk` de uma release. */
data class ApkAsset(val name: String, val url: String, val size: Long?)

/** Release lida da API do GitHub, ainda sem decisão sobre ser ou não mais nova. */
data class ParsedRelease(
    val tag: String,
    val name: String,
    val body: String,
    val pageUrl: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val apkAssets: List<ApkAsset>,
) {
    val version: ReleaseVersion? = ReleaseVersion.parseTag(tag)

    /** Asset preferido: nome contendo `release`; senão o único `.apk`; senão o primeiro (nunca um `debug` se houver outro). */
    fun pickApk(): ApkAsset? {
        if (apkAssets.isEmpty()) return null
        apkAssets.firstOrNull { it.name.contains("release", ignoreCase = true) }?.let { return it }
        if (apkAssets.size == 1) return apkAssets.single()
        return apkAssets.firstOrNull { !it.name.contains("debug", ignoreCase = true) } ?: apkAssets.first()
    }

    /** Converte em [AppUpdate] se a release for mais nova que a versão instalada e tiver APK. */
    fun toUpdateIfNewer(installedVersionCode: Int, installedVersionName: String): AppUpdate? {
        val v = version ?: return null
        if (!v.isNewerThan(installedVersionCode, installedVersionName)) return null
        val apk = pickApk() ?: return null
        return AppUpdate(
            tag = tag,
            versionName = v.versionName,
            versionCode = v.versionCode,
            title = name.ifBlank { tag },
            notes = MarkdownText.toPlain(body),
            apkUrl = apk.url,
            apkSize = apk.size,
            pageUrl = pageUrl,
        )
    }
}

/** Leitura tolerante do JSON de `GET /repos/{owner}/{repo}/releases/latest`. */
object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): ParsedRelease {
        val root = json.parseToJsonElement(body).jsonObject
        val assets = (root["assets"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val url = obj.string("browser_download_url") ?: return@mapNotNull null
            val isApk = name.endsWith(".apk", ignoreCase = true) ||
                obj.string("content_type") == "application/vnd.android.package-archive"
            if (!isApk) return@mapNotNull null
            ApkAsset(name = name, url = url, size = obj["size"]?.jsonPrimitive?.longOrNull)
        }.orEmpty()
        return ParsedRelease(
            tag = root.string("tag_name").orEmpty(),
            name = root.string("name").orEmpty(),
            body = root.string("body").orEmpty(),
            pageUrl = root.string("html_url"),
            draft = root["draft"]?.jsonPrimitive?.booleanOrNull ?: false,
            prerelease = root["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
            apkAssets = assets,
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
}

/** Conversão simples de Markdown (notas de release) para texto legível no diálogo. */
object MarkdownText {
    private val htmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val fencedCode = Regex("""```[a-zA-Z0-9]*\n?""")
    // Só espaços/tabs nos padrões ancorados em linha: `\s` engoliria as linhas em branco.
    private val heading = Regex("""^[ \t]{0,3}#{1,6}[ \t]*""", RegexOption.MULTILINE)
    private val bullet = Regex("""^[ \t]*[-*+][ \t]+""", RegexOption.MULTILINE)
    private val checkbox = Regex("""^[ \t]*\[( |x|X)][ \t]*""", RegexOption.MULTILINE)
    private val link = Regex("""!?\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    private val boldItalic = Regex("""(\*\*\*|___|\*\*|__|~~)(.+?)\1""")
    private val emphasis = Regex("""(?<![\w*])([*_])(?!\s)(.+?)(?<!\s)\1(?![\w*])""")
    private val inlineCode = Regex("""`([^`]*)`""")
    private val quote = Regex("""^[ \t]{0,3}>[ \t]?""", RegexOption.MULTILINE)
    private val rule = Regex("""^[ \t]{0,3}([-*_])[ \t]*(\1[ \t]*){2,}$""", RegexOption.MULTILINE)
    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")
    private val blankLines = Regex("""\n{3,}""")

    fun toPlain(markdown: String): String {
        if (markdown.isBlank()) return ""
        var s = markdown.replace("\r\n", "\n").replace('\r', '\n')
        s = htmlComment.replace(s, "")
        s = fencedCode.replace(s, "")
        s = rule.replace(s, "")
        s = heading.replace(s, "")
        s = quote.replace(s, "")
        s = checkbox.replace(s, "")
        s = bullet.replace(s, "• ")
        s = link.replace(s) { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
        s = boldItalic.replace(s) { it.groupValues[2] }
        s = emphasis.replace(s) { it.groupValues[2] }
        s = inlineCode.replace(s) { it.groupValues[1] }
        s = htmlTag.replace(s, "")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        s = blankLines.replace(s, "\n\n")
        return s.lines().joinToString("\n") { it.trimEnd() }.trim()
    }
}
