package com.licitaia.domain.util

import com.licitaia.domain.model.Company

/** Validação leve de dados cadastrais brasileiros (CPF, CEP, e-mail, telefone). Aceita texto com ou sem máscara. */
object BrDocuments {
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun digits(raw: String): String = raw.filter { it.isDigit() }

    /** CPF com 11 dígitos e dígitos verificadores corretos (rejeita sequências repetidas como 111.111.111-11). */
    fun isValidCpf(raw: String): Boolean {
        val d = digits(raw)
        if (d.length != 11 || d.all { it == d[0] }) return false
        fun dv(length: Int): Int {
            val sum = (0 until length).sumOf { (d[it] - '0') * (length + 1 - it) }
            val r = (sum * 10) % 11
            return if (r == 10) 0 else r
        }
        return dv(9) == d[9] - '0' && dv(10) == d[10] - '0'
    }

    /** CNPJ com 14 dígitos e dígitos verificadores corretos (rejeita sequências repetidas). */
    fun isValidCnpj(raw: String): Boolean {
        val d = digits(raw)
        if (d.length != 14 || d.all { it == d[0] }) return false
        fun dv(base: String, weights: IntArray): Int {
            val r = base.indices.sumOf { (base[it] - '0') * weights[it] } % 11
            return if (r < 2) 0 else 11 - r
        }
        val w1 = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val w2 = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val d1 = dv(d.substring(0, 12), w1)
        val d2 = dv(d.substring(0, 12) + d1, w2)
        return d[12] - '0' == d1 && d[13] - '0' == d2
    }

    fun isValidCep(raw: String): Boolean = digits(raw).length == 8

    fun isValidEmail(raw: String): Boolean = emailRegex.matches(raw.trim())

    /** Fixo (10 dígitos) ou celular (11, começando por 9 após o DDD), com DDD de 11 a 99. */
    fun isValidPhone(raw: String): Boolean {
        val d = digits(raw)
        if (d.length != 10 && d.length != 11) return false
        if (d[0] == '0' || d[1] == '0') return false
        return d.length == 10 || d[2] == '9'
    }
}

/** Logradouro, número, complemento e bairro (ex.: "Rua das Flores, 123, Sala 4 – Centro"); vazio se nada informado. */
fun Company.streetLine(): String {
    val first = listOf(street, complement).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
    return listOf(first, district.trim()).filter { it.isNotEmpty() }.joinToString(" – ")
}

/** Endereço completo em uma linha, com cidade/UF e CEP formatado; vazio se nada informado. */
fun Company.fullAddress(): String {
    val cityUf = listOf(city.trim(), uf.trim()).filter { it.isNotEmpty() }.joinToString("/")
    val cep = zipCode.takeIf { it.isNotBlank() }?.let { "CEP ${Formatters.cep(it)}" }
    return listOfNotNull(streetLine().takeIf { it.isNotEmpty() }, cityUf.takeIf { it.isNotEmpty() }, cep).joinToString(" – ")
}

/** Telefone e e-mail formatados em uma linha (ex.: "Tel. (11) 98765-4321 · contato@empresa.com"); vazio se nada informado. */
fun Company.contactLine(): String = listOfNotNull(
    phone.takeIf { it.isNotBlank() }?.let { "Tel. ${Formatters.phone(it)}" },
    email.trim().takeIf { it.isNotEmpty() },
).joinToString(" · ")

/** Banco, agência e conta formatados (ex.: "Banco do Brasil · Agência 1234-5 · Conta 98765-0"); vazio se nada informado. */
fun Company.bankLine(): String = listOfNotNull(
    bankName.trim().takeIf { it.isNotEmpty() },
    bankAgency.trim().takeIf { it.isNotEmpty() }?.let { "Agência $it" },
    bankAccount.trim().takeIf { it.isNotEmpty() }?.let { "Conta $it" },
).joinToString(" · ")

/**
 * Dados essenciais que faltam para o PDF da proposta (rótulos em minúsculas, na ordem de exibição): endereço
 * (logradouro e CEP) e representante legal (nome e CPF). Lista vazia = cadastro suficiente.
 */
fun Company.missingProposalData(): List<String> = buildList {
    if (street.isBlank() || zipCode.isBlank()) add("endereço")
    if (legalRepName.isBlank() || legalRepCpf.isBlank()) add("representante legal")
}
