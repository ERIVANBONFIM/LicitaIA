package com.licitaia.feature.warroom

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.portal.PortalMyTender
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Sessão pública da empresa nos próximos dias (licitação salva ou "minhas licitações" do portal). */
data class UpcomingSession(
    val key: String,
    val tenderId: Long?,
    val portal: Portal,
    val number: String,
    val agency: String,
    val objectDescription: String,
    val sessionAt: Long,
    val statusLabel: String,
    /** A empresa já cadastrou proposta/está participando. */
    val participating: Boolean,
    /** Situação oficial (suspensa, revogada...) para o selo colorido; null = normal. */
    val situation: com.licitaia.domain.model.OfficialSituation? = null,
)

enum class DeadlineKind(val label: String) {
    PROPOSTA("Envio da proposta"),
    ESCLARECIMENTO("Pedido de esclarecimento"),
    IMPUGNACAO("Impugnação do edital"),
}

data class UpcomingDeadline(
    val key: String,
    val tenderId: Long?,
    val portal: Portal,
    val number: String,
    val kind: DeadlineKind,
    val at: Long,
    /** true = data calculada pela regra legal (não publicada pela fonte). */
    val estimated: Boolean,
)

/** Montagem da agenda da Sala de Guerra. Pura e testável. */
object WarRoomAgenda {
    const val DAY_MS = 24L * 60 * 60 * 1000
    val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")

    /** Licitações encerradas/descartadas não entram na agenda. */
    private val CLOSED = setOf(TenderStatus.VENCIDA, TenderStatus.PERDIDA, TenderStatus.DESCARTADA)
    private val PARTICIPATING = setOf(TenderStatus.ENVIADA_SIMULADA, TenderStatus.EM_DISPUTA, TenderStatus.PRONTA_PARA_ENVIO, TenderStatus.APROVADA)

    fun upcomingSessions(tenders: List<Tender>, myTenders: List<PortalMyTender>, now: Long, days: Int = 7): List<UpcomingSession> {
        val end = now + days * DAY_MS
        val fromTenders = tenders.filter { it.status !in CLOSED }
            .mapNotNull { t ->
                val at = t.sessionAt.takeIf { it > 0L } ?: t.proposalDeadline.takeIf { it > 0L } ?: return@mapNotNull null
                if (at !in now..end) return@mapNotNull null
                UpcomingSession(
                    key = "t-${t.id}", tenderId = t.id, portal = t.portal, number = t.number, agency = t.agency,
                    objectDescription = t.objectDescription, sessionAt = at, statusLabel = t.status.label,
                    participating = t.status in PARTICIPATING, situation = t.officialSituation,
                )
            }
        val linked = fromTenders.mapNotNull { it.tenderId }.toSet()
        val fromPortal = myTenders.filter { it.matchedTenderId == null || it.matchedTenderId !in linked }
            .mapNotNull { m ->
                val at = m.openingAt ?: return@mapNotNull null
                if (at !in now..end) return@mapNotNull null
                UpcomingSession(
                    key = "m-${m.tenderKey}", tenderId = m.matchedTenderId, portal = m.portal, number = "${m.number}/${m.year}",
                    agency = "UASG ${m.uasg}", objectDescription = m.objectDescription, sessionAt = at,
                    statusLabel = m.situation.ifBlank { "Minhas licitações" }, participating = m.hasProposal,
                    situation = com.licitaia.domain.model.OfficialSituation.fromText(m.situation),
                )
            }
        return (fromTenders + fromPortal).sortedBy { it.sessionAt }
    }

    /**
     * Prazos dos próximos [days] dias: fim do envio da proposta (publicado) e, para cada sessão, esclarecimento e
     * impugnação até 3 dias úteis antes da abertura (art. 164 da Lei 14.133/2021) — marcados como estimados.
     */
    fun upcomingDeadlines(tenders: List<Tender>, now: Long, days: Int = 7): List<UpcomingDeadline> {
        val end = now + days * DAY_MS
        return tenders.filter { it.status !in CLOSED }.flatMap { t ->
            buildList {
                if (t.proposalDeadline > 0L && t.status !in setOf(TenderStatus.ENVIADA_SIMULADA, TenderStatus.EM_DISPUTA)) {
                    add(UpcomingDeadline("p-${t.id}", t.id, t.portal, t.number, DeadlineKind.PROPOSTA, t.proposalDeadline, estimated = false))
                }
                val opening = t.sessionAt.takeIf { it > 0L } ?: t.proposalDeadline.takeIf { it > 0L }
                if (opening != null) {
                    val legal = businessDaysBefore(opening, 3)
                    add(UpcomingDeadline("e-${t.id}", t.id, t.portal, t.number, DeadlineKind.ESCLARECIMENTO, legal, estimated = true))
                    add(UpcomingDeadline("i-${t.id}", t.id, t.portal, t.number, DeadlineKind.IMPUGNACAO, legal, estimated = true))
                }
            }
        }.filter { it.at in now..end }.sortedBy { it.at }
    }

    /** Fim do dia útil que fica [n] dias úteis antes de [at] (sáb/dom não contam; feriados não são conhecidos). */
    fun businessDaysBefore(at: Long, n: Int): Long {
        var date = Instant.ofEpochMilli(at).atZone(ZONE).toLocalDate()
        var count = 0
        while (count < n) {
            date = date.minusDays(1)
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) count++
        }
        return date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1
    }

    /** "2d 04h" / "03h 12min" / "12:30" (mm:ss) — contagem regressiva. */
    fun countdown(at: Long, now: Long): String {
        val diff = at - now
        if (diff <= 0) return "agora"
        val totalSec = diff / 1000
        val d = totalSec / 86_400
        val h = (totalSec % 86_400) / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            d >= 1 -> "${d}d ${h.toString().padStart(2, '0')}h"
            h >= 1 -> "${h.toString().padStart(2, '0')}h ${m.toString().padStart(2, '0')}min"
            else -> "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        }
    }
}
