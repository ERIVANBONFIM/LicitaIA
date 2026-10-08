package com.licitaia.core.ui.components

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.licitaia.core.ui.nav.AppNavigator
import com.licitaia.core.ui.nav.Routes
import com.licitaia.domain.model.OfficialFile
import com.licitaia.domain.model.PortalLinks

/** Ações dos links oficiais (abrir, copiar, baixar) e do "Abrir no portal". */
object OfficialLinksUi {

    /** Abre no navegador do aparelho; false = nenhum app abre links. */
    fun openExternal(context: Context, url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun share(context: Context, title: String, url: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, url)
        context.startActivity(Intent.createChooser(send, "Compartilhar link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun copy(context: Context, label: String, url: String): Boolean = runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, url))
        true
    }.getOrDefault(false)

    /**
     * Baixa pelo DownloadManager do Android para Downloads/LicitaIA/<nome seguro>, com notificação ao concluir.
     * Devolve a mensagem para o usuário.
     */
    fun download(context: Context, file: OfficialFile): String = runCatching {
        val request = DownloadManager.Request(Uri.parse(file.url))
            .setTitle(file.fileName)
            .setDescription(file.typeName ?: "Arquivo oficial da licitação")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "LicitaPRO/${file.fileName}")
            .setAllowedOverMetered(true)
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        "Baixando \"${file.fileName}\" em Downloads/LicitaIA — avisamos ao concluir."
    }.getOrElse { "Não foi possível iniciar o download (${it.message ?: "erro"})." }

    /** Executa o destino do "Abrir no portal": navegador interno do app ou externo. */
    fun open(navigator: AppNavigator, context: Context, target: PortalLinks.Target?) {
        when (target) {
            null -> navigator.showMessage("Não há link do portal para esta licitação.")
            is PortalLinks.Target.External -> if (!openExternal(context, target.url)) navigator.showMessage("Nenhum navegador disponível para abrir o link.")
            is PortalLinks.Target.Internal -> navigator.navigate(
                Routes.portalWebTarget(
                    target.portal, url = target.url,
                    uasg = target.compras?.uasg, numero = target.compras?.numberYear, modalidade = target.compras?.modality,
                ),
            )
        }
    }
}
