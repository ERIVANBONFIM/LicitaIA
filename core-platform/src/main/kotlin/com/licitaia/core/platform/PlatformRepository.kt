package com.licitaia.core.platform

import com.licitaia.core.platform.db.PlatformTenderDao
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.net.ConcorrenteDto
import com.licitaia.core.platform.net.DocumentoDto
import com.licitaia.core.platform.net.EmpresaDetailDto
import com.licitaia.core.platform.net.HealthDto
import com.licitaia.core.platform.net.MensagemDto
import com.licitaia.core.platform.net.PlatformApi
import com.licitaia.core.platform.net.PlatformException
import com.licitaia.core.platform.net.PlatformFile
import com.licitaia.core.platform.net.PlatformItem
import com.licitaia.core.platform.net.RadarFiltroDto
import com.licitaia.core.platform.net.TenderDto
import com.licitaia.core.platform.net.TenderPageDto
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

/** Recortes da lista de licitações (consultados no servidor). */
enum class TenderFilter { TODAS, INTERESSE, ARQUIVADAS, PARTICIPACOES }

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

    /**
     * Recorte da lista de licitações consultando o SERVIDOR (o backend já respeita `favorita`/`status`;
     * Participações usa `GET /licitacoes/minhas`). Pagina até [maxPages] e espelha o resultado localmente.
     * "Todas" com texto vazio volta ao pull padrão; com texto, usa `busca=`.
     */
    suspend fun fetchTenders(filter: TenderFilter, query: String, maxPages: Int = 5): Result<List<PlatformTenderEntity>> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma.", PlatformException.Kind.UNAUTHORIZED)
        val busca = query.trim().ifBlank { null }
        val now = System.currentTimeMillis()
        val acc = ArrayList<PlatformTenderEntity>()
        var page = 1
        while (page <= maxPages) {
            val pageData: TenderPageDto = when (filter) {
                TenderFilter.TODAS -> api.licitacoesLeve(token, page, busca = busca)
                TenderFilter.INTERESSE -> api.licitacoesLeve(token, page, busca = busca, favorita = true)
                TenderFilter.ARQUIVADAS -> api.licitacoesLeve(token, page, busca = busca, status = "arquivada")
                TenderFilter.PARTICIPACOES -> api.licitacoesMinhas(token, page, busca = busca)
            }
            if (pageData.data.isEmpty()) break
            val entities = pageData.data.map { it.toEntity(now) }
            acc += entities
            tenderDao.upsert(entities)
            if (page >= pageData.totalPages) break
            page++
        }
        acc
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    /** Cópia local completa do espelho (fallback offline para o recorte "Todas"). */
    suspend fun mirrorSnapshot(): List<PlatformTenderEntity> = tenderDao.all()

    /** Contagem de um recorte (lê só o `total` da 1ª página, limit=1), para os cards do dashboard. */
    suspend fun countTenders(filter: TenderFilter): Result<Int> = authedRead { token ->
        val page = when (filter) {
            TenderFilter.TODAS -> api.licitacoesLeve(token, 1, limit = 1)
            TenderFilter.INTERESSE -> api.licitacoesLeve(token, 1, limit = 1, favorita = true)
            TenderFilter.ARQUIVADAS -> api.licitacoesLeve(token, 1, limit = 1, status = "arquivada")
            TenderFilter.PARTICIPACOES -> api.licitacoesMinhas(token, 1, limit = 1)
        }
        page.total
    }

    /** `GET /documentos` (metadados). */
    suspend fun documentos(): Result<List<DocumentoDto>> = authedRead { api.documentos(it) }

    /** `GET /radar/filtros`. */
    suspend fun radarFiltros(): Result<List<RadarFiltroDto>> = authedRead { api.radarFiltros(it) }

    /** `GET /concorrente`. */
    suspend fun concorrentes(): Result<List<ConcorrenteDto>> = authedRead { api.concorrentes(it).data }

    /** `GET /mensagens`. */
    suspend fun mensagens(): Result<List<MensagemDto>> = authedRead { api.mensagens(it) }

    private suspend fun <T> authedRead(block: suspend (token: String) -> T): Result<T> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma.", PlatformException.Kind.UNAUTHORIZED)
        block(token)
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    /** `GET /licitacoes/:id`. Em falha de rede, cai para o espelho local se existir. */
    suspend fun tenderDetail(id: String): Result<TenderDto> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma.", PlatformException.Kind.UNAUTHORIZED)
        api.licitacaoDetalhe(token, id) ?: throw PlatformException("Licitação não encontrada na plataforma.", PlatformException.Kind.INVALID_RESPONSE)
    }.onFailure { if (it is PlatformException && it.isUnauthorized) handleUnauthorized() }

    /** Cópia local da licitação (para abrir o detalhe offline enquanto a rede não responde). */
    suspend fun tenderFromMirror(id: String): PlatformTenderEntity? = tenderDao.byId(id)

    /** Itens da licitação (`GET /licitacoes/:id/itens`). */
    suspend fun tenderItens(id: String): Result<List<PlatformItem>> = authedRead { api.licitacaoItens(it, id) }

    /** Anexos/arquivos da licitação (`GET /licitacoes/:id/arquivos`). */
    suspend fun tenderArquivos(id: String): Result<List<PlatformFile>> = authedRead { api.licitacaoArquivos(it, id) }

    /** `PUT /licitacoes/:id/favoritar` (alterna). Reflete no espelho local. Retorna o novo estado. */
    suspend fun toggleFavorita(id: String): Result<Boolean> = authedRead { token ->
        val fav = api.favoritar(token, id).favorita
        tenderDao.byId(id)?.let { tenderDao.upsert(listOf(it.copy(favorita = fav))) }
        fav
    }

    /** `PUT /licitacoes/:id/arquivar` (alterna). Reflete no espelho local. Retorna o novo status. */
    suspend fun toggleArquivar(id: String): Result<String> = authedRead { token ->
        val status = api.arquivar(token, id).status
        tenderDao.byId(id)?.let { tenderDao.upsert(listOf(it.copy(status = status))) }
        status
    }

    /** `PUT /licitacoes/:id/ocultar` (alterna). Reflete no espelho local. Retorna o novo status. */
    suspend fun toggleOcultar(id: String): Result<String> = authedRead { token ->
        val status = api.ocultar(token, id).status
        tenderDao.byId(id)?.let { tenderDao.upsert(listOf(it.copy(status = status))) }
        status
    }

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
