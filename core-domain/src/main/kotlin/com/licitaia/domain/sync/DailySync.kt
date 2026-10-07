package com.licitaia.domain.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Atualização diária das licitações (Compras.gov.br + PNCP + prazos + notas de IA dos radares) num horário fixo
 * (padrão 05:30, fuso de Brasília): o app abre com a lista já baixada e não rebaixa tudo a cada abertura.
 */
data class DailySyncSettings(
    val enabled: Boolean = true,
    val hour: Int = DEFAULT_HOUR,
    val minute: Int = DEFAULT_MINUTE,
) {
    /** "05:30". */
    val timeLabel: String get() = "%02d:%02d".format(hour, minute)

    companion object {
        const val DEFAULT_HOUR = 5
        const val DEFAULT_MINUTE = 30
    }
}

/** Estado persistido da atualização diária (instantes em epoch ms; null = nunca). */
data class DailySyncStatus(
    /** Fim da última sincronização COMPLETA bem-sucedida (agendada ou de recuperação). */
    val lastCompletedAt: Long? = null,
    /** Última limpeza do cache local (canceladas/encerradas/antigas). */
    val lastCleanupAt: Long? = null,
    /** Sincronização diária em andamento agora (Worker rodando). */
    val running: Boolean = false,
)

/**
 * Configuração e disparo da atualização diária. Implementação no app (AlarmManager + WorkManager).
 */
interface DailySyncRepository {
    val settings: Flow<DailySyncSettings>
    val status: Flow<DailySyncStatus>

    /** Grava a configuração e reagenda o próximo alarme. */
    suspend fun updateSettings(transform: (DailySyncSettings) -> DailySyncSettings)

    /**
     * Abertura do app/tela de busca: se o horário de hoje passou sem sincronização (aparelho desligado/sem rede), dispara
     * a de recuperação em segundo plano; se a limpeza ainda não rodou hoje, roda (local, rápida). Nunca bloqueia.
     */
    fun onAppOpened()
}

/** Cálculo de horários da atualização diária (lógica pura, testável). Fuso sempre America/Sao_Paulo. */
object DailySyncSchedule {
    val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")

    private fun slotOn(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)), zone).toInstant().toEpochMilli()

    private fun today(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    /** Próximo horário agendado ESTRITAMENTE depois de [now] (hoje, se ainda não chegou; senão amanhã). */
    fun nextRun(now: Long, hour: Int, minute: Int, zone: ZoneId = ZONE): Long {
        val day = today(now, zone)
        val todaySlot = slotOn(day, hour, minute, zone)
        return if (now < todaySlot) todaySlot else slotOn(day.plusDays(1), hour, minute, zone)
    }

    /** Último horário agendado que já passou (hoje, se já chegou; senão ontem). */
    fun latestSlot(now: Long, hour: Int, minute: Int, zone: ZoneId = ZONE): Long {
        val day = today(now, zone)
        val todaySlot = slotOn(day, hour, minute, zone)
        return if (now >= todaySlot) todaySlot else slotOn(day.minusDays(1), hour, minute, zone)
    }

    /**
     * Recuperação: a última sincronização completa é anterior ao último horário agendado que já passou (ex.: abriu às
     * 08:00 e a das 05:30 não rodou porque o aparelho estava desligado/sem rede). Desligada → nunca.
     */
    fun isCatchUpDue(now: Long, lastCompletedAt: Long?, settings: DailySyncSettings, zone: ZoneId = ZONE): Boolean {
        if (!settings.enabled) return false
        val last = lastCompletedAt ?: return true
        return last < latestSlot(now, settings.hour, settings.minute, zone)
    }

    /** Meia-noite do 1º dia do mês de [now]. */
    fun monthStart(now: Long, zone: ZoneId = ZONE): Long =
        today(now, zone).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Meia-noite do dia de [now]. */
    fun dayStart(now: Long, zone: ZoneId = ZONE): Long = today(now, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    fun sameDay(a: Long, b: Long, zone: ZoneId = ZONE): Boolean = today(a, zone) == today(b, zone)

    /** "hoje às 05:30" / "ontem às 05:31" / "em 03/10 às 05:30". */
    fun whenLabel(at: Long, now: Long, zone: ZoneId = ZONE): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val time = "%02d:%02d".format(t.hour, t.minute)
        val day = t.toLocalDate()
        val ref = today(now, zone)
        return when (day) {
            ref -> "hoje às $time"
            ref.minusDays(1) -> "ontem às $time"
            else -> "em %02d/%02d às %s".format(day.dayOfMonth, day.monthValue, time)
        }
    }

    /** "hoje 05:30" / "amanhã 05:30" / "08/10 05:30". */
    fun nextLabel(next: Long, now: Long, zone: ZoneId = ZONE): String {
        val t = Instant.ofEpochMilli(next).atZone(zone)
        val time = "%02d:%02d".format(t.hour, t.minute)
        val ref = today(now, zone)
        return when (t.toLocalDate()) {
            ref -> "hoje $time"
            ref.plusDays(1) -> "amanhã $time"
            else -> "%02d/%02d %s".format(t.dayOfMonth, t.monthValue, time)
        }
    }

    /**
     * Linha de fontes da busca: "Atualizado hoje às 05:30 · Próxima atualização automática: amanhã 05:30"
     * (desligada: só o "Atualizado…" e o aviso de que a automática está desligada).
     */
    fun sourceLine(status: DailySyncStatus, settings: DailySyncSettings, now: Long, zone: ZoneId = ZONE): String {
        val updated = when {
            status.running -> "Atualizando agora em segundo plano"
            status.lastCompletedAt != null -> "Atualizado ${whenLabel(status.lastCompletedAt, now, zone)}"
            else -> "Ainda não atualizado"
        }
        val next = if (settings.enabled) "Próxima atualização automática: ${nextLabel(nextRun(now, settings.hour, settings.minute, zone), now, zone)}"
        else "Atualização automática desligada"
        return "$updated · $next"
    }
}
