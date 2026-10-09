package com.licitaia.feature.platform.site

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.licitaia.core.platform.PlatformConfig
import com.licitaia.core.platform.PlatformRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Entrega ao site embutido o login da plataforma guardado no aparelho (cofre cifrado), e encerra quando o site sai. */
@HiltViewModel
class PlatformSiteViewModel @Inject constructor(
    private val repository: PlatformRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Ready(val siteUrl: String, val token: String, val userJson: String?) : State
        data object SignedOut : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    private var saindo = false

    init {
        viewModelScope.launch {
            val auth = runCatching { repository.siteAuth() }.getOrNull()
            _state.value = if (auth == null) State.SignedOut else State.Ready(SITE_URL, auth.first, auth.second)
        }
    }

    /** O site foi para /login ("Sair" ou sessão vencida): encerra a conta da plataforma no app (volta ao login do app). */
    fun onSiteLoggedOut() {
        if (saindo || _state.value !is State.Ready) return
        saindo = true
        viewModelScope.launch {
            runCatching { repository.logout() }
            _state.value = State.SignedOut
        }
    }

    private companion object {
        /** Mesmo host da API da plataforma, sem o /api/ (ex.: https://cont-negociacao.nexussystemtech.com.br/). */
        val SITE_URL: String = PlatformConfig.DEFAULT_BASE_URL.removeSuffix("/").removeSuffix("/api") + "/"
    }
}
