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
import com.licitaia.domain.util.BrDocuments
import dagger.hilt.android.lifecycle.HiltViewModel
import com.licitaia.domain.lookup.CompanyLookup
import com.licitaia.domain.lookup.LookupException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** Espera após a última tecla antes de consultar CNPJ/CEP. */
internal const val LOOKUP_DEBOUNCE_MS = 600L

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
    // Endereço / contato / representante legal / banco (saem na proposta em PDF; todos opcionais).
    val street: String = "",
    val complement: String = "",
    val district: String = "",
    /** Somente dígitos (até 8). */
    val zipDigits: String = "",
    /** Somente dígitos, com DDD (até 11). */
    val phoneDigits: String = "",
    val email: String = "",
    val legalRepName: String = "",
    /** Somente dígitos (até 11). */
    val legalRepCpfDigits: String = "",
    val legalRepRole: String = "",
    val bankName: String = "",
    val bankAgency: String = "",
    val bankAccount: String = "",
    /** Declarações padrão do Compras.gov (o robô de proposta só as aplica com autorização na confirmação). */
    val declarations: com.licitaia.domain.model.PortalDeclarations = com.licitaia.domain.model.PortalDeclarations(),
    val errors: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    // ---- Preenchimento automático (CNPJ/CEP). Nada é salvo até tocar em Salvar.
    /** O usuário já escolheu o segmento (ou a empresa já existe): a sugestão pelo CNAE não o altera. */
    val segmentTouched: Boolean = false,
    /** Campo → fonte ("Receita"/"CEP") dos valores que vieram da consulta e ainda não foram editados. */
    val autoFilled: Map<String, String> = emptyMap(),
    val cnpjLookup: LookupStatus = LookupStatus(),
    val zipLookup: LookupStatus = LookupStatus(),
    /** Última consulta do CNPJ, para oferecer "Atualizar com os dados da Receita". */
    val cnpjResult: com.licitaia.domain.lookup.CnpjData? = null,
    val cnpjConflicts: List<FieldConflict> = emptyList(),
    val confirmOverwrite: Boolean = false,
    /** Últimos valores já consultados automaticamente (evita repetir a consulta). */
    val lastCnpjLookup: String = "",
    val lastZipLookup: String = "",
) {
    val isNew get() = id == 0L

    companion object {
        fun from(c: Company) = fromCompany(c).let {
            // Empresa existente: não consulta de novo sozinha ao abrir (há o botão "Buscar dados do CNPJ").
            it.copy(segmentTouched = true, lastCnpjLookup = it.cnpjDigits, lastZipLookup = it.zipDigits)
        }

        private fun fromCompany(c: Company) = CompanyForm(
            id = c.id, name = c.name, tradeName = c.tradeName, cnpjDigits = c.cnpj.filter { it.isDigit() }.take(14),
            segment = c.segment, uf = c.uf, city = c.city, preferredAi = c.preferredAi,
            street = c.street, complement = c.complement, district = c.district,
            zipDigits = BrDocuments.digits(c.zipCode).take(8), phoneDigits = BrDocuments.digits(c.phone).take(11), email = c.email,
            legalRepName = c.legalRepName, legalRepCpfDigits = BrDocuments.digits(c.legalRepCpf).take(11), legalRepRole = c.legalRepRole,
            bankName = c.bankName, bankAgency = c.bankAgency, bankAccount = c.bankAccount,
            declarations = c.portalDeclarations,
        )
    }

    /** Empresa a persistir (textos aparados); preserva o que não é editado aqui (id, flag demo é imposta pelo repositório). */
    fun toCompany() = Company(
        id = id, name = name.trim(), tradeName = tradeName.trim().ifBlank { name.trim() },
        cnpj = cnpjDigits, segment = segment, uf = uf, city = city.trim(), preferredAi = preferredAi,
        street = street.trim(), complement = complement.trim(), district = district.trim(), zipCode = zipDigits,
        phone = phoneDigits, email = email.trim(), legalRepName = legalRepName.trim(), legalRepCpf = legalRepCpfDigits,
        legalRepRole = legalRepRole.trim(), bankName = bankName.trim(), bankAgency = bankAgency.trim(), bankAccount = bankAccount.trim(),
        portalDeclarations = declarations,
    )
}

/**
 * Validação do formulário da empresa (chave do campo → mensagem). Endereço, contato, representante e banco são
 * opcionais, mas, se preenchidos, precisam estar corretos: CEP com 8 dígitos, CPF com dígitos verificadores válidos,
 * e-mail com @ e domínio, telefone com DDD.
 */
internal fun validateCompanyForm(form: CompanyForm, companies: List<Company>): Map<String, String> = buildMap {
    if (form.name.isBlank()) put("name", "Informe a razão social")
    if (form.cnpjDigits.length != 14) put("cnpj", "O CNPJ deve ter 14 dígitos")
    if (form.city.isBlank()) put("city", "Informe a cidade")
    val duplicate = companies.any { it.id != form.id && it.cnpj.filter { c -> c.isDigit() } == form.cnpjDigits }
    if (duplicate) put("cnpj", "Já existe uma empresa com este CNPJ")
    if (form.zipDigits.isNotEmpty() && !BrDocuments.isValidCep(form.zipDigits)) put("zip", "O CEP deve ter 8 dígitos")
    if (form.phoneDigits.isNotEmpty() && !BrDocuments.isValidPhone(form.phoneDigits)) put("phone", "Informe o telefone com DDD")
    if (form.email.isNotBlank() && !BrDocuments.isValidEmail(form.email)) put("email", "E-mail inválido")
    if (form.legalRepCpfDigits.isNotEmpty() && !BrDocuments.isValidCpf(form.legalRepCpfDigits)) put("cpf", "CPF inválido")
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
    /** Sessão de demonstração: pode editar a própria empresa demo, mas não criar empresas nem gerir usuários. */
    val demo: Boolean = false,
    val companies: List<Company> = emptyList(),
    val users: List<UserProfile> = emptyList(),
    /** Contas reais identificadas (ex.: Google) sem nenhuma empresa — só para quem pode gerenciar. */
    val unassignedUsers: List<UserProfile> = emptyList(),
    val companyForm: CompanyForm? = null,
    val userForm: UserForm? = null,
    val switchingTo: Long? = null,
    val confirmDeleteUser: UserProfile? = null,
) {
    val canCreateCompany: Boolean get() = canManage && !demo
    val canManageUsers: Boolean get() = canManage && !demo

    fun canSwitchTo(company: Company): Boolean {
        val s = session ?: return false
        return company.id != s.activeCompany.id && company.demo == s.user.demo && (company.id in s.user.companyIds || s.user.role == UserRole.ADMIN)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompaniesViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val companies: CompanyRepository,
    private val companyLookup: CompanyLookup,
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
                        canManage = canManage, demo = session.user.demo,
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
        if (!state.value.canCreateCompany) return
        local.update { it.copy(companyForm = CompanyForm()) }
    }

    fun editCompany(company: Company) {
        if (!state.value.canManage) return
        local.update { it.copy(companyForm = CompanyForm.from(company)) }
    }

    fun dismissCompanyForm() {
        if (local.value.companyForm?.busy == true) return
        cnpjJob?.cancel()
        zipJob?.cancel()
        local.update { it.copy(companyForm = null) }
    }

    /**
     * Edição de um campo do formulário. [field] = chave do campo editado pelo usuário: deixa de ser marcado como
     * "preenchido pela Receita/CEP". CNPJ ou CEP completos disparam a consulta automática (com debounce).
     */
    fun updateCompanyForm(field: String? = null, transform: (CompanyForm) -> CompanyForm) {
        val before = local.value.companyForm ?: return
        local.update { s ->
            s.copy(
                companyForm = s.companyForm?.let { f ->
                    val t = transform(f)
                    val marks = if (field != null) t.autoFilled - field else t.autoFilled
                    t.copy(errors = emptyMap(), autoFilled = marks, segmentTouched = t.segmentTouched || field == "segment")
                },
            )
        }
        val after = local.value.companyForm ?: return
        if (after.cnpjDigits != before.cnpjDigits) onCnpjChanged(after)
        if (after.zipDigits != before.zipDigits) onZipChanged(after)
    }

    // ------------------------------------------------------------------ consulta CNPJ / CEP

    private var cnpjJob: Job? = null
    private var zipJob: Job? = null

    private fun updateForm(transform: (CompanyForm) -> CompanyForm) =
        local.update { s -> s.copy(companyForm = s.companyForm?.let(transform)) }

    private fun onCnpjChanged(form: CompanyForm) {
        cnpjJob?.cancel()
        val digits = form.cnpjDigits
        if (digits.length != 14 || !isValidCnpj(digits)) {
            updateForm { it.copy(cnpjLookup = LookupStatus(), cnpjConflicts = emptyList(), cnpjResult = null) }
            return
        }
        if (digits == form.lastCnpjLookup) return
        cnpjJob = viewModelScope.launch {
            delay(LOOKUP_DEBOUNCE_MS)
            runCnpjLookup(digits)
        }
    }

    /** Botão "Buscar dados do CNPJ": consulta agora, mesmo se já consultado. */
    fun lookupCnpjNow() {
        val form = local.value.companyForm ?: return
        if (form.busy) return
        if (!isValidCnpj(form.cnpjDigits)) {
            updateForm { it.copy(cnpjLookup = LookupStatus(message = "Digite um CNPJ válido (14 dígitos) para buscar os dados.", error = true)) }
            return
        }
        cnpjJob?.cancel()
        cnpjJob = viewModelScope.launch { runCnpjLookup(form.cnpjDigits) }
    }

    private suspend fun runCnpjLookup(digits: String) {
        updateForm { it.copy(lastCnpjLookup = digits, cnpjLookup = LookupStatus(loading = true, message = "Consultando a Receita Federal…")) }
        val result = try {
            companyLookup.lookupCnpj(digits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val notFound = (e as? LookupException)?.notFound == true
            updateForm {
                if (it.cnpjDigits != digits) it
                else it.copy(
                    cnpjLookup = LookupStatus(message = e.message ?: "Não foi possível consultar o CNPJ. Preencha manualmente.", error = true),
                    // Falha temporária: permite nova tentativa automática se o usuário redigitar.
                    lastCnpjLookup = if (notFound) digits else "",
                )
            }
            return
        }
        updateForm { f ->
            if (f.cnpjDigits != digits) return@updateForm f
            val applied = CompanyFormAutofill.applyCnpj(f, result, overwrite = false)
            val changed = changedFields(f, applied)
            applied.copy(
                cnpjLookup = CompanyFormAutofill.cnpjStatus(result, changed),
                cnpjResult = result,
                cnpjConflicts = CompanyFormAutofill.conflicts(applied, result),
                zipLookup = LookupStatus(),
            )
        }
    }

    fun askOverwriteWithReceita(show: Boolean) = updateForm { it.copy(confirmOverwrite = show && it.cnpjConflicts.isNotEmpty()) }

    /** "Atualizar com os dados da Receita" confirmado: substitui também os campos divergentes (ainda sem salvar). */
    fun overwriteWithReceita() = updateForm { f ->
        val data = f.cnpjResult ?: return@updateForm f.copy(confirmOverwrite = false)
        val applied = CompanyFormAutofill.applyCnpj(f, data, overwrite = true)
        applied.copy(
            confirmOverwrite = false, cnpjConflicts = emptyList(), errors = emptyMap(),
            cnpjLookup = CompanyFormAutofill.cnpjStatus(data, changedFields(f, applied)),
        )
    }

    private fun onZipChanged(form: CompanyForm) {
        zipJob?.cancel()
        val digits = form.zipDigits
        if (digits.length != 8) {
            updateForm { it.copy(zipLookup = LookupStatus()) }
            return
        }
        if (digits == form.lastZipLookup) return
        zipJob = viewModelScope.launch {
            delay(LOOKUP_DEBOUNCE_MS)
            updateForm { it.copy(lastZipLookup = digits, zipLookup = LookupStatus(loading = true, message = "Consultando o CEP…")) }
            val result = try {
                companyLookup.lookupCep(digits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val notFound = (e as? LookupException)?.notFound == true
                updateForm {
                    if (it.zipDigits != digits) it
                    else it.copy(
                        zipLookup = LookupStatus(message = e.message ?: "Não foi possível consultar o CEP. Preencha o endereço manualmente.", error = true),
                        lastZipLookup = if (notFound) digits else "",
                    )
                }
                return@launch
            }
            updateForm { f ->
                if (f.zipDigits != digits) return@updateForm f
                val applied = CompanyFormAutofill.applyCep(f, result)
                val generic = result.street.isBlank()
                applied.copy(
                    zipLookup = LookupStatus(
                        message = if (generic) "CEP geral de ${result.city}/${result.uf}: informe o logradouro e o bairro." else "Endereço preenchido pelo CEP (${result.source}). Confira o número.",
                    ),
                )
            }
        }
    }

    private fun changedFields(before: CompanyForm, after: CompanyForm): Int =
        (CompanyFormAutofill.LABELS.keys.count { CompanyFormAutofill.valueOf(before, it) != CompanyFormAutofill.valueOf(after, it) }) +
            (if (before.segment != after.segment) 1 else 0)

    fun saveCompany() {
        val form = local.value.companyForm ?: return
        val session = state.value.session ?: return
        if (form.busy || !state.value.canManage || (form.isNew && !state.value.canCreateCompany)) return
        val errors = validateCompanyForm(form, state.value.companies)
        if (errors.isNotEmpty()) {
            local.update { it.copy(companyForm = form.copy(errors = errors)) }
            return
        }
        local.update { it.copy(companyForm = form.copy(busy = true)) }
        viewModelScope.launch {
            val company = form.toCompany()
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
        if (!s.canManageUsers) return
        val companyId = s.session?.activeCompany?.id ?: return
        local.update { it.copy(userForm = UserForm(companyIds = listOf(companyId))) }
    }

    fun editUser(user: UserProfile) {
        if (!state.value.canManageUsers) return
        local.update { it.copy(userForm = UserForm.from(user)) }
    }

    /** Abre o editor já com a empresa ativa adicionada; o administrador escolhe o perfil antes de salvar. */
    fun linkUserToActiveCompany(user: UserProfile) {
        val s = state.value
        if (!s.canManageUsers) return
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
        if (form.busy || !s.canManageUsers) return
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
        if (!s.canManageUsers || user.id == s.session?.user?.id) return
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
internal fun isValidCnpj(digits: String): Boolean = digits.length == 14 && BrDocuments.isValidCnpj(digits)
