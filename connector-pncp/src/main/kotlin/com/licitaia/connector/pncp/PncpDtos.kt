package com.licitaia.connector.pncp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTOs da API pública de consulta do PNCP.
 * Fonte: OpenAPI oficial https://pncp.gov.br/api/consulta/v3/api-docs (schemas
 * PaginaRetornoRecuperarCompraPublicacaoDTO, RecuperarCompraPublicacaoDTO, RecuperarCompraDTO,
 * RecuperarOrgaoEntidadeDTO, RecuperarUnidadeOrgaoDTO) e https://pncp.gov.br/api/pncp/v3/api-docs
 * (RecuperarDadosDocumentoCompraDTO, RecuperarCompraItemSigiloDTO).
 *
 * Só os campos usados pelo app são declarados; os demais são ignorados (ignoreUnknownKeys).
 * Todos são opcionais porque a API devolve `null` com frequência (ex.: valorTotalHomologado).
 * Campos cujo tipo real diverge do schema (ex.: situacaoCompraId chega como inteiro) foram omitidos.
 */

@Serializable
internal data class PncpPage<T>(
    val data: List<T> = emptyList(),
    val totalRegistros: Long = 0,
    val totalPaginas: Long = 0,
    val numeroPagina: Long = 0,
    val paginasRestantes: Long = 0,
    val empty: Boolean = false,
)

@Serializable
internal data class PncpOrgao(
    val cnpj: String? = null,
    val razaoSocial: String? = null,
    val poderId: String? = null,
    val esferaId: String? = null,
)

@Serializable
internal data class PncpUnidade(
    val ufNome: String? = null,
    val ufSigla: String? = null,
    val municipioNome: String? = null,
    val codigoUnidade: String? = null,
    val nomeUnidade: String? = null,
    val codigoIbge: String? = null,
)

@Serializable
internal data class PncpAmparoLegal(
    val codigo: Long? = null,
    val nome: String? = null,
    val descricao: String? = null,
)

/** Contratação (RecuperarCompraPublicacaoDTO / RecuperarCompraDTO — mesmos campos relevantes). */
@Serializable
internal data class PncpContratacao(
    val numeroControlePNCP: String? = null,
    val numeroCompra: String? = null,
    val anoCompra: Int? = null,
    val sequencialCompra: Int? = null,
    val processo: String? = null,
    val objetoCompra: String? = null,
    val informacaoComplementar: String? = null,
    val orgaoEntidade: PncpOrgao? = null,
    val unidadeOrgao: PncpUnidade? = null,
    val orgaoSubRogado: PncpOrgao? = null,
    val unidadeSubRogada: PncpUnidade? = null,
    val amparoLegal: PncpAmparoLegal? = null,
    val modalidadeId: Long? = null,
    val modalidadeNome: String? = null,
    val modoDisputaId: Long? = null,
    val modoDisputaNome: String? = null,
    val tipoInstrumentoConvocatorioCodigo: Long? = null,
    val tipoInstrumentoConvocatorioNome: String? = null,
    val srp: Boolean? = null,
    val valorTotalEstimado: Double? = null,
    val valorTotalHomologado: Double? = null,
    val dataPublicacaoPncp: String? = null,
    val dataAberturaProposta: String? = null,
    val dataEncerramentoProposta: String? = null,
    val dataInclusao: String? = null,
    val dataAtualizacao: String? = null,
    val dataAtualizacaoGlobal: String? = null,
    val situacaoCompraNome: String? = null,
    val linkSistemaOrigem: String? = null,
    val linkProcessoEletronico: String? = null,
    val usuarioNome: String? = null,
)

/** Documento público de uma contratação (RecuperarDadosDocumentoCompraDTO). */
@Serializable
internal data class PncpDocumento(
    val uri: String? = null,
    val url: String? = null,
    val sequencialDocumento: Int? = null,
    val statusAtivo: Boolean? = null,
    val dataPublicacaoPncp: String? = null,
    val titulo: String? = null,
    val tipoDocumentoId: Long? = null,
    val tipoDocumentoNome: String? = null,
    val tipoDocumentoDescricao: String? = null,
)

/** Item de uma contratação (RecuperarCompraItemSigiloDTO). */
@Serializable
internal data class PncpItem(
    val numeroItem: Int? = null,
    val descricao: String? = null,
    val materialOuServicoNome: String? = null,
    val quantidade: Double? = null,
    val unidadeMedida: String? = null,
    val valorUnitarioEstimado: Double? = null,
    val valorTotal: Double? = null,
    val orcamentoSigiloso: Boolean? = null,
    val criterioJulgamentoNome: String? = null,
    val situacaoCompraItemNome: String? = null,
    val tipoBeneficioNome: String? = null,
    /** O item já tem resultado (fornecedor homologado) publicado. */
    val temResultado: Boolean? = null,
    // Detalhe do item (tela de detalhe da aba Itens) — campos reais de `/itens` (07/10/2026). Tipos incertos (catálogo,
    // margem) chegam como JsonElement para nunca quebrar a decodificação da lista.
    val informacaoComplementar: String? = null,
    val itemCategoriaNome: String? = null,
    val incentivoProdutivoBasico: Boolean? = null,
    val exigenciaConteudoNacional: Boolean? = null,
    val dataInclusao: String? = null,
    val dataAtualizacao: String? = null,
    val aplicabilidadeMargemPreferenciaNormal: Boolean? = null,
    val aplicabilidadeMargemPreferenciaAdicional: Boolean? = null,
    val percentualMargemPreferenciaNormal: Double? = null,
    val percentualMargemPreferenciaAdicional: Double? = null,
    val tipoMargemPreferencia: JsonElement? = null,
    val ncmNbsCodigo: JsonElement? = null,
    val ncmNbsDescricao: String? = null,
    val catalogo: JsonElement? = null,
    val categoriaItemCatalogo: JsonElement? = null,
    val catalogoCodigoItem: JsonElement? = null,
)

/**
 * Resultado de um item (RecuperarCompraItemResultadoDTO da API de integração do PNCP,
 * `GET /v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens/{numeroItem}/resultados`).
 */
@Serializable
internal data class PncpItemResultado(
    val numeroItem: Int? = null,
    val sequencialResultado: Int? = null,
    val niFornecedor: String? = null,
    val tipoPessoa: String? = null,
    val nomeRazaoSocialFornecedor: String? = null,
    val porteFornecedorNome: String? = null,
    val quantidadeHomologada: Double? = null,
    val valorUnitarioHomologado: Double? = null,
    val valorTotalHomologado: Double? = null,
    val percentualDesconto: Double? = null,
    val situacaoCompraItemResultadoNome: String? = null,
    val dataResultado: String? = null,
    val dataInclusao: String? = null,
    val dataCancelamento: String? = null,
    val motivoCancelamento: String? = null,
    val numeroControlePNCPCompra: String? = null,
)
