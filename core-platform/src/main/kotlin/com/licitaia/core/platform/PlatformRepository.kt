package com.licitaia.core.platform

import com.licitaia.core.platform.db.PlatformTenderDao
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.net.EmpresaDetailDto
import com.licitaia.core.platform.net.HealthDto
import com.licitaia.core.platform.net.PlatformApi
import com.licitaia.core.platform.net.PlatformException
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.platform.net.UsuarioDto
import com.licitaia.core.platform.queue.OfflineMutationQueue
import com.licitaia.core.platform.session.PlatformSession
import com.licitaia.core.platform.session.PlatformSessionManager
import com.licitaia.core.platform.session.PlatformTokenStore
import com.licitaia.core.platform.sync.PlatformTenderSync
import com.licitaia.core.platform.sync.SyncResult
import com.licitaia.core.platform.sync.toEntity
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

    /** `POST /auth/register` (auto-cadastro por CNPJ): guarda token+user e autentica. */
    suspend fun registerByCnpj(nome: String, email: String, senha: String, cnpj: String, razaoSocial: String): Result<Unit> = runCatching {
        val resp = api.register(
            com.licitaia.core.platform.net.RegisterRequest(
                nome = nome.trim(), email = email.trim(), senha = senha, cnpj = cnpj.trim(), razaoSocial = razaoSocial.trim(),
            ),
        )
        tokenStore.save(resp.token, resp.user)
        sessionManager.onSignedIn(resp.user)
    }

    /** `POST /auth/register-convite` (cadastro por código de convite): guarda token+user e autentica. */
    suspend fun registerByInvite(codigo: String, nome: String, email: String, senha: String): Result<Unit> = runCatching {
        val resp = api.registerConvite(
            com.licitaia.core.platform.net.RegisterConviteRequest(
                codigo = codigo.trim(), nome = nome.trim(), email = email.trim(), senha = senha,
            ),
        )
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

    /**
     * Busca textual na plataforma (`GET /licitacoes?busca=…&leve=true`): confirmado que o backend filtra por
     * `busca`. Traz os resultados (algumas páginas) para o espelho local; a lista observada reflete os novos.
     */
    suspend fun searchTenders(query: String, maxPages: Int = 5): Result<SyncResult> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma para buscar.", PlatformException.Kind.UNAUTHORIZED)
        val now = System.currentTimeMillis()
        var page = 1
        var fetched = 0
        var pagesRead = 0
        while (page <= maxPages) {
            val pageData = api.licitacoesLeve(token, page, busca = query)
            pagesRead++
            if (pageData.data.isEmpty()) break
            fetched += pageData.data.size
            tenderDao.upsert(pageData.data.map { it.toEntity(now) })
            if (page >= pageData.totalPages) break
            page++
        }
        SyncResult(fetched = fetched, upserted = fetched, totalLocal = tenderDao.count(), pages = pagesRead)
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    /** `GET /licitacoes/:id`. Em falha de rede, cai para o espelho local se existir. */
    suspend fun tenderDetail(id: String): Result<TenderDto> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma.", PlatformException.Kind.UNAUTHORIZED)
        api.licitacaoDetalhe(token, id) ?: throw PlatformException("Licitação não encontrada na plataforma.", PlatformException.Kind.INVALID_RESPONSE)
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    /** Cópia local da licitação (para abrir o detalhe offline enquanto a rede não responde). */
    suspend fun tenderFromMirror(id: String): PlatformTenderEntity? = tenderDao.byId(id)

    /** `GET /usuarios` (perfis/acessos da empresa). */
    suspend fun usuarios(): Result<List<UsuarioDto>> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma.", PlatformException.Kind.UNAUTHORIZED)
        api.usuarios(token)
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    /** `GET /empresas` (empresas visíveis ao usuário). */
    suspend fun empresas(): Result<List<EmpresaDetailDto>> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma.", PlatformException.Kind.UNAUTHORIZED)
        api.empresas(token)
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    private suspend fun handleUnauthorized() {
        tokenStore.clear()
        sessionManager.onSignedOut()
    }

    fun queue(): OfflineMutationQueue = queue
}
