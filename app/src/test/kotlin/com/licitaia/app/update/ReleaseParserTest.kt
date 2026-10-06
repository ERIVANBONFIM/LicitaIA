package com.licitaia.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private val releaseJson = """
        {
          "url": "https://api.github.com/repos/ERIVANBONFIM/LicitaIA/releases/1",
          "html_url": "https://github.com/ERIVANBONFIM/LicitaIA/releases/tag/v0.3.0+4",
          "tag_name": "v0.3.0+4",
          "name": "LicitaIA 0.3.0",
          "draft": false,
          "prerelease": false,
          "body": "## Melhorias\r\n- Busca **PNCP** real\r\n- Corrige [backup](https://example.com/doc)\r\n\r\n<!-- interno -->\r\n### Notas\r\n> Reinstale apenas se `necessário`.",
          "assets": [
            {"name": "mapping.txt", "browser_download_url": "https://github.com/x/mapping.txt", "size": 10, "content_type": "text/plain"},
            {"name": "app-debug.apk", "browser_download_url": "https://github.com/x/app-debug.apk", "size": 100, "content_type": "application/vnd.android.package-archive"},
            {"name": "app-release.apk", "browser_download_url": "https://github.com/x/app-release.apk", "size": 12345678, "content_type": "application/vnd.android.package-archive"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses tag with versionCode`() {
        val v = ReleaseVersion.parseTag("v0.2.0+3")
        assertNotNull(v)
        assertEquals("0.2.0", v!!.versionName)
        assertEquals(3, v.versionCode)
        assertEquals(listOf(0, 2, 0), v.semver)
    }

    @Test
    fun `parses tag without versionCode and with prerelease suffix`() {
        val plain = ReleaseVersion.parseTag("v1.4.2")
        assertEquals("1.4.2", plain?.versionName)
        assertNull(plain?.versionCode)

        val pre = ReleaseVersion.parseTag("0.9.0-beta.1+12")
        assertEquals("0.9.0-beta.1", pre?.versionName)
        assertEquals(12, pre?.versionCode)
        assertEquals(listOf(0, 9, 0), pre?.semver)
    }

    @Test
    fun `rejects tags outside the convention`() {
        assertNull(ReleaseVersion.parseTag("latest"))
        assertNull(ReleaseVersion.parseTag("release-3"))
        assertNull(ReleaseVersion.parseTag(""))
    }

    @Test
    fun `versionCode decides when present`() {
        val v = ReleaseVersion.parseTag("v0.2.0+4")!!
        assertTrue(v.isNewerThan(installedVersionCode = 3, installedVersionName = "0.2.0-personal-debug"))
        assertFalse(v.isNewerThan(installedVersionCode = 4, installedVersionName = "0.1.0"))
        assertFalse(v.isNewerThan(installedVersionCode = 5, installedVersionName = "0.1.0"))
    }

    @Test
    fun `semver decides when tag has no versionCode`() {
        val v = ReleaseVersion.parseTag("v0.2.1")!!
        assertTrue(v.isNewerThan(installedVersionCode = 99, installedVersionName = "0.2.0-personal-debug"))
        assertFalse(v.isNewerThan(installedVersionCode = 1, installedVersionName = "0.2.1-personal"))
        assertFalse(v.isNewerThan(installedVersionCode = 1, installedVersionName = "1.0"))
        assertTrue(ReleaseVersion.parseTag("v1.0")!!.isNewerThan(1, "0.99.99"))
    }

    @Test
    fun `compareSemver handles different lengths`() {
        assertEquals(0, ReleaseVersion.compareSemver(listOf(1, 0), listOf(1, 0, 0)))
        assertTrue(ReleaseVersion.compareSemver(listOf(1, 0, 1), listOf(1, 0)) > 0)
        assertTrue(ReleaseVersion.compareSemver(listOf(0, 9), listOf(1)) < 0)
    }

    @Test
    fun `parses release json and picks the release apk`() {
        val release = ReleaseParser.parse(releaseJson)
        assertEquals("v0.3.0+4", release.tag)
        assertEquals("LicitaIA 0.3.0", release.name)
        assertEquals("https://github.com/ERIVANBONFIM/LicitaIA/releases/tag/v0.3.0+4", release.pageUrl)
        assertEquals(2, release.apkAssets.size) // mapping.txt ignorado
        val apk = release.pickApk()
        assertEquals("app-release.apk", apk?.name)
        assertEquals(12345678L, apk?.size)
    }

    @Test
    fun `picks the single apk when none is named release`() {
        val release = ParsedRelease(
            tag = "v0.3.0+4", name = "", body = "", pageUrl = null, draft = false, prerelease = false,
            apkAssets = listOf(ApkAsset("licitaia.apk", "https://x/licitaia.apk", 1)),
        )
        assertEquals("licitaia.apk", release.pickApk()?.name)
        assertNull(release.copy(apkAssets = emptyList()).pickApk())
    }

    @Test
    fun `prefers a non-debug apk when several exist without release in the name`() {
        val release = ParsedRelease(
            tag = "v0.3.0+4", name = "", body = "", pageUrl = null, draft = false, prerelease = false,
            apkAssets = listOf(ApkAsset("app-debug.apk", "u1", 1), ApkAsset("licitaia-personal.apk", "u2", 1)),
        )
        assertEquals("licitaia-personal.apk", release.pickApk()?.name)
    }

    @Test
    fun `toUpdateIfNewer builds AppUpdate with plain-text notes`() {
        val update = ReleaseParser.parse(releaseJson).toUpdateIfNewer(installedVersionCode = 3, installedVersionName = "0.2.0-personal")
        assertNotNull(update)
        update!!
        assertEquals("v0.3.0+4", update.tag)
        assertEquals("0.3.0", update.versionName)
        assertEquals(4, update.versionCode)
        assertEquals("LicitaIA 0.3.0", update.title)
        assertEquals("https://github.com/x/app-release.apk", update.apkUrl)
        assertEquals(12345678L, update.apkSize)
        assertEquals(
            "Melhorias\n• Busca PNCP real\n• Corrige backup\n\nNotas\nReinstale apenas se necessário.",
            update.notes,
        )
    }

    @Test
    fun `toUpdateIfNewer returns null when installed is current or newer`() {
        val release = ReleaseParser.parse(releaseJson)
        assertNull(release.toUpdateIfNewer(installedVersionCode = 4, installedVersionName = "0.3.0"))
        assertNull(release.toUpdateIfNewer(installedVersionCode = 7, installedVersionName = "0.5.0"))
    }

    @Test
    fun `markdown to plain text strips common syntax`() {
        assertEquals("", MarkdownText.toPlain("   "))
        assertEquals("Título\n• item um\n• item dois", MarkdownText.toPlain("# Título\n* item um\n+ item dois"))
        assertEquals("negrito e itálico e código", MarkdownText.toPlain("**negrito** e _itálico_ e `código`"))
        assertEquals("a\n\nb", MarkdownText.toPlain("a\n\n\n\n---\n\nb"))
        assertEquals("texto", MarkdownText.toPlain("<b>texto</b>"))
        assertEquals("v0.2.0+3", MarkdownText.toPlain("v0.2.0+3"))
    }
}
