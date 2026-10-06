package com.licitaia.domain.model

// Convenções: datas em epoch millis (Long); valores monetários em BRL (Double).

// ---------------------------------------------------------------- Empresa / usuários

data class Company(
    val id: Long = 0,
    val name: String,
    val tradeName: String,
    val cnpj: String,
    val segment: Segment,
    val uf: String,
    val city: String,
    /** Provedor de IA preferido da empresa; null = usa o provedor global ativo. */
    val preferredAi: AiProviderType? = null,
)

data class UserProfile(
    val id: Long = 0,
    val name: String,
    val email: String,
    val role: UserRole,
    /** Empresas às quais o usuário tem acesso. Vazio = identificado, mas sem acesso (aguardando vínculo). */
    val companyIds: List<Long>,
    val provider: AuthProvider = AuthProvider.LOCAL,
    /** Usuário de demonstração (seed). Nunca se mistura com contas reais. */
    val demo: Boolean = false,
) {
    val hasCompanyAccess: Boolean get() = companyIds.isNotEmpty()
}

data class AuthSession(
    val user: UserProfile,
    val activeCompany: Company,
)

// ---------------------------------------------------------------- Radar / oportunidades

data class Radar(
    val id: Long = 0,
    val companyId: Long,
    val name: String,
    val segment: Segment,
    val keywords: List<String> = emptyList(),
    val forbiddenKeywords: List<String> = emptyList(),
    /** Ignorado quando [allPortals] = true. */
    val portals: List<Portal> = emptyList(),
    val allPortals: Boolean = true,
    val ufs: List<String> = emptyList(),
    val region: String? = null,
    val agency: String? = null,
    val modality: Modality? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val minScore: Int = 0,
    val cnae: String? = null,
    val preferredObject: String? = null,
    val requireLocalSupport: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long = 0,
)

data class Opportunity(
    /** Identificador único estável (ex.: "COMPRAS_GOV:90012/2026"). */
    val id: String,
    val portal: Portal,
    val number: String,
    val agency: String,
    val objectDescription: String,
    val modality: Modality,
    val segment: Segment,
    val uf: String,
    val city: String,
    val estimatedValue: Double,
    val publishedAt: Long,
    val proposalDeadline: Long,
    val sessionAt: Long,
    val requiresLocalSupport: Boolean = false,
    val keywords: List<String> = emptyList(),
    val editalUrl: String? = null,
)

data class ScoredOpportunity(
    val opportunity: Opportunity,
    /** Score de aderência 0..100. */
    val score: Int,
    val interested: Boolean,
)

data class OpportunityFilter(
    val query: String = "",
    val portals: Set<Portal> = emptySet(),
    val ufs: Set<String> = emptySet(),
    val segment: Segment? = null,
    val modality: Modality? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val minScore: Int = 0,
)

// ---------------------------------------------------------------- Licitação de interesse / análise

data class Tender(
    val id: Long = 0,
    val companyId: Long,
    val opportunityId: String,
    val portal: Portal,
    val number: String,
    val agency: String,
    val objectDescription: String,
    val modality: Modality,
    val segment: Segment,
    val uf: String,
    val city: String,
    val estimatedValue: Double,
    val proposalDeadline: Long,
    val sessionAt: Long,
    val status: TenderStatus = TenderStatus.INTERESSE,
    val editalRegistered: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Cópia privada do PDF do edital (filesDir/editais/{companyId}/{tenderId}.pdf), quando importado. */
    val editalPdfPath: String? = null,
    /** Texto extraído/colado do edital (filesDir/editais/{companyId}/{tenderId}.txt), quando existe. */
    val editalTextPath: String? = null,
    /** Tamanho do texto do edital em caracteres (0 = sem texto). */
    val editalChars: Int = 0,
    /** Páginas do PDF importado; null quando o texto foi colado ou não há PDF. */
    val editalPages: Int? = null,
    /** PDF sem camada de texto (escaneado). Com [hasEditalText] = true, o texto veio do OCR local (conferir trechos). */
    val editalScanned: Boolean = false,
) {
    /** Há texto real do edital para enviar à IA. */
    val hasEditalText: Boolean get() = editalTextPath != null && editalChars > 0

    /** Licitação cadastrada manualmente (sem oportunidade de portal). */
    val isManual: Boolean get() = opportunityId.startsWith(MANUAL_OPPORTUNITY_PREFIX)

    companion object {
        const val MANUAL_OPPORTUNITY_PREFIX = "MANUAL:"
    }
}

/** Dados informados pelo usuário ao cadastrar uma licitação manualmente (edital real). */
data class ManualTenderDraft(
    val portal: Portal,
    val number: String,
    val agency: String,
    val objectDescription: String,
    val modality: Modality,
    val segment: Segment,
    val uf: String,
    val city: String,
    val estimatedValue: Double,
    val proposalDeadline: Long,
    val sessionAt: Long,
    val editalUrl: String? = null,
) {
    /** Mensagens de validação em pt-BR; vazio = válido. */
    fun validate(): List<String> = buildList {
        if (number.trim().length < 3) add("Informe o número do edital (ex.: 90012/2026).")
        if (agency.trim().length < 3) add("Informe o órgão licitante.")
        if (objectDescription.trim().length < 10) add("Descreva o objeto com pelo menos 10 caracteres.")
        if (uf.trim().length != 2 || !uf.trim().all { it.isLetter() }) add("Informe a UF com 2 letras (ex.: CE).")
        if (city.trim().isEmpty()) add("Informe a cidade.")
        if (estimatedValue.isNaN() || estimatedValue < 0.0) add("Valor estimado inválido.")
        if (proposalDeadline <= 0L) add("Informe o prazo final de envio das propostas.")
        if (sessionAt <= 0L) add("Informe a data da sessão pública.")
        if (proposalDeadline > 0L && sessionAt > 0L && sessionAt < proposalDeadline) add("A sessão pública não pode ocorrer antes do prazo de propostas.")
        val url = editalUrl?.trim().orEmpty()
        if (url.isNotEmpty() && !(url.startsWith("https://") || url.startsWith("http://"))) add("A URL do edital deve começar com http:// ou https://.")
    }

    val isValid: Boolean get() = validate().isEmpty()

    /** Identificador estável por empresa: evita cadastrar o mesmo edital duas vezes. */
    fun opportunityId(): String {
        val normalized = number.trim().uppercase().replace(Regex("\\s+"), "")
        return "${Tender.MANUAL_OPPORTUNITY_PREFIX}${portal.name}:$normalized"
    }
}

/** Origem do texto do edital anexado a uma licitação. */
sealed interface EditalSource {
    /** `content://` do PDF escolhido pelo usuário (a UI já pediu permissão persistente). */
    data class Pdf(val uri: String) : EditalSource

    /** Texto colado/digitado pelo usuário. */
    data class Text(val text: String) : EditalSource

    /** Reconhecimento de texto (OCR no aparelho) sobre o PDF já importado da licitação. */
    data object Ocr : EditalSource
}

data class EditalImportResult(
    val chars: Int,
    val pages: Int?,
    /** true = PDF sem camada de texto (escaneado). Com [ocr] = true o texto foi reconhecido no aparelho. */
    val scanned: Boolean,
    val storedPath: String,
    /** true = o texto salvo veio do OCR local (ML Kit); pode conter erros de reconhecimento. */
    val ocr: Boolean = false,
)

/** Progresso da importação do edital (cópia, extração e OCR página a página). */
data class EditalImportProgress(
    val tenderId: Long,
    val stage: Stage,
    /** Páginas já reconhecidas (OCR); 0 nas demais etapas. */
    val page: Int = 0,
    /** Total de páginas a reconhecer (OCR); 0 quando desconhecido. */
    val totalPages: Int = 0,
) {
    enum class Stage(val label: String) {
        COPIANDO("Copiando o PDF"),
        EXTRAINDO("Extraindo o texto"),
        OCR("Reconhecendo texto"),
    }
}

data class ExtractedEdital(
    val objectDescription: String,
    val agency: String,
    val portal: String,
    val number: String,
    val modality: String,
    val estimatedValue: Double,
    val proposalDeadline: Long,
    val openingAt: Long,
    val sessionAt: Long,
    val installationDeadline: String,
    val sla: String,
    val technicalRequirements: List<String>,
    val requiredDocuments: List<DocumentType>,
    val guarantees: List<String>,
    val penalties: List<String>,
    val attestationRequirements: List<String>,
    val certificationRequirements: List<String>,
)

data class FitScore(
    val overall: Int,
    val technical: Int,
    val documentary: Int,
    val financial: Int,
    val estimatedMarginPct: Double,
    val operationalRisk: RiskLevel,
    val documentaryRisk: RiskLevel,
    val contractualRisk: RiskLevel,
    /** 0..100 — quão confortável é o prazo. */
    val deadlineFit: Int,
    /** Média histórica de concorrentes em objetos similares. */
    val historicalCompetitors: Int,
    /** 0..100. */
    val geographicFit: Int,
    /** Investimento estimado necessário (BRL). */
    val investmentNeeded: Double,
)

data class PriceRange(val min: Double, val suggested: Double, val max: Double)

data class ChecklistItem(
    val title: String,
    val done: Boolean,
    val critical: Boolean = false,
    val relatedDocument: DocumentType? = null,
)

data class TenderAnalysis(
    val tenderId: Long,
    val providerName: String,
    val generatedAt: Long,
    val summary: String,
    val extracted: ExtractedEdital,
    val fit: FitScore,
    val recommendation: Recommendation,
    val justification: String,
    val criticalPoints: List<String>,
    val priceRange: PriceRange,
    val checklist: List<ChecklistItem>,
    /**
     * Nomes dos campos efetivamente preenchidos pelo modelo de IA (ex.: "summary", "requiredDocuments").
     * Vazio = análise 100% heurística local (provedor de demonstração ou campos todos rejeitados).
     */
    val aiFields: Set<String> = emptySet(),
) {
    /** true quando nenhum campo veio de um modelo de IA: a UI deve rotular como heurística. */
    val heuristicOnly: Boolean get() = aiFields.isEmpty()
}

// ---------------------------------------------------------------- Documentos

data class CompanyDocument(
    val id: Long = 0,
    val companyId: Long,
    val type: DocumentType,
    val title: String,
    val issuer: String? = null,
    val issuedAt: Long? = null,
    val expiresAt: Long? = null,
    /** content:// ou caminho local do anexo. */
    val attachmentUri: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val createdAt: Long = 0,
) {
    fun status(now: Long, soonDays: Int = 30): DocumentStatus = when {
        attachmentUri == null && issuedAt == null -> DocumentStatus.AUSENTE
        expiresAt == null -> DocumentStatus.VALIDO
        expiresAt < now -> DocumentStatus.VENCIDO
        expiresAt - now <= soonDays * DAY_MS -> DocumentStatus.VENCE_EM_BREVE
        else -> DocumentStatus.VALIDO
    }

    fun daysToExpire(now: Long): Long? = expiresAt?.let { (it - now) / DAY_MS }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

// ---------------------------------------------------------------- Proposta

data class ProposalItem(
    val description: String,
    val unit: String,
    val quantity: Double,
    val unitPrice: Double,
) {
    val total: Double get() = quantity * unitPrice
}

data class Proposal(
    val id: Long = 0,
    val tenderId: Long,
    val companyId: Long,
    val version: Int = 1,
    val items: List<ProposalItem>,
    val deliveryDays: Int,
    val validityDays: Int = 60,
    val notes: String = "",
    val status: ProposalStatus = ProposalStatus.RASCUNHO,
    val pdfPath: String? = null,
    val createdBy: String,
    val createdAt: Long = 0,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val rejectionReason: String? = null,
) {
    val totalValue: Double get() = items.sumOf { it.total }
}

// ---------------------------------------------------------------- Portais

data class ConnectorCapabilities(
    val supportsWebView: Boolean,
    val supportsOfficialApi: Boolean,
    val supportsBrowserAutomation: Boolean,
    val supportsPersistentSession: Boolean,
    val requiresMfa: Boolean,
    val mayShowCaptcha: Boolean,
    val isMock: Boolean,
    val limitations: List<String>,
)

data class PortalSession(
    val id: Long = 0,
    val companyId: Long,
    val portal: Portal,
    /** Vazio no login manual: o app não coleta nem guarda usuário/senha. */
    val username: String = "",
    val status: PortalConnectionStatus,
    val lastLoginAt: Long? = null,
    /** Chave do cookie/session store isolado (empresa + portal). */
    val sessionKey: String,
)

// ---------------------------------------------------------------- Pregão ao vivo / robô

data class BidRule(
    val mode: RobotMode = RobotMode.SUPERVISIONADO,
    val strategy: BidStrategy = BidStrategy.CONSERVADORA,
    val initialPrice: Double,
    /** Piso: o robô NUNCA envia lance abaixo deste valor. */
    val floorPrice: Double,
    /** Custo total estimado — base do cálculo de margem. */
    val costPrice: Double,
    /** Redução absoluta por lance (BRL). */
    val reductionValue: Double,
    val minMarginPct: Double,
    /** Perda máxima aceitável em BRL abaixo do custo (0 = não aceita perda). */
    val lossLimit: Double = 0.0,
    val minIntervalSeconds: Int = 5,
    /** Pede autorização humana quando o próximo lance ficar a menos de X% do piso. */
    val authorizationThresholdPct: Double = 5.0,
    /** Sempre true no MVP: nenhum lance real é enviado. */
    val simulation: Boolean = true,
) {
    fun marginPct(price: Double): Double =
        if (price <= 0.0) 0.0 else (price - costPrice) / price * 100.0
}

data class BidAuthorization(
    val id: String,
    val sessionId: String,
    val proposedValue: Double,
    val reason: String,
    val requestedAt: Long,
)

data class LiveSession(
    val id: String,
    val companyId: Long,
    val tenderId: Long? = null,
    val portal: Portal,
    val tenderNumber: String,
    val agency: String,
    val itemLabel: String,
    val objectDescription: String,
    val status: LiveStatus,
    val robotStatus: RobotStatus,
    /** Nossa posição (1 = vencendo). 0 = sem lance. */
    val position: Int,
    val competitors: Int,
    val ourLastBid: Double?,
    val bestBid: Double?,
    val rule: BidRule,
    val remainingSeconds: Int?,
    val captchaPending: Boolean = false,
    val captchaSince: Long? = null,
    val pendingAuthorization: BidAuthorization? = null,
    val unreadMessages: Int = 0,
    val lastError: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    /** Cronômetro do modo assistido em contagem (estado de runtime; não persiste). */
    val timerRunning: Boolean = false,
) {
    /** Margem no nosso último lance (ou no preço inicial se ainda não houve lance). */
    val currentMarginPct: Double get() = rule.marginPct(ourLastBid ?: rule.initialPrice)
    val isWinning: Boolean get() = position == 1
    val robotRunning: Boolean
        get() = robotStatus == RobotStatus.ATIVO || robotStatus == RobotStatus.AGUARDANDO_AUTORIZACAO
    val isOpen: Boolean get() = status != LiveStatus.ENCERRADA && status != LiveStatus.ERRO
}

data class LiveSessionSpec(
    val companyId: Long,
    val tenderId: Long? = null,
    val portal: Portal,
    val tenderNumber: String,
    val agency: String,
    val itemLabel: String,
    val objectDescription: String,
    val rule: BidRule,
    val competitors: Int = 4,
)

data class BidEvent(
    val id: Long = 0,
    val sessionId: String,
    val timestamp: Long,
    val type: BidEventType,
    val value: Double? = null,
    /** "Robô", nome do usuário, "Concorrente 2", "Portal", "Sistema"... */
    val actor: String,
    val description: String,
)

sealed interface BidResult {
    data class Accepted(val value: Double, val position: Int) : BidResult
    data class Rejected(val reason: String) : BidResult
}

// ---------------------------------------------------------------- Mensagens

data class AuctioneerMessage(
    val id: Long = 0,
    val companyId: Long,
    val sessionId: String? = null,
    val portal: Portal,
    val tenderNumber: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val read: Boolean = false,
    val urgent: Boolean = false,
    val responseDeadline: Long? = null,
    val aiSummary: String? = null,
    val suggestedReply: String? = null,
    val replyDraft: String? = null,
    val replyStatus: ReplyStatus = ReplyStatus.NENHUMA,
    val repliedAt: Long? = null,
)

// ---------------------------------------------------------------- Notificações

data class AppNotification(
    val id: Long = 0,
    val companyId: Long?,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val createdAt: Long,
    val read: Boolean = false,
    val critical: Boolean = false,
    /** Rota de navegação interna ao tocar. */
    val route: String? = null,
    val sessionId: String? = null,
)

// ---------------------------------------------------------------- Auditoria

data class AuditEvent(
    val id: Long = 0,
    val timestamp: Long,
    val user: String,
    val companyId: Long?,
    val companyName: String,
    val portal: String? = null,
    val tenderNumber: String? = null,
    val item: String? = null,
    val action: AuditAction,
    val previousValue: String? = null,
    val newValue: String? = null,
    val reason: String? = null,
    val origin: AuditOrigin,
    val result: AuditResult,
    val details: String = "",
    /** Hash do evento anterior na cadeia ("" no primeiro evento encadeado ou em eventos legados). */
    val prevHash: String = "",
    /** SHA-256 (hex) de `prevHash` + campos essenciais; "" em eventos anteriores ao encadeamento. */
    val hash: String = "",
)

/** Resultado da verificação da cadeia de hashes da auditoria. */
data class IntegrityReport(
    /** Total de eventos na trilha. */
    val total: Int,
    /** Eventos encadeados cujo hash e vínculo com o anterior conferiram. */
    val verified: Int,
    /** id do primeiro evento cujo hash/encadeamento não confere; null = cadeia íntegra. */
    val firstBroken: Long?,
    /** Eventos anteriores ao encadeamento (sem hash), não verificáveis. */
    val unhashed: Int = 0,
) {
    val ok: Boolean get() = firstBroken == null
}

// ---------------------------------------------------------------- Concorrência (dados públicos / internos)

data class CompetitionRecord(
    val id: Long = 0,
    val companyId: Long,
    val portal: Portal,
    val tenderNumber: String,
    val agency: String,
    val segment: Segment,
    val objectSummary: String,
    val date: Long,
    val competitors: Int,
    val estimatedValue: Double,
    val closingValue: Double,
    val ourFinalBid: Double,
    val won: Boolean,
    val ourMarginPct: Double,
    val bidsCount: Int,
    /** Resumo do comportamento observado (público). */
    val behavior: String,
)

// ---------------------------------------------------------------- IA / configurações

data class AiConfig(
    val provider: AiProviderType,
    val model: String,
    val baseUrl: String,
    /** A chave nunca trafega no modelo — apenas a indicação de que existe no Keystore. */
    val hasApiKey: Boolean,
    val authMode: AiAuthMode = AiAuthMode.API_KEY,
    /** E-mail da conta autorizada (só exibição). null = não autorizado. O token nunca sai do cofre. */
    val oauthAccount: String? = null,
    /** ID do projeto Google Cloud para `x-goog-user-project` (opcional). */
    val cloudProject: String? = null,
) {
    /** Há autorização OAuth registrada (conta conhecida + token no cofre). */
    val hasOAuth: Boolean get() = oauthAccount != null

    /** Credencial utilizável no modo escolhido. */
    val isConfigured: Boolean
        get() = provider == AiProviderType.MOCK || when (authMode) {
            AiAuthMode.API_KEY -> hasApiKey
            AiAuthMode.OAUTH -> hasOAuth
        }
}

data class AppSettings(
    /** Intervalo de repetição do alerta de CAPTCHA em minutos: 1, 3, 5, 10, 15 ou 0 (desativado). */
    val captchaRepeatMinutes: Int = 3,
    val captchaVibrate: Boolean = true,
    val hapticFeedback: Boolean = true,
    val pushEnabled: Boolean = true,
    val biometricLock: Boolean = false,
    val sessionTimeoutMinutes: Int = 15,
    val screenshotProtection: Boolean = false,
    val rememberSession: Boolean = true,
    val activeAiProvider: AiProviderType = AiProviderType.MOCK,
    val showBottomBar: Boolean = true,
) {
    companion object {
        val CAPTCHA_REPEAT_OPTIONS = listOf(1, 3, 5, 10, 15, 0)
    }
}
