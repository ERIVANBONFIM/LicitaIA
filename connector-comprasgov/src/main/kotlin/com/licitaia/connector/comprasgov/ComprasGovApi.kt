package com.licitaia.connector.comprasgov

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** Falha ao consultar o Compras.gov.br, já com mensagem amigável em pt-BR. */
class ComprasGovException(message: String, val kind: Kind, val httpStatus: Int? = null, cause: Throwable? = null) :
    IOException(message, cause) {
    enum class Kind { OFFLINE, TIMEOUT, HTTP, INVALID_RESPONSE }
}

/**
 * Cliente HTTP mínimo da API pública de Dados Abertos do Compras.gov.br (somente GET, sem chave).
 *
 * Endpoints usados — todos documentados em https://dadosabertos.compras.gov.br/v3/api-docs e
 * verificados com requisições reais em 06/10/2026:
 * - `GET /modulo-contratacoes/1_consultarContratacoes_PNCP_14133` — contratações da Lei 14.133
 *   (obrigatórios: dataPublicacaoPncpInicial/Final=YYYY-MM-DD, codigoModalidade; opcionais:
 *   unidadeOrgaoUfSigla, orgaoEntidadeCnpj, codigoOrgao, unidadeOrgaoCodigoUnidade; pagina ≥ 1,
 *   tamanhoPagina 10..500). Resultado ordenado por dataPublicacaoPncp ASCENDENTE.
 * - `GET /modulo-contratacoes/1.1_consultarContratacoes_PNCP_14133_Id` — uma contratação
 *   (tipo=idCompra|numeroControlePNCPCompra, codigo=...; valores aceitos informados pela própria API).
 * - `GET /modulo-contratacoes/2.1_consultarItensContratacoes_PNCP_14133_Id` — itens da contratação.
 * - `GET /modulo-legado/1_consultarLicitacao` — licitações legadas (Lei 8.666; obrigatórios
 *   data_publicacao_inicial/final=YYYY-MM-DD; opcionais uasg, modalidade, numero_aviso).
 * - `GET /modulo-legado/1.1_consultarLicitacao_Id` e `2.1_consultarItemLicitacao_Id` (id_compra).
 *
 * Erros de validação chegam como HTTP 400 em JSON "problem" (`title`, `detail`); rota inexistente
 * devolve 404 `{statusCode, message}`. Resultado vazio é HTTP 200 com `resultado: []`.
 */
internal class ComprasGovApi(
    client: OkHttpClient,
    private val json: Json,
    /** Raiz da API (https://dadosabertos.compras.gov.br/). Parametrizável para testes com MockWebServer. */
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun contratacoes14133(
        dataInicial: String,
        dataFinal: String,
        codigoModalidade: Int,
        uf: String?,
        pagina: Int,
        tamanhoPagina: Int,
    ): ComprasGovPage<ComprasGovContratacao> {
        val url = path("modulo-contratacoes/1_consultarContratacoes_PNCP_14133")
            .addQueryParameter("dataPublicacaoPncpInicial", dataInicial)
            .addQueryParameter("dataPublicacaoPncpFinal", dataFinal)
            .addQueryParameter("codigoModalidade", codigoModalidade.toString())
            .apply { if (!uf.isNullOrBlank()) addQueryParameter("unidadeOrgaoUfSigla", uf.uppercase()) }
            .addQueryParameter("pagina", pagina.coerceAtLeast(1).toString())
            .addQueryParameter("tamanhoPagina", tamanhoPagina.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE).toString())
            .build()
        return get(url, ComprasGovPage.serializer(ComprasGovContratacao.serializer())) ?: ComprasGovPage()
    }

    suspend fun contratacao14133(numeroControlePncp: String): ComprasGovContratacao? =
        get(
            path("modulo-contratacoes/1.1_consultarContratacoes_PNCP_14133_Id")
                .addQueryParameter("tipo", "numeroControlePNCPCompra")
                .addQueryParameter("codigo", numeroControlePncp)
                .build(),
            ComprasGovPage.serializer(ComprasGovContratacao.serializer()),
        )?.resultado?.firstOrNull()

    suspend fun itens14133(numeroControlePncp: String): List<ComprasGovItem> =
        get(
            path("modulo-contratacoes/2.1_consultarItensContratacoes_PNCP_14133_Id")
                .addQueryParameter("tipo", "numeroControlePNCPCompra")
                .addQueryParameter("codigo", numeroControlePncp)
                .build(),
            ComprasGovPage.serializer(ComprasGovItem.serializer()),
        )?.resultado.orEmpty()

    suspend fun licitacoesLegado(
        dataInicial: String,
        dataFinal: String,
        modalidade: Int?,
        pagina: Int,
        tamanhoPagina: Int,
    ): ComprasGovPage<ComprasGovLicitacaoLegado> {
        val url = path("modulo-legado/1_consultarLicitacao")
            .addQueryParameter("data_publicacao_inicial", dataInicial)
            .addQueryParameter("data_publicacao_final", dataFinal)
            .apply { if (modalidade != null) addQueryParameter("modalidade", modalidade.toString()) }
            .addQueryParameter("pagina", pagina.coerceAtLeast(1).toString())
            .addQueryParameter("tamanhoPagina", tamanhoPagina.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE).toString())
            .build()
        return get(url, ComprasGovPage.serializer(ComprasGovLicitacaoLegado.serializer())) ?: ComprasGovPage()
    }

    suspend fun licitacaoLegado(idCompra: String): ComprasGovLicitacaoLegado? =
        get(
            path("modulo-legado/1.1_consultarLicitacao_Id").addQueryParameter("id_compra", idCompra).build(),
            ComprasGovPage.serializer(ComprasGovLicitacaoLegado.serializer()),
        )?.resultado?.firstOrNull()

    suspend fun itensLegado(idCompra: String): List<ComprasGovItemLegado> =
        get(
            path("modulo-legado/2.1_consultarItemLicitacao_Id").addQueryParameter("id_compra", idCompra).build(),
            ComprasGovPage.serializer(ComprasGovItemLegado.serializer()),
        )?.resultado.orEmpty()

    // ------------------------------------------------------------------ infra

    private fun path(segments: String): HttpUrl.Builder = baseUrl.newBuilder().addEncodedPathSegments(segments)

    /** GET com decodificação; devolve null em 204/corpo vazio. */
    private suspend fun <T> get(url: HttpUrl, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", USER_AGENT).get().build()
        val response = try {
            client.newCall(request).await()
        } catch (e: SocketTimeoutException) {
            throw ComprasGovException(TIMEOUT_MESSAGE, ComprasGovException.Kind.TIMEOUT, cause = e)
        } catch (e: UnknownHostException) {
            throw ComprasGovException("Sem conexão com o Compras.gov.br. Verifique sua internet e tente novamente.", ComprasGovException.Kind.OFFLINE, cause = e)
        } catch (e: IOException) {
            if (e is ComprasGovException) throw e
            val timeout = e.message?.contains("timeout", ignoreCase = true) == true
            throw ComprasGovException(
                if (timeout) TIMEOUT_MESSAGE else "Não foi possível conectar ao Compras.gov.br. Verifique sua internet e tente novamente.",
                if (timeout) ComprasGovException.Kind.TIMEOUT else ComprasGovException.Kind.OFFLINE,
                cause = e,
            )
        }
        response.use { r ->
            when {
                r.code == 204 -> null
                r.isSuccessful -> {
                    val body = r.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    try {
                        json.decodeFromString(serializer, body)
                    } catch (e: SerializationException) {
                        throw ComprasGovException(INVALID_MESSAGE, ComprasGovException.Kind.INVALID_RESPONSE, r.code, e)
                    } catch (e: IllegalArgumentException) {
                        throw ComprasGovException(INVALID_MESSAGE, ComprasGovException.Kind.INVALID_RESPONSE, r.code, e)
                    }
                }
                else -> throw ComprasGovException(httpMessage(r.code, problemDetail(r)), ComprasGovException.Kind.HTTP, r.code)
            }
        }
    }

    /** Extrai `detail`/`title` do JSON de erro (RFC 7807) que a API devolve em 400; null se não houver. */
    private fun problemDetail(response: Response): String? = try {
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) null
        else {
            val obj = json.decodeFromString(JsonObject.serializer(), body)
            (obj["detail"] ?: obj["title"] ?: obj["message"])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    private fun httpMessage(code: Int, detail: String?): String = when (code) {
        400, 422 -> "O Compras.gov.br rejeitou os parâmetros da consulta (HTTP $code)." + (detail?.let { " $it" } ?: "")
        404 -> "Consulta não encontrada no Compras.gov.br (HTTP 404)."
        429 -> "O Compras.gov.br limitou a quantidade de consultas. Aguarde alguns minutos e tente novamente."
        in 500..599 -> "O Compras.gov.br está indisponível no momento (HTTP $code). Tente novamente mais tarde."
        else -> "Falha ao consultar o Compras.gov.br (HTTP $code)."
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
        const val DEFAULT_BASE_URL = "https://dadosabertos.compras.gov.br/"
        /** Limites confirmados pela API (HTTP 400 "deve ser no mínimo 10" / "no máximo 500"). */
        const val MIN_PAGE_SIZE = 10
        const val MAX_PAGE_SIZE = 500
        private const val USER_AGENT = "LicitaIA-Android (consulta publica Compras.gov.br dados abertos)"
        private const val TIMEOUT_MESSAGE = "O Compras.gov.br demorou demais para responder. Tente novamente em instantes."
        private const val INVALID_MESSAGE = "O Compras.gov.br devolveu uma resposta em formato inesperado."
    }
}
