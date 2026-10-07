package com.licitaia.core.data.competition

import com.licitaia.domain.competition.CompetitionSyncReport
import com.licitaia.domain.competition.PublicAwardResult
import com.licitaia.domain.competition.TrackedTender
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionResultsTest {

    private val control = "20918579000183-1-000016/2025"

    private fun tender(participated: Boolean, outcome: Boolean? = null) = TrackedTender(
        tenderId = 3, controlNumber = control, portal = Portal.COMPRAS_GOV, number = "90016/2025", agency = "Prefeitura X",
        segment = Segment.TELECOM_ISP, objectDescription = "Link dedicado", estimatedValue = 60_000.0, sessionAt = 1_000L,
        participated = participated, knownOutcome = outcome,
    )

    private fun award(doc: String, name: String, hom: Double, item: Int = 1, est: Double = 50_000.0) = PublicAwardResult(
        controlNumber = control, itemNumber = item, supplierName = name, supplierDocument = doc,
        estimatedValue = est, homologatedValue = hom, resultDate = 5_000L,
    )

    @Test
    fun `derrota com vencedor e CNPJ publicos`() {
        val record = ResultRecords.toRecord(7, tender(participated = true), listOf(award("98765432000155", "Fibra Norte", 42_000.0)), "12345678000190", 9_999L)!!
        assertFalse(record.won)
        assertEquals(42_000.0, record.closingValue, 0.0)
        assertEquals(50_000.0, record.estimatedValue, 0.0)
        assertEquals(0.0, record.ourFinalBid, 0.0)
        assertEquals(1, record.competitors)
        assertEquals(5_000L, record.date)
        assertTrue(record.behavior.contains("Fibra Norte (CNPJ 98.765.432/0001-55)"))
        assertTrue(record.behavior.contains("Resultado público PNCP $control"))
    }

    @Test
    fun `vitoria pelo CNPJ da empresa mesmo sem participacao marcada, com nosso valor homologado`() {
        val results = listOf(award("12345678000190", "Nossa Empresa", 30_000.0), award("11222333000144", "Outra", 10_000.0, item = 2, est = 12_000.0))
        val record = ResultRecords.toRecord(7, tender(participated = false), results, "12.345.678/0001-90", 0L)!!
        assertTrue(record.won)
        assertEquals(30_000.0, record.ourFinalBid, 0.0)
        assertEquals(40_000.0, record.closingValue, 0.0)
        assertEquals(62_000.0, record.estimatedValue, 0.0)
        assertEquals(2, record.competitors)
    }

    @Test
    fun `licitacao so de interesse nao vira vitoria nem derrota`() {
        assertNull(ResultRecords.toRecord(7, tender(participated = false), listOf(award("98765432000155", "Fibra", 1.0)), "12345678000190", 0L))
        assertNull(ResultRecords.toRecord(7, tender(participated = true), emptyList(), "12345678000190", 0L))
    }

    @Test
    fun `nao duplica registro ja importado ou registrado a mao`() {
        val imported = ResultRecords.toRecord(7, tender(true), listOf(award("98765432000155", "Fibra", 1.0)), "1", 0L)!!
        assertTrue(ResultRecords.alreadyRecorded(listOf(imported), tender(true)))
        val manual = imported.copy(behavior = "Fechou no último minuto", tenderNumber = "PE 90016/2025")
        assertTrue(ResultRecords.alreadyRecorded(listOf(manual), tender(true)))
        assertFalse(ResultRecords.alreadyRecorded(listOf(manual.copy(portal = Portal.BLL)), tender(true)))
    }

    @Test
    fun `base de mercado - codifica, mescla por chave e limita`() {
        val a = award("1", "A", 10.0).copy(agency = "Órgão", uf = "MG", ownTender = true, publishedDiscountPct = 3.0)
        val decoded = MarketStore.decode(MarketStore.encode(listOf(a)))
        assertEquals(listOf(a), decoded)
        val updated = a.copy(homologatedValue = 20.0)
        val merged = MarketStore.merge(listOf(a), listOf(updated, award("2", "B", 5.0)))
        assertEquals(2, merged.size)
        assertEquals(20.0, merged.first { it.supplierDocument == "1" }.homologatedValue, 0.0)
        assertTrue(MarketStore.decode("lixo").isEmpty())

        val report = CompetitionSyncReport(1L, 3, 1, 2, 4, partial = true)
        assertEquals(report, MarketStore.decodeReport(MarketStore.encodeReport(report)))
    }

    @Test
    fun `palavras do mercado vem dos radares ou do segmento`() {
        val radar = Radar(companyId = 7, name = "R", segment = Segment.TELECOM_ISP, keywords = listOf("link dedicado", "fibra", "ab"), preferredObject = "Fibra")
        assertEquals(listOf("link dedicado", "fibra"), MarketKeywords.from(listOf(radar), Segment.TI))
        assertTrue(MarketKeywords.from(emptyList(), Segment.TELECOM_ISP).isNotEmpty())
    }
}
