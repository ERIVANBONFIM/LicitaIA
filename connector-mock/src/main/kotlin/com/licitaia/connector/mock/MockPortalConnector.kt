package com.licitaia.connector.mock

import com.licitaia.connector.api.BidSubmission
import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.HumanConfirmation
import com.licitaia.connector.api.LiveSessionHandle
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalBidState
import com.licitaia.connector.api.PortalConnector
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.connector.api.PortalMessage
import com.licitaia.connector.api.ProposalPreparation
import com.licitaia.connector.api.SubmissionResult
import com.licitaia.connector.api.TenderDetails
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.LiveSessionSpec
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Tender
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Controles exclusivos do portal MOCK (não fazem parte do contrato [PortalConnector]).
 * Representam o que, num portal real, aconteceria DENTRO do site: o CAPTCHA aparecer e o
 * usuário resolvê-lo manualmente. O app nunca resolve CAPTCHA — apenas informa ao mock que
 * o usuário confirmou a resolução.
 */
interface MockSessionControl {
    /** Demonstração: faz o portal mock exibir um CAPTCHA na sessão. false = sessão inexistente/encerrada. */
    fun triggerCaptcha(sessionId: String): Boolean
    /** O usuário confirmou que resolveu o CAPTCHA manualmente. */
    fun confirmCaptchaResolved(sessionId: String)
    /** Retoma a disputa mock a partir do estado persistido (após reabrir o app). Chamar antes de coletar eventos. */
    fun restoreState(sessionId: String, bestBid: Double?, ourLastBid: Double?, remainingSeconds: Int?, captchaPending: Boolean)
}

/** Parâmetros de comportamento do mock por portal (ritmo dos concorrentes, CAPTCHA, mensagens). */
internal data class PortalProfile(
    /** Multiplicador do intervalo entre lances dos concorrentes (maior = disputa mais lenta). */
    val pace: Double,
    /** Dispara um CAPTCHA de demonstração 45–75 s após abrir a sessão. */
    val demoCaptcha: Boolean,
    /** Probabilidade por segundo de um CAPTCHA espontâneo. */
    val captchaChancePerSecond: Double,
    val messageEverySeconds: IntRange,
    val durationSeconds: IntRange,
    val auctioneer: String,
)

class MockPortalConnector internal constructor(
    override val portal: Portal,
    override val capabilities: ConnectorCapabilities,
    private val profile: PortalProfile,
) : PortalConnector, MockSessionControl {

    private val liveSessions = ConcurrentHashMap<String, MockLiveSession>()
    private val authenticatedKeys = ConcurrentHashMap.newKeySet<String>()
    private val protocolCounter = AtomicLong(1000)

    // ------------------------------------------------------------ autenticação (simulada)

    override suspend fun authenticate(credentials: PortalCredentials): PortalAuthResult {
        delay(600)
        if (credentials.username.isBlank() || credentials.secret.isBlank()) {
            return PortalAuthResult.Failure("Informe usuário e senha do portal.")
        }
        // Conexão de demonstração: nenhuma credencial é enviada a lugar algum.
        authenticatedKeys += credentials.sessionKey
        return PortalAuthResult.Success(credentials.sessionKey)
    }

    override suspend fun restoreSession(sessionKey: String): PortalAuthResult {
        if (sessionKey.isBlank()) return PortalAuthResult.Failure("Sessão inválida.")
        if (!capabilities.supportsPersistentSession && sessionKey !in authenticatedKeys) {
            return PortalAuthResult.Failure("Este portal não mantém sessão persistente: conecte novamente.")
        }
        authenticatedKeys += sessionKey
        return PortalAuthResult.Success(sessionKey)
    }

    override suspend fun logout(sessionKey: String) {
        authenticatedKeys -= sessionKey
    }

    // ------------------------------------------------------------ consulta (catálogo local)

    override suspend fun listOpportunities(filter: OpportunityFilter): List<Opportunity> {
        delay(250)
        return MockCatalog.search(portal, filter)
    }

    override suspend fun getTenderDetails(opportunityId: String): TenderDetails? =
        MockCatalog.find(portal, opportunityId)?.let(MockCatalog::details)

    override suspend fun getMessages(liveSessionId: String): List<PortalMessage> =
        liveSessions[liveSessionId]?.messagesSnapshot().orEmpty()

    // ------------------------------------------------------------ proposta (sempre simulada)

    override suspend fun prepareProposal(proposal: Proposal, tender: Tender): ProposalPreparation {
        val warnings = buildList {
            add("Modo SIMULAÇÃO: nenhum dado será transmitido ao portal ${portal.displayName}.")
            if (tender.portal != portal) add("A licitação pertence a outro portal (${tender.portal.displayName}).")
            if (proposal.status != ProposalStatus.APROVADA) add("A proposta ainda não está aprovada internamente.")
            if (proposal.items.isEmpty()) add("A proposta não possui itens.")
            if (proposal.totalValue > tender.estimatedValue) add("O valor total está acima do valor estimado do edital.")
            if (tender.proposalDeadline < System.currentTimeMillis()) add("O prazo de envio de propostas já terminou.")
        }
        return ProposalPreparation(
            portal = portal,
            tenderNumber = tender.number,
            itemLabel = proposal.items.firstOrNull()?.description ?: "Lote único",
            companyId = proposal.companyId,
            totalValue = proposal.totalValue,
            warnings = warnings,
        )
    }

    override suspend fun submitProposal(preparation: ProposalPreparation, confirmation: HumanConfirmation): SubmissionResult {
        delay(500)
        if (preparation.portal != portal) return SubmissionResult.Failure("Proposta preparada para outro portal.")
        if (confirmation.userName.isBlank()) return SubmissionResult.Failure("Confirmação humana ausente.")
        if (preparation.totalValue <= 0.0) return SubmissionResult.Failure("Valor total inválido.")
        return SubmissionResult.Simulated("SIM-${portal.shortName.uppercase().filter { it.isLetterOrDigit() }}-${protocolCounter.incrementAndGet()}")
    }

    // ------------------------------------------------------------ disputa ao vivo (mock, isolada por sessão)

    override suspend fun openLiveSession(sessionId: String, spec: LiveSessionSpec): LiveSessionHandle {
        require(spec.portal == portal) { "Sessão de ${spec.portal.displayName} aberta no conector ${portal.displayName}" }
        liveSessions.remove(sessionId)?.shutdown()
        val session = MockLiveSession(sessionId, spec, profile) { liveSessions.remove(sessionId, it) }
        liveSessions[sessionId] = session
        return session
    }

    override suspend fun readCurrentBidState(sessionId: String): PortalBidState? = liveSessions[sessionId]?.snapshot()

    override suspend fun submitBid(
        sessionId: String, itemLabel: String, value: Double, confirmation: HumanConfirmation?,
    ): BidSubmission {
        val session = liveSessions[sessionId]
            ?: return BidSubmission.Rejected("Sessão não encontrada neste portal.")
        // SIMULADO: o lance só existe dentro da disputa mock desta sessão.
        return session.submitOurBid(itemLabel, value)
    }

    override suspend fun pauseAutomation(sessionId: String) {
        // Nada a fazer no mock: a automação vive no app, não no portal.
    }

    // ------------------------------------------------------------ MockSessionControl

    override fun triggerCaptcha(sessionId: String): Boolean = liveSessions[sessionId]?.forceCaptcha() ?: false

    override fun confirmCaptchaResolved(sessionId: String) {
        liveSessions[sessionId]?.clearCaptcha()
    }

    override fun restoreState(
        sessionId: String, bestBid: Double?, ourLastBid: Double?, remainingSeconds: Int?, captchaPending: Boolean,
    ) {
        liveSessions[sessionId]?.restore(bestBid, ourLastBid, remainingSeconds, captchaPending)
    }
}

/**
 * Registro dos 4 conectores mock. Uma instância = um conjunto independente de portais simulados.
 * O PNCP NUNCA é simulado aqui: ele possui conector real em `connector-pncp` (consulta pública).
 */
class MockConnectorRegistry : ConnectorRegistry {

    private val connectors: Map<Portal, MockPortalConnector> = Portal.entries
        .filter { it != Portal.PNCP }
        .associateWith { portal -> MockPortalConnector(portal, capabilitiesOf(portal), profileOf(portal)) }

    override fun get(portal: Portal): PortalConnector = connectors[portal]
        ?: throw IllegalArgumentException("O portal ${portal.displayName} não possui conector mock; use o conector real.")

    override fun all(): List<PortalConnector> = connectors.values.toList()

    private companion object {
        const val NOT_MOCKED = "O PNCP não é simulado: a consulta pública é feita pelo conector real (connector-pncp)."
        const val NO_API = "Não há API oficial validada para ações autenticadas (login, proposta, lances): este conector é MOCK."
        const val SIMULATED = "Envio de proposta e de lances é sempre SIMULADO; nada é transmitido ao portal."
        const val ASSUMPTION = "As capacidades declaradas são premissas de demonstração, pendentes de validação técnica e contratual."
        const val CATALOG = "Oportunidades, editais, concorrentes e mensagens são dados fictícios locais."

        fun capabilitiesOf(portal: Portal): ConnectorCapabilities = when (portal) {
            Portal.PNCP -> throw IllegalArgumentException(NOT_MOCKED)
            Portal.COMPRAS_GOV -> ConnectorCapabilities(
                supportsWebView = true, supportsOfficialApi = false, supportsBrowserAutomation = false,
                supportsPersistentSession = true, requiresMfa = true, mayShowCaptcha = true, isMock = true,
                limitations = listOf(
                    NO_API, SIMULATED,
                    "O acesso real exige conta gov.br com verificação em duas etapas, feita somente pelo usuário.",
                    "Dados abertos de consulta pública não foram integrados nesta versão.",
                    CATALOG, ASSUMPTION,
                ),
            )
            Portal.BLL -> ConnectorCapabilities(
                supportsWebView = true, supportsOfficialApi = false, supportsBrowserAutomation = false,
                supportsPersistentSession = true, requiresMfa = false, mayShowCaptcha = true, isMock = true,
                limitations = listOf(
                    NO_API, SIMULATED,
                    "Automação de navegador não habilitada: depende de autorização formal da plataforma.",
                    CATALOG, ASSUMPTION,
                ),
            )
            Portal.LICITANET -> ConnectorCapabilities(
                supportsWebView = true, supportsOfficialApi = false, supportsBrowserAutomation = false,
                supportsPersistentSession = false, requiresMfa = false, mayShowCaptcha = true, isMock = true,
                limitations = listOf(
                    NO_API, SIMULATED,
                    "Sessão não persistente: é preciso conectar novamente a cada abertura do app.",
                    "Este mock exibe um CAPTCHA de demonstração cerca de 1 minuto após abrir a sessão de disputa.",
                    CATALOG, ASSUMPTION,
                ),
            )
            Portal.PORTAL_COMPRAS_PUBLICAS -> ConnectorCapabilities(
                supportsWebView = true, supportsOfficialApi = false, supportsBrowserAutomation = false,
                supportsPersistentSession = true, requiresMfa = false, mayShowCaptcha = false, isMock = true,
                limitations = listOf(
                    NO_API, SIMULATED,
                    "O mock deste portal não simula CAPTCHA; o portal real pode exigi-lo.",
                    "Caso o portal bloqueie a WebView, o acesso deve ser feito pelo navegador (Custom Tabs).",
                    CATALOG, ASSUMPTION,
                ),
            )
        }

        fun profileOf(portal: Portal): PortalProfile = when (portal) {
            Portal.PNCP -> throw IllegalArgumentException(NOT_MOCKED)
            Portal.COMPRAS_GOV -> PortalProfile(
                pace = 1.0, demoCaptcha = false, captchaChancePerSecond = 1.0 / 2400,
                messageEverySeconds = 90..200, durationSeconds = 1080..1500, auctioneer = "Pregoeiro(a)",
            )
            Portal.BLL -> PortalProfile(
                pace = 1.6, demoCaptcha = false, captchaChancePerSecond = 1.0 / 3000,
                messageEverySeconds = 140..280, durationSeconds = 900..1320, auctioneer = "Pregoeiro(a)",
            )
            Portal.LICITANET -> PortalProfile(
                pace = 0.7, demoCaptcha = true, captchaChancePerSecond = 0.0,
                messageEverySeconds = 110..240, durationSeconds = 960..1380, auctioneer = "Pregoeiro(a)",
            )
            Portal.PORTAL_COMPRAS_PUBLICAS -> PortalProfile(
                pace = 1.25, demoCaptcha = false, captchaChancePerSecond = 0.0,
                messageEverySeconds = 120..260, durationSeconds = 900..1260, auctioneer = "Agente de contratação",
            )
        }
    }
}
