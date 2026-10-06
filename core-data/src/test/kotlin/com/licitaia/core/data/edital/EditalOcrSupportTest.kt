package com.licitaia.core.data.edital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditalOcrSupportTest {

    @Test
    fun needsOcrWhenScannedOrTooLittleText() {
        assertTrue(EditalOcrSupport.needsOcr(scanned = true, meaningfulChars = 10_000))
        assertTrue(EditalOcrSupport.needsOcr(scanned = false, meaningfulChars = 50))
        assertFalse(EditalOcrSupport.needsOcr(scanned = false, meaningfulChars = EditalOcrSupport.MIN_USEFUL_CHARS))
        assertFalse(EditalOcrSupport.needsOcr(scanned = false, meaningfulChars = 5_000))
    }

    @Test
    fun assembleAddsPageMarkersAndFlagsEmptyPages() {
        val out = EditalOcrSupport.assemble(listOf("  EDITAL Nº 1/2026\nObjeto: link dedicado.  ", "", "9. DA HABILITAÇÃO"))
        val expected = "--- Página 1 ---\nEDITAL Nº 1/2026\nObjeto: link dedicado.\n\n" +
            "--- Página 2 ---\n${EditalOcrSupport.EMPTY_PAGE_NOTE}\n\n" +
            "--- Página 3 ---\n9. DA HABILITAÇÃO"
        assertEquals(expected, out)
        assertEquals("--- Página 7 ---", EditalOcrSupport.pageMarker(7))
    }

    @Test
    fun assembleOfNoPagesIsEmpty() {
        assertEquals("", EditalOcrSupport.assemble(emptyList()))
        assertFalse(EditalOcrSupport.isUsable(EditalOcrSupport.assemble(emptyList())))
    }

    @Test
    fun pagesWithTextIgnoresNoiseOnlyPages() {
        val pages = listOf("a b c", "x".repeat(EditalOcrSupport.MIN_CHARS_PER_PAGE), "   ", "palavra ".repeat(30))
        assertEquals(2, EditalOcrSupport.pagesWithText(pages))
    }

    @Test
    fun usableRequiresMinimumMeaningfulChars() {
        assertFalse(EditalOcrSupport.isUsable("--- Página 1 ---\n${EditalOcrSupport.EMPTY_PAGE_NOTE}"))
        assertTrue(EditalOcrSupport.isUsable("--- Página 1 ---\n" + "texto ".repeat(60)))
        assertEquals(5, EditalOcrSupport.meaningfulChars(" a b\n c\td e "))
    }

    @Test
    fun renderScaleKeepsTargetForA4AndShrinksHugePages() {
        // A4 em pontos (595×842) a 2x = ~2 Mpx: dentro do teto.
        assertEquals(EditalOcrSupport.TARGET_SCALE, EditalOcrSupport.renderScale(595, 842), 0.0001f)
        // Página A0-like (2384×3370): 2x daria 32 Mpx, precisa reduzir para caber em 4 Mpx.
        val scale = EditalOcrSupport.renderScale(2384, 3370)
        assertTrue(scale < EditalOcrSupport.TARGET_SCALE)
        val (w, h) = EditalOcrSupport.bitmapSize(2384, 3370, scale)
        assertTrue("${w}x$h", w.toLong() * h <= EditalOcrSupport.MAX_PIXELS_PER_PAGE)
        // Nunca abaixo de 0,5 nem dimensões zero.
        assertTrue(EditalOcrSupport.renderScale(100_000, 100_000) >= 0.5f)
        assertEquals(1 to 1, EditalOcrSupport.bitmapSize(0, 0, 2f))
    }
}
