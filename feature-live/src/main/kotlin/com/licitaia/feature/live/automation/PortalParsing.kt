package com.licitaia.feature.live.automation

import com.licitaia.domain.portal.PortalMyTender
import com.licitaia.domain.portal.PortalTenderMatching
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * Leitura (pura) de textos do Comprasnet: tabelas HTML genéricas, identificação de compra em texto, linhas da sala de
 * disputa e mensagens do chat (estes dois ainda HEURÍSTICOS por texto — sala/chat não mapeados no aparelho).
 * A lista "Compras eletrônicas" e o cadastro de proposta (telas REAIS mapeadas) estão em [SpaPurchaseParser] e afins
 * (PortalSpa.kt). As páginas legadas `cotacao.asp` (Cotação Eletrônica, Lei 8.666) não são mais lidas.
 */

/** Extrator tolerante de tabelas HTML (sem dependências): só texto das células, tabelas aninhadas separadas. */
object HtmlTables {
    data class Row(val cells: List<String>, val header: Boolean)
    data class Table(val rows: List<Row>)

    private val dropBlocks = Regex("""(?is)<!--.*?-->|<script\b.*?</script>|<style\b.*?</style>""")
    private val token = Regex("""<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9:-]*)[^>]*>|([^<]+)""")

    private class TB { val rows = mutableListOf<Row>(); var cells: MutableList<String>? = null; var cell: StringBuilder? = null; var header = false; var rowHeader = true
        fun closeCell() { cell?.let { c -> cells?.add(clean(c.toString())) }; cell = null }
        fun closeRow() { closeCell(); cells?.let { if (it.isNotEmpty()) rows += Row(it.toList(), rowHeader && it.isNotEmpty()) }; cells = null }
    }

    fun parse(html: String): List<Table> {
        val out = mutableListOf<Table>()
        val stack = ArrayDeque<TB>()
        for (m in token.findAll(html.replace(dropBlocks, " "))) {
            val text = m.groupValues[3]
            if (text.isNotEmpty()) { stack.lastOrNull()?.cell?.append(decode(text)); continue }
            val closing = m.groupValues[1] == "/"
            when (m.groupValues[2].lowercase()) {
                "table" -> if (!closing) stack.addLast(TB()) else stack.removeLastOrNull()?.let { it.closeRow(); if (it.rows.isNotEmpty()) out += Table(it.rows.toList()) }
                "tr" -> stack.lastOrNull()?.let { tb -> tb.closeRow(); if (!closing) { tb.cells = mutableListOf(); tb.rowHeader = true } }
                "td", "th" -> stack.lastOrNull()?.let { tb ->
                    tb.closeCell()
                    if (!closing) {
                        if (tb.cells == null) { tb.cells = mutableListOf(); tb.rowHeader = true }
                        tb.cell = StringBuilder()
                        if (m.groupValues[2].equals("td", true)) tb.rowHeader = false
                    }
                }
                "br", "p", "div", "li" -> stack.lastOrNull()?.cell?.append(' ')
            }
        }
        while (stack.isNotEmpty()) stack.removeLast().let { it.closeRow(); if (it.rows.isNotEmpty()) out += Table(it.rows.toList()) }
        return out
    }

    private val named = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "ordm" to "º", "ordf" to "ª", "deg" to "°",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "acirc" to "â", "ecirc" to "ê", "ocirc" to "ô",
        "atilde" to "ã", "otilde" to "õ", "ccedil" to "ç", "agrave" to "à", "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó",
        "Uacute" to "Ú", "Acirc" to "Â", "Ecirc" to "Ê", "Ocirc" to "Ô", "Atilde" to "Ã", "Otilde" to "Õ", "Ccedil" to "Ç", "Agrave" to "À",
    )
    private val entity = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
}

/** Identificação de uma compra extraída de um texto (linha de tabela/cartão). */
data class PurchaseRef(
    val uasg: String?,
    val number: String?,
    val year: Int?,
    val pncpControl: String?,
)

object PurchaseText {
    private val uasgLabeled = Regex("""(?i)\buasg\b\D{0,4}(\d{5,6})""")
    private val numberYear = Regex("""(?<![\d/])(\d{1,6})\s*/\s*(\d{4})(?![\d/])""")
    private val glued = Regex("""(?i)\bn[º°o.]*\s*(\d{5})(20\d{2})\b""")
    private val dateTime = Regex("""(\d{2})/(\d{2})/(\d{4})(?:\s*(?:às|as|-|,)?\s*(\d{1,2})[:h](\d{2}))?""")
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    fun ref(text: String, uasgHint: String? = null): PurchaseRef {
        val uasg = uasgLabeled.find(text)?.groupValues?.get(1)?.padStart(6, '0') ?: uasgHint?.filter(Char::isDigit)?.takeIf { it.length in 5..6 }?.padStart(6, '0')
        val pncp = PortalTenderMatching.normalizePncpControl(text)
        // Número/ano: ignora datas (dd/mm/aaaa) e o próprio controle PNCP.
        val cleaned = text.replace(dateTime, " ").let { t -> pncp?.let { t.replace(Regex("""\d{14}-1-\d{1,6}/\d{4}"""), " ") } ?: t }
        val ny = numberYear.find(cleaned)?.let { it.groupValues[1].trimStart('0').ifEmpty { "0" } to it.groupValues[2].toInt() }
            ?: glued.find(cleaned)?.let { it.groupValues[1].trimStart('0').ifEmpty { "0" } to it.groupValues[2].toInt() }
        return PurchaseRef(uasg, ny?.first, ny?.second?.takeIf { it in 1990..2100 }, pncp)
    }

    /** Primeira data/hora "dd/mm/aaaa [hh:mm]" do texto (horário de Brasília) em millis. */
    fun dateTime(text: String): Long? {
        val m = dateTime.find(text) ?: return null
        return runCatching {
            val d = LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
            val t = if (m.groupValues[4].isNotEmpty()) LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt()) else LocalTime.of(9, 0)
            LocalDateTime.of(d, t).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private val modalities = listOf(
        "pregao eletronico" to "Pregão Eletrônico", "pregao" to "Pregão Eletrônico", "dispensa eletronica" to "Dispensa Eletrônica",
        "dispensa" to "Dispensa Eletrônica", "concorrencia eletronica" to "Concorrência Eletrônica", "concorrencia" to "Concorrência",
        "cotacao eletronica" to "Cotação Eletrônica", "rdc" to "RDC", "leilao" to "Leilão",
    )

    fun modality(text: String): String {
        val t = TextNorm.norm(text)
        return modalities.firstOrNull { t.contains(it.first) }?.second.orEmpty()
    }
}

/** Item lido na sala de disputa. */
data class BidRoomItem(
    val itemNumber: Int,
    val bestBid: Double?,
    val ourBid: Double?,
    /** 1 = em primeiro; null = não informada na linha. */
    val position: Int?,
    val phase: DisputePhase,
    /** Mais de um "melhor lance" diferente para o mesmo item = leitura ambígua. */
    val ambiguous: Boolean = false,
)

/** Linhas da sala de disputa → itens. HEURÍSTICA a validar com o modo mapear. */
object BidRoomParser {
    private val item = Regex("""(?i)\bitem\s*(?:n[º°o.]*\s*)?(\d{1,4})\b""")
    private val money = """r\$\s*([\d.]+,\d{2,4}|\d+(?:\.\d{2,4})?)"""
    private val best = Regex("""(?:melhor (?:lance|valor|oferta)|valor do melhor lance|lance vencedor)\s*(?:atual)?\s*[:\-|]?\s*$money""")
    private val ours = Regex("""(?:meu|seu|nosso)\s+(?:ultimo\s+)?(?:lance|valor)\s*[:\-|]?\s*$money""")
    private val position = Regex("""(?:posicao|classificacao|colocacao)\s*[:\-|]?\s*(\d{1,3})|(\d{1,3})\s*[ºo°]?\s*(?:lugar|colocad[oa])""")

    fun parse(rows: List<String>): List<BidRoomItem> {
        val byItem = LinkedHashMap<Int, MutableList<BidRoomItem>>()
        for (row in rows) {
            val n = item.find(row)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val t = TextNorm.norm(row)
            val bests = best.findAll(t).mapNotNull { TextNorm.parseMoney(it.groupValues[1]) }.distinct().toList()
            val our = ours.find(t)?.let { TextNorm.parseMoney(it.groupValues[1]) }
            val pos = position.find(t)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
            byItem.getOrPut(n) { mutableListOf() } += BidRoomItem(n, bests.singleOrNull(), our, pos, PortalPageClassifier.phase(t), ambiguous = bests.size > 1)
        }
        return byItem.map { (n, list) ->
            val bests = list.mapNotNull { it.bestBid }.distinct()
            val phases = list.map { it.phase }.filter { it != DisputePhase.UNKNOWN }.distinct()
            BidRoomItem(
                itemNumber = n,
                bestBid = bests.singleOrNull(),
                ourBid = list.mapNotNull { it.ourBid }.distinct().singleOrNull(),
                position = list.mapNotNull { it.position }.distinct().singleOrNull(),
                phase = phases.firstOrNull { it == DisputePhase.CLOSED } ?: phases.firstOrNull { it == DisputePhase.SUSPENDED } ?: phases.singleOrNull()
                    ?: if (phases.isEmpty()) DisputePhase.UNKNOWN else phases.first(),
                ambiguous = list.any { it.ambiguous } || bests.size > 1,
            )
        }
    }
}

/** Mensagem lida do chat da sala de disputa. */
data class ChatMessage(val sender: String, val body: String, val at: Long?, val timeText: String, val urgent: Boolean, val directed: Boolean) {
    /** Chave de deduplicação (independente de quando foi lida). */
    fun dedupKey(tenderKey: String): String = "$tenderKey|${TextNorm.norm(sender)}|$timeText|${TextNorm.norm(body).take(300)}".hashCode().toString(16)
}

object ChatParser {
    private val senders = listOf("pregoeiro", "agente de contratacao", "sistema", "comissao", "presidente", "fornecedor", "autoridade competente")
    private val time = Regex("""(\d{2}/\d{2}/\d{4}\s*(?:às|as|-)?\s*)?(\d{2}:\d{2}(?::\d{2})?)""")
    private val urgentWords = Regex("""\b(convoca\w*|anexo\w*|prazo\w*|habilitacao|negociacao|contraproposta|diligencia\w*|recurso\w*|documentacao|desclassifica\w*|aceite)\b""")
    private val directedWords = Regex("""\b(sr\.?\s*fornecedor|srs?\.?\s*licitante|prezado fornecedor|ao fornecedor|a empresa|ao licitante|convoco|convocamos|solicito)\b""")

    fun parse(rows: List<String>, ourCnpj: String? = null): List<ChatMessage> = rows.mapNotNull { row ->
        val parts = row.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@mapNotNull null
        val norm = TextNorm.norm(row)
        val sender = parts.firstOrNull { p -> senders.any { TextNorm.norm(p).startsWith(it) } }
            ?: senders.firstOrNull { norm.startsWith(it) }?.replaceFirstChar { it.uppercase() }
            ?: return@mapNotNull null
        val tm = time.find(row)
        val body = parts.filter { it != sender && (tm == null || !it.contains(tm.value)) }.maxByOrNull { it.length }
            ?: row.substringAfter(sender).trim()
        if (body.length < 2) return@mapNotNull null
        val nb = TextNorm.norm(body)
        val cnpjDigits = ourCnpj?.filter(Char::isDigit)?.takeIf { it.length == 14 }
        val directed = directedWords.containsMatchIn(nb) || (cnpjDigits != null && body.filter(Char::isDigit).contains(cnpjDigits))
        ChatMessage(sender.take(60), body.take(2000), tm?.let { PurchaseText.dateTime(it.value) }, tm?.value.orEmpty(), urgentWords.containsMatchIn(nb), directed)
    }
}

/**
 * Sanitização do snapshot do MODO MAPEAR e dos trechos de página guardados no log: mascara CPF/CNPJ, e-mails,
 * telefones e sequências longas (tokens/ids de sessão). Pura e testável.
 */
object SnapshotSanitizer {
    private val cnpj = Regex("""\b\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}\b""")
    private val cpf = Regex("""\b\d{3}\.?\d{3}\.?\d{3}-?\d{2}\b""")
    private val email = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val phone = Regex("""\(?\b\d{2}\)?\s?9?\d{4}-?\d{4}\b""")
    private val longToken = Regex("""\b[A-Za-z0-9_\-]{32,}\b""")
    private val jwt = Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+""")

    fun clean(text: String): String = text
        .replace(jwt, "[token]")
        .replace(email, "[e-mail]")
        .replace(cnpj, "[cnpj]")
        .replace(cpf, "[cpf]")
        .replace(phone, "[telefone]")
        .replace(longToken, "[id]")
}
