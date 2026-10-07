package com.licitaia.app

import android.content.Context
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.ProposalWindows
import com.licitaia.domain.repository.AppNotifier
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.RadarRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Execução dos radares ativos em segundo plano (Worker de alertas a cada 3 h e atualização diária das 05:30): mesma
 * busca/dedup/portais da tela, nota heurística + nota por IA (provedor real) só para itens novos, até [AI_LIMIT] por
 * radar; a nota fica no cache. Os ids já vistos por radar ficam em SharedPreferences "personal_alerts" (um aviso por
 * oportunidade nova, nunca repetido entre o Worker de 3 h e a atualização diária).
 */
@Singleton
class RadarAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val radars: RadarRepository,
    private val opportunities: OpportunityRepository,
    private val notifier: AppNotifier,
) {
    /** [found] = ids de todos os resultados dos radares; [fresh] = os nunca vistos antes desta execução. */
    data class Outcome(val found: Set<String>, val fresh: Set<String>)

    /**
     * Roda os radares ativos de [company]. [notify] = um aviso por radar com resultados novos (Worker de 3 h); a
     * atualização diária passa false e manda um aviso único no fim. [shouldStop] interrompe entre radares.
     */
    suspend fun check(session: AuthSession, company: Long, notify: Boolean, shouldStop: () -> Boolean = { false }): Outcome {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val allFound = HashSet<String>()
        val allFresh = HashSet<String>()
        for (radar in radars.observeRadars(company).first().filter { it.active }) {
            if (shouldStop() || auth.session.value != session) break
            // Falha de um radar (fonte em backoff, 429...) não derruba os demais nem força retry imediato: tenta no próximo ciclo.
            val seenKey = "radar:${company}:${radar.id}"
            val previous = prefs.getStringSet(seenKey, emptySet()).orEmpty()
            val found = opportunities.runRadarWithAi(radar.id, AI_LIMIT, skipIds = previous).getOrNull() ?: continue
            val ids = found.map { it.opportunity.id }.toSet()
            val fresh = ids - previous
            allFound += ids
            allFresh += fresh
            // Prioridade para as que encerram (ou têm sessão) HOJE: título e texto destacam essas primeiro.
            val now = System.currentTimeMillis()
            val today = found.count { it.opportunity.id in fresh && ProposalWindows.endsToday(it.opportunity, now) }
            if (notify && fresh.isNotEmpty() && auth.session.value == session) notifier.notify(
                NotificationCategory.RADAR,
                if (today > 0) "HOJE: $today oportunidade(s) do radar encerram hoje" else "Novas oportunidades no radar",
                (if (today > 0) "$today encerram hoje · " else "") + "${fresh.size} resultado(s) novo(s) para ${radar.name}. Abra o radar para revisar.",
                companyId = company,
            )
            prefs.edit().putStringSet(seenKey, (ids + previous).take(2000).toSet()).apply()
        }
        return Outcome(allFound, allFresh)
    }

    companion object {
        const val PREFS = "personal_alerts"

        /** Notas por IA por radar a cada execução em segundo plano. */
        const val AI_LIMIT = 25
    }
}
