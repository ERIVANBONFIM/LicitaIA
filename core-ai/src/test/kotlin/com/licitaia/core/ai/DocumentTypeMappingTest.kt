package com.licitaia.core.ai

import com.licitaia.domain.model.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTypeMappingTest {

    @Test
    fun mapsTypicalEditalWording() {
        val cases = mapOf(
            "Prova de inscrição no Cadastro Nacional de Pessoa Jurídica (CNPJ)" to DocumentType.CNPJ,
            "Ato constitutivo, estatuto ou contrato social em vigor" to DocumentType.CONTRATO_SOCIAL,
            "Certidão Conjunta Negativa de Débitos relativos a Tributos Federais e à Dívida Ativa da União" to DocumentType.CERTIDAO_FEDERAL,
            "Prova de regularidade com a Fazenda Estadual (ICMS)" to DocumentType.CERTIDAO_ESTADUAL,
            "Certidão negativa de débitos municipais do domicílio da licitante" to DocumentType.CERTIDAO_MUNICIPAL,
            "Certificado de Regularidade do FGTS – CRF" to DocumentType.FGTS,
            "Certidão Negativa de Débitos Trabalhistas – CNDT" to DocumentType.TRABALHISTA,
            "Balanço patrimonial e demonstrações contábeis do último exercício social" to DocumentType.BALANCO,
            "Ato de autorização para exploração do Serviço de Comunicação Multimídia (SCM) expedido pela Anatel" to DocumentType.SCM,
            "Registro ou inscrição da empresa no CREA" to DocumentType.CREA_CRT,
            "Atestado(s) de capacidade técnica emitido(s) por pessoa jurídica de direito público ou privado" to DocumentType.ATESTADO,
            "Procuração do representante legal" to DocumentType.PROCURACAO,
            "Certificado ISO 27001" to DocumentType.CERTIFICADO,
            "Declaração de que não emprega menor de 18 anos em trabalho noturno" to DocumentType.DECLARACAO,
            "Declaração de inexistência de fatos impeditivos" to DocumentType.DECLARACAO,
            "Certidão de regularidade com a Fazenda do Distrito Federal" to DocumentType.CERTIDAO_ESTADUAL,
        )
        cases.forEach { (name, expected) -> assertEquals(name, expected, DocumentTypeMapping.match(name)) }
    }

    @Test
    fun acceptsEnumNamesAndLabels() {
        assertEquals(DocumentType.FGTS, DocumentTypeMapping.match("FGTS"))
        assertEquals(DocumentType.TRABALHISTA, DocumentTypeMapping.match("trabalhista"))
        assertEquals(DocumentType.ATESTADO, DocumentTypeMapping.match("Atestado de Capacidade Técnica"))
        assertEquals(DocumentType.OUTROS, DocumentTypeMapping.match("Outros"))
    }

    @Test
    fun unknownNamesAreReportedNotInvented() {
        assertNull(DocumentTypeMapping.match("Certidão negativa de falência e recuperação judicial"))
        assertNull(DocumentTypeMapping.match("Garantia de proposta de 1%"))
        assertNull(DocumentTypeMapping.match(""))
        val mapping = DocumentTypeMapping.map(listOf("CNDT", "Certidão de falência", " ", "Contrato social", "Certidão de falência"))
        assertEquals(listOf(DocumentType.TRABALHISTA, DocumentType.CONTRATO_SOCIAL), mapping.types)
        assertEquals(listOf("Certidão de falência"), mapping.unmatched)
    }

    @Test
    fun mappingDeduplicatesTypesPreservingOrder() {
        val mapping = DocumentTypeMapping.map(listOf("Certidão federal", "CRF do FGTS", "Receita Federal / PGFN", "Atestado"))
        assertEquals(listOf(DocumentType.CERTIDAO_FEDERAL, DocumentType.FGTS, DocumentType.ATESTADO), mapping.types)
        assertTrue(mapping.unmatched.isEmpty())
    }
}
