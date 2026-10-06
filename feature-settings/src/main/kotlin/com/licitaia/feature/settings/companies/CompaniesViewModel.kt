package com.licitaia.feature.settings.companies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.model.AiProviderType
import com.licitaia.domain.model.AuthSession
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Segment
import com.licitaia.domain.model.UserProfile
import com.licitaia.domain.model.UserRole
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompanyRepository
import com.licitaia.domain.security.Permission
import com.licitaia.domain.security.Rbac
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

val BRAZIL_UFS = listOf(
    "AC", "AL", "AM", "AP", "BA", "CE", "DF", "ES", "GO", "MA", "MG", "MS", "MT", "PA", "PB", "PE", "PI",
    "PR", "RJ", "RN", "RO", "RR", "RS", "SC", "SE", "SP", "TO",
)

data class CompanyForm(
    val id: Long = 0,
    val name: String = "",
    val tradeName: String = "",
    /** Somente dígitos (até 14). */
    val cnpjDigits: String = "",
    val segment: Segment = Segment.TELECOM_ISP,
    val uf: String = "SP",
    val city: String = "",
    val preferredAi: AiProviderType? = null,
    val errors: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
) {
    val isNew get() = id == 0L

    companion object {
        fun from(c: Company) = CompanyForm(
            id = c.id, name = c.name, tradeName = c.tradeName, cnpjDigits = c.cnpj.filter { it.isDigit() }.take(14),
            segment = c.segment, uf = c.uf, city = c.city, preferredAi = c.preferredAi,
        )
    }
}

data class UserForm(
    val id: Long = 0,
    val name: String = "",
    val email: String = "",
    val role: UserRole = UserRole.LICITACOES,
    val password: String = "",
    val companyIds: List<Long> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
) {
    val isNew get() = id == 0L

    companion object {
        fun from(u: UserProfile) = UserForm(id = u.id, name = u.name, email = u.email, role = u.role, companyIds = u.companyIds)
    }
}

data class CompaniesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val noSession: Boolean = false,
    val session: AuthSession? = null,
    val canManage: Boolean = false,
    val companies: List<Company> = emptyList(),
    val users: List<UserProfile> = emptyList(),
    /** Contas reais identificadas (ex.: Google) sem nenhuma empresa — só para quem pode gerenciar. */
    val unassignedUsers: List<UserProfile> = emptyList(),
    val companyForm: CompanyForm? = null,
    val userForm: UserForm? = null,
    val switchingTo: Long? = null,
    val confirmDeleteUser: UserProfile? = null,
) {
    fun canSwitchTo(company: Company): Boolean {
        val s = session ?: return false
        return company.id != s.activeCompany.id && (company.id in s.user.companyIds || s.user.role == UserRole.ADMIN)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompaniesViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val companies: CompanyRepository,
) : ViewModel() {

    private val local = MutableStateFlow(CompaniesUiState())
    private val retry = MutableStateFlow(0)
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<CompaniesUiState> = combine(auth.session, retry) { s, _ -> s }
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(CompaniesUiState(loading = false, noSession = true))
            } else {
                combine(
                    companies.observeCompanies(), companies.observeUsers(session.activeCompany.id),
                    companies.observeUnassignedUsers(), local,
                ) { list, users, unassigned, l ->
                    val canManage = Rbac.can(session.user.role, Permission.GERENCIAR_EMPRESAS)
                    l.copy(
                        loading = false, error = null, session = session,
                        canManage = canManage,
                        companies = list.sortedWith(compareByDescending<Company> { it.id == session.activeCompany.id }.thenBy { it.tradeName.ifBlank { it.name } }),
                        users = users.sortedBy { it.name },
                        unassignedUsers = if (canManage) unassigned.sortedBy { it.name } else emptyList(),
                    )
                }.catch { emit(CompaniesUiState(loading = false, error = it.message ?: "Falha ao carregar as empresas.")) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompaniesUiState())

    fun retry() = retry.update { it + 1 }

    // ------------------------------------------------------------------ troca de empresa

    fun switchCompany(company: Company) {
        val s = state.value
        if (!s.canSwitchTo(company) || s.switchingTo != null) return
        local.update { it.copy(switchingTo = company.id) }
        viewModelScope.launch {
            runCatching { auth.switchCompany(company.id).getOrThrow() }
                .onSuccess { _events.send("Empresa ativa: ${company.tradeName.ifBlank { company.name }}") }
                .onFailure { _events.send(it.message ?: "Não foi possível trocar de empresa") }
            local.update { it.copy(switchingTo = null) }
        }
    }

    // ------------------------------------------------------------------ empresa

    fun newCompany() {
        if (!state.value.canManage) return
        local.update { it.copy(companyForm = CompanyForm()) }
    }

    fun editCompany(company: Company) {
        if (!state.value.canManage) return
        local.update { it.copy(companyForm = CompanyForm.from(company)) }
    }

    fun dismissCompanyForm() = local.update { if (it.companyForm?.busy == true) it else it.copy(companyForm = null) }

    fun updateCompanyForm(transform: (CompanyForm) -> CompanyForm) {
        local.update { s -> s.copy(companyForm = s.companyForm?.let { f -> transform(f).copy(errors = emptyMap()) }) }
    }

    fun saveCompany() {
        val form = local.value.companyForm ?: return
        val session = state.value.session ?: return
        if (form.busy || !state.value.canManage) return
        val errors = buildMap {
            if (form.name.isBlank()) put("name", "Informe a razão social")
            if (form.cnpjDigits.length != 14) put("cnpj", "O CNPJ deve ter 14 dígitos")
            if (form.city.isBlank()) put("city", "Informe a cidade")
            val duplicate = state.value.companies.any { it.id != form.id && it.cnpj.filter { c -> c.isDigit() } == form.cnpjDigits }
            if (duplicate) put("cnpj", "Já existe uma empresa com este CNPJ")
        }
        if (errors.isNotEmpty()) {
            local.update { it.copy(companyForm = form.copy(errors = errors)) }
            return
        }
        local.update { it.copy(companyForm = form.copy(busy = true)) }
        viewModelScope.launch {
            val company = Company(
                id = form.id, name = form.name.trim(), tradeName = form.tradeName.trim().ifBlank { form.name.trim() },
                cnpj = form.cnpjDigits, segment = form.segment, uf = form.uf, city = form.city.trim(), preferredAi = form.preferredAi,
            )
            // O repositório concede acesso a quem cria a empresa e registra a auditoria (CADASTRO).
            runCatching { companies.upsertCompany(company) }
                .onSuccess {
                    local.update { it.copy(companyForm = null) }
                    _events.send(if (form.isNew) "Empresa cadastrada" else "Empresa atualizada")
                }
                .onFailure { e ->
                    local.update { it.copy(companyForm = form.copy(busy = false, errors = mapOf("form" to (e.message ?: "Não foi possível salvar")))) }
                }
        }
    }

    // ------------------------------------------------------------------ usuários

    fun newUser() {
        val s = state.value
        if (!s.canManage) return
        val companyId = s.session?.activeCompany?.id ?: return
        local.update { it.copy(userForm = UserForm(companyIds = listOf(companyId))) }
    }

    fun editUser(user: UserProfile) {
        if (!state.value.canManage) return
        local.update { it.copy(userForm = UserForm.from(user)) }
    }

    /** Abre o editor já com a empresa ativa adicionada; o administrador escolhe o perfil antes de salvar. */
    fun linkUserToActiveCompany(user: UserProfile) {
        val s = state.value
        if (!s.canManage) return
        val companyId = s.session?.activeCompany?.id ?: return
        local.update { it.copy(userForm = UserForm.from(user).copy(companyIds = (user.companyIds + companyId).distinct())) }
    }

    fun dismissUserForm() = local.update { if (it.userForm?.busy == true) it else it.copy(userForm = null) }

    fun updateUserForm(transform: (UserForm) -> UserForm) {
        local.update { s -> s.copy(userForm = s.userForm?.let { f -> transform(f).copy(errors = emptyMap()) }) }
    }

    fun saveUser() {
        val form = local.value.userForm ?: return
        val s = state.value
        if (form.busy || !s.canManage) return
        val email = form.email.trim().lowercase()
        val errors = buildMap {
            if (form.name.isBlank()) put("name", "Informe o nome")
            if (!email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) put("email", "E-mail inválido")
            if (form.isNew && form.password.length < 6) put("password", "A senha deve ter ao menos 6 caracteres")
            if (!form.isNew && form.password.isNotEmpty() && form.password.length < 6) put("password", "A nova senha deve ter ao menos 6 caracteres")
            if (s.users.any { it.id != form.id && it.email.equals(email, ignoreCase = true) }) put("email", "Já existe um usuário com este e-mail")
            if (!form.isNew && form.id == s.session?.user?.id && form.role != s.session.user.role) put("role", "Você não pode alterar o próprio perfil")
        }
        if (errors.isNotEmpty()) {
            local.update { it.copy(userForm = form.copy(errors = errors)) }
            return
        }
        local.update { it.copy(userForm = form.copy(busy = true)) }
        viewModelScope.launch {
            val user = UserProfile(id = form.id, name = form.name.trim(), email = email, role = form.role, companyIds = form.companyIds.distinct())
            runCatching { companies.upsertUser(user, form.password.takeIf { it.isNotEmpty() }) }
                .onSuccess {
                    local.update { it.copy(userForm = null) }
                    _events.send(if (form.isNew) "Usuário criado" else "Usuário atualizado")
                }
                .onFailure { e ->
                    local.update { it.copy(userForm = form.copy(busy = false, password = "", errors = mapOf("form" to (e.message ?: "Não foi possível salvar")))) }
                }
        }
    }

    fun askDeleteUser(user: UserProfile?) = local.update { it.copy(confirmDeleteUser = user) }

    fun deleteUserConfirmed() {
        val user = local.value.confirmDeleteUser ?: return
        val s = state.value
        local.update { it.copy(confirmDeleteUser = null) }
        if (!s.canManage || user.id == s.session?.user?.id) return
        viewModelScope.launch {
            runCatching { companies.deleteUser(user.id) }
                .onSuccess {
                    _events.send("Usuário removido")
                }
                .onFailure { _events.send(it.message ?: "Não foi possível remover o usuário") }
        }
    }
}

/** Validação dos dígitos verificadores do CNPJ (14 dígitos). */
internal fun isValidCnpj(digits: String): Boolean {
    if (digits.length != 14 || digits.all { it == digits[0] }) return false
    fun dv(base: String, weights: IntArray): Int {
        val sum = base.indices.sumOf { (base[it] - '0') * weights[it] }
        val r = sum % 11
        return if (r < 2) 0 else 11 - r
    }
    val w1 = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    val w2 = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    val d1 = dv(digits.substring(0, 12), w1)
    val d2 = dv(digits.substring(0, 12) + d1, w2)
    return digits[12] - '0' == d1 && digits[13] - '0' == d2
}
