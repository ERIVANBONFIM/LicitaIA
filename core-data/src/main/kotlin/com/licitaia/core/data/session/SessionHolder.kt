package com.licitaia.core.data.session

import com.licitaia.domain.model.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Sessão local corrente, compartilhada entre os repositórios (evita ciclo Auth ↔ Audit). */
@Singleton
class SessionHolder @Inject constructor() {
    private val _state = MutableStateFlow<AuthSession?>(null)
    val state: StateFlow<AuthSession?> = _state.asStateFlow()

    val current: AuthSession? get() = _state.value

    fun set(session: AuthSession?) {
        _state.value = session
    }

    fun update(transform: (AuthSession) -> AuthSession) {
        _state.value = _state.value?.let(transform)
    }
}
