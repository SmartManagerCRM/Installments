package com.smartmanager.installments.core

import com.smartmanager.installments.core.collection.*
import com.smartmanager.installments.core.contracts.*
import com.smartmanager.installments.core.money.*
import com.smartmanager.installments.core.payments.*
import com.smartmanager.installments.core.schedule.*
import java.time.LocalDate
import kotlin.test.*

class MoneyTests {
    @Test fun parsesCommonFormats() {
        assertEquals(2_400_000L, MoneyMath.parse("2400", 3))
        assertEquals(1_234_500L, MoneyMath.parse("1,234.5", 3))
        assertEquals(1_234_500L, MoneyMath.parse("1.234,5", 3))
        assertEquals(1_234_500L, MoneyMath.parse("1 234,500", 3))
        assertEquals(20_050L, MoneyMath.parse("200.5", 2))
        assertEquals(20_000L, MoneyMath.parse("٢٠٠٫٠٠", 2)) // Arabic-Indic digits and decimal separator
        assertEquals(1_234_00L, MoneyMath.parse("1,234", 2)) // grouping, not decimal, when 3 digits follow and decimals < 3
    }

    @Test fun rejectsInvalidInput() {
        assertNull(MoneyMath.parse("", 3))
        assertNull(MoneyMath.parse("abc", 3))
        assertNull(MoneyMath.parse("-5", 3))
        assertNull(MoneyMath.parse("1.2345", 3)) // too many decimals: never silently rounded
        assertNull(MoneyMath.parse("1.5.5.5.", 2))
        assertNull(MoneyMath.parse("12,34,567", 2))
        assertNull(MoneyMath.parse("1234567890123456789", 3))
    }

    @Test fun formatsWithoutFloatingPoint() {
        assertEquals("2,400.000", MoneyMath.format(2_400_000, 3))
        assertEquals("0.005", MoneyMath.format(5, 3))
        assertEquals("1 234,50", MoneyMath.format(123_450, 2, ' ', ','))
        assertEquals("-1.00", MoneyMath.format(-100, 2))
        assertEquals("12", MoneyMath.format(12, 0))
    }

    @Test fun currencyDecimalsAreNotHardcoded() {
        assertEquals(3, Currencies.of("TND").decimals)
        assertEquals(2, Currencies.of("SAR").decimals)
        assertEquals(1000L, Currencies.of("TND").unit)
        assertEquals(2, Currencies.of("XYZ").decimals)
    }
}

class ScheduleTests {
    private val d = LocalDate.of(2026, 1, 31)

    @Test fun weeklySchedule() {
        val s = ScheduleGenerator.generate(1_000_000, 4, Frequency.WEEKLY, LocalDate.of(2026, 3, 1), 1000)
        assertEquals(listOf("2026-03-01", "2026-03-08", "2026-03-15", "2026-03-22"), s.map { it.dueDate.toString() })
        assertEquals(1_000_000, s.sumOf { it.amountMinor })
    }

    @Test fun biweekly() {
        val dates = ScheduleGenerator.dueDates(LocalDate.of(2026, 3, 1), 3, Frequency.BIWEEKLY)
        assertEquals(listOf("2026-03-01", "2026-03-15", "2026-03-29"), dates.map { it.toString() })
    }

    @Test fun monthlyDoesNotDriftAtMonthEnd() {
        val dates = ScheduleGenerator.dueDates(d, 4, Frequency.MONTHLY)
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), dates.map { it.toString() })
    }

    @Test fun bimonthlyAndQuarterly() {
        assertEquals(listOf("2026-01-15", "2026-03-15", "2026-05-15"), ScheduleGenerator.dueDates(LocalDate.of(2026, 1, 15), 3, Frequency.BIMONTHLY).map { it.toString() })
        assertEquals(listOf("2026-01-15", "2026-04-15", "2026-07-15"), ScheduleGenerator.dueDates(LocalDate.of(2026, 1, 15), 3, Frequency.QUARTERLY).map { it.toString() })
    }

    @Test fun refrigeratorExample() {
        // 2,400 TND price, 400 down => 2,000 financed, 10 installments of 200 TND.
        val totals = ContractMath.totals(listOf(LineItem(1, 2_400_000)), 0, 400_000)
        assertTrue(totals.ok)
        assertEquals(2_000_000, totals.financedMinor)
        val s = ScheduleGenerator.generate(totals.financedMinor, 10, Frequency.MONTHLY, LocalDate.of(2026, 2, 1), 1000)
        assertTrue(s.all { it.amountMinor == 200_000L })
        assertTrue(ScheduleGenerator.validate(s, totals.financedMinor).ok)
    }

    @Test fun roundingAlwaysSumsExactly() {
        for (total in listOf(1L, 7L, 999L, 1_000_000L, 2_000_001L, 123_456_789L)) for (n in listOf(1, 2, 3, 7, 12, 36)) for (step in listOf(1L, 10L, 1000L)) {
            if (total < n) continue
            val parts = ScheduleGenerator.splitAmount(total, n, step)
            assertEquals(n, parts.size)
            assertEquals(total, parts.sum(), "total=$total n=$n step=$step")
            assertTrue(parts.all { it > 0 })
        }
    }

    @Test fun roundingPolicyWholeUnits() {
        // 1,000 TND / 7 => units 1000, q=142 r=6 -> first six get 143, last gets 142.
        val parts = ScheduleGenerator.splitAmount(1_000_000, 7, 1000)
        assertEquals(listOf(143_000L, 143_000, 143_000, 143_000, 143_000, 143_000, 142_000), parts)
        // Leftover below one step goes to the last installment.
        val p2 = ScheduleGenerator.splitAmount(1_000_500, 3, 1000)
        assertEquals(1_000_500, p2.sum())
        assertEquals(334_000L, p2[0]); assertEquals(333_000L, p2[1]); assertEquals(333_500L, p2[2]) // 1000 units: q=333 r=1, leftover 500 -> last
    }

    @Test fun coarseStepFallsBackToExact() {
        val parts = ScheduleGenerator.splitAmount(5_000, 10, 1000) // only 5 whole units for 10 installments
        assertEquals(5_000, parts.sum()); assertTrue(parts.all { it > 0 })
    }

    @Test fun customScheduleValidation() {
        val ok = listOf(
            ScheduledInstallment(1, LocalDate.of(2026, 2, 1), 200_000),
            ScheduledInstallment(2, LocalDate.of(2026, 3, 1), 200_000),
            ScheduledInstallment(3, LocalDate.of(2026, 5, 1), 600_000),
        )
        assertTrue(ScheduleGenerator.validate(ok, 1_000_000).ok)
        val bad = ScheduleGenerator.validate(ok, 1_100_000)
        assertTrue(ScheduleError.SUM_MISMATCH in bad.errors)
        assertEquals(100_000, bad.differenceMinor)
        val unordered = ok.reversed()
        assertTrue(ScheduleError.DATES_NOT_ORDERED in ScheduleGenerator.validate(unordered, 1_000_000).errors)
        assertTrue(ScheduleError.NON_POSITIVE_AMOUNT in ScheduleGenerator.validate(listOf(ok[0].copy(amountMinor = 0)), 0).errors)
        assertTrue(ScheduleError.EMPTY in ScheduleGenerator.validate(emptyList(), 100).errors)
        assertEquals(listOf(1, 2, 3), ScheduleGenerator.renumber(unordered).map { it.sequence })
    }
}

class ContractMathTests {
    @Test fun totalsAndDiscounts() {
        val t = ContractMath.totals(listOf(LineItem(2, 500_000), LineItem(1, 100_000)), 100_000, 300_000)
        assertEquals(1_100_000, t.originalPriceMinor)
        assertEquals(1_000_000, t.netPriceMinor)
        assertEquals(700_000, t.financedMinor)
    }

    @Test fun validation() {
        assertTrue(ContractError.NO_ITEMS in ContractMath.totals(emptyList(), 0, 0).errors)
        assertTrue(ContractError.DISCOUNT_TOO_HIGH in ContractMath.totals(listOf(LineItem(1, 100)), 200, 0).errors)
        assertTrue(ContractError.DOWN_PAYMENT_TOO_HIGH in ContractMath.totals(listOf(LineItem(1, 100)), 0, 200).errors)
        assertTrue(ContractError.NOTHING_TO_FINANCE in ContractMath.totals(listOf(LineItem(1, 100)), 0, 100).errors)
        assertTrue(ContractError.NEGATIVE_AMOUNT in ContractMath.totals(listOf(LineItem(1, 100)), -1, 0).errors)
    }

    @Test fun rescheduleMustCoverOutstandingExactly() {
        val ns = listOf(ScheduledInstallment(1, LocalDate.of(2026, 6, 1), 300), ScheduledInstallment(2, LocalDate.of(2026, 7, 1), 200))
        assertTrue(ReschedulePlanner.validate(ns, 500).isEmpty())
        assertTrue(ReschedulePlanner.Error.SUM_MISMATCH in ReschedulePlanner.validate(ns, 600))
        assertTrue(ReschedulePlanner.Error.NOTHING_TO_RESCHEDULE in ReschedulePlanner.validate(ns, 0))
    }
}

class AllocationTests {
    private fun inst(id: String, seq: Int, due: String, amount: Long, paid: Long = 0) = InstallmentBalance(id, seq, LocalDate.parse(due), amount, paid)
    private val list = listOf(inst("a", 1, "2026-01-01", 200), inst("b", 2, "2026-02-01", 200), inst("c", 3, "2026-03-01", 200))

    @Test fun fullPaymentOfOneInstallment() {
        val r = PaymentAllocator.auto(200, list) as AllocationResult.Success
        assertEquals(listOf(AllocationLine("a", 200)), r.lines)
    }

    @Test fun partialPayment() {
        val r = PaymentAllocator.auto(50, list) as AllocationResult.Success
        assertEquals(listOf(AllocationLine("a", 50)), r.lines)
        val after = PaymentAllocator.preview(list, r.lines)
        assertEquals(150, after[0].outstandingMinor)
    }

    @Test fun multiplePartialPaymentsAgainstOneInstallment() {
        var state = list
        repeat(4) {
            val r = PaymentAllocator.auto(50, state) as AllocationResult.Success
            assertEquals("a", r.lines.single().installmentId, "payment ${it + 1}")
            state = PaymentAllocator.preview(state, r.lines)
        }
        assertEquals(0, state[0].outstandingMinor)
        val next = PaymentAllocator.auto(10, state) as AllocationResult.Success
        assertEquals("b", next.lines.single().installmentId)
    }

    @Test fun paymentCoveringMultipleInstallments() {
        val r = PaymentAllocator.auto(450, list) as AllocationResult.Success
        assertEquals(listOf(AllocationLine("a", 200), AllocationLine("b", 200), AllocationLine("c", 50)), r.lines)
        assertEquals(450, r.lines.sumOf { it.amountMinor })
    }

    @Test fun earlySettlementPaysEverything() {
        val r = PaymentAllocator.auto(600, list) as AllocationResult.Success
        assertTrue(PaymentAllocator.preview(list, r.lines).all { it.outstandingMinor == 0L })
    }

    @Test fun latestFirstRule() {
        val r = PaymentAllocator.auto(250, list, AllocationRule.LATEST_FIRST) as AllocationResult.Success
        assertEquals(listOf(AllocationLine("c", 200), AllocationLine("b", 50)), r.lines)
    }

    @Test fun rejectsOverpaymentAndInvalidAmounts() {
        assertEquals(AllocationError.EXCEEDS_OUTSTANDING, (PaymentAllocator.auto(601, list) as AllocationResult.Failure).error)
        assertEquals(AllocationError.NON_POSITIVE_AMOUNT, (PaymentAllocator.auto(0, list) as AllocationResult.Failure).error)
        assertEquals(AllocationError.NON_POSITIVE_AMOUNT, (PaymentAllocator.auto(-5, list) as AllocationResult.Failure).error)
        val paid = list.map { it.copy(paidMinor = it.amountMinor) }
        assertEquals(AllocationError.NOTHING_OUTSTANDING, (PaymentAllocator.auto(1, paid) as AllocationResult.Failure).error)
    }

    @Test fun manualAllocationRules() {
        val ok = PaymentAllocator.validateManual(300, listOf(AllocationLine("c", 200), AllocationLine("a", 100)), list)
        assertTrue(ok is AllocationResult.Success)
        fun err(amount: Long, lines: List<AllocationLine>) = (PaymentAllocator.validateManual(amount, lines, list) as AllocationResult.Failure).error
        assertEquals(AllocationError.ALLOCATION_MISMATCH, err(300, listOf(AllocationLine("a", 100))))
        assertEquals(AllocationError.ALLOCATION_MISMATCH, err(50, listOf(AllocationLine("a", 100)))) // allocated beyond amount received
        assertEquals(AllocationError.LINE_EXCEEDS_OUTSTANDING, err(250, listOf(AllocationLine("a", 250))))
        assertEquals(AllocationError.UNKNOWN_INSTALLMENT, err(10, listOf(AllocationLine("zzz", 10))))
        assertEquals(AllocationError.DUPLICATE_LINE, err(20, listOf(AllocationLine("a", 10), AllocationLine("a", 10))))
        assertEquals(AllocationError.LINE_NON_POSITIVE, err(10, listOf(AllocationLine("a", 0))))
    }

    @Test fun allocationNeverExceedsPaymentOverManyRandomCases() {
        val rnd = java.util.Random(42)
        repeat(500) {
            val insts = (1..rnd.nextInt(8) + 1).map { inst("i$it", it, "2026-01-${it.toString().padStart(2, '0')}", (rnd.nextInt(900) + 100).toLong(), rnd.nextInt(100).toLong()) }
            val total = insts.sumOf { it.outstandingMinor }
            val amt = (rnd.nextInt(total.toInt()) + 1).toLong()
            val r = PaymentAllocator.auto(amt, insts) as AllocationResult.Success
            assertEquals(amt, r.lines.sumOf { it.amountMinor })
            val after = PaymentAllocator.preview(insts, r.lines)
            assertEquals(total - amt, after.sumOf { it.outstandingMinor })
        }
    }
}

class StatusTests {
    private val today = LocalDate.of(2026, 5, 10)
    private fun st(due: String, amount: Long = 100, paid: Long = 0, state: InstallmentState = InstallmentState.ACTIVE, t: LocalDate = today) =
        InstallmentStatusCalculator.status(LocalDate.parse(due), amount, paid, t, state)

    @Test fun dueTodayUpcomingOverdue() {
        assertEquals(InstallmentStatus.DUE_TODAY, st("2026-05-10"))
        assertEquals(InstallmentStatus.UPCOMING, st("2026-05-11"))
        assertEquals(InstallmentStatus.OVERDUE, st("2026-05-09")) // one day overdue
        assertEquals(InstallmentStatus.OVERDUE, st("2026-04-01"))
    }

    @Test fun daysOverdue() {
        assertEquals(1, InstallmentStatusCalculator.daysOverdue(LocalDate.parse("2026-05-09"), 10, today))
        assertEquals(39, InstallmentStatusCalculator.daysOverdue(LocalDate.parse("2026-04-01"), 10, today))
        assertEquals(0, InstallmentStatusCalculator.daysOverdue(LocalDate.parse("2026-05-10"), 10, today))
        assertEquals(0, InstallmentStatusCalculator.daysOverdue(LocalDate.parse("2026-04-01"), 0, today))
    }

    @Test fun paidAndPartial() {
        assertEquals(InstallmentStatus.PAID, st("2026-04-01", paid = 100))
        assertEquals(InstallmentStatus.PAID, st("2026-04-01", paid = 120))
        assertEquals(InstallmentStatus.OVERDUE, st("2026-04-01", paid = 40)) // partially paid but late
        assertTrue(InstallmentStatusCalculator.isPartiallyPaid(100, 40))
        assertEquals(InstallmentStatus.PARTIALLY_PAID, st("2026-06-01", paid = 40))
        assertEquals(InstallmentStatus.DUE_TODAY, st("2026-05-10", paid = 40))
    }

    @Test fun rescheduledAndCancelledAreExcluded() {
        assertEquals(InstallmentStatus.RESCHEDULED, st("2026-01-01", state = InstallmentState.RESCHEDULED))
        assertEquals(InstallmentStatus.CANCELLED, st("2026-01-01", state = InstallmentState.CANCELLED))
    }

    @Test fun localDateChangeMovesStatus() {
        assertEquals(InstallmentStatus.UPCOMING, st("2026-05-11"))
        assertEquals(InstallmentStatus.DUE_TODAY, st("2026-05-11", t = today.plusDays(1)))
        assertEquals(InstallmentStatus.OVERDUE, st("2026-05-11", t = today.plusDays(2)))
    }
}

class CollectionTests {
    private fun c(id: String, overdueDays: Long = 0, overdue: Long = 0, dueToday: Long = 0, partial: Boolean = false, promise: Long? = null) =
        CollectionInput(id, id, "1", overdue + dueToday, overdue, dueToday, overdueDays, partial, promise, promise?.let { 100 })

    @Test fun buckets() {
        val today = 20_000L
        val res = CollectionClassifier.classify(
            listOf(c("t", dueToday = 100), c("o3", 3, 50), c("o10", 10, 80), c("o40", 40, 500), c("p", partial = true), c("pr", promise = today + 2)), today
        )
        assertEquals(listOf("t"), res.getValue(CollectionGroup.DUE_TODAY).map { it.input.customerId })
        assertEquals(listOf("o3"), res.getValue(CollectionGroup.OVERDUE_1_7).map { it.input.customerId })
        assertEquals(listOf("o10"), res.getValue(CollectionGroup.OVERDUE_8_30).map { it.input.customerId })
        assertEquals(listOf("o40"), res.getValue(CollectionGroup.OVERDUE_30_PLUS).map { it.input.customerId })
        assertEquals(listOf("p"), res.getValue(CollectionGroup.PARTIAL_PAYERS).map { it.input.customerId })
        assertEquals(listOf("pr"), res.getValue(CollectionGroup.PROMISED).map { it.input.customerId })
    }

    @Test fun boundaries() {
        assertTrue(CollectionGroup.OVERDUE_1_7 in CollectionClassifier.groupsOf(c("a", 7, 1)))
        assertTrue(CollectionGroup.OVERDUE_8_30 in CollectionClassifier.groupsOf(c("a", 8, 1)))
        assertTrue(CollectionGroup.OVERDUE_8_30 in CollectionClassifier.groupsOf(c("a", 30, 1)))
        assertTrue(CollectionGroup.OVERDUE_30_PLUS in CollectionClassifier.groupsOf(c("a", 31, 1)))
    }

    @Test fun recommendations() {
        assertEquals(RecommendedAction.FOLLOW_UP_PROMISE, CollectionClassifier.recommend(c("a", 3, 10, promise = 100), 100))
        assertEquals(RecommendedAction.ESCALATE, CollectionClassifier.recommend(c("a", 45, 10), 100))
        assertEquals(RecommendedAction.CALL_AND_AGREE_DATE, CollectionClassifier.recommend(c("a", 12, 10), 100))
        assertEquals(RecommendedAction.SEND_OVERDUE_REMINDER, CollectionClassifier.recommend(c("a", 2, 10), 100))
        assertEquals(RecommendedAction.SEND_TODAY_REMINDER, CollectionClassifier.recommend(c("a", dueToday = 10), 100))
    }
}
