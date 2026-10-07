package com.licitaia.connector.pncp

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Campos extras dos itens (detalhe da aba Itens) e órgão/unidade compradora, a partir de JSON real do PNCP. */
class PncpItemDetailTest {

    private val json = PncpConnector.defaultJson()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/pncp/$name")) { "fixture $name ausente" }.bufferedReader().readText()

    @Test
    fun `item real traz situacao, categoria, beneficio, datas e margem sem quebrar com catalogo nulo`() {
        val items = json.decodeFromString(ListSerializer(PncpItem.serializer()), fixture("itens_20918579000183_2025_16.json"))
        val item = PncpMapper.toOfficialItem(items.first())!!
        assertEquals(1, item.number)
        assertEquals("Em andamento", item.situation)
        assertEquals("Não se aplica", item.category)
        assertEquals("Sem benefício", item.benefit)
        assertEquals("Material", item.materialOrService)
        assertEquals("2025-09-12T15:15:04", item.includedAt)
        assertEquals("2025-09-12T15:15:04", item.updatedAt)
        // aplicabilidadeMargemPreferencia* = false → "Não se aplica"; sem catálogo/NCM publicados → null.
        assertEquals("Não se aplica", item.preferenceMargin)
        assertNull(item.catalogName)
        assertNull(item.catalogCode)
        assertNull(item.ncmNbsCode)
        assertNull(item.complementaryInfo)
        assertEquals(false, item.productiveIncentive)
        assertEquals(false, item.nationalContentRequired)
    }

    @Test
    fun `catalogo como objeto, codigo numerico, NCM e margem com percentuais`() {
        val raw = """
            [{"numeroItem":4,"descricao":"Notebook 14 polegadas","materialOuServicoNome":"Material","quantidade":10,
              "unidadeMedida":"UN","valorUnitarioEstimado":4500.0,"valorTotal":45000.0,"orcamentoSigiloso":false,
              "criterioJulgamentoNome":"Menor preço","situacaoCompraItemNome":"Em andamento",
              "tipoBeneficioNome":"Participação exclusiva para ME/EPP","itemCategoriaNome":"Bens móveis",
              "informacaoComplementar":"Garantia on-site de 36 meses.","incentivoProdutivoBasico":true,
              "aplicabilidadeMargemPreferenciaNormal":true,"percentualMargemPreferenciaNormal":10,
              "aplicabilidadeMargemPreferenciaAdicional":true,"percentualMargemPreferenciaAdicional":5.5,
              "ncmNbsCodigo":84713012,"ncmNbsDescricao":"Computadores portáteis",
              "catalogo":{"id":1,"nome":"CATMAT"},"catalogoCodigoItem":"451230",
              "categoriaItemCatalogo":{"id":2,"nome":"Material"},"tipoMargemPreferencia":null,
              "dataInclusao":"2026-09-01T07:26:01","dataAtualizacao":"2026-09-02T10:00:00","temResultado":false}]
        """.trimIndent()
        val item = PncpMapper.toOfficialItem(json.decodeFromString(ListSerializer(PncpItem.serializer()), raw).single())!!
        assertEquals("CATMAT", item.catalogName)
        assertEquals("451230", item.catalogCode)
        assertEquals("84713012", item.ncmNbsCode)
        assertEquals("Computadores portáteis", item.ncmNbsDescription)
        assertEquals("Normal: 10% · Adicional: 5,50%", item.preferenceMargin)
        assertEquals("Garantia on-site de 36 meses.", item.complementaryInfo)
        assertEquals(true, item.productiveIncentive)
        assertEquals("Bens móveis", item.category)
        assertEquals(false, item.hasResult)
        assertEquals(45_000.0, item.referenceTotal!!, 0.0)
    }

    @Test
    fun `orgao e unidade compradora vem da contratacao`() {
        val compra = json.decodeFromString(PncpContratacao.serializer(), fixture("contratacao_20918579000183_2025_16.json"))
        val buyer = PncpMapper.toBuyer(compra)
        assertNotNull(buyer)
        assertEquals("FUNDACAO MUNICIPAL DE SAUDE DE ESTRELA DO INDAIA", buyer!!.agencyName)
        assertEquals("20918579000183", buyer.agencyCnpj)
        assertEquals("2064", buyer.unitCode)
        assertEquals("Unidade Única", buyer.unitName)
        assertEquals("Estrela do Indaiá", buyer.city)
        assertEquals("MG", buyer.uf)
        assertNull(PncpMapper.toBuyer(PncpContratacao()))
        assertFalse(buyer.fromOfficialSource.not())
    }
}
