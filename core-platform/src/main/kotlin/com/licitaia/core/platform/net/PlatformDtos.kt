package com.licitaia.core.platform.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    val scoreRisco: Int? = null,
    /** Resumo do edital pela IA (quando já gerado na plataforma). */
    val editalResumoIA: String? = null,
    /** Veredito da IA (recomendação). */
    val veredito: String? = null,
    /** Preço sugerido pela IA (decimal como string). */
    val precoSugeridoIA: String? = null,
    /** Margem estimada pela IA (decimal como string). */
    val margemEstimadaIA: String? = null,
    /** Texto integral do edital coletado pela VPS (só no detalhe; pode ser grande/null). */
    val editalTexto: String? = null,
    val updatedAt: String? = null,
    val empresaId: String? = null,
)

/** `PUT /licitacoes/:id/favoritar` → `{ok, favorita}` (alterna). */
@Serializable
data class FavoritaResult(val ok: Boolean = false, val favorita: Boolean = false)

/** `PUT /licitacoes/:id/arquivar|ocultar` → `{ok, status}` (alterna ativa↔arquivada/oculta). */
@Serializable
data class StatusResult(val ok: Boolean = false, val status: String = "")

/** `GET/POST /ai/tenders/:id/analysis|analyze` → `{analysis, job}`. analysis tem shape variável. */
@Serializable
data class AnalysisStatusDto(
    val analysis: kotlinx.serialization.json.JsonElement? = null,
    val job: AnalysisJobDto? = null,
) {
    val hasAnalysis: Boolean get() = analysis != null && analysis !is kotlinx.serialization.json.JsonNull
}

@Serializable
data class AnalysisJobDto(val id: String? = null, val status: String? = null)

/** `GET /robo-lances/config/:id` (valorMinimo/decremento vêm como número aqui). */
@Serializable
data class RoboConfigDto(
    val ativo: Boolean = false,
    val modoExecucao: String? = null,
    val estrategia: String? = null,
    val valorMinimo: Double? = null,
    val decremento: Double? = null,
    val intervaloSegundos: Int? = null,
    val itemAlvo: String? = null,
)

/** Piso (lance mínimo) de UM item, para `pisosItens` do PUT da config do robô. */
@Serializable
data class PisoItemRequest(
    val numero: Int,
    val valorLanceMinimo: Double,
)

/**
 * `PUT /robo-lances/config/:id`. modoExecucao="auto" exige confirmarAuto=true (trava do backend) E piso em
 * TODOS os itens (senão 400 "Defina o lance mínimo de todos os itens"). [pisosItens] grava o piso POR ITEM.
 */
@Serializable
data class RoboConfigUpdateRequest(
    val estrategia: String? = null,
    val valorMinimo: Double,
    val decremento: Double,
    val intervaloSegundos: Int? = null,
    val itemAlvo: String? = null,
    val modoExecucao: String = "dry_run",
    val confirmarAuto: Boolean? = null,
    val pisosItens: List<PisoItemRequest>? = null,
)

/** `GET /robo-lances/prontidao/:id`. */
@Serializable
data class ProntidaoDto(
    val ok: Boolean = false,
    val estado: String? = null,
    val motivos: List<String> = emptyList(),
)

/** `GET /licitacoes/:id/ao-vivo` → acompanhamento ao vivo (só leitura). Decimais como string. */
@Serializable
data class AoVivoDto(
    val snapshots: List<AoVivoSnapshotDto> = emptyList(),
    val eventos: List<AoVivoEventoDto> = emptyList(),
    val ativo: Boolean = false,
)

@Serializable
data class AoVivoSnapshotDto(
    val itemNumero: Int? = null,
    val bestBid: String? = null,
    val ownBid: String? = null,
    val ownPosition: Int? = null,
    val floor: String? = null,
    val phase: String? = null,
    val situacao: String? = null,
    val portalState: String? = null,
    val capturedAt: String? = null,
)

@Serializable
data class AoVivoEventoDto(
    val previousState: String? = null,
    val newState: String? = null,
    val reason: String? = null,
    val severity: String? = null,
    val createdAt: String? = null,
)

/** `GET /robo-lances/ativas` (valorMinimo/decremento vêm como STRING aqui — tolerante). */
@Serializable
data class RoboAtivaDto(
    val id: String = "",
    val licitacaoId: String = "",
    val ativo: Boolean = false,
    val estrategia: String? = null,
    val valorMinimo: String? = null,
    val decremento: String? = null,
    val intervaloSegundos: Int? = null,
    val itemAlvo: String? = null,
    val modoExecucao: String? = null,
    val status: String? = null,
    val ultimoLanceValor: String? = null,
)

/** `GET /ia/chat-edital/:id/historico` → `{ok, mensagens:[...]}` (shape das mensagens tolerante). */
@Serializable
data class ChatHistoricoDto(
    val ok: Boolean = false,
    val mensagens: List<JsonObject> = emptyList(),
)

/** Mensagem do "Pergunte ao edital" (extraída de forma tolerante do histórico). */
data class ChatMsg(val autor: String, val texto: String)

/** `POST /ia/chat-edital/:id` body. */
@Serializable
data class ChatPerguntaRequest(val mensagem: String, val historico: List<String> = emptyList())

/** `GET /auditoria?limit=` → trilha de auditoria da empresa (detalhes é objeto → omitido aqui). */
@Serializable
data class AuditUserDto(val nome: String = "", val email: String = "")

@Serializable
data class AuditoriaItemDto(
    val id: String = "",
    val acao: String = "",
    val entidade: String? = null,
    val entidadeId: String? = null,
    val createdAt: String? = null,
    val usuario: AuditUserDto? = null,
)

@Serializable
data class AuditoriaPageDto(val data: List<AuditoriaItemDto> = emptyList(), val total: Int = 0)

/** `GET /licitacoes/:id/resultado` (null se não houver). Numéricos decimais podem vir como string. */
@Serializable
data class ResultadoDto(
    val resultado: String? = null,
    val valorProposto: String? = null,
    val valorArrematado: String? = null,
    val posicaoFinal: Int? = null,
    val totalConcorrentes: Int? = null,
    val margemFinal: String? = null,
)

/** `POST /licitacoes/:id/resultado`. */
@Serializable
data class ResultadoRequest(
    val resultado: String,
    val valorProposto: String? = null,
    val valorArrematado: String? = null,
    val posicaoFinal: Int? = null,
    val totalConcorrentes: Int? = null,
)

/** `GET /certidoes`. */
@Serializable
data class CertidaoDto(
    val id: String = "",
    val nome: String = "",
    val tipo: String? = null,
    val status: String? = null,
    val validade: String? = null,
)

/** `POST /mensagens` body (envio no detalhe/menu). */
@Serializable
data class MensagemRequest(
    val licitacaoId: String? = null,
    val conteudo: String,
    /** "pregoeiro" quando a mensagem foi LIDA do chat do portal pelo robô; null = escrita pelo usuário. */
    val tipo: String? = null,
    val remetente: String? = null,
    /** Momento original (epoch ms) da mensagem no portal. */
    val enviadaEm: Long? = null,
)

/**
 * `POST /licitacoes/:id/robo-eventos`: o que o robô do APARELHO viu/fez na disputa (lance nosso, melhor lance do
 * portal, posição, início/fim), para a equipe acompanhar pelo site e pelo "Pregão ao vivo".
 */
@Serializable
data class RoboEventoRequest(
    val tipo: String,
    val valor: Double? = null,
    val ator: String,
    val descricao: String,
    val item: String? = null,
    /** Número do item ("Item 1" → 1): atualiza o quadro do item no "Pregão ao vivo". */
    val itemNumero: Int? = null,
    /** Nossa posição no item no momento (1 = vencendo). */
    val posicao: Int? = null,
    /** Momento do evento no aparelho (epoch ms). */
    val momento: Long,
    /** Id local do evento (evita duplicar no servidor se o envio for repetido). */
    val idLocal: String,
    val origem: String = "aparelho",
)

/** `POST /licitacoes/:id/analise-local` → grava o resultado da análise feita no aparelho (não usa IA no servidor). */
@Serializable
data class AnaliseLocalRequest(
    val veredito: String? = null,
    val scoreRelevancia: Int? = null,
    val scoreRisco: Int? = null,
    val editalResumoIA: String? = null,
    val precoSugeridoIA: String? = null,
    val margemEstimadaIA: String? = null,
)

/** `POST /propostas` → grava metadados da proposta (conteúdo completo fica no aparelho por ora). */
@Serializable
data class PropostaCreateRequest(
    val licitacaoId: String,
    val valorTotal: Double,
    val status: String = "gerada_ia",
    val geradaPorIA: Boolean = true,
)

/** `GET /propostas?licitacaoId=` → propostas da licitação (valorTotal vem como string). */
@Serializable
data class PropostaDto(
    val id: String = "",
    val valorTotal: String? = null,
    val status: String? = null,
    val geradaPorIA: Boolean = false,
    val createdAt: String? = null,
)

@Serializable
data class PropostaPageDto(val data: List<PropostaDto> = emptyList(), val total: Int = 0)

/** `PUT /propostas/:id` {valorTotal?, status?}. */
@Serializable
data class PropostaUpdateRequest(val valorTotal: Double? = null, val status: String? = null)

/** `POST/PUT /radar/filtros[/:id]` → mesmos campos do GET (segmento não existe na VPS). */
@Serializable
data class RadarUpsertRequest(
    val nome: String,
    val palavrasChave: List<String> = emptyList(),
    val portais: List<String> = emptyList(),
    val estados: List<String> = emptyList(),
    val valorMinimo: Double? = null,
    val valorMaximo: Double? = null,
    val ativo: Boolean = true,
)

/** Item da licitação (shape variável no backend; extraído de forma tolerante). */
data class PlatformItem(
    val numero: Int? = null,
    val descricao: String? = null,
    val quantidade: String? = null,
    val unidade: String? = null,
    val valor: String? = null,
    /** Piso (lance mínimo) já salvo para este item, quando houver (string decimal). */
    val valorLanceMinimo: String? = null,
    /** Preço OFERTADO pela empresa (unitário) salvo na plataforma — usado pelo robô de proposta. */
    val valorProposto: String? = null,
    /** Marca/fabricante informada para o item (vai para o cadastro de proposta). */
    val marca: String? = null,
    val modelo: String? = null,
)

/** Arquivo/anexo da licitação (shape variável no backend; extraído de forma tolerante). */
data class PlatformFile(
    val nome: String? = null,
    val tipo: String? = null,
    val url: String? = null,
)
