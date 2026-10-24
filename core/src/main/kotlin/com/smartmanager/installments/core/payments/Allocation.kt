package com.smartmanager.installments.core.payments

import java.time.LocalDate

/** Snapshot of an installment's money position, computed from actual posted allocations. */
data class InstallmentBalance(
    val id: String,
    val sequence: Int,
    val dueDate: LocalDate,
    val amountMinor: Long,
    val paidMinor: Long,
) {
    val outstandingMinor: Long get() = (amountMinor - paidMinor).coerceAtLeast(0)
}

data class AllocationLine(val installmentId: String, val amountMinor: Long)

enum class AllocationRule { OLDEST_FIRST, LATEST_FIRST }

enum class AllocationError {
    NON_POSITIVE_AMOUNT, NOTHING_OUTSTANDING, EXCEEDS_OUTSTANDING, ALLOCATION_MISMATCH,
    LINE_EXCEEDS_OUTSTANDING, LINE_NON_POSITIVE, UNKNOWN_INSTALLMENT, DUPLICATE_LINE
}

sealed class AllocationResult {
    data class Success(val lines: List<AllocationLine>) : AllocationResult()
    data class Failure(val error: AllocationError) : AllocationResult()
}

object PaymentAllocator {
    /**
     * Default rule: allocate to the oldest unpaid installment first (by due date, then sequence).
     * The whole amount must be allocated: a payment can never exceed the contract's outstanding balance.
     */
    fun auto(amountMinor: Long, installments: List<InstallmentBalance>, rule: AllocationRule = AllocationRule.OLDEST_FIRST): AllocationResult {
        if (amountMinor <= 0) return AllocationResult.Failure(AllocationError.NON_POSITIVE_AMOUNT)
        val open = installments.filter { it.outstandingMinor > 0 }
        if (open.isEmpty()) return AllocationResult.Failure(AllocationError.NOTHING_OUTSTANDING)
        if (amountMinor > open.sumOf { it.outstandingMinor }) return AllocationResult.Failure(AllocationError.EXCEEDS_OUTSTANDING)
        val ordered = when (rule) {
            AllocationRule.OLDEST_FIRST -> open.sortedWith(compareBy({ it.dueDate }, { it.sequence }))
            AllocationRule.LATEST_FIRST -> open.sortedWith(compareByDescending<InstallmentBalance> { it.dueDate }.thenByDescending { it.sequence })
        }
        var remaining = amountMinor
        val lines = mutableListOf<AllocationLine>()
        for (i in ordered) {
            if (remaining == 0L) break
            val take = minOf(remaining, i.outstandingMinor)
            lines += AllocationLine(i.id, take)
            remaining -= take
        }
        check(remaining == 0L)
        return AllocationResult.Success(lines)
    }

    /** Validates a manual allocation chosen by the retailer. */
    fun validateManual(amountMinor: Long, lines: List<AllocationLine>, installments: List<InstallmentBalance>): AllocationResult {
        if (amountMinor <= 0) return AllocationResult.Failure(AllocationError.NON_POSITIVE_AMOUNT)
        val byId = installments.associateBy { it.id }
        val seen = mutableSetOf<String>()
        for (l in lines) {
            val inst = byId[l.installmentId] ?: return AllocationResult.Failure(AllocationError.UNKNOWN_INSTALLMENT)
            if (!seen.add(l.installmentId)) return AllocationResult.Failure(AllocationError.DUPLICATE_LINE)
            if (l.amountMinor <= 0) return AllocationResult.Failure(AllocationError.LINE_NON_POSITIVE)
            if (l.amountMinor > inst.outstandingMinor) return AllocationResult.Failure(AllocationError.LINE_EXCEEDS_OUTSTANDING)
        }
        if (lines.sumOf { it.amountMinor } != amountMinor) return AllocationResult.Failure(AllocationError.ALLOCATION_MISMATCH)
        return AllocationResult.Success(lines)
    }

    /** Remaining balance of each installment after applying [lines] (for the confirmation preview). */
    fun preview(installments: List<InstallmentBalance>, lines: List<AllocationLine>): List<InstallmentBalance> {
        val add = lines.associate { it.installmentId to it.amountMinor }
        return installments.map { it.copy(paidMinor = it.paidMinor + (add[it.id] ?: 0L)) }
    }
}
