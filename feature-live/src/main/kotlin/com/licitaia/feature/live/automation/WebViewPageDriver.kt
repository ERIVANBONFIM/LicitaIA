package com.licitaia.feature.live.automation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/**
 * [PageDriver] sobre o WebView RETIDO da empresa (sessão logada), ANEXADO a uma janela
 * ([com.licitaia.feature.live.web.PortalWebViewHolder] estaciona-o atrás do conteúdo do app quando a tela do portal não
 * o exibe). Cada chamada roda na thread principal e termina em no máximo [timeoutMs] (sem resposta = resultado vazio).
 *
 * Digitação: os campos do portal (máscaras de UASG/número/moeda + ngModel) NÃO aceitam valor setado por JS; só
 * digitação real. [typeKeys] manda eventos de tecla do Android ao WebView ([WebView.dispatchKeyEvent]), que o Chromium
 * entrega à página como keydown/keypress/input confiáveis — o chamador sempre confere o valor lido depois.
 */
class WebViewPageDriver(
    private val webView: () -> WebView?,
    private val timeoutMs: Long = 8_000,
) : PageDriver {
    private val main = Handler(Looper.getMainLooper())

    private suspend fun evalRaw(script: String): String? = withContext(Dispatchers.Main) {
        val view = webView() ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            var done = false
            val timeout = Runnable { if (!done) { done = true; if (cont.isActive) cont.resume(null) } }
            main.postDelayed(timeout, timeoutMs)
            runCatching {
                view.evaluateJavascript(script) { raw ->
                    if (done) return@evaluateJavascript
                    done = true
                    main.removeCallbacks(timeout)
                    if (cont.isActive) cont.resume(raw)
                }
            }.onFailure {
                if (!done) { done = true; main.removeCallbacks(timeout); if (cont.isActive) cont.resume(null) }
            }
            cont.invokeOnCancellation { main.removeCallbacks(timeout) }
        }
    }

    override suspend fun currentUrl(): String? = withContext(Dispatchers.Main) { runCatching { webView()?.url }.getOrNull() }
    override suspend fun find(query: ElementQuery): FindResult = AutomationJson.parseFind(evalRaw(AutomationScripts.find(query)))
    override suspend fun fill(query: ElementQuery, value: String): ActionResult = AutomationJson.parseAction(evalRaw(AutomationScripts.fill(query, value)))
    override suspend fun click(query: ElementQuery): ActionResult = AutomationJson.parseAction(evalRaw(AutomationScripts.click(query)))
    override suspend fun read(query: ElementQuery): String? = AutomationJson.parseRead(evalRaw(AutomationScripts.read(query)))
    override suspend fun probe(): PageProbe = AutomationJson.parseProbe(evalRaw(AutomationScripts.probe()))
    override suspend fun rows(scopeText: String?): List<String> = AutomationJson.parseRows(evalRaw(AutomationScripts.rows(scopeText)))
    override suspend fun tablesHtml(): String = AutomationJson.parseTablesHtml(evalRaw(AutomationScripts.tablesHtml()))
    override suspend fun snapshot(): String = AutomationJson.snapshotText(evalRaw(AutomationScripts.snapshot())).orEmpty()
    override suspend fun eval(script: String): String? = evalRaw(script)

    /** WebView numa janela visível (pré-requisito para eventos nativos). */
    private fun usable(view: WebView?): WebView? =
        view?.takeIf { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }

    private fun send(view: WebView, action: Int, keyCode: Int, meta: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return view.dispatchKeyEvent(
            KeyEvent(now, now, action, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE),
        )
    }

    private fun key(view: WebView, keyCode: Int) {
        send(view, KeyEvent.ACTION_DOWN, keyCode)
        send(view, KeyEvent.ACTION_UP, keyCode)
    }

    override suspend fun typeKeys(text: String, clearFirst: Boolean): Boolean {
        val chars = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val ready = withContext(Dispatchers.Main) {
            val view = usable(webView()) ?: return@withContext false
            if (!view.hasFocus()) view.requestFocus()
            if (clearFirst) {
                // O script de foco já selecionou o conteúdo: Ctrl+A garante, Backspace/Delete apagam.
                send(view, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
                send(view, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
                key(view, KeyEvent.KEYCODE_DEL)
                key(view, KeyEvent.KEYCODE_FORWARD_DEL)
            }
            true
        }
        if (!ready) return false
        for (c in text) {
            val ok = withContext(Dispatchers.Main) {
                val view = usable(webView()) ?: return@withContext false
                val events = chars.getEvents(charArrayOf(c)) ?: return@withContext false
                events.forEach { ev -> send(view, ev.action, ev.keyCode, ev.metaState) }
                true
            }
            if (!ok) return false
            delay(KEY_GAP_MS)
        }
        return true
    }

    override suspend fun pressEnter(): Boolean = withContext(Dispatchers.Main) {
        val view = usable(webView()) ?: return@withContext false
        if (!view.hasFocus()) view.requestFocus()
        key(view, KeyEvent.KEYCODE_ENTER)
        true
    }

    override suspend fun tapAt(x: Float, y: Float): Boolean {
        val down = SystemClock.uptimeMillis()
        val started = withContext(Dispatchers.Main) {
            val view = usable(webView()) ?: return@withContext false
            if (x < 0 || y < 0 || x > view.width || y > view.height) return@withContext false
            val ev = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
            val r = runCatching { view.dispatchTouchEvent(ev) }.isSuccess
            ev.recycle()
            r
        }
        if (!started) return false
        delay(80)
        return withContext(Dispatchers.Main) {
            val view = usable(webView()) ?: return@withContext false
            val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            val r = runCatching { view.dispatchTouchEvent(ev) }.isSuccess
            ev.recycle()
            r
        }
    }

    override suspend fun insertText(text: String): Boolean {
        val lit = AutomationScripts.jsLiteral(JsonPrimitive(text))
        val raw = evalRaw(
            "(function(){try{var e=document.activeElement;if(!e)return 'no';document.execCommand('selectAll',false,null);" +
                "document.execCommand('delete',false,null);return document.execCommand('insertText',false,$lit)?'ok':'no';}catch(x){return 'no';}})()",
        )
        return raw?.trim('"') == "ok"
    }

    private companion object {
        /** Pausa entre teclas (máscaras do portal reagem a cada tecla). */
        const val KEY_GAP_MS = 60L
    }
}
