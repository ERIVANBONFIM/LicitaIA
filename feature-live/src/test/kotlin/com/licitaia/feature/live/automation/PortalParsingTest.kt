package com.licitaia.feature.live.automation

import com.licitaia.domain.portal.PortalMyTender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Parsing genérico (tabelas, texto de compra) e HEURÍSTICO da sala de disputa/chat (telas ainda não mapeadas). A lista
 * "Compras eletrônicas" e o cadastro de proposta (telas REAIS) são testados em [PortalSpaRealScreensTest].
 */
class PortalParsingTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test fun htmlTablesSeparatesNestedTablesAndDecodesEntities() {
        val tables = HtmlTables.parse("<table><tr><td>fora<table><tr><th>A&ccedil;&atilde;o</th><td>R$&nbsp;1,00</td></tr></table></td></tr></table>")
        val inner = tables.first { t -> t.rows.any { r -> r.cells.contains("Ação") } }
        assertEquals(listOf("Ação", "R$ 1,00"), inner.rows.single().cells)
        assertFalse(inner.rows.single().header)
    }

    @Test fun purchaseTextRefAndDateTime() {
        val r = PurchaseText.ref("UASG 160123 · Pregão Eletrônico 90005/2026 · abertura 15/10/2026 09:00")
        assertEquals("160123", r.uasg); assertEquals("90005", r.number); assertEquals(2026, r.year)
        val opening = Instant.ofEpochMilli(PurchaseText.dateTime("15/10/2026 09:00")!!).atZone(zone)
        assertEquals(15, opening.dayOfMonth); assertEquals(9, opening.hour)
    }

    @Test fun bidRoomRowsAndAmbiguity() {
        val rows = listOf(
            "Item 1 | Roteador | Melhor lance: R$ 1.234,50 | Seu último lance: R$ 1.250,00 | 2º lugar | Em disputa",
            "Item 2 | Switch | Melhor lance R$ 900,00 | Melhor valor R$ 880,00 | Aberto para lances",
            "Item 3 | Cabo | Encerrado",
        )
        val items = BidRoomParser.parse(rows)
        val i1 = items.first { it.itemNumber == 1 }
        assertEquals(1234.5, i1.bestBid!!, 1e-9)
        assertEquals(1250.0, i1.ourBid!!, 1e-9)
        assertEquals(2, i1.position)
        assertEquals(DisputePhase.OPEN, i1.phase)
        assertFalse(i1.ambiguous)
        assertTrue(items.first { it.itemNumber == 2 }.ambiguous)
        assertEquals(DisputePhase.CLOSED, items.first { it.itemNumber == 3 }.phase)
    }

    @Test fun phaseClassification() {
        assertEquals(DisputePhase.SUSPENDED, PortalPageClassifier.phase("Sessão suspensa pelo pregoeiro"))
        assertEquals(DisputePhase.RANDOM, PortalPageClassifier.phase("Item em tempo aleatório"))
        assertEquals(DisputePhase.WAITING, PortalPageClassifier.phase("Aguardando abertura da disputa"))
        assertEquals(DisputePhase.UNKNOWN, PortalPageClassifier.phase("Roteador 24 portas"))
    }

    @Test fun chatMessagesUrgencyAndDirection() {
        val rows = listOf(
            "Pregoeiro | 07/10/2026 10:15:32 | Sr. Fornecedor, encaminhe o anexo da proposta no prazo de 2 horas.",
            "Sistema | 10:16 | O item 1 está em tempo aleatório.",
            "Item 1 | Melhor lance R$ 10,00",
        )
        val msgs = ChatParser.parse(rows)
        assertEquals(2, msgs.size)
        assertTrue(msgs[0].urgent && msgs[0].directed)
        assertEquals("Pregoeiro", msgs[0].sender)
        assertNotNull(msgs[0].at)
        assertFalse(msgs[1].urgent)
        assertEquals(msgs[0].dedupKey("k"), ChatParser.parse(rows.take(1)).single().dedupKey("k"))
    }

    @Test fun moneyParsingAndFormatting() {
        assertEquals(1234.56, TextNorm.parseMoney("R$ 1.234,56")!!, 1e-9)
        assertEquals(1234.56, TextNorm.parseMoney("1234,56")!!, 1e-9)
        assertEquals(1234.56, TextNorm.parseMoney("1,234.56")!!, 1e-9)
        assertEquals(12.5, TextNorm.parseMoney("12.5")!!, 1e-9)
        assertEquals(1234.0, TextNorm.parseMoney("1.234")!!, 1e-9)
        assertNull(TextNorm.parseMoney("sem valor"))
        assertEquals("1234,50", TextNorm.formatInputMoney(1234.5))
        assertEquals("10", TextNorm.formatInputNumber(10.0))
        assertEquals("2,5", TextNorm.formatInputNumber(2.5))
        assertTrue(ValueMatch.number("1234,50", "R$ 1.234,50"))
        assertFalse(ValueMatch.number("1234,50", "1.234,00"))
    }

    @Test fun sanitizerMasksPersonalDataAndTokens() {
        val s = SnapshotSanitizer.clean("CNPJ 12.345.678/0001-90 cpf 123.456.789-09 a@b.com.br (61) 99999-1234 token eyJhbGciOi.eyJzdWIi.abc12 id 0123456789abcdef0123456789abcdef99")
        assertFalse(s.contains("12.345.678"))
        assertFalse(s.contains("123.456.789"))
        assertFalse(s.contains("a@b.com.br"))
        assertFalse(s.contains("99999-1234"))
        assertFalse(s.contains("eyJhbGciOi"))
        assertFalse(s.contains("0123456789abcdef0123456789abcdef99"))
    }
}
