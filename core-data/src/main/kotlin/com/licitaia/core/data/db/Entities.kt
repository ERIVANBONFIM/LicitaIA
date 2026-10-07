package com.licitaia.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.licitaia.domain.model.AiAuthMode
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.BidEventType
import com.licitaia.domain.model.BidStrategy
import com.licitaia.domain.model.DocumentType
import com.licitaia.domain.model.LiveStatus
import com.licitaia.domain.model.Modality
import com.licitaia.domain.model.NotificationCategory
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Recommendation
import com.licitaia.domain.model.ReplyStatus
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.model.RobotStatus
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.TenderStatus
import com.licitaia.domain.model.UserRole

// Enums são persistidos pelo Room como texto (nome da constante).

@Entity(tableName = "companies")
data class CompanyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tradeName: String,
    val cnpj: String,
    val segment: Segment,
    val uf: String,
    val city: String,
    val preferredAi: AiProviderType?,
    /** Versão 6: empresa do espaço de demonstração. Default casa com DatabaseMigrations.FROM_5_TO_6. */
    @ColumnInfo(defaultValue = "0") val demo: Boolean = false,
)

@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val email: String,
    val role: UserRole,
    val companyIds: List<Long>,
    /** Hash PBKDF2 (nunca a senha). Vazio = usuário sem senha definida (não consegue entrar por senha). */
    val passwordHash: String,
    /** [com.licitaia.domain.model.AuthProvider] pelo nome. */
    val provider: String = "LOCAL",
    /** Identificador estável no provedor externo (claim `sub` do Google). Nunca o token. */
    val externalId: String? = null,
    /** Usuário de demonstração (seed). */
    val demo: Boolean = false,
)

@Entity(tableName = "radars", indices = [Index("companyId")])
data class RadarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val name: String,
    val segment: Segment,
    val keywords: List<String>,
    val forbiddenKeywords: List<String>,
    val portals: List<String>,
    val allPortals: Boolean,
    val ufs: List<String>,
    val region: String?,
    val agency: String?,
    val modality: Modality?,
    val minValue: Double?,
    val maxValue: Double?,
    val startDate: Long?,
    val endDate: Long?,
    val minScore: Int,
    val cnae: String?,
    val preferredObject: String?,
    val requireLocalSupport: Boolean,
    val active: Boolean,
    val createdAt: Long,
    /** Mostrar dispensas sem disputa (contratação direta). Versão 9. */
    @androidx.room.ColumnInfo(defaultValue = "0") val showNoDispute: Boolean = false,
)

/** Cache local das oportunidades vistas nos portais (modo offline). */
@Entity(tableName = "opportunities")
data class OpportunityEntity(
    @PrimaryKey val id: String,
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
    val requiresLocalSupport: Boolean,
    val keywords: List<String>,
    val editalUrl: String?,
    val cachedAt: Long,
    /** Plataforma de origem informada pelo PNCP (usuarioNome). Versão 7. */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val platformName: String? = null,
    /** Dispensa sem disputa (contratação direta). Versão 9. */
    @androidx.room.ColumnInfo(defaultValue = "0") val noDispute: Boolean = false,
)

@Entity(
    tableName = "tenders",
    indices = [Index(value = ["companyId", "opportunityId"], unique = true), Index("companyId")],
)
data class TenderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val status: TenderStatus,
    val editalRegistered: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    // Versão 3: edital real (PDF/texto) em armazenamento privado. Defaults casam com DatabaseMigrations.FROM_2_TO_3.
    @ColumnInfo(defaultValue = "NULL") val editalPdfPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val editalTextPath: String? = null,
    @ColumnInfo(defaultValue = "0") val editalChars: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val editalPages: Int? = null,
    @ColumnInfo(defaultValue = "0") val editalScanned: Boolean = false,
)

/** Análise completa serializada (edital extraído, fit, checklist...) + colunas de consulta rápida. */
@Entity(tableName = "tender_analyses")
data class TenderAnalysisEntity(
    @PrimaryKey val tenderId: Long,
    val providerName: String,
    val generatedAt: Long,
    val recommendation: Recommendation,
    val overallScore: Int,
    val payload: TenderAnalysisDto,
)

@Entity(tableName = "documents", indices = [Index("companyId")])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val type: DocumentType,
    val title: String,
    val issuer: String?,
    val issuedAt: Long?,
    val expiresAt: Long?,
    val attachmentUri: String?,
    val tags: List<String>,
    val notes: String,
    val createdAt: Long,
)

@Entity(tableName = "proposals", indices = [Index("tenderId"), Index("companyId")])
data class ProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tenderId: Long,
    val companyId: Long,
    val version: Int,
    val items: List<ProposalItemDto>,
    val deliveryDays: Int,
    val validityDays: Int,
    val notes: String,
    val status: ProposalStatus,
    val pdfPath: String?,
    val createdBy: String,
    val createdAt: Long,
    val approvedBy: String?,
    val approvedAt: Long?,
    val rejectionReason: String?,
)

@Entity(tableName = "portal_sessions", indices = [Index(value = ["companyId", "portal"], unique = true)])
data class PortalSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val portal: Portal,
    val username: String,
    val status: PortalConnectionStatus,
    val lastLoginAt: Long?,
    val sessionKey: String,
)

/** BidRule embutida na sessão ao vivo (prefixo rule_). */
data class BidRuleColumns(
    val mode: RobotMode,
    val strategy: BidStrategy,
    val initialPrice: Double,
    val floorPrice: Double,
    val costPrice: Double,
    val reductionValue: Double,
    val minMarginPct: Double,
    val lossLimit: Double,
    val minIntervalSeconds: Int,
    val authorizationThresholdPct: Double,
    val simulation: Boolean,
)

@Entity(tableName = "live_sessions", indices = [Index("companyId")])
data class LiveSessionEntity(
    @PrimaryKey val id: String,
    val companyId: Long,
    val tenderId: Long?,
    val portal: Portal,
    val tenderNumber: String,
    val agency: String,
    val itemLabel: String,
    val objectDescription: String,
    val status: LiveStatus,
    val robotStatus: RobotStatus,
    val position: Int,
    val competitors: Int,
    val ourLastBid: Double?,
    val bestBid: Double?,
    @Embedded(prefix = "rule_") val rule: BidRuleColumns,
    val remainingSeconds: Int?,
    val captchaPending: Boolean,
    val captchaSince: Long?,
    val pendingAuthorization: BidAuthorizationDto?,
    val unreadMessages: Int,
    val lastError: String?,
    val startedAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "bid_events", indices = [Index("sessionId")])
data class BidEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestamp: Long,
    val type: BidEventType,
    val value: Double?,
    val actor: String,
    val description: String,
)

@Entity(tableName = "notifications", indices = [Index("companyId")])
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long?,
    val category: NotificationCategory,
    val title: String,
    val body: String,
    val createdAt: Long,
    val read: Boolean,
    val critical: Boolean,
    val route: String?,
    val sessionId: String?,
)

@Entity(tableName = "audit_events", indices = [Index("companyId"), Index("timestamp")])
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val user: String,
    val companyId: Long?,
    val companyName: String,
    val portal: String?,
    val tenderNumber: String?,
    val item: String?,
    val action: AuditAction,
    val previousValue: String?,
    val newValue: String?,
    val reason: String?,
    val origin: AuditOrigin,
    val result: AuditResult,
    val details: String,
    /** Hash do evento anterior (cadeia); "" em eventos anteriores à versão 5 do banco. */
    @ColumnInfo(defaultValue = "") val prevHash: String = "",
    /** SHA-256 de prevHash + campos essenciais; "" = evento legado (sem hash). */
    @ColumnInfo(defaultValue = "") val hash: String = "",
)

/**
 * Modelo/URL por (provedor, empresa). `companyId = 0` é o "padrão do aparelho", usado por toda empresa
 * sem configuração própria. A chave e o token OAuth ficam no cofre (Keystore), nunca aqui.
 */
@Entity(tableName = "ai_configs", primaryKeys = ["provider", "companyId"])
data class AiConfigEntity(
    val provider: AiProviderType,
    /** Versão 6: 0 = padrão do aparelho; >0 = configuração própria da empresa. */
    @ColumnInfo(defaultValue = "0") val companyId: Long = 0,
    val model: String,
    val baseUrl: String,
    @ColumnInfo(defaultValue = "API_KEY") val authMode: AiAuthMode = AiAuthMode.API_KEY,
    /** E-mail da conta autorizada (exibição). */
    @ColumnInfo(defaultValue = "NULL") val oauthAccount: String? = null,
    /** ID do projeto Google Cloud (`x-goog-user-project`), opcional. */
    @ColumnInfo(defaultValue = "NULL") val cloudProject: String? = null,
)

@Entity(tableName = "auctioneer_messages", indices = [Index("companyId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val sessionId: String?,
    val portal: Portal,
    val tenderNumber: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val read: Boolean,
    val urgent: Boolean,
    val responseDeadline: Long?,
    val aiSummary: String?,
    val suggestedReply: String?,
    val replyDraft: String?,
    val replyStatus: ReplyStatus,
    val repliedAt: Long?,
)

@Entity(tableName = "competition_records", indices = [Index("companyId")])
data class CompetitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val behavior: String,
)

/**
 * Cache das notas de relevância por IA (v8). Chave: oportunidade × empresa × assinatura do radar (hash de
 * segmento + palavras + objeto preferencial); mudou o radar, a nota é pedida de novo. Cache local: não entra no backup.
 */
@Entity(tableName = "relevance_scores", primaryKeys = ["opportunityId", "companyId", "radarSignature"])
data class RelevanceScoreEntity(
    val opportunityId: String,
    val companyId: Long,
    val radarSignature: String,
    val score: Int,
    val reason: String,
    val provider: String,
    val createdAt: Long,
)
