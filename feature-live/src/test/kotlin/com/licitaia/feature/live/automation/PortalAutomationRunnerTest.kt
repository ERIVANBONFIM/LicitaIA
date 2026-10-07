package com.licitaia.feature.live.automation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalAutomationRunnerTest {

    /** Página falsa: cada elemento tem as "assinaturas" pelas quais pode ser achado. */
    private class FakePage(var url: String = "https://cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras/12345") : PageDriver {
        data class El(val css: String?, val keys: Set<String>, var value: String = "", val count: Int = 1)
        val elements = mutableListOf<El>()
        var probeResult = PageProbe(url = url)
        var appearAfterCalls = 0
        var findCalls = 0
        val clicked = mutableListOf<String?>()
        var acceptValue: (String) -> String = { it }

        private fun match(q: ElementQuery): Pair<Int, El>? {
            findCalls++
            if (findCalls <= appearAfterCalls) return null
            q.locators.forEachIndexed { i, l ->
                val key = if (l.kind == LocatorKind.CSS) "css:${l.value}" else "${l.kind}:${TextNorm.norm(l.value)}"
                elements.firstOrNull { e -> key in e.keys || (l.kind == LocatorKind.CSS && e.css == l.value) }?.let { return i to it }
            }
            return null
        }

        override suspend fun currentUrl() = url
        override suspend fun find(query: ElementQuery): FindResult = match(query)?.let { (i, e) -> FindResult(true, i, e.count, e.css, "", "input") } ?: FindResult(false)
        override suspend fun fill(query: ElementQuery, value: String): ActionResult {
            val (i, e) = match(query) ?: return ActionResult(false, error = "não encontrado")
            if (query.critical && e.count > 1) return ActionResult(false, FindResult(true, i, e.count), error = "ambiguous")
            e.value = acceptValue(value)
            return ActionResult(true, FindResult(true, i, e.count, e.css), readBack = e.value)
        }
        override suspend fun click(query: ElementQuery): ActionResult {
            val (i, e) = match(query) ?: return ActionResult(false, error = "não encontrado")
            clicked += e.css
            return ActionResult(true, FindResult(true, i, e.count, e.css))
        }
        override suspend fun read(query: ElementQuery): String? = match(query)?.second?.value
        override suspend fun probe() = probeResult
        override suspend fun rows(scopeText: String?) = emptyList<String>()
        override suspend fun tablesHtml() = ""
        override suspend fun snapshot() = "{}"
    }

    private var now = 0L
    private fun runner(page: FakePage, learned: LearnedSelectors = LearnedSelectors()) =
        PortalAutomationRunner(page, learned, clock = { now }, sleep = { now += it }, pollMs = 500)

    private val price = Target("t.preco", "valor unitário", ElementKind.INPUT, listOf(Locator(LocatorKind.LABEL, "Valor unitário")), timeoutMs = 3_000, critical = true)

    @Test fun findsByLabelAndLearnsStableCss() = runTest {
        val page = FakePage().apply { elements += FakePage.El("input[formcontrolname=\"valorUnitario\"]", setOf("LABEL:valor unitario")) }
        val learned = LearnedSelectors()
        val r = runner(page, learned).fill(price, "1234,56", ValueMatch::number)
        assertTrue(r.ok)
        val pageKey = LearnedSelectors.pageKey(page.url)
        assertEquals("input[formcontrolname=\"valorUnitario\"]", learned.get(pageKey, "t.preco"))
        assertEquals("cnetmobile.estaleiro.serpro.gov.br/comprasnet-web/seguro/fornecedor/compras/:n", pageKey)
    }

    @Test fun usesLearnedSelectorFirstAndFallsBackWhenItBreaks() = runTest {
        val page = FakePage().apply { elements += FakePage.El("#novo", setOf("LABEL:valor unitario")) }
        val learned = LearnedSelectors()
        learned.learn(LearnedSelectors.pageKey(page.url), "t.preco", "#antigo", 0)
        val r = runner(page, learned).fill(price, "10,00", ValueMatch::number)
        assertTrue(r.ok)
        // O seletor antigo não achou nada: o candidato por rótulo achou e o mapa foi atualizado.
        assertEquals("#novo", learned.get(LearnedSelectors.pageKey(page.url), "t.preco"))
    }

    @Test fun criticalAmbiguityStops() = runTest {
        val page = FakePage().apply { elements += FakePage.El("#x", setOf("LABEL:valor unitario"), count = 3) }
        val r = runner(page).fill(price, "10,00")
        assertTrue(r is StepOutcome.Ambiguous)
        assertEquals(3, (r as StepOutcome.Ambiguous).count)
    }

    @Test fun waitsForLateElementWithinTimeout() = runTest {
        val page = FakePage().apply { elements += FakePage.El("#p", setOf("LABEL:valor unitario")); appearAfterCalls = 3 }
        assertTrue(runner(page).waitFor(price).ok)
        assertTrue(now in 1..3_000)
    }

    @Test fun timeoutClassifiesCaptchaLoggedOutAndNotFound() = runTest {
        val captcha = FakePage().apply { probeResult = PageProbe(url = url, hasCaptcha = true) }
        val r1 = runner(captcha).click(price)
        assertTrue(r1 is StepOutcome.Blocked && r1.state == PageState.CAPTCHA)

        val out = FakePage(url = "https://www.gov.br/compras/pt-br").apply { probeResult = PageProbe(url = "https://www.gov.br/compras/pt-br") }
        val r2 = runner(out).click(price)
        assertTrue(r2 is StepOutcome.Blocked && r2.state == PageState.LOGGED_OUT)

        val plain = FakePage()
        val r3 = runner(plain).click(price)
        assertTrue(r3 is StepOutcome.NotFound)
        assertFalse(r3.ok)
    }

    @Test fun fillVerifiesReadBack() = runTest {
        val page = FakePage().apply { elements += FakePage.El("#p", setOf("LABEL:valor unitario")); acceptValue = { "1,00" } }
        val r = runner(page).fill(price, "1234,56", ValueMatch::number)
        assertTrue(r is StepOutcome.Failed)
    }

    @Test fun unstableCssIsNotLearned() = runTest {
        val page = FakePage().apply { elements += FakePage.El("#mat-input-12", setOf("LABEL:valor unitario")) }
        val learned = LearnedSelectors()
        assertTrue(runner(page, learned).fill(price, "1,00", ValueMatch::number).ok)
        assertNull(learned.get(LearnedSelectors.pageKey(page.url), "t.preco"))
    }

    @Test fun pageClassifier() {
        val portal = com.licitaia.domain.model.Portal.COMPRAS_GOV
        assertEquals(PageState.UNAUTHORIZED, PortalPageClassifier.classify(portal, PageProbe(url = "https://cnetmobile.estaleiro.serpro.gov.br/x", text = "nao autorizado sua sessao pode ter expirado")))
        assertEquals(PageState.MFA, PortalPageClassifier.classify(portal, PageProbe(url = "https://cnetmobile.estaleiro.serpro.gov.br/x", text = "informe o codigo de verificacao")))
        assertEquals(PageState.LOGGED_OUT, PortalPageClassifier.classify(portal, PageProbe(url = "https://sso.acesso.gov.br/login")))
        assertEquals(PageState.OK, PortalPageClassifier.classify(portal, PageProbe(url = "https://www.comprasnet.gov.br/intro.htm", text = "area de trabalho")))
    }
}
