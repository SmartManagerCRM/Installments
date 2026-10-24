package com.smartmanager.installments.core.payments

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class InstallmentStatus { UPCOMING, DUE_TODAY, PARTIALLY_PAID, PAID, OVERDUE, RESCHEDULED, CANCELLED }

/** Row-level lifecycle of an installment, independent of payments. */
enum class InstallmentState { ACTIVE, RESCHEDULED, CANCELLED }

/**
 * Status rules (documented in docs/FINANCIAL_RULES.md). Evaluated against the DEVICE LOCAL DATE passed in as [today]:
 *  1. RESCHEDULED / CANCELLED rows are excluded from balances and overdue detection.
 *  2. Nothing outstanding            -> PAID
 *  3. due date before today          -> OVERDUE   (also when partially paid; see [isPartiallyPaid])
 *  4. due date is today              -> DUE_TODAY (also when partially paid)
 *  5. some money paid, not yet due   -> PARTIALLY_PAID
 *  6. otherwise                      -> UPCOMING
 */
object InstallmentStatusCalculator {
    fun status(
        dueDate: LocalDate, amountMinor: Long, paidMinor: Long, today: LocalDate, state: InstallmentState = InstallmentState.ACTIVE,
    ): InstallmentStatus {
        if (state == InstallmentState.RESCHEDULED) return InstallmentStatus.RESCHEDULED
        if (state == InstallmentState.CANCELLED) return InstallmentStatus.CANCELLED
        if (amountMinor - paidMinor <= 0) return InstallmentStatus.PAID
        return when {
            dueDate.isBefore(today) -> InstallmentStatus.OVERDUE
            dueDate == today -> InstallmentStatus.DUE_TODAY
            paidMinor > 0 -> InstallmentStatus.PARTIALLY_PAID
            else -> InstallmentStatus.UPCOMING
        }
    }

    fun isPartiallyPaid(amountMinor: Long, paidMinor: Long): Boolean = paidMinor > 0 && amountMinor - paidMinor > 0

    fun daysOverdue(dueDate: LocalDate, outstandingMinor: Long, today: LocalDate): Long =
        if (outstandingMinor > 0 && dueDate.isBefore(today)) ChronoUnit.DAYS.between(dueDate, today) else 0L
}
