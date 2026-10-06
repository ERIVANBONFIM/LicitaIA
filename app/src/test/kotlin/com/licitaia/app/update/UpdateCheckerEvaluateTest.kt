package com.licitaia.app.update

import android.content.Context
import com.licitaia.app.BuildConfig
import com.licitaia.core.data.settings.UpdatePrefs
import com.licitaia.domain.update.UpdateCheckResult
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisão a partir do JSON da release, usando a versão real do BuildConfig do módulo. */
class UpdateCheckerEvaluateTest {

    private val checker = UpdateChecker(mockk<Context>(relaxed = true), OkHttpClient(), mockk<UpdatePrefs>(relaxed = true))

    private fun json(tag: String, assets: String = """[{"name":"app-release.apk","browser_download_url":"https://x/app-release.apk","size":1000}]""") = """
        {"tag_name": "$tag", "name": "LicitaIA $tag", "body": "- nota", "html_url": "https://github.com/x/releases/tag/$tag", "assets": $assets}
    """.trimIndent()

    @Test
    fun `newer versionCode is offered`() {
        val result = checker.evaluate(json("v9.9.9+${BuildConfig.VERSION_CODE + 1}"))
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("https://x/app-release.apk", (result as UpdateCheckResult.Available).update.apkUrl)
    }

    @Test
    fun `same versionCode is up to date even with higher name`() {
        assertEquals(UpdateCheckResult.UpToDate, checker.evaluate(json("v9.9.9+${BuildConfig.VERSION_CODE}")))
    }

    @Test
    fun `older versionCode is up to date`() {
        assertEquals(UpdateCheckResult.UpToDate, checker.evaluate(json("v9.9.9+${BuildConfig.VERSION_CODE - 1}")))
    }

    @Test
    fun `release without apk asset is reported`() {
        val result = checker.evaluate(json("v9.9.9+${BuildConfig.VERSION_CODE + 1}", assets = "[]"))
        assertTrue(result is UpdateCheckResult.NoRelease)
    }

    @Test
    fun `unparseable tag fails explicitly instead of offering`() {
        assertTrue(checker.evaluate(json("latest")) is UpdateCheckResult.Failed)
    }

    @Test
    fun `malformed json fails`() {
        assertTrue(checker.evaluate("not json") is UpdateCheckResult.Failed)
    }
}
