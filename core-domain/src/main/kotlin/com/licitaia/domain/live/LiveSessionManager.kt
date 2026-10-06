package com.licitaia.domain.live

import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveSessionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orquestra os pregões acompanhados em MODO ASSISTIDO. Não existe API oficial de pregão: o usuário
 * opera no portal (navegador interno com sessão salva) e registra aqui lances, posição e cronômetro;
 * o app calcula margem/piso, sugere o próximo lance, alerta e audita. Cada pregão é uma sessão
 * lógica ISOLADA (estado, log, cronômetro e regra próprios), restrita à empresa ativa.
 *
 * Regras invioláveis da implementação:
 * - nenhum lance ou ação é enviado a portal algum;
 * - nenhum registro de lance abaixo do piso é aceito;
 * - CAPTCHA/MFA são sempre resolvidos pelo usuário no portal;
 * - toda operação gera [BidEvent] e auditoria.
 */
interface LiveSessionManager {
    /** Sessões da empresa ativa. */
    val sessions: StateFlow<List<LiveSession>>

    /** true = alertas (margem, piso, cronômetro) silenciados em todas as sessões da empresa. */
    val alertsMuted: StateFlow<Boolean>

    fun observeSession(sessionId: String): Flow<LiveSession?>
    /** Log próprio da sessão, mais recente primeiro. */
    fun observeEvents(sessionId: String): Flow<List<BidEvent>>

    /** Restaura as sessões abertas da empresa. Não cria sessões fictícias. Chamado após login/troca de empresa. */
    suspend fun restoreOrSeed(companyId: Long)

    /** Cria uma sessão assistida (status AGUARDANDO, robô INATIVO) e devolve o id. */
    suspend fun openSession(spec: LiveSessionSpec): String

    /** Encerra o acompanhamento sem resultado (ou remove uma sessão já encerrada). */
    suspend fun closeSession(sessionId: String)

    /** Marca a disputa como iniciada (AGUARDANDO → EM_DISPUTA). */
    suspend fun startDispute(sessionId: String)

    /** Registra o lance que o USUÁRIO já deu no portal. Valida piso via BidRuleEngine; bloqueia abaixo do piso. */
    suspend fun recordOurBid(sessionId: String, value: Double): BidResult

    /** Registra o melhor lance de um concorrente visto no portal. */
    suspend fun recordCompetitorBid(sessionId: String, value: Double, alias: String = "Concorrente"): BidResult

    /** Posição informada pelo usuário (1 = vencendo; 0 = sem lance). */
    suspend fun setPosition(sessionId: String, position: Int)

    /** Inicia a contagem regressiva informada pelo usuário (tempo aleatório / iminência do fechamento). */
    suspend fun startTimer(sessionId: String, seconds: Int)
    suspend fun stopTimer(sessionId: String)

    /**
     * Encerra com resultado: gera CompetitionRecord e atualiza o status da licitação vinculada
     * (VENCIDA/PERDIDA). A sessão permanece listada como ENCERRADA até ser removida.
     */
    suspend fun finishSession(sessionId: String, won: Boolean, finalValue: Double)

    /** Altera estratégia/piso/margem. Mudança de piso exige APROVAR_PISO e gera auditoria específica. */
    suspend fun updateRule(sessionId: String, rule: BidRule)

    /** Silencia/reativa os alertas de todas as sessões da empresa (mantém as sessões). Retorna quantas sessões abertas foram afetadas. */
    suspend fun setAlertsMuted(muted: Boolean): Int
}

/**
 * Persistência das sessões ao vivo (Room). Implementado em core-data; consumido pelo motor
 * de sessões para restaurar pregões ativos após reabrir o app.
 */
interface LiveSessionStore {
    /** Sessões não encerradas da empresa. */
    suspend fun loadSessions(companyId: Long): List<LiveSession>
    suspend fun saveSession(session: LiveSession)
    suspend fun deleteSession(sessionId: String)
    suspend fun appendEvent(event: BidEvent)
    /** Log da sessão, mais recente primeiro. */
    fun observeEvents(sessionId: String): Flow<List<BidEvent>>
}
