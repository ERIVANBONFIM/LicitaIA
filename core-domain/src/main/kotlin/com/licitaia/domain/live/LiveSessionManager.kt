package com.licitaia.domain.live

import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.BidResult
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.LiveSessionSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orquestra os pregões ao vivo. Cada pregão roda em uma sessão lógica ISOLADA:
 * estado, credencial, item/lote, fila de eventos, log, CAPTCHA e estratégia próprios.
 *
 * Regras invioláveis da implementação:
 * - nunca enviar lance abaixo do piso;
 * - nunca atuar em item/sessão diferente do configurado;
 * - nunca operar com CAPTCHA/MFA pendente (pausa SOMENTE a sessão afetada);
 * - nunca resolver/contornar CAPTCHA — apenas aguardar o usuário;
 * - nunca continuar após erro crítico;
 * - registrar cada ação (BidEvent + auditoria).
 */
interface LiveSessionManager {
    /** Sessões da empresa ativa. */
    val sessions: StateFlow<List<LiveSession>>

    fun observeSession(sessionId: String): Flow<LiveSession?>
    /** Log próprio da sessão, mais recente primeiro. */
    fun observeEvents(sessionId: String): Flow<List<BidEvent>>

    /**
     * Restaura as sessões ativas da empresa; se não houver nenhuma, cria as 3 sessões
     * de demonstração (Compras.gov, BLL, Licitanet). Chamado após o login/troca de empresa.
     */
    suspend fun restoreOrSeed(companyId: Long)

    suspend fun openSession(spec: LiveSessionSpec): String
    suspend fun closeSession(sessionId: String)

    suspend fun startRobot(sessionId: String)
    suspend fun pauseRobot(sessionId: String, reason: String = "Pausa manual")
    suspend fun resumeRobot(sessionId: String)
    suspend fun stopRobot(sessionId: String)
    /** "PARAR E ASSUMIR": encerra a automação e deixa a sessão em controle manual. */
    suspend fun takeOverManually(sessionId: String)

    /** Lance manual do operador (simulado). Valida piso/item/CAPTCHA antes. */
    suspend fun submitManualBid(sessionId: String, value: Double): BidResult

    /** Altera estratégia/piso/margem. Mudança de piso gera auditoria específica. */
    suspend fun updateRule(sessionId: String, rule: BidRule)

    /** Usuário confirmou que resolveu o CAPTCHA/MFA manualmente no portal. */
    suspend fun confirmCaptchaResolved(sessionId: String)
    /** Apenas demonstração: força um evento de CAPTCHA no portal mock. */
    suspend fun triggerDemoCaptcha(sessionId: String)

    suspend fun respondAuthorization(sessionId: String, authorizationId: String, approved: Boolean)

    /** Botão de emergência: pausa todas as automações, mantém as sessões abertas. Retorna quantas pausou. */
    suspend fun pauseAllRobots(reason: String): Int
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
