package com.licitaia.ai.mock

import com.licitaia.ai.api.TenderAnalysisRequest
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Recommendation
import com.licitaia.domain.model.RiskLevel
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.Tender
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockAIProviderTest {
    private val now = 1_780_000_000_000L
    private val day = CompanyDocument.DAY_MS
    private val provider = MockAIProvider(latencyMs = 0L..0L, clock = { now })

    private val isp = Company(1, "Conecta Minas Telecom Ltda", "Conecta Minas", "12345678000190", Segment.TELECOM_ISP, "MG", "Uberlândia")

    private fun tender(uf: String = "MG", city: String = "Uberaba", segment: Segment = Segment.TELECOM_ISP) = Tender(
        id = 7, companyId = 1, opportunityId = "COMPRAS_GOV:90012/2026", portal = Portal.COMPRAS_GOV,
        number = "90012/2026", agency = "Prefeitura Municipal de Uberaba",
        objectDescription = "Contratação de link dedicado de internet em fibra óptica de 1 Gbps",
        modality = Modality.PREGAO_ELETRONICO, segment = segment, uf = uf, city = city,
        estimatedValue = 420_000.0, proposalDeadline = now + 12 * day, sessionAt = now + 13 * day,
    )

    private fun doc(type: DocumentType, expiresInDays: Long?) = CompanyDocument(
        companyId = 1, type = type, title = type.label, issuedAt = now - 60 * day,
        expiresAt = expiresInDays?.let { now + it * day }, attachmentUri = "file://x",
    )

    private val fullDocs = listOf(
        DocumentType.CONTRATO_SOCIAL, DocumentType.CNPJ, DocumentType.CERTIDAO_FEDERAL,
        DocumentType.CERTIDAO_ESTADUAL, DocumentType.CERTIDAO_MUNICIPAL, DocumentType.FGTS,
        DocumentType.TRABALHISTA, DocumentType.BALANCO, DocumentType.SCM, DocumentType.ATESTADO,
        DocumentType.DECLARACAO,
    ).map { doc(it, 200) }

    @Test
    fun adherentTenderWithFullDocsIsRecommended() = runTest {
        val a = provider.analyzeTender(TenderAnalysisRequest(tender(), isp, fullDocs, null, now))
        assertEquals(Recommendation.PARTICIPAR, a.recommendation)
        assertEquals(100, a.fit.documentary)
        assertTrue(a.fit.overall >= 80)
        assertTrue(a.priceRange.min < a.priceRange.suggested && a.priceRange.suggested < a.priceRange.max)
        assertTrue(a.priceRange.max <= 420_000.0)
        assertTrue(a.checklist.filter { it.relatedDocument != null }.all { it.done })
    }

    @Test
    fun analysisIsDeterministic() = runTest {
        val req = TenderAnalysisRequest(tender(), isp, fullDocs, null, now)
        assertEquals(provider.analyzeTender(req), provider.analyzeTender(req))
    }

    @Test
    fun missingScmAndExpiredDocsBlockRecommendation() = runTest {
        val docs = fullDocs.filter { it.type != DocumentType.SCM && it.type != DocumentType.FGTS } +
            doc(DocumentType.FGTS, -3)
        val a = provider.analyzeTender(TenderAnalysisRequest(tender(), isp, docs, null, now))
        assertNotEquals(Recommendation.PARTICIPAR, a.recommendation)
        assertEquals(RiskLevel.CRITICO, a.fit.documentaryRisk)
        assertTrue(a.criticalPoints.any { it.contains("SCM") })
        assertTrue(a.criticalPoints.any { it.contains("FGTS") })
        assertTrue(a.checklist.any { it.relatedDocument == DocumentType.FGTS && !it.done && it.critical })
    }

    @Test
    fun distantUnrelatedTenderIsNotRecommended() = runTest {
        val t = tender(uf = "AM", city = "Manaus", segment = Segment.EQUIPAMENTOS)
        val a = provider.analyzeTender(TenderAnalysisRequest(t, isp, emptyList(), null, now))
        assertEquals(Recommendation.NAO_PARTICIPAR, a.recommendation)
    }

    @Test
    fun proposalDraftTotalMatchesSuggestedPrice() = runTest {
        val t = tender()
        val a = provider.analyzeTender(TenderAnalysisRequest(t, isp, fullDocs, null, now))
        val draft = provider.draftProposal(t, a, isp)
        val total = draft.items.sumOf { it.total }
        assertTrue(kotlin.math.abs(total - a.priceRange.suggested) < 1.0)
    }

    @Test
    fun messageAboutExequibilidadeIsUrgentWithShortDeadline() = runTest {
        val msg = AuctioneerMessage(
            companyId = 1, portal = Portal.BLL, tenderNumber = "45/2026", sender = "Pregoeiro",
            body = "Solicito o envio da planilha de composição de custos em 30 minutos, sob pena de desclassificação.",
            receivedAt = now, responseDeadline = now + 30 * 60_000,
        )
        val draft = provider.draftMessage(msg, isp)
        assertTrue(draft.urgent)
        assertTrue(draft.suggestedReply.contains("exequibilidade"))
    }

    @Test
    fun compareDocumentsSplitsByStatus() = runTest {
        val docs = listOf(doc(DocumentType.CNPJ, null), doc(DocumentType.FGTS, 5), doc(DocumentType.TRABALHISTA, -1))
        val c = provider.compareDocuments(
            listOf(DocumentType.CNPJ, DocumentType.FGTS, DocumentType.TRABALHISTA, DocumentType.SCM), docs, now,
        )
        assertEquals(listOf(DocumentType.CNPJ), c.satisfied)
        assertEquals(listOf(DocumentType.FGTS), c.expiring)
        assertEquals(listOf(DocumentType.TRABALHISTA), c.expired)
        assertEquals(listOf(DocumentType.SCM), c.missing)
        assertEquals(50, c.coveragePct)
    }
}
