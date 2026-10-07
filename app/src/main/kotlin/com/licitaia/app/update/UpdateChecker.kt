package com.licitaia.app.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.licitaia.app.BuildConfig
import com.licitaia.core.data.settings.UpdatePrefs
import com.licitaia.domain.update.AppUpdate
import com.licitaia.domain.update.AppUpdateChecker
import com.licitaia.domain.update.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consulta a release mais recente do repositório ([BuildConfig.UPDATE_REPO]) na API pública do GitHub,
 * sem token, e decide se é mais nova que a versão instalada.
 *
 * - Automática (ao abrir/voltar ao app logado e pelo worker periódico): no máximo a cada [AUTO_INTERVAL_MS];
 *   silenciosa em erro/offline;
 *   respeita a tag adiada em "Depois" por [SKIP_INTERVAL_MS].
 * - Manual (Configurações): sempre consulta e devolve o resultado para a tela.
 *
 * Nunca baixa nada: o download só começa em "Atualizar agora" (ver [ApkDownloader]).
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val prefs: UpdatePrefs,
) : AppUpdateChecker {

    /** Atualização aguardando decisão do usuário; observada pelo diálogo global em AppRoot. */
    private val _pending = MutableStateFlow<AppUpdate?>(null)
    val pending: StateFlow<AppUpdate?> = _pending.asStateFlow()

    /** Atualização adiada em "Depois": a faixa no topo do app continua oferecendo enquanto não for instalada. */
    private val _postponed = MutableStateFlow<AppUpdate?>(null)
    val postponed: StateFlow<AppUpdate?> = _postponed.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var lastAttemptAt = 0L

    val repo: String get() = BuildConfig.UPDATE_REPO
    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE
    val installedVersionName: String get() = BuildConfig.VERSION_NAME

    /** Checagem silenciosa ao abrir o app. Retorna o resultado apenas para diagnóstico/testes. */
    suspend fun checkAutomatically(now: Long = System.currentTimeMillis()): UpdateCheckResult? = mutex.withLock {
        if (_pending.value != null) return@withLock UpdateCheckResult.Available(_pending.value!!)
        val last = runCatching { prefs.lastCheckAt() }.getOrNull() ?: 0L
        if (now - last < AUTO_INTERVAL_MS) return@withLock null
        // Evita martelar a API quando o app é reaberto várias vezes offline.
        if (now - lastAttemptAt < RETRY_BACKOFF_MS) return@withLock null
        lastAttemptAt = now
        val result = fetchLatest()
        when (result) {
            is UpdateCheckResult.Available -> {
                prefs.markChecked(now)
                val skipped = runCatching { prefs.skipped() }.getOrNull()
                if (skipped != null && skipped.tag == result.update.tag && skipped.until > now) {
                    _postponed.value = result.update
                    return@withLock null
                }
                _postponed.value = null
                _pending.value = result.update
            }
            UpdateCheckResult.UpToDate, is UpdateCheckResult.NoRelease -> prefs.markChecked(now)
            UpdateCheckResult.Offline, is UpdateCheckResult.Failed -> Unit // tenta de novo na próxima abertura
        }
        result
    }

    /** Checagem manual: ignora intervalo e tag adiada; se houver atualização, abre o diálogo global. */
    override suspend fun checkNow(): UpdateCheckResult = mutex.withLock {
        val result = fetchLatest()
        if (result is UpdateCheckResult.Available) {
            runCatching { prefs.clearSkip() }
            _postponed.value = null
            _pending.value = result.update
        }
        if (result !is UpdateCheckResult.Offline && result !is UpdateCheckResult.Failed) {
            runCatching { prefs.markChecked() }
        }
        result
    }

    /** "Depois": esconde o diálogo e não oferece a mesma tag por [SKIP_INTERVAL_MS]. */
    suspend fun postpone(update: AppUpdate, now: Long = System.currentTimeMillis()) {
        runCatching { prefs.skip(update.tag, now + SKIP_INTERVAL_MS) }
        _postponed.value = update
        if (_pending.value?.tag == update.tag) _pending.value = null
    }

    /** Faixa "Atualizar" tocada: reabre o diálogo da atualização adiada. */
    suspend fun resumePostponed() {
        val update = _postponed.value ?: return
        runCatching { prefs.clearSkip() }
        _postponed.value = null
        _pending.value = update
    }

    /** Fecha o diálogo sem adiar (ex.: após abrir o instalador). */
    fun clearPending() {
        _pending.value = null
    }

    // ------------------------------------------------------------------ rede

    private suspend fun fetchLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext UpdateCheckResult.Offline
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LicitaIA/${installedVersionName} (Android)")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> UpdateCheckResult.NoRelease("Nenhuma atualização publicada ainda.")
                    response.code == 403 || response.code == 429 ->
                        UpdateCheckResult.Failed("Limite de consultas da API do GitHub atingido. Tente novamente mais tarde.")
                    !response.isSuccessful -> UpdateCheckResult.Failed("GitHub respondeu HTTP ${response.code}.")
                    else -> {
                        val body = response.body?.string().orEmpty()
                        evaluate(body)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            UpdateCheckResult.Offline
        } catch (e: Exception) {
            UpdateCheckResult.Failed("Falha ao consultar releases: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Decide a partir do JSON da release (separado para teste). */
    internal fun evaluate(body: String): UpdateCheckResult {
        val release = try {
            ReleaseParser.parse(body)
        } catch (e: Exception) {
            return UpdateCheckResult.Failed("Resposta inesperada da API do GitHub.")
        }
        if (release.tag.isBlank()) return UpdateCheckResult.NoRelease("A atualização publicada está sem número de versão.")
        if (release.version == null) {
            return UpdateCheckResult.Failed("Tag '${release.tag}' fora do padrão vX.Y.Z+N; a versão não pôde ser comparada.")
        }
        if (!release.version.isNewerThan(installedVersionCode, installedVersionName)) return UpdateCheckResult.UpToDate
        if (release.pickApk() == null) return UpdateCheckResult.NoRelease("A release ${release.tag} não tem um APK anexado.")
        val update = release.toUpdateIfNewer(installedVersionCode, installedVersionName)
            ?: return UpdateCheckResult.UpToDate
        return UpdateCheckResult.Available(update)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val AUTO_INTERVAL_MS = 30 * 60 * 1000L
        const val SKIP_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val RETRY_BACKOFF_MS = 15 * 60 * 1000L
    }
}
