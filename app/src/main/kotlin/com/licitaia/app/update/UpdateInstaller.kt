package com.licitaia.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado da verificação prévia do APK baixado contra o app instalado. */
sealed interface ApkCheck {
    data object Ok : ApkCheck
    data class Mismatch(val message: String) : ApkCheck
}

/**
 * Entrega o APK ao instalador do sistema e trata a permissão "instalar apps desconhecidos".
 * Antes de abrir o instalador, compara pacote e assinatura com o app instalado para explicar
 * recusas previsíveis (ex.: APK release sobre um debug instalado).
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    val authority: String get() = "${appContext.packageName}.update.fileprovider"

    /** Android 8+: o usuário precisa autorizar esta origem em Configurações. */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /** Abre a tela do sistema para autorizar o LicitaPRO a instalar apps. */
    fun openUnknownSourcesSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
        if (context === appContext) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    /** Abre o instalador com o APK baixado via FileProvider. */
    fun openInstaller(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(appContext, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context === appContext) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    /** Abre a página da release no navegador (fallback quando o download/instalação falha). */
    fun openReleasePage(context: Context, url: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context === appContext) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    /**
     * Compara `packageName` e certificado de assinatura do APK com os do app instalado.
     * O Android recusaria a instalação em ambos os casos; aqui a mensagem explica o motivo.
     */
    fun verify(apk: File): ApkCheck {
        val pm = appContext.packageManager
        val archive = archiveInfo(pm, apk) ?: return ApkCheck.Mismatch("O arquivo baixado não é um APK válido ou está corrompido.")
        val installedPackage = appContext.packageName
        if (archive.packageName != installedPackage) {
            return ApkCheck.Mismatch(
                "O APK da release é do pacote ${archive.packageName}, mas este app instalado é $installedPackage. " +
                    "Compilações debug (sufixo .debug) e release são apps distintos: instale o APK release manualmente " +
                    "e migre seus dados por backup.",
            )
        }
        val installed = installedInfo(pm, installedPackage) ?: return ApkCheck.Ok
        val archiveSigs = signatures(archive)
        val installedSigs = signatures(installed)
        if (archiveSigs.isEmpty() || installedSigs.isEmpty()) return ApkCheck.Ok // sem dados: deixa o Android decidir
        if (archiveSigs.intersect(installedSigs).isEmpty()) {
            return ApkCheck.Mismatch(
                "A assinatura do APK publicado é diferente da assinatura deste app instalado; o Android recusará a " +
                    "atualização. Isso acontece quando o app instalado é a versão debug ou quando a release foi assinada " +
                    "com outro keystore. O APK release precisa ser assinado sempre com o mesmo keystore fixo.",
            )
        }
        return ApkCheck.Ok
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    /** SHA-256 dos certificados de assinatura (histórico incluído em API 28+). */
    private fun signatures(info: PackageInfo): Set<String> {
        val raw: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        } ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return raw.map { sig -> digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
