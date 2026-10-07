package com.licitaia.connector.comprasgov

import kotlinx.serialization.Serializable

/*
 * DTOs da API de Dados Abertos do Compras.gov.br.
 * Fonte: OpenAPI oficial https://dadosabertos.compras.gov.br/v3/api-docs (schemas
 * GenericAPIResponseDTO*, VwFtPNCPCompraDTO, VwFtPNCPCompraItemDTO, TbVwLicitacaoDTO,
 * TbVwItemLicitacaoDTO), confirmados com respostas reais capturadas em 06/10/2026
 * (ver os arquivos .json em src/test/resources/comprasgov).
 *
 * Só os campos usados pelo app são declarados (DTOs mínimos: menos alocação ao decodificar páginas de 500 linhas);
 * os demais são ignorados (ignoreUnknownKeys).
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
    val orgaoEntidadeRazaoSocial: String? = null,
    val unidadeOrgaoCodigoUnidade: String? = null,
    val unidadeOrgaoNomeUnidade: String? = null,
    val unidadeOrgaoUfSigla: String? = null,
    val unidadeOrgaoMunicipioNome: String? = null,
    val numeroCompra: String? = null,
    val modalidadeIdPncp: Int? = null,
    val codigoModalidade: Int? = null,
    val modalidadeNome: String? = null,
    val srp: Boolean? = null,
    val modoDisputaNomePncp: String? = null,
    /** 5 = "Não se aplica" (dispensa sem disputa), 4 = "Dispensa Com Disputa" (respostas reais de 06/10/2026). */
    val modoDisputaIdPncp: Int? = null,
    /** 3 = "Ato que autoriza a Contratação Direta", 2 = "Aviso de Contratação Direta". */
    val tipoInstrumentoConvocatorioCodigoPncp: Int? = null,
    val amparoLegalNome: String? = null,
    val informacaoComplementar: String? = null,
    val processo: String? = null,
    val objetoCompra: String? = null,
    val situacaoCompraNomePncp: String? = null,
    val tipoInstrumentoConvocatorioNome: String? = null,
    val valorTotalEstimado: Double? = null,
    val dataInclusaoPncp: String? = null,
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
    // Detalhe do item (tela de detalhe da aba Itens) — campos reais observados em 07/10/2026.
    val itemCategoriaNome: String? = null,
    val tipoBeneficioNome: String? = null,
    val incentivoProdutivoBasico: Boolean? = null,
    val codItemCatalogo: String? = null,
    val codigoPdm: String? = null,
    val nomePdm: String? = null,
    val codigoNCM: String? = null,
    val descricaoNCM: String? = null,
    val margemPreferenciaNormal: Boolean? = null,
    val percentualMargemPreferenciaNormal: Double? = null,
    val margemPreferenciaAdicional: Boolean? = null,
    val percentualMargemPreferenciaAdicional: Double? = null,
    val temResultado: Boolean? = null,
    val nomeFornecedor: String? = null,
    val dataInclusaoPncp: String? = null,
    val dataAtualizacaoPncp: String? = null,
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

/**
 * Detalhe de uma contratação no PNCP (`/api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}`), só os campos do
 * enriquecimento de prazo. Verificado em 06/10/2026: `situacaoCompraId` inteiro (1 Divulgada no PNCP, 2 Revogada,
 * 3 Anulada, 4 Suspensa); datas sem fuso ("2026-10-14T09:00:00"); dispensas sem disputa vêm com as duas datas nulas.
 */
@Serializable
internal data class PncpCompraStatus(
    val numeroControlePNCP: String? = null,
    val dataAberturaProposta: String? = null,
    val dataEncerramentoProposta: String? = null,
    val situacaoCompraId: Int? = null,
    val situacaoCompraNome: String? = null,
)
