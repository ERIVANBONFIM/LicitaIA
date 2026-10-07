package com.licitaia.domain.model

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * UASG / código da unidade compradora (lógica pura, testável).
 * - Compras.gov.br: UASG com 6 dígitos (zeros à esquerda), rótulo "UASG";
 * - demais plataformas do PNCP: código da unidade do órgão, rótulo "Cód. unidade".
 */
object UasgCode {
    private val KEYWORD = Regex("""(?i)\buasg\D{0,3}(\d{3,6})\b""")
    private val QUERY = Regex("""^\s*(?:uasg\s*)?(\d{5,6})\s*$""", RegexOption.IGNORE_CASE)

    /** Só dígitos; [comprasGov] = completa com zeros à esquerda até 6. null quando vazio. */
    fun normalize(code: String?, comprasGov: Boolean): String? {
        val digits = code?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() } ?: return null
        return if (comprasGov && digits.length <= 6) digits.padStart(6, '0') else digits
    }

    /** "UASG 927312" nas palavras-chave (Compras.gov.br 14.133 e legado). */
    fun fromKeywords(keywords: List<String>): String? =
        keywords.firstNotNullOfOrNull { KEYWORD.find(it)?.groupValues?.get(1) }?.padStart(6, '0')

    /** Código da oportunidade: campo explícito; senão o das palavras-chave. */
    fun of(o: Opportunity): String? =
        o.uasg?.takeIf { it.isNotBlank() } ?: if (o.portal == Portal.COMPRAS_GOV) fromKeywords(o.keywords) else null

    /** "UASG 927312" (Compras.gov.br) ou "Cód. unidade 1234" (outras plataformas); null sem código. */
    fun label(portal: Portal, code: String?): String? {
        val c = code?.takeIf { it.isNotBlank() } ?: return null
        return if (portal == Portal.COMPRAS_GOV) "UASG $c" else "Cód. unidade $c"
    }

    fun label(o: Opportunity): String? = label(o.portal, of(o))

    /** Texto digitado que é um código de UASG (5–6 dígitos, opcionalmente "UASG 123456"); null caso contrário. */
    fun fromQuery(query: String): String? = QUERY.find(query)?.groupValues?.get(1)

    /** A oportunidade é da UASG [code] (compara sem zeros à esquerda). */
    fun matches(o: Opportunity, code: String): Boolean {
        val mine = of(o)?.trimStart('0') ?: return false
        return mine.isNotEmpty() && mine == code.filter(Char::isDigit).trimStart('0')
    }
}

/** Marcas da empresa sobre uma oportunidade do cache (descartada / vista). */
data class OpportunityFlag(
    val companyId: Long,
    val opportunityId: String,
    /** Descartada pelo usuário (some da Busca/Radar e dos avisos); null = não descartada. */
    val discardedAt: Long? = null,
    /** Aberta pelo usuário (detalhe, "Tenho interesse" ou "Analisar"): perde o selo "Nova". */
    val seenAt: Long? = null,
)

/**
 * Selo "Nova" (lógica pura, testável): entrou no cache na última atualização diária OU desde a última vez que o
 * usuário abriu a Busca, e nunca foi aberta.
 */
object Novelty {
    /** Margem antes do fim da atualização diária (duração máxima da sincronização). */
    const val DAILY_SYNC_WINDOW_MS = 15L * 60 * 1000

    /** Instante a partir do qual o que entrou no cache é "novo"; null = sem referência (primeiro uso). */
    fun since(lastDailyCompletedAt: Long?, previousSearchOpenAt: Long?): Long? =
        listOfNotNull(lastDailyCompletedAt?.minus(DAILY_SYNC_WINDOW_MS), previousSearchOpenAt).minOrNull()

    fun isNew(firstSeenAt: Long?, seenAt: Long?, since: Long?): Boolean =
        firstSeenAt != null && since != null && seenAt == null && firstSeenAt >= since

    /** Ids novos entre [ids]. */
    fun newIds(ids: Collection<String>, firstSeen: Map<String, Long>, flags: Map<String, OpportunityFlag>, since: Long?): Set<String> =
        if (since == null) emptySet()
        else ids.filterTo(HashSet()) { isNew(firstSeen[it], flags[it]?.seenAt, since) }

    /** "N novas" (null sem novas). */
    fun label(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 nova"
        else -> "$count novas"
    }
}

/** Presets do filtro de período. */
enum class PeriodPreset(val label: String) {
    ANY("Qualquer data"),
    TODAY("Hoje"),
    NEXT_7("Próximos 7 dias"),
    NEXT_30("Próximos 30 dias"),
    CUSTOM("Personalizado"),
}

/** Data comparada pelo filtro de período. */
enum class PeriodField(val label: String) {
    /** Data de proposta (encerramento; sem ele, sessão/abertura). */
    PROPOSAL("Proposta"),
    PUBLICATION("Publicação"),
}

/**
 * Filtro de período (lógica pura, testável; fuso America/Sao_Paulo). Combina com o padrão "de hoje em diante" e com
 * "Mostrar dias anteriores": só restringe o que já estaria na lista. [customFrom]/[customTo] = meia-noite local dos dias
 * escolhidos (inclusive).
 */
data class PeriodFilter(
    val preset: PeriodPreset = PeriodPreset.ANY,
    val field: PeriodField = PeriodField.PROPOSAL,
    val customFrom: Long? = null,
    val customTo: Long? = null,
) {
    val active: Boolean get() = preset != PeriodPreset.ANY && (preset != PeriodPreset.CUSTOM || customFrom != null || customTo != null)

    /** Intervalo [first, last) em epoch ms; null = sem filtro. */
    fun range(now: Long): LongRange? {
        val zone = ProposalWindows.ZONE
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return when (preset) {
            PeriodPreset.ANY -> null
            PeriodPreset.TODAY -> start(today) until start(today.plusDays(1))
            PeriodPreset.NEXT_7 -> start(today) until start(today.plusDays(8))
            PeriodPreset.NEXT_30 -> start(today) until start(today.plusDays(31))
            PeriodPreset.CUSTOM -> {
                if (customFrom == null && customTo == null) return null
                val from = customFrom?.let { start(day(it)) } ?: Long.MIN_VALUE
                val to = customTo?.let { start(day(it).plusDays(1)) } ?: Long.MAX_VALUE
                from until to
            }
        }
    }

    fun dateOf(o: Opportunity): Long? = when (field) {
        PeriodField.PUBLICATION -> o.publishedAt.takeIf { it > 0L }
        PeriodField.PROPOSAL -> ProposalWindows.proposalDate(o) ?: o.proposalOpening ?: ProposalWindows.referenceDate(o)
    }

    fun matches(o: Opportunity, now: Long): Boolean {
        val r = range(now) ?: return true
        val date = dateOf(o) ?: return false
        return date in r
    }

    /** "Próximos 7 dias", "Personalizado 01/10–15/10 (publicação)". */
    fun label(): String {
        val base = when (preset) {
            PeriodPreset.CUSTOM -> {
                val f = DateTimeFormatter.ofPattern("dd/MM").withZone(ProposalWindows.ZONE)
                val from = customFrom?.let { f.format(Instant.ofEpochMilli(it)) } ?: "…"
                val to = customTo?.let { f.format(Instant.ofEpochMilli(it)) } ?: "…"
                "$from–$to"
            }
            else -> preset.label
        }
        return if (field == PeriodField.PUBLICATION && preset != PeriodPreset.ANY) "$base (publicação)" else base
    }
}

/**
 * Filtros locais da lista (sem nova consulta): descartadas, novas e período. [discarded]/[newIds] = ids da empresa.
 */
data class ListMarks(
    val discarded: Set<String> = emptySet(),
    /** Chip "Descartadas (N)": mostra SÓ as descartadas (para restaurar). */
    val showDiscarded: Boolean = false,
    val newIds: Set<String> = emptySet(),
    /** Chip "Só novas". */
    val onlyNew: Boolean = false,
    val period: PeriodFilter = PeriodFilter(),
)

// ---------------------------------------------------------------- situação oficial (selo colorido)

/** Gravidade visual do selo: vermelho (cancelada/revogada/anulada), âmbar (suspensa), cinza (deserta/fracassada). */
enum class SituationSeverity { RED, AMBER, GRAY }

/**
 * Situação oficial que interrompe/encerra a contratação (PNCP `situacaoCompraId`/`situacaoCompraNome`, Compras.gov.br
 * `situacaoCompraNomePncp`/`situacao_aviso`, aviso "COMPRA SUSPENSA/ANULADA/REVOGADA" do Comprasnet). null = normal.
 */
enum class OfficialSituation(val label: String, val severity: SituationSeverity) {
    SUSPENSA("SUSPENSA", SituationSeverity.AMBER),
    CANCELADA("CANCELADA", SituationSeverity.RED),
    REVOGADA("REVOGADA", SituationSeverity.RED),
    ANULADA("ANULADA", SituationSeverity.RED),
    DESERTA("DESERTA", SituationSeverity.GRAY),
    FRACASSADA("FRACASSADA", SituationSeverity.GRAY),
    ;

    /** O robô não pode iniciar sozinho (suspensa/cancelada/revogada/anulada/deserta/fracassada). */
    val blocksRobot: Boolean get() = true

    companion object {
        private fun norm(s: String?) = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        /** Pelo texto da fonte/portal ("Revogada", "Compra suspensa", "Licitação deserta"...). */
        fun fromText(text: String?): OfficialSituation? {
            val s = norm(text)
            if (s.isBlank()) return null
            return when {
                "suspens" in s -> SUSPENSA
                "revogad" in s -> REVOGADA
                "anulad" in s -> ANULADA
                "cancelad" in s -> CANCELADA
                "desert" in s -> DESERTA
                "fracassad" in s -> FRACASSADA
                else -> null
            }
        }

        /**
         * PNCP `situacaoCompraId` (1 Divulgada, 2 Revogada, 3 Anulada, 4 Suspensa); o nome, quando houver, tem prioridade
         * (cobre deserta/fracassada/cancelada).
         */
        fun of(id: Int?, name: String?): OfficialSituation? = fromText(name) ?: when (id) {
            2 -> REVOGADA
            3 -> ANULADA
            4 -> SUSPENSA
            else -> null
        }

        /** Prefixo da palavra-chave "situação: Suspensa" (cache de linhas do Compras.gov.br, sem coluna própria). */
        const val KEYWORD_PREFIX = "situação: "

        fun fromKeywords(keywords: List<String>): OfficialSituation? =
            keywords.firstOrNull { it.startsWith(KEYWORD_PREFIX) }?.let { fromText(it.removePrefix(KEYWORD_PREFIX)) }

        /** Grava/lê o nome no banco (null = normal). */
        fun parse(stored: String?): OfficialSituation? = stored?.let { s -> entries.firstOrNull { it.name == s } }
    }
}

/**
 * ADIADA (lógica pura, testável): a data oficial de propostas mudou em relação ao valor salvo no cache (detectado na
 * atualização diária para TODAS as oportunidades do cache) ou a fonte publicou "adiada"/"remarcada".
 */
object Postponement {
    private val SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ProposalWindows.ZONE)
    private val WORDS = Regex("""(?i)\b(adiad[ao]s?|remarcad[ao]s?|adiamento)\b""")

    /** Diferença mínima para considerar mudança de data (arredondamentos de fonte). */
    const val MIN_SHIFT_MS = 60_000L

    /**
     * Novo registro vindo da fonte × o que estava salvo: data mudou → guarda a anterior ("antes"); data igual → mantém a
     * marcação anterior (se havia); sem registro salvo → como veio.
     */
    fun merge(old: Opportunity?, new: Opportunity): Opportunity {
        if (old == null) return new
        val changed = old.hasProposalDeadline && new.hasProposalDeadline &&
            kotlin.math.abs(old.proposalDeadline - new.proposalDeadline) > MIN_SHIFT_MS
        return when {
            changed -> new.copy(previousProposalDeadline = old.proposalDeadline)
            new.previousProposalDeadline == null && old.previousProposalDeadline != null && new.proposalDeadline == old.proposalDeadline ->
                new.copy(previousProposalDeadline = old.previousProposalDeadline)
            else -> new
        }
    }

    fun isPostponed(o: Opportunity): Boolean =
        (o.previousProposalDeadline != null && o.hasProposalDeadline && o.previousProposalDeadline != o.proposalDeadline) ||
            o.keywords.any { WORDS.containsMatchIn(it) }

    /** "ADIADA para 15/10 09:00" (null quando não está adiada). */
    fun label(o: Opportunity): String? {
        if (!isPostponed(o)) return null
        return if (o.hasProposalDeadline) "ADIADA para ${SHORT.format(Instant.ofEpochMilli(o.proposalDeadline))}" else "ADIADA"
    }

    /** "antes: 08/10 10:00" (null sem data anterior). */
    fun beforeLabel(o: Opportunity): String? =
        o.previousProposalDeadline?.takeIf { it > 0L && isPostponed(o) }?.let { "antes: ${SHORT.format(Instant.ofEpochMilli(it))}" }
}

// ---------------------------------------------------------------- mudança de fase das licitações acompanhadas

/** Situação oficial de uma contratação (PNCP `situacaoCompra` / Compras.gov.br situação + datas). */
data class OfficialStatus(
    val situation: String?,
    val proposalDeadline: Long = 0L,
    val proposalOpening: Long? = null,
    /** A fonte informou resultado/homologação. */
    val hasResult: Boolean = false,
)

/** Último estado oficial conhecido de uma licitação acompanhada (tabela `tender_status_watch`). */
data class TenderStatusSnapshot(
    val tenderId: Long,
    val companyId: Long,
    val situation: String?,
    val proposalDeadline: Long,
    val sessionAt: Long,
    val hasResult: Boolean,
    val checkedAt: Long,
)

enum class TenderPhase(val title: String) {
    SUSPENDED("suspensa"),
    REVOKED("revogada/anulada"),
    POSTPONED("adiada"),
    RESULT("com resultado/homologada"),
}

data class TenderPhaseChange(val phase: TenderPhase, val detail: String)

/** Detecção de mudança de fase (lógica pura, testável). Uma notificação por mudança: o estado novo é gravado depois. */
object TenderPhaseRule {
    private val SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ProposalWindows.ZONE)

    private fun norm(s: String?) = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    /** Fase indicada pelo nome da situação (ou [hasResult]); null = em andamento/divulgada. */
    fun phaseOf(situation: String?, hasResult: Boolean = false): TenderPhase? {
        val s = norm(situation)
        return when {
            "suspens" in s -> TenderPhase.SUSPENDED
            "revog" in s || "anulad" in s || "cancelad" in s -> TenderPhase.REVOKED
            hasResult || "homolog" in s || "adjudic" in s || "resultado" in s || "concluid" in s -> TenderPhase.RESULT
            else -> null
        }
    }

    /** Diferença de mais de 1 min entre datas conhecidas. */
    private fun moved(old: Long, new: Long) = old > 0L && new > 0L && kotlin.math.abs(old - new) > 60_000L

    /** null = sem mudança relevante (ou primeira observação: só grava, não avisa). */
    fun change(old: TenderStatusSnapshot?, new: OfficialStatus, newSessionAt: Long = new.proposalDeadline): TenderPhaseChange? {
        if (old == null) return null
        val before = phaseOf(old.situation, old.hasResult)
        val after = phaseOf(new.situation, new.hasResult)
        if (after != null && after != before) {
            return TenderPhaseChange(after, new.situation?.takeIf { it.isNotBlank() } ?: after.title)
        }
        if (after == null && moved(old.proposalDeadline, new.proposalDeadline)) {
            return TenderPhaseChange(
                TenderPhase.POSTPONED,
                "propostas: ${SHORT.format(Instant.ofEpochMilli(old.proposalDeadline))} → ${SHORT.format(Instant.ofEpochMilli(new.proposalDeadline))}",
            )
        }
        if (after == null && moved(old.sessionAt, newSessionAt)) {
            return TenderPhaseChange(
                TenderPhase.POSTPONED,
                "sessão: ${SHORT.format(Instant.ofEpochMilli(old.sessionAt))} → ${SHORT.format(Instant.ofEpochMilli(newSessionAt))}",
            )
        }
        return null
    }

    /** Título e texto da notificação. */
    fun message(number: String, agency: String, change: TenderPhaseChange): Pair<String, String> =
        "Licitação $number ${change.phase.title}" to "$agency — ${change.detail}. Toque para abrir a licitação."
}
