package com.licitaia.feature.settings.companies

import com.licitaia.domain.lookup.CepData
import com.licitaia.domain.lookup.CnpjData
import com.licitaia.domain.lookup.CnpjPartner
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyFormAutofillTest {

    private val receita = CnpjData(
        cnpj = "11222333000181", legalName = "REDE SUL TELECOM LTDA", tradeName = "REDE SUL",
        streetType = "RUA", street = "DAS FLORES", number = "123", complement = "SALA 4", district = "CENTRO",
        zipCode = "13010100", city = "CAMPINAS", uf = "SP", phone = "1932324545", email = "contato@redesul.com.br",
        status = "ATIVA", cnaeCode = "6190601", cnaeDescription = "Provedores de acesso às redes de comunicações",
        partners = listOf(CnpjPartner("JOAO DA SILVA", "Sócio"), CnpjPartner("MARIA SOUZA", "Sócio-Administrador")),
        source = "BrasilAPI",
    )

    @Test
    fun `preenche somente campos vazios`() {
        val form = CompanyForm(cnpjDigits = "11222333000181", tradeName = "Minha Rede", segment = Segment.TI)
        val out = CompanyFormAutofill.applyCnpj(form, receita, overwrite = false)
        assertEquals("REDE SUL TELECOM LTDA", out.name)
        assertEquals("Minha Rede", out.tradeName) // já preenchido: mantido
        assertEquals("RUA DAS FLORES, 123", out.street)
        assertEquals("SALA 4", out.complement)
        assertEquals("CENTRO", out.district)
        assertEquals("13010100", out.zipDigits)
        assertEquals("CAMPINAS", out.city)
        assertEquals("SP", out.uf)
        assertEquals("1932324545", out.phoneDigits)
        assertEquals("contato@redesul.com.br", out.email)
        assertEquals("MARIA SOUZA", out.legalRepName)
        assertEquals("Sócio-Administrador", out.legalRepRole)
        assertEquals("", out.legalRepCpfDigits) // CPF nunca vem da Receita
        // Segmento ainda não escolhido pelo usuário: sugerido pelo CNAE.
        assertEquals(Segment.TELECOM_ISP, out.segment)
        assertEquals("Receita", out.autoFilled["name"])
        assertFalse(out.autoFilled.containsKey("tradeName"))
        // O CEP preenchido pela Receita não dispara nova consulta de CEP.
        assertEquals("13010100", out.lastZipLookup)

        val conflicts = CompanyFormAutofill.conflicts(out, receita)
        assertEquals(listOf("tradeName"), conflicts.map { it.key })
        assertEquals("REDE SUL", conflicts.single().incoming)
    }

    @Test
    fun `segmento escolhido e empresa existente nao sao alterados`() {
        val existing = CompanyForm.from(
            Company(id = 3, name = "Rede Sul", tradeName = "Rede Sul", cnpj = "11222333000181", segment = Segment.SOFTWARE, uf = "MG", city = "Uberaba"),
        )
        val out = CompanyFormAutofill.applyCnpj(existing, receita, overwrite = false)
        assertEquals(Segment.SOFTWARE, out.segment)
        assertEquals("Uberaba", out.city)
        assertEquals("MG", out.uf) // UF acompanha a cidade já preenchida
        assertEquals(setOf("name", "city", "uf"), CompanyFormAutofill.conflicts(out, receita).map { it.key }.toSet())
    }

    @Test
    fun `atualizar com os dados da Receita sobrescreve os divergentes`() {
        val form = CompanyForm(name = "Rede Sul Ltda", city = "Uberaba", uf = "MG", legalRepName = "José", legalRepRole = "Diretor", legalRepCpfDigits = "52998224725")
        val out = CompanyFormAutofill.applyCnpj(form, receita, overwrite = true)
        assertEquals("REDE SUL TELECOM LTDA", out.name)
        assertEquals("CAMPINAS", out.city)
        assertEquals("SP", out.uf)
        assertEquals("MARIA SOUZA", out.legalRepName)
        assertEquals("Sócio-Administrador", out.legalRepRole)
        assertEquals("52998224725", out.legalRepCpfDigits) // CPF digitado nunca é apagado
        assertTrue(CompanyFormAutofill.conflicts(out, receita).isEmpty())
    }

    @Test
    fun `valor igual com caixa diferente nao e conflito`() {
        val form = CompanyForm(name = "Rede Sul Telecom Ltda", phoneDigits = "1932324545")
        assertFalse(CompanyFormAutofill.conflicts(form, receita).any { it.key == "name" || it.key == "phone" })
    }

    @Test
    fun `representante ja digitado nao recebe cargo de outro socio`() {
        val form = CompanyForm(legalRepName = "José", legalRepRole = "")
        val out = CompanyFormAutofill.applyCnpj(form, receita, overwrite = false)
        assertEquals("José", out.legalRepName)
        assertEquals("", out.legalRepRole)
    }

    @Test
    fun `CEP preenche endereco mantendo numero e complemento`() {
        val form = CompanyForm(street = "Rua Velha, 45", complement = "Sala 2", district = "", city = "", zipDigits = "48903000")
        val out = CompanyFormAutofill.applyCep(form, CepData("48903000", street = "Rua Juscelino Kubitschek", district = "Centro", city = "Juazeiro", uf = "BA", source = "ViaCEP"))
        assertEquals("Rua Juscelino Kubitschek, 45", out.street)
        assertEquals("Sala 2", out.complement)
        assertEquals("Centro", out.district)
        assertEquals("Juazeiro", out.city)
        assertEquals("BA", out.uf)
        assertEquals("CEP", out.autoFilled["street"])
    }

    @Test
    fun `CEP geral de cidade nao apaga logradouro`() {
        val form = CompanyForm(street = "Rua Velha, 45", district = "Bairro X")
        val out = CompanyFormAutofill.applyCep(form, CepData("48900000", city = "Juazeiro", uf = "BA"))
        assertEquals("Rua Velha, 45", out.street)
        assertEquals("Bairro X", out.district)
        assertEquals("Juazeiro", out.city)
        assertEquals("BA", out.uf)
    }

    @Test
    fun `situacao nao ativa gera aviso`() {
        assertFalse(CompanyFormAutofill.cnpjStatus(receita, 3).warning)
        assertTrue(CompanyFormAutofill.cnpjStatus(receita, 3).message!!.startsWith("Situação: ATIVA"))
        val baixada = CompanyFormAutofill.cnpjStatus(receita.copy(status = "BAIXADA"), 0)
        assertTrue(baixada.warning)
        assertTrue(baixada.message!!.contains("BAIXADA"))
    }

    @Test
    fun `validacao de digitos do CNPJ no formulario`() {
        assertTrue(isValidCnpj("11222333000181"))
        assertFalse(isValidCnpj("11222333000180"))
        assertFalse(isValidCnpj("1122233300018"))
    }
}
