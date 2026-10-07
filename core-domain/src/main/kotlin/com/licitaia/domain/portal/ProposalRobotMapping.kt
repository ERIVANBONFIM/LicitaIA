package com.licitaia.domain.portal

import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalStatus

/**
 * Proposta do app → itens do plano do robô (cadastro item a item no Compras.gov.br). Puro e testado.
 * O usuário ainda revisa o plano e confirma "Soltar robô".
 */
object ProposalRobotMapping {

    /** Descrição curta exibida no cartão do plano (a detalhada leva o texto inteiro). */
    private const val SHORT_DESCRIPTION = 120

    /**
     * Itens da proposta como [ProposalItemPlan]: nº do item do edital (ou a posição, sem repetir números),
     * quantidade, valor unitário, marca/fabricante/modelo e descrição.
     */
    fun toPlanItems(proposal: Proposal): List<ProposalItemPlan> {
        val used = proposal.items.mapNotNull { it.itemNumber?.takeIf { n -> n > 0 } }.toMutableSet()
        val seen = mutableSetOf<Int>()
        var next = 1
        return proposal.items.mapIndexed { index, item ->
            val own = item.itemNumber?.takeIf { it > 0 && seen.add(it) }
            val number = own ?: run {
                // sem número do edital (ou repetido): primeira posição livre a partir da ordem do item
                next = maxOf(next, index + 1)
                while (next in used || next in seen) next++
                next.also { seen += it; used += it }
            }
            val description = item.description.trim()
            ProposalItemPlan(
                itemNumber = number,
                description = if (description.length > SHORT_DESCRIPTION) description.take(SHORT_DESCRIPTION - 1).trimEnd() + "…" else description,
                quantity = item.quantity,
                unitPrice = item.unitPrice,
                brand = item.brand.trim(),
                manufacturer = item.manufacturer.trim(),
                modelVersion = item.model.trim(),
                detailedDescription = description,
            )
        }
    }

    /** Mantém o piso de lance já definido no plano para o mesmo nº de item (o piso não vem da proposta). */
    fun mergeFloors(fresh: List<ProposalItemPlan>, existing: List<ProposalItemPlan>): List<ProposalItemPlan> {
        val floors = existing.associate { it.itemNumber to it.floorUnitPrice }
        return fresh.map { item ->
            val floor = floors[item.itemNumber]?.takeIf { it <= item.unitPrice + 1e-9 }
            if (floor != null) item.copy(floorUnitPrice = floor) else item
        }
    }

    /**
     * Proposta de referência para o robô: a liberada mais recente; senão a aprovada; senão a versão mais nova.
     * [proposals] em qualquer ordem.
     */
    fun preferredProposal(proposals: List<Proposal>): Proposal? {
        val byVersion = proposals.sortedByDescending { it.version }
        return byVersion.firstOrNull { it.status == ProposalStatus.ENVIADA_SIMULADA }
            ?: byVersion.firstOrNull { it.status == ProposalStatus.APROVADA }
            ?: byVersion.firstOrNull()
    }
}
