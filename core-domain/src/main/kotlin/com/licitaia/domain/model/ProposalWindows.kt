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

    /** Data de proposta em dias anteriores do mês atual (oculta por padrão; "Mostrar dias anteriores"). */
    PREVIOUS_DAYS("Dias anteriores"),
}

data class SectionedOpportunities(val section: OpportunitySection, val items: List<ScoredOpportunity>)

/** Filtro rápido de modalidade da Busca (chips "Todas · Pregão · Dispensa · Concorrência/Outras"). */
enum class ModalityGroup(val label: String) {
    ALL("Todas"),
    PREGAO("Pregão"),
    DISPENSA("Dispensa"),
    OTHERS("Concorrência/Outras"),
    ;

    fun matches(o: Opportunity): Boolean = when (this) {
        ALL -> true
        PREGAO -> o.modality == Modality.PREGAO_ELETRONICO
        DISPENSA -> o.modality == Modality.DISPENSA_ELETRONICA
        OTHERS -> o.modality != Modality.PREGAO_ELETRONICO && o.modality != Modality.DISPENSA_ELETRONICA
    }
}

/**
 * Regras de exibição por data de proposta (lógica pura, testável; fuso America/Sao_Paulo).
 *
 * Escopo: do 1º dia do MÊS ATUAL em diante (data de proposta = encerramento do recebimento de propostas; sem ele, a
 * sessão; dispensa sem disputa sem nenhuma das duas: a publicação). Exibição padrão: de HOJE em diante (≥ meia-noite
 * de hoje), inclusive as que encerraram hoje (estado "encerrada"); as de dias anteriores do mês ficam na seção
 * "Dias anteriores", oculta por padrão. A cada virada do dia as do dia anterior passam para lá sozinhas.
 * Sem data confiável (e não dispensa sem disputa): ocultas e contadas.
 * Agrupamento: "Hoje" (encerramento ou sessão hoje) → "Próximos dias" → "Vão abrir" → "Dias anteriores"; dentro de
 * cada seção, maior nota primeiro e, empatando, o horário (encerramento/sessão; abertura em "Vão abrir"); em
 * "Dias anteriores", da mais recente para a mais antiga.
 */
object ProposalWindows {
    val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE)

    /** Meia-noite (America/Sao_Paulo) do dia de [now]. */
    fun dayStart(now: Long): Long = day(now).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** Meia-noite do 1º dia do mês de [now]. */
    fun monthStart(now: Long): Long = day(now).withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** Data de proposta: encerramento do recebimento; sem ele, a sessão; null quando a fonte não informou nenhuma. */
    fun proposalDate(o: Opportunity): Long? = when {
        o.hasProposalDeadline -> o.proposalDeadline
        o.sessionAt > 0L -> o.sessionAt
        else -> null
    }

    /** Data que define o "dia" do item: [proposalDate]; dispensa sem disputa sem datas usa a publicação. */
    fun referenceDate(o: Opportunity): Long? =
        proposalDate(o) ?: o.publishedAt.takeIf { o.noDispute && it > 0L }

    /** Data de referência em um dia anterior a hoje, dentro do mês atual. */
    fun isPreviousDay(o: Opportunity, now: Long): Boolean =
        referenceDate(o)?.let { it >= monthStart(now) && it < dayStart(now) } ?: false

    /** Data de referência anterior ao 1º dia do mês atual (fora do escopo; a limpeza diária apaga). */
    fun isBeforeMonth(o: Opportunity, now: Long): Boolean = referenceDate(o)?.let { it < monthStart(now) } ?: false

    fun classify(o: Opportunity, now: Long): ProposalWindow = when {
        !o.hasProposalDeadline -> ProposalWindow.NO_DEADLINE
        o.proposalDeadline <= now -> ProposalWindow.CLOSED
        // Sessão de disputa já realizada (fontes que publicam a sessão separada do encerramento).
        o.sessionAt > 0L && o.sessionAt < now && o.sessionAt != o.proposalDeadline -> ProposalWindow.CLOSED
        (o.proposalOpening ?: 0L) > now -> ProposalWindow.UPCOMING
        else -> ProposalWindow.OPEN
    }

    /**
     * Resultado do corte: [items] = de hoje em diante (inclui as encerradas hoje); [previous] = dias anteriores do mês
     * atual (exibidas só com "Mostrar dias anteriores" ou texto digitado); [hiddenNoDeadline] = sem data confiável
     * ocultas (as anteriores ao mês não contam).
     */
    data class Visible(
        val items: List<Opportunity>,
        val hiddenNoDeadline: Int,
        val previous: List<Opportunity> = emptyList(),
    )

    /**
     * Mantém o que tem data de proposta do 1º dia do mês atual em diante, separando hoje-em-diante de dias anteriores.
     * Sem data: ocultas (contadas), exceto dispensas sem disputa com [showNoDispute] (o dia delas é a publicação).
     */
    fun visible(opportunities: List<Opportunity>, now: Long, showNoDispute: Boolean = false): Visible {
        var hidden = 0
        val kept = ArrayList<Opportunity>()
        val previous = ArrayList<Opportunity>()
        for (o in opportunities) {
            if (proposalDate(o) == null && !(o.noDispute && showNoDispute)) {
                hidden++
                continue
            }
            when (sectionOf(o, now)) {
                null -> Unit
                OpportunitySection.PREVIOUS_DAYS -> previous += o
                else -> kept += o
            }
        }
        return Visible(kept, hidden, previous)
    }

    fun isToday(millis: Long, now: Long): Boolean =
        millis > 0L && day(millis) == day(now)

    private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate()

    /** Encerramento ou sessão hoje (o horário de referência da seção "Hoje"); null se nenhum for hoje. */
    fun todayTime(o: Opportunity, now: Long): Long? = listOf(o.proposalDeadline, o.sessionAt)
        .filter { it >= now && isToday(it, now) }
        .minOrNull()

    /**
     * Seção do item em [now]; null = fora do escopo (data anterior ao mês atual ou sem data e não dispensa sem disputa).
     * Encerradas hoje continuam em "Hoje" (estado "encerrada"); data em dia anterior do mês → "Dias anteriores".
     */
    fun sectionOf(o: Opportunity, now: Long): OpportunitySection? {
        val ref = referenceDate(o) ?: return null
        if (ref < monthStart(now)) return null
        if (ref < dayStart(now)) return OpportunitySection.PREVIOUS_DAYS
        return when (classify(o, now)) {
            ProposalWindow.CLOSED -> if (isToday(ref, now)) OpportunitySection.TODAY else OpportunitySection.NEXT_DAYS
            ProposalWindow.NO_DEADLINE ->
                if (!o.noDispute && todayTime(o, now) != null) OpportunitySection.TODAY else OpportunitySection.NEXT_DAYS
            ProposalWindow.UPCOMING -> if (todayTime(o, now) != null) OpportunitySection.TODAY else OpportunitySection.UPCOMING
            ProposalWindow.OPEN -> if (todayTime(o, now) != null) OpportunitySection.TODAY else OpportunitySection.NEXT_DAYS
        }
    }

    /** Prazo de propostas (ou sessão) já passou: card no estado "encerrada". */
    fun isClosed(o: Opportunity, now: Long): Boolean = when (classify(o, now)) {
        ProposalWindow.CLOSED -> true
        ProposalWindow.NO_DEADLINE -> !o.noDispute && o.sessionAt in 1 until now
        else -> false
    }

    /**
     * Ordem dentro de uma seção: maior nota primeiro ([ScoredOpportunity.score] = nota por IA quando houver, senão a
     * heurística) e, empatando, o horário de referência da seção ([timeOf]: encerramento/sessão ou abertura).
     */
    fun sectionOrder(timeOf: (ScoredOpportunity) -> Long): Comparator<ScoredOpportunity> =
        compareByDescending<ScoredOpportunity> { it.score }.thenBy(timeOf).thenBy { it.opportunity.id }

    /**
     * Seções não vazias, na ordem fixa Hoje → Próximos dias → Vão abrir → Dias anteriores; fora do escopo (antes do
     * mês atual / sem data) somem. Dentro de cada seção: nota decrescente e depois o horário ([sectionOrder]); em "Hoje"
     * as encerradas vão para o fim; "Dias anteriores" da data mais recente para a mais antiga.
     * A seção "Dias anteriores" só aparece se houver itens dela em [items] (quem chama decide se os inclui).
     */
    fun group(items: List<ScoredOpportunity>, now: Long): List<SectionedOpportunities> {
        val by = items.groupBy { sectionOf(it.opportunity, now) }
        val today = by[OpportunitySection.TODAY].orEmpty()
            .sortedWith(
                compareBy<ScoredOpportunity> { isClosed(it.opportunity, now) }
                    .then(sectionOrder { todayTime(it.opportunity, now) ?: proposalDate(it.opportunity) ?: Long.MAX_VALUE }),
            )
        val next = by[OpportunitySection.NEXT_DAYS].orEmpty()
            .sortedWith(sectionOrder { if (it.opportunity.hasProposalDeadline) it.opportunity.proposalDeadline else Long.MAX_VALUE })
        val upcoming = by[OpportunitySection.UPCOMING].orEmpty()
            .sortedWith(sectionOrder { it.opportunity.proposalOpening ?: Long.MAX_VALUE })
        val previous = by[OpportunitySection.PREVIOUS_DAYS].orEmpty()
            .sortedWith(
                compareByDescending<ScoredOpportunity> { referenceDate(it.opportunity) ?: 0L }
                    .thenByDescending { it.score }.thenBy { it.opportunity.id },
            )
        return listOf(
            SectionedOpportunities(OpportunitySection.TODAY, today),
            SectionedOpportunities(OpportunitySection.NEXT_DAYS, next),
            SectionedOpportunities(OpportunitySection.UPCOMING, upcoming),
            SectionedOpportunities(OpportunitySection.PREVIOUS_DAYS, previous),
        ).filter { it.items.isNotEmpty() }
    }

    /**
     * "Abre em dd/MM HH:mm" / "Aberta · encerra em dd/MM HH:mm" / "Encerrada em dd/MM HH:mm" ("Dia anterior · encerrada
     * em …" para as de dias anteriores); null quando não se aplica.
     */
    fun statusLabel(o: Opportunity, now: Long): String? = when (classify(o, now)) {
        ProposalWindow.UPCOMING -> "Abre em ${SHORT.format(Instant.ofEpochMilli(o.proposalOpening!!))}"
        ProposalWindow.OPEN -> "Aberta · encerra em ${SHORT.format(Instant.ofEpochMilli(o.proposalDeadline))}"
        ProposalWindow.CLOSED -> closedLabel(o, now, if (o.proposalDeadline <= now) o.proposalDeadline else o.sessionAt)
        ProposalWindow.NO_DEADLINE -> if (isClosed(o, now)) closedLabel(o, now, o.sessionAt)
        else if (isPreviousDay(o, now)) "Dia anterior" else null
    }

    private fun closedLabel(o: Opportunity, now: Long, at: Long): String {
        val text = "ncerrada em ${SHORT.format(Instant.ofEpochMilli(at))}"
        return if (isPreviousDay(o, now)) "Dia anterior · e$text" else "E$text"
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

    /**
     * Lista pronta para exibir: visíveis (achatados na ordem das seções), seções, ocultos por baixa aderência e a
     * contagem de dias anteriores ([previousCount]: as que aparecem com "Mostrar dias anteriores"; [previousShown] = já
     * estão na lista). [currentCount] = de hoje em diante visíveis; [dispensas] = dispensas visíveis.
     */
    data class Grouped(
        val items: List<ScoredOpportunity>,
        val sections: List<SectionedOpportunities>,
        val hiddenLow: List<ScoredOpportunity>,
        val previousCount: Int = 0,
        val previousShown: Boolean = false,
        val currentCount: Int = items.size,
        val dispensas: Int = 0,
        /** Descartadas pela empresa entre as que casam com os filtros (chip "Descartadas (N)"). */
        val discardedCount: Int = 0,
        /** Novas (selo "Nova") entre as visíveis. */
        val newCount: Int = 0,
    ) {
        /** "312 de hoje em diante · 45 de dias anteriores ocultos · 120 dispensas · 8 novas". */
        val windowSummary: String
            get() = listOfNotNull(DayWindowSummary.of(currentCount, previousCount, previousShown, dispensas), Novelty.label(newCount))
                .joinToString(" · ")
    }

    /**
     * Dedup por id → modalidade ([modality]) → escopo do mês (fora dele some) → dias anteriores (só com
     * [showPrevious]) → corte de baixa aderência ([threshold] null = sem corte, como nos radares) → seções
     * Hoje / Próximos dias / Vão abrir / Dias anteriores. Recalculado pelo relógio: na virada do dia as de ontem passam
     * para "Dias anteriores" sozinhas.
     */
    fun group(
        items: List<ScoredOpportunity>,
        now: Long,
        threshold: Int?,
        show: Boolean,
        showPrevious: Boolean = false,
        modality: ModalityGroup = ModalityGroup.ALL,
        marks: ListMarks = ListMarks(),
    ): Grouped {
        val sectionById = HashMap<String, OpportunitySection>()
        var discardedCount = 0
        val unique = items.distinctBy { it.opportunity.id }.filter { s ->
            val o = s.opportunity
            if (!modality.matches(o)) return@filter false
            val section = ProposalWindows.sectionOf(o, now) ?: return@filter false
            if (!marks.period.matches(o, now)) return@filter false
            val discarded = o.id in marks.discarded
            if (discarded) discardedCount++
            // Chip "Descartadas": só elas (para restaurar); senão, descartadas nunca aparecem.
            if (discarded != marks.showDiscarded) return@filter false
            if (marks.onlyNew && o.id !in marks.newIds) return@filter false
            sectionById[o.id] = section
            true
        }
        val (previous, current) = unique.partition { sectionById[it.opportunity.id] == OpportunitySection.PREVIOUS_DAYS }
        val base = if (showPrevious) unique else current
        val split = if (threshold == null) Split(base, emptyList()) else split(base, show, threshold)
        val sections = ProposalWindows.group(split.visible, now)
        val flat = sections.flatMap { it.items }
        val previousVisible = if (threshold == null || show) previous.size else previous.count { !isLow(it, threshold) }
        val currentVisible = flat.count { sectionById[it.opportunity.id] != OpportunitySection.PREVIOUS_DAYS }
        return Grouped(
            items = flat, sections = sections, hiddenLow = split.hidden,
            previousCount = previousVisible, previousShown = showPrevious, currentCount = currentVisible,
            dispensas = flat.count { it.opportunity.modality == Modality.DISPENSA_ELETRONICA },
            discardedCount = discardedCount,
            newCount = flat.count { it.opportunity.id in marks.newIds },
        )
    }

    /** "N de baixa aderência ocultas" (null sem ocultas). */
    fun hiddenLabel(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 de baixa aderência oculta"
        else -> "$count de baixa aderência ocultas"
    }
}

/** Linha de resumo da janela de datas da lista (lógica pura, testável). */
object DayWindowSummary {
    /**
     * "312 de hoje em diante · 45 de dias anteriores ocultos · 120 dispensas" (com os anteriores visíveis:
     * "· 45 de dias anteriores"); partes zeradas de anteriores/dispensas são omitidas.
     */
    fun of(current: Int, previous: Int, previousShown: Boolean, dispensas: Int): String = buildList {
        add("$current de hoje em diante")
        if (previous > 0) {
            add(
                if (previousShown) "$previous de dias anteriores"
                else if (previous == 1) "1 de dia anterior oculto" else "$previous de dias anteriores ocultos",
            )
        }
        if (dispensas > 0) add(if (dispensas == 1) "1 dispensa" else "$dispensas dispensas")
    }.joinToString(" · ")
}
