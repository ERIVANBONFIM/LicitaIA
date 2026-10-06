package com.licitaia.core.data.repository

import androidx.room.withTransaction
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.UserDao
import com.licitaia.core.data.db.UserEntity
import com.licitaia.core.data.db.toDomain
import com.licitaia.core.data.db.toEntity
import com.licitaia.core.data.seed.DatabaseSeeder
import com.licitaia.core.data.session.SessionHolder
import com.licitaia.core.data.settings.SessionPrefs
import com.licitaia.core.security.PasswordHasher
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.auth.DemoAccountConflictException
import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.NoCompanyAccessException
import com.licitaia.domain.model.AuditAction
import com.licitaia.domain.model.AuthProvider
import com.licitaia.domain.model.AuditResult
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val db: LicitaDatabase,
    private val holder: SessionHolder,
    private val userDao: UserDao,
    private val companyDao: CompanyDao,
    private val hasher: PasswordHasher,
    private val secretStore: SecretStore,
    private val sessionPrefs: SessionPrefs,
    private val audit: AuditRepositoryImpl,
    private val seeder: DatabaseSeeder,
) : AuthRepository {

    override val session: StateFlow<AuthSession?> = holder.state

    override suspend fun restoreSession(): AuthSession? = withContext(Dispatchers.IO) {
        seeder.ensureSeeded()
        holder.current?.let { return@withContext it }
        val remembered = sessionPrefs.read() ?: return@withContext null
        val user = userDao.getById(remembered.userId) ?: run { sessionPrefs.clear(); return@withContext null }
        if (user.demo) { sessionPrefs.clear(); return@withContext null }
        val companyId = remembered.companyId.takeIf { it in user.companyIds } ?: user.companyIds.firstOrNull()
        val company = companyId?.let { companyDao.getById(it) } ?: run { sessionPrefs.clear(); return@withContext null }
        AuthSession(user.toDomain(), company.toDomain()).also(holder::set)
    }

    override suspend fun login(email: String, password: String, companyId: Long?, remember: Boolean): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            seeder.ensureSeeded()
            val normalized = email.trim().lowercase()
            val user = userDao.getByEmail(normalized)
            // Mesma mensagem para e-mail inexistente e senha errada (não revela cadastros).
            if (user == null || user.demo || user.passwordHash.isEmpty() || !hasher.verify(password, user.passwordHash)) {
                audit.recordAs(
                    user = normalized.ifEmpty { "—" }, companyId = null, companyName = "—",
                    action = AuditAction.LOGIN, result = AuditResult.FALHA, details = "Credenciais inválidas",
                )
                return@withContext Result.failure(IllegalArgumentException("E-mail ou senha inválidos."))
            }
            val companies = user.companyIds.mapNotNull { companyDao.getById(it) }
            val company = companies.firstOrNull { it.id == companyId } ?: companies.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Usuário sem empresa vinculada. Contate o administrador."))

            val authSession = AuthSession(user.toDomain(), company.toDomain())
            holder.set(authSession)
            if (remember) sessionPrefs.save(user.id, company.id) else sessionPrefs.clear()
            audit.record(AuditAction.LOGIN, details = "Login local" + if (remember) " (sessão lembrada)" else "")
            Result.success(authSession)
        }

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        companyName: String,
        cnpj: String,
    ): Result<AuthSession> = withContext(Dispatchers.IO) {
        seeder.ensureSeeded()
        val normalizedEmail = email.trim().lowercase()
        val digits = cnpj.filter { it.isDigit() }
        val error = when {
            name.trim().length < 3 -> "Informe o nome completo."
            !EMAIL.matches(normalizedEmail) -> "Informe um e-mail válido."
            password.length < 8 -> "A senha deve ter pelo menos 8 caracteres."
            companyName.trim().length < 3 -> "Informe a razão social da empresa."
            !validCnpj(digits) -> "Informe um CNPJ válido."
            userDao.getByEmail(normalizedEmail) != null -> "Já existe uma conta com este e-mail."
            else -> null
        }
        if (error != null) return@withContext Result.failure(IllegalArgumentException(error))

        val companyId = companyDao.upsert(
            Company(
                name = companyName.trim(), tradeName = companyName.trim(), cnpj = digits,
                segment = Segment.PERSONALIZADO, uf = "", city = "",
            ).toEntity(),
        )
        val userId = userDao.upsert(
            UserEntity(
                name = name.trim(), email = normalizedEmail, role = UserRole.ADMIN,
                companyIds = listOf(companyId), passwordHash = hasher.hash(password),
            ),
        )
        val user = userDao.getById(userId)!!.toDomain()
        val company = companyDao.getById(companyId)!!.toDomain()
        val authSession = AuthSession(user, company)
        holder.set(authSession)
        sessionPrefs.save(userId, companyId)
        audit.record(AuditAction.CADASTRO, details = "Conta local criada: empresa \"${company.name}\" e usuário ADMIN")
        audit.record(AuditAction.LOGIN, details = "Login local (cadastro)")
        Result.success(authSession)
    }

    override suspend fun loginWithGoogle(identity: GoogleIdentity, remember: Boolean): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            seeder.ensureSeeded()
            val email = identity.email.trim().lowercase()
            val provider = AuthProvider.GOOGLE.name
            if (identity.subject.isBlank() || !EMAIL.matches(email)) {
                return@withContext Result.failure(IllegalArgumentException("Conta Google sem identificação válida."))
            }
            if (!identity.emailVerified) {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.LOGIN, result = AuditResult.BLOQUEADO, details = "Login Google: e-mail não verificado")
                return@withContext Result.failure(IllegalStateException("O e-mail desta conta Google não está verificado."))
            }

            // 1) identidade já conhecida pelo `sub`; 2) conta local real com o mesmo e-mail verificado → vincula;
            // 3) e-mail de conta demo → recusa (dados demo nunca se misturam com contas reais); 4) novo usuário SEM empresa.
            val bySubject = userDao.getByExternalId(provider, identity.subject)
            if (bySubject?.demo == true) return@withContext Result.failure(IllegalStateException("Conta de demonstração desativada."))
            val byEmail = if (bySubject == null) userDao.getByEmail(email) else null
            if (byEmail?.demo == true) {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.LOGIN, result = AuditResult.BLOQUEADO, details = "Login Google recusado: e-mail da conta demo")
                return@withContext Result.failure(DemoAccountConflictException(email))
            }
            if (byEmail != null && (byEmail.externalId == null || byEmail.externalId != identity.subject)) {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.LOGIN, result = AuditResult.BLOQUEADO, details = "Login Google recusado: e-mail já vinculado a outra identidade")
                return@withContext Result.failure(IllegalStateException("Este e-mail já possui conta local. Entre com sua senha; o vínculo automático foi bloqueado."))
            }
            val userId = when {
                bySubject != null -> {
                    // Mantém perfil/empresas; atualiza apenas nome e e-mail informados pelo Google.
                    userDao.upsert(bySubject.copy(name = identity.name.ifBlank { bySubject.name }, email = email)); bySubject.id
                }
                byEmail != null -> {
                    userDao.upsert(byEmail.copy(provider = provider, externalId = identity.subject)); byEmail.id
                }
                else -> userDao.upsert(
                    UserEntity(
                        name = identity.name.ifBlank { email.substringBefore('@') }, email = email,
                        // Perfil operacional mínimo e NENHUMA empresa: o administrador decide o acesso.
                        role = UserRole.LICITACOES, companyIds = emptyList(), passwordHash = "",
                        provider = provider, externalId = identity.subject, demo = false,
                    ),
                )
            }
            val user = userDao.getById(userId)!!
            if (byEmail == null && bySubject == null) {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.CADASTRO, details = "Usuário identificado via Google (sem empresa vinculada)")
            }

            val companies = user.companyIds.mapNotNull { companyDao.getById(it) }
            val company = companies.firstOrNull() ?: run {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.LOGIN, result = AuditResult.PENDENTE, details = "Login Google: aguardando vínculo com empresa")
                return@withContext Result.failure(NoCompanyAccessException(email))
            }
            val authSession = AuthSession(user.toDomain(), company.toDomain())
            holder.set(authSession)
            if (remember) sessionPrefs.save(user.id, company.id) else sessionPrefs.clear()
            audit.record(AuditAction.LOGIN, details = "Login Google" + if (remember) " (sessão lembrada)" else "")
            Result.success(authSession)
        }

    override suspend fun createGoogleCompany(identity: GoogleIdentity, companyName: String, cnpj: String, remember: Boolean): Result<AuthSession> = withContext(Dispatchers.IO) {
        val user = userDao.getByExternalId(AuthProvider.GOOGLE.name, identity.subject)
            ?: return@withContext Result.failure(IllegalStateException("Entre novamente com Google."))
        if (user.demo || user.companyIds.isNotEmpty() || !identity.emailVerified) return@withContext Result.failure(IllegalStateException("Conta não elegível para criar a primeira empresa."))
        val digits = cnpj.filter(Char::isDigit)
        if (companyName.trim().length < 3 || !validCnpj(digits)) return@withContext Result.failure(IllegalArgumentException("Informe razão social e CNPJ válidos."))
        val companyId = db.withTransaction {
        require(userDao.getById(user.id)?.companyIds?.isEmpty() == true) { "Conta já possui empresa." }
        val createdId = companyDao.upsert(Company(name = companyName.trim(), tradeName = companyName.trim(), cnpj = digits, segment = Segment.PERSONALIZADO, uf = "", city = "").toEntity())
        userDao.upsert(user.copy(role = UserRole.ADMIN, companyIds = listOf(createdId)))
        createdId
        }
        val session = AuthSession(userDao.getById(user.id)!!.toDomain(), companyDao.getById(companyId)!!.toDomain())
        holder.set(session)
        if (remember) sessionPrefs.save(user.id, companyId) else sessionPrefs.clear()
        audit.record(AuditAction.CADASTRO, details = "Primeira empresa criada pela conta Google; ADMIN somente de sua empresa")
        Result.success(session)
    }
    override suspend fun logout() {
        withContext(Dispatchers.IO) {
            holder.current?.let { audit.record(AuditAction.LOGOUT, details = "Logout (${it.user.provider.label})") }
            sessionPrefs.clear()
            holder.set(null)
        }
    }

    override suspend fun switchCompany(companyId: Long): Result<AuthSession> = withContext(Dispatchers.IO) {
        val current = holder.current
            ?: return@withContext Result.failure(IllegalStateException("Nenhuma sessão ativa."))
        if (companyId !in current.user.companyIds) {
            audit.record(AuditAction.TROCA_EMPRESA, result = AuditResult.BLOQUEADO, details = "Usuário sem acesso à empresa $companyId")
            return@withContext Result.failure(IllegalArgumentException("Você não tem acesso a esta empresa."))
        }
        val company = companyDao.getById(companyId)?.toDomain()
            ?: return@withContext Result.failure(IllegalArgumentException("Empresa não encontrada."))
        if (company.id == current.activeCompany.id) return@withContext Result.success(current)

        val next = current.copy(activeCompany = company)
        holder.set(next)
        sessionPrefs.updateCompany(company.id)
        audit.record(
            AuditAction.TROCA_EMPRESA,
            previousValue = current.activeCompany.tradeName,
            newValue = company.tradeName,
            details = "Empresa ativa alterada",
        )
        Result.success(next)
    }

    override suspend fun setPin(pin: String?) {
        if (pin.isNullOrBlank()) {
            secretStore.remove(PIN_KEY)
            audit.record(AuditAction.CONFIGURACAO, details = "PIN de desbloqueio removido")
        } else {
            secretStore.put(PIN_KEY, withContext(Dispatchers.Default) { hasher.hash(pin, PIN_ITERATIONS) })
            audit.record(AuditAction.CONFIGURACAO, details = "PIN de desbloqueio definido")
        }
    }

    override suspend fun hasPin(): Boolean = secretStore.contains(PIN_KEY)

    override suspend fun verifyPin(pin: String): Boolean {
        val stored = secretStore.get(PIN_KEY) ?: return false
        return withContext(Dispatchers.Default) { hasher.verify(pin, stored) }
    }

    private companion object {
        const val PIN_KEY = "auth.pin_hash"
        const val PIN_ITERATIONS = 60_000
        val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}




