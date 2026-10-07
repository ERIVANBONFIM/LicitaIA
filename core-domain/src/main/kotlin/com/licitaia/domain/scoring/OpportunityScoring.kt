package com.licitaia.domain.scoring

import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.Segment
import java.text.Normalizer

/** Utilidades de texto para casamento de palavras-chave (sem acento, minúsculas). */
object TextMatch {
    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun normalize(text: String): String =
        diacritics.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().trim()

    /**
     * O termo aparece como PALAVRA(S) INTEIRA(S) no texto já normalizado (aceita plural simples: s, es, ão→ões,
     * l→is). "link" não casa dentro de outra palavra; "radio" não casa "radiologico"; "sip" não casa "sipac".
     */
    fun containsTerm(haystackNormalized: String, term: String): Boolean {
        val t = normalize(term)
        if (t.isEmpty()) return false
        return variants(t).any { containsWholeWord(haystackNormalized, it) }
    }

    /** Substring simples (sem fronteira de palavra) — usado só na busca digitada pelo usuário. */
    fun containsSubstring(haystackNormalized: String, term: String): Boolean {
        val t = normalize(term)
        return t.isNotEmpty() && haystackNormalized.contains(t)
    }

    private fun variants(t: String): List<String> = buildList {
        add(t)
        add(t + "s")
        add(t + "es")
        if (t.endsWith("ao")) add(t.dropLast(2) + "oes")
        if (t.endsWith("l")) add(t.dropLast(1) + "is")
    }

    private fun containsWholeWord(haystack: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = haystack.indexOf(word, from)
            if (i < 0) return false
            val beforeOk = i == 0 || !haystack[i - 1].isLetterOrDigit()
            val end = i + word.length
            val afterOk = end >= haystack.length || !haystack[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
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

/**
 * Vocabulário de afinidade semântica por segmento (termos FORTES: descrevem o objeto que o segmento fornece) e
 * termos de OUTROS domínios (alimentação, frota, saúde, limpeza, obras...), que derrubam a nota de empresas de
 * tecnologia/telecom. Termos terminados em `*` casam por prefixo de palavra ("telecomunica*" → telecomunicações).
 */
object RelevanceTerms {
    private val strong: Map<Segment, List<String>> = mapOf(
        Segment.TELECOM_ISP to listOf(
            "link dedicado", "links dedicados", "link de internet", "links de internet", "link de dados", "links de dados",
            "conexao dedicada", "conexoes dedicadas", "acesso a internet", "acesso dedicado", "internet dedicada",
            "internet banda larga", "banda larga", "servico de internet", "servicos de internet", "fornecimento de internet",
            "fibra optica", "fibras opticas", "provedor de acesso", "provedor de internet", "provedores de internet",
            "conectividade", "telecomunica*", "mpls", "sd-wan", "sdwan", "rede metro*", "ip dedicado", "ips dedicados",
            "telefonia", "circuito de dados", "circuitos de dados", "transmissao de dados", "comunicacao de dados",
            "comunicacao multimidia", "scm", "voip", "radio enlace", "radioenlace", "backbone", "lan to lan",
        ),
        Segment.TI to listOf(
            "tecnologia da informacao", "infraestrutura de ti", "datacenter", "data center", "firewall", "backup",
            "computacao em nuvem", "nuvem", "service desk", "suporte tecnico de informatica", "rede logica",
            "cabeamento estruturado", "seguranca da informacao", "storage", "informatica", "virtualizacao",
            "rede sem fio", "wi-fi", "wireless", "outsourcing de impressao",
        ),
        Segment.SOFTWARE to listOf(
            "software", "softwares", "sistema informatizado", "sistema de gestao", "sistema web", "licenca de uso",
            "licencas de uso", "licenciamento de software", "saas", "desenvolvimento de sistema*", "aplicativo",
            "plataforma digital", "plataforma web", "solucao tecnologica", "erp", "manutencao evolutiva",
        ),
        Segment.EQUIPAMENTOS to listOf(
            "notebook*", "computador*", "microcomputador*", "desktop*", "switch*", "roteador*", "nobreak*",
            "impressora*", "tablet*", "projetor*", "equipamentos de informatica", "equipamentos de rede",
            "servidor de rede", "servidores de rede",
        ),
    )

    /** Termos de domínios alheios a tecnologia/telecom. */
    val offDomain: List<String> = listOf(
        "coffee break", "coffe break", "alimentacao", "alimenticio*", "generos alimenticios", "lanche*", "refeic*",
        "buffet", "merenda", "hortifrut*", "agua mineral", "solenidade",
        "veiculo*", "frota", "combustive*", "pneu*", "pecas automotivas", "manutencao preventiva e corretiva de veiculos",
        "locacao de veiculos",
        "material de limpeza", "limpeza e conservacao", "produtos de limpeza", "higiene",
        "medicamento*", "fios cirurgicos", "material hospitalar", "material medico", "insumos hospitalares",
        "odontolog*", "exames laboratoriais",
        "uniforme*", "fardamento", "material de expediente", "material escolar", "material grafico",
        "pavimentacao", "reforma predial", "construcao civil", "passagens aereas", "dedetizacao", "jardinagem",
        "botijao*", "gas de cozinha", "mobiliario",
    )

    /** "via internet", "pela internet"... descrevem o MEIO (ex.: gestão de frota pela internet), não o objeto. */
    private val mediumPhrases = Regex(
        "\\b(via|pela|por meio da|atraves da|na|em plataforma na|em ambiente da) (rede mundial de computadores|internet|web)\\b",
    )

    fun strongTerms(segment: Segment): List<String> = strong[segment].orEmpty()

    fun hasStrongTerms(segment: Segment): Boolean = strongTerms(segment).isNotEmpty()

    /** Remove as menções a internet/web como meio de execução. */
    fun stripMedium(normalized: String): String = mediumPhrases.replace(normalized, " ")

    /** Casa termo (palavra inteira, plural simples) ou prefixo de palavra quando termina em `*`. */
    fun matches(normalized: String, term: String): Boolean {
        if (!term.endsWith("*")) return TextMatch.containsTerm(normalized, term)
        val prefix = TextMatch.normalize(term.dropLast(1))
        if (prefix.isEmpty()) return false
        var from = 0
        while (true) {
            val i = normalized.indexOf(prefix, from)
            if (i < 0) return false
            if (i == 0 || !normalized[i - 1].isLetterOrDigit()) return true
            from = i + 1
        }
    }
}

/** Sinais da heurística para uma oportunidade (úteis para escolher candidatos à nota por IA). */
data class RelevanceAssessment(
    val score: Int,
    /** Termos fortes do(s) segmento(s) alvo presentes no objeto. */
    val strongHits: Int,
    /** Palavras-chave do radar/segmento presentes no objeto (sem contar "via internet"). */
    val keywordHits: Int,
    /** Termos de outros domínios presentes no objeto. */
    val offDomainHits: Int,
) {
    /** Passou no filtro de palavras: vale pedir nota à IA. */
    val isCandidate: Boolean get() = strongHits > 0 || keywordHits > 0
}

/** Score de aderência 0..100 de uma oportunidade para a empresa (lógica pura e determinística). */
object OpportunityScorer {

    fun score(opportunity: Opportunity, company: Company, radars: List<Radar> = emptyList()): Int =
        assess(opportunity, company, radars).score

    /**
     * Heurística rígida (sem IA):
     * - palavra-chave isolada NÃO basta: a nota alta exige termos fortes do segmento (ex.: Telecom → "link dedicado",
     *   "acesso à internet", "fibra óptica") ou, em segmentos sem vocabulário próprio (Serviços/Personalizado),
     *   as palavras do radar;
     * - "via internet"/"pela internet" é meio, não objeto, e não conta;
     * - termos de outros domínios (coffee break, frota, medicamentos...) derrubam a nota;
     * - geografia só soma quando é próxima (mesma cidade/UF/região); "todo o Brasil" não infla a nota.
     */
    fun assess(opportunity: Opportunity, company: Company, radars: List<Radar> = emptyList()): RelevanceAssessment {
        val text = searchableText(opportunity)
        // Palavras-chave casam só no OBJETO: campos auxiliares (informação complementar, amparo, processo) trazem
        // textos padrão como "acesse o link do edital" que geravam falsos positivos.
        val objectText = RelevanceTerms.stripMedium(objectText(opportunity))
        val active = radars.filter { it.active }

        val segments = (active.map { it.segment } + company.segment).distinct()
        val strongTerms = segments.flatMap { RelevanceTerms.strongTerms(it) }.distinct()
        val userTerms = active.flatMap { it.keywords + listOfNotNull(it.preferredObject?.takeIf(String::isNotBlank)) }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val keywords = (userTerms + SegmentAffinity.defaultKeywords(company.segment)).distinct()

        val strongHits = strongTerms.count { RelevanceTerms.matches(objectText, it) }
        val keywordHits = keywords.count { TextMatch.containsTerm(objectText, it) }
        // Objeto preferencial e palavras-chave compostas ("link dedicado") são específicos: valem como termo forte.
        val specificUserHits = userTerms.count { term ->
            (term.trim().contains(' ') || active.any { it.preferredObject?.trim() == term }) &&
                TextMatch.containsTerm(objectText, term)
        }
        // Segmentos sem vocabulário próprio dependem das palavras do usuário.
        val keywordsAreStrong = segments.none { RelevanceTerms.hasStrongTerms(it) }
        val effectiveStrong = strongHits + specificUserHits + if (keywordsAreStrong) keywordHits else 0

        // O usuário pode querer explicitamente um termo "alheio" (ex.: radar de veículos): então ele não penaliza.
        val offDomainHits = if (segments.all { it == Segment.SERVICOS || it == Segment.PERSONALIZADO }) {
            0
        } else {
            RelevanceTerms.offDomain.count { term ->
                RelevanceTerms.matches(objectText, term) &&
                    userTerms.none { u -> TextMatch.normalize(u).let { n -> n.contains(term.trimEnd('*')) || term.trimEnd('*').contains(n) } }
            }
        }

        var total = when {
            effectiveStrong >= 2 -> 86.0
            effectiveStrong == 1 && keywordHits >= 1 -> 80.0
            effectiveStrong == 1 -> 72.0
            keywordHits >= 2 -> 42.0
            keywordHits == 1 -> 30.0
            else -> 12.0
        }
        // Classificação do conector só confirma (nunca cria) relevância.
        if (effectiveStrong > 0 && segments.any { it == opportunity.segment }) total += 4
        total += geoBonus(company, opportunity)
        if (offDomainHits > 0) {
            total -= if (effectiveStrong > 0) (15.0 * offDomainHits).coerceAtMost(30.0) else (30.0 * offDomainHits).coerceAtMost(60.0)
            if (effectiveStrong == 0) total = total.coerceAtMost(20.0)
        }

        val forbiddenHit = active.any { r -> r.forbiddenKeywords.any { TextMatch.containsTerm(text, it) } }
        if (forbiddenHit) total -= 40
        if (opportunity.requiresLocalSupport && !company.uf.equals(opportunity.uf, true)) total -= 10
        val inValueRange = active.any { r ->
            (r.minValue == null || opportunity.estimatedValue >= r.minValue) &&
                (r.maxValue == null || opportunity.estimatedValue <= r.maxValue) &&
                (r.minValue != null || r.maxValue != null)
        }
        if (inValueRange && effectiveStrong > 0) total += 3
        return RelevanceAssessment(total.toInt().coerceIn(0, 100), strongHits + specificUserHits, keywordHits, offDomainHits)
    }

    /** Só proximidade real soma; local distante ou "todo o Brasil" não infla nem derruba a nota. */
    private fun geoBonus(company: Company, opportunity: Opportunity): Double = when {
        company.uf.isBlank() || opportunity.uf.isBlank() -> 0.0
        company.uf.equals(opportunity.uf, true) &&
            TextMatch.normalize(company.city) == TextMatch.normalize(opportunity.city) -> 6.0
        company.uf.equals(opportunity.uf, true) -> 4.0
        BrazilRegions.sameRegion(company.uf, opportunity.uf) -> 1.0
        else -> 0.0
    }

    /** Texto do objeto (normalizado): base do casamento de palavras-chave de radar/score. */
    fun objectText(opportunity: Opportunity): String = TextMatch.normalize(opportunity.objectDescription)

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
        // Dispensa sem disputa (contratação direta): só com o radar configurado para mostrá-las.
        if (opportunity.noDispute && !radar.showNoDispute) return false
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
        // "Prazo não informado" não é comparado com a janela de datas do radar (não se inventa prazo).
        if (opportunity.hasProposalDeadline) {
            if (radar.startDate != null && opportunity.proposalDeadline < radar.startDate) return false
            if (radar.endDate != null && opportunity.proposalDeadline > radar.endDate) return false
        }
        if (radar.requireLocalSupport && companyUf != null && opportunity.requiresLocalSupport &&
            !companyUf.equals(opportunity.uf, true)
        ) {
            return false
        }

        // "via internet"/"pela internet" é o meio de execução, não o objeto: não casa a palavra "internet".
        val objectText = RelevanceTerms.stripMedium(OpportunityScorer.objectText(opportunity))
        val keywordHit = radar.keywords.any { TextMatch.containsTerm(objectText, it) } ||
            (radar.preferredObject?.let { TextMatch.containsTerm(objectText, it) } ?: false)
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
        if (opportunity.noDispute && !filter.showNoDispute) return false
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
            if (!terms.all { TextMatch.containsSubstring(text, it) }) return false
        }
        return true
    }
}

/**
 * Contexto do radar para a nota por IA: [hint] (texto curto enviado ao modelo, sem dados pessoais) e [signature]
 * (hash de segmento + palavras + objeto preferencial + CNAE), chave do cache de notas — mudou o radar, muda a nota.
 */
data class RelevanceContext(val signature: String, val hint: String) {
    companion object {
        fun of(company: Company, radars: List<Radar>): RelevanceContext {
            val active = radars.filter { it.active }.ifEmpty { radars }
            val segments = (active.map { it.segment } + company.segment).distinct().sortedBy { it.ordinal }
            val keywords = active.flatMap { it.keywords }.map { TextMatch.normalize(it) }.filter { it.isNotEmpty() }.distinct().sorted()
            val preferred = active.mapNotNull { it.preferredObject?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()
            val cnaes = active.mapNotNull { it.cnae?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()
            val canonical = buildString {
                append("seg=").append(segments.joinToString(",") { it.name })
                append("|kw=").append(keywords.joinToString(","))
                append("|obj=").append(preferred.joinToString(",") { TextMatch.normalize(it) })
                append("|cnae=").append(cnaes.joinToString(","))
            }
            val hint = buildString {
                append("Segmento de atuação: ").append(segments.joinToString(", ") { it.label })
                if (keywords.isNotEmpty()) append(". Palavras do radar: ").append(keywords.joinToString(", "))
                if (preferred.isNotEmpty()) append(". Objeto preferencial: ").append(preferred.joinToString("; "))
                if (cnaes.isNotEmpty()) append(". CNAE: ").append(cnaes.joinToString(", "))
                val forbidden = active.flatMap { it.forbiddenKeywords }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (forbidden.isNotEmpty()) append(". Não fornece: ").append(forbidden.joinToString(", "))
            }
            return RelevanceContext(sha256(canonical).take(32), hint)
        }

        private fun sha256(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Ordem de exibição: score desc; depois prazo mais próximo; "prazo não informado" por último. */
object ScoredOrder {
    val ORDER: Comparator<ScoredOpportunity> = compareByDescending<ScoredOpportunity> { it.score }
        .thenBy { !it.opportunity.hasProposalDeadline }
        .thenBy { it.opportunity.proposalDeadline }
}

/** Seleção e aplicação das notas por IA (lógica pura). */
object AiScoreMerge {
    /** Candidatos priorizados por prazo mais próximo ("prazo não informado" por último), até [limit]. */
    fun prioritize(candidates: List<ScoredOpportunity>, limit: Int): List<ScoredOpportunity> =
        candidates.sortedWith(
            compareBy<ScoredOpportunity> { !it.opportunity.hasProposalDeadline }.thenBy { it.opportunity.proposalDeadline },
        ).take(limit.coerceAtLeast(0))

    /**
     * Junta as notas por IA [rated] à lista [current] (heurística + notas anteriores): a nota por IA substitui a
     * heurística; itens que a IA aprovou entram mesmo que a heurística os tenha deixado de fora, e itens reprovados
     * (abaixo de [minScore]) saem. Mantém o estado de interesse da lista atual.
     */
    fun merge(current: List<ScoredOpportunity>, rated: List<ScoredOpportunity>, minScore: Int): List<ScoredOpportunity> {
        if (rated.isEmpty()) return current
        val byId = LinkedHashMap<String, ScoredOpportunity>()
        current.forEach { byId[it.opportunity.id] = it }
        rated.forEach { r ->
            val existing = byId[r.opportunity.id]
            byId[r.opportunity.id] = if (existing != null) r.copy(interested = existing.interested || r.interested) else r
        }
        return byId.values.filter { it.score >= minScore }.sortedWith(ScoredOrder.ORDER)
    }
}
