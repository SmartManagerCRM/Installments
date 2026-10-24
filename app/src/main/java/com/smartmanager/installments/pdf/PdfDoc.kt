package com.smartmanager.installments.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File

/**
 * Minimal paginating PDF writer on top of android.graphics.pdf.PdfDocument.
 * Text is laid out with StaticLayout so Arabic shaping and bidirectional text render correctly; in RTL mode columns,
 * label/value pairs and paragraph alignment are mirrored.
 */
class PdfDoc(private val rtl: Boolean, val pageW: Int = 595, val pageH: Int = 842, private val margin: Int = 36, private val footerText: String = "") {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNo = 0
    var y = margin.toFloat()
        private set
    val contentW get() = (pageW - 2 * margin).toFloat()

    private val ink = Color.parseColor("#0B2A5B")
    private val muted = Color.parseColor("#5B6B84")
    private val line = Color.parseColor("#C9D3E3")
    private val blue = Color.parseColor("#1E6FEB")

    private fun paint(size: Float, bold: Boolean = false, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private fun layout(text: String, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(1))
            .setAlignment(align)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(0f, 1.1f).build()

    private fun ensure(h: Float) {
        if (page == null || y + h > pageH - margin - 18) newPage()
    }

    fun newPage() {
        page?.let { finishPage(it) }
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        canvas = page!!.canvas
        y = margin.toFloat()
    }

    private fun finishPage(p: PdfDocument.Page) {
        val fp = paint(8f, color = muted)
        val c = p.canvas
        val label = "$footerText   ·   $pageNo"
        val l = layout(label, fp, contentW.toInt(), Layout.Alignment.ALIGN_CENTER)
        c.save(); c.translate(margin.toFloat(), pageH - margin + 4f); l.draw(c); c.restore()
        doc.finishPage(p)
    }

    fun spacer(h: Float) { y += h }

    fun rule(color: Int = line) {
        ensure(6f)
        val p = Paint().apply { this.color = color; strokeWidth = 0.8f }
        canvas!!.drawLine(margin.toFloat(), y + 2, pageW - margin.toFloat(), y + 2, p)
        y += 6f
    }

    /** Paragraph aligned to the "start" side of the current language. */
    fun text(text: String, size: Float = 10f, bold: Boolean = false, color: Int = ink, center: Boolean = false, gapAfter: Float = 3f) {
        if (text.isBlank()) { y += gapAfter; return }
        val l = layout(text, paint(size, bold, color), contentW.toInt(), if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
        ensure(l.height.toFloat())
        canvas!!.save(); canvas!!.translate(margin.toFloat(), y); l.draw(canvas!!); canvas!!.restore()
        y += l.height + gapAfter
    }

    fun title(text: String) = text(text, 18f, true, ink, gapAfter = 6f)

    fun keyValue(label: String, value: String, bold: Boolean = false, valueColor: Int = ink) {
        val half = (contentW * 0.45f).toInt()
        val lp = paint(10f, false, muted)
        val vp = paint(10f, bold, valueColor)
        val ll = layout(label, lp, half)
        val vl = layout(value, vp, (contentW - half - 8).toInt(), Layout.Alignment.ALIGN_OPPOSITE)
        val h = maxOf(ll.height, vl.height).toFloat()
        ensure(h)
        val c = canvas!!
        val labelX = if (rtl) margin + contentW - half else margin.toFloat()
        val valueX = if (rtl) margin.toFloat() else margin + half + 8f
        c.save(); c.translate(labelX, y); ll.draw(c); c.restore()
        c.save(); c.translate(valueX, y); vl.draw(c); c.restore()
        y += h + 3f
    }

    fun logoAndBusiness(logo: Bitmap?, name: String, details: List<String>) {
        ensure(60f)
        val c = canvas!!
        val logoSize = 44f
        var textW = contentW
        if (logo != null) {
            val x = if (rtl) margin + contentW - logoSize else margin.toFloat()
            c.drawBitmap(Bitmap.createScaledBitmap(logo, logoSize.toInt(), logoSize.toInt(), true), x, y, null)
            textW = contentW - logoSize - 10
        }
        val tx = if (logo != null && !rtl) margin + logoSize + 10 else margin.toFloat()
        var ty = y
        val nl = layout(name, paint(15f, true), textW.toInt())
        c.save(); c.translate(tx, ty); nl.draw(c); c.restore(); ty += nl.height
        details.filter { it.isNotBlank() }.forEach {
            val dl = layout(it, paint(9f, false, muted), textW.toInt())
            c.save(); c.translate(tx, ty); dl.draw(c); c.restore(); ty += dl.height
        }
        y = maxOf(ty, y + if (logo != null) logoSize else 0f) + 6f
        rule(blue)
    }

    /** weights: relative column widths. numeric: columns right-aligned (mirrored in RTL). */
    fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float>, numeric: Set<Int> = emptySet(), totals: List<String>? = null, fontSize: Float = 9f) {
        val total = weights.sum()
        val widths = weights.map { (it / total * contentW) }
        val order = if (rtl) headers.indices.reversed().toList() else headers.indices.toList()
        fun drawRow(cells: List<String>, bold: Boolean, color: Int, shade: Int?) {
            val ps = paint(fontSize, bold, color)
            val layouts = cells.mapIndexed { i, t ->
                layout(t, ps, (widths[i] - 8).toInt(), if (i in numeric) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            }
            val h = (layouts.maxOf { it.height } + 6).toFloat()
            ensure(h)
            val c = canvas!!
            if (shade != null) c.drawRect(margin.toFloat(), y, margin + contentW, y + h, Paint().apply { this.color = shade })
            var x = margin.toFloat()
            for (i in order) {
                c.save(); c.translate(x + 4, y + 3); layouts[i].draw(c); c.restore()
                x += widths[i]
            }
            y += h
            c.drawLine(margin.toFloat(), y, margin + contentW, y, Paint().apply { this.color = line; strokeWidth = 0.5f })
        }
        drawRow(headers, true, ink, Color.parseColor("#E9EFF8"))
        rows.forEach { drawRow(it, false, ink, null) }
        totals?.let { drawRow(it, true, ink, Color.parseColor("#F3F7FC")) }
        y += 6f
    }

    fun signatureBlock(labels: List<String>) {
        ensure(90f)
        y += 40f
        val n = labels.size
        val w = contentW / n
        val c = canvas!!
        val p = Paint().apply { color = ink; strokeWidth = 0.8f }
        val idx = if (rtl) labels.indices.reversed().toList() else labels.indices.toList()
        idx.forEachIndexed { pos, i ->
            val x0 = margin + pos * w + 10
            c.drawLine(x0, y, x0 + w - 20, y, p)
            val l = layout(labels[i], paint(9f, false, muted), (w - 20).toInt(), Layout.Alignment.ALIGN_CENTER)
            c.save(); c.translate(x0, y + 3); l.draw(c); c.restore()
        }
        y += 30f
    }

    fun write(file: File) {
        if (page == null) newPage()
        page?.let { finishPage(it) }
        page = null
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
