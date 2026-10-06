package com.licitaia.core.data.edital

/**
 * Regras puras do OCR de editais (sem Android): decisão "precisa OCR", montagem do texto por
 * página com marcadores e cálculo da escala de renderização. Testável em JVM.
 */
object EditalOcrSupport {

    /** Máximo de páginas reconhecidas: acima disso o restante do PDF é ignorado (aviso ao usuário). */
    const val MAX_OCR_PAGES = 150

    /** Escala alvo sobre o tamanho nominal da página (72 dpi → ~144 dpi), boa para OCR de texto impresso. */
    const val TARGET_SCALE = 2f

    /** Teto de pixels por página renderizada (~4 Mpx = 16 MB ARGB_8888) para não estourar a memória. */
    const val MAX_PIXELS_PER_PAGE = 4_000_000L

    /** Abaixo disto, a extração do PDF é considerada insuficiente e o OCR é acionado. */
    const val MIN_USEFUL_CHARS = 200

    /** Mínimo de caracteres não brancos por página para contar como "página com texto". */
    const val MIN_CHARS_PER_PAGE = 40

    fun pageMarker(page: Int): String = "--- Página $page ---"

    /**
     * O texto extraído pelo pdfbox é insuficiente e vale tentar OCR?
     * @param scanned decisão do extrator (quase nenhuma página com texto).
     * @param meaningfulChars caracteres não brancos do texto extraído.
     */
    fun needsOcr(scanned: Boolean, meaningfulChars: Int): Boolean = scanned || meaningfulChars < MIN_USEFUL_CHARS

    /** O resultado do OCR tem texto suficiente para alimentar a análise. */
    fun isUsable(text: String): Boolean = meaningfulChars(text) >= MIN_USEFUL_CHARS

    fun meaningfulChars(text: String): Int = text.count { !it.isWhitespace() }

    /**
     * Concatena o texto reconhecido de cada página (índice 0 = página 1) com o marcador
     * `--- Página N ---`. Páginas sem texto recebem apenas o marcador e uma nota, para o leitor
     * saber que a página existe mas nada foi reconhecido.
     */
    fun assemble(pages: List<String>): String = buildString {
        pages.forEachIndexed { index, raw ->
            if (index > 0) append("\n\n")
            append(pageMarker(index + 1)).append('\n')
            val text = raw.trim()
            if (text.isEmpty()) append(EMPTY_PAGE_NOTE) else append(text)
        }
    }

    /** Quantas páginas têm texto reconhecido de verdade (>= [MIN_CHARS_PER_PAGE] caracteres não brancos). */
    fun pagesWithText(pages: List<String>): Int = pages.count { meaningfulChars(it) >= MIN_CHARS_PER_PAGE }

    /**
     * Escala de renderização para uma página de [widthPt] × [heightPt] pontos: [TARGET_SCALE],
     * reduzida quando o bitmap ultrapassaria [maxPixels]. Nunca abaixo de 0,5 (páginas gigantes).
     */
    fun renderScale(widthPt: Int, heightPt: Int, targetScale: Float = TARGET_SCALE, maxPixels: Long = MAX_PIXELS_PER_PAGE): Float {
        val w = widthPt.coerceAtLeast(1).toDouble()
        val h = heightPt.coerceAtLeast(1).toDouble()
        val pixelsAtTarget = w * targetScale * h * targetScale
        if (pixelsAtTarget <= maxPixels) return targetScale
        val fitted = Math.sqrt(maxPixels / (w * h)).toFloat()
        return fitted.coerceIn(0.5f, targetScale)
    }

    /** Dimensões em pixels do bitmap para a página, já com a escala aplicada (mínimo 1×1). */
    fun bitmapSize(widthPt: Int, heightPt: Int, scale: Float): Pair<Int, Int> =
        (widthPt * scale).toInt().coerceAtLeast(1) to (heightPt * scale).toInt().coerceAtLeast(1)

    const val EMPTY_PAGE_NOTE = "[página sem texto reconhecido]"
}
