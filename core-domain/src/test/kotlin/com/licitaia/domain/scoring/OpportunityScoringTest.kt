package com.licitaia.domain.scoring

import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunityScoringTest {
    private val isp = Company(1, "Conecta Minas Telecom Ltda", "Conecta Minas", "12345678000190", Segment.TELECOM_ISP, "MG", "Uberlândia")

    private fun opp(
        obj: String,
        segment: Segment,
        uf: String,
        value: Double = 300_000.0,
        portal: Portal = Portal.COMPRAS_GOV,
    ) = Opportunity(
        id = "${portal.name}:1/2026", portal = portal, number = "1/2026", agency = "Prefeitura Municipal",
        objectDescription = obj, modality = Modality.PREGAO_ELETRONICO, segment = segment, uf = uf,
        city = "Uberaba", estimatedValue = value, publishedAt = 0, proposalDeadline = 1_000, sessionAt = 2_000,
    )

    @Test
    fun adherentOpportunityScoresHigherThanUnrelated() {
        val good = opp("Contratação de link dedicado de internet em fibra óptica", Segment.TELECOM_ISP, "MG")
        val bad = opp("Aquisição de gêneros alimentícios", Segment.SERVICOS, "AM")
        val sGood = OpportunityScorer.score(good, isp)
        val sBad = OpportunityScorer.score(bad, isp)
        assertTrue("good=$sGood", sGood >= 80)
        assertTrue("bad=$sBad", sBad < 45)
    }

    @Test
    fun radarForbiddenKeywordExcludes() {
        val radar = Radar(
            companyId = 1, name = "Links", segment = Segment.TELECOM_ISP,
            keywords = listOf("internet"), forbiddenKeywords = listOf("satélite"),
        )
        assertTrue(RadarMatcher.matches(radar, opp("Link de internet dedicado", Segment.TELECOM_ISP, "MG")))
        assertFalse(RadarMatcher.matches(radar, opp("Internet via satelite", Segment.TELECOM_ISP, "MG")))
    }

    @Test
    fun radarRespectsUfPortalAndValue() {
        val radar = Radar(
            companyId = 1, name = "MG", segment = Segment.TELECOM_ISP, ufs = listOf("MG"),
            allPortals = false, portals = listOf(Portal.BLL), maxValue = 500_000.0,
        )
        assertTrue(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "MG", portal = Portal.BLL)))
        assertFalse(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "SP", portal = Portal.BLL)))
        assertFalse(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "MG")))
        assertFalse(RadarMatcher.matches(radar, opp("Internet", Segment.TELECOM_ISP, "MG", 900_000.0, Portal.BLL)))
    }

    @Test
    fun filterQueryIgnoresAccentsAndCase() {
        val o = opp("Contratação de FIBRA ÓPTICA", Segment.TELECOM_ISP, "MG")
        assertTrue(OpportunityFilterMatcher.matches(OpportunityFilter(query = "fibra optica"), o))
        assertFalse(OpportunityFilterMatcher.matches(OpportunityFilter(query = "software"), o))
        assertFalse(OpportunityFilterMatcher.matches(OpportunityFilter(minValue = 1_000_000.0), o))
    }
}
