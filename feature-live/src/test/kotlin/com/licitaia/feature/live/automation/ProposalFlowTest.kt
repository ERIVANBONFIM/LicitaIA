package com.licitaia.feature.live.automation

import com.licitaia.domain.model.PortalDeclarations
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.ProposalAuthorization
import com.licitaia.domain.portal.ProposalItemPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/** Robô de proposta: seleção de itens, confirmação, compra certa, termo, declarações, número exato do item e grupos. */
class ProposalFlowTest {
    private fun real(name: String) = javaClass.classLoader!!.getResource("fixtures/real/$name")!!.readText()
    private fun field(dump: String, prefix: String) = dump.lines().first { it.startsWith(prefix) }.removePrefix(prefix)
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val sebrae = SpaNavigator.Purchase("927312", "Pregão Eletrônico", "3", 2026)
    private val complete = PortalDeclarations(meEpp = true, genderEquity = false, integrity = true)

    private fun item(n: Int, price: Double, qty: Double = 1.0, selected: Boolean = true) = ProposalItemPlan(n, quantity = qty, unitPrice = price, selected = selected)

    // ------------------------------------------------------------------ seleção / total / confirmação

    @Test fun selectionCountsOnlySelectedAndHighlightsItemsWithoutPrice() {
        val items = listOf(item(1, 209.25, 12.0), item(2, 10.0, 3.0, selected = false), item(3, 0.0, 5.0, selected = false), item(77, 1.5, 2.0))
        val s = RobotPlanRules.selection(items)
        assertEquals(4, s.total)
        assertEquals(2, s.selected)
        assertEquals(2511.0 + 3.0, s.selectedTotal, 1e-9)
        assertEquals(listOf(3), s.withoutPrice)
        assertEquals("2 de 4 itens selecionados", s.label)
    }

    @Test fun selectAllNeverSelectsItemsWithoutPrice() {
        val all = RobotPlanRules.selectAll(listOf(item(1, 5.0, selected = false), item(2, 0.0)), true)
        assertEquals(listOf(true, false), all.map { it.selected })
        assertEquals(listOf(false, false), RobotPlanRules.selectAll(all, false).map { it.selected })
    }

    @Test fun proposalErrorsIgnoreUnselectedItemsWithoutPrice() {
        assertTrue(RobotPlanRules.proposalErrors(listOf(item(1, 10.0), item(2, 0.0, selected = false))).isEmpty())
        assertEquals("Selecione pelo menos um item para participar.", RobotPlanRules.proposalErrors(listOf(item(1, 10.0, selected = false))).first())
        assertTrue(RobotPlanRules.proposalErrors(listOf(item(2, 0.0))).any { it.contains("valor unitário") })
    }

    @Test fun confirmationRequiresAuthorizationAndCompleteDeclarations() {
        val items = listOf(item(1, 10.0))
        assertTrue(RobotPlanRules.confirmationErrors(items, acceptTerms = false, declarations = complete).any { it.contains("Autorizo o aceite do Termo de Aceitação") })
        assertTrue(RobotPlanRules.confirmationErrors(items, true, complete.copy(integrity = null)).any { it.contains("ME/EPP") })
        assertTrue(RobotPlanRules.confirmationErrors(items, true, complete).isEmpty())
        assertFalse(RobotPlanRules.confirmationErrors(listOf(item(1, 10.0, selected = false)), true, complete).isEmpty())
    }

    @Test fun authorizationAuditNamesWhoWhenTenderAndAnswers() {
        val t = PortalMyTender(1, "160192-00048-2026", uasg = "160192", number = "48", year = 2026, modality = "Pregão Eletrônico", objectDescription = "", openingAt = null, situation = "", hasProposal = false, sources = emptySet(), firstSeenAt = 0, updatedAt = 0)
        val text = RobotPlanRules.authorizationAudit(t, ProposalAuthorization(true, complete, "Erivan", at(2026, 10, 7, 9, 0)), listOf(item(1, 10.0), item(2, 5.0, selected = false)))
        listOf("Erivan", "07/10/2026", "UASG 160192 · 48/2026", "ME/EPP: Sim", "Equidade de gênero: Não", "Programa de integridade: Sim", "1 de 2 itens").forEach {
            assertTrue("sem: $it em $text", text.contains(it))
        }
    }

    // ------------------------------------------------------------------ número exato do item

    @Test fun itemNumberMatchesExactlyAtStartOfCard() {
        assertEquals(77, ItemNumber.of("77 ACESSO A INTERNET VIA CABO Quantidade solicitada 1"))
        assertFalse(ItemNumber.matches("77 ACESSO A INTERNET", 7))
        assertTrue(ItemNumber.matches("7 ACESSO A INTERNET", 7))
        assertFalse(ItemNumber.matches("17 CABO", 7))
        assertNull(ItemNumber.of("GRUPO 1 | 24 itens"))
        assertNull(ItemNumber.of("12345 número longo"))
    }

    // ------------------------------------------------------------------ compra certa / disponibilidade (telas reais)

    @Test fun realProposalPageIsTheRightPurchase() {
        val dump = real("REAL_03_cadastro_propostas.txt")
        val page = PurchasePage(field(dump, "URL: "), field(dump, "TEXTO: "))
        assertNull(PurchasePageCheck.verify(page, sebrae))
        assertTrue(PurchasePageCheck.header(page.text).contains("UASG 927312"))
    }

    @Test fun realProposalPageOfAnotherPurchaseIsRejected() {
        val dump = real("REAL_03_cadastro_propostas.txt")
        val page = PurchasePage(field(dump, "URL: "), field(dump, "TEXTO: "))
        // Mesma UASG, outro número: o código compra= da URL não bate.
        assertTrue(PurchasePageCheck.verify(page, sebrae.copy(number = "30"))!!.contains("não a pedida"))
        assertTrue(PurchasePageCheck.verify(page.copy(text = ""), sebrae.copy(number = "30"))!!.contains("OUTRA compra"))
        // URL certa mas cabeçalho de outra UASG (portal trocou o conteúdo): rejeita pelo texto.
        val other = page.copy(text = page.text.replace("UASG 927312", "UASG 160192"))
        assertTrue(PurchasePageCheck.verify(other, sebrae)!!.contains("UASG 927312"))
        // 3/2026 não casa com 30/2026 nem 3/20261.
        assertFalse(PurchasePageCheck.numberIn("Pregão Eletrônico N° 30/2026", "3", 2026))
        assertTrue(PurchasePageCheck.numberIn("Pregão Eletrônico N° 0003/2026 (Lei", "3", 2026))
    }

    @Test fun realParticipationsListIsNotTheProposalPage() {
        val dump = real("REAL_01_minhas_participacoes.txt")
        val page = PurchasePage(field(dump, "URL: "), field(dump, "TEXTO: "), isList = true)
        val why = PurchasePageCheck.verify(page, SpaNavigator.Purchase("160192", "Pregão Eletrônico", "48", 2026))
        assertNotNull(why)
        assertTrue(why!!.contains("lista de compras"))
    }

    @Test fun availabilityReadsRealDeadline() {
        val text = field(real("REAL_03_cadastro_propostas.txt"), "TEXTO: ")
        val ok = PurchasePageCheck.availability(text, at(2026, 10, 7, 12, 0))
        assertTrue(ok.ok)
        assertEquals(at(2026, 10, 13, 8, 59), ok.deadline)
        assertTrue(ok.describe().contains("aceita proposta agora"))
        val late = PurchasePageCheck.availability(text, at(2026, 10, 13, 9, 0))
        assertFalse(late.ok)
        assertTrue(late.blocked!!.contains("terminou"))
        val suspended = PurchasePageCheck.availability(text.replace("Cadastrar propostas Pregão", "Cadastrar propostas COMPRA SUSPENSA Pregão"), at(2026, 10, 7, 12, 0))
        assertFalse(suspended.ok)
    }

    // ------------------------------------------------------------------ termo de aceitação

    @Test fun termsModalAllCheckedConfirms() {
        assertEquals(TermsModal.Decision.Confirm, TermsModal.decide(TermsModal.State(true, 9, 9, confirmPresent = true), triedMarkAll = true, triedEach = false))
    }

    @Test fun termsModalMissingMarksAllThenEachThenStopsWithoutConfirming() {
        val s = TermsModal.State(true, 9, 7, confirmPresent = true)
        assertEquals(TermsModal.Decision.MarkAll, TermsModal.decide(s, false, false))
        assertEquals(TermsModal.Decision.MarkEach, TermsModal.decide(s, true, false))
        val stop = TermsModal.decide(s, true, true)
        assertTrue(stop is TermsModal.Decision.Stop)
        assertTrue((stop as TermsModal.Decision.Stop).reason.contains("2 de 9"))
        assertTrue(TermsModal.decide(TermsModal.State(true, 0, 0), false, false) is TermsModal.Decision.Stop)
        assertTrue(TermsModal.decide(TermsModal.State(true, 3, 3, confirmPresent = true, confirmDisabled = true), true, true) is TermsModal.Decision.Stop)
    }

    @Test fun termsModalParsesScriptReply() {
        val s = TermsModal.parse("\"{\\\"open\\\":true,\\\"total\\\":9,\\\"checked\\\":4,\\\"confirm\\\":true,\\\"confirmDisabled\\\":false}\"")!!
        assertEquals(5, s.missing)
        assertTrue(s.open)
    }

    // ------------------------------------------------------------------ declarações da empresa

    @Test fun declarationRadiosFollowCompanyAnswers() {
        val pending = DeclarationsState(
            section = true, warning = true, termAccepted = true, modalOpen = false,
            groups = listOf(
                DeclarationsState.Group("meepp", "ME/EPP", yes = false, no = false),
                DeclarationsState.Group("equidade", "Equidade", yes = true, no = false),
                DeclarationsState.Group("integridade", "Integridade", yes = true, no = false),
            ),
        )
        val (todo, unanswered) = DeclarationRadios.plan(pending, complete)
        assertTrue(unanswered.isEmpty())
        assertEquals(listOf("labelSimMeepp", "declaracaoEquidadeGeneroNao"), todo.map { it.id })
        assertEquals(listOf("ME/EPP"), DeclarationRadios.plan(pending, complete.copy(meEpp = null)).second)
    }

    // ------------------------------------------------------------------ grupos / lotes

    /** REAL (print do usuário, Pregão 48/2026 UASG 160192): cartão do grupo incompleto. */
    @Test fun realGroupCardIncomplete() {
        val g = GroupCard.parse("GRUPO 1 | 24 itens < apelido > Valor estimado (total) R$ 90.523,1600 Meu valor (total) R$ 25.110,0000 Proposta incompleta")!!
        assertEquals("GRUPO 1", g.key)
        assertEquals(24, g.itemCount)
        assertEquals(0, BigDecimal("90523.1600").compareTo(g.estimatedTotal))
        assertEquals(0, BigDecimal("25110.0000").compareTo(g.myTotal))
        assertTrue(g.incomplete)
        assertEquals("Grupo 1: 9/24", g.progress(9))
        assertFalse(GroupCard.parse("GRUPO 1 | 24 itens Valor estimado (total) R$ 90.523,1600 Meu valor (total) R$ 90.000,0000")!!.incomplete)
        assertNull(GroupCard.parse("10 CABO DE REDE"))
    }

    /** REAL: grupo aberto com cartões de item (campo aparece ao abrir cada item) → salvar por item. */
    @Test fun realGroupWithItemCardsIsPerItem() {
        val g = GroupStructure(0, "GRUPO 1 | 24 itens", items = (1..24).toList(), inputs = 0, perItemSave = 0, groupSaves = emptyList(), groupValueField = false)
        assertEquals(GroupStrategy.PerItem, GroupPlanner.decide(g))
    }

    /** PROVISÓRIA (DOM sintético): cada bloco de item com o próprio Salvar. */
    @Test fun provisionalGroupPerItemSave() {
        val raw = "{\"groups\":[{\"i\":0,\"label\":\"Grupo 1\",\"text\":\"GRUPO 1 | 3 itens\",\"items\":[\"1\",\"2\",\"3\"],\"inputs\":3,\"perItemSave\":3,\"saves\":[],\"groupValue\":false}]}"
        val g = GroupPlanner.parse(raw).single()
        assertEquals(listOf(1, 2, 3), g.items)
        assertEquals("GRUPO 1", GroupCard.parse(g.text)!!.key)
        assertEquals(GroupStrategy.PerItem, GroupPlanner.decide(g))
    }

    /** PROVISÓRIA (DOM sintético): um único "Salvar todos" do grupo. */
    @Test fun provisionalGroupSingleSave() {
        val raw = "{\"groups\":[{\"i\":1,\"label\":\"Lote 2\",\"items\":[\"4\",\"5\"],\"inputs\":2,\"perItemSave\":0,\"saves\":[\"Salvar todos\"],\"groupValue\":false}]}"
        assertEquals(GroupStrategy.GroupSave("Salvar todos"), GroupPlanner.decide(GroupPlanner.parse(raw).single()))
    }

    /** PROVISÓRIA: valor do grupo sem valor no plano, ou forma ambígua → para com segurança. */
    @Test fun provisionalGroupStopsSafely() {
        val base = GroupStructure(0, "Grupo 1", items = listOf(1, 2), inputs = 2, perItemSave = 0, groupSaves = listOf("Salvar"), groupValueField = true)
        assertTrue(GroupPlanner.decide(base) is GroupStrategy.Stop)
        assertTrue(GroupPlanner.decide(base.copy(groupValueField = false, groupSaves = listOf("Salvar", "Salvar grupo"))) is GroupStrategy.Stop)
        assertTrue(GroupPlanner.decide(base.copy(groupValueField = false, perItemSave = 1)) is GroupStrategy.Stop)
        assertTrue(GroupPlanner.decide(base.copy(groupValueField = false, groupSaves = emptyList())) is GroupStrategy.Stop)
    }

    // ------------------------------------------------------------------ "Abrir no portal": compra aberta ≠ compra pedida

    /** REAL (print v0.5.2): a aba ficou em "Acompanhar Contratação" da compra A quando o usuário pediu a compra B. */
    private val acompanharA = PurchasePage(
        url = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/acompanhamento-compra?compra=16033905000612026",
        text = "Compras eletrônicas Acompanhar Contratação Pregão Eletrônico N° 61/2026 (SRP) (Lei 14.133/2021) UASG 160339 - " +
            "COMANDO DA 6A REGIAO MILITAR COMPRA SUSPENSA Critério julgamento: Menor Preço Itens Avisos Documentos",
    )

    @Test fun purchaseTargetMismatchIsDetected() {
        val b = SpaNavigator.Purchase("986727", "Pregão Eletrônico", "76", 2026)
        assertTrue(PurchasePageCheck.isPurchasePage(acompanharA))
        assertEquals(PurchasePageCheck.Ref("160339", "61", 2026), PurchasePageCheck.currentRef(acompanharA))
        val why = PurchasePageCheck.mismatch(acompanharA, b)!!
        assertTrue(why.contains("UASG 160339 · N° 61/2026") && why.contains("UASG 986727 · N° 76/2026"))
        assertNotNull(PurchasePageCheck.verify(acompanharA, b, requireProposalPage = false))
        // A compra A pedida: a página "Acompanhar Contratação" vale para "Abrir no portal", não para o robô de proposta.
        val a = SpaNavigator.Purchase("160339", "Pregão Eletrônico", "61", 2026)
        assertNull(PurchasePageCheck.mismatch(acompanharA, a))
        assertNull(PurchasePageCheck.verify(acompanharA, a, requireProposalPage = false))
        assertNotNull(PurchasePageCheck.verify(acompanharA, a))
        assertFalse(PurchasePageCheck.availability(acompanharA.text, at(2026, 10, 7, 12, 0)).ok)
        // Lista de compras não é "outra compra".
        assertNull(PurchasePageCheck.mismatch(acompanharA.copy(isList = true), b))
    }

    // ------------------------------------------------------------------ grupo paginado

    /** REAL (print): próximo grupo ainda sem proposta, exclusivo ME/EPP. */
    @Test fun realGroupCardNotRegistered() {
        val g = GroupCard.parse("GRUPO 2 | 16 itens Exclusividade ME/EPP < apelido > Valor estimado (total) R$ 55.272,0000 Proposta não cadastrada")!!
        assertEquals("GRUPO 2", g.key)
        assertEquals(16, g.itemCount)
        assertTrue(g.notRegistered && g.meEppExclusive && !g.incomplete)
        // Itens dentro do cartão do grupo não contaminam o estado do grupo.
        val nested = GroupCard.parse(
            "GRUPO 1 | 24 itens Valor estimado (total) R$ 90.523,1600 Meu valor (total) R$ 90.000,0000 " +
                "1 CABO Unidade fornecimento Valor estimado (unitário) R$ 238,3300 Proposta não cadastrada",
        )!!
        assertFalse(nested.notRegistered)
    }

    /**
     * PROVISÓRIA (montada da descrição do print: paginador «‹ 1 [2] 3 ›» DENTRO do grupo, 24 itens, 10 por página; o
     * portal volta à página 1 depois de cada Salvar): o percurso processa as páginas 1, 2 e 3, nessa ordem, cada item
     * uma vez, voltando à página em processamento depois de cada salvar.
     */
    @Test fun provisionalPaginatedGroupTraversal() {
        val itemsPerPage = 10
        val total = 24
        var current = 1
        fun pager() = GroupPager(true, listOf(1, 2, 3), current, current < 3)
        fun visible() = ((current - 1) * itemsPerPage + 1..minOf(current * itemsPerPage, total)).toList()
        fun ensure(page: Int) {
            repeat(5) {
                when (val a = GroupTraversal.decide(pager(), page)) {
                    GroupTraversal.Action.Stay -> return
                    is GroupTraversal.Action.GoTo -> current = a.page
                    is GroupTraversal.Action.Fail -> error(a.reason)
                }
            }
            error("não chegou na página $page")
        }
        val processed = mutableListOf<Int>()
        val pagesSeen = mutableListOf<Int>()
        var page = 1
        val expected = GroupTraversal.expectedPages(24, itemsPerPage, pager())
        assertEquals(3, expected)
        while (true) {
            ensure(page)
            pagesSeen += page
            for (n in visible()) {
                ensure(page)
                processed += n
                current = 1 // o portal volta à página 1 depois de salvar
            }
            ensure(page)
            if (pager().hasNext) page++ else break
        }
        assertEquals(listOf(1, 2, 3), pagesSeen)
        assertEquals((1..24).toList(), processed)
        assertEquals("Grupo 1 · página 2/3 · 14/24", GroupTraversal.progress("GRUPO 1", 2, 3, 14, 24))
        assertEquals(GroupTraversal.Action.Stay, GroupTraversal.decide(GroupPager(false, emptyList(), 1, false), 1))
        assertTrue(GroupTraversal.decide(GroupPager(false, emptyList(), 1, false), 2) is GroupTraversal.Action.Fail)
        val raw = "{\"found\":true,\"ok\":true,\"pager\":true,\"pages\":[\"1\",\"2\",\"3\"],\"current\":2,\"next\":true}"
        assertEquals(GroupPager(true, listOf(1, 2, 3), 2, true), GroupPager.parse(raw))
        assertTrue(SpaScripts.groupPager("GRUPO 1", "next").contains("SP.inGroup(x,hs,gi)"))
    }

    // ------------------------------------------------------------------ leitura do que já está lançado (REAL_TEXTO)

    private val reais by lazy {
        real("REAL_09_grupos_itens_lancados.txt").lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore(": ") to it.substringAfter(": ") }
    }

    /** Texto REAL do cartão do item [n] na situação do print (1–9 lançados a 209,25; 10 a 208,25; 11–24 não cadastrados). */
    private fun cardText(n: Int): String = when {
        n == 10 -> reais.getValue("ITEM_10")
        n < 10 -> reais.getValue("ITEM_LANCADO").replaceFirst(Regex("^1 "), "$n ")
        else -> reais.getValue("ITEM_NAO_LANCADO").replaceFirst(Regex("^11 "), "$n ")
    }

    /** Grupo 1 do print: 24 itens em 3 páginas (10 por página), lidas pelo percurso do paginador interno. */
    private fun readGroup1(): GroupRead {
        var current = 1
        val (pages, warn) = kotlinx.coroutines.runBlocking {
            GroupPageWalk.walk(
                goTo = { p -> current = p; null },
                read = {
                    val items = ((current - 1) * 10 + 1..minOf(current * 10, 24)).map(::cardText)
                    GroupPageWalk.View(GroupPager(true, listOf(1, 2, 3), current, current < 3), items)
                },
            )
        }
        assertNull(warn)
        return GroupRead("GRUPO 1", GroupCard.parse(reais.getValue("GRUPO1")), pages)
    }

    private val plan24 = (1..24).map { item(it, 209.25, 12.0) }

    /** REAL_TEXTO: item lançado (tem "Meu valor (unitário)" + valor) e item "Proposta não cadastrada". */
    @Test fun realItemCardLaunchedAndNotRegistered() {
        val l = PortalItemCard.parse(reais.getValue("ITEM_LANCADO"))!!
        assertEquals(1, l.number)
        assertEquals(12.0, l.quantity!!, 1e-9)
        assertEquals(0, BigDecimal("238.3300").compareTo(l.estimatedUnit))
        assertEquals(0, BigDecimal("209.2500").compareTo(l.myUnit))
        assertEquals(0, BigDecimal("2511.0000").compareTo(l.myTotal))
        assertEquals(com.licitaia.domain.portal.PortalItemState.LANCADO, l.state)
        val n = PortalItemCard.parse(reais.getValue("ITEM_NAO_LANCADO"))!!
        assertEquals(11, n.number)
        assertEquals(0, BigDecimal("238.3300").compareTo(n.estimatedUnit))
        assertNull(n.myUnit)
        assertTrue(n.notRegistered)
        assertEquals(com.licitaia.domain.portal.PortalItemState.NAO_CADASTRADO, n.state)
        // O cartão do grupo não é item.
        assertNull(PortalItemCard.parse(reais.getValue("GRUPO1")))
        // O parser antigo (conferência depois de salvar) continua lendo o "Meu valor (unitário)".
        assertEquals(0, BigDecimal("209.2500").compareTo(ProposalItemCardParser.parse(reais.getValue("ITEM_LANCADO"))!!.myUnitValue))
    }

    /** REAL_TEXTO: item 10 lançado com valor diferente do plano → não sobrescreve por padrão; atualiza só com a opção. */
    @Test fun realItem10DifferentValueIsNotOverwrittenByDefault() {
        val c = PortalItemCard.parse(reais.getValue("ITEM_10"))!!
        assertEquals(0, BigDecimal("208.2500").compareTo(c.myUnit))
        assertEquals(0, BigDecimal("2499.0000").compareTo(c.myTotal))
        val r = ProposalReadingBuilder.build(0, listOf(readGroup1())).of(10)!!
        val skip = ProposalItemDecision.decide(item(10, 209.25, 12.0), r, updateDifferent = false)
        assertEquals(ItemAction.SkipDifferent(208.25, 209.25), skip)
        assertTrue(ProposalItemDecision.describe(10, skip).contains("lançado com valor diferente (portal R$ 208,25 × plano R$ 209,25)"))
        assertEquals(ItemAction.Update(208.25, 209.25), ProposalItemDecision.decide(item(10, 209.25, 12.0), r, updateDifferent = true))
    }

    /** REAL_TEXTO: grupo 1 com paginador interno «‹ [1] 2 3 ›» → 3 páginas, 24 itens: 10 lançados e 14 não cadastrados. */
    @Test fun realGroupPaginationThreePagesBuildsItemMap() {
        val g = readGroup1()
        assertEquals(3, g.pages.size)
        assertEquals(listOf(10, 10, 4), g.pages.map { it.size })
        val card = g.card!!
        assertEquals(24, card.itemCount)
        assertTrue(card.incomplete)
        assertEquals(0, BigDecimal("25098.0000").compareTo(card.myTotal))
        val reading = ProposalReadingBuilder.build(1_000, listOf(g))
        assertEquals((1..24).toList(), reading.items.map { it.itemNumber })
        assertEquals((1..10).toList(), reading.items.filter { it.state == com.licitaia.domain.portal.PortalItemState.LANCADO }.map { it.itemNumber })
        assertEquals((11..24).toList(), reading.items.filter { it.state == com.licitaia.domain.portal.PortalItemState.NAO_CADASTRADO }.map { it.itemNumber })
        assertTrue(reading.items.all { it.group == "GRUPO 1" })
        assertEquals("Grupo 1 (24 itens, 3 página(s)): 10 lançado(s) · 14 não cadastrado(s)", ProposalReadingBuilder.groupLine(g, reading))
        // Percurso que não avança (o portal ficou na página 1) → aviso, sem repetir itens.
        val (stuck, warn) = kotlinx.coroutines.runBlocking {
            GroupPageWalk.walk(goTo = { null }, read = { GroupPageWalk.View(GroupPager(true, listOf(1, 2, 3), 1, true), (1..10).map(::cardText)) })
        }
        assertEquals(1, stuck.size)
        assertNotNull(warn)
    }

    /** Decisão: lançado igual → pula ("já lançado: R$ 209,25"); não cadastrado → preenche; resumo do grupo. */
    @Test fun decisionSkipsLaunchedAndFillsNotRegistered() {
        val reading = ProposalReadingBuilder.build(0, listOf(readGroup1()))
        val a1 = ProposalItemDecision.decide(item(1, 209.25, 12.0), reading.of(1), false)
        assertEquals(ItemAction.SkipAlready(209.25), a1)
        assertEquals("Item 1: já lançado: R$ 209,25", ProposalItemDecision.describe(1, a1))
        assertEquals(ItemAction.Fill, ProposalItemDecision.decide(item(11, 209.25, 12.0), reading.of(11), false))
        assertEquals(ItemAction.Fill, ProposalItemDecision.decide(item(30, 1.0), null, false))
        // Execução: 14 salvos agora (11–24), item 10 diferente não sobrescrito.
        val tally = GroupTally.of(
            "GRUPO 1", 24, (1..24).toSet(),
            reading.items.filter { it.state == com.licitaia.domain.portal.PortalItemState.LANCADO }.map { it.itemNumber }.toSet(),
            savedNow = (11..24).toSet(), different = setOf(10),
        )
        assertTrue(tally.line(), tally.line().startsWith("Grupo 1: 10 já lançados · 14 cadastrados agora · 0 faltando"))
        assertEquals("Grupo 1: 10 já lançados · 14 cadastrados agora · 0 faltando", tally.copy(different = 0).line())
        // Com a opção de atualizar: o item 10 salvo agora sai de "já lançados".
        assertEquals("Grupo 1: 9 já lançados · 15 cadastrados agora · 0 faltando", GroupTally.of("GRUPO 1", 24, (1..24).toSet(), (1..10).toSet(), (10..24).toSet(), emptySet()).line())
    }

    /** REAL_TEXTO: GRUPO 2 "Exclusividade ME/EPP" e a empresa declarou "Não" → avisa e pula o grupo. */
    @Test fun meEppExclusiveGroupIsSkippedWhenCompanyDeclaresNo() {
        val card = GroupCard.parse(reais.getValue("GRUPO2"))!!
        assertTrue(card.meEppExclusive && card.notRegistered)
        val why = GroupEligibility.skipReason("GRUPO 2", card, meEpp = false)!!
        assertTrue(why.contains("Exclusividade ME/EPP") && why.contains("não pode participar"))
        assertNull(GroupEligibility.skipReason("GRUPO 2", card, meEpp = true))
        assertNull(GroupEligibility.skipReason("GRUPO 1", GroupCard.parse(reais.getValue("GRUPO1")), meEpp = false))
        assertEquals("Grupo 2: pulado — $why", GroupTally("GRUPO 2", 0, 0, 0, skipped = why).line())
        // Itens do grupo 2 (não cadastrados) não são pré-selecionados com ME/EPP = Não.
        val g2 = GroupRead("GRUPO 2", card, listOf((25..26).map { reais.getValue("ITEM_NAO_LANCADO").replaceFirst(Regex("^11 "), "$it ") }))
        val reading = ProposalReadingBuilder.build(0, listOf(g2))
        assertTrue(reading.of(25)!!.meEppExclusive)
        val pre = ProposalReadingRules.preselect(listOf(item(25, 5.0), item(26, 5.0)), reading, meEpp = false)
        assertTrue(pre.none { it.selected })
        assertEquals(PortalMarker.Kind.BLOQUEADO, ProposalReadingRules.marker(item(25, 5.0), reading, false)!!.kind)
        assertTrue(ProposalReadingRules.preselect(listOf(item(25, 5.0)), reading, meEpp = true).single().selected)
        assertEquals("GRUPO 3", GroupCard.parse(reais.getValue("GRUPO3"))!!.key)
    }

    /** Depois da leitura: só os "Proposta não cadastrada" ficam selecionados; marcas ✓ / ⚠ / ○. */
    @Test fun preselectionAfterReadingKeepsOnlyNotRegistered() {
        val reading = ProposalReadingBuilder.build(0, listOf(readGroup1()))
        val plan = plan24.map { if (it.itemNumber == 5) it.copy(selected = false) else it } + item(99, 1.0) + item(12, 0.0).copy(itemNumber = 98)
        val pre = ProposalReadingRules.preselect(plan, reading, meEpp = true)
        assertEquals((11..24).toList(), pre.filter { it.selected }.map { it.itemNumber })
        assertEquals((10..24).toList(), ProposalReadingRules.preselect(plan, reading, true, updateDifferent = true).filter { it.selected }.map { it.itemNumber })
        assertEquals("✓ lançado (R$ 209,25)", ProposalReadingRules.marker(item(1, 209.25, 12.0), reading, true)!!.label)
        assertEquals("⚠ diferente (portal R$ 208,25 × plano R$ 209,25)", ProposalReadingRules.marker(item(10, 209.25, 12.0), reading, true)!!.label)
        assertEquals("○ não cadastrado", ProposalReadingRules.marker(item(11, 209.25, 12.0), reading, true)!!.label)
        assertEquals(PortalMarker.Kind.AUSENTE, ProposalReadingRules.marker(item(99, 1.0), reading, true)!!.kind)
        assertNull(ProposalReadingRules.marker(item(1, 1.0), null, true))
        assertTrue(ProposalReadingRules.summary(plan24, reading, true).contains("9 lançado(s) · 1 com valor diferente · 14 não cadastrado(s)"))
    }

    /** Scripts: o chevron do GRUPO ignora as setas dos itens de dentro do cartão; abrir/fechar só o grupo pedido. */
    @Test fun scriptsUseOwnGroupChevronAndReadItemsInsideGroup() {
        assertTrue(SpaScripts.SPA_PRELUDE.contains("SP.mine=function(c,e)"))
        assertTrue(SpaScripts.SPA_PRELUDE.contains("if(!SP.mine(c,b)"))
        assertTrue(SpaScripts.setGroupOpen("GRUPO 1", true).contains("SP.groupOpen(h,hs,gi)===P.open"))
        assertTrue(SpaScripts.collapseGroup("GRUPO 1").contains("\"open\":false"))
        assertTrue(SpaScripts.groupItemCards("GRUPO 1").contains("SP.inGroup(c,hs,gi)"))
    }

    @Test fun scriptsTreatGroupCardsAsNonItemsAndReexpandAfterSave() {
        assertTrue(SpaScripts.SPA_PRELUDE.contains("SP.itemNo=function(c){if(SP.isGroupText(SP.T(c)))return -1;"))
        val expand = SpaScripts.expandGroups()
        assertTrue(expand.contains("aria-expanded"))
        assertTrue(expand.contains("data-lz-open-at"))
        assertTrue(SpaScripts.groupsInfo().contains("perItemSave"))
    }
}
