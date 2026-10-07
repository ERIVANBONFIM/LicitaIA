package com.licitaia.feature.tender.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.Tender
import com.licitaia.domain.portal.PortalTenderMatching
import com.licitaia.domain.proposal.MoneyInWords
import com.licitaia.domain.repository.ProposalPdfGenerator
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gera a proposta comercial em PDF (A4) pronta para anexar no portal, com android.graphics.pdf.PdfDocument, em
 * `filesDir/proposals/Proposta_<numero>_<orgao>_v<versao>.pdf`.
 *
 * Conteúdo: cabeçalho da proponente (repetido), destinatário (órgão/UASG), identificação da licitação, planilha de
 * itens (nº do item do edital, descrição completa com quebra de linha, unidade, quantidade, unitário e total, cabeçalho
 * repetido a cada página), valor global e por extenso, validade, prazo, pagamento, declarações, local/data e assinatura.
 * Rodapé "Página X de Y" (duas passagens: a primeira só mede para saber o total de páginas). Sem marca d'água.
 */
@Singleton
class AndroidProposalPdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProposalPdfGenerator {

    override suspend fun generate(proposal: Proposal, tender: Tender, company: Company): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                require(proposal.items.isNotEmpty()) { "A proposta não tem itens." }
                val dir = File(context.filesDir, "proposals").apply { mkdirs() }
                val file = File(dir, ProposalPdfLayout.fileName(tender.number, tender.agency, proposal.version))
                val tmp = File(dir, file.name + ".tmp")
                val issuedAt = System.currentTimeMillis()
                // 1ª passagem: só mede (conta páginas); 2ª: desenha com "Página X de Y".
                val pages = Layout(null, proposal, tender, company, totalPages = 0, issuedAt = issuedAt).render()
                val document = PdfDocument()
                try {
                    Layout(document, proposal, tender, company, totalPages = pages, issuedAt = issuedAt).render()
                    FileOutputStream(tmp).use { document.writeTo(it) }
                } finally {
                    document.close()
                }
                check(tmp.length() > 0) { "arquivo vazio" }
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                Result.success(file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Falha ao gerar o PDF da proposta: ${e.message ?: e.javaClass.simpleName}", e))
            }
        }

    /**
     * Layout paginado em pontos (A4 = 595 x 842). Com [document] = null nada é desenhado: só a paginação é calculada
     * (mesma lógica, mesmas medidas), e [render] devolve o número de páginas.
     */
    private class Layout(
        private val document: PdfDocument?,
        private val proposal: Proposal,
        private val tender: Tender,
        private val company: Company,
        private val totalPages: Int,
        private val issuedAt: Long,
    ) {
        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 42f
        private val contentWidth = pageWidth - margin * 2
        private val pageTop = 112f
        private val bottomLimit = pageHeight - 56f

        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = 0f
        private var pageNumber = 0

        private val navy = Color.rgb(15, 31, 63)
        private val accent = Color.rgb(37, 99, 235)
        private val gray = Color.rgb(90, 104, 125)
        private val lightGray = Color.rgb(214, 220, 228)
        private val zebra = Color.rgb(246, 248, 251)
        private val textDark = Color.rgb(15, 23, 42)

        private val headerTitle = paint(13f, bold = true, color = Color.WHITE)
        private val headerText = paint(8.5f, color = Color.rgb(214, 224, 240))
        private val headerRightTitle = paint(13f, bold = true, color = Color.WHITE)
        private val h2 = paint(10.5f, bold = true, color = navy)
        private val label = paint(8.5f, color = gray)
        private val body = paint(9.5f, color = textDark)
        private val bodyBold = paint(9.5f, bold = true, color = textDark)
        private val small = paint(7.5f, color = gray)
        private val tableBody = paint(8.5f, color = textDark)
        private val tableBold = paint(8.5f, bold = true, color = textDark)
        private val tableHeader = paint(8f, bold = true, color = Color.WHITE)
        private val totalValue = paint(12f, bold = true, color = navy)
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = lightGray }
        private val signLine = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = gray }

        private val ptBr = Locale("pt", "BR")
        private val qtyFormat = DecimalFormat("#,##0.####", DecimalFormatSymbols(ptBr))

        // colunas da planilha: nº item, descrição (flex), unidade, quantidade, unitário, total
        private val cols = floatArrayOf(34f, 0f, 46f, 50f, 78f, 86f).also { it[1] = contentWidth - (it[0] + it[2] + it[3] + it[4] + it[5]) }
        private val tableLine = 11f
        private val rowPadding = 7f
        private val tableHeaderHeight = 22f

        private fun paint(size: Float, bold: Boolean = false, color: Int = textDark) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        /** Desenha tudo e devolve o número de páginas. */
        fun render(): Int {
            newPage()
            drawAddressee()
            drawIdentification()
            drawBidder()
            drawItemsTable()
            drawTotals()
            drawConditions()
            drawDeclarations()
            // As observações da proposta são notas internas (a IA escreve ali pendências e conferências): não vão ao órgão.
            drawPlaceAndSignature()
            finishPage()
            return pageNumber
        }

        // ------------------------------------------------------------------ páginas

        private fun newPage() {
            pageNumber++
            if (document != null) {
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = document.startPage(info).also { canvas = it.canvas }
            }
            drawHeader()
            y = pageTop
        }

        private fun finishPage() {
            drawFooter()
            page?.let { document?.finishPage(it) }
            page = null
            canvas = null
        }

        private fun ensureSpace(needed: Float) {
            if (y + needed > bottomLimit) {
                finishPage()
                newPage()
            }
        }

        private fun drawHeader() {
            val c = canvas
            fill.color = navy
            c?.drawRect(0f, 0f, pageWidth.toFloat(), 88f, fill)
            fill.color = accent
            c?.drawRect(0f, 88f, pageWidth.toFloat(), 91f, fill)

            val leftWidth = 320f
            val nameLines = wrap(company.name, headerTitle, leftWidth).take(2)
            var ly = 30f
            nameLines.forEach { l -> c?.drawText(l, margin, ly, headerTitle); ly += 15f }
            // CNPJ, endereço completo (com cidade/UF e CEP), telefone/e-mail e nome fantasia, no que couber na faixa.
            val headerLine = 10f
            val maxLines = ((84f - ly) / headerLine).toInt() + 1
            ProposalPdfLayout.headerLines(company, maxLines).forEach { l ->
                c?.drawText(ellipsize(l, headerText, leftWidth), margin, ly, headerText); ly += headerLine
            }

            val right = pageWidth - margin
            drawRight(c, "PROPOSTA COMERCIAL", right, 32f, headerRightTitle)
            drawRight(c, ellipsize("${tender.modality.label} nº ${tender.number}", headerText, 200f), right, 48f, headerText)
            drawRight(c, "Versão ${proposal.version} · ${Formatters.date(issuedAt)}", right, 60f, headerText)
            drawRight(c, "Validade: ${proposal.validityDays} dias", right, 72f, headerText)
        }

        private fun drawFooter() {
            val c = canvas ?: return
            val fy = pageHeight - 34f
            c.drawLine(margin, fy - 10f, pageWidth - margin, fy - 10f, line)
            c.drawText(
                ellipsize("${company.name} · CNPJ ${Formatters.cnpj(company.cnpj)} · Proposta ${tender.number} · v${proposal.version}", small, contentWidth - 80f),
                margin, fy, small,
            )
            val total = if (totalPages > 0) totalPages else pageNumber
            drawRight(c, "Página $pageNumber de $total", pageWidth - margin, fy, small)
        }

        // ------------------------------------------------------------------ blocos

        private fun drawAddressee() {
            sectionTitle("Destinatário")
            val uasg = runCatching { PortalTenderMatching.refOf(tender.opportunityId, tender.number, tender.agency).uasg }.getOrNull()
            keyValue("Órgão", tender.agency.ifBlank { "—" })
            uasg?.takeIf { it.isNotBlank() }?.let { keyValue("UASG", it) }
            listOf(tender.city, tender.uf).filter { it.isNotBlank() }.joinToString("/").takeIf { it.isNotEmpty() }?.let { keyValue("Local", it) }
            keyValue("A/C", "Agente de contratação / Pregoeiro(a) e equipe de apoio")
            y += 6f
        }

        private fun drawIdentification() {
            sectionTitle("Identificação da licitação")
            keyValue("Modalidade", tender.modality.label)
            keyValue("Número/ano", tender.number)
            keyValue("Portal", tender.portal.displayName)
            if (tender.sessionAt > 0) keyValue("Sessão pública", Formatters.dateTime(tender.sessionAt))
            keyValue("Objeto", tender.objectDescription.ifBlank { "—" })
            y += 6f
        }

        private fun drawBidder() {
            sectionTitle("Proponente")
            ProposalPdfLayout.bidderFields(company).forEach { (key, value) -> keyValue(key, value) }
            y += 6f
        }

        private fun drawItemsTable() {
            sectionTitle("Planilha de preços")
            val descWidth = cols[1] - 8f
            val unitWidth = cols[2] - 6f
            val descLines = proposal.items.mapIndexed { i, item ->
                val extras = listOfNotNull(
                    item.brand.takeIf { it.isNotBlank() }?.let { "Marca: $it" },
                    item.manufacturer.takeIf { it.isNotBlank() }?.let { "Fabricante: $it" },
                    item.model.takeIf { it.isNotBlank() }?.let { "Modelo: $it" },
                )
                val text = item.description.ifBlank { "Item ${item.itemNumber ?: (i + 1)}" } +
                    if (extras.isNotEmpty()) "\n" + extras.joinToString(" · ") else ""
                wrap(text, tableBody, descWidth)
            }
            val unitLines = proposal.items.map { wrap(it.unit.ifBlank { "un" }, tableBody, unitWidth) }
            val counts = proposal.items.indices.map { maxOf(descLines[it].size, unitLines[it].size) }

            ensureSpace(tableHeaderHeight + tableLine * 2 + rowPadding)
            drawTableHeader()
            val segments = ProposalPdfLayout.paginateTable(counts, tableLine, rowPadding, y, pageTop, bottomLimit, tableHeaderHeight)
            segments.forEach { seg ->
                if (seg.newPageBefore) {
                    finishPage()
                    newPage()
                    drawTableHeader()
                }
                val item = proposal.items[seg.item]
                val rows = seg.toLine - seg.fromLine
                val rowHeight = rows * tableLine + rowPadding
                val c = canvas
                if (seg.item % 2 == 1) {
                    fill.color = zebra
                    c?.drawRect(margin, y, margin + contentWidth, y + rowHeight, fill)
                }
                val baseline0 = y + rowPadding / 2f + tableLine - 2.5f
                var x = margin
                if (seg.fromLine == 0) c?.drawText("${item.itemNumber ?: (seg.item + 1)}", x + 4f, baseline0, tableBold)
                x += cols[0]
                for (k in seg.fromLine until seg.toLine) {
                    val baseline = baseline0 + (k - seg.fromLine) * tableLine
                    descLines[seg.item].getOrNull(k)?.let { c?.drawText(it, x + 4f, baseline, tableBody) }
                    unitLines[seg.item].getOrNull(k)?.let { c?.drawText(it, x + cols[1] + 3f, baseline, tableBody) }
                }
                x += cols[1] + cols[2]
                if (seg.fromLine == 0) {
                    drawRightFit(c, qtyFormat.format(item.quantity), x + cols[3] - 4f, baseline0, tableBody, cols[3] - 6f); x += cols[3]
                    val unitPrice = if (item.unitPrice > 0) Formatters.brl(item.unitPrice) else "a definir"
                    drawRightFit(c, unitPrice, x + cols[4] - 4f, baseline0, tableBody, cols[4] - 6f); x += cols[4]
                    drawRightFit(c, Formatters.brl(item.total), x + cols[5] - 4f, baseline0, tableBold, cols[5] - 6f)
                }
                y += rowHeight
                c?.drawLine(margin, y, margin + contentWidth, y, line)
            }
            y += 4f
        }

        private fun drawTableHeader() {
            val c = canvas
            fill.color = navy
            c?.drawRect(margin, y, margin + contentWidth, y + tableHeaderHeight - 4f, fill)
            val base = y + 12f
            var x = margin
            c?.drawText("Item", x + 4f, base, tableHeader); x += cols[0]
            c?.drawText("Descrição", x + 4f, base, tableHeader); x += cols[1]
            c?.drawText("Unid.", x + 3f, base, tableHeader); x += cols[2]
            drawRight(c, "Qtd.", x + cols[3] - 4f, base, tableHeader); x += cols[3]
            drawRight(c, "Valor unit.", x + cols[4] - 4f, base, tableHeader); x += cols[4]
            drawRight(c, "Valor total", x + cols[5] - 4f, base, tableHeader)
            y += tableHeaderHeight
        }

        private fun drawTotals() {
            val words = "(${MoneyInWords.brl(proposal.totalValue)})"
            val wordLines = wrap(words, body, contentWidth - 20f)
            val boxHeight = 30f + wordLines.size * 12f + 8f
            ensureSpace(boxHeight + 8f)
            val c = canvas
            fill.color = Color.rgb(238, 243, 252)
            c?.drawRect(margin, y, margin + contentWidth, y + boxHeight, fill)
            c?.drawText("VALOR GLOBAL DA PROPOSTA", margin + 10f, y + 19f, paint(9.5f, bold = true, color = navy))
            drawRight(c, Formatters.brl(proposal.totalValue), margin + contentWidth - 10f, y + 20f, totalValue)
            var ly = y + 36f
            wordLines.forEach { l -> c?.drawText(l, margin + 10f, ly, body); ly += 12f }
            y += boxHeight + 14f
        }

        private fun drawConditions() {
            sectionTitle("Condições da proposta")
            keyValue("Validade da proposta", "${proposal.validityDays} (${MoneyInWords.integer(proposal.validityDays.toLong())}) dias, contados da data de abertura da sessão pública.")
            keyValue("Prazo de entrega/execução", "${proposal.deliveryDays} (${MoneyInWords.integer(proposal.deliveryDays.toLong())}) dias corridos, contados do recebimento da ordem de fornecimento/serviço ou da assinatura do contrato.")
            keyValue("Condições de pagamento", "Conforme o edital e o termo de referência, mediante apresentação da nota fiscal e atesto do recebimento.")
            keyValue("Quantidade de itens", "${proposal.items.size}")
            y += 6f
        }

        private fun drawDeclarations() {
            sectionTitle("Declarações")
            listOf(
                "Declaramos que nos preços propostos estão incluídos todos os custos diretos e indiretos, tributos, encargos sociais, " +
                    "trabalhistas, previdenciários, fiscais e comerciais, taxas, fretes, seguros e quaisquer outras despesas necessárias ao " +
                    "cumprimento integral do objeto.",
                "Declaramos que o objeto ofertado atende integralmente às especificações do edital e de seus anexos, e que aceitamos todas " +
                    "as condições neles estabelecidas.",
                "Declaramos que esta proposta é válida por ${proposal.validityDays} dias, contados da data de abertura da sessão pública.",
                "Declaramos que, sendo vencedores, cumpriremos o prazo de entrega/execução indicado e manteremos as condições de " +
                    "habilitação durante toda a execução do contrato.",
            ).forEach { bullet(it) }
            y += 6f
        }

        private fun drawNotes() {
            val notes = proposal.notes.trim()
            if (notes.isEmpty()) return
            sectionTitle("Observações")
            paragraph(notes, body, margin, contentWidth, 12.5f)
            y += 8f
        }

        private fun drawPlaceAndSignature() {
            val place = listOf(company.city, company.uf).filter { it.isNotBlank() }.joinToString("/")
            val date = SimpleDateFormat("d 'de' MMMM 'de' yyyy", ptBr).format(Date(issuedAt))
            ensureSpace(135f)
            y += 8f
            canvas?.drawText(if (place.isNotEmpty()) "$place, $date." else "$date.", margin, y, body)
            y += 54f
            val c = canvas
            val lineWidth = 260f
            val left = margin + (contentWidth - lineWidth) / 2f
            c?.drawLine(left, y, left + lineWidth, y, signLine)
            y += 13f
            // Representante legal do cadastro (nome, cargo e CPF); sem cadastro, quem aprovou/criou e CPF em branco.
            ProposalPdfLayout.signatureLines(company, proposal.approvedBy ?: proposal.createdBy).forEachIndexed { i, l ->
                centered(l, if (i == 0) bodyBold else small)
            }
        }

        // ------------------------------------------------------------------ primitivas

        private fun sectionTitle(text: String) {
            ensureSpace(40f)
            y += 6f
            canvas?.drawText(text.uppercase(ptBr), margin, y + 8f, h2)
            y += 12f
            fill.color = accent
            canvas?.drawRect(margin, y, margin + 28f, y + 2f, fill)
            y += 12f
        }

        private fun keyValue(key: String, value: String) {
            val labelWidth = 128f
            val lines = wrap(value, body, contentWidth - labelWidth)
            lines.forEachIndexed { i, l ->
                ensureSpace(12.5f)
                if (i == 0) canvas?.drawText(key, margin, y + 9f, label)
                canvas?.drawText(l, margin + labelWidth, y + 9f, body)
                y += 12.5f
            }
            y += 2f
        }

        private fun bullet(text: String) {
            val indent = 12f
            val lines = wrap(text, body, contentWidth - indent)
            lines.forEachIndexed { i, l ->
                ensureSpace(12.5f)
                if (i == 0) canvas?.drawText("•", margin + 2f, y + 9f, body)
                canvas?.drawText(l, margin + indent, y + 9f, body)
                y += 12.5f
            }
            y += 3f
        }

        private fun paragraph(text: String, p: Paint, x: Float, width: Float, lineHeight: Float) {
            wrap(text, p, width).forEach { l ->
                ensureSpace(lineHeight)
                canvas?.drawText(l, x, y + 9f, p)
                y += lineHeight
            }
        }

        private fun centered(text: String, p: Paint) {
            wrap(text, p, contentWidth).forEach { l ->
                ensureSpace(12f)
                canvas?.drawText(l, margin + (contentWidth - p.measureText(l)) / 2f, y, p)
                y += 12f
            }
        }

        private fun drawRight(c: Canvas?, text: String, right: Float, baseline: Float, p: Paint) {
            c?.drawText(text, right - p.measureText(text), baseline, p)
        }

        /** Alinha à direita reduzindo a fonte quando o valor não cabe na coluna (nunca corta números). */
        private fun drawRightFit(c: Canvas?, text: String, right: Float, baseline: Float, p: Paint, maxWidth: Float) {
            val width = p.measureText(text)
            if (width <= maxWidth) return drawRight(c, text, right, baseline, p)
            val shrunk = Paint(p).apply { textSize = (p.textSize * maxWidth / width).coerceAtLeast(5f) }
            drawRight(c, text, right, baseline, shrunk)
        }

        private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> =
            ProposalPdfLayout.wrap(text, maxWidth) { p.measureText(it) }

        private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
            if (p.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && p.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end) + "…"
        }
    }
}
