package com.licitaia.feature.live.automation

import com.licitaia.feature.live.automation.LocatorKind.ARIA_LABEL
import com.licitaia.feature.live.automation.LocatorKind.LABEL
import com.licitaia.feature.live.automation.LocatorKind.NAME
import com.licitaia.feature.live.automation.LocatorKind.PLACEHOLDER
import com.licitaia.feature.live.automation.LocatorKind.TEXT

/**
 * Catálogo de alvos do Compras.gov.br ("Compras eletrônicas", SPA Angular + PrimeNG no cnetmobile).
 *
 * Lista de compras e cadastro de proposta: seletores REAIS mapeados via DevTools no aparelho logado (CSS primeiro,
 * texto como reserva). O fluxo do robô de proposta usa [SpaScripts]/[SpaNavigator] (abas pelo TEXTO, cartões
 * `div.cp-itens-card`, campo `input[data-test="input-valor-proposta"]`); os alvos abaixo servem ao motor genérico
 * ([PortalAutomationRunner]) e ao mapa aprendido.
 *
 * Sala de disputa e chat: AINDA HEURÍSTICA (não mapeadas) — candidatos por texto/rótulo.
 */
object PortalTargets {
    private fun t(v: String, exact: Boolean = false) = Locator(TEXT, v, exact)
    private fun l(v: String) = Locator(LABEL, v)
    private fun p(v: String) = Locator(PLACEHOLDER, v)
    private fun a(v: String) = Locator(ARIA_LABEL, v)
    private fun n(v: String) = Locator(NAME, v)
    private fun css(v: String) = Locator(LocatorKind.CSS, v)

    // ------------------------------------------------------------ lista de compras / busca (REAL)

    /** Abas PrimeNG (`p-tab[role=tab]`, ids `pn_id_*` gerados → pelo texto). */
    val tabMyParticipations = Target("compras.aba.minhas", "aba Minhas participações", ElementKind.CLICKABLE, listOf(t("minhas participacoes", true)), timeoutMs = 8_000)
    val tabAllPurchases = Target("compras.aba.todas", "aba Todas as compras", ElementKind.CLICKABLE, listOf(t("todas as compras", true)), timeoutMs = 8_000)

    val searchUasgField = Target(
        "compras.busca.uasg", "campo Unidade compradora", ElementKind.INPUT,
        listOf(css("#unidadeCompradora"), l("unidade compradora")), timeoutMs = 4_000,
    )
    /** Número + ano sem barra (3/2026 → "32026"), ver [CompraCode.searchNumber]. */
    val searchNumberField = Target(
        "compras.busca.numero", "campo Número da compra", ElementKind.INPUT,
        listOf(css("input[placeholder=\"Ex: 102021\"]"), p("ex: 102021"), l("numero da compra")), timeoutMs = 4_000,
    )
    val searchField = searchNumberField
    val searchButton = Target(
        "compras.busca.botao", "botão Pesquisar", ElementKind.CLICKABLE,
        listOf(t("pesquisar", true)),
    )

    /** Botão do cartão da compra (escopo = texto "N° n/aaaa" do cartão). Clique JS validado no aparelho. */
    fun purchaseOpen(numberYear: String) = Target(
        "compras.abrir", "Acompanhar compra $numberYear", ElementKind.CLICKABLE,
        listOf(css("button[aria-label=\"Participar/acompanhar compra\"]"), css("button[aria-label=\"Acompanhar compra\"]"), a("participar/acompanhar compra"), a("acompanhar compra")),
        scopeText = numberYear, critical = true,
    )

    // ------------------------------------------------------------ proposta (REAL)

    /** A compra abre direto em `cadastro-propostas?compra=...`; não há botão intermediário. */
    val proposalEntry = Target(
        "proposta.abrir", "Cadastrar propostas", ElementKind.ANY,
        listOf(t("cadastrar propostas", true)), timeoutMs = 8_000,
    )

    /** Escopo de linha da SALA DE DISPUTA (heurística: "Item N"). */
    fun itemRowScope(itemNumber: Int) = "item $itemNumber"

    fun itemOpen(itemNumber: Int) = Target(
        "proposta.item.abrir", "seta do item $itemNumber", ElementKind.CLICKABLE,
        listOf(css("div.cp-itens-card button[class*=\"animationRotate\"]")),
        scopeText = "$itemNumber ", timeoutMs = 8_000,
    )

    val unitPrice = Target(
        "proposta.item.valorUnitario", "valor unitário", ElementKind.INPUT,
        listOf(css("input[data-test=\"input-valor-proposta\"]"), l("valor unitario (r$)"), l("valor unitario")), critical = true,
    )
    val totalPrice = Target(
        "proposta.item.valorTotal", "valor total", ElementKind.INPUT,
        listOf(l("valor total"), p("valor total"), n("valortotal")), timeoutMs = 2_000, critical = true,
    )
    val quantity = Target(
        "proposta.item.quantidade", "quantidade ofertada", ElementKind.INPUT,
        listOf(l("quantidade ofertada"), l("quantidade"), p("quantidade"), n("quantidade")), timeoutMs = 3_000, critical = true,
    )
    val brand = Target("proposta.item.marca", "marca", ElementKind.INPUT, listOf(l("marca"), p("marca"), n("marca")), timeoutMs = 3_000, critical = true)
    val manufacturer = Target("proposta.item.fabricante", "fabricante", ElementKind.INPUT, listOf(l("fabricante"), p("fabricante"), n("fabricante")), timeoutMs = 3_000, critical = true)
    val modelVersion = Target(
        "proposta.item.modelo", "modelo/versão", ElementKind.INPUT,
        listOf(l("modelo / versao"), l("modelo/versao"), l("modelo"), p("modelo"), n("modelo"), l("versao")), timeoutMs = 3_000, critical = true,
    )
    val detailedDescription = Target(
        "proposta.item.descricao", "descrição detalhada", ElementKind.INPUT,
        listOf(l("descricao detalhada"), l("descricao do objeto"), l("descricao"), p("descricao"), n("descricaodetalhada"), n("descricao")), timeoutMs = 3_000, critical = true,
    )
    /** Só o botão com texto exato "Salvar" do formulário do item (nunca "Desfazer alterações" nem a lixeira). */
    val saveItem = Target(
        "proposta.item.salvar", "botão Salvar do item", ElementKind.CLICKABLE,
        listOf(t("salvar", true)), critical = true,
    )
    val saveConfirmation = Target(
        "proposta.item.salvo", "aviso “Operação realizada com sucesso!”", ElementKind.ANY,
        listOf(css(".p-toast-message.p-toast-message-success"), t("operacao realizada com sucesso")),
        timeoutMs = 8_000,
    )
    /**
     * Termo/declarações (LEGAIS): o robô NUNCA marca; só verifica ([SpaScripts.declarations]). Ids reais dos rádios:
     * `#labelSimMeepp`/`#labelNaoMeepp`, `#declaracaoEquidadeGeneroSim`/`Nao`, `#declaracaoProgramasIntegridadeSim`/`Nao`.
     */
    val declarationCheckbox = Target(
        "proposta.declaracao", "termo/declarações", ElementKind.CHECKBOX,
        listOf(css("#labelSimMeepp"), css("#labelNaoMeepp"), css("#declaracaoEquidadeGeneroSim"), css("#declaracaoProgramasIntegridadeSim")), timeoutMs = 1_500,
    )

    // ------------------------------------------------------------ sala de disputa (HEURÍSTICA, não mapeada)

    val disputeRoomEntry = Target(
        "disputa.abrir", "Sala de disputa", ElementKind.CLICKABLE,
        listOf(t("sala de disputa"), t("acompanhar disputa"), t("disputa"), t("lances"), t("acompanhar"), a("disputa")),
    )

    fun bidInput(itemNumber: Int) = Target(
        "disputa.lance.valor", "valor do lance do item $itemNumber", ElementKind.INPUT,
        listOf(l("valor do lance"), p("valor do lance"), l("lance"), p("lance"), l("valor"), p("informe o valor"), n("valorlance"), n("lance")),
        scopeText = itemRowScope(itemNumber), timeoutMs = 5_000, critical = true,
    )

    fun bidSend(itemNumber: Int) = Target(
        "disputa.lance.enviar", "Enviar lance do item $itemNumber", ElementKind.CLICKABLE,
        listOf(t("enviar lance"), t("dar lance"), t("registrar lance"), t("enviar", true), t("ofertar", true), a("enviar lance")),
        scopeText = itemRowScope(itemNumber), timeoutMs = 5_000, critical = true,
    )

    /** Confirmação do próprio portal após "Enviar" (diálogo). Só usada após o envio armado/confirmado pelo usuário. */
    val bidConfirm = Target(
        "disputa.lance.confirmar", "confirmação do lance", ElementKind.CLICKABLE,
        listOf(t("confirmar", true), t("sim", true), t("ok", true), t("confirmar lance")), timeoutMs = 3_000, critical = true,
    )

    val chatPanel = Target(
        "disputa.chat", "mensagens do pregoeiro", ElementKind.ANY,
        listOf(t("mensagens"), t("chat"), a("mensagens"), t("mensagens do pregoeiro")), timeoutMs = 1_500,
    )
}
