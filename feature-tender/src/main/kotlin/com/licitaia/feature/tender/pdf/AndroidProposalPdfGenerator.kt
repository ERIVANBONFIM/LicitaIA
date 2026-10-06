package com.licitaia.feature.tender.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.licitaia.domain.model.Company
import com.licitaia.domain.model.Proposal
import com.licitaia.domain.model.ProposalStatus
import com.licitaia.domain.model.Tender
import com.licitaia.domain.repository.ProposalPdfGenerator
import com.licitaia.domain.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gera a proposta comercial em PDF (A4) com android.graphics.pdf.PdfDocument, em
 * `filesDir/proposals/`. Layout: cabeçalho da empresa, dados do pregão, tabela de itens,
 * totais, prazos, observações, assinatura e rodapé "gerado pelo LicitaIA".
 */
@Singleton
class AndroidProposalPdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProposalPdfGenerator {

    override suspend fun generate(proposal: Proposal, tender: Tender, company: Company): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "proposals").apply { mkdirs() }
                val safeNumber = tender.number.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(32)
                val file = File(dir, "proposta_${safeNumber}_v${proposal.version}_${proposal.id}.pdf")
                val document = PdfDocument()
                try {
                    Layout(document, proposal, tender, company).render()
                    FileOutputStream(file).use { document.writeTo(it) }
                } finally {
                    document.close()
                }
                Result.success(file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Falha ao gerar o PDF da proposta: ${e.message ?: e.javaClass.simpleName}", e))
            }
        }

    /** Layout paginado em pontos (A4 = 595 x 842). */
    private class Layout(
        private val document: PdfDocument,
        private val proposal: Proposal,
        private val tender: Tender,
        private val company: Company,
    ) {
        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 40f
        private val contentWidth = pageWidth - margin * 2
        private val bottomLimit = pageHeight - 60f

        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = 0f
        private var pageNumber = 0

        private val navy = Color.rgb(15, 31, 63)
        private val blue = Color.rgb(59, 130, 246)
        private val green = Color.rgb(16, 185, 129)
        private val gray = Color.rgb(100, 116, 139)
        private val lightGray = Color.rgb(226, 232, 240)
        private val zebra = Color.rgb(248, 250, 252)
        private val textDark = Color.rgb(15, 23, 42)

        private val title = paint(18f, bold = true, color = Color.WHITE)
        private val subtitle = paint(10f, color = Color.rgb(203, 213, 225))
        private val h2 = paint(12f, bold = true, color = navy)
        private val label = paint(8.5f, color = gray)
        private val body = paint(9.5f, color = textDark)
        private val bodyBold = paint(9.5f, bold = true, color = textDark)
        private val small = paint(7.5f, color = gray)
        private val tableHeader = paint(8.5f, bold = true, color = Color.WHITE)
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = lightGray }

        private fun paint(size: Float, bold: Boolean = false, color: Int = textDark) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        fun render() {
            newPage()
            drawTenderBlock()
            drawItemsTable()
            drawTotals()
            drawTerms()
            drawNotes()
            drawSignature()
            finishPage()
        }

        // ------------------------------------------------------------------ páginas

        private fun newPage() {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            val p = document.startPage(info)
            page = p
            canvas = p.canvas
            drawHeader(p.canvas)
            drawWatermark(p.canvas)
            y = 118f
        }

        private fun finishPage() {
            val p = page ?: return
            drawFooter(p.canvas)
            document.finishPage(p)
            page = null
            canvas = null
        }

        private fun ensureSpace(needed: Float) {
            if (y + needed > bottomLimit) {
                finishPage()
                newPage()
            }
        }

        private fun drawHeader(c: Canvas) {
            fill.color = navy
            c.drawRect(0f, 0f, pageWidth.toFloat(), 92f, fill)
            fill.color = green
            c.drawRect(0f, 92f, pageWidth.toFloat(), 95f, fill)

            val companyName = company.tradeName.ifBlank { company.name }
            c.drawText(ellipsize(companyName, title, 330f), margin, 38f, title)
            c.drawText(company.name.takeIf { it != companyName }?.let { ellipsize(it, subtitle, 330f) } ?: "", margin, 53f, subtitle)
            c.drawText("CNPJ ${Formatters.cnpj(company.cnpj)} · ${company.city}/${company.uf}", margin, 68f, subtitle)
            c.drawText("Segmento: ${company.segment.label}", margin, 81f, subtitle)

            val right = pageWidth - margin
            val t1 = paint(14f, bold = true, color = Color.WHITE)
            drawRight(c, "PROPOSTA COMERCIAL", right, 38f, t1)
            drawRight(c, "Versão ${proposal.version} · ${proposal.status.label}", right, 54f, subtitle)
            drawRight(c, "Emitida em ${Formatters.dateTime(if (proposal.createdAt > 0) proposal.createdAt else System.currentTimeMillis())}", right, 68f, subtitle)
            drawRight(c, "Validade: ${proposal.validityDays} dias", right, 81f, subtitle)
        }

        private fun drawWatermark(c: Canvas) {
            val text = when (proposal.status) {
                ProposalStatus.RASCUNHO -> "RASCUNHO"
                ProposalStatus.EM_REVISAO -> "EM REVISÃO"
                ProposalStatus.REJEITADA -> "REJEITADA"
                ProposalStatus.ENVIADA_SIMULADA -> "SIMULAÇÃO"
                ProposalStatus.APROVADA -> return
            }
            val wm = paint(64f, bold = true, color = Color.argb(22, 59, 130, 246))
            c.save()
            c.rotate(-30f, pageWidth / 2f, pageHeight / 2f)
            c.drawText(text, pageWidth / 2f - wm.measureText(text) / 2f, pageHeight / 2f, wm)
            c.restore()
        }

        private fun drawFooter(c: Canvas) {
            c.drawLine(margin, pageHeight - 42f, pageWidth - margin, pageHeight - 42f, line)
            c.drawText(
                "Documento gerado pelo LicitaIA em ${Formatters.dateTime(System.currentTimeMillis())} · Proposta #${proposal.id} v${proposal.version}",
                margin, pageHeight - 28f, small,
            )
            drawRight(c, "Página $pageNumber", pageWidth - margin, pageHeight - 28f, small)
            if (proposal.status != ProposalStatus.APROVADA) {
                c.drawText("Nenhum dado é enviado automaticamente a portais: todo envio exige confirmação humana.", margin, pageHeight - 16f, small)
            }
        }

        // ------------------------------------------------------------------ blocos

        private fun drawTenderBlock() {
            val c = canvas ?: return
            sectionTitle("Dados do pregão")
            val rows = listOf(
                "Portal" to tender.portal.displayName,
                "Número" to tender.number,
                "Órgão" to tender.agency,
                "Modalidade" to tender.modality.label,
                "Local" to "${tender.city}/${tender.uf}",
                "Valor estimado" to Formatters.brl(tender.estimatedValue),
                "Limite de propostas" to Formatters.dateTime(tender.proposalDeadline),
                "Sessão pública" to Formatters.dateTime(tender.sessionAt),
            )
            val labelWidth = 110f
            rows.forEach { (k, v) ->
                ensureSpace(14f)
                c.drawText(k, margin, y, label)
                c.drawText(ellipsize(v, body, contentWidth - labelWidth), margin + labelWidth, y, body)
                y += 14f
            }
            ensureSpace(14f)
            c.drawText("Objeto", margin, y, label)
            val lines = wrap(tender.objectDescription, body, contentWidth - labelWidth)
            lines.forEach { l ->
                ensureSpace(13f)
                canvas?.drawText(l, margin + labelWidth, y, body)
                y += 13f
            }
            y += 10f
        }

        private fun drawItemsTable() {
            sectionTitle("Itens da proposta")
            val cols = floatArrayOf(24f, 0f, 38f, 48f, 82f, 90f) // '#', descrição (flex), un, qtd, unit, total
            cols[1] = contentWidth - (cols[0] + cols[2] + cols[3] + cols[4] + cols[5])
            drawTableHeader(cols)
            proposal.items.forEachIndexed { index, item ->
                val descLines = wrap(item.description.ifBlank { "Item ${index + 1}" }, body, cols[1] - 8f)
                val rowHeight = 8f + descLines.size * 12f
                if (y + rowHeight > bottomLimit) {
                    finishPage()
                    newPage()
                    drawTableHeader(cols)
                }
                val c = canvas ?: return
                if (index % 2 == 1) {
                    fill.color = zebra
                    c.drawRect(margin, y - 10f, margin + contentWidth, y - 10f + rowHeight, fill)
                }
                var x = margin
                c.drawText("${index + 1}", x + 4f, y, body); x += cols[0]
                descLines.forEachIndexed { i, l -> c.drawText(l, x + 4f, y + i * 12f, body) }; x += cols[1]
                c.drawText(ellipsize(item.unit, body, cols[2] - 6f), x + 3f, y, body); x += cols[2]
                drawRight(c, numberPt(item.quantity), x + cols[3] - 4f, y, body); x += cols[3]
                drawRight(c, Formatters.brl(item.unitPrice), x + cols[4] - 4f, y, body); x += cols[4]
                drawRight(c, Formatters.brl(item.total), x + cols[5] - 4f, y, bodyBold)
                y += rowHeight
                c.drawLine(margin, y - 10f, margin + contentWidth, y - 10f, line)
            }
            y += 6f
        }

        private fun drawTableHeader(cols: FloatArray) {
            ensureSpace(22f)
            val c = canvas ?: return
            fill.color = blue
            c.drawRect(margin, y - 11f, margin + contentWidth, y + 7f, fill)
            var x = margin
            c.drawText("#", x + 4f, y + 1f, tableHeader); x += cols[0]
            c.drawText("Descrição", x + 4f, y + 1f, tableHeader); x += cols[1]
            c.drawText("Un.", x + 3f, y + 1f, tableHeader); x += cols[2]
            drawRight(c, "Qtd.", x + cols[3] - 4f, y + 1f, tableHeader); x += cols[3]
            drawRight(c, "Preço unit.", x + cols[4] - 4f, y + 1f, tableHeader); x += cols[4]
            drawRight(c, "Total", x + cols[5] - 4f, y + 1f, tableHeader)
            y += 22f
        }

        private fun drawTotals() {
            ensureSpace(44f)
            val c = canvas ?: return
            val boxLeft = margin + contentWidth - 230f
            fill.color = Color.rgb(236, 253, 245)
            c.drawRect(boxLeft, y - 8f, margin + contentWidth, y + 30f, fill)
            c.drawText("Quantidade de itens", boxLeft + 10f, y + 5f, label)
            drawRight(c, "${proposal.items.size}", margin + contentWidth - 10f, y + 5f, body)
            c.drawText("VALOR TOTAL DA PROPOSTA", boxLeft + 10f, y + 22f, paint(9f, bold = true, color = navy))
            drawRight(c, Formatters.brl(proposal.totalValue), margin + contentWidth - 10f, y + 22f, paint(12f, bold = true, color = Color.rgb(4, 120, 87)))
            if (tender.estimatedValue > 0) {
                val delta = (proposal.totalValue / tender.estimatedValue - 1.0) * 100.0
                val txt = if (delta <= 0) "${Formatters.percent(-delta)} abaixo do valor estimado pelo órgão" else "${Formatters.percent(delta)} acima do valor estimado pelo órgão"
                c.drawText(txt, margin, y + 22f, small)
            }
            y += 50f
        }

        private fun drawTerms() {
            sectionTitle("Prazos e condições")
            val rows = buildList {
                add("Prazo de entrega/instalação" to "${proposal.deliveryDays} dias corridos a contar da assinatura do contrato/ordem de serviço")
                add("Validade da proposta" to "${proposal.validityDays} dias a contar da data de abertura da sessão")
                add("Status interno" to proposal.status.label)
                add("Elaborada por" to proposal.createdBy)
                proposal.approvedBy?.let { add("Aprovada por" to "$it em ${Formatters.dateTime(proposal.approvedAt)}") }
                proposal.rejectionReason?.takeIf { it.isNotBlank() }?.let { add("Motivo da rejeição" to it) }
            }
            val labelWidth = 150f
            rows.forEach { (k, v) ->
                val lines = wrap(v, body, contentWidth - labelWidth)
                ensureSpace(13f * lines.size + 2f)
                canvas?.drawText(k, margin, y, label)
                lines.forEach { l ->
                    canvas?.drawText(l, margin + labelWidth, y, body)
                    y += 13f
                }
            }
            y += 8f
        }

        private fun drawNotes() {
            sectionTitle("Observações")
            val text = proposal.notes.ifBlank { "Declaramos que nos preços propostos estão incluídos todos os tributos, encargos, fretes e demais despesas necessárias à execução do objeto." }
            wrap(text, body, contentWidth).forEach { l ->
                ensureSpace(13f)
                canvas?.drawText(l, margin, y, body)
                y += 13f
            }
            y += 10f
        }

        private fun drawSignature() {
            ensureSpace(90f)
            y += 36f
            val c = canvas ?: return
            val half = contentWidth / 2f - 16f
            c.drawLine(margin, y, margin + half, y, Paint().apply { color = gray; strokeWidth = 0.8f })
            c.drawLine(margin + half + 32f, y, margin + contentWidth, y, Paint().apply { color = gray; strokeWidth = 0.8f })
            y += 12f
            c.drawText(ellipsize(proposal.approvedBy ?: proposal.createdBy, bodyBold, half), margin, y, bodyBold)
            c.drawText(ellipsize(company.name, bodyBold, half), margin + half + 32f, y, bodyBold)
            y += 12f
            c.drawText("Responsável pela proposta", margin, y, small)
            c.drawText("CNPJ ${Formatters.cnpj(company.cnpj)}", margin + half + 32f, y, small)
            y += 20f
        }

        private fun sectionTitle(text: String) {
            ensureSpace(30f)
            val c = canvas ?: return
            c.drawText(text.uppercase(), margin, y, h2)
            y += 6f
            fill.color = blue
            c.drawRect(margin, y, margin + 28f, y + 2f, fill)
            y += 16f
        }

        // ------------------------------------------------------------------ utilitários

        private fun drawRight(c: Canvas, text: String, right: Float, baseline: Float, p: Paint) {
            c.drawText(text, right - p.measureText(text), baseline, p)
        }

        private fun numberPt(v: Double): String =
            if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale("pt", "BR"), "%.2f", v)

        private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
            if (p.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && p.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end) + "…"
        }

        private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
            val result = mutableListOf<String>()
            text.split('\n').forEach { paragraph ->
                var current = StringBuilder()
                paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (p.measureText(candidate) <= maxWidth) {
                        current = StringBuilder(candidate)
                    } else {
                        if (current.isNotEmpty()) result += current.toString()
                        current = StringBuilder(if (p.measureText(word) <= maxWidth) word else ellipsize(word, p, maxWidth))
                    }
                }
                result += current.toString()
            }
            return result.ifEmpty { listOf("") }
        }
    }
}
