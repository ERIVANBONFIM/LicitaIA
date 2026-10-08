package com.licitaia.core.platform

import com.licitaia.core.platform.db.PlatformTenderDao
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.net.HealthDto
import com.licitaia.core.platform.net.PlatformApi
import com.licitaia.core.platform.net.PlatformException
import com.licitaia.core.platform.queue.OfflineMutationQueue
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.core.platform.session.PlatformSessionManager
import com.licitaia.core.platform.session.PlatformTokenStore
import com.licitaia.core.platform.sync.PlatformTenderSync
import com.licitaia.core.platform.sync.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fachada da camada de plataforma para a UI: login, sessão, sincronização de leitura e fila offline.
 * Convive com o app local — nada aqui toca o fluxo/local atual.
 */
@Singleton
class PlatformRepository @Inject constructor(
    private val api: PlatformApi,
    private val tokenStore: PlatformTokenStore,
    private val sessionManager: PlatformSessionManager,
    private val tenderDao: PlatformTenderDao,
    private val queue: OfflineMutationQueue,
) {
    val session: StateFlow<PlatformSession> get() = sessionManager.session

    suspend fun ensureSessionLoaded() = sessionManager.ensureRestored()

    fun observeTenders(): Flow<List<PlatformTenderEntity>> = tenderDao.observeAll()

    fun observePendingMutations(): Flow<Int> = queue.observeCount()

    /** Teste de conectividade seguro, sem login (`GET /health`). */
    suspend fun checkConnectivity(): Result<HealthDto> = runCatching { api.health() }

    /** `POST /auth/login`: guarda token+user com segurança e atualiza a sessão. */
    suspend fun login(email: String, senha: String): Result<Unit> = runCatching {
        val resp = api.login(email.trim(), senha)
        tokenStore.save(resp.token, resp.user)
        sessionManager.onSignedIn(resp.user)
    }

    /** `POST /auth/logout` (best-effort) + limpa o cofre e o espelho local. */
    suspend fun logout() {
        val token = tokenStore.token()
        if (!token.isNullOrBlank()) runCatching { api.logout(token) }
        tokenStore.clear()
        tenderDao.clear()
        sessionManager.onSignedOut()
    }

    /** Atualiza role/empresa via `GET /auth/me` quando a rota existir (L7); silencioso se ainda não. */
    suspend fun refreshMe(): Result<Unit> = runCatching {
        val token = tokenStore.token() ?: return@runCatching
        api.me(token)?.let { user ->
            tokenStore.updateUser(user)
            sessionManager.onSignedIn(user)
        }
    }

    /** Sincroniza o espelho de leitura. Em 401, limpa a sessão (contrato §4.2). */
    suspend fun syncTenders(): Result<SyncResult> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma para sincronizar.", PlatformException.Kind.UNAUTHORIZED)
        val sync = PlatformTenderSync(tenderDao)
        sync.sync { page -> api.licitacoesLeve(token, page) }
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    private suspend fun handleUnauthorized() {
        tokenStore.clear()
        sessionManager.onSignedOut()
    }

    fun queue(): OfflineMutationQueue = queue
}
