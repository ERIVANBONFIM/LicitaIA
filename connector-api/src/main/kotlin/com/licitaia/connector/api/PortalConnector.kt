package com.licitaia.connector.api

import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Tender
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de integração com um portal de licitações (SDD §10).
 *
 * IMPORTANTE:
 * - Nunca presumir API oficial para ações autenticadas. Sem documentação oficial/validação
 *   contratual, o portal fica atrás de um conector MOCK.
 * - Conectores NUNCA resolvem/contornam CAPTCHA ou MFA: apenas sinalizam
 *   [PortalLiveEvent.CaptchaRequired]/[PortalLiveEvent.MfaRequired] e aguardam o usuário.
 * - [submitProposal] e [submitBid] exigem [HumanConfirmation] quando a ação é vinculante.
 */
interface PortalConnector {
    val portal: Portal
    val capabilities: ConnectorCapabilities

    suspend fun authenticate(credentials: PortalCredentials): PortalAuthResult
    suspend fun restoreSession(sessionKey: String): PortalAuthResult
    suspend fun listOpportunities(filter: OpportunityFilter): List<Opportunity>
    suspend fun getTenderDetails(opportunityId: String): TenderDetails?
    suspend fun getMessages(liveSessionId: String): List<PortalMessage>
    suspend fun prepareProposal(proposal: Proposal, tender: Tender): ProposalPreparation
    suspend fun submitProposal(preparation: ProposalPreparation, confirmation: HumanConfirmation): SubmissionResult

    /** Abre uma sessão de disputa ISOLADA; cada chamada devolve um handle com fila de eventos própria. */
    suspend fun openLiveSession(sessionId: String, spec: LiveSessionSpec): LiveSessionHandle
    suspend fun readCurrentBidState(sessionId: String): PortalBidState?
    suspend fun submitBid(sessionId: String, itemLabel: String, value: Double, confirmation: HumanConfirmation?): BidSubmission
    suspend fun pauseAutomation(sessionId: String)
    suspend fun logout(sessionKey: String)
}

/** Registro dos conectores disponíveis (um por portal). */
interface ConnectorRegistry {
    fun get(portal: Portal): PortalConnector
    fun all(): List<PortalConnector>
}

data class PortalCredentials(
    val companyId: Long,
    val username: String,
    /** Mantido só em memória durante a chamada; persistência é responsabilidade do cofre (Keystore). */
    val secret: String,
    val sessionKey: String,
)

sealed interface PortalAuthResult {
    data class Success(val sessionKey: String) : PortalAuthResult
    data object MfaRequired : PortalAuthResult
    data object CaptchaRequired : PortalAuthResult
    data class Failure(val message: String) : PortalAuthResult
}

data class TenderDetails(
    val opportunity: Opportunity,
    /** Texto (ou trecho) do edital, quando o portal o disponibiliza publicamente. */
    val editalText: String?,
    val items: List<String>,
)

data class PortalMessage(
    val sender: String,
    val body: String,
    val timestamp: Long,
    val urgent: Boolean,
    val responseDeadline: Long? = null,
)

data class ProposalPreparation(
    val portal: Portal,
    val tenderNumber: String,
    val itemLabel: String,
    val companyId: Long,
    val totalValue: Double,
    val warnings: List<String>,
)

/** Prova de confirmação humana explícita para ações vinculantes. */
data class HumanConfirmation(
    val userName: String,
    val confirmedAt: Long,
)

sealed interface SubmissionResult {
    data class Simulated(val protocol: String) : SubmissionResult
    data class Failure(val message: String) : SubmissionResult
}

interface LiveSessionHandle {
    val sessionId: String
    /** Fila de eventos própria desta sessão. */
    val events: Flow<PortalLiveEvent>
    suspend fun close()
}

sealed interface PortalLiveEvent {
    data class CompetitorBid(val alias: String, val value: Double) : PortalLiveEvent
    data class TimerTick(val remainingSeconds: Int) : PortalLiveEvent
    data class Message(val message: PortalMessage) : PortalLiveEvent
    /** O portal exibiu um CAPTCHA: a automação desta sessão deve pausar até resolução manual. */
    data object CaptchaRequired : PortalLiveEvent
    data object MfaRequired : PortalLiveEvent
    data class Error(val message: String, val critical: Boolean) : PortalLiveEvent
    data class Closed(val winnerAlias: String?, val finalValue: Double?) : PortalLiveEvent
}

data class PortalBidState(
    val sessionId: String,
    val itemLabel: String,
    val bestBid: Double?,
    val ourLastBid: Double?,
    val ourPosition: Int,
    val competitors: Int,
    val remainingSeconds: Int?,
    val captchaPending: Boolean,
    val closed: Boolean,
)

sealed interface BidSubmission {
    data class Accepted(val value: Double, val position: Int) : BidSubmission
    data class Rejected(val reason: String) : BidSubmission
    data object CaptchaRequired : BidSubmission
}
