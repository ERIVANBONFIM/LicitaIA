package com.licitaia.core.platform.net

import com.licitaia.core.platform.PlatformConfig
import com.licitaia.core.platform.TenderFiltros
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cliente HTTP da API LicitaPRO (CONTRATO_API). Reusa o [OkHttpClient] HTTPS-only e o [Json] de core-network.
 * Segue o mesmo padrão do PncpApi (sem Retrofit): chamadas suspensas, decode direto do stream, erros em pt-BR.
 *
 * O [baseUrl] é parametrizável para testes com MockWebServer. `Authorization: Bearer` é enviado em TODA rota
 * protegida, inclusive leitura (contrato §4.1: não depender da "empresa padrão"). Nunca enviamos `X-Empresa-Id`.
 */
class PlatformApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: HttpUrl = PlatformConfig.DEFAULT_BASE_URL.toHttpUrl(),
) {

    /** Conectividade segura, sem login: `GET /health` → `{status:"ok"}`. */
    suspend fun health(): HealthDto =
        get(url("health"), HealthDto.serializer(), token = null)
            ?: throw PlatformException("A plataforma não respondeu.", PlatformException.Kind.INVALID_RESPONSE)

    /** `POST /auth/login` → `{token,user}`; 401 `{error}` em credenciais inválidas. */
    suspend fun login(email: String, senha: String): LoginResponse {
        val payload = json.encodeToString(LoginRequest.serializer(), LoginRequest(email, senha)).toRequestBody(JSON_MEDIA)
        val request = baseRequest(url("auth/login"), token = null).post(payload).build()
        return execute(request, LoginResponse.serializer())
            ?: throw PlatformException("Resposta de login vazia.", PlatformException.Kind.INVALID_RESPONSE)
    }

    /** `POST /auth/register` (auto-cadastro por CNPJ) → `{token,user}`. */
    suspend fun register(req: RegisterRequest): LoginResponse {
        val payload = json.encodeToString(RegisterRequest.serializer(), req).toRequestBody(JSON_MEDIA)
        val request = baseRequest(url("auth/register"), token = null).post(payload).build()
        return execute(request, LoginResponse.serializer())
            ?: throw PlatformException("Resposta de cadastro vazia.", PlatformException.Kind.INVALID_RESPONSE)
    }

    /** `POST /auth/register-convite` (cadastro por código de convite) → `{token,user}`. */
    suspend fun registerConvite(req: RegisterConviteRequest): LoginResponse {
        val payload = json.encodeToString(RegisterConviteRequest.serializer(), req).toRequestBody(JSON_MEDIA)
        val request = baseRequest(url("auth/register-convite"), token = null).post(payload).build()
        return execute(request, LoginResponse.serializer())
            ?: throw PlatformException("Resposta de cadastro vazia.", PlatformException.Kind.INVALID_RESPONSE)
    }

    /**
     * `GET /auth/me` → usuário/empresa atuais. Ainda é lacuna no backend (CONTRATO L7); se a rota não existir
     * (404), devolve null em vez de falhar — o cliente mantém o `user` obtido no login.
     */
    suspend fun me(token: String): UserDto? =
        try {
            get(url("auth/me"), UserDto.serializer(), token = token)
        } catch (e: PlatformException) {
            if (e.httpStatus == 404) null else throw e
        }

    /** `POST /auth/logout` (Bearer): apaga a sessão no servidor. */
    suspend fun logout(token: String) {
        val request = baseRequest(url("auth/logout"), token).post(ByteArray(0).toRequestBody(JSON_MEDIA)).build()
        execute(request, OkDto.serializer())
    }

    /**
     * `GET /licitacoes?leve=true&limit&page&ordenar=updatedAt`. Enviar sempre Bearer.
     * [incluirOcultas] reflete status/ocultação no espelho local.
     */
    suspend fun licitacoesLeve(
        token: String,
        page: Int,
        limit: Int = PlatformConfig.SYNC_PAGE_SIZE,
        ordenar: String = "updatedAt",
        incluirOcultas: Boolean = true,
        busca: String? = null,
        favorita: Boolean? = null,
        status: String? = null,
        filtros: TenderFiltros? = null,
    ): TenderPageDto {
        val u = url("licitacoes").newBuilder()
            .addQueryParameter("leve", "true")
            .addQueryParameter("limit", limit.coerceIn(1, 200).toString())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("ordenar", ordenar)
            .apply { if (incluirOcultas) addQueryParameter("incluirOcultas", "true") }
            .apply { busca?.trim()?.takeIf { it.isNotBlank() }?.let { addQueryParameter("busca", it) } }
            .apply { if (favorita == true) addQueryParameter("favorita", "true") }
            .apply { status?.takeIf { it.isNotBlank() }?.let { addQueryParameter("status", it) } }
            .apply {
                filtros?.let { f ->
                    f.estado?.takeIf { it.isNotBlank() }?.let { addQueryParameter("estado", it) }
                    f.portal?.takeIf { it.isNotBlank() }?.let { addQueryParameter("portal", it) }
                    f.modalidade?.takeIf { it.isNotBlank() }?.let { addQueryParameter("modalidade", it) }
                    f.valorMin?.let { addQueryParameter("valorMin", it.toString()) }
                    f.valorMax?.let { addQueryParameter("valorMax", it.toString()) }
                    f.dataAberturaInicio?.takeIf { it.isNotBlank() }?.let { addQueryParameter("dataAberturaInicio", it) }
                    f.dataAberturaFim?.takeIf { it.isNotBlank() }?.let { addQueryParameter("dataAberturaFim", it) }
                }
            }
            .build()
        return get(u, TenderPageDto.serializer(), token = token) ?: TenderPageDto()
    }

    /** `GET /licitacoes/minhas?leve=true` → favoritas OU fase=participando (recorte "Minhas Participações"). */
    suspend fun licitacoesMinhas(
        token: String,
        page: Int,
        limit: Int = PlatformConfig.SYNC_PAGE_SIZE,
        busca: String? = null,
    ): TenderPageDto {
        val u = url("licitacoes/minhas").newBuilder()
            .addQueryParameter("leve", "true")
            .addQueryParameter("limit", limit.coerceIn(1, 200).toString())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("ordenar", "updatedAt")
            .apply { busca?.trim()?.takeIf { it.isNotBlank() }?.let { addQueryParameter("busca", it) } }
            .build()
        return get(u, TenderPageDto.serializer(), token = token) ?: TenderPageDto()
    }

    /** `GET /documentos` → metadados dos documentos da empresa (array). */
    suspend fun documentos(token: String): List<DocumentoDto> =
        get(url("documentos"), ListSerializer(DocumentoDto.serializer()), token = token) ?: emptyList()

    /** `GET /radar/filtros` → filtros de radar salvos (array). */
    suspend fun radarFiltros(token: String): List<RadarFiltroDto> =
        get(url("radar/filtros"), ListSerializer(RadarFiltroDto.serializer()), token = token) ?: emptyList()

    /** `GET /radar/filtros/:id/licitacoes` → licitações que o radar encontrou (mesmo shape da lista). */
    suspend fun radarLicitacoes(token: String, id: String): TenderPageDto =
        get(url("radar/filtros/$id/licitacoes"), TenderPageDto.serializer(), token = token) ?: TenderPageDto()

    /** `GET /concorrente` → concorrentes mapeados (paginado `{data,total}`). */
    suspend fun concorrentes(token: String): ConcorrentePageDto =
        get(url("concorrente"), ConcorrentePageDto.serializer(), token = token) ?: ConcorrentePageDto()

    /** `GET /licitacoes/:id` → detalhe completo (campos pesados incluídos; só mapeamos os que a UI usa). */
    suspend fun licitacaoDetalhe(token: String, id: String): TenderDto? =
        get(url("licitacoes/$id"), TenderDto.serializer(), token = token)

    /** `GET /licitacoes/:id/itens` → itens (shape variável; extração tolerante a tipos/chaves). */
    suspend fun licitacaoItens(token: String, id: String): List<PlatformItem> =
        (get(url("licitacoes/$id/itens"), ListSerializer(JsonObject.serializer()), token = token) ?: emptyList())
            .map {
                PlatformItem(
                    numero = it.str("numero", "item", "numeroItem")?.toDoubleOrNull()?.toInt(),
                    descricao = it.str("descricao", "objeto", "nome", "especificacao"),
                    quantidade = it.str("quantidade", "qtd", "quantidadeTotal"),
                    unidade = it.str("unidade", "unidadeMedida", "unidadeFornecimento"),
                    valor = it.str("valorReferencia", "valorUnitario", "valorEstimado", "valorTotal"),
                    valorLanceMinimo = it.str("valorLanceMinimo", "lanceMinimo", "pisoItem"),
                    valorProposto = it.str("valorProposto", "precoOfertado", "valorOfertado"),
                    marca = it.str("marca", "marcaFabricante", "fabricante"),
                    modelo = it.str("modelo", "modeloVersao", "versao"),
                )
            }

    /** `GET /licitacoes/:id/arquivos` → anexos (shape variável; extração tolerante). */
    suspend fun licitacaoArquivos(token: String, id: String): List<PlatformFile> =
        (get(url("licitacoes/$id/arquivos"), ListSerializer(JsonObject.serializer()), token = token) ?: emptyList())
            .map {
                PlatformFile(
                    nome = it.str("nome", "titulo", "descricao", "fileName"),
                    tipo = it.str("tipo", "categoria", "mimeType", "extensao"),
                    url = it.str("url", "link", "downloadUrl"),
                )
            }

    /** `PUT /licitacoes/:id/favoritar` (alterna). */
    suspend fun favoritar(token: String, id: String): FavoritaResult =
        put(url("licitacoes/$id/favoritar"), FavoritaResult.serializer(), token) ?: FavoritaResult()

    /** `PUT /licitacoes/:id/arquivar` (alterna ativa↔arquivada). */
    suspend fun arquivar(token: String, id: String): StatusResult =
        put(url("licitacoes/$id/arquivar"), StatusResult.serializer(), token) ?: StatusResult()

    /** `PUT /licitacoes/:id/ocultar` (alterna ativa↔oculta). */
    suspend fun ocultar(token: String, id: String): StatusResult =
        put(url("licitacoes/$id/ocultar"), StatusResult.serializer(), token) ?: StatusResult()

    /** `POST /ai/tenders/:id/analyze` → inicia a análise (job). */
    suspend fun analyzeTender(token: String, id: String): AnalysisStatusDto =
        postJson(url("ai/tenders/$id/analyze"), "{}", AnalysisStatusDto.serializer(), token) ?: AnalysisStatusDto()

    /** `GET /ai/tenders/:id/analysis` → estado/resultado da análise (polling). */
    suspend fun tenderAnalysis(token: String, id: String): AnalysisStatusDto =
        get(url("ai/tenders/$id/analysis"), AnalysisStatusDto.serializer(), token = token) ?: AnalysisStatusDto()

    /** `GET /robo-lances/config/:id` → configuração do robô (leitura). */
    suspend fun roboConfig(token: String, id: String): RoboConfigDto? =
        get(url("robo-lances/config/$id"), RoboConfigDto.serializer(), token = token)

    /** `GET /robo-lances/historico/:id` → nº de lances registrados. */
    suspend fun roboHistoricoCount(token: String, id: String): Int =
        (get(url("robo-lances/historico/$id"), ListSerializer(JsonObject.serializer()), token = token) ?: emptyList()).size

    /** `PUT /robo-lances/config/:id` → arma/edita a config do robô. */
    suspend fun updateRoboConfig(token: String, id: String, req: RoboConfigUpdateRequest) {
        val payload = json.encodeToString(RoboConfigUpdateRequest.serializer(), req)
        putJson(url("robo-lances/config/$id"), payload, OkDto.serializer(), token)
    }

    /** `GET /robo-lances/prontidao/:id`. */
    suspend fun roboProntidao(token: String, id: String): ProntidaoDto =
        get(url("robo-lances/prontidao/$id"), ProntidaoDto.serializer(), token = token) ?: ProntidaoDto()

    /** `POST /robo-lances/preparar/:id`. */
    suspend fun roboPreparar(token: String, id: String) {
        postJson(url("robo-lances/preparar/$id"), "{}", OkDto.serializer(), token)
    }

    /** `POST /robo-lances/participar/:id` → marca favorita + fase + arma em dry_run. */
    suspend fun roboParticipar(token: String, id: String) {
        postJson(url("robo-lances/participar/$id"), "{}", OkDto.serializer(), token)
    }

    /** `GET /robo-lances/ativas` → robôs ativos da empresa. */
    suspend fun roboAtivas(token: String): List<RoboAtivaDto> =
        get(url("robo-lances/ativas"), ListSerializer(RoboAtivaDto.serializer()), token = token) ?: emptyList()

    /** `GET /licitacoes/:id/ao-vivo` → acompanhamento ao vivo (só leitura). */
    suspend fun aoVivo(token: String, id: String): AoVivoDto =
        get(url("licitacoes/$id/ao-vivo"), AoVivoDto.serializer(), token = token) ?: AoVivoDto()

    /** `GET /ia/chat-edital/:id/historico` → mensagens do "Pergunte ao edital". */
    suspend fun chatHistorico(token: String, id: String): List<ChatMsg> =
        (get(url("ia/chat-edital/$id/historico"), ChatHistoricoDto.serializer(), token = token)?.mensagens ?: emptyList())
            .map {
                ChatMsg(
                    autor = it.str("role", "autor", "remetente") ?: "ia",
                    texto = it.str("mensagem", "texto", "content", "conteudo", "resposta").orEmpty(),
                )
            }

    /** `POST /ia/chat-edital/:id` → envia uma pergunta ao edital. */
    suspend fun chatPerguntar(token: String, id: String, mensagem: String) {
        val payload = json.encodeToString(ChatPerguntaRequest.serializer(), ChatPerguntaRequest(mensagem))
        postJson(url("ia/chat-edital/$id"), payload, OkDto.serializer(), token)
    }

    /** `GET /auditoria?limit=` → trilha de auditoria da empresa. */
    suspend fun auditoria(token: String, limit: Int = 100): AuditoriaPageDto {
        val u = url("auditoria").newBuilder().addQueryParameter("limit", limit.coerceIn(1, 500).toString()).build()
        return get(u, AuditoriaPageDto.serializer(), token = token) ?: AuditoriaPageDto()
    }

    /** `GET /licitacoes/:id/resultado` (null se não houver). */
    suspend fun resultado(token: String, id: String): ResultadoDto? =
        get(url("licitacoes/$id/resultado"), ResultadoDto.serializer(), token = token)

    /** `POST /licitacoes/:id/resultado`. */
    suspend fun registrarResultado(token: String, id: String, req: ResultadoRequest) {
        val payload = json.encodeToString(ResultadoRequest.serializer(), req)
        postJson(url("licitacoes/$id/resultado"), payload, OkDto.serializer(), token)
    }

    /** `POST /licitacoes/:id/analise-local` → grava o resultado da análise feita no aparelho. */
    suspend fun saveAnaliseLocal(token: String, id: String, req: AnaliseLocalRequest) {
        val payload = json.encodeToString(AnaliseLocalRequest.serializer(), req)
        postJson(url("licitacoes/$id/analise-local"), payload, OkDto.serializer(), token)
    }

    /** `POST /propostas` → grava metadados da proposta (conteúdo fica no aparelho). */
    suspend fun criarProposta(token: String, req: PropostaCreateRequest) {
        val payload = json.encodeToString(PropostaCreateRequest.serializer(), req)
        postJson(url("propostas"), payload, OkDto.serializer(), token)
    }

    /** `GET /propostas?licitacaoId=`. */
    suspend fun propostas(token: String, licitacaoId: String): PropostaPageDto {
        val u = url("propostas").newBuilder().addQueryParameter("licitacaoId", licitacaoId).build()
        return get(u, PropostaPageDto.serializer(), token = token) ?: PropostaPageDto()
    }

    /** `PUT /propostas/:id`. */
    suspend fun updateProposta(token: String, id: String, req: PropostaUpdateRequest) {
        val payload = json.encodeToString(PropostaUpdateRequest.serializer(), req)
        putJson(url("propostas/$id"), payload, OkDto.serializer(), token)
    }

    /** `DELETE /propostas/:id`. */
    suspend fun deleteProposta(token: String, id: String) {
        execute(baseRequest(url("propostas/$id"), token).delete().build(), OkDto.serializer())
    }

    /** `POST /radar/filtros`. */
    suspend fun criarRadar(token: String, req: RadarUpsertRequest) {
        val payload = json.encodeToString(RadarUpsertRequest.serializer(), req)
        postJson(url("radar/filtros"), payload, OkDto.serializer(), token)
    }

    /** `PUT /radar/filtros/:id`. */
    suspend fun updateRadar(token: String, id: String, req: RadarUpsertRequest) {
        val payload = json.encodeToString(RadarUpsertRequest.serializer(), req)
        putJson(url("radar/filtros/$id"), payload, OkDto.serializer(), token)
    }

    /** `DELETE /radar/filtros/:id`. */
    suspend fun deleteRadar(token: String, id: String) {
        execute(baseRequest(url("radar/filtros/$id"), token).delete().build(), OkDto.serializer())
    }

    /** `POST /mensagens` {licitacaoId?, conteudo}. */
    suspend fun enviarMensagem(token: String, licitacaoId: String?, conteudo: String) {
        val payload = json.encodeToString(MensagemRequest.serializer(), MensagemRequest(licitacaoId, conteudo))
        postJson(url("mensagens"), payload, OkDto.serializer(), token)
    }

    /** `POST /licitacoes/:id/mensagens-portal`: mensagem do PREGOEIRO lida no portal pelo robô do aparelho. */
    suspend fun enviarMensagemPregoeiro(token: String, licitacaoId: String, conteudo: String, remetente: String, enviadaEm: Long) {
        val payload = json.encodeToString(
            MensagemRequest.serializer(),
            MensagemRequest(licitacaoId, conteudo, tipo = "pregoeiro", remetente = remetente, enviadaEm = enviadaEm),
        )
        postJson(url("licitacoes/$licitacaoId/mensagens-portal"), payload, OkDto.serializer(), token)
    }

    /** `POST /licitacoes/:id/robo-eventos` (lances/posição vistos pelo robô do aparelho). */
    suspend fun enviarRoboEvento(token: String, licitacaoId: String, evento: RoboEventoRequest) {
        postJson(url("licitacoes/$licitacaoId/robo-eventos"), json.encodeToString(RoboEventoRequest.serializer(), evento), OkDto.serializer(), token)
    }

    /** `GET /mensagens?licitacaoId=` (filtro opcional por licitação). */
    suspend fun mensagens(token: String, licitacaoId: String? = null): List<MensagemDto> {
        val u = url("mensagens").newBuilder()
            .apply { licitacaoId?.takeIf { it.isNotBlank() }?.let { addQueryParameter("licitacaoId", it) } }
            .build()
        return get(u, ListSerializer(MensagemDto.serializer()), token = token) ?: emptyList()
    }

    /** `GET /certidoes`. */
    suspend fun certidoes(token: String): List<CertidaoDto> =
        get(url("certidoes"), ListSerializer(CertidaoDto.serializer()), token = token) ?: emptyList()

    /** `POST /documentos` (multipart): envia um arquivo + metadados. */
    suspend fun uploadDocumento(
        token: String,
        bytes: ByteArray,
        fileName: String,
        nome: String,
        categoria: String,
        validade: String?,
    ) {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("arquivo", fileName, bytes.toRequestBody(OCTET_MEDIA, 0, bytes.size))
            .addFormDataPart("nome", nome)
            .addFormDataPart("categoria", categoria)
            .apply { validade?.takeIf { it.isNotBlank() }?.let { addFormDataPart("validade", it) } }
            .build()
        val request = Request.Builder().url(url("documentos"))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        execute(request, OkDto.serializer())
    }

    /** `DELETE /documentos/:id`. */
    suspend fun deleteDocumento(token: String, id: String) {
        val request = baseRequest(url("documentos/$id"), token).delete().build()
        execute(request, OkDto.serializer())
    }

    /** `GET /documentos/:id/download` → bytes do arquivo (Bearer). Erros em pt-BR como nas demais rotas. */
    suspend fun downloadDocumento(token: String, id: String): ByteArray =
        getBytes(url("documentos/$id/download"), token)

    private suspend fun getBytes(url: HttpUrl, token: String?): ByteArray = withContext(Dispatchers.IO) {
        val request = baseRequest(url, token).get().build()
        val response = try {
            client.newCall(request).await()
        } catch (e: SocketTimeoutException) {
            throw PlatformException("A plataforma demorou demais para responder. Tente novamente.", PlatformException.Kind.TIMEOUT, cause = e)
        } catch (e: UnknownHostException) {
            throw PlatformException("Sem conexão com a plataforma. Verifique sua internet.", PlatformException.Kind.OFFLINE, cause = e)
        } catch (e: IOException) {
            if (e is PlatformException) throw e
            throw PlatformException("Não foi possível conectar à plataforma. Verifique sua internet.", PlatformException.Kind.OFFLINE, cause = e)
        }
        response.use { r ->
            if (!r.isSuccessful) throw httpError(r)
            r.body?.bytes() ?: throw PlatformException("Arquivo vazio.", PlatformException.Kind.INVALID_RESPONSE, r.code)
        }
    }

    /** Lê a primeira chave string/number não-vazia dentre [keys] (tolerante a objetos/null aninhados). */
    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() && s != "null" } }

    private suspend fun <T> postJson(url: HttpUrl, body: String, serializer: KSerializer<T>, token: String?): T? {
        val request = baseRequest(url, token).post(body.toRequestBody(JSON_MEDIA)).build()
        return execute(request, serializer)
    }

    private suspend fun <T> putJson(url: HttpUrl, body: String, serializer: KSerializer<T>, token: String?): T? {
        val request = baseRequest(url, token).put(body.toRequestBody(JSON_MEDIA)).build()
        return execute(request, serializer)
    }

    /** `GET /usuarios` → perfis/acessos da empresa (array). */
    suspend fun usuarios(token: String): List<UsuarioDto> =
        get(url("usuarios"), ListSerializer(UsuarioDto.serializer()), token = token) ?: emptyList()

    /** `GET /empresas` → empresas visíveis ao usuário (array). */
    suspend fun empresas(token: String): List<EmpresaDetailDto> =
        get(url("empresas"), ListSerializer(EmpresaDetailDto.serializer()), token = token) ?: emptyList()

    // ------------------------------------------------------------------ infra

    private fun url(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    private suspend fun <T> get(url: HttpUrl, serializer: KSerializer<T>, token: String?): T? {
        val request = baseRequest(url, token).get().build()
        return execute(request, serializer)
    }

    private suspend fun <T> put(url: HttpUrl, serializer: KSerializer<T>, token: String?): T? {
        val request = baseRequest(url, token).put(ByteArray(0).toRequestBody(JSON_MEDIA)).build()
        return execute(request, serializer)
    }

    private fun baseRequest(url: HttpUrl, token: String?): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> execute(request: Request, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: SocketTimeoutException) {
            throw PlatformException("A plataforma demorou demais para responder. Tente novamente.", PlatformException.Kind.TIMEOUT, cause = e)
        } catch (e: UnknownHostException) {
            throw PlatformException("Sem conexão com a plataforma. Verifique sua internet.", PlatformException.Kind.OFFLINE, cause = e)
        } catch (e: IOException) {
            if (e is PlatformException) throw e
            val timeout = e.message?.contains("timeout", ignoreCase = true) == true
            throw PlatformException(
                if (timeout) "A plataforma demorou demais para responder. Tente novamente."
                else "Não foi possível conectar à plataforma. Verifique sua internet.",
                if (timeout) PlatformException.Kind.TIMEOUT else PlatformException.Kind.OFFLINE,
                cause = e,
            )
        }
        response.use { r ->
            when {
                r.code == 204 -> null
                r.isSuccessful -> {
                    val source = r.body?.source() ?: return@use null
                    if (source.exhausted()) return@use null
                    try {
                        json.decodeFromStream(serializer, source.inputStream())
                    } catch (e: SerializationException) {
                        throw PlatformException("A plataforma devolveu uma resposta inesperada.", PlatformException.Kind.INVALID_RESPONSE, r.code, cause = e)
                    } catch (e: IllegalArgumentException) {
                        throw PlatformException("A plataforma devolveu uma resposta inesperada.", PlatformException.Kind.INVALID_RESPONSE, r.code, cause = e)
                    }
                }
                else -> throw httpError(r)
            }
        }
    }

    private fun httpError(r: Response): PlatformException {
        val err = runCatching {
            r.body?.string()?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(ErrorDto.serializer(), it) }
        }.getOrNull()
        val serverMsg = err?.message
        return when (r.code) {
            401, 403 -> PlatformException(
                serverMsg ?: "Sessão expirada. Entre novamente na plataforma.",
                PlatformException.Kind.UNAUTHORIZED, r.code, err?.code,
            )
            429 -> PlatformException(
                serverMsg ?: "Muitas tentativas. Aguarde alguns minutos e tente novamente.",
                PlatformException.Kind.HTTP, r.code, err?.code,
            )
            in 500..599 -> PlatformException(
                serverMsg ?: "A plataforma está indisponível no momento (HTTP ${r.code}).",
                PlatformException.Kind.HTTP, r.code, err?.code,
            )
            else -> PlatformException(serverMsg ?: "Falha na plataforma (HTTP ${r.code}).", PlatformException.Kind.HTTP, r.code, err?.code)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
        private const val USER_AGENT = "LicitaPRO-Android (plataforma)"
    }
}
