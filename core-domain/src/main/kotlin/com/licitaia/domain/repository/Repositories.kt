package com.licitaia.domain.repository

import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.PinVerification
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiConfig
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AppNotification
import com.licitaia.domain.model.AppSettings
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditEvent
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.EditalImportProgress
import com.licitaia.domain.model.EditalImportResult
import com.licitaia.domain.model.EditalSource
import com.licitaia.domain.model.IntegrityReport
import com.licitaia.domain.model.ManualTenderDraft
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Opportunity
import com.licitaia.domain.model.OpportunityFilter
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalSession
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Radar
import com.licitaia.domain.model.ScoredOpportunity
import com.licitaia.domain.model.Tender
import com.licitaia.domain.model.TenderAnalysis
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Autenticação LOCAL do LicitaIA (não é a autenticação dos portais). */
interface AuthRepository {
    /** Sessão corrente; null = deslogado. */
    val session: StateFlow<AuthSession?>

    /** Tenta restaurar a sessão lembrada. Deve ser chamada na abertura do app. */
    suspend fun restoreSession(): AuthSession?
    suspend fun login(email: String, password: String, companyId: Long?, remember: Boolean): Result<AuthSession>
    suspend fun register(name: String, email: String, password: String, companyName: String, cnpj: String): Result<AuthSession>

    /**
     * Login com identidade Google já verificada pelo cliente. Identifica (cria ou reconhece) o usuário,
     * mas NÃO concede empresa nem perfil de administrador: sem vínculo, falha com
     * [com.licitaia.domain.auth.NoCompanyAccessException] até que um administrador o vincule.
     */
    suspend fun loginWithGoogle(identity: GoogleIdentity, remember: Boolean): Result<AuthSession>
    suspend fun createGoogleCompany(identity: GoogleIdentity, companyName: String, cnpj: String, remember: Boolean): Result<AuthSession> =
        Result.failure(UnsupportedOperationException("Cadastro Google indisponível."))
    suspend fun logout()
    suspend fun switchCompany(companyId: Long): Result<AuthSession>

    /** PIN opcional de desbloqueio; null remove. */
    suspend fun setPin(pin: String?)
    suspend fun hasPin(): Boolean

    /** true somente se o PIN confere e não há bloqueio temporário ativo. Erros contam para o bloqueio. */
    suspend fun verifyPin(pin: String): Boolean

    /** Variante detalhada: informa tentativas restantes ou o tempo de bloqueio (progressivo, persistido). */
    suspend fun verifyPinDetailed(pin: String): PinVerification =
        if (verifyPin(pin)) PinVerification.Success else PinVerification.Wrong(remainingAttempts = 0)
}

interface CompanyRepository {
    fun observeCompanies(): Flow<List<Company>>
    suspend fun getCompany(id: Long): Company?
    suspend fun upsertCompany(company: Company): Long
    suspend fun deleteCompany(id: Long)

    fun observeUsers(companyId: Long): Flow<List<UserProfile>>
    /** Usuários reais identificados (ex.: via Google) que ainda não têm empresa — para o administrador vincular. */
    fun observeUnassignedUsers(): Flow<List<UserProfile>>
    /** [password] só é necessário ao criar usuário ou trocar senha. */
    suspend fun upsertUser(user: UserProfile, password: String? = null): Long
    suspend fun deleteUser(id: Long)
}

interface RadarRepository {
    fun observeRadars(companyId: Long): Flow<List<Radar>>
    suspend fun getRadar(id: Long): Radar?
    suspend fun upsert(radar: Radar): Long
    suspend fun delete(id: Long)
}

interface OpportunityRepository {
    /** Busca nos conectores dos portais (mock no MVP), calcula score e marca interesse. */
    suspend fun search(companyId: Long, filter: OpportunityFilter): Result<List<ScoredOpportunity>>
    suspend fun runRadar(radarId: Long): Result<List<ScoredOpportunity>>
    suspend fun getOpportunity(id: String): Opportunity?
    /** Total de oportunidades encontradas pelos radares ativos da empresa. */
    fun observeRadarMatchCount(companyId: Long): Flow<Int>
}

interface TenderRepository {
    fun observeTenders(companyId: Long): Flow<List<Tender>>
    fun observeTender(id: Long): Flow<Tender?>
    fun observeAnalysis(tenderId: Long): Flow<TenderAnalysis?>

    /**
     * Fluxo "Tenho Interesse": salva a licitação, registra o edital e dispara a análise de IA
     * em segundo plano. Idempotente por (empresa, oportunidade). Retorna o id da licitação.
     */
    suspend fun markInterest(companyId: Long, opportunity: Opportunity): Long
    suspend fun removeInterest(tenderId: Long)
    suspend fun findByOpportunity(companyId: Long, opportunityId: String): Tender?

    /**
     * (Re)gera a análise com o provedor de IA ativo usando o texto real do edital quando existir.
     * Sem fallback silencioso: falha do provedor real = falha da análise (auditada).
     */
    suspend fun analyze(tenderId: Long): Result<TenderAnalysis>
    suspend fun updateStatus(tenderId: Long, status: TenderStatus)
    suspend fun toggleChecklistItem(tenderId: Long, index: Int)

    /**
     * Cadastra manualmente uma licitação real (edital obtido fora do app). Não dispara análise:
     * o usuário anexa o edital e analisa quando quiser. Falha se já houver o mesmo edital na empresa.
     */
    suspend fun createManual(companyId: Long, draft: ManualTenderDraft): Result<Long>

    /**
     * Anexa o edital (PDF, texto colado ou OCR do PDF já importado) à licitação, extrai o texto e o
     * guarda em armazenamento privado. PDF sem camada de texto (`scanned = true`) passa automaticamente
     * pelo OCR local; `ocr = true` no resultado indica texto reconhecido (conferir trechos importantes).
     */
    suspend fun attachEdital(tenderId: Long, source: EditalSource): Result<EditalImportResult>

    /** Progresso da importação/OCR em andamento para a licitação; null quando não há importação. */
    fun observeEditalImportProgress(tenderId: Long): Flow<EditalImportProgress?>

    /** Texto do edital armazenado; null quando não há texto. */
    fun observeEditalText(tenderId: Long): Flow<String?>
}

interface DocumentRepository {
    fun observeDocuments(companyId: Long): Flow<List<CompanyDocument>>
    suspend fun getDocument(id: Long): CompanyDocument?
    suspend fun upsert(document: CompanyDocument): Long
    suspend fun delete(id: Long)
    /** Documentos vencidos ou vencendo em até [withinDays] dias. */
    fun observeExpiringCount(companyId: Long, withinDays: Int = 30): Flow<Int>
}

interface ProposalRepository {
    /** Todas as versões da proposta de uma licitação, mais recente primeiro. */
    fun observeProposals(tenderId: Long): Flow<List<Proposal>>
    fun observeProposal(id: Long): Flow<Proposal?>
    suspend fun getProposal(id: Long): Proposal?

    /** Gera um rascunho com a IA (versão 1 ou próxima versão). */
    suspend fun generateDraft(tenderId: Long): Result<Proposal>
    /** Salva [proposal] como NOVA versão (version + 1, status RASCUNHO). Retorna o id. */
    suspend fun saveNewVersion(proposal: Proposal): Long
    suspend fun update(proposal: Proposal)
    suspend fun submitForReview(id: Long)
    suspend fun approve(id: Long)
    suspend fun reject(id: Long, reason: String)
    suspend fun attachPdf(id: Long, path: String)

    /**
     * Envio SIMULADO ao portal. Só pode ser chamado após confirmação humana explícita na UI.
     * Nenhum dado sai do aparelho no MVP.
     */
    suspend fun simulateSubmission(id: Long): Result<Unit>
    fun observePendingApprovals(companyId: Long): Flow<Int>
}

/** Gera o PDF da proposta em armazenamento privado do app e devolve o caminho absoluto. */
interface ProposalPdfGenerator {
    suspend fun generate(proposal: Proposal, tender: Tender, company: Company): Result<String>
}

interface PortalRepository {
    fun observeSessions(companyId: Long): Flow<List<PortalSession>>
    /**
     * Obsoleto: o app não coleta usuário/senha. O login é feito pelo usuário no navegador interno
     * ([markSessionDetected] registra o resultado). Implementações devem retornar falha.
     */
    @Deprecated("Login manual no navegador interno; o app não coleta credenciais.", level = DeprecationLevel.WARNING)
    suspend fun connect(companyId: Long, portal: Portal, username: String, secret: String): Result<PortalSession>
    suspend fun disconnect(companyId: Long, portal: Portal)

    /**
     * Resultado da heurística de sessão do navegador interno (sem ler credenciais):
     * loggedIn=true → CONECTADO com lastLoginAt=agora; false → SESSAO_EXPIRADA (mantém lastLoginAt).
     * Só audita quando o status muda. `username` fica vazio (login manual).
     */
    suspend fun markSessionDetected(companyId: Long, portal: Portal, loggedIn: Boolean)

    /** "Sair do portal": status DESCONECTADO + auditoria. Os cookies são removidos pela camada web. */
    suspend fun clearWebSession(companyId: Long, portal: Portal)
    fun capabilities(portal: Portal): ConnectorCapabilities
}

interface MessageRepository {
    fun observeMessages(companyId: Long): Flow<List<AuctioneerMessage>>
    fun observeMessage(id: Long): Flow<AuctioneerMessage?>
    fun observeUnreadCount(companyId: Long): Flow<Int>
    suspend fun insert(message: AuctioneerMessage): Long
    suspend fun markRead(id: Long)
    /** Preenche aiSummary e suggestedReply usando a IA ativa. */
    suspend fun generateAiAssist(id: Long): Result<AuctioneerMessage>
    suspend fun saveReplyDraft(id: Long, text: String)
    suspend fun approveReply(id: Long)
    /** Envio SIMULADO; exige confirmação humana prévia na UI. */
    suspend fun sendReplySimulated(id: Long): Result<Unit>
}

interface NotificationRepository {
    fun observeNotifications(companyId: Long): Flow<List<AppNotification>>
    fun observeUnreadCount(companyId: Long): Flow<Int>
    suspend fun insert(notification: AppNotification): Long
    suspend fun markRead(id: Long)
    suspend fun markAllRead(companyId: Long)
    suspend fun clear(companyId: Long)
}

/**
 * Notificador unificado: persiste em [NotificationRepository], publica push local no canal
 * Android correspondente e emite em [inAppAlerts] (toast/banner dentro do app).
 */
interface AppNotifier {
    val inAppAlerts: SharedFlow<AppNotification>

    suspend fun notify(
        category: NotificationCategory,
        title: String,
        body: String,
        critical: Boolean = false,
        route: String? = null,
        sessionId: String? = null,
        companyId: Long? = null,
    )

    /** Remove as notificações de sistema ligadas a uma sessão (ex.: CAPTCHA resolvido). */
    fun cancelSessionAlerts(sessionId: String)
}

interface AuditRepository {
    /** [companyId] null = todas as empresas. Mais recente primeiro. */
    fun observeEvents(companyId: Long?): Flow<List<AuditEvent>>

    /** Registra um evento preenchendo timestamp, usuário e empresa da sessão atual. */
    suspend fun record(
        action: AuditAction,
        result: AuditResult = AuditResult.SUCESSO,
        origin: AuditOrigin = AuditOrigin.USUARIO,
        portal: Portal? = null,
        tenderNumber: String? = null,
        item: String? = null,
        previousValue: String? = null,
        newValue: String? = null,
        reason: String? = null,
        details: String = "",
    )

    /**
     * Percorre a trilha em ordem de inserção e confere o hash encadeado de cada evento
     * (SHA-256 de `prevHash` + campos essenciais). Eventos anteriores ao encadeamento (sem hash)
     * são contados em [IntegrityReport.unhashed]; a verificação começa no primeiro evento com hash.
     */
    suspend fun verifyIntegrity(): IntegrityReport
}

interface CompetitionRepository {
    fun observeRecords(companyId: Long): Flow<List<CompetitionRecord>>

    /** Registra o resultado real de um pregão (vitória ou derrota) para a empresa. Retorna o id. */
    suspend fun insert(record: CompetitionRecord): Long

    /** Remove um registro do histórico da empresa. */
    suspend fun delete(id: Long)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface AiConfigRepository {
    /** Uma configuração por provedor (inclui MOCK). */
    fun observeConfigs(): Flow<List<AiConfig>>
    fun observeActive(): Flow<AiProviderType>
    suspend fun setActive(provider: AiProviderType)
    /**
     * [apiKey] null = mantém a chave atual. A chave é cifrada com o Android Keystore.
     * [cloudProject] null = mantém; vazio = limpa (ID do projeto Google Cloud, só Gemini via conta).
     */
    suspend fun saveConfig(provider: AiProviderType, model: String, baseUrl: String, apiKey: String?, cloudProject: String? = null)
    suspend fun clearApiKey(provider: AiProviderType)
    /** Troca o modo de autenticação (chave de API ↔ conta). Só provedores com [AiProviderType.supportsOAuth] aceitam OAUTH. */
    suspend fun setAuthMode(provider: AiProviderType, mode: AiAuthMode)
    /**
     * Registra a autorização concedida: e-mail da conta (exibição) e projeto Google Cloud opcional.
     * O token de acesso é guardado pelo autorizador no cofre, nunca passa por aqui. Ativa o modo OAUTH.
     */
    suspend fun saveOAuth(provider: AiProviderType, account: String, cloudProject: String?)
    /** Remove token e conta autorizada; volta ao modo chave de API. */
    suspend fun clearOAuth(provider: AiProviderType)
    /** Faz uma chamada mínima ao provedor e devolve uma mensagem de status. */
    suspend fun testConnection(provider: AiProviderType): Result<String>
}
