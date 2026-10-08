package com.licitaia.core.platform.net

import com.licitaia.core.platform.PlatformConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    ): TenderPageDto {
        val u = url("licitacoes").newBuilder()
            .addQueryParameter("leve", "true")
            .addQueryParameter("limit", limit.coerceIn(1, 200).toString())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("ordenar", ordenar)
            .apply { if (incluirOcultas) addQueryParameter("incluirOcultas", "true") }
            .apply { busca?.trim()?.takeIf { it.isNotBlank() }?.let { addQueryParameter("busca", it) } }
            .build()
        return get(u, TenderPageDto.serializer(), token = token) ?: TenderPageDto()
    }

    /** `GET /licitacoes/:id` → detalhe completo (campos pesados incluídos; só mapeamos os que a UI usa). */
    suspend fun licitacaoDetalhe(token: String, id: String): TenderDto? =
        get(url("licitacoes/$id"), TenderDto.serializer(), token = token)

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
        private const val USER_AGENT = "LicitaPRO-Android (plataforma)"
    }
}
