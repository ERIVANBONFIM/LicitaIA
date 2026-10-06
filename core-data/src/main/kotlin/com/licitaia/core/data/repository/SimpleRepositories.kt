package com.licitaia.core.data.repository

import com.licitaia.core.data.db.BidEventDao
import com.licitaia.core.data.db.CompetitionDao
import com.licitaia.core.data.db.DocumentDao
import com.licitaia.core.data.db.LiveSessionDao
import com.licitaia.core.data.db.NotificationDao
import com.licitaia.core.data.db.RadarDao
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.domain.live.LiveSessionStore
import com.licitaia.domain.model.AppNotification
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.BidEvent
import com.licitaia.domain.model.CompanyDocument
import com.licitaia.domain.model.CompetitionRecord
import com.licitaia.domain.model.DocumentStatus
import com.licitaia.domain.model.LiveSession
import com.licitaia.domain.model.Radar
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.CompetitionRepository
import com.licitaia.domain.repository.DocumentRepository
import com.licitaia.domain.repository.NotificationRepository
import com.licitaia.domain.repository.RadarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.licitaia.domain.security.Permission
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadarRepositoryImpl @Inject constructor(
    private val radarDao: RadarDao,
    private val audit: AuditRepository,
    private val access: RepositoryAccess,
) : RadarRepository {

    override fun observeRadars(companyId: Long): Flow<List<Radar>> =
        radarDao.observeByCompany(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain() } else emptyList() }

    override suspend fun getRadar(id: Long): Radar? = radarDao.getById(id)?.takeIf { access.owns(it.companyId) }?.toDomain()

    override suspend fun upsert(radar: Radar): Long = withContext(Dispatchers.IO) {
        access.requireCompany(radar.companyId, Permission.BUSCAR)
        if (radar.id != 0L) check(radarDao.getById(radar.id)?.companyId == radar.companyId) { "Radar de outra empresa." }
        val isNew = radar.id == 0L
        val entity = radar.copy(createdAt = if (radar.createdAt == 0L) System.currentTimeMillis() else radar.createdAt).toEntity()
        val id = radarDao.upsert(entity)
        audit.record(AuditAction.CONFIGURACAO, newValue = radar.name, details = if (isNew) "Radar criado" else "Radar atualizado")
        id
    }

    override suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) {
            val radar = radarDao.getById(id) ?: return@withContext
            access.requireCompany(radar.companyId, Permission.BUSCAR)
            radarDao.delete(id)
            audit.record(AuditAction.CONFIGURACAO, previousValue = radar?.name, details = "Radar excluído")
        }
    }
}

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val audit: AuditRepository,
    private val access: RepositoryAccess,
) : DocumentRepository {

    override fun observeDocuments(companyId: Long): Flow<List<CompanyDocument>> =
        documentDao.observeByCompany(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain() } else emptyList() }

    override suspend fun getDocument(id: Long): CompanyDocument? = documentDao.getById(id)?.takeIf { access.owns(it.companyId) }?.toDomain()

    override suspend fun upsert(document: CompanyDocument): Long = withContext(Dispatchers.IO) {
        access.requireCompany(document.companyId, Permission.GERENCIAR_DOCUMENTOS)
        if (document.id != 0L) check(documentDao.getById(document.id)?.companyId == document.companyId) { "Documento de outra empresa." }
        val isNew = document.id == 0L
        val entity = document.copy(createdAt = if (document.createdAt == 0L) System.currentTimeMillis() else document.createdAt).toEntity()
        val id = documentDao.upsert(entity)
        audit.record(
            AuditAction.CADASTRO, newValue = "${document.type.label}: ${document.title}",
            details = if (isNew) "Documento adicionado ao cofre" else "Documento atualizado",
        )
        id
    }

    override suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) {
            val doc = documentDao.getById(id) ?: return@withContext
            access.requireCompany(doc.companyId, Permission.GERENCIAR_DOCUMENTOS)
            documentDao.delete(id)
            audit.record(AuditAction.CADASTRO, previousValue = doc?.title, details = "Documento removido do cofre")
        }
    }

    override fun observeExpiringCount(companyId: Long, withinDays: Int): Flow<Int> =
        documentDao.observeByCompany(companyId).map { list ->
            if (!access.owns(companyId)) return@map 0
            val now = System.currentTimeMillis()
            list.count { doc ->
                val status = doc.toDomain().status(now, withinDays)
                status == DocumentStatus.VENCIDO || status == DocumentStatus.VENCE_EM_BREVE
            }
        }.distinctUntilChanged()
}

@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val notificationDao: NotificationDao,
    private val access: RepositoryAccess,
) : NotificationRepository {

    override fun observeNotifications(companyId: Long): Flow<List<AppNotification>> =
        notificationDao.observeByCompany(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain() } else emptyList() }

    override fun observeUnreadCount(companyId: Long): Flow<Int> =
        notificationDao.observeUnreadCount(companyId).map { if (access.owns(companyId)) it else 0 }.distinctUntilChanged()

    override suspend fun insert(notification: AppNotification): Long = withContext(Dispatchers.IO) {
        access.requireCompany(notification.companyId ?: error("Notificação sem empresa."))
        val entity = notification
            .copy(createdAt = if (notification.createdAt == 0L) System.currentTimeMillis() else notification.createdAt)
            .toEntity()
        notificationDao.insert(entity)
    }

    override suspend fun markRead(id: Long) = withContext(Dispatchers.IO) { val row = notificationDao.getById(id) ?: return@withContext; access.requireCompany(row.companyId ?: error("Notificação sem empresa.")); notificationDao.markRead(id) }

    override suspend fun markAllRead(companyId: Long) = withContext(Dispatchers.IO) { access.requireCompany(companyId); notificationDao.markAllRead(companyId) }

    override suspend fun clear(companyId: Long) = withContext(Dispatchers.IO) { access.requireCompany(companyId); notificationDao.clear(companyId) }
}

@Singleton
class CompetitionRepositoryImpl @Inject constructor(
    private val competitionDao: CompetitionDao,
    private val access: RepositoryAccess,
) : CompetitionRepository {
    override fun observeRecords(companyId: Long): Flow<List<CompetitionRecord>> =
        competitionDao.observeByCompany(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain() } else emptyList() }
}

@Singleton
class LiveSessionStoreImpl @Inject constructor(
    private val liveSessionDao: LiveSessionDao,
    private val bidEventDao: BidEventDao,
    private val access: RepositoryAccess,
) : LiveSessionStore {

    override suspend fun loadSessions(companyId: Long): List<LiveSession> = withContext(Dispatchers.IO) {
        access.requireCompany(companyId)
        liveSessionDao.getOpen(companyId).map { it.toDomain() }
    }

    override suspend fun saveSession(session: LiveSession) = withContext(Dispatchers.IO) {
        access.requireCompany(session.companyId, Permission.OPERAR_SESSOES)
        liveSessionDao.upsert(session.toEntity())
    }

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            requireSession(sessionId)
            bidEventDao.deleteBySession(sessionId)
            liveSessionDao.delete(sessionId)
        }
    }

    override suspend fun appendEvent(event: BidEvent) {
        withContext(Dispatchers.IO) { requireSession(event.sessionId); bidEventDao.insert(event.copy(id = 0).toEntity()) }
    }

    private suspend fun requireSession(id: String) {
        val entity = liveSessionDao.getById(id) ?: error("Sessão não encontrada.")
        access.requireCompany(entity.companyId, Permission.OPERAR_SESSOES)
    }

    override fun observeEvents(sessionId: String): Flow<List<BidEvent>> =
        bidEventDao.observeBySession(sessionId).map { list -> runCatching { requireSession(sessionId) }.fold({ list.map { it.toDomain() } }, { emptyList() }) }
}
