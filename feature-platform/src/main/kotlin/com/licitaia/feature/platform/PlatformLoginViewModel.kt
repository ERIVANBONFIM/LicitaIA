package com.licitaia.feature.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformRepository
import com.licitaia.core.platform.net.PlatformException
import com.licitaia.core.platform.session.PlatformSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformLoginUiState(
    val email: String = "",
    val senha: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** Resultado do teste de conectividade (`GET /health`), sem login. */
    val connectivity: String? = null,
    val checkingConnectivity: Boolean = false,
)

@HiltViewModel
class PlatformLoginViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlatformLoginUiState())
    val state: StateFlow<PlatformLoginUiState> = _state.asStateFlow()

    /** Para o grafo redirecionar à lista quando já houver sessão. */
    val session: StateFlow<PlatformSession> = repository.session

    init {
        viewModelScope.launch { repository.ensureSessionLoaded() }
    }

    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onSenha(v: String) = _state.update { it.copy(senha = v, error = null) }

    fun login() {
        val s = _state.value
        if (s.loading) return
        if (s.email.isBlank() || s.senha.isBlank()) {
            _state.update { it.copy(error = "Informe e-mail e senha.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = repository.login(s.email, s.senha)
            _state.update {
                it.copy(
                    loading = false,
                    error = result.exceptionOrNull()?.let(::friendlyError),
                    senha = if (result.isSuccess) "" else it.senha,
                )
            }
        }
    }

    /** Teste seguro de conectividade com a VPS, sem credenciais. */
    fun checkConnectivity() {
        if (_state.value.checkingConnectivity) return
        _state.update { it.copy(checkingConnectivity = true, connectivity = null) }
        viewModelScope.launch {
            val result = repository.checkConnectivity()
            _state.update {
                it.copy(
                    checkingConnectivity = false,
                    connectivity = result.fold(
                        onSuccess = { h -> "Plataforma online (${h.status}${h.version?.let { v -> " · v$v" } ?: ""})." },
                        onFailure = { e -> friendlyError(e) },
                    ),
                )
            }
        }
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is PlatformException -> e.message ?: "Falha ao falar com a plataforma."
        else -> e.message ?: "Falha inesperada."
    }
}
