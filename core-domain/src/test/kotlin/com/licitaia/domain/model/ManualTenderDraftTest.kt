package com.licitaia.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTenderDraftTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_780_000_000_000L

    private fun valid() = ManualTenderDraft(
        portal = Portal.COMPRAS_GOV, number = "90012/2026", agency = "Prefeitura Municipal de Uberaba",
        objectDescription = "Contratação de link dedicado de internet em fibra óptica",
        modality = Modality.PREGAO_ELETRONICO, segment = Segment.TELECOM_ISP, uf = "MG", city = "Uberaba",
        estimatedValue = 420_000.0, proposalDeadline = now + 10 * day, sessionAt = now + 11 * day,
        editalUrl = "https://pncp.gov.br/app/editais/1",
    )

    @Test fun validDraftHasNoErrors() {
        assertTrue(valid().validate().isEmpty())
        assertTrue(valid().isValid)
        assertTrue(valid().copy(editalUrl = null).isValid)
        assertTrue(valid().copy(estimatedValue = 0.0).isValid) // valor sigiloso/não informado
    }

    @Test fun requiredFieldsAreValidated() {
        val errors = valid().copy(number = "1", agency = "", objectDescription = "curto", uf = "MGS", city = " ").validate()
        assertTrue(errors.any { it.contains("número", true) })
        assertTrue(errors.any { it.contains("órgão", true) })
        assertTrue(errors.any { it.contains("objeto", true) })
        assertTrue(errors.any { it.contains("UF") })
        assertTrue(errors.any { it.contains("cidade", true) })
    }

    @Test fun datesAndValueAreValidated() {
        assertTrue(valid().copy(proposalDeadline = 0L).validate().any { it.contains("prazo", true) })
        assertTrue(valid().copy(sessionAt = 0L).validate().any { it.contains("sessão", true) })
        assertTrue(valid().copy(sessionAt = now + 9 * day).validate().any { it.contains("antes do prazo") })
        assertTrue(valid().copy(estimatedValue = -1.0).validate().any { it.contains("Valor") })
        assertTrue(valid().copy(estimatedValue = Double.NaN).validate().any { it.contains("Valor") })
    }

    @Test fun editalUrlMustBeHttp() {
        assertTrue(valid().copy(editalUrl = "ftp://x").validate().any { it.contains("URL") })
        assertTrue(valid().copy(editalUrl = "pncp.gov.br").validate().any { it.contains("URL") })
        assertFalse(valid().copy(editalUrl = "http://pncp.gov.br").validate().any { it.contains("URL") })
        assertTrue(valid().copy(editalUrl = "   ").isValid)
    }

    @Test fun opportunityIdIsStablePerPortalAndNumber() {
        val a = valid().opportunityId()
        val b = valid().copy(number = " 90012/2026 ", agency = "Outro órgão").opportunityId()
        assertEquals(a, b)
        assertTrue(a.startsWith(Tender.MANUAL_OPPORTUNITY_PREFIX))
        assertTrue(valid().copy(portal = Portal.BLL).opportunityId() != a)
        assertTrue(valid().copy(number = "90013/2026").opportunityId() != a)
    }

    @Test fun manualTenderIsRecognizedByPrefix() {
        val tender = Tender(
            companyId = 1, opportunityId = valid().opportunityId(), portal = Portal.COMPRAS_GOV, number = "90012/2026",
            agency = "x", objectDescription = "y", modality = Modality.PREGAO_ELETRONICO, segment = Segment.TI,
            uf = "MG", city = "Uberaba", estimatedValue = 1.0, proposalDeadline = now, sessionAt = now,
        )
        assertTrue(tender.isManual)
        assertFalse(tender.hasEditalText)
        assertTrue(tender.copy(editalTextPath = "/x/1.txt", editalChars = 1_200).hasEditalText)
        assertFalse(tender.copy(editalTextPath = "/x/1.txt", editalChars = 0).hasEditalText)
        assertFalse(tender.copy(opportunityId = "COMPRAS_GOV:90012/2026").isManual)
    }

    @Test fun analysisWithoutAiFieldsIsHeuristic() {
        val extracted = ExtractedEdital("o", "a", "p", "n", "m", 1.0, now, now, now, "", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val fit = FitScore(50, 50, 50, 50, 10.0, RiskLevel.MEDIO, RiskLevel.MEDIO, RiskLevel.MEDIO, 50, 4, 50, 0.0)
        val analysis = TenderAnalysis(1, "x", now, "s", extracted, fit, Recommendation.AVALIAR, "j", emptyList(), PriceRange(1.0, 2.0, 3.0), emptyList())
        assertTrue(analysis.heuristicOnly)
        assertFalse(analysis.copy(aiFields = setOf("summary")).heuristicOnly)
    }
}
