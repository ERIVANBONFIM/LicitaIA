package com.licitaia.core.platform

import com.licitaia.core.platform.db.PlatformTenderDao
import com.licitaia.core.platform.db.PlatformTenderEntity
import com.licitaia.core.platform.net.ConcorrenteDto
import com.licitaia.core.platform.net.DocumentoDto
import com.licitaia.core.platform.net.EmpresaDetailDto
import com.licitaia.core.platform.net.HealthDto
import com.licitaia.core.platform.net.AnaliseLocalRequest
import com.licitaia.core.platform.net.AoVivoDto
import com.licitaia.core.platform.net.AuditoriaItemDto
import com.licitaia.core.platform.net.CertidaoDto
import com.licitaia.core.platform.net.PropostaCreateRequest
import com.licitaia.core.platform.net.ChatMsg
import com.licitaia.core.platform.net.MensagemDto
import com.licitaia.core.platform.net.ResultadoDto
import com.licitaia.core.platform.net.ResultadoRequest
import com.licitaia.core.platform.net.PlatformApi
import com.licitaia.core.platform.net.PlatformException
import com.licitaia.core.platform.net.PlatformFile
import com.licitaia.core.platform.net.PlatformItem
import com.licitaia.core.platform.net.ProntidaoDto
import com.licitaia.core.platform.net.PropostaDto
import com.licitaia.core.platform.net.PropostaUpdateRequest
import com.licitaia.core.platform.net.RadarFiltroDto
import com.licitaia.core.platform.net.RadarUpsertRequest
import com.licitaia.core.platform.net.RoboAtivaDto
import com.licitaia.core.platform.net.RoboConfigUpdateRequest
import com.licitaia.core.platform.net.RoboConfigDto
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

/** Filtros avançados da busca (espelham o app local). Segmento não existe na VPS. */
data class TenderFiltros(
    val estado: String? = null,
    val portal: String? = null,
    val modalidade: String? = null,
    val valorMin: Long? = null,
    val valorMax: Long? = null,
    /** YYYY-MM-DD. */
    val dataAberturaInicio: String? = null,
    val dataAberturaFim: String? = null,
) {
    val isEmpty: Boolean get() = estado == null && portal == null && modalidade == null &&
        valorMin == null && valorMax == null && dataAberturaInicio == null && dataAberturaFim == null
}

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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
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
    suspend fun fetchTenders(filter: TenderFilter, query: String, filtros: TenderFiltros? = null, maxPages: Int = 5): Result<List<PlatformTenderEntity>> = runCatching {
        val token = tokenStore.token() ?: throw PlatformException("Entre na plataforma.", PlatformException.Kind.UNAUTHORIZED)
        val busca = query.trim().ifBlank { null }
        val f = filtros?.takeIf { !it.isEmpty }
        val now = System.currentTimeMillis()
        val acc = ArrayList<PlatformTenderEntity>()
        var page = 1
        while (page <= maxPages) {
            val pageData: TenderPageDto = when (filter) {
                TenderFilter.TODAS -> api.licitacoesLeve(token, page, busca = busca, filtros = f)
                TenderFilter.INTERESSE -> api.licitacoesLeve(token, page, busca = busca, favorita = true, filtros = f)
                TenderFilter.ARQUIVADAS -> api.licitacoesLeve(token, page, busca = busca, status = "arquivada", filtros = f)
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

    /** `GET /radar/filtros/:id/licitacoes` → licitações encontradas pelo radar. */
    suspend fun radarLicitacoes(id: String): Result<List<TenderDto>> = authedRead { api.radarLicitacoes(it, id).data }

    /** `GET /concorrente`. */
    suspend fun concorrentes(): Result<List<ConcorrenteDto>> = authedRead { api.concorrentes(it).data }

    /** `GET /mensagens` (opcionalmente filtrado por licitação). */
    suspend fun mensagens(licitacaoId: String? = null): Result<List<MensagemDto>> = authedRead { api.mensagens(it, licitacaoId) }

    /** `POST /mensagens` (envio; no detalhe passa o licitacaoId). */
    suspend fun enviarMensagem(conteudo: String, licitacaoId: String? = null): Result<Unit> = authedRead { api.enviarMensagem(it, licitacaoId, conteudo) }

    suspend fun enviarMensagemPregoeiro(licitacaoId: String, conteudo: String, remetente: String, enviadaEm: Long): Result<Unit> =
        authedRead { api.enviarMensagemPregoeiro(it, licitacaoId, conteudo, remetente, enviadaEm) }

    suspend fun enviarRoboEvento(licitacaoId: String, evento: com.licitaia.core.platform.net.RoboEventoRequest): Result<Unit> =
        authedRead { api.enviarRoboEvento(it, licitacaoId, evento) }

    /** `GET /certidoes`. */
    suspend fun certidoes(): Result<List<CertidaoDto>> = authedRead { api.certidoes(it) }

    /** `GET /auditoria?limit=`. */
    suspend fun auditoria(limit: Int = 100): Result<List<AuditoriaItemDto>> = authedRead { api.auditoria(it, limit).data }

    /** `GET /licitacoes/:id/resultado` (null se não houver). */
    suspend fun resultado(id: String): Result<ResultadoDto?> = authedRead { api.resultado(it, id) }

    /** `POST /licitacoes/:id/resultado`. */
    suspend fun registrarResultado(id: String, req: ResultadoRequest): Result<Unit> = authedRead { api.registrarResultado(it, id, req) }

    /** `GET /propostas?licitacaoId=`. */
    suspend fun propostas(licitacaoId: String): Result<List<PropostaDto>> = authedRead { api.propostas(it, licitacaoId).data }

    /** `PUT /propostas/:id` (editar valor/status; "aprovar" = status "revisada"). */
    suspend fun updateProposta(id: String, valorTotal: Double?, status: String?): Result<Unit> =
        authedRead { api.updateProposta(it, id, PropostaUpdateRequest(valorTotal, status)) }

    /** `DELETE /propostas/:id`. */
    suspend fun deleteProposta(id: String): Result<Unit> = authedRead { api.deleteProposta(it, id) }

    /** `POST /radar/filtros`. */
    suspend fun criarRadar(req: RadarUpsertRequest): Result<Unit> = authedRead { api.criarRadar(it, req) }

    /** `PUT /radar/filtros/:id`. */
    suspend fun updateRadar(id: String, req: RadarUpsertRequest): Result<Unit> = authedRead { api.updateRadar(it, id, req) }

    /** `DELETE /radar/filtros/:id`. */
    suspend fun deleteRadar(id: String): Result<Unit> = authedRead { api.deleteRadar(it, id) }

    /** `POST /licitacoes/:id/analise-local` → grava a análise feita no aparelho (falha tratada pelo chamador). */
    suspend fun saveAnaliseLocal(id: String, req: AnaliseLocalRequest): Result<Unit> = authedRead { api.saveAnaliseLocal(it, id, req) }

    /** `POST /propostas` → grava metadados da proposta gerada no aparelho. */
    suspend fun criarProposta(req: PropostaCreateRequest): Result<Unit> = authedRead { api.criarProposta(it, req) }

    /**
     * Cache em memória do CONTEÚDO (texto/planilha) da proposta gerada no aparelho, por licitação. A VPS só guarda
     * metadados (valor/status), então o texto gerado fica aqui para a tela "Ver proposta" exibir dentro da sessão.
     */
    /**
     * Conteúdo da proposta gerada pela IA no aparelho: memória + ARQUIVO no armazenamento privado do app
     * (sobrevive a fechar/reabrir; não sobe para a VPS — lá vão só os metadados).
     */
    private val propostaConteudoCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun propostaFile(licitacaoId: String) =
        java.io.File(java.io.File(context.filesDir, "propostas_ia").apply { mkdirs() }, licitacaoId.filter { it.isLetterOrDigit() || it == '-' } + ".txt")

    fun cacheProposta(licitacaoId: String, conteudo: String) {
        if (conteudo.isBlank()) return
        propostaConteudoCache[licitacaoId] = conteudo
        runCatching { propostaFile(licitacaoId).writeText(conteudo) }
    }

    fun propostaConteudo(licitacaoId: String): String? =
        propostaConteudoCache[licitacaoId] ?: runCatching { propostaFile(licitacaoId).takeIf { it.exists() }?.readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.also { propostaConteudoCache[licitacaoId] = it }

    /** `POST /documentos` (multipart). */
    suspend fun uploadDocumento(bytes: ByteArray, fileName: String, nome: String, categoria: String, validade: String?): Result<Unit> =
        authedRead { api.uploadDocumento(it, bytes, fileName, nome, categoria, validade) }

    /** `DELETE /documentos/:id`. */
    suspend fun deleteDocumento(id: String): Result<Unit> = authedRead { api.deleteDocumento(it, id) }

    /** `GET /documentos/:id/download` → bytes do arquivo. */
    suspend fun downloadDocumento(id: String): Result<ByteArray> = authedRead { api.downloadDocumento(it, id) }

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

    /** `POST /ai/tenders/:id/analyze` → inicia a análise por IA. Retorna o status do job. */
    suspend fun analyzeTender(id: String): Result<String> = authedRead { api.analyzeTender(it, id).job?.status ?: "QUEUED" }

    /** `GET /ai/tenders/:id/analysis` → (statusDoJob, temAnalise). */
    suspend fun tenderAnalysisStatus(id: String): Result<Pair<String?, Boolean>> = authedRead {
        val a = api.tenderAnalysis(it, id); a.job?.status to a.hasAnalysis
    }

    /** Cache em memória da config do robô por licitação (para o robô decidir mesmo se a VPS cair na disputa). */
    private val roboConfigCache = java.util.concurrent.ConcurrentHashMap<String, RoboConfigDto>()

    /** `GET /robo-lances/config/:id` (leitura). Guarda em cache e, se a VPS falhar, devolve o cache. */
    suspend fun roboConfig(id: String): Result<RoboConfigDto?> =
        authedRead { api.roboConfig(it, id)?.also { c -> roboConfigCache[id] = c } }
            .recoverCatching { e -> roboConfigCache[id] ?: throw e }

    /** Última config conhecida (cache local), sem rede. */
    fun roboConfigCacheada(id: String): RoboConfigDto? = roboConfigCache[id]

    /** `GET /robo-lances/historico/:id` → nº de lances. */
    suspend fun roboHistoricoCount(id: String): Result<Int> = authedRead { api.roboHistoricoCount(it, id) }

    /** `PUT /robo-lances/config/:id` → arma/edita (dry_run por padrão; auto exige confirmarAuto). */
    suspend fun armarRobo(id: String, req: RoboConfigUpdateRequest): Result<Unit> = authedRead { api.updateRoboConfig(it, id, req) }

    /** `GET /robo-lances/prontidao/:id`. */
    suspend fun roboProntidao(id: String): Result<ProntidaoDto> = authedRead { api.roboProntidao(it, id) }

    /** `POST /robo-lances/preparar/:id`. */
    suspend fun roboPreparar(id: String): Result<Unit> = authedRead { api.roboPreparar(it, id) }

    /** `POST /robo-lances/participar/:id` → marca favorita + fase + arma em dry_run. */
    suspend fun roboParticipar(id: String): Result<Unit> = authedRead { api.roboParticipar(it, id) }

    /** `GET /robo-lances/ativas`. */
    suspend fun roboAtivas(): Result<List<RoboAtivaDto>> = authedRead { api.roboAtivas(it) }

    /** `GET /licitacoes/:id/ao-vivo` (acompanhamento ao vivo; só leitura). */
    suspend fun aoVivo(id: String): Result<AoVivoDto> = authedRead { api.aoVivo(it, id) }

    /** `GET /ia/chat-edital/:id/historico`. */
    suspend fun chatHistorico(id: String): Result<List<ChatMsg>> = authedRead { api.chatHistorico(it, id) }

    /** `POST /ia/chat-edital/:id` → envia pergunta ao edital. */
    suspend fun chatPerguntar(id: String, mensagem: String): Result<Unit> = authedRead { api.chatPerguntar(it, id, mensagem) }

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
