package com.licitaia.core.data.lookup

import com.licitaia.domain.lookup.CepData
import com.licitaia.domain.lookup.CnpjData
import com.licitaia.domain.lookup.CnpjPartner
import com.licitaia.domain.lookup.CompanyAutofill
import com.licitaia.domain.lookup.CompanyLookup
import com.licitaia.domain.lookup.LookupException
import com.licitaia.domain.network.OfflineException
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Endereços base das APIs públicas (substituídos nos testes por um MockWebServer). */
internal data class LookupEndpoints(
    val brasilApi: HttpUrl = "https://brasilapi.com.br/".toHttpUrl(),
    val cnpjWs: HttpUrl = "https://publica.cnpj.ws/".toHttpUrl(),
    val viaCep: HttpUrl = "https://viacep.com.br/".toHttpUrl(),
)

/**
 * Consulta pública e gratuita (sem chave) de CNPJ e CEP.
 *  - CNPJ: BrasilAPI (`/api/cnpj/v1/{cnpj}`) e, se falhar, CNPJ.ws pública (`/cnpj/{cnpj}`).
 *  - CEP: ViaCEP (`/ws/{cep}/json/`) e, se falhar, BrasilAPI (`/api/cep/v2/{cep}`).
 * Timeout curto (a tela espera a resposta), sem rede falha na hora, 404/"erro" = não encontrado,
 * 429 = limite de consultas. Somente leitura: nada é gravado aqui.
 */
@Singleton
class PublicCompanyLookup internal constructor(
    client: OkHttpClient,
    private val endpoints: LookupEndpoints,
) : CompanyLookup {

    @Inject
    constructor(client: OkHttpClient) : this(client, LookupEndpoints())

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun lookupCnpj(cnpj: String): CnpjData {
        val digits = cnpj.filter { it.isDigit() }
        if (digits.length != 14) throw LookupException("Informe os 14 dígitos do CNPJ.")
        return firstAvailable(
            what = "o CNPJ",
            notFoundMessage = "CNPJ não encontrado na Receita Federal. Confira os números ou preencha manualmente.",
            attempts = listOf(
                endpoints.brasilApi.newBuilder().addPathSegments("api/cnpj/v1").addPathSegment(digits).build() to { body: JsonObject -> parseBrasilApiCnpj(body) },
                endpoints.cnpjWs.newBuilder().addPathSegment("cnpj").addPathSegment(digits).build() to { body: JsonObject -> parseCnpjWs(body) },
            ),
        )
    }

    override suspend fun lookupCep(cep: String): CepData {
        val digits = cep.filter { it.isDigit() }
        if (digits.length != 8) throw LookupException("Informe os 8 dígitos do CEP.")
        return firstAvailable(
            what = "o CEP",
            notFoundMessage = "CEP não encontrado. Confira os números ou preencha o endereço manualmente.",
            attempts = listOf(
                endpoints.viaCep.newBuilder().addPathSegment("ws").addPathSegment(digits).addPathSegment("json").addPathSegment("").build() to { body: JsonObject -> parseViaCep(body) },
                endpoints.brasilApi.newBuilder().addPathSegments("api/cep/v2").addPathSegment(digits).build() to { body: JsonObject -> parseBrasilApiCep(body) },
            ),
        )
    }

    /**
     * Tenta cada fonte em ordem. Sem internet: falha na hora (nenhuma fonte vai responder). Não encontrado em
     * todas as fontes que responderam → [LookupException] com notFound. Qualquer outra falha → próxima fonte.
     */
    private suspend fun <T> firstAvailable(what: String, notFoundMessage: String, attempts: List<Pair<HttpUrl, (JsonObject) -> T>>): T =
        withContext(Dispatchers.IO) {
            var notFound = 0
            var rateLimited = false
            for ((url, parse) in attempts) {
                val outcome = try {
                    fetch(url)
                } catch (e: OfflineException) {
                    throw LookupException(e.message ?: OfflineException.OFFLINE_MESSAGE, cause = e)
                } catch (e: UnknownHostException) {
                    throw LookupException("Sem internet para consultar $what. Preencha manualmente ou tente de novo.", cause = e)
                } catch (e: IOException) {
                    Fetch.Failed
                }
                when (outcome) {
                    is Fetch.Ok -> {
                        if (isNotFoundBody(outcome.body)) {
                            notFound++
                            continue
                        }
                        val parsed = runCatching { parse(outcome.body) }.getOrNull()
                        if (parsed != null) return@withContext parsed
                    }
                    Fetch.NotFound -> notFound++
                    Fetch.RateLimited -> rateLimited = true
                    Fetch.Failed -> Unit
                }
            }
            throw when {
                notFound > 0 && !rateLimited ->LookupException(notFoundMessage, notFound = true)
                rateLimited -> LookupException("Limite de consultas gratuitas atingido. Aguarde um minuto e tente novamente, ou preencha manualmente.")
                else -> LookupException("Não foi possível consultar $what agora. Tente novamente ou preencha manualmente.")
            }
        }

    private sealed interface Fetch {
        data class Ok(val body: JsonObject) : Fetch
        data object NotFound : Fetch
        data object RateLimited : Fetch
        data object Failed : Fetch
    }

    private suspend fun fetch(url: HttpUrl): Fetch {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get().build()
        return client.newCall(request).await().use { r ->
            when {
                r.code == 404 || r.code == 400 -> Fetch.NotFound
                r.code == 429 -> Fetch.RateLimited
                !r.isSuccessful -> Fetch.Failed
                else -> {
                    val text = r.body?.string().orEmpty()
                    val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
                    if (element is JsonObject) Fetch.Ok(element) else Fetch.Failed
                }
            }
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

    internal companion object {
        private const val USER_AGENT = "LicitaPRO-Android (consulta publica de CNPJ/CEP)"

        /** ViaCEP responde 200 com `{"erro": true}` (ou `"true"`) para CEP inexistente. */
        fun isNotFoundBody(body: JsonObject): Boolean {
            val erro = body["erro"] as? JsonPrimitive ?: return false
            return erro.content.equals("true", ignoreCase = true)
        }

        private fun JsonObject?.str(key: String): String {
            val v = this?.get(key) ?: return ""
            return if (v is JsonPrimitive && v !is JsonNull) v.content.trim() else ""
        }

        private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

        private fun JsonObject?.arr(key: String): List<JsonElement> = (this?.get(key) as? JsonArray).orEmpty()

        /** BrasilAPI `/api/cnpj/v1/{cnpj}` (dados da Receita em campos planos). */
        fun parseBrasilApiCnpj(body: JsonObject): CnpjData {
            val legalName = body.str("razao_social")
            require(legalName.isNotEmpty()) { "resposta sem razão social" }
            return CnpjData(
                cnpj = body.str("cnpj").filter { it.isDigit() },
                legalName = legalName,
                tradeName = body.str("nome_fantasia"),
                streetType = body.str("descricao_tipo_de_logradouro"),
                street = body.str("logradouro"),
                number = body.str("numero"),
                complement = body.str("complemento"),
                district = body.str("bairro"),
                zipCode = body.str("cep").filter { it.isDigit() }.take(8),
                city = body.str("municipio"),
                uf = body.str("uf").uppercase(),
                phone = CompanyAutofill.phoneDigits(body.str("ddd_telefone_1")),
                email = body.str("email").lowercase(),
                status = body.str("descricao_situacao_cadastral").uppercase(),
                cnaeCode = body.str("cnae_fiscal"),
                cnaeDescription = body.str("cnae_fiscal_descricao"),
                size = body.str("porte").ifEmpty { body.str("descricao_porte") },
                partners = body.arr("qsa").mapNotNull { it as? JsonObject }.map {
                    CnpjPartner(it.str("nome_socio"), it.str("qualificacao_socio"))
                }.filter { it.name.isNotEmpty() },
                source = "BrasilAPI",
            )
        }

        /** CNPJ.ws pública `/cnpj/{cnpj}` (endereço/contato dentro de `estabelecimento`). */
        fun parseCnpjWs(body: JsonObject): CnpjData {
            val legalName = body.str("razao_social")
            require(legalName.isNotEmpty()) { "resposta sem razão social" }
            val est = body.obj("estabelecimento")
            val activity = est.obj("atividade_principal")
            return CnpjData(
                cnpj = est.str("cnpj").filter { it.isDigit() },
                legalName = legalName,
                tradeName = est.str("nome_fantasia"),
                streetType = est.str("tipo_logradouro"),
                street = est.str("logradouro"),
                number = est.str("numero"),
                complement = est.str("complemento"),
                district = est.str("bairro"),
                zipCode = est.str("cep").filter { it.isDigit() }.take(8),
                city = est.obj("cidade").str("nome"),
                uf = est.obj("estado").str("sigla").uppercase(),
                phone = CompanyAutofill.phoneDigits(est.str("ddd1") + est.str("telefone1")),
                email = est.str("email").lowercase(),
                status = est.str("situacao_cadastral").uppercase(),
                cnaeCode = activity.str("id").ifEmpty { activity.str("subclasse") },
                cnaeDescription = activity.str("descricao"),
                size = body.obj("porte").str("descricao"),
                partners = body.arr("socios").mapNotNull { it as? JsonObject }.map {
                    CnpjPartner(it.str("nome"), it.obj("qualificacao_socio").str("descricao"))
                }.filter { it.name.isNotEmpty() },
                source = "CNPJ.ws",
            )
        }

        /** ViaCEP `/ws/{cep}/json/`. */
        fun parseViaCep(body: JsonObject): CepData {
            val city = body.str("localidade")
            require(city.isNotEmpty()) { "resposta sem cidade" }
            return CepData(
                cep = body.str("cep").filter { it.isDigit() },
                street = body.str("logradouro"),
                complement = body.str("complemento"),
                district = body.str("bairro"),
                city = city,
                uf = body.str("uf").uppercase(),
                source = "ViaCEP",
            )
        }

        /** BrasilAPI `/api/cep/v2/{cep}`. */
        fun parseBrasilApiCep(body: JsonObject): CepData {
            val city = body.str("city")
            require(city.isNotEmpty()) { "resposta sem cidade" }
            return CepData(
                cep = body.str("cep").filter { it.isDigit() },
                street = body.str("street"),
                district = body.str("neighborhood"),
                city = city,
                uf = body.str("state").uppercase(),
                source = "BrasilAPI",
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CompanyLookupModule {
    @Binds abstract fun companyLookup(impl: PublicCompanyLookup): CompanyLookup
}
