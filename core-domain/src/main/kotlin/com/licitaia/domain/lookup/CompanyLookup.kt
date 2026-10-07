package com.licitaia.domain.lookup

import com.licitaia.domain.model.Segment
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/**
 * Consulta de dados cadastrais públicos: CNPJ (Receita Federal, via APIs públicas gratuitas) e CEP (Correios).
 * As implementações lançam [LookupException] com mensagem pronta para o usuário (pt-BR).
 */
interface CompanyLookup {
    /** [cnpj] com ou sem máscara (14 dígitos). */
    suspend fun lookupCnpj(cnpj: String): CnpjData

    /** [cep] com ou sem máscara (8 dígitos). */
    suspend fun lookupCep(cep: String): CepData
}

/** Falha na consulta. [notFound] = o documento não existe na base (não adianta tentar de novo). */
class LookupException(message: String, val notFound: Boolean = false, cause: Throwable? = null) : IOException(message, cause)

/** Sócio/administrador do quadro societário (QSA). O CPF nunca vem completo da Receita, por isso não é trazido. */
data class CnpjPartner(val name: String, val qualification: String)

/** Dados públicos do CNPJ (textos como vieram da Receita, normalmente em MAIÚSCULAS). */
data class CnpjData(
    val cnpj: String,
    val legalName: String,
    val tradeName: String = "",
    /** Tipo de logradouro separado (ex.: "RUA"), quando a fonte o informa à parte. */
    val streetType: String = "",
    val street: String = "",
    val number: String = "",
    val complement: String = "",
    val district: String = "",
    /** Somente dígitos. */
    val zipCode: String = "",
    val city: String = "",
    val uf: String = "",
    /** Somente dígitos, com DDD. */
    val phone: String = "",
    val email: String = "",
    /** Situação cadastral (ex.: "ATIVA", "BAIXADA"). */
    val status: String = "",
    val cnaeCode: String = "",
    val cnaeDescription: String = "",
    val size: String = "",
    val partners: List<CnpjPartner> = emptyList(),
    /** Fonte que respondeu (ex.: "BrasilAPI"). */
    val source: String = "",
) {
    val isActive: Boolean get() = status.trim().equals("ATIVA", ignoreCase = true)

    /** "RUA X, 123" — tipo + logradouro + número (sem duplicar o tipo quando ele já está no logradouro). */
    val streetLine: String get() = CompanyAutofill.streetWithNumber(streetType, street, number)
}

/** Endereço de um CEP. [street]/[district] podem vir vazios em CEP geral de cidade. */
data class CepData(
    val cep: String,
    val street: String = "",
    val complement: String = "",
    val district: String = "",
    val city: String = "",
    val uf: String = "",
    val source: String = "",
)

/** Regras puras (sem rede) usadas para preencher o cadastro a partir das consultas. */
object CompanyAutofill {

    /** Monta "RUA X, 123". Número vazio, "0" ou "SN" vira "S/N" somente se houver logradouro. */
    fun streetWithNumber(type: String, street: String, number: String): String {
        val s = street.trim().replace(Regex("\\s+"), " ")
        val t = type.trim()
        val base = when {
            s.isEmpty() -> return ""
            t.isEmpty() || s.uppercase(Locale.ROOT).startsWith(t.uppercase(Locale.ROOT) + " ") -> s
            else -> "$t $s"
        }
        val n = number.trim()
        val normalized = when {
            n.isEmpty() -> ""
            n.uppercase(Locale.ROOT).replace("/", "").replace(".", "") in setOf("SN", "SEMNUMERO", "SNº", "0") -> "S/N"
            else -> n
        }
        return if (normalized.isEmpty()) base else "$base, $normalized"
    }

    private val numberSuffix = Regex(",\\s*((?:\\d+[\\w\\-/]*)|(?:[sS]\\s*/\\s*[nN]))\\s*$")

    /** Número já digitado no fim do logradouro ("Rua A, 123" → "123"), ou null. */
    fun numberOf(streetLine: String): String? = numberSuffix.find(streetLine.trim())?.groupValues?.get(1)

    /**
     * Logradouro vindo do CEP aplicado sobre o que já foi digitado: mantém o número que o usuário já tinha
     * ("Rua Velha, 45" + CEP "Rua Nova" → "Rua Nova, 45"). CEP sem logradouro (CEP geral) não altera nada.
     */
    fun mergeStreetFromCep(current: String, cepStreet: String): String {
        val street = cepStreet.trim()
        if (street.isEmpty()) return current
        val number = numberOf(current)
        return if (number != null) "$street, $number" else street
    }

    /**
     * Representante legal sugerido pelo QSA: sócio-administrador, depois administrador/presidente/diretor/titular;
     * null se ninguém administra (ex.: só sócios quotistas).
     */
    fun pickLegalRepresentative(partners: List<CnpjPartner>): CnpjPartner? {
        val candidates = partners.filter { it.name.isNotBlank() }
        val ranks = listOf(
            "socio-administrador", "socio administrador", "administrador", "titular", "presidente", "diretor", "empresario",
        )
        for (key in ranks) {
            candidates.firstOrNull { normalize(it.qualification).contains(key) }?.let { return it }
        }
        return null
    }

    /** "49-Sócio-Administrador" / "SÓCIO-ADMINISTRADOR" → "Sócio-Administrador". */
    fun roleFromQualification(qualification: String): String {
        val text = qualification.trim().replace(Regex("^\\d+\\s*-\\s*"), "")
        return text.lowercase(Locale("pt", "BR")).split(" ").joinToString(" ") { word ->
            word.split("-").joinToString("-") { part ->
                if (part.length <= 2 && part in setOf("de", "da", "do", "e")) part
                else part.replaceFirstChar { it.titlecase(Locale("pt", "BR")) }
            }
        }
    }

    /** Segmento sugerido pela atividade principal (CNAE); null quando não há correspondência clara. */
    fun suggestSegment(cnaeCode: String, cnaeDescription: String): Segment? {
        val code = cnaeCode.filter { it.isDigit() }
        val d = normalize(cnaeDescription)
        return when {
            code.startsWith("61") || "telecomunica" in d || "provedor" in d || "internet" in d -> Segment.TELECOM_ISP
            code.startsWith("6201") || code.startsWith("6202") || code.startsWith("6203") || "software" in d || "programas de computador" in d -> Segment.SOFTWARE
            code.startsWith("46") || code.startsWith("47") || "comercio" in d -> Segment.EQUIPAMENTOS
            code.startsWith("62") || code.startsWith("63") || "tecnologia da informacao" in d || "informatica" in d -> Segment.TI
            code.length >= 2 && (code.startsWith("8") || code.startsWith("7") || code.startsWith("43")) -> Segment.SERVICOS
            "servico" in d -> Segment.SERVICOS
            else -> null
        }
    }

    /** "1133334444", "11 33334444", "(11) 3333-4444" → só dígitos com DDD (até 11). */
    fun phoneDigits(raw: String): String = raw.filter { it.isDigit() }.trimStart('0').take(11)

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
