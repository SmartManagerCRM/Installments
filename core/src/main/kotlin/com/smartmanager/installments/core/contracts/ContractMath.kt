package com.smartmanager.installments.core.contracts

import com.smartmanager.installments.core.schedule.ScheduledInstallment

enum class ContractStatus { DRAFT, ACTIVE, COMPLETED, CANCELLED }

enum class ContractError { NO_ITEMS, NEGATIVE_AMOUNT, DISCOUNT_TOO_HIGH, DOWN_PAYMENT_TOO_HIGH, NOTHING_TO_FINANCE }

data class ContractTotals(
    val originalPriceMinor: Long,
    val discountMinor: Long,
    val downPaymentMinor: Long,
    val financedMinor: Long,
    val errors: Set<ContractError>,
) {
    val ok: Boolean get() = errors.isEmpty()
    val netPriceMinor: Long get() = originalPriceMinor - discountMinor
}

data class LineItem(val quantity: Int, val unitPriceMinor: Long) {
    val totalMinor: Long get() = quantity.toLong() * unitPriceMinor
}

object ContractMath {
    fun totals(items: List<LineItem>, discountMinor: Long, downPaymentMinor: Long): ContractTotals {
        val errors = mutableSetOf<ContractError>()
        if (items.isEmpty()) errors += ContractError.NO_ITEMS
        if (items.any { it.quantity <= 0 || it.unitPriceMinor < 0 } || discountMinor < 0 || downPaymentMinor < 0) {
            errors += ContractError.NEGATIVE_AMOUNT
        }
        val price = items.sumOf { it.totalMinor }
        if (discountMinor > price) errors += ContractError.DISCOUNT_TOO_HIGH
        val net = price - discountMinor
        if (downPaymentMinor > net) errors += ContractError.DOWN_PAYMENT_TOO_HIGH
        val financed = net - downPaymentMinor
        if (errors.isEmpty() && financed <= 0) errors += ContractError.NOTHING_TO_FINANCE
        return ContractTotals(price, discountMinor, downPaymentMinor, financed, errors)
    }
}

/** Rescheduling rules: the new schedule must cover exactly the unpaid balance and must not start in the past of the old one. */
object ReschedulePlanner {
    enum class Error { NOTHING_TO_RESCHEDULE, SUM_MISMATCH, EMPTY, NON_POSITIVE_AMOUNT }

    fun validate(newSchedule: List<ScheduledInstallment>, outstandingMinor: Long): Set<Error> {
        val errors = mutableSetOf<Error>()
        if (outstandingMinor <= 0) errors += Error.NOTHING_TO_RESCHEDULE
        if (newSchedule.isEmpty()) errors += Error.EMPTY
        if (newSchedule.any { it.amountMinor <= 0 }) errors += Error.NON_POSITIVE_AMOUNT
        if (newSchedule.sumOf { it.amountMinor } != outstandingMinor) errors += Error.SUM_MISMATCH
        return errors
    }
}
