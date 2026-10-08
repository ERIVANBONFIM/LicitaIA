package com.licitaia.core.platform.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs de fio da API LicitaPRO (CONTRATO_API). Só os campos que o app usa hoje — o Json de core-network tem
 * `ignoreUnknownKeys = true`, então campos extras (editalTexto, scores, etc.) são ignorados sem quebrar o parse.
 */

@Serializable
data class LoginRequest(val email: String, val senha: String)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserDto,
)

@Serializable
data class UserDto(
    val id: String,
    val nome: String = "",
    val email: String = "",
    val role: String = "operador",
    val empresa: EmpresaDto? = null,
)

@Serializable
data class EmpresaDto(
    val id: String = "",
    val cnpj: String = "",
    val razaoSocial: String = "",
)

/** `{ "error": "mensagem" }` (algumas rotas antigas usam `erro`; aceitamos as duas). */
@Serializable
data class ErrorDto(
    val error: String? = null,
    val erro: String? = null,
    val code: String? = null,
) {
    val message: String? get() = error ?: erro
}

@Serializable
data class HealthDto(
    val status: String = "",
    val version: String? = null,
    val timestamp: String? = null,
)

@Serializable
data class OkDto(val ok: Boolean = false)

/** Página padrão do contrato: `{ data, total, page, totalPages }`. */
@Serializable
data class TenderPageDto(
    val data: List<TenderDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val totalPages: Int = 1,
)

/**
 * Licitação na listagem leve (`GET /licitacoes?leve=true`). Decimais chegam como String (converter com
 * BigDecimal na UI); datas em ISO-8601 UTC.
 */
@Serializable
data class TenderDto(
    val id: String,
    val numero: String = "",
    val orgao: String = "",
    val uasg: String? = null,
    val objeto: String = "",
    val modalidade: String? = null,
    val modoDisputa: String? = null,
    val valorEstimado: String? = null,
    val dataPublicacao: String? = null,
    val dataAbertura: String? = null,
    val dataEncerramento: String? = null,
    val portal: String? = null,
    val portalUrl: String? = null,
    val estado: String? = null,
    val cidade: String? = null,
    val fase: String? = null,
    val status: String? = null,
    val favorita: Boolean = false,
    @SerialName("scoreRelevancia") val scoreRelevancia: Int? = null,
    val updatedAt: String? = null,
    val empresaId: String? = null,
)
