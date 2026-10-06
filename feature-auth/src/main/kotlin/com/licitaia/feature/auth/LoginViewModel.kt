package com.licitaia.feature.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.domain.auth.GoogleIdentity
import com.licitaia.domain.auth.NoCompanyAccessException
import com.licitaia.domain.model.AuthProvider
import com.licitaia.feature.auth.google.GoogleCredentialClient
import com.licitaia.feature.auth.google.GoogleSignInResult
import kotlinx.coroutines.Job
import com.licitaia.domain.model.Company
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompanyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }

data class LoginUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val companyName: String = "",
    val cnpj: String = "",
    /** null = empresa padrão do usuário. */
    val companyId: Long? = null,
    val remember: Boolean = true,
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val companyError: String? = null,
    val cnpjError: String? = null,
    val generalError: String? = null,
    val loading: Boolean = false,
    val demoLoading: Boolean = false,
    val googleLoading: Boolean = false,
    /** false = build sem LICITAIA_GOOGLE_SERVER_CLIENT_ID configurado. */
    val googleConfigured: Boolean = false,
    /** Conta Google identificada que ainda não tem empresa vinculada. */
    val googlePending: GooglePending? = null,
    /** Aviso não bloqueante (ex.: "Entrada com Google cancelada."). */
    val info: String? = null,
    val loggedIn: Boolean = false,
) {
    val busy: Boolean get() = loading || demoLoading || googleLoading
}

data class GooglePending(val email: String, val name: String)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    companyRepository: CompanyRepository,
    private val google: GoogleCredentialClient,
) : ViewModel() {

    val companies: StateFlow<List<Company>> = companyRepository.observeCompanies()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(LoginUiState(googleConfigured = google.isConfigured))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var googleJob: Job? = null
    private var lastGoogleIdentity: GoogleIdentity? = null

    // ------------------------------------------------------------------ Google

    /** [activityContext] deve ser a Activity atual (o seletor de contas é UI do sistema). */
    fun signInWithGoogle(activityContext: Context) {
        val s = _state.value
        if (s.busy) return
        if (!google.isConfigured) {
            _state.update { it.copy(generalError = "Login Google não está configurado neste build (LICITAIA_GOOGLE_SERVER_CLIENT_ID). Veja o README.") }
            return
        }
        _state.update { it.copy(googleLoading = true, generalError = null, info = null, googlePending = null) }
        googleJob = viewModelScope.launch {
            when (val result = google.signIn(activityContext)) {
                is GoogleSignInResult.Success -> completeGoogleLogin(result.identity)
                GoogleSignInResult.Cancelled -> _state.update { it.copy(googleLoading = false, info = "Entrada com Google cancelada.") }
                GoogleSignInResult.NoAccount -> _state.update {
                    it.copy(googleLoading = false, generalError = "O Google não ofereceu nenhuma conta para este app. Verifique se há uma conta Google no aparelho e se este build (pacote e SHA-1 da assinatura) está cadastrado como cliente OAuth Android no Google Cloud.")
                }
                GoogleSignInResult.NotConfigured -> _state.update {
                    it.copy(googleLoading = false, generalError = "Login Google não está configurado neste build.")
                }
                is GoogleSignInResult.Failure -> _state.update { it.copy(googleLoading = false, generalError = result.message) }
            }
        }
    }

    /** Cancela a solicitação em andamento (o seletor do sistema também pode ser fechado pelo usuário). */
    fun cancelGoogle() {
        googleJob?.cancel()
        googleJob = null
        _state.update { if (it.googleLoading) it.copy(googleLoading = false, info = "Entrada com Google cancelada.") else it }
    }

    private suspend fun completeGoogleLogin(identity: GoogleIdentity) {
        lastGoogleIdentity = identity
        val result = runSafely { auth.loginWithGoogle(identity, _state.value.remember) }
        _state.update {
            when (val error = result.exceptionOrNull()) {
                null -> it.copy(googleLoading = false, loggedIn = true)
                is NoCompanyAccessException -> it.copy(googleLoading = false, googlePending = GooglePending(error.email, identity.name))
                else -> it.copy(
                    googleLoading = false,
                    generalError = error.message?.takeIf(String::isNotBlank) ?: "Não foi possível entrar com o Google.",
                )
            }
        }
    }

    /** Reavalia o acesso (ex.: após um administrador vincular a conta a uma empresa). */
    fun retryGoogleAccess() {
        val identity = lastGoogleIdentity ?: return
        if (_state.value.busy) return
        _state.update { it.copy(googleLoading = true, generalError = null, info = null) }
        viewModelScope.launch { completeGoogleLogin(identity) }
    }

    /** "Sair da conta Google": limpa o estado de credencial e volta ao formulário. */
    fun signOutGoogle() {
        lastGoogleIdentity = null
        _state.update { it.copy(googlePending = null, generalError = null, info = "Conta Google desconectada deste aparelho.") }
        viewModelScope.launch { google.signOut(AuthProvider.GOOGLE) }
    }

    fun createGoogleCompany() {
        val identity = lastGoogleIdentity ?: return
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(googleLoading = true, generalError = null) }
        viewModelScope.launch {
            val result = runSafely { auth.createGoogleCompany(identity, s.companyName, s.cnpj, s.remember) }
            _state.update { it.copy(googleLoading = false, loggedIn = result.isSuccess, generalError = result.exceptionOrNull()?.message) }
        }
    }
    fun dismissInfo() = _state.update { it.copy(info = null) }

    fun setMode(mode: AuthMode) = _state.update {
        it.copy(
            mode = mode, generalError = null, nameError = null, emailError = null,
            passwordError = null, companyError = null, cnpjError = null, info = null,
        )
    }

    fun onName(v: String) = _state.update { it.copy(name = v, nameError = null, generalError = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v.trim(), emailError = null, generalError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, generalError = null) }
    fun onCompanyName(v: String) = _state.update { it.copy(companyName = v, companyError = null, generalError = null) }
    fun onCnpj(v: String) = _state.update {
        it.copy(cnpj = v.filter(Char::isDigit).take(14), cnpjError = null, generalError = null)
    }
    fun onCompany(id: Long?) = _state.update { it.copy(companyId = id) }
    fun onRemember(v: Boolean) = _state.update { it.copy(remember = v) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        if (s.mode == AuthMode.LOGIN) login(s) else register(s)
    }

    private fun login(s: LoginUiState) {
        val emailError = validateEmail(s.email)
        val passwordError = if (s.password.isBlank()) "Informe sua senha" else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(loading = true, generalError = null) }
        viewModelScope.launch {
            val result = runSafely { auth.login(s.email, s.password, s.companyId, s.remember) }
            _state.update {
                if (result.isSuccess) it.copy(loading = false, loggedIn = true)
                else it.copy(
                    loading = false,
                    generalError = result.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                        ?: "E-mail ou senha inválidos.",
                )
            }
        }
    }

    private fun register(s: LoginUiState) {
        val nameError = if (s.name.trim().length < 3) "Informe seu nome completo" else null
        val emailError = validateEmail(s.email)
        val passwordError = when {
            // Mesmo mínimo exigido pelo AuthRepository (evita erro genérico após o envio).
            s.password.length < 8 -> "A senha deve ter ao menos 8 caracteres"
            s.password.none(Char::isDigit) || s.password.none(Char::isLetter) -> "Use letras e números na senha"
            else -> null
        }
        val companyError = if (s.companyName.trim().length < 2) "Informe a razão social ou nome fantasia" else null
        val cnpjError = when {
            s.cnpj.length != 14 -> "O CNPJ deve ter 14 dígitos"
            s.cnpj.toSet().size == 1 -> "CNPJ inválido"
            else -> null
        }
        if (listOfNotNull(nameError, emailError, passwordError, companyError, cnpjError).isNotEmpty()) {
            _state.update {
                it.copy(
                    nameError = nameError, emailError = emailError, passwordError = passwordError,
                    companyError = companyError, cnpjError = cnpjError,
                )
            }
            return
        }
        _state.update { it.copy(loading = true, generalError = null) }
        viewModelScope.launch {
            val result = runSafely {
                auth.register(s.name.trim(), s.email, s.password, s.companyName.trim(), s.cnpj)
            }
            _state.update {
                if (result.isSuccess) it.copy(loading = false, loggedIn = true)
                else it.copy(
                    loading = false,
                    generalError = result.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                        ?: "Não foi possível criar a conta. Verifique os dados e tente novamente.",
                )
            }
        }
    }

    private fun validateEmail(email: String): String? = when {
        email.isBlank() -> "Informe seu e-mail"
        !EMAIL_REGEX.matches(email) -> "E-mail inválido"
        else -> null
    }

    private suspend fun <T> runSafely(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}


