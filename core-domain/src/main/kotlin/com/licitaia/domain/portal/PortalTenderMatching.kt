package com.licitaia.domain.portal

import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Tender

/**
 * Casamento das "minhas licitações" do Comprasnet com as oportunidades/licitações do app. Funções puras.
 *
 * Regras (da mais forte para a mais fraca):
 * 1. número de controle PNCP igual (sufixo do id `PNCP:`/`COMPRAS_GOV:`);
 * 2. UASG + número + ano iguais (UASG do id legado `uasg-mod-num/ano` ou do texto "UASG 123456" do órgão);
 * 3. número + ano iguais quando a oportunidade NÃO informa UASG e só UMA candidata casa (sem ambiguidade).
 */
object PortalTenderMatching {

    private val PNCP_CONTROL = Regex("""(\d{14})-1-(\d{1,6})/(\d{4})""")
    private val LEGACY_ID = Regex("""(\d{6})-(\d{2})-(\d{5})/(\d{4})""")
    private val UASG_TEXT = Regex("""(?i)uasg\D{0,3}(\d{5,6})""")
    private val NUMBER_YEAR = Regex("""(\d{1,6})\s*/\s*(\d{4})""")

    /** Chave normalizada `<uasg 6>-<número 5>-<ano>`; null se faltar algum componente. */
    fun tenderKey(uasg: String?, number: String?, year: Int?): String? {
        val u = uasg?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() && it.length <= 6 } ?: return null
        val n = number?.filter(Char::isDigit)?.trimStart('0')?.ifEmpty { "0" }?.takeIf { it.length <= 6 } ?: return null
        val y = year?.takeIf { it in 1990..2100 } ?: return null
        return "${u.padStart(6, '0')}-${n.padStart(5, '0')}-$y"
    }

    /** "90005/2026" → (90005, 2026). */
    fun parseNumberYear(text: String?): Pair<String, Int>? {
        val m = NUMBER_YEAR.find(text.orEmpty()) ?: return null
        val year = m.groupValues[2].toIntOrNull()?.takeIf { it in 1990..2100 } ?: return null
        return m.groupValues[1].trimStart('0').ifEmpty { "0" } to year
    }

    fun normalizePncpControl(text: String?): String? =
        PNCP_CONTROL.find(text.orEmpty())?.let { "${it.groupValues[1]}-1-${it.groupValues[2].padStart(6, '0')}/${it.groupValues[3]}" }

    /** Identificação extraída de uma oportunidade do app. */
    data class OpportunityRef(val uasg: String?, val number: String?, val year: Int?, val pncpControl: String?)

    fun refOf(opportunity: Opportunity): OpportunityRef = refOf(opportunity.id, opportunity.number, opportunity.agency)

    fun refOf(id: String, number: String, agency: String): OpportunityRef {
        val raw = id.substringAfter(':')
        val pncp = normalizePncpControl(raw)
        LEGACY_ID.find(raw)?.let { m ->
            return OpportunityRef(m.groupValues[1], m.groupValues[3].trimStart('0').ifEmpty { "0" }, m.groupValues[4].toIntOrNull(), pncp)
        }
        val ny = parseNumberYear(number)
        val uasg = UASG_TEXT.find(agency)?.groupValues?.get(1)?.padStart(6, '0') ?: UASG_TEXT.find(number)?.groupValues?.get(1)?.padStart(6, '0')
        return OpportunityRef(uasg, ny?.first, ny?.second, pncp)
    }

    enum class Strength { PNCP, UASG_NUMBER_YEAR, NUMBER_YEAR_UNIQUE }

    data class Match(val opportunityId: String, val strength: Strength)

    /** Melhor oportunidade para [mine] entre [candidates] (só do Compras.gov.br/PNCP); null = nenhuma ou ambígua. */
    fun match(mine: PortalMyTender, candidates: List<Opportunity>): Match? =
        matchRefs(mine, candidates.filter { it.portal == Portal.COMPRAS_GOV || it.portal == Portal.PNCP }.map { it.id to refOf(it) })

    fun matchRefs(mine: PortalMyTender, candidates: List<Pair<String, OpportunityRef>>): Match? {
        val myUasg = mine.uasg.filter(Char::isDigit).padStart(6, '0')
        val myNumber = mine.number.filter(Char::isDigit).trimStart('0').ifEmpty { "0" }
        val myPncp = normalizePncpControl(mine.pncpControl)
        if (myPncp != null) {
            candidates.firstOrNull { it.second.pncpControl == myPncp }?.let { return Match(it.first, Strength.PNCP) }
        }
        val sameNumber = candidates.filter { it.second.number == myNumber && it.second.year == mine.year }
        sameNumber.firstOrNull { it.second.uasg == myUasg }?.let { return Match(it.first, Strength.UASG_NUMBER_YEAR) }
        // Sem UASG na oportunidade: só aceita se for a ÚNICA com o mesmo número/ano (evita casar compras de órgãos diferentes).
        val withoutUasg = sameNumber.filter { it.second.uasg == null }
        if (sameNumber.size == 1 && withoutUasg.size == 1) return Match(withoutUasg.single().first, Strength.NUMBER_YEAR_UNIQUE)
        return null
    }

    /** Licitação ("Tenho interesse") do app correspondente: pela oportunidade casada ou pelas mesmas regras. */
    fun matchTender(mine: PortalMyTender, opportunityId: String?, tenders: List<Tender>): Tender? {
        opportunityId?.let { id -> tenders.firstOrNull { it.opportunityId == id }?.let { return it } }
        val refs = tenders.filter { it.portal == Portal.COMPRAS_GOV || it.portal == Portal.PNCP }
            .map { it.opportunityId to refOf(it.opportunityId, it.number, it.agency) }
        val m = matchRefs(mine, refs) ?: return null
        return tenders.firstOrNull { it.opportunityId == m.opportunityId }
    }
}
