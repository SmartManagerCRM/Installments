package com.smartmanager.installments.data.repo

import android.content.Context
import com.smartmanager.installments.R
import com.smartmanager.installments.core.payments.InstallmentStatusCalculator
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.PaymentKind
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.util.AppClock
import com.smartmanager.installments.util.AppLanguage
import com.smartmanager.installments.util.Formatters
import java.time.LocalDate
import java.time.ZoneId

/** A report already formatted as text so the SAME table feeds the screen, the PDF and the CSV export. */
data class ReportTable(
    val title: String,
    val subtitle: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val totals: List<String>? = null,
    /** column indexes that hold numbers/money (right aligned in LTR, left aligned in RTL) */
    val numericColumns: Set<Int> = emptySet(),
)

enum class ReportType(val key: String) {
    OUTSTANDING("outstanding"), COLLECTED("collected"), OVERDUE("overdue"), DUE("due"), COMPLETED("completed"),
    ACTIVE("active"), STATEMENT("statement"), PAYMENT_HISTORY("history");

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: OUTSTANDING }
}

class ReportBuilder(private val context: Context, private val db: AppDatabase, private val settings: SettingsRepository, private val clock: AppClock) {

    private suspend fun env(): Triple<Context, Formatters, AppSettings> {
        val s = settings.current()
        val lang = AppLanguage.current()
        return Triple(AppLanguage.localized(context), Formatters(lang, s.dateFormat, s.currency), s)
    }

    private fun period(f: Formatters, from: LocalDate, to: LocalDate) = f.date(from) + " – " + f.date(to)
    private fun millis(d: LocalDate, zone: ZoneId, endOfDay: Boolean) =
        (if (endOfDay) d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 else d.atStartOfDay(zone).toInstant().toEpochMilli())

    suspend fun build(type: ReportType, from: LocalDate, to: LocalDate, customerId: String? = null): ReportTable {
        val (ctx, f, s) = env()
        val cur = s.currency
        val today = clock.today()
        val zone = clock.zone()
        fun str(id: Int) = ctx.getString(id)
        return when (type) {
            ReportType.OUTSTANDING -> {
                val rows = db.dashboard().outstandingByCustomer(cur)
                ReportTable(str(R.string.report_outstanding), f.date(today), listOf(str(R.string.customer), str(R.string.phone), str(R.string.contracts), str(R.string.outstanding_balance)),
                    rows.map { listOf(it.customerName, it.phone, it.contracts.toString(), f.money(it.outstandingMinor, cur)) },
                    listOf(str(R.string.total), "", "", f.money(rows.sumOf { it.outstandingMinor }, cur)), setOf(2, 3))
            }
            ReportType.COLLECTED -> {
                val rows = db.payments().inPeriod(millis(from, zone, false), millis(to, zone, true), cur).filter { it.p.status == Status.POSTED && it.p.kind != PaymentKind.ADJUSTMENT }
                ReportTable(str(R.string.report_collected), period(f, from, to), listOf(str(R.string.payment_date), str(R.string.receipt_number), str(R.string.customer), str(R.string.contract), str(R.string.payment_method), str(R.string.amount)),
                    rows.map { listOf(f.dateTime(it.p.paidAt, zone), it.p.receiptNumber, it.customerName, it.contractNumber, methodName(ctx, it.p.method), f.money(it.p.amountMinor, cur)) },
                    listOf(str(R.string.total), "", "", "", "", f.money(rows.sumOf { it.p.amountMinor }, cur)), setOf(5))
            }
            ReportType.OVERDUE -> {
                val rows = db.installments().overdue(today.toEpochDay(), cur)
                ReportTable(str(R.string.report_overdue), f.date(today), listOf(str(R.string.customer), str(R.string.phone), str(R.string.contract), str(R.string.due_date), str(R.string.days_overdue_short), str(R.string.outstanding_balance)),
                    rows.map { listOf(it.customerName, it.phone, it.b.contractNumber + " #" + it.b.sequence, f.date(it.b.dueDate), (today.toEpochDay() - it.b.dueDate).toString(), f.money(it.b.outstandingMinor, cur)) },
                    listOf(str(R.string.total), "", "", "", "", f.money(rows.sumOf { it.b.outstandingMinor }, cur)), setOf(4, 5))
            }
            ReportType.DUE -> {
                val rows = db.installments().dueBetween(from.toEpochDay(), to.toEpochDay(), cur)
                ReportTable(str(R.string.report_due), period(f, from, to), listOf(str(R.string.due_date), str(R.string.customer), str(R.string.contract), str(R.string.installment), str(R.string.outstanding_balance)),
                    rows.map { listOf(f.date(it.b.dueDate), it.customerName, it.b.contractNumber, "#" + it.b.sequence, f.money(it.b.outstandingMinor, cur)) },
                    listOf(str(R.string.total), "", "", "", f.money(rows.sumOf { it.b.outstandingMinor }, cur)), setOf(4))
            }
            ReportType.COMPLETED, ReportType.ACTIVE -> {
                val status = if (type == ReportType.COMPLETED) Status.COMPLETED else Status.ACTIVE
                val rows = db.contracts().byStatus(status).filter { it.currency == cur }
                ReportTable(str(if (type == ReportType.COMPLETED) R.string.report_completed else R.string.report_active), f.date(today),
                    listOf(str(R.string.contract), str(R.string.customer), str(R.string.contract_date), str(R.string.financed_amount), str(R.string.outstanding_balance)),
                    rows.map { listOf(it.number, it.customerName, f.date(it.contractDate), f.money(it.financedMinor, cur), f.money(it.outstandingMinor, cur)) },
                    listOf(str(R.string.total), "", "", f.money(rows.sumOf { it.financedMinor }, cur), f.money(rows.sumOf { it.outstandingMinor }, cur)), setOf(3, 4))
            }
            ReportType.PAYMENT_HISTORY -> {
                val rows = db.payments().inPeriod(millis(from, zone, false), millis(to, zone, true), cur)
                ReportTable(str(R.string.report_payment_history), period(f, from, to), listOf(str(R.string.payment_date), str(R.string.receipt_number), str(R.string.customer), str(R.string.contract), str(R.string.amount), str(R.string.status)),
                    rows.map { listOf(f.dateTime(it.p.paidAt, zone), it.p.receiptNumber, it.customerName, it.contractNumber, f.money(it.p.amountMinor, cur), str(if (it.p.status == Status.REVERSED) R.string.reversed else R.string.posted)) },
                    listOf(str(R.string.total), "", "", "", f.money(rows.filter { it.p.status == Status.POSTED && it.p.kind != PaymentKind.ADJUSTMENT }.sumOf { it.p.amountMinor }, cur), ""), setOf(4))
            }
            ReportType.STATEMENT -> statement(customerId ?: error("customer required"), ctx, f, s, today)
        }
    }

    /** Chronological account statement: contract charges, payments and reversals with a running balance. */
    suspend fun statement(customerId: String, ctx: Context, f: Formatters, s: AppSettings, today: LocalDate): ReportTable {
        val customer = db.customers().get(customerId)!!
        val contractsList = db.contracts().observeForCustomerOnce(customerId)
        val payments = db.payments().forCustomer(customerId)
        data class Ev(val at: Long, val text: String, val debit: Long, val credit: Long)
        val zone = clock.zone()
        val ev = mutableListOf<Ev>()
        contractsList.filter { it.status != Status.DRAFT }.forEach { c ->
            val net = c.originalPriceMinor - c.discountMinor
            ev += Ev(LocalDate.ofEpochDay(c.contractDate).atStartOfDay(zone).toInstant().toEpochMilli(), ctx.getString(R.string.stmt_contract, c.number), net, 0)
        }
        payments.forEach { p ->
            val c = contractsList.firstOrNull { it.id == p.contractId }
            val label = ctx.getString(R.string.stmt_payment, p.receiptNumber, c?.number ?: "")
            ev += Ev(p.paidAt, label, 0, p.amountMinor)
            if (p.status == Status.REVERSED) ev += Ev(p.reversedAt ?: p.paidAt, ctx.getString(R.string.stmt_reversal, p.receiptNumber), p.amountMinor, 0)
        }
        var bal = 0L
        val cur = s.currency
        val rows = ev.sortedBy { it.at }.map { e ->
            bal += e.debit - e.credit
            listOf(f.date(java.time.Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()), e.text, if (e.debit > 0) f.amount(e.debit, cur) else "", if (e.credit > 0) f.amount(e.credit, cur) else "", f.amount(bal, cur))
        }
        return ReportTable(
            ctx.getString(R.string.account_statement) + " — " + customer.fullName, f.date(today) + " · " + customer.phone,
            listOf(ctx.getString(R.string.date), ctx.getString(R.string.description), ctx.getString(R.string.debit), ctx.getString(R.string.credit), ctx.getString(R.string.balance)),
            rows, listOf(ctx.getString(R.string.balance_due), "", "", "", f.money(bal, cur)), setOf(2, 3, 4),
        )
    }

    private fun methodName(ctx: Context, m: String) = ctx.getString(
        when (m) { "CASH" -> R.string.method_cash; "BANK_TRANSFER" -> R.string.method_bank; "CARD" -> R.string.method_card; "ADJUSTMENT" -> R.string.method_adjustment; else -> R.string.method_other },
    )

    companion object {
        /** UTF-8 with BOM so Excel opens Arabic correctly; cells starting with = + - @ are neutralised against CSV/formula injection. */
        fun toCsv(t: ReportTable): String {
            fun cell(v: String): String {
                val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
                return "\"" + safe.replace("\"", "\"\"") + "\""
            }
            val sb = StringBuilder("﻿")
            sb.append(cell(t.title)).append("\r\n").append(cell(t.subtitle)).append("\r\n")
            sb.append(t.columns.joinToString(",") { cell(it) }).append("\r\n")
            t.rows.forEach { r -> sb.append(r.joinToString(",") { cell(it) }).append("\r\n") }
            t.totals?.let { sb.append(it.joinToString(",") { c -> cell(c) }).append("\r\n") }
            return sb.toString()
        }
    }
}
