package com.licitaia.feature.settings.companies

import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyFormTest {

    private val valid = CompanyForm(name = "Rede Sul Telecom Ltda", cnpjDigits = "11222333000181", city = "Campinas", uf = "SP")

    @Test
    fun `campos novos sao opcionais`() {
        assertTrue(validateCompanyForm(valid, emptyList()).isEmpty())
    }

    @Test
    fun `campos preenchidos sao validados`() {
        val errors = validateCompanyForm(
            valid.copy(zipDigits = "1301010", phoneDigits = "32324545", email = "contato.redesul.com", legalRepCpfDigits = "52998224724"),
            emptyList(),
        )
        assertEquals(setOf("zip", "phone", "email", "cpf"), errors.keys)
        val ok = validateCompanyForm(
            valid.copy(zipDigits = "13010100", phoneDigits = "19987654321", email = "contato@redesul.com.br", legalRepCpfDigits = "52998224725"),
            emptyList(),
        )
        assertTrue(ok.isEmpty())
    }

    @Test
    fun `validacoes antigas continuam`() {
        val other = Company(id = 9, name = "Outra", tradeName = "Outra", cnpj = "11.222.333/0001-81", segment = Segment.TELECOM_ISP, uf = "SP", city = "SP")
        val errors = validateCompanyForm(valid.copy(name = " ", city = ""), listOf(other))
        assertEquals("Informe a razão social", errors["name"])
        assertEquals("Informe a cidade", errors["city"])
        assertEquals("Já existe uma empresa com este CNPJ", errors["cnpj"])
    }

    @Test
    fun `formulario ida e volta preserva todos os campos`() {
        val company = Company(
            id = 3, name = "Rede Sul Telecom Ltda", tradeName = "Rede Sul", cnpj = "11222333000181", segment = Segment.TELECOM_ISP, uf = "SP", city = "Campinas",
            street = "Rua das Flores, 123", complement = "Sala 4", district = "Centro", zipCode = "13010100", phone = "19987654321",
            email = "contato@redesul.com.br", legalRepName = "Maria Souza", legalRepCpf = "52998224725", legalRepRole = "Sócia-administradora",
            bankName = "Banco do Brasil", bankAgency = "1234-5", bankAccount = "98765-0",
        )
        assertEquals(company, CompanyForm.from(company).toCompany())
        // Textos são aparados ao salvar.
        val trimmed = CompanyForm.from(company).copy(street = "  Rua X, 1 ", legalRepName = " Ana ").toCompany()
        assertEquals("Rua X, 1", trimmed.street)
        assertEquals("Ana", trimmed.legalRepName)
    }

    @Test
    fun `mascaras de exibicao`() {
        val cep = DigitMaskTransformation { "#####-###" }
        assertEquals("13010-100", cep.apply("13010100").first)
        assertEquals("13010-1", cep.apply("130101").first)
        assertEquals("1301", cep.apply("1301").first)
        val phone = DigitMaskTransformation { if (it <= 10) "(##) ####-####" else "(##) #####-####" }
        assertEquals("(19) 3232-4545", phone.apply("1932324545").first)
        assertEquals("(19) 98765-4321", phone.apply("19987654321").first)
        assertEquals("", phone.apply("").first)
        val cpf = DigitMaskTransformation { "###.###.###-##" }
        val (text, positions) = cpf.apply("52998224725")
        assertEquals("529.982.247-25", text)
        assertEquals(text.length, positions.last())
        assertEquals(4, positions[3]) // 4º dígito vem depois do primeiro ponto
    }
}
