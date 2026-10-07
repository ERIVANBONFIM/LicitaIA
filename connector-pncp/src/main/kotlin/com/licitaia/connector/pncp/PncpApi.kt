package com.licitaia.connector.pncp

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Falha ao consultar o PNCP, já com mensagem amigável em pt-BR. */
class PncpException(
    message: String,
    val kind: Kind,
    override val httpStatus: Int? = null,
    cause: Throwable? = null,
    /** Cabeçalho `Retry-After` (em ms) de uma resposta 429/503, quando informado em segundos. */
    val retryAfterMs: Long? = null,
) : IOException(message, cause), com.licitaia.connector.api.HttpStatusFailure {
    enum class Kind { OFFLINE, TIMEOUT, HTTP, INVALID_RESPONSE }
}

/**
 * Cliente HTTP mínimo da API pública do PNCP (somente GET, sem autenticação).
 *
 * Endpoints usados — todos documentados nos OpenAPI oficiais e verificados com requisições reais:
 * - `GET /api/consulta/v1/contratacoes/proposta` — contratações com recebimento de propostas aberto
 *   (obrigatórios: dataFinal=yyyyMMdd, pagina; opcionais: codigoModalidadeContratacao, uf, tamanhoPagina 10..50).
 * - `GET /api/consulta/v1/contratacoes/publicacao` — contratações por data de publicação
 *   (obrigatórios: dataInicial, dataFinal, codigoModalidadeContratacao, pagina; opcionais: uf, tamanhoPagina 10..50).
 * - `GET /api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}` — detalhe de uma contratação.
 * - `GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/arquivos` — documentos públicos (edital etc.).
 * - `GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens` — itens da contratação.
 * - `GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens/{numeroItem}/resultados` — fornecedor homologado.
 *
 * Respostas vazias chegam como HTTP 204 sem corpo (conforme OpenAPI) e são tratadas como lista vazia.
 */
internal class PncpApi(
    client: OkHttpClient,
    private val json: Json,
    /** Raiz do portal (ex.: https://pncp.gov.br/). Parametrizável para testes com MockWebServer. */
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun contratacoesComPropostaAberta(
        dataFinal: String,
        codigoModalidade: Int?,
        uf: String?,
        pagina: Int,
        tamanhoPagina: Int,
    ): PncpPage<PncpContratacao> {
        val url = consulta("v1/contratacoes/proposta")
            .addQueryParameter("dataFinal", dataFinal)
            .apply { if (codigoModalidade != null) addQueryParameter("codigoModalidadeContratacao", codigoModalidade.toString()) }
            .apply { if (!uf.isNullOrBlank()) addQueryParameter("uf", uf.uppercase()) }
            .addQueryParameter("pagina", pagina.toString())
            .addQueryParameter("tamanhoPagina", tamanhoPagina.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE).toString())
            .build()
        return get(url, PncpPage.serializer(PncpContratacao.serializer())) ?: PncpPage(empty = true)
    }

    suspend fun contratacoesPorPublicacao(
        dataInicial: String,
        dataFinal: String,
        codigoModalidade: Int,
        uf: String?,
        pagina: Int,
        tamanhoPagina: Int,
        /** CNPJ do órgão (parâmetro `cnpj` da consulta): só as contratações dele. */
        cnpjOrgao: String? = null,
    ): PncpPage<PncpContratacao> {
        val url = consulta("v1/contratacoes/publicacao")
            .addQueryParameter("dataInicial", dataInicial)
            .addQueryParameter("dataFinal", dataFinal)
            .addQueryParameter("codigoModalidadeContratacao", codigoModalidade.toString())
            .apply { if (!uf.isNullOrBlank()) addQueryParameter("uf", uf.uppercase()) }
            .apply { if (!cnpjOrgao.isNullOrBlank()) addQueryParameter("cnpj", cnpjOrgao) }
            .addQueryParameter("pagina", pagina.toString())
            .addQueryParameter("tamanhoPagina", tamanhoPagina.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE).toString())
            .build()
        return get(url, PncpPage.serializer(PncpContratacao.serializer())) ?: PncpPage(empty = true)
    }

    suspend fun contratacao(ref: PncpControlNumber): PncpContratacao? =
        get(consulta("v1/orgaos/${ref.cnpj}/compras/${ref.ano}/${ref.sequencial}").build(), PncpContratacao.serializer())

    suspend fun documentos(ref: PncpControlNumber): List<PncpDocumento> =
        get(
            integracao("v1/orgaos/${ref.cnpj}/compras/${ref.ano}/${ref.sequencial}/arquivos")
                .addQueryParameter("pagina", "1").addQueryParameter("tamanhoPagina", "50").build(),
            ListSerializer(PncpDocumento.serializer()),
        ).orEmpty()

    suspend fun itens(ref: PncpControlNumber, pagina: Int = 1, tamanhoPagina: Int = 50): List<PncpItem> =
        get(
            integracao("v1/orgaos/${ref.cnpj}/compras/${ref.ano}/${ref.sequencial}/itens")
                .addQueryParameter("pagina", pagina.coerceAtLeast(1).toString())
                .addQueryParameter("tamanhoPagina", tamanhoPagina.coerceIn(1, MAX_ITEMS_PAGE_SIZE).toString()).build(),
            ListSerializer(PncpItem.serializer()),
        ).orEmpty()

    /** Resultados (fornecedores homologados) de um item: `GET /api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens/{n}/resultados`. */
    suspend fun resultados(ref: PncpControlNumber, numeroItem: Int): List<PncpItemResultado> =
        get(
            integracao("v1/orgaos/${ref.cnpj}/compras/${ref.ano}/${ref.sequencial}/itens/$numeroItem/resultados").build(),
            ListSerializer(PncpItemResultado.serializer()),
        ).orEmpty()

    // ------------------------------------------------------------------ infra

    private fun consulta(path: String): HttpUrl.Builder = baseUrl.newBuilder().addEncodedPathSegments("api/consulta/$path")
    private fun integracao(path: String): HttpUrl.Builder = baseUrl.newBuilder().addEncodedPathSegments("api/pncp/$path")

    /** GET com decodificação; devolve null em 204 (sem conteúdo). */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> get(url: HttpUrl, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", USER_AGENT).get().build()
        val response = try {
            client.newCall(request).await()
        } catch (e: SocketTimeoutException) {
            throw PncpException("O PNCP demorou demais para responder. Tente novamente em instantes.", PncpException.Kind.TIMEOUT, cause = e)
        } catch (e: UnknownHostException) {
            throw PncpException("Sem conexão com o PNCP. Verifique sua internet e tente novamente.", PncpException.Kind.OFFLINE, cause = e)
        } catch (e: IOException) {
            if (e is PncpException) throw e
            val timeout = e.message?.contains("timeout", ignoreCase = true) == true
            throw PncpException(
                if (timeout) "O PNCP demorou demais para responder. Tente novamente em instantes."
                else "Não foi possível conectar ao PNCP. Verifique sua internet e tente novamente.",
                if (timeout) PncpException.Kind.TIMEOUT else PncpException.Kind.OFFLINE,
                cause = e,
            )
        }
        response.use { r ->
            when {
                r.code == 204 -> null
                r.isSuccessful -> {
                    // Decodifica direto do stream (sem montar a String da página), fora do Main.
                    val source = r.body?.source() ?: return@use null
                    if (source.exhausted()) return@use null
                    try {
                        json.decodeFromStream(serializer, source.inputStream())
                    } catch (e: SerializationException) {
                        throw PncpException("O PNCP devolveu uma resposta em formato inesperado.", PncpException.Kind.INVALID_RESPONSE, r.code, e)
                    } catch (e: IllegalArgumentException) {
                        throw PncpException("O PNCP devolveu uma resposta em formato inesperado.", PncpException.Kind.INVALID_RESPONSE, r.code, e)
                    }
                }
                else -> throw PncpException(
                    httpMessage(r.code), PncpException.Kind.HTTP, r.code,
                    retryAfterMs = r.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000L },
                )
            }
        }
    }

    private fun httpMessage(code: Int): String = when (code) {
        400, 422 -> "O PNCP rejeitou os parâmetros da consulta (HTTP $code)."
        404 -> "Contratação não encontrada no PNCP."
        429 -> "O PNCP limitou a quantidade de consultas. Aguarde alguns minutos e tente novamente."
        in 500..599 -> "O PNCP está indisponível no momento (HTTP $code). Tente novamente mais tarde."
        else -> "Falha ao consultar o PNCP (HTTP $code)."
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
        const val DEFAULT_BASE_URL = "https://pncp.gov.br/"
        const val MIN_PAGE_SIZE = 10
        const val MAX_PAGE_SIZE = 50
        /** Itens da contratação: a API de integração aceita páginas grandes (500 cobre quase todos os editais). */
        const val MAX_ITEMS_PAGE_SIZE = 500
        private const val USER_AGENT = "LicitaIA-Android (consulta publica PNCP)"
    }
}
