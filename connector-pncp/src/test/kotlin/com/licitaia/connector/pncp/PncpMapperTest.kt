package com.licitaia.connector.pncp

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Mapeamento JSON real do PNCP (fixture capturada em 06/10/2026) → [com.licitaia.domain.model.Opportunity]. */
class PncpMapperTest {

    private val json: Json = PncpConnector.defaultJson()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/pncp/$name")) { "fixture $name ausente" }.bufferedReader().readText()

    private fun saoPaulo(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli()

    @Test
    fun `numero de controle PNCP e decomposto em cnpj, ano e sequencial`() {
        val ref = PncpControlNumber.parse("20918579000183-1-000016/2025")
        assertNotNull(ref)
        assertEquals("20918579000183", ref!!.cnpj)
        assertEquals(2025, ref.ano)
        assertEquals(16, ref.sequencial)
        assertEquals("20918579000183-1-000016/2025", ref.raw)
        assertEquals("PNCP:20918579000183-1-000016/2025", ref.opportunityId)
        assertEquals("https://pncp.gov.br/app/editais/20918579000183/2025/16", ref.publicPageUrl)
        assertEquals(ref, PncpControlNumber.fromOpportunityId("PNCP:20918579000183-1-000016/2025"))
        assertNull(PncpControlNumber.parse("COMPRAS_GOV:90045/2026"))
        assertNull(PncpControlNumber.parse(null))
    }

    @Test
    fun `codigos de modalidade confirmados pela API`() {
        assertEquals(6, PncpModalities.codeOf(Modality.PREGAO_ELETRONICO))
        assertEquals(8, PncpModalities.codeOf(Modality.DISPENSA_ELETRONICA))
        assertEquals(4, PncpModalities.codeOf(Modality.CONCORRENCIA))
        assertEquals(12, PncpModalities.codeOf(Modality.CREDENCIAMENTO))
        assertEquals(Modality.CONCORRENCIA, PncpModalities.modalityOf(5))
        assertNull("Pregão presencial não é representado", PncpModalities.modalityOf(7))
        assertNull("Inexigibilidade não é representada", PncpModalities.modalityOf(9))
        assertNull(PncpModalities.modalityOf(null))
    }

    @Test
    fun `pagina real de contratacoes com proposta aberta e mapeada`() {
        val page = json.decodeFromString(PncpPage.serializer(PncpContratacao.serializer()), fixture("contratacoes_proposta_mg_p1.json"))
        assertEquals(371, page.totalRegistros)
        assertEquals(37, page.paginasRestantes)
        assertEquals(10, page.data.size)

        val mapped = page.data.mapNotNull(PncpMapper::toOpportunity)
        assertEquals(10, mapped.size)
        assertTrue(mapped.all { it.uf == "MG" && it.modality == Modality.CREDENCIAMENTO && it.id.startsWith("PNCP:") })
        // usuarioNome "ECustomize Consultoria em Software S.A" (2 registros, links portaldecompraspublicas.com.br) → PCP; demais → PNCP.
        assertEquals(2, mapped.count { it.portal == Portal.PORTAL_COMPRAS_PUBLICAS })
        assertEquals(8, mapped.count { it.portal == Portal.PNCP })
        assertEquals(mapped.size, mapped.map { it.id }.distinct().size)

        val first = mapped.first()
        assertEquals("PNCP:20918579000183-1-000016/2025", first.id)
        assertEquals("Licitar Digital", first.platformName)
        assertEquals("004/2025", first.number)
        assertEquals("FUNDACAO MUNICIPAL DE SAUDE DE ESTRELA DO INDAIA", first.agency)
        assertTrue(first.objectDescription.startsWith("CONTRATAÇÃO DE LABORATÓRIO DE ANÁLISES CLÍNICAS"))
        assertEquals("Estrela do Indaiá", first.city)
        assertEquals(162001.4, first.estimatedValue, 0.0001)
        assertEquals(saoPaulo(2025, 9, 12, 15, 15, 4), first.publishedAt)
        assertEquals(saoPaulo(2026, 10, 6, 23, 59, 59), first.proposalDeadline)
        assertEquals(first.proposalDeadline, first.sessionAt)
        assertEquals("https://pncp.gov.br/app/editais/20918579000183/2025/16", first.editalUrl)
        assertTrue(first.keywords.any { it.equals("Credenciamento", true) })
        assertTrue(first.keywords.contains("20918579000183-1-000016/2025"))
    }

    @Test
    fun `detalhe real da contratacao e mapeado`() {
        val dto = json.decodeFromString(PncpContratacao.serializer(), fixture("contratacao_20918579000183_2025_16.json"))
        val opp = PncpMapper.toOpportunity(dto)
        assertNotNull(opp)
        assertEquals("PNCP:20918579000183-1-000016/2025", opp!!.id)
        assertEquals(Modality.CREDENCIAMENTO, opp.modality)
    }

    @Test
    fun `contratacao com modalidade nao representada ou sem numero de controle e descartada`() {
        val base = json.decodeFromString(PncpContratacao.serializer(), fixture("contratacao_20918579000183_2025_16.json"))
        assertNull(PncpMapper.toOpportunity(base.copy(modalidadeId = 9)))
        assertNull(PncpMapper.toOpportunity(base.copy(numeroControlePNCP = "inválido")))
        // Orçamento sigiloso / valor ausente: 0.0, nunca um valor inventado.
        assertEquals(0.0, PncpMapper.toOpportunity(base.copy(valorTotalEstimado = null))!!.estimatedValue, 0.0)
    }

    @Test
    fun `datas sem fuso sao interpretadas em horario de Brasilia`() {
        assertEquals(saoPaulo(2025, 9, 12, 15, 15, 4), PncpMapper.parseDate("2025-09-12T15:15:04"))
        assertEquals(saoPaulo(2025, 9, 12, 0, 0, 0), PncpMapper.parseDate("2025-09-12"))
        assertNull(PncpMapper.parseDate(null))
        assertNull(PncpMapper.parseDate("não é data"))
        assertEquals("20261006", PncpMapper.queryDate(saoPaulo(2026, 10, 6, 12, 0, 0)))
    }

    @Test
    fun `segmento e inferido por palavras do objeto, senao PERSONALIZADO`() {
        assertEquals(Segment.TELECOM_ISP, PncpMapper.inferSegment("Contratação de link dedicado de internet 1 Gbps em fibra óptica"))
        assertEquals(Segment.EQUIPAMENTOS, PncpMapper.inferSegment("Aquisição de notebooks e monitores"))
        assertEquals(Segment.SERVICOS, PncpMapper.inferSegment("Prestação de serviço de limpeza predial"))
        assertEquals(Segment.PERSONALIZADO, PncpMapper.inferSegment("Aquisição de gêneros alimentícios para merenda escolar"))
        assertEquals(Segment.PERSONALIZADO, PncpMapper.inferSegment(""))
    }

    @Test
    fun `itens reais sao descritos de forma legivel`() {
        val items = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PncpItem.serializer()), fixture("itens_20918579000183_2025_16.json"))
        assertTrue(items.isNotEmpty())
        val text = PncpMapper.describeItem(items.first())
        assertTrue(text, text.startsWith("Item 1 — ANTI TRANSGLUTAMINASE IGG E IGA"))
        assertTrue(text, text.contains("30 SE"))
    }
}
