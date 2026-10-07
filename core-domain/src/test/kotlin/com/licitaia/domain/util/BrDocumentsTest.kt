package com.licitaia.domain.util

import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrDocumentsTest {

    private val base = Company(name = "Rede Sul Telecom Ltda", tradeName = "Rede Sul", cnpj = "11222333000181", segment = Segment.TELECOM_ISP, uf = "SP", city = "Campinas")

    @Test
    fun `cpf valido pelos digitos verificadores com ou sem mascara`() {
        assertTrue(BrDocuments.isValidCpf("52998224725"))
        assertTrue(BrDocuments.isValidCpf("529.982.247-25"))
        assertTrue(BrDocuments.isValidCpf("111.444.777-35"))
    }

    @Test
    fun `cpf invalido por digito, tamanho ou sequencia repetida`() {
        assertFalse(BrDocuments.isValidCpf("52998224724"))
        assertFalse(BrDocuments.isValidCpf("52998224715"))
        assertFalse(BrDocuments.isValidCpf("5299822472"))
        assertFalse(BrDocuments.isValidCpf("111.111.111-11"))
        assertFalse(BrDocuments.isValidCpf(""))
    }

    @Test
    fun `cep exige 8 digitos`() {
        assertTrue(BrDocuments.isValidCep("13010-100"))
        assertTrue(BrDocuments.isValidCep("13010100"))
        assertFalse(BrDocuments.isValidCep("1301010"))
        assertFalse(BrDocuments.isValidCep("130101000"))
    }

    @Test
    fun `email exige arroba e dominio`() {
        assertTrue(BrDocuments.isValidEmail("contato@redesul.com.br"))
        assertTrue(BrDocuments.isValidEmail("  a.b@c.io "))
        assertFalse(BrDocuments.isValidEmail("contato.redesul.com.br"))
        assertFalse(BrDocuments.isValidEmail("contato@redesul"))
        assertFalse(BrDocuments.isValidEmail("con tato@redesul.com"))
    }

    @Test
    fun `telefone exige DDD e 10 ou 11 digitos`() {
        assertTrue(BrDocuments.isValidPhone("(19) 3232-4545"))
        assertTrue(BrDocuments.isValidPhone("19987654321"))
        assertFalse(BrDocuments.isValidPhone("32324545")) // sem DDD
        assertFalse(BrDocuments.isValidPhone("(01) 3232-4545")) // DDD inválido
        assertFalse(BrDocuments.isValidPhone("19887654321")) // celular sem o 9
        assertFalse(BrDocuments.isValidPhone("199876543210"))
    }

    @Test
    fun `formatadores de cpf, cep e telefone`() {
        assertEquals("529.982.247-25", Formatters.cpf("52998224725"))
        assertEquals("123", Formatters.cpf("123"))
        assertEquals("13010-100", Formatters.cep("13010100"))
        assertEquals("1301", Formatters.cep("1301"))
        assertEquals("(19) 3232-4545", Formatters.phone("1932324545"))
        assertEquals("(19) 98765-4321", Formatters.phone("19987654321"))
        assertEquals("98765", Formatters.phone("98765"))
    }

    @Test
    fun `endereco, contato e banco omitem partes vazias`() {
        assertEquals("", base.streetLine())
        assertEquals("Campinas/SP", base.fullAddress())
        assertEquals("", base.contactLine())
        assertEquals("", base.bankLine())
        val full = base.copy(
            street = "Rua das Flores, 123", complement = "Sala 4", district = "Centro", zipCode = "13010100",
            phone = "19987654321", email = "contato@redesul.com.br",
            bankName = "Banco do Brasil", bankAgency = "1234-5", bankAccount = "98765-0",
        )
        assertEquals("Rua das Flores, 123, Sala 4 – Centro", full.streetLine())
        assertEquals("Rua das Flores, 123, Sala 4 – Centro – Campinas/SP – CEP 13010-100", full.fullAddress())
        assertEquals("Tel. (19) 98765-4321 · contato@redesul.com.br", full.contactLine())
        assertEquals("Banco do Brasil · Agência 1234-5 · Conta 98765-0", full.bankLine())
        assertEquals("Rua das Flores, 123 – Centro", full.copy(complement = "").streetLine())
        assertEquals("contato@redesul.com.br", full.copy(phone = "").contactLine())
    }

    @Test
    fun `dados essenciais que faltam para o pdf`() {
        assertEquals(listOf("endereço", "representante legal"), base.missingProposalData())
        val withAddress = base.copy(street = "Rua A, 1", zipCode = "13010100")
        assertEquals(listOf("representante legal"), withAddress.missingProposalData())
        assertEquals(listOf("representante legal"), withAddress.copy(legalRepName = "Maria").missingProposalData())
        assertTrue(withAddress.copy(legalRepName = "Maria", legalRepCpf = "52998224725").missingProposalData().isEmpty())
    }
}
