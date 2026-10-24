package com.smartmanager.installments.core.schedule

import java.time.LocalDate

enum class Frequency(val days: Int, val months: Int) {
    WEEKLY(7, 0), BIWEEKLY(14, 0), MONTHLY(0, 1), BIMONTHLY(0, 2), QUARTERLY(0, 3), CUSTOM(0, 0)
}

data class ScheduledInstallment(val sequence: Int, val dueDate: LocalDate, val amountMinor: Long)

enum class ScheduleError {
    EMPTY, NON_POSITIVE_AMOUNT, SUM_MISMATCH, DATES_NOT_ORDERED, TOO_MANY, BAD_COUNT, NON_POSITIVE_FINANCED
}

data class ScheduleValidation(val errors: Set<ScheduleError>, val sumMinor: Long, val differenceMinor: Long) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * Installment schedule generation.
 *
 * Rounding policy (documented in docs/FINANCIAL_RULES.md):
 *  * Every amount is a whole number of minor units; the schedule always sums EXACTLY to the financed amount.
 *  * `roundingStep` (in minor units) is the granularity of the regular installments. Step = 1 means exact minor
 *    units; step = currency unit (e.g. 1000 for TND) gives "whole dinar" installments.
 *  * total = units*step + leftover (leftover < step). The `units` are split evenly; the first (units % n)
 *    installments receive one extra step. The leftover (< 1 step) is added to the LAST installment.
 */
object ScheduleGenerator {
    const val MAX_INSTALLMENTS = 360

    fun dueDates(first: LocalDate, count: Int, frequency: Frequency): List<LocalDate> {
        require(frequency != Frequency.CUSTOM) { "custom schedules have explicit dates" }
        // Always computed from the anchor date so that month-end days never drift (Jan 31 -> Feb 28 -> Mar 31).
        return (0 until count).map { i ->
            if (frequency.days > 0) first.plusDays(frequency.days.toLong() * i)
            else first.plusMonths(frequency.months.toLong() * i)
        }
    }

    fun splitAmount(totalMinor: Long, count: Int, roundingStep: Long = 1L): List<Long> {
        require(totalMinor > 0) { "total must be positive" }
        require(count in 1..MAX_INSTALLMENTS) { "bad count" }
        require(roundingStep >= 1) { "bad step" }
        var step = roundingStep
        // If the step is too coarse to give every installment at least one step, fall back to exact units.
        if (totalMinor / step < count) step = 1L
        val units = totalMinor / step
        val leftover = totalMinor - units * step
        val q = units / count
        val r = (units % count).toInt()
        val result = MutableList(count) { i -> (q + if (i < r) 1 else 0) * step }
        result[count - 1] = result[count - 1] + leftover
        check(result.sum() == totalMinor)
        return result
    }

    fun generate(
        financedMinor: Long, count: Int, frequency: Frequency, firstDue: LocalDate, roundingStep: Long = 1L,
    ): List<ScheduledInstallment> {
        val dates = dueDates(firstDue, count, frequency)
        val amounts = splitAmount(financedMinor, count, roundingStep)
        return dates.indices.map { ScheduledInstallment(it + 1, dates[it], amounts[it]) }
    }

    fun validate(schedule: List<ScheduledInstallment>, expectedTotalMinor: Long): ScheduleValidation {
        val errors = mutableSetOf<ScheduleError>()
        if (expectedTotalMinor <= 0) errors += ScheduleError.NON_POSITIVE_FINANCED
        if (schedule.isEmpty()) errors += ScheduleError.EMPTY
        if (schedule.size > MAX_INSTALLMENTS) errors += ScheduleError.TOO_MANY
        if (schedule.any { it.amountMinor <= 0 }) errors += ScheduleError.NON_POSITIVE_AMOUNT
        for (i in 1 until schedule.size) if (schedule[i].dueDate.isBefore(schedule[i - 1].dueDate)) {
            errors += ScheduleError.DATES_NOT_ORDERED
        }
        val sum = schedule.sumOf { it.amountMinor }
        if (sum != expectedTotalMinor) errors += ScheduleError.SUM_MISMATCH
        return ScheduleValidation(errors, sum, expectedTotalMinor - sum)
    }

    /** Re-numbers a (possibly edited) schedule by due date, keeping the sort stable. */
    fun renumber(schedule: List<ScheduledInstallment>): List<ScheduledInstallment> =
        schedule.sortedBy { it.dueDate }.mapIndexed { i, s -> s.copy(sequence = i + 1) }
}
