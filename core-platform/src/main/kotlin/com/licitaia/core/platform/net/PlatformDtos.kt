package com.licitaia.core.platform.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs de fio da API LicitaPRO (CONTRATO_API). Só os campos que o app usa hoje — o Json de core-network tem
 * `ignoreUnknownKeys = true`, então campos extras (editalTexto, scores, etc.) são ignorados sem quebrar o parse.
 */

@Serializable
data class LoginRequest(val email: String, val senha: String)

/** `POST /auth/register` (auto-cadastro por CNPJ) → `{token,user}`. */
@Serializable
data class RegisterRequest(
    val nome: String,
    val email: String,
    val senha: String,
    val cnpj: String,
    val razaoSocial: String,
)

/** `POST /auth/register-convite` (cadastro por código de convite) → `{token,user}`. */
@Serializable
data class RegisterConviteRequest(
    val codigo: String,
    val nome: String,
    val email: String,
    val senha: String,
)

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

/** `GET /empresas` → empresas do escopo do usuário (admin vê a própria empresa). */
@Serializable
data class EmpresaDetailDto(
    val id: String = "",
    val cnpj: String = "",
    val razaoSocial: String = "",
    val nomeFantasia: String? = null,
    val cidade: String? = null,
    val estado: String? = null,
)

/** `GET /usuarios` → usuários da empresa (perfis/acessos). */
@Serializable
data class UsuarioDto(
    val id: String,
    val nome: String = "",
    val email: String = "",
    val role: String = "operador",
    val ativo: Boolean = true,
    val createdAt: String? = null,
)

/** `GET /documentos` → documentos/metadados da empresa (sem baixar o arquivo). */
@Serializable
data class DocumentoDto(
    val id: String = "",
    val nome: String = "",
    val categoria: String? = null,
    val status: String? = null,
    val validade: String? = null,
    val tamanho: Long? = null,
)

/** `GET /radar/filtros` → filtros de radar salvos da empresa. */
@Serializable
data class RadarFiltroDto(
    val id: String = "",
    val nome: String = "",
    val palavrasChave: List<String> = emptyList(),
    val portais: List<String> = emptyList(),
    val estados: List<String> = emptyList(),
    val modalidades: List<String> = emptyList(),
    val valorMinimo: String? = null,
    val valorMaximo: String? = null,
    val ativo: Boolean = true,
    val countMatch: Int? = null,
    val updatedAt: String? = null,
)

/** `GET /concorrente` → concorrentes mapeados da empresa (paginado). */
@Serializable
data class ConcorrentePageDto(
    val data: List<ConcorrenteDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ConcorrenteDto(
    val id: String = "",
    val cnpj: String = "",
    val razaoSocial: String = "",
    val nomeFantasia: String? = null,
    val cidade: String? = null,
    val estado: String? = null,
    val porte: String? = null,
    val situacao: String? = null,
)

/**
 * `GET /mensagens` → mensagens do chat de disputa (array). Todos os campos opcionais: o backend ainda não
 * tem registros, então o formato exato não foi observado ao vivo — tolerante a chaves desconhecidas.
 */
@Serializable
data class MensagemDto(
    val id: String? = null,
    val titulo: String? = null,
    val assunto: String? = null,
    val mensagem: String? = null,
    val texto: String? = null,
    val conteudo: String? = null,
    val remetente: String? = null,
    val autor: String? = null,
    val licitacaoId: String? = null,
    val lida: Boolean = false,
    val createdAt: String? = null,
) {
    /** Melhor título disponível. */
    val displayTitle: String get() = titulo ?: assunto ?: remetente ?: autor ?: "Mensagem"
    /** Melhor corpo disponível. */
    val displayBody: String get() = mensagem ?: texto ?: conteudo ?: ""
}

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
    /** Link da proposta (quando a empresa já tem proposta vinculada → sinal de participação). */
    val urlProposta: String? = null,
    val estado: String? = null,
    val cidade: String? = null,
    val fase: String? = null,
    val status: String? = null,
    val favorita: Boolean = false,
    @SerialName("scoreRelevancia") val scoreRelevancia: Int? = null,
    val updatedAt: String? = null,
    val empresaId: String? = null,
)
