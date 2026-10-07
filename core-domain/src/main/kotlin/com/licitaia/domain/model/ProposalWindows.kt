package com.licitaia.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Situação do recebimento de propostas em um instante. */
enum class ProposalWindow {
    /** Recebendo propostas agora (abertura ≤ agora < encerramento). */
    OPEN,

    /** Abertura das propostas no futuro. */
    UPCOMING,

    /** Encerramento das propostas (ou sessão) já passou: encerrada/disputada. */
    CLOSED,

    /** Sem data de encerramento confiável (oculta por padrão). */
    NO_DEADLINE,
}

/** Seções fixas das listas de oportunidades (Busca e Resultados do Radar). */
enum class OpportunitySection(val title: String) {
    TODAY("Hoje"),
    NEXT_DAYS("Próximos dias"),
    UPCOMING("Vão abrir"),
}

data class SectionedOpportunities(val section: OpportunitySection, val items: List<ScoredOpportunity>)

/**
 * Regras de exibição por prazo (lógica pura, testável; fuso America/Sao_Paulo):
 * só ABERTAS e as que VÃO ABRIR; encerradas/disputadas nunca; sem prazo confiável ocultas por padrão.
 * Agrupamento: "Hoje" (encerramento ou sessão hoje) → "Próximos dias" → "Vão abrir"; dentro de cada seção, maior
 * nota primeiro e, empatando, o horário (encerramento/sessão; abertura em "Vão abrir").
 */
object ProposalWindows {
    val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE)

    fun classify(o: Opportunity, now: Long): ProposalWindow = when {
        !o.hasProposalDeadline -> ProposalWindow.NO_DEADLINE
        o.proposalDeadline <= now -> ProposalWindow.CLOSED
        // Sessão de disputa já realizada (fontes que publicam a sessão separada do encerramento).
        o.sessionAt > 0L && o.sessionAt < now && o.sessionAt != o.proposalDeadline -> ProposalWindow.CLOSED
        (o.proposalOpening ?: 0L) > now -> ProposalWindow.UPCOMING
        else -> ProposalWindow.OPEN
    }

    /** Resultado do corte: visíveis + quantas sem prazo ficaram ocultas (encerradas não contam). */
    data class Visible(val items: List<Opportunity>, val hiddenNoDeadline: Int)

    /**
     * Mantém abertas e as que vão abrir. Sem prazo: ocultas, exceto dispensas sem disputa quando o usuário pediu
     * para vê-las ([showNoDispute]); estas não têm prazo por definição.
     */
    fun visible(opportunities: List<Opportunity>, now: Long, showNoDispute: Boolean = false): Visible {
        var hidden = 0
        val kept = opportunities.filter { o ->
            when (classify(o, now)) {
                ProposalWindow.OPEN, ProposalWindow.UPCOMING -> true
                ProposalWindow.CLOSED -> false
                ProposalWindow.NO_DEADLINE -> (showNoDispute && o.noDispute).also { if (!it) hidden++ }
            }
        }
        return Visible(kept, hidden)
    }

    fun isToday(millis: Long, now: Long): Boolean =
        millis > 0L && day(millis) == day(now)

    private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate()

    /** Encerramento ou sessão hoje (o horário de referência da seção "Hoje"); null se nenhum for hoje. */
    fun todayTime(o: Opportunity, now: Long): Long? = listOf(o.proposalDeadline, o.sessionAt)
        .filter { it >= now && isToday(it, now) }
        .minOrNull()

    fun sectionOf(o: Opportunity, now: Long): OpportunitySection? = when (classify(o, now)) {
        ProposalWindow.CLOSED -> null
        ProposalWindow.NO_DEADLINE -> if (o.noDispute) OpportunitySection.NEXT_DAYS else null
        else -> when {
            todayTime(o, now) != null -> OpportunitySection.TODAY
            classify(o, now) == ProposalWindow.UPCOMING -> OpportunitySection.UPCOMING
            else -> OpportunitySection.NEXT_DAYS
        }
    }

    /**
     * Ordem dentro de uma seção: maior nota primeiro ([ScoredOpportunity.score] = nota por IA quando houver, senão a
     * heurística) e, empatando, o horário de referência da seção ([timeOf]: encerramento/sessão ou abertura).
     */
    fun sectionOrder(timeOf: (ScoredOpportunity) -> Long): Comparator<ScoredOpportunity> =
        compareByDescending<ScoredOpportunity> { it.score }.thenBy(timeOf).thenBy { it.opportunity.id }

    /**
     * Seções não vazias, na ordem fixa Hoje → Próximos dias → Vão abrir; encerradas pelo relógio somem.
     * Dentro de cada seção: nota decrescente e depois o horário ([sectionOrder]).
     */
    fun group(items: List<ScoredOpportunity>, now: Long): List<SectionedOpportunities> {
        val by = items.groupBy { sectionOf(it.opportunity, now) }
        val today = by[OpportunitySection.TODAY].orEmpty()
            .sortedWith(sectionOrder { todayTime(it.opportunity, now) ?: Long.MAX_VALUE })
        val next = by[OpportunitySection.NEXT_DAYS].orEmpty()
            .sortedWith(sectionOrder { if (it.opportunity.hasProposalDeadline) it.opportunity.proposalDeadline else Long.MAX_VALUE })
        val upcoming = by[OpportunitySection.UPCOMING].orEmpty()
            .sortedWith(sectionOrder { it.opportunity.proposalOpening ?: Long.MAX_VALUE })
        return listOf(
            SectionedOpportunities(OpportunitySection.TODAY, today),
            SectionedOpportunities(OpportunitySection.NEXT_DAYS, next),
            SectionedOpportunities(OpportunitySection.UPCOMING, upcoming),
        ).filter { it.items.isNotEmpty() }
    }

    /** "Abre em dd/MM HH:mm" / "Aberta · encerra em dd/MM HH:mm"; null quando não se aplica. */
    fun statusLabel(o: Opportunity, now: Long): String? = when (classify(o, now)) {
        ProposalWindow.UPCOMING -> "Abre em ${SHORT.format(Instant.ofEpochMilli(o.proposalOpening!!))}"
        ProposalWindow.OPEN -> "Aberta · encerra em ${SHORT.format(Instant.ofEpochMilli(o.proposalDeadline))}"
        else -> null
    }

    /** Destaque "HOJE": encerramento ou sessão no dia atual. */
    fun endsToday(o: Opportunity, now: Long): Boolean = todayTime(o, now) != null
}

/**
 * Filtro de aderência da Busca: itens com nota abaixo de [THRESHOLD] ficam ocultos por padrão (contados) e aparecem
 * com "Mostrar baixa aderência". Os radares não usam este filtro: aplicam o próprio score mínimo.
 */
object LowAdherence {
    const val THRESHOLD = 30

    /** [visible] na ordem recebida; [hidden] = itens de baixa aderência ocultos (vazio quando [show]). */
    data class Split(val visible: List<ScoredOpportunity>, val hidden: List<ScoredOpportunity>)

    fun isLow(item: ScoredOpportunity, threshold: Int = THRESHOLD): Boolean = item.score < threshold

    fun split(items: List<ScoredOpportunity>, show: Boolean, threshold: Int = THRESHOLD): Split {
        if (show || threshold <= 0) return Split(items, emptyList())
        val (low, ok) = items.partition { isLow(it, threshold) }
        return Split(ok, low)
    }

    /** Lista pronta para exibir: visíveis (achatados na ordem das seções), seções e ocultos por baixa aderência. */
    data class Grouped(
        val items: List<ScoredOpportunity>,
        val sections: List<SectionedOpportunities>,
        val hiddenLow: List<ScoredOpportunity>,
    )

    /**
     * Dedup por id → corte de baixa aderência ([threshold] null = sem corte, como nos radares) → seções
     * Hoje / Próximos dias / Vão abrir ordenadas por nota e horário. Os ocultos também passam pelo relógio
     * (encerrados somem da contagem).
     */
    fun group(items: List<ScoredOpportunity>, now: Long, threshold: Int?, show: Boolean): Grouped {
        val unique = items.distinctBy { it.opportunity.id }
        val split = if (threshold == null) Split(unique, emptyList()) else split(unique, show, threshold)
        val sections = ProposalWindows.group(split.visible, now)
        val hidden = split.hidden.filter { ProposalWindows.sectionOf(it.opportunity, now) != null }
        return Grouped(sections.flatMap { it.items }, sections, hidden)
    }

    /** "N de baixa aderência ocultas" (null sem ocultas). */
    fun hiddenLabel(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 de baixa aderência oculta"
        else -> "$count de baixa aderência ocultas"
    }
}
