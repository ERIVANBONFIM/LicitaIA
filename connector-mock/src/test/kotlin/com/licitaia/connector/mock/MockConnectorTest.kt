package com.licitaia.connector.mock

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.PortalLiveEvent
import com.licitaia.domain.bidding.DemoSessionSpecs
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Segment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockConnectorTest {

    private val registry = MockConnectorRegistry()

    @Test
    fun `catalogo cobre os 4 portais e todos os conectores sao mock`() = runBlocking {
        registry.all().forEach { c ->
            assertTrue(c.capabilities.isMock)
            assertFalse(c.capabilities.supportsOfficialApi)
            assertTrue(c.capabilities.limitations.any { it.contains("API oficial") })
            assertTrue(c.listOpportunities(OpportunityFilter()).size >= 10)
        }
        val total = registry.all().sumOf { it.listOpportunities(OpportunityFilter()).size }
        assertTrue("catálogo com $total oportunidades", total in 40..60)
    }

    @Test
    fun `filtro realmente filtra`() = runBlocking {
        val c = registry.get(Portal.COMPRAS_GOV)
        val mg = c.listOpportunities(OpportunityFilter(ufs = setOf("MG")))
        assertTrue(mg.isNotEmpty() && mg.all { it.uf == "MG" })
        val telecom = c.listOpportunities(OpportunityFilter(segment = Segment.TELECOM_ISP, maxValue = 500_000.0))
        assertTrue(telecom.isNotEmpty() && telecom.all { it.segment == Segment.TELECOM_ISP && it.estimatedValue <= 500_000.0 })
        val query = c.listOpportunities(OpportunityFilter(query = "link dedicado"))
        assertTrue(query.isNotEmpty())
        assertTrue(c.listOpportunities(OpportunityFilter(portals = setOf(Portal.BLL))).isEmpty())
        val details = c.getTenderDetails(query.first().id)
        assertNotNull(details?.editalText)
        assertTrue(details!!.items.isNotEmpty())
    }

    @Test
    fun `sessoes sao isoladas e submitBid valida`() = runBlocking {
        val connector = registry.get(Portal.LICITANET) as MockPortalConnector
        val spec = DemoSessionSpecs.initial(1).first { it.portal == Portal.LICITANET }
        val a = connector.openLiveSession("A", spec)
        val b = connector.openLiveSession("B", spec)

        // item errado é rejeitado
        assertTrue(connector.submitBid("A", "Item errado", 200_000.0, null) is BidSubmission.Rejected)
        // primeiro lance aceito
        val first = connector.submitBid("A", spec.itemLabel, 200_000.0, null)
        assertTrue(first is BidSubmission.Accepted)
        // lance não inferior ao melhor é rejeitado
        assertTrue(connector.submitBid("A", spec.itemLabel, 200_000.0, null) is BidSubmission.Rejected)
        assertTrue(connector.submitBid("A", spec.itemLabel, 199_999.99, null) is BidSubmission.Accepted)

        // CAPTCHA na sessão A não afeta a sessão B
        assertTrue(connector.triggerCaptcha("A"))
        assertEquals(BidSubmission.CaptchaRequired, connector.submitBid("A", spec.itemLabel, 199_000.0, null))
        assertTrue(connector.submitBid("B", spec.itemLabel, 210_000.0, null) is BidSubmission.Accepted)
        assertTrue(connector.readCurrentBidState("A")!!.captchaPending)
        assertFalse(connector.readCurrentBidState("B")!!.captchaPending)
        assertEquals(199_999.99, connector.readCurrentBidState("A")!!.bestBid!!, 0.0001)
        assertEquals(210_000.0, connector.readCurrentBidState("B")!!.bestBid!!, 0.0001)

        // o evento de CAPTCHA está na fila da sessão A
        val event = a.events.first()
        assertEquals(PortalLiveEvent.CaptchaRequired, event)

        connector.confirmCaptchaResolved("A")
        assertTrue(connector.submitBid("A", spec.itemLabel, 199_000.0, null) is BidSubmission.Accepted)

        a.close(); b.close()
        assertTrue(connector.submitBid("A", spec.itemLabel, 100_000.0, null) is BidSubmission.Rejected)
    }
}
