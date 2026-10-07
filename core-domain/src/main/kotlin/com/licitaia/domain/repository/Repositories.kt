package com.licitaia.domain.repository

import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.PinVerification
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiConfig
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AiScoreUpdate
import com.licitaia.domain.model.AiScoringRequest
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
import com.licitaia.domain.model.SearchOutcome
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

    /**
     * Vincula a identidade Google a uma conta LOCAL já existente com o mesmo e-mail, exigindo a senha
     * dessa conta (PBKDF2). Mantém perfil e empresas; grava provedor GOOGLE + `externalId`; audita CADASTRO.
     * Chamado após [loginWithGoogle] falhar com [com.licitaia.domain.auth.NoLocalLinkException].
     */
    suspend fun linkGoogleToLocal(identity: GoogleIdentity, password: String, remember: Boolean): Result<AuthSession> =
        Result.failure(UnsupportedOperationException("Vínculo Google indisponível."))

    /**
     * Modo demonstração ISOLADO: cria (uma vez por aparelho) a empresa demo, o usuário demo (ADMIN só dela)
     * e dados de exemplo, e entra sem senha. Usuários reais nunca veem esse espaço e vice-versa.
     */
    suspend fun loginDemo(): Result<AuthSession> =
        Result.failure(UnsupportedOperationException("Demonstração indisponível."))

    /** "Sair da demonstração": apaga a empresa demo, todos os dados por companyId e o usuário demo; encerra a sessão. */
    suspend fun exitDemo(): Result<Unit> = Result.failure(UnsupportedOperationException("Demonstração indisponível."))

    /** "Reiniciar demonstração": apaga e recria o espaço demo, mantendo a sessão na nova empresa demo. */
    suspend fun resetDemo(): Result<AuthSession> = Result.failure(UnsupportedOperationException("Demonstração indisponível."))

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

    /** Igual a [search], com a contagem por fonte (diagnóstico "PNCP 120 · Compras.gov.br 35"). */
    suspend fun searchWithSources(companyId: Long, filter: OpportunityFilter): Result<SearchOutcome> =
        search(companyId, filter).map { SearchOutcome(it) }

    /** Igual a [runRadar], com a contagem por fonte. */
    suspend fun runRadarWithSources(radarId: Long): Result<SearchOutcome> =
        runRadar(radarId).map { SearchOutcome(it) }

    /**
     * Igual a [searchWithSources], mas só com o que já está salvo no aparelho (abertura da tela: nenhuma consulta às
     * fontes; a atualização diária baixa as novas). Sem nada salvo, consulta as fontes. Padrão: [searchWithSources].
     */
    suspend fun searchCached(companyId: Long, filter: OpportunityFilter): Result<SearchOutcome> =
        searchWithSources(companyId, filter)

    /** Igual a [runRadarWithSources], só com o que já está salvo no aparelho. Padrão: [runRadarWithSources]. */
    suspend fun runRadarCached(radarId: Long): Result<SearchOutcome> = runRadarWithSources(radarId)

    /**
     * Notas por IA para os candidatos de [request] (lotes de até 25, um de cada vez, cancelável). Cada emissão traz os
     * itens já avaliados (score = nota da IA). Sem rede ou com falha do provedor, emite [AiScoreUpdate.failure] e para:
     * os demais itens continuam com a nota heurística. Padrão: nada a avaliar.
     */
    fun scoreWithAi(request: AiScoringRequest): Flow<AiScoreUpdate> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Radar com notas por IA aplicadas antes do score mínimo (uso em segundo plano): avalia até [aiLimit] candidatos
     * ainda sem nota, ignorando [skipIds] (já vistos). Padrão: igual a [runRadar].
     */
    suspend fun runRadarWithAi(radarId: Long, aiLimit: Int, skipIds: Set<String> = emptySet()): Result<List<ScoredOpportunity>> =
        runRadar(radarId)

    suspend fun getOpportunity(id: String): Opportunity?
    /** Total de oportunidades encontradas pelos radares ativos da empresa. */
    fun observeRadarMatchCount(companyId: Long): Flow<Int>

    /**
     * Andamento da sincronização das fontes de leitura completa (ex.: "Sincronizando Compras.gov.br… 4.500 linhas");
     * null quando nenhuma está em andamento. Padrão: nunca sincroniza.
     */
    fun observeSourceSync(): Flow<String?> = kotlinx.coroutines.flow.flowOf(null)
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

    /**
     * Baixa TODOS os documentos publicados no PNCP para a contratação (edital, termo de referência, anexos, ETP...) e
     * monta a base de perguntas/análise: texto único com `=== DOCUMENTO: <tipo — título> (página N) ===` em cada
     * página, na ordem Edital → TR → Anexos → Minuta → ETP (ver `EditalDocumentBase`). O primeiro documento fica como
     * PDF do edital. Extração + OCR local quando escaneado, com limites de documentos, tamanho e páginas.
     */
    suspend fun fetchOfficialEdital(tenderId: Long): Result<EditalImportResult>

    /**
     * Motivo da última falha ao baixar o edital oficial (ex.: disparo automático do "Tenho Interesse" sem rede);
     * null quando não houve falha ou após um download bem-sucedido. Mantido só em memória.
     */
    fun observeOfficialEditalError(tenderId: Long): Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

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

    /**
     * Gera um rascunho (versão 1 ou próxima versão) a partir dos ITENS OFICIAIS atuais da licitação (PNCP; fallback
     * dados abertos do Compras.gov.br) com preços calculados; a IA sugere prazo, validade e observações. Sem itens
     * oficiais, os itens vêm da IA (comportamento anterior).
     */
    suspend fun generateDraft(tenderId: Long): Result<ProposalDraftOutcome>

    /** Relê os itens oficiais do edital e recalcula os valores de um rascunho (preserva marca/fabricante/modelo). */
    suspend fun refreshOfficialValues(id: Long): Result<ProposalDraftOutcome>
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

/** Resultado da montagem/atualização da proposta pelos itens oficiais. */
data class ProposalDraftOutcome(
    val proposal: Proposal,
    /** Fonte dos itens oficiais ("PNCP", "Compras.gov.br"); null = itens montados pela IA (sem itens oficiais). */
    val officialSource: String? = null,
    /** Nº dos itens com orçamento sigiloso (preço a definir). */
    val confidentialItems: List<Int> = emptyList(),
    /** true = preços pela faixa da análise da IA; false = desconto padrão sobre o estimado. */
    val pricedByAnalysis: Boolean = false,
    /** Aviso para o usuário (ex.: "itens oficiais indisponíveis: montado pela IA"). */
    val warning: String? = null,
)

/**
 * "Pergunte ao edital": perguntas livres respondidas pela IA ativa SÓ com base no texto do edital da licitação (e nos
 * itens oficiais quando ajudam). Toda pergunta é gravada — inclusive as que falham (status ERRO, com "Tentar de novo").
 * Leitura exige a empresa ativa; perguntar, refazer e apagar exigem a permissão de analisar editais.
 */
interface EditalQuestionRepository {
    /** Histórico da licitação, mais antiga primeiro. Vazio para licitação de outra empresa. */
    fun observeQuestions(tenderId: Long): Flow<List<com.licitaia.domain.edital.EditalQuestion>>

    /** Ids das perguntas sendo respondidas agora (só em memória; o resto com status PENDENTE foi interrompido). */
    fun observeAnswering(): Flow<Set<Long>> = kotlinx.coroutines.flow.flowOf(emptySet())

    /** Grava a pergunta e pede a resposta à IA; a falha também fica gravada (status ERRO). */
    suspend fun ask(tenderId: Long, question: String): Result<com.licitaia.domain.edital.EditalQuestion>

    /** Pede de novo a resposta de uma pergunta já gravada (erro ou interrompida), sobrescrevendo a resposta. */
    suspend fun retry(questionId: Long): Result<com.licitaia.domain.edital.EditalQuestion>

    suspend fun delete(questionId: Long): Result<Unit>
}

/** Itens oficiais da licitação (aba "Itens"), com cache em memória por sessão do app. */
interface TenderItemsRepository {
    /** [refresh] = true ignora o cache e consulta as fontes oficiais de novo. */
    suspend fun officialItems(tenderId: Long, refresh: Boolean = false): Result<TenderItems>
}

/** Resultado da consulta dos itens oficiais. */
data class TenderItems(
    val items: List<com.licitaia.domain.proposal.OfficialTenderItem>,
    /** Fonte dos itens ("PNCP", "Compras.gov.br"); null quando não há itens. */
    val source: String?,
    val fetchedAt: Long,
    /** Explicação quando não há itens (sem número PNCP, nada publicado...). */
    val message: String? = null,
    /** true = devolvido do cache desta sessão, sem nova consulta. */
    val fromCache: Boolean = false,
    /** Órgão e unidade compradora (fonte oficial; senão os dados do cadastro da licitação). */
    val buyer: com.licitaia.domain.proposal.OfficialBuyer? = null,
) {
    /** Soma dos totais estimados conhecidos (itens sigilosos ficam de fora). */
    val knownTotal: Double get() = items.filterNot { it.confidentialBudget }.sumOf { it.referenceTotal ?: 0.0 }
    val confidentialCount: Int get() = items.count { it.confidentialBudget || it.referenceTotal == null }
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

    /**
     * Última URL da área logada do portal (já sanitizada pela camada web, sem tokens), por empresa+portal,
     * para reabrir o navegador interno onde o usuário estava. null = nenhuma. Apagada em [clearWebSession].
     */
    suspend fun lastWebUrl(companyId: Long, portal: Portal): String? = null
    suspend fun saveLastWebUrl(companyId: Long, portal: Portal, url: String) {}

    /** Auditoria CONEXAO_PORTAL de "Manter sessão ativa" (ligar/desligar/queda). Nunca inclui URLs. */
    suspend fun auditKeepAlive(companyId: Long, portal: Portal, details: String) {}
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

/**
 * Configuração dos provedores de IA por (provedor, empresa). Cada empresa pode ter a própria configuração
 * (modelo, URL, chave/conta); sem ela, vale o "padrão do aparelho" (companyId 0). As leituras resolvem
 * sempre pela empresa ativa com esse fallback. O parâmetro `deviceDefault = true` nas alterações edita o
 * padrão do aparelho em vez da configuração da empresa ativa.
 */
interface AiConfigRepository {
    /**
     * Uma configuração por provedor (inclui MOCK), resolvida para a empresa ativa com fallback para o padrão
     * do aparelho (ver [AiConfig.companyScoped]). Com [deviceDefault] = true, mostra só o padrão do aparelho.
     */
    fun observeConfigs(deviceDefault: Boolean = false): Flow<List<AiConfig>>
    fun observeActive(): Flow<AiProviderType>
    suspend fun setActive(provider: AiProviderType)
    /**
     * [apiKey] null = mantém a chave atual. A chave é cifrada com o Android Keystore.
     * [cloudProject] null = mantém; vazio = limpa (ID do projeto Google Cloud, só Gemini via conta).
     */
    suspend fun saveConfig(provider: AiProviderType, model: String, baseUrl: String, apiKey: String?, cloudProject: String? = null, deviceDefault: Boolean = false)
    suspend fun clearApiKey(provider: AiProviderType, deviceDefault: Boolean = false)
    /** Troca o modo de autenticação (chave de API ↔ conta). Só provedores com [AiProviderType.supportsOAuth] aceitam OAUTH. */
    suspend fun setAuthMode(provider: AiProviderType, mode: AiAuthMode, deviceDefault: Boolean = false)
    /**
     * Registra a autorização concedida: e-mail da conta (exibição) e projeto Google Cloud opcional.
     * O token de acesso é guardado pelo autorizador no cofre, nunca passa por aqui. Ativa o modo OAUTH.
     */
    suspend fun saveOAuth(provider: AiProviderType, account: String, cloudProject: String?, deviceDefault: Boolean = false)
    /** Remove token e conta autorizada; volta ao modo chave de API. */
    suspend fun clearOAuth(provider: AiProviderType, deviceDefault: Boolean = false)
    /**
     * "Usar padrão do aparelho": apaga a configuração própria da empresa ativa para o provedor
     * (linha, chave e token no cofre). A empresa passa a usar o padrão do aparelho.
     */
    suspend fun useDeviceDefault(provider: AiProviderType) {}
    /** Faz uma chamada mínima ao provedor e devolve uma mensagem de status. */
    suspend fun testConnection(provider: AiProviderType): Result<String>
    /**
     * Provedor que o app inteiro usa agora (conta logada ou chave; preferência explícita da empresa; global).
     * MOCK = nenhum provedor real disponível (ou empresa de demonstração): análises ficam heurísticas.
     */
    fun observeEffective(): Flow<AiProviderType> = observeActive()
}
