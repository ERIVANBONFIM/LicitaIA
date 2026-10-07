package com.licitaia.domain.model

import kotlinx.coroutines.flow.Flow

/** Arquivo público oficial de uma contratação (PNCP `/arquivos`). */
data class OfficialFile(
    val title: String,
    /** Tipo informado pelo PNCP (Edital, Termo de Referência, Anexo, ETP, Aviso, Minuta...); null quando ausente. */
    val typeName: String?,
    val url: String,
    /** Publicação no PNCP (epoch millis); null = não informada. */
    val publishedAt: Long? = null,
    /** Tamanho em bytes quando a fonte informa; null = desconhecido. */
    val sizeBytes: Long? = null,
    val sequence: Int? = null,
) {
    /** Nome de arquivo seguro para salvar em Downloads/LicitaIA (sem caracteres proibidos, com .pdf quando não houver extensão). */
    val fileName: String get() = OfficialFileNames.sanitize(title, typeName, sequence)
}

/** Links oficiais de uma licitação: página da compra no portal de origem, página no PNCP e todos os arquivos. */
data class OfficialLinks(
    val originUrl: String?,
    val pncpUrl: String?,
    val files: List<OfficialFile>,
    val fetchedAt: Long,
)

object OfficialFileNames {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val KNOWN_EXT = Regex("""\.(pdf|zip|rar|7z|doc|docx|xls|xlsx|odt|ods|txt|csv|jpg|jpeg|png)$""", RegexOption.IGNORE_CASE)

    fun sanitize(title: String, typeName: String?, sequence: Int?): String {
        val base = title.trim().ifEmpty { typeName?.trim().orEmpty() }.ifEmpty { "documento" + (sequence?.let { "-$it" } ?: "") }
        var clean = base.replace(ILLEGAL, "_").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (clean.isEmpty()) clean = "documento"
        if (!KNOWN_EXT.containsMatchIn(clean)) clean = clean.take(100) + ".pdf"
        return if (clean.length > 120) clean.take(110).trimEnd() + clean.substring(clean.lastIndexOf('.')) else clean
    }
}

/**
 * Links oficiais (arquivos + páginas) por licitação, com cache local por oportunidade (DataStore) e "Atualizar".
 * Só funciona para oportunidades com número de controle PNCP.
 */
interface OfficialLinksRepository {
    /** Cache da licitação (null = nunca consultado). */
    fun observe(opportunityId: String): Flow<OfficialLinks?>

    /** Consulta o PNCP (arquivos + `linkSistemaOrigem`) e grava no cache. */
    suspend fun refresh(opportunityId: String): Result<OfficialLinks>

    /** `linkSistemaOrigem` (cache; senão consulta só a contratação). null sem número de controle ou sem link publicado. */
    suspend fun originUrl(opportunityId: String): String?
}
