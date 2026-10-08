package com.licitaia.core.platform.session

import com.licitaia.core.platform.net.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Fonte única do estado de sessão da plataforma. Restaura do cofre uma vez e reflete login/logout. */
@Singleton
class PlatformSessionManager @Inject constructor(
    private val tokenStore: PlatformTokenStore,
) {
    private val _session = MutableStateFlow<PlatformSession>(PlatformSession.Unknown)
    val session: StateFlow<PlatformSession> = _session.asStateFlow()

    private val restoreMutex = Mutex()
    @Volatile private var restored = false

    /** Lê o cofre na primeira vez; chamadas seguintes são baratas. */
    suspend fun ensureRestored() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            val user = tokenStore.user()
            _session.value = if (tokenStore.hasSession() && user != null) {
                PlatformSession.SignedIn(user)
            } else {
                PlatformSession.SignedOut
            }
            restored = true
        }
    }

    fun onSignedIn(user: UserDto) {
        restored = true
        _session.value = PlatformSession.SignedIn(user)
    }

    fun onSignedOut() {
        restored = true
        _session.value = PlatformSession.SignedOut
    }
}
