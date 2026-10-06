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
import com.licitaia.core.security.PinLockoutPolicy
import com.licitaia.core.security.SecretStore
import com.licitaia.domain.auth.DemoAccountConflictException
import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.NoCompanyAccessException
import com.licitaia.domain.auth.NoLocalLinkException
import com.licitaia.domain.auth.PinVerification
import com.licitaia.domain.demo.DemoAccount
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        if (company.demo) { sessionPrefs.clear(); return@withContext null }
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
            // Contas reais nunca entram numa empresa de demonstração.
            val companies = user.companyIds.mapNotNull { companyDao.getById(it) }.filter { !it.demo }
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
            normalizedEmail == DemoAccount.EMAIL -> "Este e-mail é reservado à demonstração."
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
            if (byEmail != null && byEmail.externalId != null && byEmail.externalId != identity.subject) {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.LOGIN, result = AuditResult.BLOQUEADO, details = "Login Google recusado: e-mail já vinculado a outra identidade")
                return@withContext Result.failure(IllegalStateException("Este e-mail já está vinculado a outra identidade Google."))
            }
            if (byEmail != null && byEmail.externalId == null) {
                // Conta local com senha e mesmo e-mail: o vínculo exige a senha local (linkGoogleToLocal).
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.LOGIN, result = AuditResult.PENDENTE, details = "Login Google: conta local com o mesmo e-mail; vínculo aguarda a senha local")
                return@withContext Result.failure(NoLocalLinkException(email))
            }
            val userId = when {
                bySubject != null -> {
                    // Mantém perfil/empresas; atualiza apenas nome e e-mail informados pelo Google.
                    userDao.upsert(bySubject.copy(name = identity.name.ifBlank { bySubject.name }, email = email)); bySubject.id
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

            val companies = user.companyIds.mapNotNull { companyDao.getById(it) }.filter { !it.demo }
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
    override suspend fun linkGoogleToLocal(identity: GoogleIdentity, password: String, remember: Boolean): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            val email = identity.email.trim().lowercase()
            val provider = AuthProvider.GOOGLE.name
            if (identity.subject.isBlank() || !identity.emailVerified || !EMAIL.matches(email)) {
                return@withContext Result.failure(IllegalArgumentException("Conta Google sem identificação válida."))
            }
            val local = userDao.getByEmail(email)
            // Mesma mensagem para conta inexistente, demo, já vinculada ou senha errada (não revela cadastros).
            val eligible = local != null && !local.demo && local.externalId == null && local.passwordHash.isNotEmpty()
            if (!eligible || !hasher.verify(password, local!!.passwordHash)) {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.LOGIN, result = AuditResult.FALHA, details = "Vínculo Google com conta local recusado: senha inválida")
                return@withContext Result.failure(IllegalArgumentException("Senha inválida. O vínculo com o Google não foi feito."))
            }
            if (userDao.getByExternalId(provider, identity.subject)?.let { it.id != local.id } == true) {
                return@withContext Result.failure(IllegalStateException("Esta conta Google já está vinculada a outro usuário."))
            }
            // Mantém perfil, empresas e senha (continua podendo entrar por senha); só grava a identidade Google.
            userDao.upsert(local.copy(provider = provider, externalId = identity.subject, name = local.name.ifBlank { identity.name }))
            val user = userDao.getById(local.id)!!
            val companies = user.companyIds.mapNotNull { companyDao.getById(it) }.filter { !it.demo }
            val company = companies.firstOrNull() ?: run {
                audit.recordAs(user = email, companyId = null, companyName = "—", action = AuditAction.CADASTRO, details = "Conta Google vinculada à conta local (sem empresa)")
                return@withContext Result.failure(NoCompanyAccessException(email))
            }
            val authSession = AuthSession(user.toDomain(), company.toDomain())
            holder.set(authSession)
            if (remember) sessionPrefs.save(user.id, company.id) else sessionPrefs.clear()
            audit.record(AuditAction.CADASTRO, newValue = email, details = "Conta Google vinculada à conta local (senha confirmada)")
            audit.record(AuditAction.LOGIN, details = "Login Google (vínculo com conta local)" + if (remember) " (sessão lembrada)" else "")
            Result.success(authSession)
        }

    // ------------------------------------------------------------------ demonstração isolada

    override suspend fun loginDemo(): Result<AuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            val workspace = seeder.seedDemoWorkspace()
            enterDemo(workspace, "Entrada na demonstração")
        }
    }

    override suspend fun exitDemo(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val current = holder.current
            check(current == null || current.user.demo) { "Só a sessão de demonstração pode encerrá-la." }
            if (current != null) audit.record(AuditAction.LOGOUT, details = "Saída da demonstração (espaço demo apagado)")
            seeder.purgeDemoWorkspace()
            sessionPrefs.clear()
            holder.set(null)
        }
    }

    override suspend fun resetDemo(): Result<AuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            val current = holder.current
            check(current != null && current.user.demo) { "Só a sessão de demonstração pode reiniciá-la." }
            audit.record(AuditAction.CONFIGURACAO, details = "Demonstração reiniciada (espaço demo recriado)")
            seeder.purgeDemoWorkspace()
            val workspace = seeder.seedDemoWorkspace()
            enterDemo(workspace, "Entrada na demonstração (reiniciada)")
        }
    }

    private suspend fun enterDemo(workspace: DatabaseSeeder.DemoWorkspace, details: String): AuthSession {
        val user = userDao.getById(workspace.userId)!!
        val company = companyDao.getById(workspace.companyId)!!
        check(user.demo && company.demo && company.id in user.companyIds) { "Espaço de demonstração inconsistente." }
        val session = AuthSession(user.toDomain(), company.toDomain())
        holder.set(session)
        // A demonstração nunca é lembrada: ao reabrir o app, volta-se ao login.
        sessionPrefs.clear()
        audit.record(AuditAction.LOGIN, details = details)
        return session
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
        if (company.demo != current.user.demo) {
            audit.record(AuditAction.TROCA_EMPRESA, result = AuditResult.BLOQUEADO, details = "Troca bloqueada: empresa fora do espaço da sessão (demo x real)")
            return@withContext Result.failure(IllegalArgumentException("Você não tem acesso a esta empresa."))
        }
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

    override suspend fun verifyPin(pin: String): Boolean = verifyPinDetailed(pin) is PinVerification.Success

    /**
     * Verificação com bloqueio progressivo ([PinLockoutPolicy]): a partir do 5º erro consecutivo o PIN fica
     * bloqueado por 30 s, 1 min, 5 min… O estado (erros + instante de desbloqueio) fica no cofre cifrado,
     * portanto sobrevive ao fechamento do app. Enquanto bloqueado, o PIN nem é comparado. Falhas e bloqueios
     * são auditados sem o PIN digitado.
     */
    override suspend fun verifyPinDetailed(pin: String): PinVerification = pinLock.withLock {
        val now = System.currentTimeMillis()
        val state = PinLockoutPolicy.State.parse(secretStore.get(PIN_LOCKOUT_KEY))
        if (PinLockoutPolicy.isLocked(state, now)) {
            val remaining = PinLockoutPolicy.remainingMillis(state, now)
            audit.record(
                AuditAction.LOGIN, result = AuditResult.BLOQUEADO,
                details = "Desbloqueio por PIN recusado: bloqueio temporário ativo (${state.failures} erros consecutivos, ${remaining / 1000 + 1}s restantes)",
            )
            return@withLock PinVerification.Locked(remaining)
        }
        val stored = secretStore.get(PIN_KEY)
        val ok = stored != null && withContext(Dispatchers.Default) { hasher.verify(pin, stored) }
        if (ok) {
            if (state != PinLockoutPolicy.State.NONE) secretStore.remove(PIN_LOCKOUT_KEY)
            return@withLock PinVerification.Success
        }
        val next = PinLockoutPolicy.onFailure(state, now)
        secretStore.put(PIN_LOCKOUT_KEY, next.serialize())
        val lockMs = PinLockoutPolicy.remainingMillis(next, now)
        if (lockMs > 0L) {
            audit.record(
                AuditAction.LOGIN, result = AuditResult.BLOQUEADO,
                details = "PIN incorreto (${next.failures}ª tentativa consecutiva): bloqueado por ${lockMs / 1000}s",
            )
            PinVerification.Locked(lockMs)
        } else {
            audit.record(
                AuditAction.LOGIN, result = AuditResult.FALHA,
                details = "PIN incorreto (${next.failures} de ${PinLockoutPolicy.FREE_ATTEMPTS} tentativas antes do bloqueio)",
            )
            PinVerification.Wrong(PinLockoutPolicy.remainingAttempts(next))
        }
    }

    private val pinLock = Mutex()

    private companion object {
        const val PIN_KEY = "auth.pin_hash"
        const val PIN_LOCKOUT_KEY = "auth.pin_lockout"
        const val PIN_ITERATIONS = 60_000
        val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}




