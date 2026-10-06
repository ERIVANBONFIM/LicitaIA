package com.licitaia.core.data.backup

/**
 * Nome de arquivo para anexos restaurados de backup: `<base>.<ext>`, preservando a extensão original para que
 * o `FileProvider` volte a informar o MIME correto. A extensão vem, nesta ordem, do nome original gravado no
 * backup, do MIME gravado, ou dos primeiros bytes do conteúdo (backups antigos sem metadados). Sem nenhuma
 * pista, o arquivo fica sem extensão (comportamento anterior). Função pura, sem dependências Android.
 */
object RestoredAttachmentNaming {
    private val mimeToExtension = mapOf(
        "application/pdf" to "pdf",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/heic" to "heic",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "text/xml" to "xml",
        "application/xml" to "xml",
        "application/json" to "json",
        "application/zip" to "zip",
        "application/x-7z-compressed" to "7z",
        "application/vnd.rar" to "rar",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/pkcs7-signature" to "p7s",
        "application/x-pkcs7-signature" to "p7s",
        "application/pkcs7-mime" to "p7m",
        "application/x-pkcs12" to "p12",
    )
    private val extensionToMime = mimeToExtension.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.first() } + mapOf("jpeg" to "image/jpeg")

    private val safeExtension = Regex("^[a-z0-9]{1,8}$")

    fun fileName(base: String, originalName: String?, mime: String?, head: ByteArray): String {
        val extension = extensionOf(originalName) ?: extensionOfMime(mime) ?: sniff(head)
        return if (extension == null) base else "$base.$extension"
    }

    /** Extensão (minúscula, sem ponto) de um nome ou caminho; `null` se ausente ou suspeita. */
    fun extensionOf(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val last = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = last.lastIndexOf('.')
        if (dot <= 0 || dot == last.length - 1) return null
        return last.substring(dot + 1).lowercase().takeIf { safeExtension.matches(it) }
    }

    fun extensionOfMime(mime: String?): String? = mime?.lowercase()?.substringBefore(';')?.trim()?.let(mimeToExtension::get)

    /** MIME conhecido para a extensão de um nome de arquivo (usado ao exportar caminhos locais). */
    fun mimeOf(name: String?): String? = extensionOf(name)?.let(extensionToMime::get)

    /** Reconhece formatos comuns pela assinatura inicial. ZIP não é inferido (docx/xlsx/zip são ambíguos). */
    fun sniff(head: ByteArray): String? = when {
        head.startsWith("%PDF") -> "pdf"
        head.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
        head.startsWith("GIF8") -> "gif"
        head.size >= 12 && head.startsWith("RIFF") && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }

    private fun ByteArray.startsWith(text: String) = startsWith(*text.toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xFF }.toIntArray())
    private fun ByteArray.startsWith(vararg bytes: Int) = size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }
}
