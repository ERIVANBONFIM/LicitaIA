package com.licitaia.core.ai

import com.licitaia.domain.model.DocumentType
import java.text.Normalizer
import java.util.Locale

/**
 * Converte os nomes de documentos de habilitação devolvidos pelo modelo (texto livre, como consta
 * no edital) para [DocumentType] do cofre. Nomes sem correspondência são devolvidos em
 * [Mapping.unmatched] para a UI mostrar o que o cofre não representa — nunca são inventados.
 */
object DocumentTypeMapping {

    data class Mapping(val types: List<DocumentType>, val unmatched: List<String>)

    /** Regras na ordem de prioridade: a primeira que casar vence. */
    private val rules: List<Pair<DocumentType, List<String>>> = listOf(
        DocumentType.SCM to listOf("scm", "anatel", "servico de comunicacao multimidia"),
        DocumentType.CREA_CRT to listOf("crea", "crt", "cft", "conselho regional de engenharia", "registro no conselho"),
        DocumentType.TRABALHISTA to listOf("cndt", "debitos trabalhistas", "justica do trabalho", "trabalhista"),
        DocumentType.FGTS to listOf("fgts", "crf", "caixa economica", "regularidade do fgts"),
        DocumentType.CERTIDAO_FEDERAL to listOf(
            "receita federal", "tributos federais", "divida ativa da uniao", "pgfn", "fazenda nacional", "federa", "inss", "previdenciaria", "seguridade social",
        ),
        DocumentType.CERTIDAO_ESTADUAL to listOf("estadua", "sefaz", "icms", "distrital", "distrito federal"),
        DocumentType.CERTIDAO_MUNICIPAL to listOf("municipa", "iss ", "issqn", "prefeitura"),
        DocumentType.BALANCO to listOf("balanco", "demonstracoes contabeis", "demonstracao contabil", "indices contabeis", "indice de liquidez", "patrimonio liquido", "capital social minimo"),
        DocumentType.CONTRATO_SOCIAL to listOf("contrato social", "estatuto", "ato constitutivo", "registro comercial", "requerimento de empresario", "junta comercial", "inscricao do ato"),
        DocumentType.CNPJ to listOf("cnpj", "cadastro nacional de pessoa juridica", "cadastro nacional da pessoa juridica", "inscricao cadastral"),
        DocumentType.ATESTADO to listOf("atestado", "capacidade tecnica", "qualificacao tecnica", "comprovacao de aptidao", "aptidao para desempenho", "experiencia anterior"),
        DocumentType.PROCURACAO to listOf("procuracao", "credenciamento do representante", "carta de credenciamento", "representante legal"),
        DocumentType.CERTIFICADO to listOf("certificado", "certificacao", "iso ", "iso-", "iso9", "iso2", "registro no cadastro", "sicaf", "certificado de registro cadastral", "crc"),
        DocumentType.DECLARACAO to listOf(
            "declaracao", "declaracoes", "menor", "trabalho infantil", "fatos impeditivos", "inexistencia de fato", "me/epp", "microempresa", "cumprimento", "elaboracao independente", "reserva de cargos",
        ),
    )

    fun map(names: List<String>): Mapping {
        val types = linkedSetOf<DocumentType>()
        val unmatched = mutableListOf<String>()
        names.map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            val type = match(name)
            if (type != null) types += type else unmatched += name
        }
        return Mapping(types.toList(), unmatched.distinct())
    }

    /** Casa um nome de documento (ou a constante do enum / o rótulo) com um [DocumentType]; null = desconhecido. */
    fun match(name: String): DocumentType? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        DocumentType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true) }?.let { return it }
        val folded = fold(raw)
        if (folded == "outros" || folded == "outro") return DocumentType.OUTROS
        // Fazenda do Distrito Federal é esfera estadual, apesar de conter "federal".
        if (folded.contains("distrito federal") || folded.contains("distrital")) return DocumentType.CERTIDAO_ESTADUAL
        // "Certidão negativa" sozinha não diz a esfera: deixa para as regras de federal/estadual/municipal.
        return rules.firstOrNull { (_, terms) -> terms.any { folded.contains(it) } }?.first
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
