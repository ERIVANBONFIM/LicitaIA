package com.licitaia.domain.portal

import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalTenderMatchingTest {
    private fun mine(uasg: String, number: String, year: Int, pncp: String? = null) = PortalMyTender(
        companyId = 1, tenderKey = PortalTenderMatching.tenderKey(uasg, number, year)!!, uasg = uasg, number = number, year = year,
        modality = "", objectDescription = "", openingAt = null, situation = "", hasProposal = false, sources = emptySet(),
        pncpControl = pncp, firstSeenAt = 0, updatedAt = 0,
    )

    private fun opp(id: String, number: String, agency: String, portal: Portal = Portal.COMPRAS_GOV) = Opportunity(
        id = id, portal = portal, number = number, agency = agency, objectDescription = "", modality = Modality.PREGAO_ELETRONICO,
        segment = Segment.TI, uf = "DF", city = "Brasília", estimatedValue = 0.0, publishedAt = 0, proposalDeadline = 0, sessionAt = 0,
    )

    @Test fun keyNormalization() {
        assertEquals("160123-90005-2026", PortalTenderMatching.tenderKey("160123", "90005", 2026))
        assertEquals("090001-00003-2026", PortalTenderMatching.tenderKey("90001", "0003", 2026))
        assertNull(PortalTenderMatching.tenderKey(null, "1", 2026))
        assertNull(PortalTenderMatching.tenderKey("160123", "1", 1800))
        assertEquals("90005" to 2026, PortalTenderMatching.parseNumberYear("Pregão nº 90005/2026"))
    }

    @Test fun matchesByLegacyIdUasgNumberYear() {
        val m = PortalTenderMatching.match(
            mine("160123", "90005", 2026),
            listOf(opp("COMPRAS_GOV:999999-05-90005/2026", "90005/2026", "UASG 999999"), opp("COMPRAS_GOV:160123-05-90005/2026", "90005/2026", "UASG 160123")),
        )
        assertEquals("COMPRAS_GOV:160123-05-90005/2026", m!!.opportunityId)
        assertEquals(PortalTenderMatching.Strength.UASG_NUMBER_YEAR, m.strength)
    }

    @Test fun matchesByPncpControlFirst() {
        val m = PortalTenderMatching.match(
            mine("160123", "90005", 2026, pncp = "05055128000176-1-000108/2026"),
            listOf(opp("PNCP:05055128000176-1-000108/2026", "12/2026", "Órgão X", Portal.PNCP)),
        )
        assertEquals(PortalTenderMatching.Strength.PNCP, m!!.strength)
    }

    @Test fun numberYearOnlyWhenUniqueAndWithoutUasg() {
        val unique = PortalTenderMatching.match(mine("160123", "90005", 2026), listOf(opp("PNCP:00000000000000-1-000001/2026", "90005/2026", "Prefeitura", Portal.PNCP)))
        assertEquals(PortalTenderMatching.Strength.NUMBER_YEAR_UNIQUE, unique!!.strength)
        // Duas compras com o mesmo número/ano e sem UASG: ambíguo → não casa.
        assertNull(
            PortalTenderMatching.match(
                mine("160123", "90005", 2026),
                listOf(opp("PNCP:1-1-000001/2026", "90005/2026", "A", Portal.PNCP), opp("PNCP:2-1-000002/2026", "90005/2026", "B", Portal.PNCP)),
            ),
        )
        // UASG diferente: nunca casa.
        assertNull(PortalTenderMatching.match(mine("160123", "90005", 2026), listOf(opp("COMPRAS_GOV:999999-05-90005/2026", "90005/2026", "UASG 999999"))))
    }
}
