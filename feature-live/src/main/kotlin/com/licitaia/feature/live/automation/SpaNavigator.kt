package com.licitaia.feature.live.automation

import kotlinx.coroutines.delay

/**
 * Passos nas telas REAIS do Compras.gov.br ([SpaScripts]) sobre um [PageDriver]. Cada função devolve null = ok ou o
 * motivo (PT-BR) de não ter conseguido — quem chama decide parar e pedir o usuário. Nada aqui marca declarações,
 * clica na lixeira, em favoritos ou em "Desfazer alterações".
 */
class SpaNavigator(
    private val driver: PageDriver,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    /** Compra alvo (da "minhas licitações"). */
    data class Purchase(val uasg: String, val modality: String, val number: String, val year: Int) {
        val numberYear: String get() = "$number/$year"
        /** UASG como o portal mostra/aceita na busca (sem zeros à esquerda). */
        val uasgShown: String get() = uasg.filter(Char::isDigit).trimStart('0').ifEmpty { "0" }
    }

    suspend fun state(): SpaScripts.State? = SpaScripts.parseState(driver.eval(SpaScripts.state()))

    private suspend fun reply(script: String): SpaScripts.Reply = SpaScripts.reply(driver.eval(script))

    private suspend fun waitState(timeoutMs: Long, pollMs: Long = 600, ok: (SpaScripts.State) -> Boolean): SpaScripts.State? {
        val deadline = clock() + timeoutMs
        while (true) {
            state()?.takeIf(ok)?.let { return it }
            if (clock() >= deadline) return null
            sleep(pollMs)
        }
    }

    /** Espera a lista de cartões parar de mudar (recarga depois de filtro/aba). */
    private suspend fun settle(timeoutMs: Long = 8_000): SpaScripts.State? {
        val deadline = clock() + timeoutMs
        var last: List<String>? = null
        var s: SpaScripts.State? = null
        while (clock() < deadline) {
            sleep(700)
            s = state() ?: continue
            val now = s.cards.map { it.text }
            if (now == last) return s
            last = now
        }
        return s
    }

    // ------------------------------------------------------------------ lista "Compras eletrônicas"

    /** Garante a lista (abas "Minhas participações"/"Todas as compras"); de outra tela do SPA usa "Tela inicial". */
    suspend fun toComprasList(): String? {
        if (waitState(15_000) { it.isComprasList } != null) return null
        if (reply(SpaScripts.clickAria("Tela inicial")).ok && waitState(15_000) { it.isComprasList } != null) return null
        return "a lista “Compras eletrônicas” (abas Minhas participações / Todas as compras) não apareceu"
    }

    suspend fun openTab(label: String): String? {
        if (state()?.tabActive(label) == true) return null
        repeat(3) {
            if (reply(SpaScripts.clickTab(label)).ok && waitState(6_000) { it.tabActive(label) } != null) {
                settle()
                return null
            }
            sleep(800)
        }
        return "a aba “$label” não abriu"
    }

    /** Filtro de "Minhas participações" ("Em andamento", "Propostas", ...). */
    suspend fun applyFilter(label: String): String? {
        repeat(3) {
            var r = reply(SpaScripts.selectFilter(label))
            if (r.flag("done")) return null
            if (r.flag("opened")) { sleep(700); r = reply(SpaScripts.selectFilter(label)) }
            if (r.flag("picked") || r.flag("done")) {
                if (waitState(8_000) { TextNorm.norm(it.filter) == TextNorm.norm(label) } != null) { settle(); return null }
            }
            sleep(900)
        }
        return "o filtro “$label” não foi aplicado"
    }

    /** Lê todas as páginas da lista atual (PrimeNG "Próxima Página"). second = aviso (ou null). */
    suspend fun readAllPages(maxPages: Int = 30): Pair<List<SpaCard>, String?> {
        val out = LinkedHashMap<String, SpaCard>()
        var s = settle() ?: return emptyList<SpaCard>() to "a lista não respondeu"
        var pages = 0
        while (true) {
            s.cards.forEach { c -> out.putIfAbsent(TextNorm.norm(c.text), c) }
            pages++
            if (!s.hasNext || pages >= maxPages) break
            val before = s.cards.firstOrNull()?.text
            val pageBefore = s.page
            if (!reply(SpaScripts.nextPage()).ok) break
            s = waitState(10_000) { it.page != pageBefore || it.cards.firstOrNull()?.text != before }
                ?: return out.values.toList() to "a paginação não avançou depois da página $pageBefore"
            s = settle(4_000) ?: s
        }
        return out.values.toList() to null
    }

    /** "Minhas participações" com cada filtro, todas as páginas. second = avisos. */
    suspend fun readMyParticipations(filters: List<String>): Pair<List<SpaCard>, List<String>> {
        val warnings = mutableListOf<String>()
        toComprasList()?.let { return emptyList<SpaCard>() to listOf(it) }
        openTab("Minhas participações")?.let { return emptyList<SpaCard>() to listOf(it) }
        val all = LinkedHashMap<String, SpaCard>()
        for (f in filters) {
            val fail = applyFilter(f)
            if (fail != null) { warnings += fail; continue }
            val (cards, warn) = readAllPages()
            warn?.let { warnings += "“$f”: $it" }
            log("Minhas participações · $f: ${cards.size} compra(s)")
            cards.forEach { c ->
                val k = TextNorm.norm(c.text)
                all[k] = all[k]?.let { it.copy(favorite = it.favorite || c.favorite) } ?: c
            }
        }
        return all.values.toList() to warnings
    }

    // ------------------------------------------------------------------ abrir a compra

    private suspend fun clickPurchase(p: Purchase): String? {
        val r = reply(SpaScripts.purchase(p.uasg, p.number, p.year, click = true))
        if (!r.found) return "compra não encontrada na lista"
        if (!r.ok) return if (r.error == "ambiguous") "mais de uma compra igual na lista" else r.error ?: "não consegui clicar em Acompanhar compra"
        return null
    }

    private suspend fun waitProposalPage(p: Purchase, timeoutMs: Long = 25_000): Boolean {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            if (CompraCode.isProposalPageFor(driver.currentUrl(), p.uasg, p.modality, p.number, p.year)) return true
            sleep(700)
        }
        return false
    }

    /** Digita (nativo) num campo da busca e confere o valor; se a máscara recusar, tenta `insertText`. */
    private suspend fun typeSearch(which: String, value: String, label: String): String? {
        var f = reply(SpaScripts.focusSearch(which))
        if (!f.found && f.flag("toggled")) { sleep(900); f = reply(SpaScripts.focusSearch(which)) }
        if (!f.found) return "campo “$label” não encontrado"
        fun same(v: String?) = v.orEmpty().filter(Char::isDigit).trimStart('0') == value.trimStart('0')
        if (same(f.value)) return null
        if (!driver.typeKeys(value, clearFirst = true)) return "não consegui digitar em “$label” (${PortalSessionGate.OPEN_APP_MESSAGE})"
        sleep(400)
        var v = reply(SpaScripts.readSearch(which)).value
        if (same(v)) return null
        log("“$label”: a digitação deixou “${v.orEmpty()}”; tentando de novo por inserção de texto.")
        reply(SpaScripts.focusSearch(which))
        driver.insertText(value)
        sleep(400)
        v = reply(SpaScripts.readSearch(which)).value
        return if (same(v)) null else "o campo “$label” ficou com “${v.orEmpty()}” em vez de “$value”"
    }

    private suspend fun waitPurchaseCard(p: Purchase, timeoutMs: Long): Boolean {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            sleep(800)
            if (reply(SpaScripts.purchase(p.uasg, p.number, p.year, click = false)).found) return true
        }
        return false
    }

    /**
     * Abre o cadastro de proposta da compra: já aberto → ok; senão lista → "Minhas participações" (página atual) →
     * "Todas as compras" com UASG + número digitados → Pesquisar (clique; senão Enter; senão toque) → "Acompanhar
     * compra" → confere a URL `cadastro-propostas?compra=<código esperado>`.
     */
    suspend fun openPurchase(p: Purchase): String? {
        if (CompraCode.isProposalPageFor(driver.currentUrl(), p.uasg, p.modality, p.number, p.year)) return null
        toComprasList()?.let { return it }
        if (clickPurchase(p) == null) {
            log("Compra achada na lista atual; abrindo.")
            if (waitProposalPage(p)) return null
        }
        openTab("Todas as compras")?.let { return it }
        typeSearch("uasg", p.uasgShown, "Unidade compradora")?.let { return it }
        typeSearch("numero", CompraCode.searchNumber(p.number, p.year), "Número da compra")?.let { return it }
        var shown = false
        if (reply(SpaScripts.searchButton(tap = false)).ok) shown = waitPurchaseCard(p, 8_000)
        if (!shown) {
            log("Pesquisar (clique) sem resultado; tentando Enter no número da compra.")
            reply(SpaScripts.focusSearch("numero"))
            if (driver.pressEnter()) shown = waitPurchaseCard(p, 8_000)
        }
        if (!shown) {
            log("Enter sem resultado; tentando toque em Pesquisar.")
            val r = reply(SpaScripts.searchButton(tap = true))
            val rect = r.obj("rect")
            val x = (rect?.get("x") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
            val y = (rect?.get("y") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
            if (x != null && y != null && driver.tapAt(x, y)) shown = waitPurchaseCard(p, 10_000)
        }
        if (!shown) return "a pesquisa por UASG ${p.uasgShown} e ${p.numberYear} não mostrou a compra"
        clickPurchase(p)?.let { return it }
        if (!waitProposalPage(p)) return "o cadastro de proposta da compra ${p.numberYear} não abriu (URL ${CompraCode.fromUrl(driver.currentUrl()) ?: "sem compra="})"
        return null
    }

    // ------------------------------------------------------------------ cadastro de proposta

    suspend fun declarations(): DeclarationsState? {
        repeat(3) {
            DeclarationsCheck.parse(driver.eval(SpaScripts.declarations()))?.let { return it }
            sleep(1_000)
        }
        return null
    }

    suspend fun itemCard(n: Int): ProposalItemCard? {
        val o = AutomationJson.obj(driver.eval(SpaScripts.itemStatus(n))) ?: return null
        val text = with(AutomationJson) { o.str("t") }.orEmpty()
        return if (text.isBlank()) null else ProposalItemCardParser.parse(text, n)
    }

    /** Abre o formulário do item [n] e marca o campo de valor dele (o que APARECE ao tocar a seta do item). */
    suspend fun openItemForm(n: Int): String? {
        val tok = "lz" + clock()
        val prep = reply(SpaScripts.prepareItem(n, tok))
        if (!prep.found) return "item $n não encontrado na página"
        if (!prep.ok) return prep.error ?: "item $n ambíguo"
        if (prep.flag("ready")) return null
        val open = prep.int("open") ?: 0
        var toggles = 0
        if (!reply(SpaScripts.toggleItem(n)).ok) return "seta do item $n não encontrada"
        toggles++
        val deadline = clock() + 10_000
        while (clock() < deadline) {
            sleep(500)
            val c = reply(SpaScripts.claimItemForm(n, tok))
            if (c.ok) return null
            val fresh = c.int("fresh") ?: 0
            val seen = c.int("seen") ?: 0
            // O formulário do item já estava aberto e a seta o FECHOU: abre de novo (aparece como novo).
            if (fresh == 0 && seen < open && toggles < 2) { sleep(400); reply(SpaScripts.toggleItem(n)); toggles++ }
        }
        // Último recurso: o campo de valor entre o cartão do item e o próximo cartão.
        if (reply(SpaScripts.claimItemForm(n, tok, byPosition = true)).ok) return null
        return "não consegui abrir o formulário do item $n"
    }

    private suspend fun typeItemField(n: Int, field: String, text: String, matches: (String?) -> Boolean): String? {
        val f = reply(SpaScripts.itemField(n, field, focus = true))
        if (!f.found) return "campo “$field” do item $n não encontrado"
        if (f.flag("disabled")) return "campo “$field” do item $n desabilitado (termo/declarações pendentes?)"
        if (!driver.typeKeys(text, clearFirst = true)) return "não consegui digitar no item $n (${PortalSessionGate.OPEN_APP_MESSAGE})"
        sleep(450)
        var v = reply(SpaScripts.itemField(n, field, focus = false)).value
        if (matches(v)) return null
        log("Item $n, “$field”: a digitação deixou “${v.orEmpty()}”; tentando inserção de texto.")
        reply(SpaScripts.itemField(n, field, focus = true))
        driver.insertText(text)
        sleep(450)
        v = reply(SpaScripts.itemField(n, field, focus = false)).value
        return if (matches(v)) null else "o campo “$field” do item $n ficou com “${v.orEmpty()}” em vez de “$text”"
    }

    /** Valor unitário: digita "150999,99" (2 casas, vírgula, sem milhar) e confere numericamente (4 casas). */
    suspend fun fillItemValue(n: Int, value: Double): String? =
        typeItemField(n, "valor", ProposalMoney.typed(value)) { ProposalMoney.matches(value, it) }

    /**
     * Campo opcional (quantidade/marca/fabricante/modelo/descrição): só se existir no formulário do item e estiver
     * diferente do plano. Ausente = ok (serviços não têm marca/fabricante).
     */
    suspend fun fillOptional(n: Int, field: String, text: String, numeric: Boolean): String? {
        if (text.isBlank()) return null
        val f = reply(SpaScripts.itemField(n, field, focus = false))
        if (!f.found || f.flag("disabled")) return null
        val same: (String?) -> Boolean = if (numeric) { v -> ValueMatch.number(text, v) } else { v -> ValueMatch.text(text, v) }
        if (same(f.value)) return null
        return typeItemField(n, field, text, same)
    }

    /**
     * "Salvar" do formulário do item e conferência: toast de sucesso + cartão com "Meu valor (unitário)" igual. Erro do
     * portal ou valor divergente → motivo (para). Uma divergência sem erro é conferida de novo antes de parar.
     */
    suspend fun saveItem(n: Int, value: Double): String? {
        reply(SpaScripts.dismissToasts())
        sleep(800)
        val r = reply(SpaScripts.saveItem(n))
        if (!r.ok) return r.error ?: "não consegui clicar em Salvar do item $n"
        val deadline = clock() + 15_000
        var softStops = 0
        while (true) {
            sleep(700)
            val o = AutomationJson.obj(driver.eval(SpaScripts.itemStatus(n)))
            val text = o?.let { with(AutomationJson) { it.str("t") } }.orEmpty()
            val card = text.takeIf { it.isNotBlank() }?.let { ProposalItemCardParser.parse(it, n) }
            val toasts = SpaScripts.parseToasts(o)
            when (val out = ProposalSaveCheck.evaluate(value, toasts, card, clock() >= deadline)) {
                ProposalSaveCheck.Outcome.Ok -> return null
                ProposalSaveCheck.Outcome.Wait -> Unit
                is ProposalSaveCheck.Outcome.Stop -> {
                    val portalError = toasts.any { it.severity == "error" || it.severity == "warn" }
                    if (portalError || clock() >= deadline || ++softStops >= 3) return out.reason
                }
            }
        }
    }
}
