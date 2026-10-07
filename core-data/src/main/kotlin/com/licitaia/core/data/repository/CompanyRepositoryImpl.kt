package com.licitaia.core.data.repository

import androidx.room.withTransaction
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.db.UserEntity
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.repository.RepositoryAccess.Companion.sameRealm
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.domain.demo.DemoAccount
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.CompanyRepository
import com.licitaia.core.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.licitaia.domain.model.UserRole
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompanyRepositoryImpl @Inject constructor(
    private val db: LicitaDatabase,
    private val holder: SessionHolder,
    private val hasher: PasswordHasher,
    private val audit: AuditRepository,
) : CompanyRepository {

    private val companyDao get() = db.companyDao()
    private val userDao get() = db.userDao()

    /**
     * Só as empresas vinculadas ao usuário e do mesmo "mundo": usuário demo vê apenas empresas demo;
     * usuário real nunca vê empresas demo.
     */
    override fun observeCompanies(): Flow<List<Company>> =
        combine(companyDao.observeAll(), holder.state) { list, session ->
            list.filter { session != null && it.id in session.user.companyIds && it.demo == session.user.demo }.map { it.toDomain() }
        }

    override suspend fun getCompany(id: Long): Company? {
        val session = holder.current ?: return null
        if (id !in session.user.companyIds) return null
        return companyDao.getById(id)?.takeIf { it.demo == session.user.demo }?.toDomain()
    }

    override suspend fun upsertCompany(company: Company): Long = withContext(Dispatchers.IO) {
        requireAdmin(company.id.takeIf { it > 0 })
        require(validCnpj(company.cnpj.filter(Char::isDigit))) { "CNPJ inválido." }
        val existing = company.id.takeIf { it > 0 }?.let { companyDao.getById(it) }
        val session = holder.current!!
        // Demonstração: só edita a própria empresa demo; nunca cria outra. A flag demo nunca muda por aqui.
        require(!session.user.demo || existing != null) { "A demonstração não permite criar empresas." }
        require(existing == null || existing.demo == session.user.demo) { "Empresa fora do seu acesso." }
        val id = companyDao.upsert(
            company.copy(cnpj = company.cnpj.filter { it.isDigit() }.ifEmpty { company.cnpj }, demo = existing?.demo ?: session.user.demo).toEntity(),
        )
        val saved = companyDao.getById(id)?.toDomain()
        // Mantém a sessão coerente se a empresa ativa foi editada (nome, IA preferida...).
        if (saved != null && holder.current?.activeCompany?.id == id) holder.update { it.copy(activeCompany = saved) }
        if (existing == null) {
            // Quem cria a empresa passa a ter acesso a ela.
            holder.current?.user?.let { user ->
                userDao.getById(user.id)?.let { entity ->
                    if (id !in entity.companyIds) {
                        userDao.upsert(entity.copy(companyIds = entity.companyIds + id))
                        holder.update { s -> s.copy(user = s.user.copy(companyIds = s.user.companyIds + id)) }
                    }
                }
            }
        }
        audit.record(
            AuditAction.CADASTRO,
            previousValue = existing?.tradeName,
            newValue = company.tradeName,
            details = if (existing == null) "Empresa criada" else "Empresa atualizada",
        )
        id
    }

    override suspend fun deleteCompany(id: Long) {
        withContext(Dispatchers.IO) {
            requireAdmin(id)
            val company = companyDao.getById(id) ?: return@withContext
            require(company.demo == holder.current!!.user.demo) { "Empresa fora do seu acesso." }
            if (holder.current?.activeCompany?.id == id) {
                audit.record(
                    AuditAction.CADASTRO, result = AuditResult.BLOQUEADO,
                    reason = "Empresa ativa não pode ser excluída", details = "Tentativa de excluir ${company.tradeName}",
                )
                return@withContext
            }
            db.withTransaction {
                db.radarDao().deleteByCompany(id)
                db.documentDao().deleteByCompany(id)
                db.tenderAnalysisDao().deleteByCompany(id)
                db.proposalDao().deleteByCompany(id)
                db.tenderDao().getByCompany(id).forEach { db.tenderDao().delete(it.id) }
                db.portalSessionDao().deleteByCompany(id)
                db.messageDao().deleteByCompany(id)
                db.competitionDao().deleteByCompany(id)
                db.bidEventDao().deleteByCompany(id)
                db.liveSessionDao().deleteByCompany(id)
                db.notificationDao().deleteByCompany(id)
                db.aiConfigDao().deleteByCompany(id)
                db.relevanceScoreDao().deleteByCompany(id)
                userDao.getAll().filter { id in it.companyIds }.forEach {
                    userDao.upsert(it.copy(companyIds = it.companyIds - id))
                }
                companyDao.delete(id)
            }
            holder.update { s -> s.copy(user = s.user.copy(companyIds = s.user.companyIds - id)) }
            audit.record(AuditAction.CADASTRO, previousValue = company.tradeName, details = "Empresa excluída")
        }
    }

    /** Usuários da empresa no mesmo "mundo" da sessão (demo vê só o usuário demo; real nunca vê o demo). */
    override fun observeUsers(companyId: Long): Flow<List<UserProfile>> =
        combine(userDao.observeAll(), holder.state) { list, session ->
            if (session?.user?.role != UserRole.ADMIN || companyId !in session.user.companyIds) emptyList()
            else list.filter { companyId in it.companyIds && it.demo == session.user.demo }.map { it.toDomain() }
        }

    /** Contas reais identificadas (ex.: Google) sem empresa. Nunca inclui o usuário demo; vazio na demonstração. */
    override fun observeUnassignedUsers(): Flow<List<UserProfile>> =
        combine(userDao.observeAll(), holder.state) { list, session ->
            if (session == null || session.user.demo || session.user.role != UserRole.ADMIN) emptyList()
            else list.filter { !it.demo && it.companyIds.isEmpty() }.map { it.toDomain() }
        }

    override suspend fun upsertUser(user: UserProfile, password: String?): Long = withContext(Dispatchers.IO) {
        requireAdmin()
        require(!holder.current!!.user.demo) { "A demonstração não permite criar ou vincular usuários." }
        require(user.companyIds.isNotEmpty() && user.companyIds.all { it in holder.current!!.user.companyIds }) { "Acesso a empresa não autorizado." }
        val email = user.email.trim().lowercase()
        require(email != DemoAccount.EMAIL) { "Este e-mail é reservado à demonstração." }
        val existing = user.id.takeIf { it > 0 }?.let { userDao.getById(it) }
        // Usuário existente: só os das minhas empresas ou os "aguardando vínculo" (sem empresa). Nunca o usuário demo.
        require(existing == null || (!existing.demo && existing.companyIds.all { it in holder.current!!.user.companyIds })) { "Usuário fora das suas empresas." }
        val clash = userDao.getByEmail(email)
        if (clash != null && clash.id != existing?.id) {
            throw IllegalArgumentException("Já existe um usuário com o e-mail $email.")
        }
        val hash = when {
            !password.isNullOrEmpty() -> hasher.hash(password)
            existing != null -> existing.passwordHash
            else -> "" // sem senha: não consegue entrar até que o administrador defina uma
        }
        // Provedor, identidade externa e flag demo nunca são alterados por aqui.
        val id = userDao.upsert(
            UserEntity(
                id = existing?.id ?: 0, name = user.name.trim(), email = email, role = user.role,
                companyIds = user.companyIds.distinct(), passwordHash = hash,
                provider = existing?.provider ?: "LOCAL", externalId = existing?.externalId, demo = existing?.demo ?: false,
            ),
        )
        if (holder.current?.user?.id == id) {
            userDao.getById(id)?.toDomain()?.let { updated -> holder.update { it.copy(user = updated) } }
        }
        audit.record(
            AuditAction.CADASTRO,
            previousValue = existing?.let { "${it.name} (${it.role.label})" },
            newValue = "${user.name} (${user.role.label})",
            details = if (existing == null) {
                "Usuário criado"
            } else {
                "Usuário atualizado" + if (!password.isNullOrEmpty()) " (senha redefinida)" else ""
            },
        )
        id
    }

    override suspend fun deleteUser(id: Long) {
        withContext(Dispatchers.IO) {
            requireAdmin()
            require(!holder.current!!.user.demo) { "A demonstração não permite remover usuários." }
            val user = userDao.getById(id) ?: return@withContext
            require(!user.demo && user.companyIds.isNotEmpty() && user.companyIds.all { it in holder.current!!.user.companyIds }) { "Usuário fora das suas empresas." }
            if (holder.current?.user?.id == id) {
                audit.record(AuditAction.CADASTRO, result = AuditResult.BLOQUEADO, reason = "Usuário logado não pode se excluir", details = user.email)
                return@withContext
            }
            userDao.delete(id)
            audit.record(AuditAction.CADASTRO, previousValue = user.email, details = "Usuário excluído")
        }
    }
    private fun requireAdmin(companyId: Long? = null) {
        val session = holder.current ?: throw IllegalStateException("Entre na sua conta.")
        require(session.user.role == UserRole.ADMIN) { "Somente o administrador da empresa pode alterar estes dados." }
        require(session.sameRealm()) { "Sessão inconsistente. Entre novamente." }
        require(companyId == null || companyId in session.user.companyIds) { "Empresa fora do seu acesso." }
    }
}



