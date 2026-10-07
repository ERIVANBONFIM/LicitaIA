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

    /**
     * Portais cujas oportunidades esta fonte consegue listar. Por padrão, só o próprio [portal]; o PNCP
     * agrega publicações de várias plataformas (Compras.gov.br, Licitanet, BLL, PCP...) e as classifica
     * pelo campo `usuarioNome`, então cobre todos eles.
     */
    val searchablePortals: Set<Portal> get() = setOf(portal)

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

/**
 * Falha de conector que carrega o status HTTP da fonte. Usada pelos repositórios para aplicar backoff
 * em 429 (limite de requisições) e 5xx sem depender das classes concretas de cada conector.
 */
interface HttpStatusFailure {
    val httpStatus: Int?
}

/** Documento público oficial de uma contratação (PDF do edital ou anexo) com URL de download direto. */
data class OfficialDocument(
    val title: String,
    val url: String,
    /** Tipo informado pela fonte (ex.: "Edital", "Termo de Referência"); null quando ausente. */
    val typeName: String?,
    val role: Role,
    /** Id do tipo na fonte (PNCP `/v1/tipos-documentos`: 2 = Edital, 4 = Termo de Referência, 7 = ETP...). */
    val typeId: Long? = null,
) {
    enum class Role { EDITAL, ANEXO }
}

/**
 * Fonte capaz de listar os documentos oficiais de uma contratação pelo número de controle PNCP
 * (`<cnpj>-1-<sequencial>/<ano>`). Devolve o edital principal primeiro e depois os anexos relevantes;
 * lista vazia = nenhum documento publicado. Falhas de rede sobem como exceção.
 */
interface OfficialDocumentSource {
    suspend fun officialEditalDocuments(pncpControlNumber: String): List<OfficialDocument>
}

/**
 * Fonte dos ITENS oficiais de uma contratação (nº, descrição, quantidade, unidade, valor estimado) pelo número de
 * controle PNCP (`<cnpj>-1-<sequencial>/<ano>`). Lista vazia = nenhum item publicado. Falhas de rede sobem como exceção.
 */
interface OfficialItemsSource {
    suspend fun officialItems(pncpControlNumber: String): List<com.licitaia.domain.proposal.OfficialTenderItem>

    /** Órgão/unidade compradora (razão social, CNPJ, UASG, município/UF); null quando a fonte não informa. */
    suspend fun officialBuyer(pncpControlNumber: String): com.licitaia.domain.proposal.OfficialBuyer? = null
}

/**
 * TODOS os arquivos públicos de uma contratação (`/arquivos` do PNCP, sem seleção) e o link oficial da compra no
 * sistema de origem (`linkSistemaOrigem`). Falhas de rede sobem como exceção.
 */
interface OfficialFilesSource {
    suspend fun officialFiles(pncpControlNumber: String): List<com.licitaia.domain.model.OfficialFile>

    /** `linkSistemaOrigem` da contratação (página da compra no portal de origem); null quando não publicado. */
    suspend fun originUrl(pncpControlNumber: String): String?
}

/**
 * Situação oficial atual de uma contratação pelo número de controle PNCP (situação + datas), usada na conferência diária
 * das licitações acompanhadas (suspensa, revogada/anulada, adiada, resultado). null = não encontrada.
 */
interface OfficialStatusSource {
    suspend fun officialStatus(pncpControlNumber: String): com.licitaia.domain.model.OfficialStatus?
}

/**
 * Triagem local aplicada pela fonte ANTES de etapas caras (ex.: consultar o prazo de cada candidata no PNCP):
 * palavras do radar/busca, UF, valor, modalidade e heurística de relevância. true = candidata.
 */
fun interface OpportunityScreen {
    fun accept(opportunity: Opportunity): Boolean

    companion object {
        val ACCEPT_ALL: OpportunityScreen = OpportunityScreen { true }
    }
}

/** Resultado de [ScreenedOpportunitySource.listScreened]: oportunidades devolvidas + funil (lidas/candidatas/abertas). */
data class ScreenedListing(
    val opportunities: List<Opportunity>,
    val diagnostics: com.licitaia.domain.model.SourceDiagnostics,
)

/**
 * Fonte que lê a janela inteira da API (sem busca por texto no servidor) e precisa de uma triagem local para chegar
 * a poucas candidatas antes de enriquecê-las. O repositório usa [listScreened] em vez de
 * [PortalConnector.listOpportunities] quando o conector implementa esta interface.
 */
interface ScreenedOpportunitySource {
    suspend fun listScreened(filter: OpportunityFilter, screen: OpportunityScreen): ScreenedListing
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
