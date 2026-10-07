package com.licitaia.connector.pncp

import com.licitaia.connector.api.OfficialDocument
import com.licitaia.domain.edital.EditalDocKind
import com.licitaia.domain.edital.EditalDocumentBase

/**
 * Documentos oficiais de uma contratação do PNCP (`GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/arquivos`)
 * para a base de perguntas e a análise.
 *
 * Campos reais observados em 06/10/2026: `url`/`uri` (https://pncp.gov.br/pncp-api/v1/orgaos/.../arquivos/{n}),
 * `titulo` (às vezes entre aspas: "\"pre180-24instalacao.pdf\""), `tipoDocumentoId`/`tipoDocumentoNome`
 * (2 = "Edital", 4 = "Termo de Referência", 7 = "Estudo Técnico Preliminar", 16 = "Outros Documentos"), `statusAtivo`,
 * `dataPublicacaoPncp`, `sequencialDocumento`. O download devolve `application/octet-stream` com `content-disposition`.
 *
 * Regras (lógica pura e testável):
 * 1. Principal = documento do tipo "Edital" (id 2 ou nome "Edital"); entre vários, prefere título com "edital" e depois o
 *    mais recente (republicações). Sem tipo "Edital": documento classificado como edital/aviso pelo título; senão o
 *    primeiro PDF que NÃO seja Estudo Técnico Preliminar; senão o primeiro.
 * 2. Demais documentos = TODOS os outros ativos (termo de referência, anexos, projeto básico, minuta, ETP, outros), na
 *    ordem de prioridade de [EditalDocumentBase] — no máximo [MAX_ANNEXES]. Outras versões do tipo "Edital" ficam de fora
 *    (republicação substituída poderia trazer datas/valores antigos).
 */
internal object PncpEditalSelector {

    const val TIPO_EDITAL = 2L
    /** Documentos além do principal (a base inteira tem até [EditalDocumentBase.MAX_DOCUMENTS]). */
    const val MAX_ANNEXES = EditalDocumentBase.MAX_DOCUMENTS - 1

    fun select(documents: List<PncpDocumento>): List<OfficialDocument> {
        val usable = documents.filter { it.statusAtivo != false && downloadUrl(it) != null }
        if (usable.isEmpty()) return emptyList()

        val editais = usable.filter(::isEditalType)
        val main = editais.sortedWith(
            compareByDescending<PncpDocumento> { kindOf(it) == EditalDocKind.EDITAL && cleanTitle(it).contains("edital", ignoreCase = true) }
                .thenByDescending { it.dataPublicacaoPncp.orEmpty() }
                .thenByDescending { it.sequencialDocumento ?: 0 },
        ).firstOrNull()
            ?: usable.firstOrNull { kindOf(it) == EditalDocKind.EDITAL }
            ?: usable.firstOrNull { cleanTitle(it).endsWith(".pdf", ignoreCase = true) && kindOf(it) != EditalDocKind.ETP }
            ?: usable.first()

        val others = usable.filter { it !== main && !isEditalType(it) }.distinctBy { downloadUrl(it) }
        val ordered = EditalDocumentBase.prioritize(others.sortedBy { it.sequencialDocumento ?: Int.MAX_VALUE }) { kindOf(it) }
            .take(MAX_ANNEXES)

        return listOf(main.toOfficial(OfficialDocument.Role.EDITAL)) + ordered.map { it.toOfficial(OfficialDocument.Role.ANEXO) }
    }

    fun kindOf(doc: PncpDocumento): EditalDocKind = EditalDocumentBase.classify(cleanTitle(doc), doc.tipoDocumentoNome, doc.tipoDocumentoId)

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
        typeId = tipoDocumentoId,
    )
}
