package com.smartmanager.installments.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Base64
import androidx.core.content.FileProvider
import com.smartmanager.installments.R
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.PaymentKind
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.data.repo.AppSettings
import com.smartmanager.installments.data.repo.ReportBuilder
import com.smartmanager.installments.data.repo.ReportTable
import com.smartmanager.installments.data.repo.SettingsRepository
import com.smartmanager.installments.util.AppClock
import com.smartmanager.installments.util.AppLanguage
import com.smartmanager.installments.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Generates contracts, receipts, statements and reports as PDF (and CSV) in the app cache and shares them via FileProvider. */
class PdfService(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val reports: ReportBuilder,
    private val clock: AppClock,
) {
    private class Env(val ctx: Context, val s: AppSettings, val f: Formatters, val rtl: Boolean, val logo: Bitmap?)

    private suspend fun env(): Env {
        val s = settings.current()
        val lang = AppLanguage.current()
        val logo = s.logoBase64?.let { runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull() }
        return Env(AppLanguage.localized(context), s, Formatters(lang, s.dateFormat, s.currency), lang == "ar", logo)
    }

    private fun outFile(name: String, ext: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Do not keep customer documents around: purge anything older than a day.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        return File(dir, name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + "." + ext)
    }

    private fun PdfDoc.business(e: Env) = logoAndBusiness(e.logo, e.s.businessName.ifBlank { e.ctx.getString(R.string.app_name) }, listOf(e.s.businessPhone, e.s.businessAddress))

    // ------------------------------------------------------------------------------------------------ contract
    suspend fun contract(contractId: String): File = withContext(Dispatchers.IO) {
        val e = env(); val ctx = e.ctx; val f = e.f
        val c = db.contracts().get(contractId)!!
        val customer = db.customers().get(c.customerId)!!
        val items = db.contracts().items(contractId)
        val inst = db.contracts().balances(contractId)
        val all = db.contracts().installments(contractId)
        // Print the ORIGINAL (revision 0) schedule as agreed; later revisions are listed separately.
        val original = all.filter { it.revision == 0 }
        val revised = all.filter { it.revision > 0 }
        val cur = c.currency
        val doc = PdfDoc(e.rtl, footerText = c.number + " · " + e.s.businessName)
        doc.newPage(); doc.business(e)
        doc.title(ctx.getString(R.string.pdf_contract_title))
        doc.keyValue(ctx.getString(R.string.contract_number), c.number, true)
        doc.keyValue(ctx.getString(R.string.contract_date), f.date(c.contractDate))
        doc.spacer(4f); doc.text(ctx.getString(R.string.customer), 11f, true)
        doc.keyValue(ctx.getString(R.string.full_name), customer.fullName)
        doc.keyValue(ctx.getString(R.string.phone), customer.phone)
        if (customer.address.isNotBlank()) doc.keyValue(ctx.getString(R.string.address), customer.address)
        customer.nationalRef?.let { doc.keyValue(ctx.getString(R.string.national_ref), it) }
        doc.spacer(6f); doc.text(ctx.getString(R.string.products), 11f, true)
        doc.table(listOf(ctx.getString(R.string.description), ctx.getString(R.string.quantity), ctx.getString(R.string.unit_price), ctx.getString(R.string.amount)),
            items.map { listOf(it.description + (it.serialNumber?.let { s -> " (S/N $s)" } ?: ""), it.quantity.toString(), f.amount(it.unitPriceMinor, cur), f.amount(it.quantity * it.unitPriceMinor, cur)) },
            listOf(5f, 1.2f, 2f, 2f), setOf(1, 2, 3))
        doc.keyValue(ctx.getString(R.string.original_price), f.money(c.originalPriceMinor, cur))
        if (c.discountMinor > 0) doc.keyValue(ctx.getString(R.string.discount), "- " + f.money(c.discountMinor, cur))
        doc.keyValue(ctx.getString(R.string.down_payment), f.money(c.downPaymentMinor, cur))
        doc.keyValue(ctx.getString(R.string.financed_amount), f.money(c.financedMinor, cur), true)
        doc.spacer(6f); doc.text(ctx.getString(R.string.installment_schedule), 11f, true)
        doc.table(listOf("#", ctx.getString(R.string.due_date), ctx.getString(R.string.amount)),
            original.map { listOf(it.sequence.toString(), f.date(it.dueDate), f.amount(it.amountMinor, cur)) },
            listOf(1f, 3f, 3f), setOf(2), listOf("", ctx.getString(R.string.total), f.money(original.sumOf { it.amountMinor }, cur)))
        if (revised.isNotEmpty()) {
            doc.text(ctx.getString(R.string.pdf_rescheduled_note, c.rescheduleCount), 9f, false, android.graphics.Color.parseColor("#B45F00"))
            doc.table(listOf("#", ctx.getString(R.string.due_date), ctx.getString(R.string.amount)),
                revised.filter { it.state == Status.ACTIVE }.map { listOf(it.sequence.toString(), f.date(it.dueDate), f.amount(it.amountMinor, cur)) }, listOf(1f, 3f, 3f), setOf(2))
        }
        doc.spacer(4f); doc.text(ctx.getString(R.string.contract_terms), 11f, true)
        doc.text(c.terms.ifBlank { e.s.contractTerms }, 9.5f)
        doc.text(ctx.getString(R.string.terms_legal_notice), 8f, false, android.graphics.Color.parseColor("#B45F00"))
        if (c.notes.isNotBlank()) { doc.text(ctx.getString(R.string.notes), 10f, true); doc.text(c.notes, 9.5f) }
        doc.signatureBlock(listOf(ctx.getString(R.string.sign_seller), ctx.getString(R.string.sign_customer)))
        val file = outFile("contract-${c.number}", "pdf"); doc.write(file); file
    }

    // ------------------------------------------------------------------------------------------------ receipt
    suspend fun receipt(paymentId: String): File = withContext(Dispatchers.IO) {
        val e = env(); val ctx = e.ctx; val f = e.f
        val p = db.payments().get(paymentId)!!
        val customer = db.customers().get(p.customerId)!!
        val contract = db.contracts().get(p.contractId)!!
        val allocs = db.payments().allocations(paymentId)
        val balances = db.contracts().balances(p.contractId) // ACTIVE rows only
        val cur = p.currency
        // Balance right after THIS payment: installments minus allocations of posted payments created up to this one.
        val upTo = db.payments().allocationsForContract(p.contractId).filter { it.paymentStatus == Status.POSTED && it.paymentCreatedAt <= p.createdAt }.sumOf { it.a.amountMinor }
        val remaining = (balances.sumOf { it.amountMinor } - upTo).coerceAtLeast(0)
        val doc = PdfDoc(e.rtl, 420, 595, 30, footerText = p.receiptNumber + " · " + e.s.businessName)
        doc.newPage(); doc.business(e)
        doc.title(ctx.getString(R.string.pdf_receipt_title))
        if (p.status == Status.REVERSED) doc.text(ctx.getString(R.string.pdf_reversed_stamp), 12f, true, android.graphics.Color.parseColor("#E5484D"))
        doc.keyValue(ctx.getString(R.string.receipt_number), p.receiptNumber, true)
        doc.keyValue(ctx.getString(R.string.payment_date), f.dateTime(p.paidAt))
        doc.keyValue(ctx.getString(R.string.customer), customer.fullName)
        doc.keyValue(ctx.getString(R.string.contract_number), contract.number)
        doc.keyValue(ctx.getString(R.string.payment_method), methodName(ctx, p.method))
        p.reference?.let { doc.keyValue(ctx.getString(R.string.reference_number), it) }
        doc.spacer(6f); doc.rule()
        doc.keyValue(ctx.getString(R.string.amount_received), f.money(p.amountMinor, cur), true, android.graphics.Color.parseColor("#1FA463"))
        doc.rule()
        if (allocs.isNotEmpty()) {
            doc.text(ctx.getString(R.string.installment_allocation), 10f, true)
            val rows = allocs.map { a ->
                val b = db.contracts().balanceOf(a.installmentId)
                listOf(b?.let { "#${it.sequence}" } ?: "", b?.let { f.date(it.dueDate) } ?: "", f.amount(a.amountMinor, cur))
            }
            doc.table(listOf("#", ctx.getString(R.string.due_date), ctx.getString(R.string.amount)), rows, listOf(1f, 3f, 3f), setOf(2))
        } else if (p.kind == PaymentKind.DOWN_PAYMENT) doc.text(ctx.getString(R.string.down_payment), 9.5f)
        doc.keyValue(ctx.getString(R.string.remaining_balance), f.money(remaining, cur), true)
        if (p.notes.isNotBlank()) doc.keyValue(ctx.getString(R.string.notes), p.notes)
        if (e.s.receiptFooter.isNotBlank()) { doc.spacer(8f); doc.text(e.s.receiptFooter, 9f, false, android.graphics.Color.parseColor("#5B6B84"), center = true) }
        val file = outFile("receipt-${p.receiptNumber}", "pdf"); doc.write(file); file
    }

    suspend fun customerStatement(customerId: String): File = withContext(Dispatchers.IO) {
        val e = env()
        report(reports.statement(customerId, e.ctx, e.f, e.s, clock.today()))
    }

    // ------------------------------------------------------------------------------------------------ report / csv
    suspend fun report(t: ReportTable): File = withContext(Dispatchers.IO) {
        val e = env()
        val landscape = t.columns.size >= 6
        val doc = if (landscape) PdfDoc(e.rtl, 842, 595, 30, footerText = t.title) else PdfDoc(e.rtl, footerText = t.title)
        doc.newPage(); doc.business(e)
        doc.title(t.title); doc.text(t.subtitle, 9.5f, false, android.graphics.Color.parseColor("#5B6B84"))
        val weights = t.columns.indices.map { if (it in t.numericColumns) 1.3f else 2f }
        doc.table(t.columns, t.rows, weights, t.numericColumns, t.totals, 8.5f)
        val file = outFile("report-" + t.title.take(40), "pdf"); doc.write(file); file
    }

    suspend fun csv(t: ReportTable): File = withContext(Dispatchers.IO) {
        val file = outFile("report-" + t.title.take(40), "csv")
        file.writeText(ReportBuilder.toCsv(t), Charsets.UTF_8)
        file
    }

    private fun methodName(ctx: Context, m: String) = ctx.getString(
        when (m) { "CASH" -> R.string.method_cash; "BANK_TRANSFER" -> R.string.method_bank; "CARD" -> R.string.method_card; "ADJUSTMENT" -> R.string.method_adjustment; else -> R.string.method_other },
    )

    // ------------------------------------------------------------------------------------------------ share / print
    fun share(ctx: Context, file: File, mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun print(ctx: Context, file: File, jobName: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true)
            }

            override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                try {
                    file.inputStream().use { i -> java.io.FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) } }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (ex: Exception) {
                    cb.onWriteFailed(ex.message)
                }
            }
        }, null)
    }
}
