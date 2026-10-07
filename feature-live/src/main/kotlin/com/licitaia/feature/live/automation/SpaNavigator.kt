package com.licitaia.feature.live.automation

import kotlinx.coroutines.delay

/**
 * Passos nas telas REAIS do Compras.gov.br ([SpaScripts]) sobre um [PageDriver]. Cada função devolve null = ok ou o
 * motivo (PT-BR) de não ter conseguido — quem chama decide parar e pedir o usuário. Nada aqui clica na lixeira, em
 * favoritos ou em "Desfazer alterações"; termo/declarações ([acceptTerms]/[applyDeclarations]) só são chamados pelo
 * robô quando o usuário autorizou na confirmação.
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
        // No cadastro de proposta (talvez de OUTRA compra) não adianta esperar a lista: volta pela "Tela inicial".
        val url0 = state()?.url.orEmpty()
        val onProposal = url0.contains("/fornecedor/cadastro-propostas") || url0.contains("compra=") || url0.contains("acompanha", ignoreCase = true)
        if (waitState(if (onProposal) 1_000 else 15_000) { it.isComprasList } != null) return null
        if (reply(SpaScripts.clickAria("Tela inicial")).ok && waitState(15_000) { it.isComprasList } != null) return null
        // Página de compra sem o botão da casinha: migalha "Compras eletrônicas" (navegação dentro do SPA).
        if (reply(SpaScripts.clickBreadcrumb("Compras eletrônicas")).ok && waitState(15_000) { it.isComprasList } != null) return null
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

    /** Página atual pelo `location.href` (o SPA troca de rota sem recarregar) + texto do início do corpo. */
    suspend fun pageInfo(): PurchasePage? {
        val o = AutomationJson.obj(driver.eval(SpaScripts.pageInfo())) ?: return null
        return with(AutomationJson) {
            PurchasePage(o.str("url")?.takeIf { it.isNotBlank() } ?: driver.currentUrl().orEmpty(), o.str("text").orEmpty(), o.bool("list"), o.bool("modal"))
        }
    }

    private suspend fun waitProposalPage(p: Purchase, timeoutMs: Long = 25_000, requireProposalPage: Boolean = true): Boolean {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            val page = pageInfo()
            val url = page?.url ?: driver.currentUrl()
            if (CompraCode.isProposalPageFor(url, p.uasg, p.modality, p.number, p.year)) return true
            if (!requireProposalPage && page != null && PurchasePageCheck.verify(page, p, requireProposalPage = false) == null) return true
            sleep(700)
        }
        return false
    }

    /**
     * null = a página é o cadastro de proposta da compra [p] (URL com o `compra=` esperado E cabeçalho com "UASG" e
     * "N° número/ano"). Espera até [timeoutMs] o cabeçalho carregar.
     */
    suspend fun verifyPurchasePage(p: Purchase, timeoutMs: Long = 0, requireProposalPage: Boolean = true): String? {
        val deadline = clock() + timeoutMs
        while (true) {
            val page = pageInfo()
            val reason = if (page == null) "a página não respondeu" else PurchasePageCheck.verify(page, p, requireProposalPage)
            if (reason == null) return null
            if (clock() >= deadline) return reason
            sleep(700)
        }
    }

    /** Disponibilidade da compra aberta (prazo futuro, sem aviso de suspensa/encerrada); null = página não respondeu. */
    suspend fun availability(): PurchasePageCheck.Availability? = pageInfo()?.let { PurchasePageCheck.availability(it.text, clock()) }

    /** Toque REAL na posição devolvida por um script (`rect`). false = sem posição ou toque recusado. */
    private suspend fun tapRect(r: SpaScripts.Reply): Boolean {
        val rect = r.obj("rect") ?: return false
        val x = (rect["x"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: return false
        val y = (rect["y"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: return false
        sleep(250)
        return driver.tapAt(x, y)
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
     * Abre o cadastro de proposta da compra e CONFERE que é a compra certa (URL `cadastro-propostas?compra=<código>` +
     * cabeçalho "UASG …" e "N° n/aaaa"). Até 3 tentativas: a 1ª tenta o cartão da lista atual; as seguintes vão por
     * "Todas as compras" com UASG + número DIGITADOS e toque real em Pesquisar. Nunca devolve ok fora da página certa.
     */
    suspend fun openPurchase(p: Purchase, requireProposalPage: Boolean = true): String? {
        if (verifyPurchasePage(p, requireProposalPage = requireProposalPage) == null) return null
        pageInfo()?.let { PurchasePageCheck.mismatch(it, p) }?.let { log("Outra compra aberta ($it); voltando a Compras eletrônicas.") }
        var last = "a compra não abriu"
        for (attempt in 1..3) {
            val reason = attemptOpen(p, viaSearch = attempt > 1, requireProposalPage) ?: verifyPurchasePage(p, timeoutMs = 10_000, requireProposalPage = requireProposalPage)
            if (reason == null) {
                if (attempt > 1) log("Compra certa aberta na tentativa $attempt.")
                return null
            }
            last = reason
            log("Abrir a compra (tentativa $attempt de 3): $reason")
            sleep(1_000)
        }
        return "não consegui abrir o cadastro de proposta da compra certa (UASG ${p.uasgShown} · ${p.numberYear}) em 3 tentativas: $last"
    }

    private suspend fun attemptOpen(p: Purchase, viaSearch: Boolean, requireProposalPage: Boolean = true): String? {
        toComprasList()?.let { return it }
        if (!viaSearch && clickPurchase(p) == null) {
            log("Compra achada na lista atual; abrindo.")
            if (waitProposalPage(p, requireProposalPage = requireProposalPage)) return null
        }
        openTab("Todas as compras")?.let { return it }
        typeSearch("uasg", p.uasgShown, "Unidade compradora")?.let { return it }
        typeSearch("numero", CompraCode.searchNumber(p.number, p.year), "Número da compra")?.let { return it }
        // Pesquisar: toque REAL primeiro; depois clique; por fim Enter no número.
        var shown = tapRect(reply(SpaScripts.searchButton(tap = true))) && waitPurchaseCard(p, 10_000)
        if (!shown) {
            log("Pesquisar (toque) sem resultado; tentando clique.")
            if (reply(SpaScripts.searchButton(tap = false)).ok) shown = waitPurchaseCard(p, 8_000)
        }
        if (!shown) {
            log("Pesquisar (clique) sem resultado; tentando Enter no número da compra.")
            reply(SpaScripts.focusSearch("numero"))
            if (driver.pressEnter()) shown = waitPurchaseCard(p, 8_000)
        }
        if (!shown) return "a pesquisa por UASG ${p.uasgShown} e ${p.numberYear} não mostrou a compra"
        // Cartão cujo texto tem a UASG E o "N° número/ano" (mais de um igual = ambíguo, não clica).
        clickPurchase(p)?.let { return it }
        if (!waitProposalPage(p, requireProposalPage = requireProposalPage)) {
            return "o cadastro de proposta da compra ${p.numberYear} não abriu (URL ${CompraCode.fromUrl(pageInfo()?.url ?: driver.currentUrl()) ?: "sem compra="})"
        }
        return null
    }

    // ------------------------------------------------------------------ termo e declarações (com autorização)

    private suspend fun modalState(): TermsModal.State? = TermsModal.parse(driver.eval(SpaScripts.termsModal()))

    private suspend fun waitModal(open: Boolean, timeoutMs: Long): Boolean {
        val deadline = clock() + timeoutMs
        while (true) {
            if ((modalState()?.open ?: false) == open) return true
            if (clock() >= deadline) return false
            sleep(500)
        }
    }

    /**
     * Aceita o Termo de Aceitação (SÓ com autorização do usuário): toque real no checkbox do termo → modal "Termo de
     * aceitação das declarações" → "Marcar todas" → confere TODAS marcadas (senão marca uma a uma) → "Confirmar" →
     * espera o modal fechar e o termo aparecer aceito. Qualquer declaração desmarcada no fim → para SEM confirmar.
     */
    suspend fun acceptTerms(): String? {
        val st = declarations()
        if (st?.termAccepted == true && !st.modalOpen) return null
        if (modalState()?.open != true) {
            val r = reply(SpaScripts.termCheckbox(tap = true))
            if (!r.found) return "checkbox do “Termo de Aceitação” não encontrado"
            if (!r.flag("checked")) {
                if (!tapRect(r)) reply(SpaScripts.termCheckbox(tap = false))
                if (!waitModal(open = true, timeoutMs = 6_000)) {
                    if (declarations()?.termAccepted == true && modalState()?.open != true) return null
                    log("Termo: o toque não abriu o modal; tentando clique.")
                    reply(SpaScripts.termCheckbox(tap = false))
                    if (!waitModal(open = true, timeoutMs = 6_000)) {
                        return if (declarations()?.termAccepted == true) null else "o modal “Termo de aceitação das declarações” não abriu"
                    }
                }
            }
        }
        var triedAll = false
        var triedEach = false
        repeat(6) {
            val s = modalState() ?: return "não consegui ler o modal do termo"
            when (val d = TermsModal.decide(s, triedAll, triedEach)) {
                TermsModal.Decision.MarkAll -> {
                    triedAll = true
                    log("Termo: Marcar todas (${s.checked}/${s.total} marcadas).")
                    if (!tapRect(reply(SpaScripts.modalMarkAll(tap = true)))) reply(SpaScripts.modalMarkAll(tap = false))
                    sleep(900)
                    if ((modalState()?.checked ?: 0) <= s.checked) { reply(SpaScripts.modalMarkAll(tap = false)); sleep(900) }
                }
                TermsModal.Decision.MarkEach -> {
                    triedEach = true
                    log("Termo: conferindo uma a uma (${s.missing} desmarcada(s)).")
                    repeat(s.total + 2) {
                        val r = reply(SpaScripts.modalBox(tap = true))
                        val left = r.int("left") ?: 0
                        if (!r.found || left == 0) return@repeat
                        if (!tapRect(r)) reply(SpaScripts.modalBox(tap = false))
                        sleep(400)
                        if ((reply(SpaScripts.modalBox(tap = true)).int("left") ?: 0) >= left) { reply(SpaScripts.modalBox(tap = false)); sleep(400) }
                    }
                }
                TermsModal.Decision.Confirm -> {
                    log("Termo: ${s.checked} de ${s.total} declarações marcadas e conferidas; Confirmar.")
                    if (!tapRect(reply(SpaScripts.modalButton("Confirmar", tap = true)))) reply(SpaScripts.modalButton("Confirmar", tap = false))
                    if (!waitModal(open = false, timeoutMs = 8_000)) {
                        reply(SpaScripts.modalButton("Confirmar", tap = false))
                        if (!waitModal(open = false, timeoutMs = 8_000)) return "o modal do termo não fechou depois de Confirmar"
                    }
                    sleep(800)
                    return if (declarations()?.termAccepted == true) null else "o termo não aparece aceito depois de Confirmar"
                }
                is TermsModal.Decision.Stop -> return d.reason
            }
        }
        return "não consegui concluir o termo de aceitação"
    }

    /**
     * Aplica as respostas da empresa nos rádios Sim/Não (abre o acordeão se preciso; toque real, depois clique) e confere.
     * Grupo presente sem resposta da empresa → para (o robô não escolhe pela empresa).
     */
    suspend fun applyDeclarations(d: com.licitaia.domain.model.PortalDeclarations): String? {
        repeat(3) {
            val st = declarations() ?: return "não consegui ler as declarações"
            val (todo, unanswered) = DeclarationRadios.plan(st, d)
            if (unanswered.isNotEmpty()) return "a empresa não tem resposta cadastrada para: ${unanswered.joinToString()}"
            if (todo.isEmpty()) return null
            for (t in todo) {
                var r = reply(SpaScripts.declarationRadio(t.id, tap = true))
                if (r.flag("expanded")) { sleep(800); r = reply(SpaScripts.declarationRadio(t.id, tap = true)) }
                if (!r.found) return "rádio “${t.label}” (${if (t.yes) "Sim" else "Não"}) não encontrado"
                if (r.flag("disabled")) return "as declarações continuam desabilitadas (termo ainda não aceito?)"
                if (r.flag("hidden")) return "o rádio “${t.label}” está escondido (acordeão não abriu)"
                if (r.flag("checked")) continue
                log("Declaração “${t.label}”: ${if (t.yes) "Sim" else "Não"} (resposta da empresa).")
                if (!tapRect(r)) reply(SpaScripts.declarationRadio(t.id, tap = false))
                sleep(500)
                if (!reply(SpaScripts.declarationRadio(t.id, tap = true)).flag("checked")) { reply(SpaScripts.declarationRadio(t.id, tap = false)); sleep(500) }
            }
        }
        val st = declarations() ?: return "não consegui ler as declarações"
        val left = DeclarationRadios.plan(st, d).first
        return if (left.isEmpty()) null else "não consegui marcar: ${left.joinToString { "${it.label} = ${if (it.yes) "Sim" else "Não"}" }}"
    }

    // ------------------------------------------------------------------ muitos itens, grupos/lotes, paginação

    /** Abre os grupos/lotes fechados (até 3 passadas) e devolve a estrutura de cada grupo (vazio = compra sem grupos). */
    suspend fun prepareGroups(): List<GroupStructure> {
        repeat(3) {
            val clicked = reply(SpaScripts.expandGroups()).int("clicked") ?: 0
            if (clicked == 0) return@repeat
            log("Abrindo $clicked grupo(s)/lote(s).")
            sleep(1_200)
        }
        return GroupPlanner.parse(driver.eval(SpaScripts.groupsInfo()))
    }

    /**
     * Procura o cartão do item [n] (número EXATO): na tela; abrindo grupos; rolando a página (listas preguiçosas/
     * virtualizadas); pela paginação PrimeNG. null = não está nesta compra.
     */
    suspend fun locateItem(n: Int): ProposalItemCard? {
        itemCard(n)?.let { return it }
        if ((reply(SpaScripts.expandGroups()).int("clicked") ?: 0) > 0) { sleep(1_200); itemCard(n)?.let { return it } }
        reply(SpaScripts.scrollLoad(top = true))
        sleep(500)
        var lastCards = -1
        var stable = 0
        for (i in 0 until 80) {
            val r = reply(SpaScripts.scrollLoad(top = false))
            sleep(600)
            itemCard(n)?.let { return it }
            if ((reply(SpaScripts.expandGroups()).int("clicked") ?: 0) > 0) { sleep(1_000); itemCard(n)?.let { return it } }
            val cards = r.int("cards") ?: 0
            if (cards == lastCards && r.flag("end")) { if (++stable >= 2) break } else stable = 0
            lastCards = cards
        }
        if (reply(SpaScripts.firstPage()).ok) { sleep(1_500); itemCard(n)?.let { return it } }
        for (page in 0 until 60) {
            if (!reply(SpaScripts.nextPage()).ok) break
            sleep(1_500)
            reply(SpaScripts.expandGroups())
            sleep(500)
            itemCard(n)?.let { return it }
        }
        return null
    }

    /** Cartão do grupo [key] ("GRUPO 1") relido agora (total, "Meu valor (total)", "Proposta incompleta"). */
    suspend fun groupCard(key: String): GroupCard? =
        GroupPlanner.parse(driver.eval(SpaScripts.groupsInfo())).firstNotNullOfOrNull { g -> GroupCard.parse(g.text)?.takeIf { it.key == key } }

    /** Paginador DENTRO do grupo [key] (null = grupo não encontrado). */
    suspend fun groupPager(key: String): GroupPager? = GroupPager.parse(driver.eval(SpaScripts.groupPager(key, "read")))

    /** Itens (número) visíveis agora no grupo [key] (página atual do paginador do grupo). */
    suspend fun groupItems(key: String): List<Int> =
        GroupPlanner.parse(driver.eval(SpaScripts.groupsInfo())).firstOrNull { GroupCard.parse(it.text)?.key == key }?.items.orEmpty()

    /**
     * Garante o grupo [key] ABERTO e na página [page] do paginador dele (o portal fecha o grupo e/ou volta à página 1
     * depois de salvar). null = ok.
     */
    suspend fun ensureGroupPage(key: String, page: Int): String? {
        repeat(8) {
            // Só ESTE grupo (os outros ficam como estão): o chevron do cartão do grupo alterna v/^.
            openGroup(key)?.let { return it }
            val pg = groupPager(key) ?: return "o $key não aparece na página"
            when (val a = GroupTraversal.decide(pg, page)) {
                GroupTraversal.Action.Stay -> return null
                is GroupTraversal.Action.Fail -> return a.reason
                is GroupTraversal.Action.GoTo -> {
                    log("$key: indo para a página ${a.page} (estava na ${pg.current}).")
                    reply(SpaScripts.groupPager(key, "page", a.page))
                    sleep(1_300)
                }
            }
        }
        return "não consegui abrir a página $page do $key"
    }

    /** Recolhe o grupo antes de passar para o próximo. */
    suspend fun collapseGroup(key: String) { openGroup(key, open = false) }

    /** Abre ([open]) ou fecha o grupo [key] pelo chevron do próprio cartão e espera o redesenho. null = ok. */
    suspend fun openGroup(key: String, open: Boolean = true): String? {
        repeat(5) {
            val r = reply(SpaScripts.setGroupOpen(key, open))
            if (!r.found) return "o $key não aparece na página"
            if (!r.ok) return r.error ?: "não consegui ${if (open) "abrir" else "fechar"} o $key"
            if (r.flag("already")) return null
            sleep(1_200)
        }
        return if (reply(SpaScripts.setGroupOpen(key, open)).flag("already")) null else "o $key não ${if (open) "abriu" else "fechou"}"
    }

    /** Cartões de item da página atual do grupo [key] (texto) + cabeçalho do grupo. */
    data class GroupItemsView(val open: Boolean, val head: String, val items: List<String>)

    suspend fun groupItemsView(key: String): GroupItemsView? {
        val o = AutomationJson.obj(driver.eval(SpaScripts.groupItemCards(key))) ?: return null
        return with(AutomationJson) {
            if (!o.bool("found")) return null
            val items = (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.str("t") }
            GroupItemsView(o.bool("open"), o.str("head").orEmpty(), items)
        }
    }

    /** Espera os cartões de item do grupo aparecerem e pararem de mudar (redesenho do Angular). */
    private suspend fun stableGroupItems(key: String, timeoutMs: Long = 8_000): GroupItemsView? {
        val deadline = clock() + timeoutMs
        var last: GroupItemsView? = null
        while (true) {
            val v = groupItemsView(key)
            if (v != null && v.items.isNotEmpty() && v.items == last?.items) return v
            last = v ?: last
            if (clock() >= deadline) return last
            sleep(600)
        }
    }

    /** Abre SÓ o grupo [key] e lê a estrutura dele (itens da página, campos, botões de salvar). */
    suspend fun groupStructure(key: String): GroupStructure? {
        if (openGroup(key) != null) return null
        sleep(500)
        return GroupPlanner.parse(driver.eval(SpaScripts.groupsInfo())).firstOrNull { GroupCard.parse(it.text)?.key == key }
    }

    /** Grupos da compra ("GRUPO 1", "GRUPO 2", …) na ordem da página; vazio = compra sem grupos. */
    suspend fun groupKeys(): List<String> =
        GroupPlanner.parse(driver.eval(SpaScripts.groupsInfo())).mapNotNull { GroupCard.parse(it.text)?.key }.distinct()

    /**
     * LÊ um grupo inteiro sem preencher nada: abre o grupo, percorre TODAS as páginas do paginador interno, guarda o
     * texto de cada cartão de item e (com [close]) fecha o grupo no fim.
     */
    suspend fun readGroup(key: String, close: Boolean = true): GroupRead {
        openGroup(key)?.let { return GroupRead(key, groupCard(key), emptyList(), it) }
        var head: String? = null
        val (pages, warn) = GroupPageWalk.walk(
            goTo = { p -> ensureGroupPage(key, p) },
            read = {
                val v = stableGroupItems(key)
                if (v != null && v.head.isNotBlank()) head = v.head
                GroupPageWalk.View(groupPager(key), v?.items.orEmpty())
            },
        )
        val card = head?.let(GroupCard::parse) ?: groupCard(key)
        log("$key lido: ${pages.size} página(s), ${pages.sumOf { it.size }} item(ns)" + (warn?.let { " · $it" } ?: ""))
        if (close) openGroup(key, open = false)
        return GroupRead(key, card, pages, warn)
    }

    /** Compra SEM grupos: cartões de item de todas as páginas da lista (paginador da página). */
    suspend fun readFlatItems(maxPages: Int = 30): List<String> {
        val out = LinkedHashMap<Int, String>()
        fun take(raw: String?) {
            val o = AutomationJson.obj(raw) ?: return
            with(AutomationJson) {
                (o["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { el ->
                    val x = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    val n = x.int("n") ?: return@forEach
                    if (n > 0) out.putIfAbsent(n, x.str("t").orEmpty())
                }
            }
        }
        if (reply(SpaScripts.firstPage()).ok) sleep(1_500)
        take(driver.eval(SpaScripts.items()))
        for (i in 0 until maxPages) {
            if (!reply(SpaScripts.nextPage()).ok) break
            sleep(1_500)
            take(driver.eval(SpaScripts.items()))
        }
        return out.values.toList()
    }

    /** Situação de TODOS os itens no portal (grupo a grupo, página a página), sem preencher nada. */
    suspend fun readProposalState(): Pair<com.licitaia.domain.portal.PortalProposalReading, List<GroupRead>> {
        val keys = groupKeys()
        if (keys.isEmpty()) return ProposalReadingBuilder.build(clock(), emptyList(), readFlatItems()) to emptyList()
        val reads = keys.map { readGroup(it) }
        return ProposalReadingBuilder.build(clock(), reads) to reads
    }

    /** Números dos itens visíveis (diagnóstico). */
    suspend fun visibleItems(): List<Int> =
        AutomationJson.obj(driver.eval(SpaScripts.itemNumbers()))?.let { with(AutomationJson) { it.strings("items") } }.orEmpty().mapNotNull { it.toIntOrNull() }

    /**
     * Salva o GRUPO de uma vez (os valores já digitados e conferidos) e confere cada item pelo "Meu valor (unitário)".
     * Devolve, por item, null = conferido ou o motivo.
     */
    suspend fun saveGroup(g: GroupStructure, values: List<Pair<Int, Double>>): Map<Int, String?> {
        reply(SpaScripts.dismissToasts())
        sleep(800)
        val r = reply(SpaScripts.saveGroup(g.index, g.label))
        if (!r.ok) return values.associate { it.first to (r.error ?: "não consegui tocar no Salvar do grupo") }
        val deadline = clock() + 25_000
        val pending = values.toMap().toMutableMap()
        val out = LinkedHashMap<Int, String?>()
        var success = false
        while (pending.isNotEmpty()) {
            sleep(900)
            for ((n, v) in pending.toMap()) {
                val o = AutomationJson.obj(driver.eval(SpaScripts.itemStatus(n)))
                val toasts = SpaScripts.parseToasts(o)
                toasts.firstOrNull { it.severity == "error" || it.severity == "warn" }?.let { t ->
                    pending.keys.forEach { k -> out[k] = "o portal avisou: “${t.text.take(160)}”" }
                    return values.associate { it.first to out[it.first] }
                }
                if (toasts.any { it.severity == "success" }) success = true
                val card = o?.let { with(AutomationJson) { it.str("t") } }?.takeIf { it.isNotBlank() }?.let { ProposalItemCardParser.parse(it, n) }
                if (card?.myUnitValue != null && ProposalMoney.matches(v, card.myUnitValue.toPlainString().replace('.', ','))) {
                    out[n] = null
                    pending.remove(n)
                }
            }
            if (clock() >= deadline) break
        }
        pending.keys.forEach { n ->
            out[n] = if (success) "o portal disse “sucesso”, mas o item não mostra “Meu valor (unitário)” igual" else "sem confirmação do portal depois do Salvar do grupo"
        }
        return values.associate { it.first to out[it.first] }
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
        if (!reply(SpaScripts.toggleItem(n)).ok) {
            // Itens dentro de grupo podem mostrar o campo sem seta: o campo de valor entre este cartão e o próximo.
            if (reply(SpaScripts.claimItemForm(n, tok, byPosition = true)).ok) return null
            return "seta do item $n não encontrada"
        }
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
