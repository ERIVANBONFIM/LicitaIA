package com.licitaia.feature.live.automation

import com.licitaia.domain.portal.BidRobotConfig
import com.licitaia.domain.portal.BidRobotMode
import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalRobotPlan
import com.licitaia.domain.portal.ProposalItemPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationScriptsTest {
    private val q = ElementQuery(ElementKind.INPUT, listOf(Locator(LocatorKind.LABEL, "Valor \"unitário\" </script>")), scopeText = "item 1", critical = true)

    @Test fun parametersAreEmbeddedAsJsonLiterals() {
        val s = AutomationScripts.fill(q, "1.234,56'); alert(1); ('")
        // Nada de interpolação de string Kotlin perdida, nem separador de linha cru (quebra literal JS).
        assertFalse(s.contains("\${"))
        assertFalse(s.contains(" "))
        assertTrue(s.contains("\\\"unit\\u00e1rio\\\"") || s.contains("\\\"unitário\\\""))
        // O valor vai como string JSON (aspas duplas), nunca concatenado como código.
        assertTrue(s.contains("V=\"1.234,56'); alert(1); ('\""))
        assertTrue(s.contains("\"critical\":true"))
        assertTrue(s.startsWith("(function(){try{"))
    }

    @Test fun scriptsNeverTouchCookiesOrStorage() {
        listOf(
            AutomationScripts.find(q), AutomationScripts.click(q), AutomationScripts.read(q), AutomationScripts.probe(),
            AutomationScripts.rows(null), AutomationScripts.tablesHtml(), AutomationScripts.snapshot(),
        ).forEach { s ->
            assertFalse(s.contains("document.cookie"))
            assertFalse(s.contains("localStorage"))
            assertFalse(s.contains("sessionStorage"))
            assertFalse(s.contains("fetch("))
            assertFalse(s.contains("XMLHttpRequest"))
        }
        // Tabelas e snapshot removem campos ocultos/senha e valores.
        assertTrue(AutomationScripts.tablesHtml().contains("input[type=hidden]"))
        assertTrue(AutomationScripts.tablesHtml().contains("removeAttribute('value')"))
        val snapshotBody = AutomationScripts.snapshot().substringAfter(AutomationScripts.PRELUDE)
        assertFalse(snapshotBody.contains(".value"))
        assertFalse(snapshotBody.contains("valOf"))
    }

    @Test fun parsesWebViewResults() {
        // evaluateJavascript devolve o JSON.stringify como string JSON (aspas escapadas).
        val raw = "\"{\\\"found\\\":true,\\\"i\\\":1,\\\"n\\\":2,\\\"css\\\":\\\"#preco\\\",\\\"text\\\":\\\"valor\\\",\\\"tag\\\":\\\"input\\\"}\""
        val r = AutomationJson.parseFind(raw)
        assertTrue(r.found); assertEquals(1, r.locatorIndex); assertEquals(2, r.count); assertEquals("#preco", r.css)
        assertFalse(AutomationJson.parseFind("null").found)
        assertFalse(AutomationJson.parseFind("lixo{").found)
        val action = AutomationJson.parseAction("{\"found\":true,\"ok\":true,\"res\":{\"i\":0,\"n\":1,\"css\":null},\"readBack\":\"1.234,56\"}")
        assertTrue(action.ok); assertEquals("1.234,56", action.readBack)
        val probe = AutomationJson.parseProbe("{\"url\":\"https://x\",\"text\":\"abc\",\"cap\":true,\"otp\":false,\"dialogs\":[\"d1\"]}")
        assertTrue(probe.hasCaptcha); assertEquals(listOf("d1"), probe.dialogs)
        assertEquals(listOf("a | b"), AutomationJson.parseRows("{\"rows\":[\"a | b\"]}"))
    }

    @Test fun learnedSelectorsPageKeyAndStability() {
        assertEquals("www.comprasnet.gov.br/assinadas/cotacao.asp", LearnedSelectors.pageKey("https://www.comprasnet.gov.br/assinadas/cotacao.asp?filtro=participou"))
        assertEquals("host/app/#/compras/:n/itens", LearnedSelectors.pageKey("https://host/app/#/compras/900052026/itens?x=1"))
        assertTrue(LearnedSelectors.isStableCss("input[formcontrolname=\"valor\"]"))
        assertFalse(LearnedSelectors.isStableCss("#mat-input-3"))
        assertFalse(LearnedSelectors.isStableCss(null))
        val l = LearnedSelectors()
        l.learn("p", "k", "#a", 1); l.learn("p", "k", "#a", 2)
        assertEquals(2, l.snapshot()[LearnedSelectors.key("p", "k")]!!.hits)
        l.forget("p", "k")
        assertEquals(null, l.get("p", "k"))
    }

    @Test fun planRulesValidateAndConfirm() {
        val ok = listOf(ProposalItemPlan(1, quantity = 2.0, unitPrice = 100.0, brand = "Marca X", floorUnitPrice = 80.0))
        assertTrue(RobotPlanRules.proposalErrors(ok).isEmpty())
        assertTrue(RobotPlanRules.proposalErrors(emptyList()).isNotEmpty())
        assertTrue(RobotPlanRules.proposalErrors(ok + ok).any { it.contains("repetido") })
        assertTrue(RobotPlanRules.proposalErrors(listOf(ok[0].copy(unitPrice = 0.0))).isNotEmpty())
        assertTrue(RobotPlanRules.proposalErrors(listOf(ok[0].copy(floorUnitPrice = 120.0))).isNotEmpty())

        val plan = PortalRobotPlan(1, "k", items = ok, bid = BidRobotConfig(mode = BidRobotMode.AUTOMATICO))
        assertTrue(RobotPlanRules.bidErrors(plan).isEmpty())
        assertTrue(RobotPlanRules.bidErrors(plan.copy(bid = plan.bid.copy(mode = BidRobotMode.DESLIGADO))).isNotEmpty())
        assertTrue(RobotPlanRules.bidErrors(plan.copy(items = listOf(ok[0].copy(floorUnitPrice = null)))).isNotEmpty())

        val t = PortalMyTender(1, "160123-90005-2026", uasg = "160123", number = "90005", year = 2026, modality = "Pregão Eletrônico",
            objectDescription = "Roteadores", openingAt = null, situation = "", hasProposal = false, sources = emptySet(), firstSeenAt = 0, updatedAt = 0)
        val text = RobotPlanRules.proposalConfirmation(t, ok)
        assertTrue(text.contains("UASG 160123 · 90005/2026"))
        assertTrue(text.contains("Item 1"))
        assertTrue(text.contains("NÃO são marcados"))
        assertNotNull(RobotPlanRules.bidConfirmation(t, plan).takeIf { it.contains("PISO") })
        assertEquals(30 * 60_000L, 1_000_000_000L - RobotPlanRules.prepareAt(1_000_000_000L))
    }
}
