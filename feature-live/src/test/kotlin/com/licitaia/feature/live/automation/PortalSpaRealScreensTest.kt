package com.licitaia.feature.live.automation

import com.licitaia.domain.model.Portal
import com.licitaia.domain.model.PortalConnectionStatus
import com.licitaia.domain.portal.PortalMyTender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * Telas REAIS do Compras.gov.br: as fixtures em `src/test/resources/fixtures/real/REAL_*.txt` são os dumps do DevTools
 * feitos no aparelho logado (dados pessoais do fornecedor substituídos). Os cartões são recortados do texto visível
 * (linha "TEXTO:") e o coração preenchido vem das linhas `div .row cp-itens-card ... icon.fa-heart fas`.
 */
class PortalSpaRealScreensTest {
    private fun real(name: String) = javaClass.classLoader!!.getResource("fixtures/real/$name")!!.readText()
    private fun texto(dump: String) = dump.lines().first { it.startsWith("TEXTO: ") }.removePrefix("TEXTO: ")
    private val cardStart = Regex("""(?=(PREGÃO ELETRÔNICO|DISPENSA ELETRÔNICA|CONCORRÊNCIA ELETRÔNICA) N°)""")
    private val zone = ZoneId.of("America/Sao_Paulo")

    /** Cartões da lista: texto (como o innerText do `div.cp-itens-card`) + favorita. */
    private fun cards(dump: String): List<SpaCard> {
        val favs = dump.lines().filter { it.startsWith("div .row cp-itens-card") }.map { it.contains("icon.fa-heart fas") }
        val parts = texto(dump).split(cardStart).drop(1).map { it.replace(Regex("""\s+1 2( \d)*\s+FORNECEDOR.*$"""), "").trim() }
        return parts.take(favs.size).mapIndexed { i, t -> SpaCard(t, favs[i]) }
    }

    // ------------------------------------------------------------------ lista "Minhas participações"

    @Test fun minhasParticipacoesCardsReal() {
        val dump = real("REAL_01_minhas_participacoes.txt")
        val cs = cards(dump)
        assertEquals(10, cs.size)
        assertEquals(10 to 16, SpaPurchaseParser.showing(texto(dump)))
        val ps = cs.map { SpaPurchaseParser.parse(it) }
        assertTrue(ps.all { it != null })

        val d = ps[0]!!
        assertEquals("Dispensa Eletrônica", d.modality)
        assertEquals("3", d.number); assertEquals(2025, d.year); assertEquals("389243", d.uasg)
        assertEquals("CONSELHO REGIONAL DE ODONTOLOGIA/TO", d.agency)
        assertEquals("Seleção de fornecedores", d.stage)
        assertNull(d.status); assertFalse(d.favorite); assertFalse(d.meEpp)

        val suspensa = ps[1]!!
        assertTrue(suspensa.meEpp)
        assertEquals("103", suspensa.number); assertEquals("929185", suspensa.uasg)
        assertEquals("CONSELHO REGIONAL DE NUTRICAO 11ª REGIAO/CE", suspensa.agency)
        assertEquals("COMPRA SUSPENSA", suspensa.status)
        assertEquals("Compra suspensa", SpaPurchaseParser.situation(suspensa))

        val anulada = ps[2]!!
        assertEquals("Pregão Eletrônico", anulada.modality)
        assertEquals("90005", anulada.number)
        assertEquals("MPR-CAMARA MUNICIPAL DE RIO NEGRO", anulada.agency)
        assertEquals("Menor Preço / Maior Desconto", anulada.criterion)
        assertEquals("COMPRA ANULADA", anulada.status)
        assertEquals("COMPRA REVOGADA", ps[3]!!.status)

        val agendada = ps[5]!!
        assertEquals("90052", agendada.number); assertEquals("983847", agendada.uasg)
        assertTrue(agendada.favorite)
        assertEquals("Início: Disputa agendada Sem prazo definido", agendada.stage)
        assertEquals("Disputa agendada · sem prazo definido · Favorita", SpaPurchaseParser.situation(agendada))

        val sesc = ps[9]!!
        assertEquals("10", sesc.number); assertEquals(2026, sesc.year); assertEquals("926483", sesc.uasg)
        assertEquals("SERVICO SOCIAL DO COMERCIO - SESC AR/MG", sesc.agency)
        assertTrue(sesc.favorite)
        assertEquals(listOf(false, false, false, false, false, true, false, false, false, true), ps.map { it!!.favorite })
    }

    @Test fun minhasParticipacoesToMyTendersReal() {
        val list = SpaPurchaseParser.toMyTenders(cards(real("REAL_01_minhas_participacoes.txt")), 7L, PortalMyTender.SOURCE_PARTICIPACOES, participation = true, now = 5L)
        assertEquals(10, list.size)
        val t = list.first { it.number == "90046" }
        assertEquals("980786-90046-2025", t.tenderKey)
        assertEquals("980786", t.uasg)
        assertEquals("Pregão Eletrônico", t.modality)
        assertTrue(t.objectDescription.startsWith("PREFEITURA MUNICIPAL DE SEROPÉRDICA - RJ"))
        assertEquals("Seleção de fornecedores", t.situation)
        assertTrue(t.hasProposal)
        assertEquals(setOf(PortalMyTender.SOURCE_PARTICIPACOES), t.sources)
        assertTrue(list.first { it.number == "10" }.situation.endsWith("Favorita"))
        assertEquals("Compra revogada", list.first { it.number == "90007" }.situation)
    }

    @Test fun todasAsComprasCardsReal() {
        val cs = cards(real("REAL_02_todas_as_compras.txt"))
        val ps = cs.mapNotNull { SpaPurchaseParser.parse(it) }
        assertTrue(ps.size >= 8)
        val fav = ps[0]
        assertTrue(fav.favorite)
        assertEquals("7", fav.number); assertEquals(2022, fav.year); assertEquals("170162", fav.uasg)
        assertTrue(fav.meEpp)
        assertEquals("Proposta até 10/05/2030 07:59", SpaPurchaseParser.stageLabel(fav.stage))
        val t = SpaPurchaseParser.toMyTenders(listOf(cs[0]), 1L, PortalMyTender.SOURCE_PARTICIPACOES, false, 0L).single()
        val at = Instant.ofEpochMilli(t.openingAt!!).atZone(zone)
        assertEquals(2030, at.year); assertEquals(7, at.hour); assertEquals(59, at.minute)
        // UASG de 5 dígitos: chave com 6.
        val esp = ps.first { it.uasg == "90137" }
        assertEquals("090137", esp.uasg6)
        assertEquals("ESP-SECRETARIA DA SAUDE", esp.agency)
        assertEquals("COMPRA ANULADA", esp.status)
        assertEquals("Concorrência Eletrônica", ps.first { it.uasg == "154054" }.modality)
    }

    // ------------------------------------------------------------------ código compra= e busca

    @Test fun compraCodeReal() {
        val url = real("REAL_03_cadastro_propostas.txt").lines().first { it.startsWith("URL: ") }.removePrefix("URL: ")
        assertEquals("92731205000032026", CompraCode.of("927312", "Pregão Eletrônico", "3", 2026))
        assertEquals("92731205000032026", CompraCode.fromUrl(url))
        assertTrue(CompraCode.isProposalPageFor(url, "927312", "Pregão Eletrônico", "3", 2026))
        assertTrue(CompraCode.isProposalPageFor(url, "927312", "", "3", 2026)) // modalidade desconhecida: UASG+número+ano
        assertFalse(CompraCode.isProposalPageFor(url, "927312", "Pregão Eletrônico", "4", 2026))
        assertFalse(CompraCode.isProposalPageFor(url, "927312", "Dispensa Eletrônica", "3", 2026))
        assertFalse(CompraCode.isProposalPageFor("https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra=", "927312", "Pregão", "3", 2026))
        assertEquals("09013705000012023", CompraCode.of("90137", "PREGÃO ELETRÔNICO", "1", 2023))
        assertEquals("32026", CompraCode.searchNumber("3", 2026))
        assertEquals("900462025", CompraCode.searchNumber("90046", 2025))
        assertNull(CompraCode.of("927312", "Leilão", "3", 2026))
    }

    // ------------------------------------------------------------------ valor para a máscara de moeda

    @Test fun moneyForCurrencyMask() {
        assertEquals("150999,99", ProposalMoney.typed(150999.99))
        assertEquals("1234,50", ProposalMoney.typed(1234.5))
        assertEquals("0,30", ProposalMoney.typed(0.1 + 0.2))
        assertEquals("151000,00", ProposalMoney.typed(151000.0))
        // Campo da máscara (4 casas, milhar com ponto) × valor pretendido.
        assertTrue(ProposalMoney.matches(150999.99, "150.999,9900"))
        assertTrue(ProposalMoney.matches(150999.99, "R$ 150.999,99"))
        // Armadilha real: dígitos sem vírgula viram parte inteira.
        assertFalse(ProposalMoney.matches(150999.99, "15.099.999,0000"))
        assertFalse(ProposalMoney.matches(150999.99, "150.999,9800"))
        assertFalse(ProposalMoney.matches(150999.99, ""))
        assertEquals(BigDecimal("150999.9900"), ProposalMoney.parse("R$ 150.999,9900"))
    }

    // ------------------------------------------------------------------ termo/declarações (só leitura)

    private fun radio(dump: String, id: String): Boolean {
        val line = dump.lines().first { it.contains("id=$id ") || it.contains("id=$id\t") }
        Regex("""checked=(true|false)""").find(line)?.let { return it.groupValues[1] == "true" }
        return line.trim().endsWith(":: true")
    }

    private fun declarationsFrom(dump: String, termAccepted: Boolean?, text: String = texto(dump)): DeclarationsState {
        val groups = listOf(
            Triple("meepp", "ME/EPP", "Meepp"),
            Triple("equidade", "Equidade", "EquidadeGenero"),
            Triple("integridade", "Integridade", "ProgramasIntegridade"),
        ).map { (k, l, id) ->
            val (sim, nao) = if (k == "meepp") "labelSimMeepp" to "labelNaoMeepp" else "declaracao${id}Sim" to "declaracao${id}Nao"
            DeclarationsState.Group(k, l, radio(dump, sim), radio(dump, nao))
        }
        return DeclarationsState(
            section = text.contains("Termo/declarações"), warning = text.contains("É necessário o aceite do termo"),
            termAccepted = termAccepted, groups = groups, modalOpen = false,
        )
    }

    @Test fun declarationsPendingReal() {
        val dump = real("REAL_04_declaracoes_pendentes.txt")
        val termo = Regex("""INPUT id= .*type=checkbox .*checked=(true|false)""").find(dump)!!.groupValues[1] == "true"
        // Rádios/termo do dump 04 (controles) + texto visível do dump 03 (mesma tela, antes do aceite).
        val s = declarationsFrom(dump, termo, text = texto(real("REAL_03_cadastro_propostas.txt")))
        assertTrue(s.section)
        assertTrue(s.warning)
        assertEquals(false, s.termAccepted)
        val missing = DeclarationsCheck.missing(s)
        assertTrue(missing.any { it.contains("Termo de Aceitação") })
        assertEquals(3, missing.count { it.startsWith("escolha Sim/Não") })
        assertFalse(DeclarationsCheck.canProceed(s, userConfirmed = false))
        // Mesmo depois de "Continuar", com termo/rádios em branco o robô NÃO segue.
        assertFalse(DeclarationsCheck.canProceed(s, userConfirmed = true))
    }

    @Test fun declarationsFilledReal() {
        val dump = real("REAL_06_declaracoes_preenchidas.txt")
        // Depois do aceite o aviso some; o termo marcado é inferido disso (o dump não traz o estado do checkbox).
        val s = declarationsFrom(dump, termAccepted = !texto(dump).contains("É necessário o aceite do termo"))
        assertFalse(s.warning)
        assertTrue(s.groups.all { it.answered })
        assertTrue(s.groups.first { it.key == "meepp" }.yes)
        assertTrue(s.groups.first { it.key == "equidade" }.no)
        assertTrue(DeclarationsCheck.missing(s).isEmpty())
        assertTrue(DeclarationsCheck.canProceed(s, userConfirmed = false))
        // Modal do termo aberto: não segue.
        assertFalse(DeclarationsCheck.canProceed(s.copy(modalOpen = true), userConfirmed = true))
        // Grupo sem resposta: não segue.
        assertFalse(DeclarationsCheck.canProceed(s.copy(groups = s.groups.map { it.copy(yes = false, no = false) }), userConfirmed = true))
        // Compra sem bloco de declarações: só depois da confirmação do usuário.
        val none = DeclarationsState(false, false, null, emptyList(), false)
        assertFalse(DeclarationsCheck.canProceed(none, userConfirmed = false))
        assertTrue(DeclarationsCheck.canProceed(none, userConfirmed = true))
    }

    @Test fun declarationsJsonFromPage() {
        val raw = "\"{\\\"section\\\":true,\\\"warning\\\":false,\\\"termo\\\":true,\\\"modal\\\":false,\\\"groups\\\":[{\\\"k\\\":\\\"meepp\\\",\\\"l\\\":\\\"ME/EPP\\\",\\\"s\\\":true,\\\"n\\\":false}]}\""
        val s = DeclarationsCheck.parse(raw)!!
        assertEquals(true, s.termAccepted)
        assertTrue(DeclarationsCheck.canProceed(s, false))
        assertNull(DeclarationsCheck.parse("{\"section\":true,\"termo\":null,\"groups\":[]}")!!.termAccepted)
    }

    // ------------------------------------------------------------------ itens: cartão, salvar, seguir/parar

    @Test fun itemCardsReal() {
        val before = texto(real("REAL_03_cadastro_propostas.txt")).substringAfter("Itens ").substringBefore(" 2 ACESSO")
        val c1 = ProposalItemCardParser.parse(before)!!
        assertEquals(1, c1.number)
        assertTrue(c1.noProposal)
        assertNull(c1.myUnitValue)
        assertEquals(BigDecimal("151000.0000"), c1.estimatedUnitValue)

        val saved = texto(real("REAL_08_item_salvo.txt"))
        val c2 = ProposalItemCardParser.parse(saved)!!
        assertEquals(1, c2.number)
        assertFalse(c2.noProposal)
        assertEquals(BigDecimal("150999.9900"), c2.myUnitValue)
        assertEquals(BigDecimal("151000.0000"), c2.estimatedUnitValue)
    }

    @Test fun saveDecisionReal() {
        val toastLine = real("REAL_07_apos_salvar.txt").lines().first { it.startsWith("aviso: ") }.removePrefix("aviso: ")
        val toast = PortalToast.of(toastLine.substringBefore(" :: "), toastLine.substringAfter(" :: "))
        assertEquals("success", toast.severity)
        assertEquals("Operação realizada com sucesso!", toast.text)
        val card = ProposalItemCardParser.parse(texto(real("REAL_08_item_salvo.txt")))

        assertEquals(ProposalSaveCheck.Outcome.Ok, ProposalSaveCheck.evaluate(150999.99, listOf(toast), card, timedOut = false))
        // Sem toast (já sumiu), mas o cartão mostra o valor: ok.
        assertEquals(ProposalSaveCheck.Outcome.Ok, ProposalSaveCheck.evaluate(150999.99, emptyList(), card, timedOut = false))
        // Sucesso com valor divergente: para.
        assertTrue(ProposalSaveCheck.evaluate(140000.0, listOf(toast), card, timedOut = false) is ProposalSaveCheck.Outcome.Stop)
        // Erro do portal: para mesmo com o cartão certo.
        val err = PortalToast.of("p-toast-message p-toast-message-error", "Valor acima do estimado")
        val stop = ProposalSaveCheck.evaluate(150999.99, listOf(err), card, timedOut = false)
        assertTrue(stop is ProposalSaveCheck.Outcome.Stop && stop.reason.contains("Valor acima do estimado"))
        // Ainda sem resposta: espera; no tempo-limite: para.
        val pending = ProposalItemCardParser.parse("1 ACESSO A INTERNET VIA CABO Valor estimado (unitário) R$ 151.000,0000 Proposta não cadastrada")
        assertEquals(ProposalSaveCheck.Outcome.Wait, ProposalSaveCheck.evaluate(150999.99, emptyList(), pending, timedOut = false))
        assertTrue(ProposalSaveCheck.evaluate(150999.99, emptyList(), pending, timedOut = true) is ProposalSaveCheck.Outcome.Stop)
    }

    // ------------------------------------------------------------------ scripts e leitura da página

    @Test fun spaScriptsSafety() {
        val all = listOf(
            SpaScripts.state(), SpaScripts.clickTab("Minhas participações"), SpaScripts.selectFilter("Em andamento"), SpaScripts.nextPage(),
            SpaScripts.focusSearch("uasg"), SpaScripts.searchButton(false), SpaScripts.purchase("927312", "3", 2026, true),
            SpaScripts.declarations(), SpaScripts.items(), SpaScripts.prepareItem(1, "t"), SpaScripts.toggleItem(1),
            SpaScripts.claimItemForm(1, "t"), SpaScripts.itemField(1, "valor", true), SpaScripts.saveItem(1), SpaScripts.itemStatus(1),
        )
        all.forEach { s ->
            assertFalse(s.contains("document.cookie")); assertFalse(s.contains("localStorage")); assertFalse(s.contains("sessionStorage"))
            assertFalse(s.contains("fetch(")); assertFalse(s.contains("XMLHttpRequest"))
            assertTrue(s.startsWith("(function(){try{"))
        }
        fun body(s: String) = s.substringAfter("var P=")
        // Declarações: só leitura (nenhum clique, nenhum checked=).
        assertFalse(body(SpaScripts.declarations()).contains("click"))
        assertFalse(body(SpaScripts.declarations()).contains("checked="))
        // A seta exclui lixeira e coração; salvar só o botão "Salvar".
        assertTrue(SpaScripts.SPA_PRELUDE.contains("fa-trash-alt"))
        assertTrue(body(SpaScripts.saveItem(1)).contains("fm.save"))
        // Parâmetros como literal JSON.
        assertTrue(SpaScripts.purchase("090137", "0003", 2026, false).contains("\"uasg\":\"90137\""))
        assertTrue(SpaScripts.clickTab("a'); alert(1); ('").contains("\"label\":\"a'); alert(1); ('\""))
    }

    @Test fun parsesSpaState() {
        val raw = "{\"url\":\"https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras?compra=\",\"tabs\":[\"Minhas participações\",\"Todas as compras\"],\"active\":\"Minhas participações\"," +
            "\"cards\":[{\"t\":\"PREGÃO ELETRÔNICO N° 10/2026 926483 - SESC Etapa: Seleção de fornecedores\",\"fav\":true}],\"showing\":\"Exibindo 10 de 16\",\"next\":true,\"page\":\"1\",\"filter\":\"Em andamento\"," +
            "\"toasts\":[{\"c\":\"p-toast-message p-toast-message-success\",\"t\":\"Operação realizada com sucesso!\"}],\"search\":false}"
        val s = SpaScripts.parseState(raw)!!
        assertTrue(s.isComprasList)
        assertTrue(s.tabActive("minhas participacoes"))
        assertTrue(s.cards.single().favorite)
        assertTrue(s.hasNext)
        assertEquals("success", s.toasts.single().severity)
        assertNotNull(SpaPurchaseParser.parse(s.cards.single()))
    }

    // ------------------------------------------------------------------ selo Logado/Deslogado

    @Test fun loginBadgeFollowsLastRealCheck() {
        val portal = Portal.COMPRAS_GOV
        val failed = PortalLoginCheck(1, logged = false, at = 2_000)
        assertFalse(PortalLoginBadge.logged(PortalConnectionStatus.CONECTADO, lastLoginAt = 1_000, check = failed))
        // Login feito DEPOIS da leitura que falhou: vale o status.
        assertTrue(PortalLoginBadge.logged(PortalConnectionStatus.CONECTADO, lastLoginAt = 3_000, check = failed))
        assertTrue(PortalLoginBadge.logged(PortalConnectionStatus.CONECTADO, 1_000, null))
        assertFalse(PortalLoginBadge.logged(PortalConnectionStatus.SESSAO_EXPIRADA, 1_000, null))
        assertTrue(PortalLoginBadge.isLoggedOutUrl(portal, "https://www.comprasnet.gov.br/acessoNegado.htm"))
        assertFalse(PortalLoginBadge.isLoggedOutUrl(portal, null))
    }
}
