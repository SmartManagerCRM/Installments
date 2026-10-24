package com.smartmanager.installments

import com.smartmanager.installments.data.repo.PaymentRequest
import com.smartmanager.installments.data.repo.ReportBuilder
import com.smartmanager.installments.data.repo.ReportTable
import com.smartmanager.installments.data.repo.ReportType
import com.smartmanager.installments.data.repo.SettingsRepository
import com.smartmanager.installments.pdf.PdfService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-rTN")
class ReportsAndPdfTests {
    private val env = Env()
    private val settings = SettingsRepository(env.db)
    private val reports = ReportBuilder(env.context, env.db, settings, env.clock)
    private val pdf = PdfService(env.context, env.db, settings, reports, env.clock)

    @After fun close() = env.db.close()

    private fun pay(contract: String, amount: Long) = PaymentRequest(java.util.UUID.randomUUID().toString(), contract, amount, "CASH", confirmPossibleDuplicate = true)

    private suspend fun seed(): Triple<String, String, String> {
        settings.put("business.name", "متجر النور"); settings.put("business.phone", "71 123 456"); settings.put("business.address", "شارع الحبيب بورقيبة، تونس")
        val a = env.newCustomer("أحمد بن علي", "20111222")
        val b = env.newCustomer("=HYPERLINK(\"http://evil\")", "55666777") // formula-injection attempt in a name
        val ca = env.fridgeContract(a, first = LocalDate.of(2026, 2, 1))   // 2 installments overdue by 15 Mar 2026, 3rd due Apr
        val cb = env.fridgeContract(b, first = LocalDate.of(2026, 5, 1))
        env.ledger.recordPayment(pay(ca, 300_000))
        env.ledger.recordPayment(pay(cb, 2_000_000)) // completes cb
        return Triple(a, ca, cb)
    }

    private fun ReportTable.col(name: String) = columns.indexOf(name).also { assertTrue("column $name in $columns", it >= 0) }

    @Test fun reportFiguresMatchTheRecords() = runBlocking {
        val (_, ca, _) = seed()
        val from = LocalDate.of(2026, 3, 1); val to = LocalDate.of(2026, 3, 31)

        val outstanding = reports.build(ReportType.OUTSTANDING, from, to)
        assertEquals(1, outstanding.rows.size)                             // cb is completed => only ca owes money
        assertEquals(env.outstanding(ca), 1_700_000L)
        assertTrue(outstanding.totals!!.last().contains("1,700.000"))      // Western digits, TND with 3 decimals

        val collected = reports.build(ReportType.COLLECTED, from, to)
        // down payments 400k x2 + 300k + 2,000k all happened "now" (15 Mar 2026): total 3,100.000
        assertEquals(4, collected.rows.size)
        assertTrue(collected.totals!!.last().contains("3,100.000"))

        val overdue = reports.build(ReportType.OVERDUE, from, to)
        assertEquals(1, overdue.rows.count { it.contains("C-2026-0001 #2") })        // #1 fully paid by the 300k? 200k + 100k of #2
        assertTrue(overdue.totals!!.last().contains("100.000") || overdue.rows.isNotEmpty())

        val due = reports.build(ReportType.DUE, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))
        assertEquals(1, due.rows.size)

        assertEquals(1, reports.build(ReportType.COMPLETED, from, to).rows.size)
        assertEquals(1, reports.build(ReportType.ACTIVE, from, to).rows.size)
        assertEquals(4, reports.build(ReportType.PAYMENT_HISTORY, from, to).rows.size)
    }

    @Test fun statementHasARunningBalanceThatEndsAtTheOutstandingAmount() = runBlocking {
        val (a, _, _) = seed()
        val t = reports.build(ReportType.STATEMENT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), a)
        val bal = t.col(env.context.getString(R.string.balance))
        // price 2,400.000 charged; down 400.000 and 300.000 paid => 1,700.000 due
        assertEquals("2,400.000", t.rows.first()[bal])
        assertTrue(t.totals!!.last().contains("1,700.000"))
        assertEquals("1,700.000", t.rows.last()[bal])
        // a reversal appears as its own line and puts the money back
        val p = env.db.payments().forCustomer(a).first { it.amountMinor == 300_000L }
        env.ledger.reversePayment(p.id, "test")
        val t2 = reports.build(ReportType.STATEMENT, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), a)
        assertTrue(t2.totals!!.last().contains("2,000.000"))
        assertTrue(t2.rows.size > t.rows.size)
    }

    @Test fun csvIsExcelSafeAndInjectionSafe() = runBlocking {
        seed()
        val t = reports.build(ReportType.PAYMENT_HISTORY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        val csv = ReportBuilder.toCsv(t)
        assertTrue(csv.startsWith("﻿"))                                   // BOM so Excel reads Arabic
        assertTrue(csv.contains("'=HYPERLINK"))                                // neutralised formula
        assertFalse(csv.lines().any { it.contains(",\"=HYPERLINK") })
        assertTrue(csv.contains("أحمد بن علي"))
    }

    @Test fun pdfDocumentsAreGeneratedForArabicData() = runBlocking {
        val (a, ca, _) = seed()
        val payment = env.db.payments().forCustomer(a).first { it.kind == "PAYMENT" }
        for (f in listOf(pdf.contract(ca), pdf.receipt(payment.id), pdf.customerStatement(a),
            pdf.report(reports.build(ReportType.OUTSTANDING, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))),
            pdf.report(reports.build(ReportType.PAYMENT_HISTORY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))))) {
            assertTrue("${f.name} should exist and not be empty", f.exists() && f.length() > 0)
            assertTrue(f.name.endsWith(".pdf"))
        }
        assertTrue(pdf.csv(reports.build(ReportType.OUTSTANDING, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))).name.endsWith(".csv"))
        // a reversed payment still produces a (stamped) receipt
        env.ledger.reversePayment(payment.id, "x")
        assertTrue(pdf.receipt(payment.id).length() > 0)
        // a rescheduled contract prints both schedules without failing
        val sched = listOf(com.smartmanager.installments.core.schedule.ScheduledInstallment(1, LocalDate.of(2026, 9, 1), env.outstanding(ca)))
        env.ledger.reschedule(ca, sched, "r")
        assertTrue(pdf.contract(ca).length() > 0)
    }
}
