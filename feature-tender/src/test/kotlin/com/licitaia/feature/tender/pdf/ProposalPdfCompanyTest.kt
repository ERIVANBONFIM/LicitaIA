package com.licitaia.feature.tender.pdf

import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalPdfCompanyTest {

    private val minimal = Company(name = "Rede Sul Telecom Ltda", tradeName = "Rede Sul", cnpj = "11222333000181", segment = Segment.TELECOM_ISP, uf = "SP", city = "Campinas")
    private val full = minimal.copy(
        street = "Rua das Flores, 123", complement = "Sala 4", district = "Centro", zipCode = "13010100",
        phone = "1932324545", email = "contato@redesul.com.br",
        legalRepName = "Maria Souza", legalRepCpf = "52998224725", legalRepRole = "Sócia-administradora",
        bankName = "Banco do Brasil", bankAgency = "1234-5", bankAccount = "98765-0",
    )

    @Test
    fun `proponente sem cadastro complementar mostra so o basico`() {
        assertEquals(
            listOf("Razão social", "Nome fantasia", "CNPJ", "Município/UF"),
            ProposalPdfLayout.bidderFields(minimal).map { it.first },
        )
    }

    @Test
    fun `proponente completo com dados formatados`() {
        val fields = ProposalPdfLayout.bidderFields(full).toMap()
        assertEquals("11.222.333/0001-81", fields["CNPJ"])
        assertEquals("Rua das Flores, 123, Sala 4 – Centro", fields["Endereço"])
        assertEquals("Campinas/SP", fields["Município/UF"])
        assertEquals("13010-100", fields["CEP"])
        assertEquals("(19) 3232-4545", fields["Telefone"])
        assertEquals("contato@redesul.com.br", fields["E-mail"])
        assertEquals("Banco do Brasil · Agência 1234-5 · Conta 98765-0", fields["Dados bancários"])
        assertEquals("Maria Souza – Sócia-administradora", fields["Representante legal"])
        assertEquals("529.982.247-25", fields["CPF do representante"])
        // Campo vazio não aparece.
        assertTrue("Dados bancários" !in ProposalPdfLayout.bidderFields(full.copy(bankName = "", bankAgency = "", bankAccount = "")).toMap())
    }

    @Test
    fun `cabecalho prioriza cnpj, endereco e contato`() {
        assertEquals(
            listOf("Nome fantasia: Rede Sul", "CNPJ 11.222.333/0001-81", "Rua das Flores, 123, Sala 4 – Centro – Campinas/SP – CEP 13010-100", "Tel. (19) 3232-4545 · contato@redesul.com.br"),
            ProposalPdfLayout.headerLines(full, 4),
        )
        // Sem espaço: o nome fantasia sai primeiro.
        assertEquals(
            listOf("CNPJ 11.222.333/0001-81", "Rua das Flores, 123, Sala 4 – Centro – Campinas/SP – CEP 13010-100", "Tel. (19) 3232-4545 · contato@redesul.com.br"),
            ProposalPdfLayout.headerLines(full, 3),
        )
        assertEquals(listOf("Nome fantasia: Rede Sul", "CNPJ 11.222.333/0001-81", "Campinas/SP"), ProposalPdfLayout.headerLines(minimal, 4))
    }

    @Test
    fun `assinatura usa o representante legal do cadastro`() {
        assertEquals(
            listOf("Maria Souza", "Sócia-administradora · CPF: 529.982.247-25", "Rede Sul Telecom Ltda", "CNPJ 11.222.333/0001-81"),
            ProposalPdfLayout.signatureLines(full, "João (aprovador)"),
        )
    }

    @Test
    fun `assinatura sem cadastro mantem linha em branco para o cpf`() {
        val lines = ProposalPdfLayout.signatureLines(minimal, "João Lima")
        assertEquals("João Lima", lines[0])
        assertEquals("Representante legal · CPF: ____________________", lines[1])
        assertEquals("Representante legal", ProposalPdfLayout.signatureLines(minimal, " ")[0])
    }
}
