package com.licitaia.feature.live.automation

import com.licitaia.domain.bidding.BidRuleEngine
import com.licitaia.domain.model.BidRule
import com.licitaia.domain.model.PortalDeclarations
import com.licitaia.domain.portal.ProposalAuthorization
import com.licitaia.domain.model.RobotMode
import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.ProposalItemPlan
import com.licitaia.domain.util.Formatters

/** Validação e textos de confirmação dos robôs (puros, testáveis). */
object RobotPlanRules {

    /**
     * Antes de "Soltar robô — cadastrar proposta". Lista vazia = pode seguir. Números valem para todos os itens; preço
     * e quantidade só para os itens SELECIONADOS (participação).
     */
    fun proposalErrors(items: List<ProposalItemPlan>): List<String> = buildList {
        if (items.isEmpty()) add("Inclua pelo menos um item.")
        else if (items.none { it.selected }) add("Selecione pelo menos um item para participar.")
        val dup = items.groupBy { it.itemNumber }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) add("Item repetido: ${dup.joinToString()}.")
        items.forEach { i ->
            if (i.itemNumber <= 0) add("Número de item inválido (${i.itemNumber}).")
            if (!i.selected) return@forEach
            if (!(i.unitPrice > 0.0) || i.unitPrice.isInfinite()) add("Item ${i.itemNumber}: informe o valor unitário.")
            if (!(i.quantity > 0.0) || i.quantity.isInfinite()) add("Item ${i.itemNumber}: informe a quantidade ofertada.")
            i.floorUnitPrice?.let { f -> if (f > i.unitPrice + 1e-9) add("Item ${i.itemNumber}: o piso (${Formatters.brl(f)}) não pode ser maior que o valor da proposta.") }
        }
    }

    /** Resumo da participação: selecionados, total ofertado dos selecionados e itens sem preço. */
    data class Selection(val total: Int, val selected: Int, val selectedTotal: Double, val withoutPrice: List<Int>) {
        val label: String get() = "$selected de $total itens selecionados"
    }

    fun selection(items: List<ProposalItemPlan>): Selection {
        val sel = items.filter { it.selected }
        return Selection(
            total = items.size, selected = sel.size,
            selectedTotal = sel.filter { it.hasPrice }.sumOf { it.totalPrice },
            withoutPrice = items.filter { !it.hasPrice }.map { it.itemNumber }.sorted(),
        )
    }

    /** Marcar/desmarcar todos: item sem preço nunca fica marcado. */
    fun selectAll(items: List<ProposalItemPlan>, selected: Boolean): List<ProposalItemPlan> =
        items.map { it.copy(selected = selected && it.hasPrice) }

    /**
     * Botão "Soltar o robô" da confirmação: plano válido, ≥1 item selecionado, autorização do Termo/declarações
     * marcada e as três declarações da empresa respondidas.
     */
    fun confirmationErrors(items: List<ProposalItemPlan>, acceptTerms: Boolean, declarations: PortalDeclarations): List<String> = buildList {
        addAll(proposalErrors(items))
        if (!acceptTerms) add("Marque “Autorizo o aceite do Termo de Aceitação e das declarações obrigatórias apresentadas pelo Compras.gov”.")
        if (!declarations.complete) add("Responda as declarações da empresa (ME/EPP, equidade de gênero e programa de integridade).")
    }

    /** Texto da autorização para a auditoria (quem, quando, licitação, respostas, itens). */
    fun authorizationAudit(t: PortalMyTender, auth: ProposalAuthorization, items: List<ProposalItemPlan>): String {
        val s = selection(items)
        return "Autorização do Termo de Aceitação e das declarações do Compras.gov por ${auth.authorizedBy} em " +
            "${Formatters.dateTime(auth.authorizedAt)} · ${t.label} · ${auth.declarations.summary()} · ${s.label} · total ${Formatters.brl(s.selectedTotal)}"
    }

    /** Antes de armar o robô de lance. */
    fun bidErrors(plan: PortalRobotPlan): List<String> = buildList {
        val c = plan.bid
        if (c.mode == BidRobotMode.DESLIGADO) add("Escolha o modo Manual ou Automático.")
        val armed = plan.items.filter { it.floorUnitPrice != null }
        if (armed.isEmpty()) add("Defina o piso de pelo menos um item (o robô nunca dá lance sem piso).")
        armed.forEach { i ->
            val f = i.floorUnitPrice!!
            if (!(f > 0.0)) add("Item ${i.itemNumber}: piso deve ser maior que zero.")
            if (f > i.unitPrice + 1e-9) add("Item ${i.itemNumber}: piso acima do valor da proposta.")
            if (!BidRuleEngine.isOperable(ruleFor(i, c))) add("Item ${i.itemNumber}: parâmetros de lance inválidos (valor, piso e redução).")
        }
        if (c.minDecrement < 0.01) add("Decremento mínimo deve ser de pelo menos R$ 0,01.")
        if (c.reductionValue <= 0.0) add("A redução por lance deve ser maior que zero.")
        if (c.maxBids < 1) add("Teto de lances deve ser de pelo menos 1.")
    }

    /** Regra do motor de estratégias para o item (unitário ou total, conforme o edital). */
    fun ruleFor(item: ProposalItemPlan, c: BidRobotConfig): BidRule {
        val k = if (c.bidOnTotal) item.quantity.coerceAtLeast(1.0) else 1.0
        val floor = (item.floorUnitPrice ?: 0.0) * k
        return BidRule(
            mode = RobotMode.AUTOMATICO_LIMITADO,
            strategy = c.strategy,
            initialPrice = item.unitPrice * k,
            floorPrice = floor,
            // Custo desconhecido aqui: o piso é a referência de margem (margem 0 no piso).
            costPrice = floor,
            reductionValue = c.reductionValue.coerceAtLeast(c.minDecrement).coerceAtLeast(0.01),
            minMarginPct = 0.0,
            lossLimit = 0.0,
            minIntervalSeconds = c.ownIntervalSeconds.coerceAtLeast(BidRobotConfig.MIN_OWN_INTERVAL_SECONDS),
            authorizationThresholdPct = 0.0,
            simulation = false,
        )
    }

    fun proposalConfirmation(t: PortalMyTender, items: List<ProposalItemPlan>): String = buildString {
        appendLine("Licitação: ${t.label}${t.modality.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}")
        if (t.objectDescription.isNotBlank()) appendLine(t.objectDescription.take(160))
        appendLine()
        items.filter { it.selected }.sortedBy { it.itemNumber }.forEach { i ->
            append("Item ${i.itemNumber}: ${Formatters.brl(i.unitPrice)} × ${TextNorm.formatInputNumber(i.quantity)} = ${Formatters.brl(i.totalPrice)}")
            i.floorUnitPrice?.let { append(" · piso ${Formatters.brl(it)}") }
            appendLine()
            listOf("Marca" to i.brand, "Fabricante" to i.manufacturer, "Modelo" to i.modelVersion).filter { it.second.isNotBlank() }
                .takeIf { it.isNotEmpty() }?.let { appendLine("   " + it.joinToString(" · ") { (k, v) -> "$k: $v" }) }
        }
        appendLine()
        appendLine("Total: ${Formatters.brl(selection(items).selectedTotal)} (${selection(items).label})")
        appendLine()
        append("O robô vai abrir Compras eletrônicas pelo menu, localizar a compra, conferir que é a compra certa, aceitar os termos (só com a sua autorização), ")
        append("abrir os grupos, preencher, SALVAR e reler cada item no portal.")
    }

    fun bidConfirmation(t: PortalMyTender, plan: PortalRobotPlan): String = buildString {
        val c = plan.bid
        appendLine("Licitação: ${t.label}")
        appendLine("Modo: ${c.mode.label} — ${c.mode.description}")
        appendLine("Estratégia: ${c.strategy.label} · redução ${Formatters.brl(c.reductionValue)} · decremento mínimo ${Formatters.brl(c.minDecrement)}")
        appendLine("Intervalos: ${c.ownIntervalSeconds.coerceAtLeast(20)} s entre nossos lances · ${c.afterBestSeconds.coerceAtLeast(3)} s após o melhor lance")
        appendLine("Teto: ${c.maxBids} lance(s) · lance por ${if (c.bidOnTotal) "VALOR TOTAL do item" else "VALOR UNITÁRIO"}")
        appendLine()
        plan.items.filter { it.floorUnitPrice != null }.sortedBy { it.itemNumber }.forEach { i ->
            val k = if (c.bidOnTotal) i.quantity else 1.0
            appendLine("Item ${i.itemNumber}: começa em ${Formatters.brl(i.unitPrice * k)} · PISO ${Formatters.brl(i.floorUnitPrice!! * k)}")
        }
        plan.sessionAt?.let { appendLine(); appendLine("Sessão: ${Formatters.dateTime(it)} (o app prepara a sessão ~30 min antes e avisa).") }
        appendLine()
        append("Nunca abaixo do piso. Para sozinho se o pregoeiro suspender/encerrar, se a sessão cair, se a leitura ficar ambígua; ")
        append("CAPTCHA/código pausam só esta sessão até você resolver no portal.")
    }

    /** Agenda: preparar (login + aviso) 30 min antes; entrar na sala 2 min antes. */
    fun prepareAt(sessionAt: Long): Long = sessionAt - 30 * 60_000L
    fun startAt(sessionAt: Long): Long = sessionAt - 2 * 60_000L
}
