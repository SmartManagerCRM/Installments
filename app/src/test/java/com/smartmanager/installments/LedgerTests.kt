package com.smartmanager.installments

import com.smartmanager.installments.core.payments.AllocationLine
import com.smartmanager.installments.core.payments.AllocationRule
import com.smartmanager.installments.core.schedule.ScheduledInstallment
import com.smartmanager.installments.data.db.PaymentKind
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.data.repo.LedgerError
import com.smartmanager.installments.data.repo.LedgerException
import com.smartmanager.installments.data.repo.PaymentRequest
import com.smartmanager.installments.data.repo.WriteGuard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerTests {
    private val env = Env()
    private val ledger get() = env.ledger
    private val db get() = env.db

    @After fun close() = env.db.close()

    private fun pay(contract: String, amount: Long, key: String = java.util.UUID.randomUUID().toString(), lines: List<AllocationLine>? = null, confirm: Boolean = true) =
        PaymentRequest(key, contract, amount, "CASH", manualLines = lines, confirmPossibleDuplicate = confirm)

    private fun code(block: () -> Unit) = expectThrows<LedgerException>(block).error

    // ---------------------------------------------------------------------------------------- contracts

    @Test fun createsContractWithScheduleDownPaymentAndNumbers() = runBlocking {
        val cust = env.newCustomer()
        val id = env.fridgeContract(cust)
        val c = db.contracts().get(id)!!
        assertEquals("C-2026-0001", c.number)
        assertEquals(Status.ACTIVE, c.status)
        assertEquals(2_000_000, c.financedMinor)
        val inst = db.contracts().balances(id)
        assertEquals(10, inst.size)
        assertTrue(inst.all { it.amountMinor == 200_000L })
        assertEquals(2_000_000, env.outstanding(id))
        // The down payment is a real payment with its own receipt, not an installment payment.
        val dp = db.payments().forCustomer(cust).single()
        assertEquals(PaymentKind.DOWN_PAYMENT, dp.kind); assertEquals(400_000, dp.amountMinor); assertEquals("R-2026-00001", dp.receiptNumber)
        assertEquals(400_000, db.contracts().observeCollected(id).first())
        // second contract gets the next number
        assertEquals("C-2026-0002", db.contracts().get(env.fridgeContract(cust))!!.number)
    }

    @Test fun rejectsContractWhenScheduleDoesNotMatchFinancedAmount() = runBlocking {
        val cust = env.newCustomer()
        val bad = listOf(ScheduledInstallment(1, LocalDate.of(2026, 4, 1), 1_999_000))
        val e = code { runBlocking {
            ledger.createContract(com.smartmanager.installments.data.repo.NewContract(cust, LocalDate.of(2026, 3, 1), "TND",
                listOf(com.smartmanager.installments.data.repo.NewItem(null, "x", 1, 2_400_000)), 0, 400_000,
                com.smartmanager.installments.core.schedule.Frequency.CUSTOM, bad, "", "", true))
        } }
        assertEquals(LedgerError.SCHEDULE_INVALID, e)
        assertEquals(0, db.bulk().contracts().size) // nothing half-written
    }

    @Test fun draftLifecycle() = runBlocking {
        val cust = env.newCustomer()
        val id = env.fridgeContract(cust, activate = false)
        assertEquals(Status.DRAFT, db.contracts().get(id)!!.status)
        assertEquals(0, db.payments().forCustomer(cust).size) // no down-payment receipt for a draft
        val dash = db.dashboard()
        assertEquals(0L, dash.outstanding("TND").first()) // drafts are excluded from balances
        // edit the draft (same number) and activate it
        val draft = ledger.loadDraft(id)!!
        val number = db.contracts().get(id)!!.number
        ledger.createContract(draft.copy(activate = true), existingDraftId = id)
        assertEquals(number, db.contracts().get(id)!!.number)
        assertEquals(Status.ACTIVE, db.contracts().get(id)!!.status)
        assertEquals(10, db.contracts().installments(id).size)
        assertEquals(2_000_000L, dash.outstanding("TND").first())
        // an active contract cannot be deleted as a draft
        assertEquals(LedgerError.ONLY_DRAFT, code { runBlocking { ledger.deleteDraft(id) } })
        val d2 = env.fridgeContract(cust, activate = false)
        ledger.deleteDraft(d2)
        assertEquals(null, db.contracts().get(d2))
    }

    @Test fun cancelledContractsLeaveTheBalances() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        ledger.cancelContract(id, "customer returned goods")
        assertEquals(Status.CANCELLED, db.contracts().get(id)!!.status)
        assertEquals(0L, db.dashboard().outstanding("TND").first())
        assertEquals(LedgerError.CONTRACT_NOT_ACTIVE, code { runBlocking { ledger.recordPayment(pay(id, 1000)) } })
        assertEquals(1, db.payments().forCustomer(cust).size) // down payment stays on record
    }

    // ---------------------------------------------------------------------------------------- payments

    @Test fun fullPartialAndMultiInstallmentPayments() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        // full installment
        val p1 = ledger.recordPayment(pay(id, 200_000))
        assertEquals(1_800_000, p1.remainingOutstandingMinor)
        assertEquals(1, p1.allocations.size)
        // partial: 4 x 50,000 on the next installment
        repeat(4) { ledger.recordPayment(pay(id, 50_000)) }
        val b = db.contracts().balances(id)
        assertEquals(400_000, b[0].paidMinor + b[1].paidMinor)
        assertEquals(200_000, b[1].paidMinor)
        // payment covering several installments: 450,000 = 200 + 200 + 50
        val multi = ledger.recordPayment(pay(id, 450_000))
        assertEquals(listOf(200_000L, 200_000L, 50_000L), multi.allocations.map { it.amountMinor })
        assertEquals(2_000_000 - 200_000 - 200_000 - 450_000, env.outstanding(id))
        assertEquals(multi.allocations.sumOf { it.amountMinor }, multi.payment.amountMinor)
    }

    @Test fun manualAllocationChoosesInstallments() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val b = db.contracts().balances(id)
        val out = ledger.recordPayment(pay(id, 300_000, lines = listOf(AllocationLine(b[2].installmentId, 200_000), AllocationLine(b[0].installmentId, 100_000))))
        assertEquals(2, out.allocations.size)
        val after = db.contracts().balances(id)
        assertEquals(100_000, after[0].paidMinor); assertEquals(0, after[1].paidMinor); assertEquals(200_000, after[2].paidMinor)
        // allocation not matching the amount is refused
        assertEquals(LedgerError.ALLOCATION_INVALID, code { runBlocking { ledger.recordPayment(pay(id, 300_000, lines = listOf(AllocationLine(b[1].installmentId, 100_000)))) } })
    }

    @Test fun overpaymentAndInvalidAmountsAreRefusedAndWriteNothing() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val before = db.payments().forCustomer(cust).size
        assertEquals(LedgerError.EXCEEDS_OUTSTANDING, code { runBlocking { ledger.recordPayment(pay(id, 2_000_001)) } })
        assertEquals(LedgerError.INVALID_AMOUNT, code { runBlocking { ledger.recordPayment(pay(id, 0)) } })
        assertEquals(LedgerError.INVALID_AMOUNT, code { runBlocking { ledger.recordPayment(pay(id, -5)) } })
        assertEquals(before, db.payments().forCustomer(cust).size)
        assertEquals(2_000_000, env.outstanding(id))
    }

    @Test fun duplicateSubmissionWithSameKeyPostsOnce() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val first = ledger.recordPayment(pay(id, 200_000, key = "form-1"))
        val second = ledger.recordPayment(pay(id, 200_000, key = "form-1")) // e.g. double tap / retry
        assertFalse(first.alreadyPosted); assertTrue(second.alreadyPosted)
        assertEquals(first.payment.id, second.payment.id)
        assertEquals(1_800_000, env.outstanding(id))
        assertEquals(1, db.payments().forCustomer(cust).count { it.kind == PaymentKind.PAYMENT })
    }

    @Test fun sameAmountShortlyAfterNeedsConfirmation() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        ledger.recordPayment(pay(id, 100_000, confirm = false))
        assertEquals(LedgerError.POSSIBLE_DUPLICATE, code { runBlocking { ledger.recordPayment(pay(id, 100_000, confirm = false)) } })
        assertEquals(1_900_000, env.outstanding(id))
        ledger.recordPayment(pay(id, 100_000, confirm = true)) // explicit confirmation
        assertEquals(1_800_000, env.outstanding(id))
        env.clock.advanceDays(1) // outside the window no confirmation is needed
        ledger.recordPayment(pay(id, 100_000, confirm = false))
        assertEquals(1_700_000, env.outstanding(id))
    }

    @Test fun payingEverythingCompletesTheContract() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val out = ledger.recordPayment(pay(id, 2_000_000))
        assertTrue(out.contractCompleted); assertEquals(0, out.remainingOutstandingMinor)
        assertEquals(Status.COMPLETED, db.contracts().get(id)!!.status)
        assertEquals(LedgerError.CONTRACT_NOT_ACTIVE, code { runBlocking { ledger.recordPayment(pay(id, 1)) } })
    }

    @Test fun receiptNumbersAreUniqueAndSequential() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val nums = (1..5).map { ledger.recordPayment(pay(id, 10_000)).payment.receiptNumber }
        assertEquals(nums.toSet().size, 5)
        assertEquals(listOf("R-2026-00002", "R-2026-00003", "R-2026-00004", "R-2026-00005", "R-2026-00006"), nums) // 00001 = down payment
    }

    // ---------------------------------------------------------------------------------------- early settlement

    @Test fun earlySettlementWithDiscount() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        ledger.recordPayment(pay(id, 200_000))
        val out = ledger.settleContract("settle-1", id, discountMinor = 100_000, method = "CASH", reference = null, notes = "")
        assertEquals(1_700_000, out.payment.amountMinor) // 1,800,000 outstanding - 100,000 discount
        assertEquals(0, env.outstanding(id))
        assertEquals(Status.COMPLETED, db.contracts().get(id)!!.status)
        val adj = db.payments().forCustomer(cust).single { it.kind == PaymentKind.ADJUSTMENT }
        assertEquals(100_000, adj.amountMinor)
        // the discount is not money collected: down 400,000 + 200,000 + 1,700,000
        assertEquals(2_300_000, db.contracts().observeCollected(id).first())
        // retry with the same key does not charge twice
        assertTrue(ledger.settleContract("settle-1", id, 100_000, "CASH", null, "").alreadyPosted)
        assertEquals(LedgerError.DISCOUNT_INVALID, code { runBlocking { ledger.settleContract("x", env.fridgeContract(cust), 2_000_000, "CASH", null, "") } })
    }

    // ---------------------------------------------------------------------------------------- reversal

    @Test fun reversalKeepsHistoryAndRestoresBalance() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val p = ledger.recordPayment(pay(id, 2_000_000)).payment
        assertEquals(Status.COMPLETED, db.contracts().get(id)!!.status)
        ledger.reversePayment(p.id, "wrong amount entered")
        val reversed = db.payments().get(p.id)!!
        assertEquals(Status.REVERSED, reversed.status); assertEquals("wrong amount entered", reversed.reversalReason); assertNotNull(reversed.reversedAt)
        assertEquals(10, db.payments().allocations(p.id).size) // allocations kept for the audit trail
        assertEquals(2_000_000, env.outstanding(id))          // balance is back
        assertEquals(Status.ACTIVE, db.contracts().get(id)!!.status) // re-opened
        assertEquals(400_000, db.contracts().observeCollected(id).first()) // only the down payment still counts
        assertEquals(LedgerError.ALREADY_REVERSED, code { runBlocking { ledger.reversePayment(p.id, "again") } })
        assertEquals(LedgerError.VALIDATION, code { runBlocking { ledger.reversePayment(ledger.recordPayment(pay(id, 100)).payment.id, " ") } })
        // correction = reverse + record the right payment
        ledger.recordPayment(pay(id, 1_500_000))
        assertEquals(499_900, env.outstanding(id)) // 100 minor units from the small payment above are still posted
    }

    @Test fun auditTrailRecordsFinancialEvents() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val p = ledger.recordPayment(pay(id, 100_000)).payment
        ledger.reversePayment(p.id, "test")
        val actions = db.audit().recent(50).map { it.entityType + ":" + it.action }
        assertTrue("PAYMENT:POSTED" in actions && "PAYMENT:REVERSED" in actions && "CONTRACT:CREATED_ACTIVE" in actions)
    }

    // ---------------------------------------------------------------------------------------- reschedule

    @Test fun reschedulePreservesTheOriginalTerms() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val early = ledger.recordPayment(pay(id, 300_000)).payment // 1 full + 100k of #2
        val originalRows = db.contracts().installments(id)
        val newSchedule = listOf(
            ScheduledInstallment(1, LocalDate.of(2026, 9, 1), 850_000), ScheduledInstallment(2, LocalDate.of(2026, 10, 1), 850_000),
        )
        assertEquals(LedgerError.SCHEDULE_INVALID, code { runBlocking { ledger.reschedule(id, newSchedule.take(1), "x") } }) // must equal outstanding
        ledger.reschedule(id, newSchedule, "customer asked for fewer, larger installments")
        val all = db.contracts().installments(id)
        assertEquals(originalRows.size + 2, all.size)                  // nothing deleted
        assertEquals(originalRows.map { it.amountMinor }, all.filter { it.revision == 0 }.map { it.amountMinor }) // original amounts untouched
        assertEquals(1, all.count { it.revision == 0 && it.state == Status.ACTIVE })      // the fully paid one stays active
        assertEquals(9, all.count { it.revision == 0 && it.state == Status.RESCHEDULED })
        assertEquals(1_700_000, env.outstanding(id))                    // 2,000,000 - 300,000
        assertEquals(2, db.contracts().balances(id).count { it.revision == 1 })
        assertEquals(1, db.contracts().get(id)!!.rescheduleCount)
        assertEquals(2_000_000, db.contracts().get(id)!!.financedMinor) // original financed amount not rewritten
        val ev = db.audit().recent(50).first { it.action == "RESCHEDULED" }
        assertTrue(ev.detail.contains("fewer, larger") && ev.detail.contains("old=[") && ev.detail.contains("new=["))
        // payments made before the reschedule on superseded rows cannot be reversed (would corrupt the new schedule)
        assertEquals(LedgerError.REVERSAL_BLOCKED_RESCHEDULED, code { runBlocking { ledger.reversePayment(early.id, "oops") } })
        // new payments go to the new schedule and can complete the contract
        val done = ledger.recordPayment(pay(id, 1_700_000))
        assertTrue(done.contractCompleted)
    }

    // ---------------------------------------------------------------------------------------- overdue / dashboard

    @Test fun overdueAndDueTodayFollowTheLocalDate() = runBlocking {
        val cust = env.newCustomer("Late Customer"); val id = env.fridgeContract(cust, first = LocalDate.of(2026, 3, 14)) // due yesterday, then monthly
        val dash = db.dashboard()
        val today = env.clock.today().toEpochDay()
        val od = dash.overdue(today, "TND").first()
        assertEquals(200_000, od.amountMinor); assertEquals(1, od.count)
        assertEquals(0, dash.dueToday(today, "TND").first().count)
        val row = dash.overdueCustomers(today, "TND", 5).first().single()
        assertEquals(1L, today - row.oldestDueDate)       // one day overdue
        // move the device date to the due date of installment #2 (2026-04-14): #1 is 31 days late, #2 is due today
        env.clock.advanceDays(30)
        val t2 = env.clock.today().toEpochDay()
        assertEquals(LocalDate.of(2026, 4, 14), env.clock.today())
        assertEquals(200_000, dash.dueToday(t2, "TND").first().amountMinor)
        assertEquals(200_000, dash.overdue(t2, "TND").first().amountMinor)
        // paying the late one clears the overdue figure immediately
        ledger.recordPayment(pay(id, 200_000))
        assertEquals(0, dash.overdue(t2, "TND").first().amountMinor)
        // partially paid installments still count as overdue for the unpaid part
        env.clock.advanceDays(1)
        ledger.recordPayment(pay(id, 50_000, confirm = true))
        assertEquals(150_000, dash.overdue(env.clock.today().toEpochDay(), "TND").first().amountMinor)
    }

    @Test fun collectionRowsAggregatePerCustomer() = runBlocking {
        val a = env.newCustomer("A"); env.fridgeContract(a, first = LocalDate.of(2026, 1, 1)) // late since January
        val b = env.newCustomer("B", "20999999"); val cb = env.fridgeContract(b, first = LocalDate.of(2026, 3, 15)) // due today
        ledger.recordPayment(pay(cb, 50_000))
        val rows = db.dashboard().collectionRows(env.clock.today().toEpochDay(), "TND").first().associateBy { it.customerName }
        assertEquals(2, rows.size)
        assertTrue(rows.getValue("A").overdueMinor > 0 && rows.getValue("A").oldestOverdueDate == LocalDate.of(2026, 1, 1).toEpochDay())
        assertEquals(150_000, rows.getValue("B").dueTodayMinor); assertEquals(1, rows.getValue("B").hasPartial)
    }

    // ---------------------------------------------------------------------------------------- customers & guard

    @Test fun customerWithHistoryCannotBeDeleted() = runBlocking {
        val cust = env.newCustomer(); env.fridgeContract(cust)
        assertEquals(LedgerError.HAS_HISTORY, code { runBlocking { env.customers.deleteIfNoHistory(cust) } })
        env.customers.setArchived(cust, true)
        assertEquals(Status.ARCHIVED, db.customers().get(cust)!!.status)
        assertEquals(LedgerError.CUSTOMER_ARCHIVED, code { runBlocking { env.fridgeContract(cust) } })
        val fresh = env.newCustomer("No History", "20000000")
        env.customers.deleteIfNoHistory(fresh)
        assertEquals(null, db.customers().get(fresh))
    }

    @Test fun duplicateCustomerDetectionIsAWarningNotABlock() = runBlocking {
        env.newCustomer("Sami Gharbi", "20123456")
        assertEquals(1, env.customers.findSimilar("sami gharbi", "99999999", null).size) // same name
        assertEquals(1, env.customers.findSimilar("Other", "20123456", null).size)       // same phone
        env.newCustomer("Sami Gharbi", "20123456") // the retailer may still save a legitimate namesake
        assertEquals(2, db.customers().search("Sami").size)
    }

    @Test fun readOnlyGuardBlocksEveryWrite() = runBlocking {
        val locked = Env(guard = WriteGuard { throw LedgerException(LedgerError.LICENSE_READ_ONLY) })
        try {
            assertEquals(LedgerError.LICENSE_READ_ONLY, code { runBlocking { locked.newCustomer() } })
            assertEquals(LedgerError.LICENSE_READ_ONLY, code { runBlocking { locked.ledger.recordPayment(pay("x", 1)) } })
            assertEquals(LedgerError.LICENSE_READ_ONLY, code { runBlocking { locked.ledger.cancelContract("x", "r") } })
            assertEquals(0, locked.db.bulk().customers().size)
        } finally { locked.db.close() }
    }

    @Test fun repeatedOperationsKeepTotalsConsistent() = runBlocking {
        val cust = env.newCustomer(); val id = env.fridgeContract(cust)
        val rnd = java.util.Random(7)
        var expected = 2_000_000L
        repeat(60) {
            val amt = (rnd.nextInt(60_000) + 1).toLong().coerceAtMost(expected)
            if (expected == 0L) return@repeat
            val p = ledger.recordPayment(pay(id, amt)).payment
            expected -= amt
            if (rnd.nextInt(4) == 0) { ledger.reversePayment(p.id, "r$it"); expected += amt }
            assertEquals(expected, env.outstanding(id))
        }
        // global invariants: no installment overpaid, allocations of posted payments never exceed the payment
        db.contracts().balances(id).forEach { assertTrue(it.paidMinor <= it.amountMinor) }
        val collected = db.contracts().observeCollected(id).first() ?: 0
        assertEquals(2_400_000 - expected, collected) // price - outstanding == collected (incl. down payment)
    }
}
