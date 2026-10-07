package com.licitaia.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.TenderStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanyDao {
    @Query("SELECT * FROM companies ORDER BY name")
    fun observeAll(): Flow<List<CompanyEntity>>

    @Query("SELECT * FROM companies ORDER BY name")
    suspend fun getAll(): List<CompanyEntity>

    @Query("SELECT * FROM companies WHERE id = :id")
    suspend fun getById(id: Long): CompanyEntity?

    @Query("SELECT COUNT(*) FROM companies")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompanyEntity): Long

    @Query("DELETE FROM companies WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY name")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users ORDER BY name")
    suspend fun getAll(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE provider = :provider AND externalId = :externalId LIMIT 1")
    suspend fun getByExternalId(provider: String, externalId: String): UserEntity?

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserEntity): Long

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RadarDao {
    @Query("SELECT * FROM radars WHERE companyId = :companyId ORDER BY createdAt DESC")
    fun observeByCompany(companyId: Long): Flow<List<RadarEntity>>

    @Query("SELECT * FROM radars WHERE companyId = :companyId AND active = 1")
    fun observeActive(companyId: Long): Flow<List<RadarEntity>>

    @Query("SELECT * FROM radars WHERE companyId = :companyId AND active = 1")
    suspend fun getActive(companyId: Long): List<RadarEntity>

    @Query("SELECT * FROM radars WHERE id = :id")
    suspend fun getById(id: Long): RadarEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RadarEntity): Long

    @Query("DELETE FROM radars WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM radars WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface OpportunityDao {
    @Query("SELECT * FROM opportunities ORDER BY publishedAt DESC")
    suspend fun getAll(): List<OpportunityEntity>

    @Query("SELECT * FROM opportunities WHERE id = :id")
    suspend fun getById(id: String): OpportunityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<OpportunityEntity>)

    @Query("DELETE FROM opportunities WHERE cachedAt < :olderThan AND id NOT IN (SELECT opportunityId FROM tenders)")
    suspend fun prune(olderThan: Long)

    /** Ids no cache (atualização diária: o que é novo hoje). */
    @Query("SELECT id FROM opportunities")
    suspend fun ids(): List<String>

    /** Limpeza diária: apaga [ids], nunca as vinculadas a uma licitação salva (interesse, análise, proposta, sessão). */
    @Query("DELETE FROM opportunities WHERE id IN (:ids) AND id NOT IN (SELECT opportunityId FROM tenders)")
    suspend fun deleteUnprotected(ids: List<String>): Int
}

/** Cache persistente do Compras.gov.br (versão 10). Leitura filtrada no SQL e em blocos (keyset por id). */
@Dao
interface ComprasGovCacheDao {
    @Query("SELECT * FROM comprasgov_sync WHERE modalityCode = :modalityCode AND uf = :uf")
    suspend fun syncMark(modalityCode: Int, uf: String): ComprasGovSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncMark(mark: ComprasGovSyncEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ComprasGovRowEntity>)

    @Query("DELETE FROM comprasgov_rows WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM comprasgov_rows WHERE publishedAt < :publishedBefore OR (proposalDeadline > 0 AND proposalDeadline < :now)")
    suspend fun prune(publishedBefore: Long, now: Long): Int

    @Query(
        "SELECT * FROM comprasgov_rows WHERE modalityCode = :modalityCode AND (:anyUf = 1 OR uf IN (:ufs)) " +
            "AND publishedAt >= :publishedSince AND (proposalDeadline <= 0 OR proposalDeadline >= :openAt) AND id > :afterId " +
            "ORDER BY id LIMIT :limit",
    )
    suspend fun page(
        modalityCode: Int,
        anyUf: Boolean,
        ufs: List<String>,
        publishedSince: Long,
        openAt: Long,
        afterId: String,
        limit: Int,
    ): List<ComprasGovRowEntity>

    @Query("SELECT COUNT(*) FROM comprasgov_rows")
    suspend fun count(): Int

    /** Limpeza diária: encerradas (prazo de propostas e sessão já passaram), nunca as vinculadas a uma licitação salva. */
    @Query(
        "DELETE FROM comprasgov_rows WHERE proposalDeadline > 0 AND proposalDeadline < :now AND (sessionAt <= 0 OR sessionAt < :now) " +
            "AND id NOT IN (SELECT opportunityId FROM tenders)",
    )
    suspend fun deleteEnded(now: Long): Int

    /** Limpeza diária: apaga [ids] (retiradas), nunca as vinculadas a uma licitação salva. */
    @Query("DELETE FROM comprasgov_rows WHERE id IN (:ids) AND id NOT IN (SELECT opportunityId FROM tenders)")
    suspend fun deleteUnprotected(ids: List<String>): Int
}

@Dao
interface TenderDao {
    @Query("SELECT * FROM tenders WHERE companyId = :companyId ORDER BY updatedAt DESC")
    fun observeByCompany(companyId: Long): Flow<List<TenderEntity>>

    @Query("SELECT * FROM tenders WHERE id = :id")
    fun observeById(id: Long): Flow<TenderEntity?>

    @Query("SELECT * FROM tenders WHERE id = :id")
    suspend fun getById(id: Long): TenderEntity?

    @Query("SELECT * FROM tenders WHERE companyId = :companyId AND opportunityId = :opportunityId LIMIT 1")
    suspend fun getByOpportunity(companyId: Long, opportunityId: String): TenderEntity?

    @Query("SELECT opportunityId FROM tenders WHERE companyId = :companyId")
    suspend fun opportunityIds(companyId: Long): List<String>

    /** Oportunidades salvas por QUALQUER empresa (protegidas da limpeza diária). */
    @Query("SELECT DISTINCT opportunityId FROM tenders")
    suspend fun allOpportunityIds(): List<String>

    /** Ids de todas as licitações salvas (editais órfãos em disco são os de ids fora desta lista). */
    @Query("SELECT id FROM tenders")
    suspend fun allIds(): List<Long>

    @Query("SELECT * FROM tenders WHERE status = :status")
    suspend fun getByStatus(status: TenderStatus): List<TenderEntity>

    @Query("SELECT * FROM tenders WHERE companyId = :companyId")
    suspend fun getByCompany(companyId: Long): List<TenderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TenderEntity): Long

    @Query("UPDATE tenders SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TenderStatus, now: Long)

    @Query("UPDATE tenders SET editalRegistered = :registered, updatedAt = :now WHERE id = :id")
    suspend fun updateEditalRegistered(id: Long, registered: Boolean, now: Long)

    @Query(
        "UPDATE tenders SET editalPdfPath = :pdfPath, editalTextPath = :textPath, editalChars = :chars, " +
            "editalPages = :pages, editalScanned = :scanned, editalRegistered = :registered, updatedAt = :now WHERE id = :id",
    )
    suspend fun updateEdital(
        id: Long,
        pdfPath: String?,
        textPath: String?,
        chars: Int,
        pages: Int?,
        scanned: Boolean,
        registered: Boolean,
        now: Long,
    )

    @Query("DELETE FROM tenders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TenderAnalysisDao {
    @Query("SELECT * FROM tender_analyses WHERE tenderId = :tenderId")
    fun observe(tenderId: Long): Flow<TenderAnalysisEntity?>

    @Query("SELECT * FROM tender_analyses WHERE tenderId = :tenderId")
    suspend fun get(tenderId: Long): TenderAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TenderAnalysisEntity)

    @Query("DELETE FROM tender_analyses WHERE tenderId = :tenderId")
    suspend fun delete(tenderId: Long)

    @Query("DELETE FROM tender_analyses WHERE tenderId IN (SELECT id FROM tenders WHERE companyId = :companyId)")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE companyId = :companyId ORDER BY type, expiresAt")
    fun observeByCompany(companyId: Long): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE companyId = :companyId")
    suspend fun getByCompany(companyId: Long): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DocumentEntity): Long

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM documents WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface ProposalDao {
    @Query("SELECT * FROM proposals WHERE tenderId = :tenderId ORDER BY version DESC")
    fun observeByTender(tenderId: Long): Flow<List<ProposalEntity>>

    @Query("SELECT * FROM proposals WHERE id = :id")
    fun observeById(id: Long): Flow<ProposalEntity?>

    @Query("SELECT * FROM proposals WHERE id = :id")
    suspend fun getById(id: Long): ProposalEntity?

    @Query("SELECT COALESCE(MAX(version), 0) FROM proposals WHERE tenderId = :tenderId")
    suspend fun maxVersion(tenderId: Long): Int

    @Query("SELECT COUNT(*) FROM proposals WHERE companyId = :companyId AND status = :status")
    fun observeCountByStatus(companyId: Long, status: ProposalStatus): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProposalEntity): Long

    @Update
    suspend fun update(entity: ProposalEntity)

    @Query("DELETE FROM proposals WHERE tenderId = :tenderId")
    suspend fun deleteByTender(tenderId: Long)

    @Query("DELETE FROM proposals WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface PortalSessionDao {
    @Query("SELECT * FROM portal_sessions WHERE companyId = :companyId ORDER BY portal")
    fun observeByCompany(companyId: Long): Flow<List<PortalSessionEntity>>

    @Query("SELECT * FROM portal_sessions WHERE companyId = :companyId AND portal = :portal LIMIT 1")
    suspend fun get(companyId: Long, portal: Portal): PortalSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PortalSessionEntity): Long

    @Query("DELETE FROM portal_sessions WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface LiveSessionDao {
    @Query("SELECT * FROM live_sessions WHERE id = :id")
    suspend fun getById(id: String): LiveSessionEntity?

    @Query("SELECT * FROM live_sessions WHERE companyId = :companyId AND status != 'ENCERRADA' ORDER BY startedAt")
    suspend fun getOpen(companyId: Long): List<LiveSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LiveSessionEntity)

    @Query("DELETE FROM live_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM live_sessions WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface BidEventDao {
    @Query("SELECT * FROM bid_events WHERE sessionId = :sessionId ORDER BY timestamp DESC, id DESC")
    fun observeBySession(sessionId: String): Flow<List<BidEventEntity>>

    @Insert
    suspend fun insert(entity: BidEventEntity): Long

    @Insert
    suspend fun insertAll(entities: List<BidEventEntity>)

    @Query("DELETE FROM bid_events WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** Eventos de todas as sessões de uma empresa (usado ao apagar o espaço de demonstração). */
    @Query("DELETE FROM bid_events WHERE sessionId IN (SELECT id FROM live_sessions WHERE companyId = :companyId)")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): NotificationEntity?

    @Query("SELECT * FROM notifications WHERE companyId = :companyId OR companyId IS NULL ORDER BY createdAt DESC")
    fun observeByCompany(companyId: Long): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE (companyId = :companyId OR companyId IS NULL) AND read = 0")
    fun observeUnreadCount(companyId: Long): Flow<Int>

    @Insert
    suspend fun insert(entity: NotificationEntity): Long

    @Query("UPDATE notifications SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE notifications SET read = 1 WHERE companyId = :companyId OR companyId IS NULL")
    suspend fun markAllRead(companyId: Long)

    @Query("DELETE FROM notifications WHERE companyId = :companyId OR companyId IS NULL")
    suspend fun clear(companyId: Long)

    /** Só as notificações da empresa (sem as globais) — usado ao apagar o espaço de demonstração. */
    @Query("DELETE FROM notifications WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC, id DESC LIMIT 2000")
    fun observeAll(): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE companyId = :companyId OR companyId IS NULL ORDER BY timestamp DESC, id DESC LIMIT 2000")
    fun observeByCompany(companyId: Long): Flow<List<AuditEventEntity>>

    @Insert
    suspend fun insert(entity: AuditEventEntity): Long

    @Insert
    suspend fun insertAll(entities: List<AuditEventEntity>)

    /** Último evento inserido (cabeça da cadeia de hashes). */
    @Query("SELECT * FROM audit_events ORDER BY id DESC LIMIT 1")
    suspend fun last(): AuditEventEntity?

    @Query("SELECT COUNT(*) FROM audit_events")
    suspend fun count(): Int

    /** Página em ordem de inserção para verificação da cadeia. */
    @Query("SELECT * FROM audit_events WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pageAfter(afterId: Long, limit: Int): List<AuditEventEntity>
}

@Dao
interface AiConfigDao {
    /** Todas as linhas (todas as empresas e o padrão do aparelho); o repositório resolve o escopo. */
    @Query("SELECT * FROM ai_configs")
    fun observeAll(): Flow<List<AiConfigEntity>>

    /** Linha exata do escopo: `companyId = 0` é o padrão do aparelho. */
    @Query("SELECT * FROM ai_configs WHERE provider = :provider AND companyId = :companyId")
    suspend fun get(provider: AiProviderType, companyId: Long): AiConfigEntity?

    /** Configuração da empresa, ou o padrão do aparelho se ela não tiver a própria (prioriza companyId > 0). */
    @Query("SELECT * FROM ai_configs WHERE provider = :provider AND companyId IN (:companyId, 0) ORDER BY companyId DESC LIMIT 1")
    suspend fun resolve(provider: AiProviderType, companyId: Long): AiConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AiConfigEntity)

    @Query("DELETE FROM ai_configs WHERE provider = :provider AND companyId = :companyId")
    suspend fun delete(provider: AiProviderType, companyId: Long)

    @Query("SELECT * FROM ai_configs WHERE companyId = :companyId")
    suspend fun getByCompany(companyId: Long): List<AiConfigEntity>

    @Query("DELETE FROM ai_configs WHERE companyId = :companyId AND companyId > 0")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM auctioneer_messages WHERE companyId = :companyId ORDER BY receivedAt DESC")
    fun observeByCompany(companyId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM auctioneer_messages WHERE id = :id")
    fun observeById(id: Long): Flow<MessageEntity?>

    @Query("SELECT COUNT(*) FROM auctioneer_messages WHERE companyId = :companyId AND read = 0")
    fun observeUnreadCount(companyId: Long): Flow<Int>

    @Query("SELECT * FROM auctioneer_messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity): Long

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("UPDATE auctioneer_messages SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM auctioneer_messages WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

@Dao
interface CompetitionDao {
    @Query("SELECT * FROM competition_records WHERE companyId = :companyId ORDER BY date DESC")
    fun observeByCompany(companyId: Long): Flow<List<CompetitionEntity>>

    @Insert
    suspend fun insertAll(entities: List<CompetitionEntity>)

    @Insert
    suspend fun insert(entity: CompetitionEntity): Long

    @Query("SELECT * FROM competition_records WHERE id = :id")
    suspend fun getById(id: Long): CompetitionEntity?

    @Query("DELETE FROM competition_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM competition_records WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

/** Versão 11: "Minhas licitações" do Comprasnet e planos do robô, sempre filtrados por empresa. */
@Dao
interface PortalRobotDao {
    @Query("SELECT * FROM portal_my_tenders WHERE companyId = :companyId ORDER BY CASE WHEN openingAt IS NULL THEN 1 ELSE 0 END, openingAt, tenderKey")
    fun observeMyTenders(companyId: Long): Flow<List<PortalMyTenderEntity>>

    @Query("SELECT * FROM portal_my_tenders WHERE companyId = :companyId")
    suspend fun getMyTenders(companyId: Long): List<PortalMyTenderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMyTenders(entities: List<PortalMyTenderEntity>)

    @Query("DELETE FROM portal_my_tenders WHERE companyId = :companyId AND tenderKey = :tenderKey")
    suspend fun deleteMyTender(companyId: Long, tenderKey: String)

    @Query("DELETE FROM portal_my_tenders WHERE companyId = :companyId")
    suspend fun deleteMyTendersByCompany(companyId: Long)

    /** Oportunidades casadas com "minhas licitações" (todas as empresas): nunca podadas pela limpeza diária. */
    @Query("SELECT DISTINCT matchedOpportunityId FROM portal_my_tenders WHERE matchedOpportunityId IS NOT NULL")
    suspend fun matchedOpportunityIds(): List<String>

    @Query("SELECT * FROM portal_robot_plans WHERE companyId = :companyId ORDER BY updatedAt DESC")
    fun observePlans(companyId: Long): Flow<List<PortalRobotPlanEntity>>

    @Query("SELECT * FROM portal_robot_plans WHERE companyId = :companyId AND tenderKey = :tenderKey")
    fun observePlan(companyId: Long, tenderKey: String): Flow<PortalRobotPlanEntity?>

    @Query("SELECT * FROM portal_robot_plans WHERE companyId = :companyId AND tenderKey = :tenderKey")
    suspend fun getPlan(companyId: Long, tenderKey: String): PortalRobotPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(entity: PortalRobotPlanEntity)

    @Query("DELETE FROM portal_robot_plans WHERE companyId = :companyId")
    suspend fun deletePlansByCompany(companyId: Long)
}

@Dao
interface RelevanceScoreDao {
    @Query("SELECT * FROM relevance_scores WHERE companyId = :companyId AND radarSignature = :signature AND opportunityId IN (:ids)")
    suspend fun find(companyId: Long, signature: String, ids: List<String>): List<RelevanceScoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RelevanceScoreEntity>)

    @Query("DELETE FROM relevance_scores WHERE createdAt < :olderThan")
    suspend fun prune(olderThan: Long)

    /** Notas por IA de oportunidades apagadas na limpeza diária. */
    @Query("DELETE FROM relevance_scores WHERE opportunityId IN (:ids)")
    suspend fun deleteForOpportunities(ids: List<String>)

    @Query("DELETE FROM relevance_scores WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}

/** Versão 13: histórico do "Pergunte ao edital". A filtragem por empresa (RBAC) fica no repositório. */
@Dao
interface EditalQuestionDao {
    @Query("SELECT * FROM edital_questions WHERE tenderId = :tenderId ORDER BY createdAt, id")
    fun observeByTender(tenderId: Long): Flow<List<EditalQuestionEntity>>

    @Query("SELECT * FROM edital_questions WHERE id = :id")
    suspend fun getById(id: Long): EditalQuestionEntity?

    @Insert
    suspend fun insert(entity: EditalQuestionEntity): Long

    @Update
    suspend fun update(entity: EditalQuestionEntity)

    @Query("DELETE FROM edital_questions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM edital_questions WHERE tenderId = :tenderId")
    suspend fun deleteByTender(tenderId: Long)

    @Query("DELETE FROM edital_questions WHERE companyId = :companyId")
    suspend fun deleteByCompany(companyId: Long)
}
