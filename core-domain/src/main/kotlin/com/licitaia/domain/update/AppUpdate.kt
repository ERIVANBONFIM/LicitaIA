package com.licitaia.domain.update

/**
 * Release publicada no GitHub que é mais nova do que a versão instalada.
 *
 * Convenção de tag: `v<versionName>+<versionCode>` (ex.: `v0.2.0+3`). Quando a tag não traz `+N`,
 * a comparação cai para o `versionName` semântico.
 */
data class AppUpdate(
    /** Tag da release (ex.: `v0.2.0+3`). */
    val tag: String,
    /** Nome semântico extraído da tag (ex.: `0.2.0`). */
    val versionName: String,
    /** `versionCode` extraído da tag, se houver `+N`. */
    val versionCode: Int?,
    /** Título da release (campo `name`); cai para a tag quando vazio. */
    val title: String,
    /** Notas da release já convertidas de Markdown para texto simples. */
    val notes: String,
    /** URL direta do asset `.apk` escolhido. */
    val apkUrl: String,
    /** Tamanho do asset em bytes, se informado pela API. */
    val apkSize: Long?,
    /** Página da release no GitHub (para abrir no navegador em caso de falha). */
    val pageUrl: String?,
)

/** Resultado de uma verificação de atualização. */
sealed interface UpdateCheckResult {
    data class Available(val update: AppUpdate) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    /** Repositório sem release publicada (HTTP 404) ou release sem asset `.apk`. */
    data class NoRelease(val reason: String) : UpdateCheckResult
    data object Offline : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * Verificação manual de atualização (item "Verificar atualizações" em Configurações).
 * A implementação vive no módulo `app`; quando há atualização, ela também abre o diálogo global.
 */
interface AppUpdateChecker {
    suspend fun checkNow(): UpdateCheckResult
}
