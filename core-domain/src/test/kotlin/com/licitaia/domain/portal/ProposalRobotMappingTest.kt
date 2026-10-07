package com.licitaia.domain.portal

import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalItem
import com.licitaia.domain.model.ProposalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProposalRobotMappingTest {

    private fun proposal(items: List<ProposalItem>, version: Int = 1, status: ProposalStatus = ProposalStatus.ENVIADA_SIMULADA) =
        Proposal(id = version.toLong(), tenderId = 7, companyId = 1, version = version, items = items, deliveryDays = 30, createdBy = "Ana", status = status)

    @Test
    fun `mapeia numero do edital, quantidade, valor e marca`() {
        val p = proposal(
            listOf(
                ProposalItem("Roteador corporativo", "UN", 4.0, 1_250.5, itemNumber = 3, brand = "Marca X", manufacturer = "Fab Y", model = "R-900"),
                ProposalItem("Switch 24 portas", "UN", 2.0, 3_000.0, itemNumber = 7),
            ),
        )
        val plan = ProposalRobotMapping.toPlanItems(p)
        assertEquals(listOf(3, 7), plan.map { it.itemNumber })
        assertEquals(4.0, plan[0].quantity, 0.0)
        assertEquals(1_250.5, plan[0].unitPrice, 0.0)
        assertEquals("Marca X", plan[0].brand)
        assertEquals("Fab Y", plan[0].manufacturer)
        assertEquals("R-900", plan[0].modelVersion)
        assertEquals("Roteador corporativo", plan[0].detailedDescription)
        assertNull(plan[0].floorUnitPrice)
    }

    @Test
    fun `itens sem numero recebem a posicao sem repetir numeros do edital`() {
        val p = proposal(
            listOf(
                ProposalItem("A", "un", 1.0, 1.0),
                ProposalItem("B", "un", 1.0, 1.0, itemNumber = 2),
                ProposalItem("C", "un", 1.0, 1.0),
                ProposalItem("D", "un", 1.0, 1.0, itemNumber = 2),
            ),
        )
        val numbers = ProposalRobotMapping.toPlanItems(p).map { it.itemNumber }
        assertEquals(numbers.size, numbers.toSet().size)
        assertEquals(1, numbers[0])
        assertEquals(2, numbers[1])
        assertEquals(3, numbers[2])
    }

    @Test
    fun `descricao longa vai inteira no detalhe e resumida no titulo`() {
        val long = "x".repeat(300)
        val plan = ProposalRobotMapping.toPlanItems(proposal(listOf(ProposalItem(long, "un", 1.0, 1.0, itemNumber = 1))))
        assertEquals(300, plan[0].detailedDescription.length)
        assertEquals(120, plan[0].description.length)
    }

    @Test
    fun `mantem piso existente valido para o mesmo item`() {
        val fresh = listOf(ProposalItemPlan(1, quantity = 1.0, unitPrice = 100.0), ProposalItemPlan(2, quantity = 1.0, unitPrice = 50.0))
        val old = listOf(ProposalItemPlan(1, quantity = 1.0, unitPrice = 120.0, floorUnitPrice = 90.0), ProposalItemPlan(2, quantity = 1.0, unitPrice = 80.0, floorUnitPrice = 70.0))
        val merged = ProposalRobotMapping.mergeFloors(fresh, old)
        assertEquals(90.0, merged[0].floorUnitPrice!!, 0.0)
        // piso acima do novo valor da proposta é descartado
        assertNull(merged[1].floorUnitPrice)
    }

    @Test
    fun `prefere a proposta liberada mais recente`() {
        val items = listOf(ProposalItem("A", "un", 1.0, 1.0))
        val list = listOf(
            proposal(items, 1, ProposalStatus.ENVIADA_SIMULADA),
            proposal(items, 2, ProposalStatus.APROVADA),
            proposal(items, 3, ProposalStatus.RASCUNHO),
        )
        assertEquals(1, ProposalRobotMapping.preferredProposal(list)!!.version)
        assertEquals(2, ProposalRobotMapping.preferredProposal(list.drop(1))!!.version)
        assertEquals(3, ProposalRobotMapping.preferredProposal(list.drop(2))!!.version)
        assertNull(ProposalRobotMapping.preferredProposal(emptyList()))
    }
}
