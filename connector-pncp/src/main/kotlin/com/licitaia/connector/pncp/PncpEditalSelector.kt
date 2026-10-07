package com.licitaia.connector.pncp

import com.licitaia.connector.api.OfficialDocument
import java.text.Normalizer
import java.util.Locale

/**
 * Escolha do edital oficial na lista de arquivos de uma contratação do PNCP
 * (`GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/arquivos`).
 *
 * Campos reais observados em 06/10/2026: `url`/`uri` (https://pncp.gov.br/pncp-api/v1/orgaos/.../arquivos/{n}),
 * `titulo` (às vezes entre aspas: "\"pre180-24instalacao.pdf\""), `tipoDocumentoId`/`tipoDocumentoNome`
 * (2 = "Edital", 4 = "Termo de Referência", 16 = "Outros Documentos"), `statusAtivo`, `dataPublicacaoPncp`,
 * `sequencialDocumento`. O download devolve `application/octet-stream` com `content-disposition`.
 *
 * Regras (lógica pura e testável):
 * 1. Principal = documento do tipo "Edital" (id 2 ou nome "Edital"); entre vários, prefere título com "edital"
 *    e depois o mais recente (republicações). Sem tipo "Edital": título com "edital"; senão o primeiro PDF; senão o primeiro.
 * 2. Anexos = Termo de Referência, Minuta do Contrato, Anteprojeto, Projeto Básico, ou título com "anexo"/"termo de
 *    referência"/"projeto básico" — no máximo [MAX_ANNEXES]. Outras versões do tipo "Edital" não são anexadas.
 */
internal object PncpEditalSelector {

    const val TIPO_EDITAL = 2L
    /** Minuta do Contrato (3), Termo de Referência (4), Anteprojeto (5), Projeto Básico (6) — `/v1/tipos-documentos`. */
    val ANNEX_TYPES: Set<Long> = setOf(3L, 4L, 5L, 6L)
    const val MAX_ANNEXES = 3

    private val ANNEX_TITLE_TERMS = listOf("termo de referencia", "anexo", "projeto basico", "minuta")

    fun select(documents: List<PncpDocumento>): List<OfficialDocument> {
        val usable = documents.filter { it.statusAtivo != false && downloadUrl(it) != null }
        if (usable.isEmpty()) return emptyList()

        val editais = usable.filter(::isEditalType)
        val main = editais.sortedWith(
            compareByDescending<PncpDocumento> { fold(cleanTitle(it)).contains("edital") }
                .thenByDescending { it.dataPublicacaoPncp.orEmpty() }
                .thenByDescending { it.sequencialDocumento ?: 0 },
        ).firstOrNull()
            ?: usable.firstOrNull { fold(cleanTitle(it)).contains("edital") }
            ?: usable.firstOrNull { cleanTitle(it).endsWith(".pdf", ignoreCase = true) }
            ?: usable.first()

        val annexes = usable.asSequence()
            .filter { it !== main && !isEditalType(it) }
            .filter { doc -> doc.tipoDocumentoId in ANNEX_TYPES || ANNEX_TITLE_TERMS.any { fold(cleanTitle(doc)).contains(it) } }
            .sortedWith(compareBy<PncpDocumento> { if (it.tipoDocumentoId == 4L) 0 else 1 }.thenBy { it.sequencialDocumento ?: Int.MAX_VALUE })
            .distinctBy { downloadUrl(it) }
            .take(MAX_ANNEXES)
            .toList()

        return listOf(main.toOfficial(OfficialDocument.Role.EDITAL)) + annexes.map { it.toOfficial(OfficialDocument.Role.ANEXO) }
    }

    private fun isEditalType(doc: PncpDocumento): Boolean =
        doc.tipoDocumentoId == TIPO_EDITAL || doc.tipoDocumentoNome?.trim().equals("Edital", ignoreCase = true)

    private fun downloadUrl(doc: PncpDocumento): String? =
        (doc.url ?: doc.uri)?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }

    /** Título sem as aspas que o PNCP às vezes inclui; vazio → "Documento <n>". */
    fun cleanTitle(doc: PncpDocumento): String =
        doc.titulo?.trim()?.trim('"', '\'', ' ')?.takeIf { it.isNotEmpty() }
            ?: "Documento ${doc.sequencialDocumento ?: ""}".trim()

    private fun PncpDocumento.toOfficial(role: OfficialDocument.Role) = OfficialDocument(
        title = cleanTitle(this),
        url = downloadUrl(this)!!,
        typeName = tipoDocumentoNome?.trim()?.takeIf { it.isNotEmpty() },
        role = role,
    )

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace('_', ' ').replace('-', ' ')
}
