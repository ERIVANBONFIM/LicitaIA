package com.licitaia.domain.lookup

import com.licitaia.domain.model.Segment
import com.licitaia.domain.util.BrDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAutofillTest {

    @Test
    fun `logradouro mais numero`() {
        assertEquals("RUA X, 123", CompanyAutofill.streetWithNumber("RUA", "X", "123"))
        // Tipo já incluso no logradouro não é duplicado.
        assertEquals("RUA DAS FLORES, 10", CompanyAutofill.streetWithNumber("RUA", "RUA DAS FLORES", "10"))
        assertEquals("AVENIDA BRASIL, S/N", CompanyAutofill.streetWithNumber("AVENIDA", "BRASIL", "SN"))
        assertEquals("AVENIDA BRASIL, S/N", CompanyAutofill.streetWithNumber("AVENIDA", "BRASIL", "S/N"))
        assertEquals("BRASIL", CompanyAutofill.streetWithNumber("", " BRASIL ", ""))
        assertEquals("", CompanyAutofill.streetWithNumber("RUA", "", "12"))
    }

    @Test
    fun `numero digitado e mantido ao aplicar o CEP`() {
        assertEquals("123", CompanyAutofill.numberOf("Rua Velha, 123"))
        assertEquals("45A", CompanyAutofill.numberOf("Rua Velha, 45A"))
        assertEquals("S/N", CompanyAutofill.numberOf("Rua Velha, S/N"))
        assertNull(CompanyAutofill.numberOf("Rua 25 de Março"))
        assertEquals("Rua Nova, 123", CompanyAutofill.mergeStreetFromCep("Rua Velha, 123", "Rua Nova"))
        assertEquals("Rua Nova", CompanyAutofill.mergeStreetFromCep("", "Rua Nova"))
        assertEquals("Rua Nova", CompanyAutofill.mergeStreetFromCep("Rua Velha", "Rua Nova"))
        // CEP geral (sem logradouro) não apaga o que foi digitado.
        assertEquals("Rua Velha, 9", CompanyAutofill.mergeStreetFromCep("Rua Velha, 9", ""))
    }

    @Test
    fun `selecao do socio administrador`() {
        val qsa = listOf(
            CnpjPartner("JOAO DA SILVA", "Sócio"),
            CnpjPartner("ANA LIMA", "Administrador"),
            CnpjPartner("MARIA SOUZA", "49-Sócio-Administrador"),
        )
        assertEquals("MARIA SOUZA", CompanyAutofill.pickLegalRepresentative(qsa)?.name)
        assertEquals("ANA LIMA", CompanyAutofill.pickLegalRepresentative(qsa.take(2))?.name)
        assertEquals("FERNANDA", CompanyAutofill.pickLegalRepresentative(listOf(CnpjPartner("FERNANDA", "Presidente")))?.name)
        assertNull(CompanyAutofill.pickLegalRepresentative(qsa.take(1)))
        assertNull(CompanyAutofill.pickLegalRepresentative(emptyList()))
        assertEquals("Sócio-Administrador", CompanyAutofill.roleFromQualification("49-Sócio-Administrador"))
        assertEquals("Sócio-Administrador", CompanyAutofill.roleFromQualification("SÓCIO-ADMINISTRADOR"))
        assertEquals("Administrador", CompanyAutofill.roleFromQualification("Administrador"))
    }

    @Test
    fun `segmento sugerido pelo CNAE`() {
        assertEquals(Segment.TELECOM_ISP, CompanyAutofill.suggestSegment("6190601", "Provedores de acesso às redes de comunicações"))
        assertEquals(Segment.TELECOM_ISP, CompanyAutofill.suggestSegment("6110-8/01", "Serviços de telefonia fixa comutada"))
        assertEquals(Segment.SOFTWARE, CompanyAutofill.suggestSegment("6201501", "Desenvolvimento de programas de computador sob encomenda"))
        assertEquals(Segment.TI, CompanyAutofill.suggestSegment("6209100", "Suporte técnico, manutenção e outros serviços em tecnologia da informação"))
        assertEquals(Segment.EQUIPAMENTOS, CompanyAutofill.suggestSegment("4651601", "Comércio atacadista de equipamentos de informática"))
        assertEquals(Segment.SERVICOS, CompanyAutofill.suggestSegment("8121400", "Limpeza em prédios e em domicílios"))
        assertNull(CompanyAutofill.suggestSegment("", ""))
    }

    @Test
    fun `telefone com DDD`() {
        assertEquals("1123851939", CompanyAutofill.phoneDigits("1123851939"))
        assertEquals("1123851939", CompanyAutofill.phoneDigits("(11) 2385-1939"))
        assertEquals("1123851939", CompanyAutofill.phoneDigits("011 23851939"))
    }

    @Test
    fun `digitos verificadores do CNPJ`() {
        assertTrue(BrDocuments.isValidCnpj("11222333000181"))
        assertTrue(BrDocuments.isValidCnpj("11.222.333/0001-81"))
        assertTrue(BrDocuments.isValidCnpj("19131243000197"))
        assertFalse(BrDocuments.isValidCnpj("11222333000182"))
        assertFalse(BrDocuments.isValidCnpj("11111111111111"))
        assertFalse(BrDocuments.isValidCnpj("1122233300018"))
    }
}
