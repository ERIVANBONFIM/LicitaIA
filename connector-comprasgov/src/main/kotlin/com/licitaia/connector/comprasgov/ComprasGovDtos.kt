package com.licitaia.connector.comprasgov

import kotlinx.serialization.Serializable

/*
 * DTOs da API de Dados Abertos do Compras.gov.br.
 * Fonte: OpenAPI oficial https://dadosabertos.compras.gov.br/v3/api-docs (schemas
 * GenericAPIResponseDTO*, VwFtPNCPCompraDTO, VwFtPNCPCompraItemDTO, TbVwLicitacaoDTO,
 * TbVwItemLicitacaoDTO), confirmados com respostas reais capturadas em 06/10/2026
 * (ver os arquivos .json em src/test/resources/comprasgov).
 *
 * Só os campos usados pelo app são declarados; os demais são ignorados (ignoreUnknownKeys).
 * Todos são opcionais porque a API devolve `null` com frequência.
 */

/** Envelope padrão de todas as consultas paginadas (GenericAPIResponseDTO). */
@Serializable
internal data class ComprasGovPage<T>(
    val resultado: List<T> = emptyList(),
    val totalRegistros: Long = 0,
    val totalPaginas: Long = 0,
    val paginasRestantes: Long = 0,
)

/**
 * Contratação da Lei 14.133 publicada no PNCP via Compras.gov.br (VwFtPNCPCompraDTO).
 * `codigoModalidade` é o código do Compras.gov.br; `modalidadeIdPncp` é o código do PNCP (diferentes!).
 */
@Serializable
internal data class ComprasGovContratacao(
    val idCompra: String? = null,
    val numeroControlePNCP: String? = null,
    val anoCompraPncp: Int? = null,
    val sequencialCompraPncp: Int? = null,
    val orgaoEntidadeCnpj: String? = null,
    val codigoOrgao: Int? = null,
    val orgaoEntidadeRazaoSocial: String? = null,
    val orgaoEntidadeEsferaId: String? = null,
    val orgaoEntidadePoderId: String? = null,
    val unidadeOrgaoCodigoUnidade: String? = null,
    val unidadeOrgaoNomeUnidade: String? = null,
    val unidadeOrgaoUfSigla: String? = null,
    val unidadeOrgaoMunicipioNome: String? = null,
    val unidadeOrgaoCodigoIbge: Int? = null,
    val numeroCompra: String? = null,
    val modalidadeIdPncp: Int? = null,
    val codigoModalidade: Int? = null,
    val modalidadeNome: String? = null,
    val srp: Boolean? = null,
    val modoDisputaNomePncp: String? = null,
    val amparoLegalNome: String? = null,
    val informacaoComplementar: String? = null,
    val processo: String? = null,
    val objetoCompra: String? = null,
    val existeResultado: Boolean? = null,
    val orcamentoSigilosoDescricao: String? = null,
    val situacaoCompraNomePncp: String? = null,
    val tipoInstrumentoConvocatorioNome: String? = null,
    val valorTotalEstimado: Double? = null,
    val valorTotalHomologado: Double? = null,
    val dataInclusaoPncp: String? = null,
    val dataAtualizacaoPncp: String? = null,
    val dataPublicacaoPncp: String? = null,
    val dataAberturaPropostaPncp: String? = null,
    val dataEncerramentoPropostaPncp: String? = null,
    val contratacaoExcluida: Boolean? = null,
)

/** Item de contratação 14.133 (VwFtPNCPCompraItemDTO). */
@Serializable
internal data class ComprasGovItem(
    val idCompraItem: String? = null,
    val numeroItemCompra: Int? = null,
    val numeroItemPncp: Int? = null,
    val descricaoResumida: String? = null,
    val descricaodetalhada: String? = null,
    val materialOuServicoNome: String? = null,
    val unidadeMedida: String? = null,
    val orcamentoSigiloso: Boolean? = null,
    val criterioJulgamentoNome: String? = null,
    val situacaoCompraItemNome: String? = null,
    val quantidade: Double? = null,
    val valorUnitarioEstimado: Double? = null,
    val valorTotal: Double? = null,
    val numeroControlePNCPCompra: String? = null,
)

/**
 * Licitação do módulo legado (Lei 8.666 — SIASG/Comprasnet; TbVwLicitacaoDTO).
 * Não traz UF nem nome do órgão: só `uasg` e `codigo_municipio_uasg` (código SIASG, não IBGE).
 * `id_compra` = UASG(6) + modalidade(2) + número(5) + ano(4), observado nas respostas reais.
 */
@Serializable
internal data class ComprasGovLicitacaoLegado(
    val id_compra: String? = null,
    val identificador: String? = null,
    val numero_processo: String? = null,
    val uasg: Int? = null,
    val modalidade: Int? = null,
    val nome_modalidade: String? = null,
    val numero_aviso: Int? = null,
    val situacao_aviso: String? = null,
    val tipo_pregao: String? = null,
    val tipo_recurso: String? = null,
    val numero_itens: Int? = null,
    val valor_estimado_total: Double? = null,
    val valor_homologado_total: Double? = null,
    val informacoes_gerais: String? = null,
    val objeto: String? = null,
    val endereco_entrega_edital: String? = null,
    val codigo_municipio_uasg: Int? = null,
    val data_abertura_proposta: String? = null,
    val data_entrega_edital: String? = null,
    val data_entrega_proposta: String? = null,
    val data_publicacao: String? = null,
    val dt_alteracao: String? = null,
    val pertence14133: Boolean? = null,
)

/** Item de licitação legada (TbVwItemLicitacaoDTO). */
@Serializable
internal data class ComprasGovItemLegado(
    val numero_item_licitacao: Int? = null,
    val nome_uasg: String? = null,
    val nome_material: String? = null,
    val nome_servico: String? = null,
    val quantidade: Double? = null,
    val unidade: String? = null,
    val descricao_item: String? = null,
    val valor_estimado: Double? = null,
    val criterio_julgamento: String? = null,
    val id_compra: String? = null,
    val id_compra_item: String? = null,
)
