package com.licitaia.app.update

import android.content.Context
import com.licitaia.domain.update.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Progresso do download do APK. */
sealed interface DownloadState {
    data class Progress(val bytes: Long, val total: Long?) : DownloadState {
        /** 0..1 ou null quando o tamanho total é desconhecido. */
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    data class Done(val file: File) : DownloadState
}

/**
 * Baixa o APK da release em streaming para `cacheDir/updates/licitaia-<tag>.apk`.
 * Cancelar a coleta do Flow interrompe o download e apaga o arquivo parcial.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    val directory: File get() = File(context.cacheDir, DIRECTORY)

    fun targetFile(update: AppUpdate): File = File(directory, "licitaia-${sanitize(update.tag)}.apk")

    fun download(update: AppUpdate): Flow<DownloadState> = flow {
        val dir = directory.apply { mkdirs() }
        // Limpa APKs de versões anteriores para não acumular no cache.
        dir.listFiles()?.filter { it.extension.equals("apk", ignoreCase = true) }?.forEach { it.delete() }
        val target = targetFile(update)
        val partial = File(dir, target.name + ".part")
        partial.delete()

        val request = Request.Builder()
            .url(update.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "LicitaIA (Android)")
            .get()
            .build()
        var completed = false
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download recusado pelo servidor (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("Resposta sem conteúdo.")
                val total = body.contentLength().takeIf { it > 0 } ?: update.apkSize
                emit(DownloadState.Progress(0, total))
                var bytes = 0L
                var lastEmitted = 0L
                body.byteStream().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytes += read
                            if (bytes - lastEmitted >= 256 * 1024) {
                                lastEmitted = bytes
                                emit(DownloadState.Progress(bytes, total))
                            }
                        }
                        output.flush()
                    }
                }
                if (total != null && bytes != total) {
                    throw IOException("Download interrompido: recebidos $bytes de $total bytes.")
                }
                if (bytes < MIN_APK_BYTES) throw IOException("Arquivo baixado é pequeno demais para ser um APK válido.")
                target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                completed = true
                emit(DownloadState.Progress(bytes, total ?: bytes))
                emit(DownloadState.Done(target))
            }
        } finally {
            if (!completed) {
                partial.delete()
                target.delete()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun sanitize(tag: String): String = tag.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "update" }

    companion object {
        const val DIRECTORY = "updates"
        private const val MIN_APK_BYTES = 64 * 1024L
    }
}
