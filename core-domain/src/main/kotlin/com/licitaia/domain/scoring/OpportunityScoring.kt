package com.licitaia.domain.scoring

import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.Segment
import java.text.Normalizer

/** Utilidades de texto para casamento de palavras-chave (sem acento, minúsculas). */
object TextMatch {
    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun normalize(text: String): String =
        diacritics.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().trim()

    fun containsTerm(haystackNormalized: String, term: String): Boolean {
        val t = normalize(term)
        return t.isNotEmpty() && haystackNormalized.contains(t)
    }
}

object BrazilRegions {
    private val regions: Map<String, Set<String>> = mapOf(
        "Norte" to setOf("AC", "AP", "AM", "PA", "RO", "RR", "TO"),
        "Nordeste" to setOf("AL", "BA", "CE", "MA", "PB", "PE", "PI", "RN", "SE"),
        "Centro-Oeste" to setOf("DF", "GO", "MT", "MS"),
        "Sudeste" to setOf("ES", "MG", "RJ", "SP"),
        "Sul" to setOf("PR", "RS", "SC"),
    )

    fun regionOf(uf: String): String? {
        val key = uf.trim().uppercase()
        return regions.entries.firstOrNull { key in it.value }?.key
    }

    fun sameRegion(ufA: String, ufB: String): Boolean {
        val a = regionOf(ufA) ?: return false
        return a == regionOf(ufB)
    }

    /** 0..100 — proximidade entre a sede da empresa e o local de execução. */
    fun geographicFit(companyUf: String, companyCity: String, uf: String, city: String): Int = when {
        companyUf.isBlank() || uf.isBlank() -> 60
        companyUf.equals(uf, true) && TextMatch.normalize(companyCity) == TextMatch.normalize(city) -> 100
        companyUf.equals(uf, true) -> 85
        sameRegion(companyUf, uf) -> 58
        else -> 32
    }
}

object SegmentAffinity {
    /** 0..100 — quão próximo o segmento do objeto está do segmento de atuação da empresa. */
    fun of(company: Segment, target: Segment): Int = when {
        company == target -> 100
        company == Segment.PERSONALIZADO || target == Segment.PERSONALIZADO -> 55
        setOf(company, target) == setOf(Segment.TI, Segment.SOFTWARE) -> 78
        setOf(company, target) == setOf(Segment.TI, Segment.EQUIPAMENTOS) -> 70
        setOf(company, target) == setOf(Segment.TELECOM_ISP, Segment.TI) -> 62
        setOf(company, target) == setOf(Segment.TELECOM_ISP, Segment.EQUIPAMENTOS) -> 50
        setOf(company, target) == setOf(Segment.SOFTWARE, Segment.SERVICOS) -> 52
        setOf(company, target) == setOf(Segment.TI, Segment.SERVICOS) -> 55
        else -> 30
    }

    /** Vocabulário típico de cada segmento, usado quando não há radar configurado. */
    fun defaultKeywords(segment: Segment): List<String> = when (segment) {
        Segment.TELECOM_ISP -> listOf(
            "internet", "link dedicado", "fibra", "banda larga", "conectividade", "telecom",
            "wi-fi", "ponto de acesso", "ip dedicado", "scm", "rede metropolitana",
        )
        Segment.TI -> listOf(
            "tecnologia da informacao", "infraestrutura", "servidor", "datacenter", "rede",
            "suporte tecnico", "firewall", "backup", "nuvem", "outsourcing",
        )
        Segment.SOFTWARE -> listOf(
            "software", "sistema", "licenca", "saas", "desenvolvimento", "aplicativo",
            "plataforma", "gestao", "implantacao", "manutencao evolutiva",
        )
        Segment.EQUIPAMENTOS -> listOf(
            "equipamento", "notebook", "computador", "switch", "nobreak", "impressora",
            "aquisicao", "monitor", "roteador",
        )
        Segment.SERVICOS -> listOf("servico", "manutencao", "instalacao", "locacao", "mao de obra", "terceirizacao")
        Segment.PERSONALIZADO -> emptyList()
    }
}

/** Score de aderência 0..100 de uma oportunidade para a empresa (lógica pura e determinística). */
object OpportunityScorer {

    fun score(opportunity: Opportunity, company: Company, radars: List<Radar> = emptyList()): Int {
        val text = searchableText(opportunity)
        val active = radars.filter { it.active }

        val segmentTargets = (active.map { it.segment } + company.segment).distinct()
        val segmentAffinity = segmentTargets.maxOf { SegmentAffinity.of(it, opportunity.segment) }

        val geo = BrazilRegions.geographicFit(company.uf, company.city, opportunity.uf, opportunity.city)

        val radarKeywords = active.flatMap { it.keywords + listOfNotNull(it.preferredObject) }
        val keywords = (radarKeywords + SegmentAffinity.defaultKeywords(company.segment)).distinct()
        val hits = keywords.count { TextMatch.containsTerm(text, it) }
        val keywordScore = when {
            keywords.isEmpty() -> 50
            hits == 0 -> 15
            hits == 1 -> 60
            hits == 2 -> 80
            else -> 100
        }

        var total = segmentAffinity * 0.42 + keywordScore * 0.28 + geo * 0.22 + 8.0

        val forbiddenHit = active.any { r -> r.forbiddenKeywords.any { TextMatch.containsTerm(text, it) } }
        if (forbiddenHit) total -= 35
        if (opportunity.requiresLocalSupport && !company.uf.equals(opportunity.uf, true)) total -= 12
        val inValueRange = active.any { r ->
            (r.minValue == null || opportunity.estimatedValue >= r.minValue) &&
                (r.maxValue == null || opportunity.estimatedValue <= r.maxValue) &&
                (r.minValue != null || r.maxValue != null)
        }
        if (inValueRange) total += 4
        return total.toInt().coerceIn(0, 100)
    }

    fun searchableText(opportunity: Opportunity): String = TextMatch.normalize(
        buildString {
            append(opportunity.objectDescription).append(' ')
            append(opportunity.agency).append(' ')
            append(opportunity.number).append(' ')
            append(opportunity.city).append(' ')
            append(opportunity.keywords.joinToString(" "))
        },
    )
}

/** Regras de casamento radar × oportunidade (o score mínimo é aplicado por quem chama). */
object RadarMatcher {

    fun matches(radar: Radar, opportunity: Opportunity, companyUf: String? = null): Boolean {
        val text = OpportunityScorer.searchableText(opportunity)
        if (radar.forbiddenKeywords.any { TextMatch.containsTerm(text, it) }) return false
        if (!radar.allPortals && radar.portals.isNotEmpty() && opportunity.portal !in radar.portals) return false
        if (radar.ufs.isNotEmpty() && radar.ufs.none { it.equals(opportunity.uf, true) }) return false
        radar.region?.takeIf { it.isNotBlank() }?.let { region ->
            val oppRegion = BrazilRegions.regionOf(opportunity.uf)
            if (oppRegion == null || TextMatch.normalize(oppRegion) != TextMatch.normalize(region)) return false
        }
        radar.agency?.takeIf { it.isNotBlank() }?.let {
            if (!TextMatch.containsTerm(TextMatch.normalize(opportunity.agency), it)) return false
        }
        if (radar.modality != null && radar.modality != opportunity.modality) return false
        if (radar.minValue != null && opportunity.estimatedValue < radar.minValue) return false
        if (radar.maxValue != null && opportunity.estimatedValue > radar.maxValue) return false
        if (radar.startDate != null && opportunity.proposalDeadline < radar.startDate) return false
        if (radar.endDate != null && opportunity.proposalDeadline > radar.endDate) return false
        if (radar.requireLocalSupport && companyUf != null && opportunity.requiresLocalSupport &&
            !companyUf.equals(opportunity.uf, true)
        ) {
            return false
        }

        val keywordHit = radar.keywords.any { TextMatch.containsTerm(text, it) } ||
            (radar.preferredObject?.let { TextMatch.containsTerm(text, it) } ?: false)
        val segmentHit = radar.segment == opportunity.segment
        return when {
            radar.keywords.isEmpty() && radar.preferredObject.isNullOrBlank() ->
                segmentHit || radar.segment == Segment.PERSONALIZADO
            radar.segment == Segment.PERSONALIZADO -> keywordHit
            else -> keywordHit || segmentHit
        }
    }
}

object OpportunityFilterMatcher {
    fun matches(filter: OpportunityFilter, opportunity: Opportunity): Boolean {
        if (filter.portals.isNotEmpty() && opportunity.portal !in filter.portals) return false
        if (filter.ufs.isNotEmpty() && filter.ufs.none { it.equals(opportunity.uf, true) }) return false
        if (filter.segment != null && filter.segment != opportunity.segment) return false
        if (filter.modality != null && filter.modality != opportunity.modality) return false
        if (filter.minValue != null && opportunity.estimatedValue < filter.minValue) return false
        if (filter.maxValue != null && opportunity.estimatedValue > filter.maxValue) return false
        val query = filter.query.trim()
        if (query.isNotEmpty()) {
            val text = OpportunityScorer.searchableText(opportunity)
            val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (!terms.all { TextMatch.containsTerm(text, it) }) return false
        }
        return true
    }
}
