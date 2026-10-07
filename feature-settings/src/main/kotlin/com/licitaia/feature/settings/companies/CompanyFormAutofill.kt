package com.licitaia.feature.settings.companies

import com.licitaia.domain.lookup.CepData
import com.licitaia.domain.lookup.CnpjData
import com.licitaia.domain.lookup.CompanyAutofill
import com.licitaia.domain.util.BrDocuments
import java.util.Locale

/** Estado de uma consulta (CNPJ ou CEP) exibido abaixo do campo. */
data class LookupStatus(
    val loading: Boolean = false,
    val message: String? = null,
    /** Mensagem de atenção (amarelo): ex. situação cadastral diferente de ATIVA. */
    val warning: Boolean = false,
    val error: Boolean = false,
)

/** Campo já preenchido com valor diferente do que veio da consulta (para "Atualizar com os dados da Receita"). */
data class FieldConflict(val key: String, val label: String, val current: String, val incoming: String)

/**
 * Preenchimento do formulário da empresa a partir da consulta de CNPJ (Receita) e de CEP. Por padrão só preenche
 * campos vazios; com `overwrite` substitui os divergentes. Os campos preenchidos ficam marcados em [CompanyForm.autoFilled].
 */
internal object CompanyFormAutofill {
    const val SOURCE_RECEITA = "Receita"
    const val SOURCE_CEP = "CEP"

    val LABELS = linkedMapOf(
        "name" to "Razão social", "tradeName" to "Nome fantasia", "street" to "Logradouro e número", "complement" to "Complemento",
        "district" to "Bairro", "zip" to "CEP", "city" to "Cidade", "uf" to "UF", "phone" to "Telefone", "email" to "E-mail",
        "legalRepName" to "Representante legal", "legalRepRole" to "Cargo",
    )

    /** Valores da Receita por campo do formulário (somente os não vazios). */
    fun cnpjValues(data: CnpjData): Map<String, String> {
        val rep = CompanyAutofill.pickLegalRepresentative(data.partners)
        val values = linkedMapOf(
            "name" to data.legalName,
            "tradeName" to data.tradeName,
            "street" to data.streetLine,
            "complement" to data.complement,
            "district" to data.district,
            "zip" to data.zipCode.filter { it.isDigit() }.take(8).takeIf { it.length == 8 }.orEmpty(),
            "city" to data.city,
            "uf" to data.uf.uppercase(Locale.ROOT).takeIf { it in BRAZIL_UFS }.orEmpty(),
            "phone" to data.phone.takeIf { BrDocuments.isValidPhone(it) }.orEmpty(),
            "email" to data.email.takeIf { BrDocuments.isValidEmail(it) }.orEmpty(),
            "legalRepName" to rep?.name.orEmpty(),
            "legalRepRole" to rep?.let { CompanyAutofill.roleFromQualification(it.qualification) }.orEmpty(),
        )
        return values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
    }

    fun valueOf(form: CompanyForm, key: String): String = when (key) {
        "name" -> form.name
        "tradeName" -> form.tradeName
        "street" -> form.street
        "complement" -> form.complement
        "district" -> form.district
        "zip" -> form.zipDigits
        "city" -> form.city
        "uf" -> form.uf
        "phone" -> form.phoneDigits
        "email" -> form.email
        "legalRepName" -> form.legalRepName
        "legalRepRole" -> form.legalRepRole
        else -> ""
    }

    private fun CompanyForm.with(key: String, value: String): CompanyForm = when (key) {
        "name" -> copy(name = value.take(150))
        "tradeName" -> copy(tradeName = value.take(100))
        "street" -> copy(street = value.take(150))
        "complement" -> copy(complement = value.take(80))
        "district" -> copy(district = value.take(80))
        "zip" -> copy(zipDigits = value.filter { it.isDigit() }.take(8))
        "city" -> copy(city = value.take(80))
        "uf" -> copy(uf = value)
        "phone" -> copy(phoneDigits = value.filter { it.isDigit() }.take(11))
        "email" -> copy(email = value.take(120))
        "legalRepName" -> copy(legalRepName = value.take(120))
        "legalRepRole" -> copy(legalRepRole = value.take(80))
        else -> this
    }

    /** Vazio para fins de preenchimento. A UF sempre tem valor (padrão SP): conta como vazia enquanto não há cidade. */
    fun isEmpty(form: CompanyForm, key: String): Boolean = when (key) {
        "uf" -> form.city.isBlank()
        // Cargo acompanha o nome do representante: só é sugerido quando o nome também está vazio.
        "legalRepRole" -> form.legalRepName.isBlank() && form.legalRepRole.isBlank()
        else -> valueOf(form, key).isBlank()
    }

    fun sameValue(key: String, a: String, b: String): Boolean = when (key) {
        "zip", "phone" -> a.filter { it.isDigit() } == b.filter { it.isDigit() }
        else -> normalize(a) == normalize(b)
    }

    private fun normalize(s: String) = CompanyAutofillText.normalize(s)

    /** Campos já preenchidos (pelo usuário) com valor diferente do da Receita. */
    fun conflicts(form: CompanyForm, data: CnpjData): List<FieldConflict> = cnpjValues(data).mapNotNull { (key, incoming) ->
        if (key == "legalRepRole") return@mapNotNull null // acompanha o nome do representante
        val current = valueOf(form, key)
        if (isEmpty(form, key) || sameValue(key, current, incoming)) null
        else FieldConflict(key, LABELS[key] ?: key, current, incoming)
    }

    /**
     * Aplica a consulta do CNPJ. Sem [overwrite]: só campos vazios. Com [overwrite]: também os divergentes.
     * Segmento: sugerido pelo CNAE somente se o usuário ainda não escolheu ([CompanyForm.segmentTouched]).
     */
    fun applyCnpj(form: CompanyForm, data: CnpjData, overwrite: Boolean): CompanyForm {
        val values = cnpjValues(data)
        var out = form
        val filled = form.autoFilled.toMutableMap()
        val repWillChange = "legalRepName" in values && (isEmpty(form, "legalRepName") ||
            (overwrite && !sameValue("legalRepName", form.legalRepName, values.getValue("legalRepName"))))
        for ((key, value) in values) {
            val apply = when (key) {
                "legalRepName" -> repWillChange
                "legalRepRole" -> repWillChange && (form.legalRepRole.isBlank() || overwrite)
                // A UF acompanha a cidade.
                "uf" -> isEmpty(form, "uf") || (overwrite && form.uf != value)
                else -> isEmpty(form, key) || (overwrite && !sameValue(key, valueOf(form, key), value))
            }
            if (apply) {
                if (!sameValue(key, valueOf(out, key), value)) out = out.with(key, value)
                filled[key] = SOURCE_RECEITA
            }
        }
        if (!form.segmentTouched) {
            CompanyAutofill.suggestSegment(data.cnaeCode, data.cnaeDescription)?.let { s ->
                out = out.copy(segment = s)
                filled["segment"] = SOURCE_RECEITA
            }
        }
        // O CEP veio junto com o endereço da Receita: não dispara uma nova consulta de CEP.
        return out.copy(autoFilled = filled, lastZipLookup = out.zipDigits)
    }

    /**
     * Aplica a consulta do CEP digitado: logradouro (mantendo o número já digitado), bairro, cidade e UF.
     * Complemento e número digitados nunca são apagados; partes vazias do CEP (CEP geral de cidade) não alteram nada.
     */
    fun applyCep(form: CompanyForm, cep: CepData): CompanyForm {
        val filled = form.autoFilled.toMutableMap()
        var out = form
        if (cep.street.isNotBlank()) {
            out = out.copy(street = CompanyAutofill.mergeStreetFromCep(form.street, cep.street).take(150))
            filled["street"] = SOURCE_CEP
        }
        if (cep.district.isNotBlank()) {
            out = out.copy(district = cep.district.trim().take(80))
            filled["district"] = SOURCE_CEP
        }
        if (cep.city.isNotBlank()) {
            out = out.copy(city = cep.city.trim().take(80))
            filled["city"] = SOURCE_CEP
        }
        val uf = cep.uf.uppercase(Locale.ROOT)
        if (uf in BRAZIL_UFS) {
            out = out.copy(uf = uf)
            filled["uf"] = SOURCE_CEP
        }
        filled["zip"] = SOURCE_CEP
        return out.copy(autoFilled = filled)
    }

    /** Texto de status da consulta do CNPJ ("Situação: ATIVA · BrasilAPI"). */
    fun cnpjStatus(data: CnpjData, filledCount: Int): LookupStatus {
        val situation = data.status.ifBlank { "não informada" }
        val filled = when (filledCount) {
            0 -> "nenhum campo vazio para preencher"
            1 -> "1 campo preenchido"
            else -> "$filledCount campos preenchidos"
        }
        return if (data.isActive) {
            LookupStatus(message = "Situação: $situation · $filled (${data.source})")
        } else {
            LookupStatus(
                message = "Atenção — situação cadastral: $situation. Empresas não ATIVAS não podem participar de licitações. $filled (${data.source})",
                warning = true,
            )
        }
    }
}

/** Normalização para comparar textos (sem acento, caixa ou espaços extras). */
internal object CompanyAutofillText {
    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")
}
