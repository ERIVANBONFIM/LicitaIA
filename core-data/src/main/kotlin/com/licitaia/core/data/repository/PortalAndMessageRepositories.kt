package com.licitaia.core.data.repository

import com.licitaia.ai.api.AiGateway
import com.licitaia.connector.api.ConnectorRegistry
import com.licitaia.connector.api.PortalAuthResult
import com.licitaia.connector.api.PortalCredentials
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.MessageDao
import com.licitaia.core.data.db.PortalSessionDao
import com.licitaia.core.data.db.PortalSessionEntity
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.model.AuctioneerMessage
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditOrigin
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.ConnectorCapabilities
import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.model.PortalSession
import com.licitaia.domain.model.ReplyStatus
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PortalRepositoryImpl @Inject constructor(
    private val portalSessionDao: PortalSessionDao,
    private val registry: ConnectorRegistry,
    private val secretStore: SecretStore,
    private val audit: AuditRepository,
    private val access: RepositoryAccess,
) : PortalRepository {

    /** Status/lastLoginAt vêm da detecção no navegador interno; `username` nunca é exposto (login manual). */
    override fun observeSessions(companyId: Long): Flow<List<PortalSession>> =
        portalSessionDao.observeByCompany(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain().copy(username = "") } else emptyList() }

    @Deprecated("Login manual no navegador interno; o app não coleta credenciais.", level = DeprecationLevel.WARNING)
    override suspend fun connect(companyId: Long, portal: Portal, username: String, secret: String): Result<PortalSession> =
        runCatching {
            access.requireCompany(companyId, Permission.OPERAR_SESSOES)
            error("Autenticação manual: entre no portal pelo navegador interno. O LicitaIA não coleta senhas.")
        }

    override suspend fun markSessionDetected(companyId: Long, portal: Portal, loggedIn: Boolean) {
        withContext(Dispatchers.IO) {
            // Detecção passiva: exige apenas posse da empresa (quem navega no portal já passou pelo login do app).
            access.requireCompany(companyId)
            val now = System.currentTimeMillis()
            val existing = portalSessionDao.get(companyId, portal)
            val newStatus = if (loggedIn) PortalConnectionStatus.CONECTADO else PortalConnectionStatus.SESSAO_EXPIRADA
            // Sem sessão anterior e sem login detectado → nada a registrar (evita marcar "expirada" quem nunca entrou).
            if (existing == null && !loggedIn) return@withContext
            val changed = existing?.status != newStatus
            val entity = (existing ?: PortalSessionEntity(
                companyId = companyId, portal = portal, username = "", status = newStatus,
                lastLoginAt = null, sessionKey = sessionKey(companyId, portal),
            )).copy(
                username = "",
                status = newStatus,
                lastLoginAt = if (loggedIn) now else existing?.lastLoginAt,
            )
            portalSessionDao.upsert(entity)
            if (changed) {
                audit.record(
                    AuditAction.CONEXAO_PORTAL, portal = portal,
                    previousValue = existing?.status?.label, newValue = newStatus.label,
                    details = if (loggedIn) "Sessão detectada no navegador interno (login manual; sem credenciais no app)" else "Sessão expirada detectada no navegador interno",
                )
            }
        }
    }

    override suspend fun clearWebSession(companyId: Long, portal: Portal) {
        withContext(Dispatchers.IO) {
            access.requireCompany(companyId, Permission.OPERAR_SESSOES)
            val sessionKey = sessionKey(companyId, portal)
            // Compatibilidade: remove qualquer segredo legado do cofre (o fluxo atual não grava nenhum).
            runCatching { secretStore.remove(secretKey(sessionKey)) }
            // "Sair do portal" também esquece a última página visitada (reabre no login oficial).
            runCatching { secretStore.remove(lastUrlKey(sessionKey)) }
            val existing = portalSessionDao.get(companyId, portal)
            val entity = (existing ?: PortalSessionEntity(
                companyId = companyId, portal = portal, username = "", status = PortalConnectionStatus.DESCONECTADO,
                lastLoginAt = null, sessionKey = sessionKey,
            )).copy(username = "", status = PortalConnectionStatus.DESCONECTADO)
            portalSessionDao.upsert(entity)
            audit.record(
                AuditAction.CONEXAO_PORTAL, portal = portal,
                previousValue = existing?.status?.label, newValue = PortalConnectionStatus.DESCONECTADO.label,
                details = "Sessão encerrada: cookies do portal removidos do navegador interno",
            )
        }
    }

    override suspend fun disconnect(companyId: Long, portal: Portal) {
        withContext(Dispatchers.IO) {
            access.requireCompany(companyId, Permission.OPERAR_SESSOES)
            val sessionKey = sessionKey(companyId, portal)
            runCatching { registry.get(portal).logout(sessionKey) }
            secretStore.remove(secretKey(sessionKey))
            portalSessionDao.get(companyId, portal)?.let {
                portalSessionDao.upsert(it.copy(status = PortalConnectionStatus.DESCONECTADO))
            }
            audit.record(AuditAction.CONEXAO_PORTAL, portal = portal, details = "Sessão encerrada e credencial removida do cofre")
        }
    }

    /**
     * Capacidades reais: conectores com API pública (PNCP, Compras.gov.br) respondem por si; portais sem
     * conector real (mock inerte) são descritos como acesso manual no navegador interno.
     */
    override fun capabilities(portal: Portal): ConnectorCapabilities {
        val real = runCatching { registry.get(portal).capabilities }.getOrNull()?.takeIf { !it.isMock }
        if (real != null) return real
        val publicOnly = portal == Portal.PNCP
        return ConnectorCapabilities(
            supportsWebView = true, supportsOfficialApi = false, supportsBrowserAutomation = false,
            supportsPersistentSession = !publicOnly, requiresMfa = !publicOnly, mayShowCaptcha = !publicOnly, isMock = false,
            limitations = listOf(
                "Sem API pública de consulta: busca de licitações indisponível neste portal (use PNCP/Compras.gov.br).",
                "Login manual no navegador interno; a sessão fica nos cookies deste aparelho e pode expirar pelo portal.",
                "Sem envio integrado de propostas, mensagens ou lances.",
            ),
        )
    }

    override suspend fun lastWebUrl(companyId: Long, portal: Portal): String? = withContext(Dispatchers.IO) {
        if (!access.owns(companyId)) return@withContext null
        runCatching { secretStore.get(lastUrlKey(sessionKey(companyId, portal))) }.getOrNull()?.takeIf { it.startsWith("https://") }
    }

    override suspend fun saveLastWebUrl(companyId: Long, portal: Portal, url: String) {
        withContext(Dispatchers.IO) {
            if (!access.owns(companyId) || !url.startsWith("https://") || url.length > MAX_URL) return@withContext
            // Cofre cifrado (Keystore): a URL pode conter identificadores de processos da empresa.
            runCatching { secretStore.put(lastUrlKey(sessionKey(companyId, portal)), url) }
        }
    }

    override suspend fun auditKeepAlive(companyId: Long, portal: Portal, details: String) {
        withContext(Dispatchers.IO) {
            if (!access.owns(companyId)) return@withContext
            audit.record(AuditAction.CONEXAO_PORTAL, portal = portal, details = details)
        }
    }

    private fun capabilityLabel(portal: Portal): String =
        if (runCatching { registry.get(portal).capabilities.isMock }.getOrDefault(true)) "simulação" else "conector real"

    companion object {
        fun sessionKey(companyId: Long, portal: Portal) = "portal_${companyId}_${portal.name}"
        fun secretKey(sessionKey: String) = "portal.secret.$sessionKey"
        fun lastUrlKey(sessionKey: String) = "portal.lastUrl.$sessionKey"
        private const val MAX_URL = 2048
    }
}

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val companyDao: CompanyDao,
    private val gateway: AiGateway,
    private val audit: AuditRepository,
    private val holder: SessionHolder,
    private val access: RepositoryAccess,
) : MessageRepository {

    override fun observeMessages(companyId: Long): Flow<List<AuctioneerMessage>> =
        messageDao.observeByCompany(companyId).map { list -> if (access.owns(companyId)) list.map { it.toDomain() } else emptyList() }

    override fun observeMessage(id: Long): Flow<AuctioneerMessage?> = messageDao.observeById(id).map { it?.takeIf { access.owns(it.companyId) }?.toDomain() }

    override fun observeUnreadCount(companyId: Long): Flow<Int> = messageDao.observeUnreadCount(companyId).map { if (access.owns(companyId)) it else 0 }.distinctUntilChanged()

    override suspend fun insert(message: AuctioneerMessage): Long = withContext(Dispatchers.IO) {
        access.requireCompany(message.companyId)
        messageDao.upsert(message.copy(receivedAt = if (message.receivedAt == 0L) System.currentTimeMillis() else message.receivedAt).toEntity())
    }

    override suspend fun markRead(id: Long) = withContext(Dispatchers.IO) { val row = messageDao.getById(id) ?: return@withContext; access.requireCompany(row.companyId); messageDao.markRead(id) }

    override suspend fun generateAiAssist(id: Long): Result<AuctioneerMessage> = withContext(Dispatchers.IO) {
        val entity = messageDao.getById(id)
            ?: return@withContext Result.failure(IllegalArgumentException("Mensagem não encontrada."))
        access.requireCompany(entity.companyId, Permission.RESPONDER_MENSAGENS)
        val message = entity.toDomain()
        val company = companyDao.getById(message.companyId)?.toDomain()
            ?: return@withContext Result.failure(IllegalStateException("Empresa não encontrada."))
        val provider = gateway.current()
        val draft = runCatching { provider.draftMessage(message, company) }.getOrElse { error ->
            audit.record(
                AuditAction.MENSAGEM, result = AuditResult.FALHA, origin = AuditOrigin.IA, portal = message.portal,
                tenderNumber = message.tenderNumber, reason = error.message, details = "Falha na assistência de IA (${provider.displayName})",
            )
            return@withContext Result.failure(error)
        }
        val updated = message.copy(
            aiSummary = draft.summary,
            suggestedReply = draft.suggestedReply,
            urgent = message.urgent || draft.urgent,
        )
        messageDao.update(updated.toEntity())
        audit.record(
            AuditAction.MENSAGEM, origin = AuditOrigin.IA, portal = message.portal, tenderNumber = message.tenderNumber,
            details = "Resumo e resposta sugerida gerados com ${provider.displayName}",
        )
        Result.success(updated)
    }

    override suspend fun saveReplyDraft(id: Long, text: String) {
        withContext(Dispatchers.IO) {
            val message = messageDao.getById(id) ?: return@withContext
            access.requireCompany(message.companyId, Permission.RESPONDER_MENSAGENS)
            messageDao.update(message.copy(replyDraft = text, replyStatus = ReplyStatus.RASCUNHO))
        }
    }

    override suspend fun approveReply(id: Long) {
        withContext(Dispatchers.IO) {
            val message = messageDao.getById(id) ?: return@withContext
            access.requireCompany(message.companyId, Permission.RESPONDER_MENSAGENS)
            if (message.replyDraft.isNullOrBlank()) return@withContext
            messageDao.update(message.copy(replyStatus = ReplyStatus.APROVADA))
            audit.record(
                AuditAction.APROVACAO, portal = message.portal, tenderNumber = message.tenderNumber,
                details = "Resposta ao pregoeiro aprovada",
            )
        }
    }

    override suspend fun sendReplySimulated(id: Long): Result<Unit> = runCatching {
        val message = messageDao.getById(id) ?: error("Mensagem não encontrada.")
        access.requireCompany(message.companyId, Permission.RESPONDER_MENSAGENS)
        error("Envio integrado indisponível. Revise e envie a resposta manualmente no portal oficial.")
    }
}
