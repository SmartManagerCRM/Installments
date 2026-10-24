package com.smartmanager.installments.core.collection

enum class CollectionGroup { DUE_TODAY, OVERDUE_1_7, OVERDUE_8_30, OVERDUE_30_PLUS, PARTIAL_PAYERS, PROMISED }

enum class RecommendedAction {
    SEND_TODAY_REMINDER, SEND_OVERDUE_REMINDER, CALL_AND_AGREE_DATE, ESCALATE, FOLLOW_UP_PROMISE, REMIND_REMAINING_BALANCE
}

data class CollectionInput(
    val customerId: String,
    val customerName: String,
    val phone: String,
    val outstandingMinor: Long,
    val overdueMinor: Long,
    val dueTodayMinor: Long,
    /** Days since the OLDEST overdue unpaid installment's due date (0 if none overdue). */
    val oldestOverdueDays: Long,
    val hasPartialPayment: Boolean,
    val openPromiseDateEpochDay: Long?,
    val openPromiseAmountMinor: Long?,
)

data class CollectionEntry(val input: CollectionInput, val action: RecommendedAction)

/** Plain rule-based prioritisation (no AI service). A customer may appear in several groups (e.g. overdue AND promised). */
object CollectionClassifier {
    fun groupsOf(c: CollectionInput): Set<CollectionGroup> {
        val g = linkedSetOf<CollectionGroup>()
        when {
            c.oldestOverdueDays > 30 -> g += CollectionGroup.OVERDUE_30_PLUS
            c.oldestOverdueDays in 8..30 -> g += CollectionGroup.OVERDUE_8_30
            c.oldestOverdueDays in 1..7 -> g += CollectionGroup.OVERDUE_1_7
        }
        if (c.dueTodayMinor > 0) g += CollectionGroup.DUE_TODAY
        if (c.hasPartialPayment) g += CollectionGroup.PARTIAL_PAYERS
        if (c.openPromiseDateEpochDay != null) g += CollectionGroup.PROMISED
        return g
    }

    fun recommend(c: CollectionInput, todayEpochDay: Long): RecommendedAction {
        val promiseDue = c.openPromiseDateEpochDay != null && c.openPromiseDateEpochDay <= todayEpochDay
        return when {
            promiseDue -> RecommendedAction.FOLLOW_UP_PROMISE
            c.oldestOverdueDays > 30 -> RecommendedAction.ESCALATE
            c.oldestOverdueDays > 7 -> RecommendedAction.CALL_AND_AGREE_DATE
            c.oldestOverdueDays >= 1 -> RecommendedAction.SEND_OVERDUE_REMINDER
            c.dueTodayMinor > 0 -> RecommendedAction.SEND_TODAY_REMINDER
            c.hasPartialPayment -> RecommendedAction.REMIND_REMAINING_BALANCE
            else -> RecommendedAction.SEND_TODAY_REMINDER
        }
    }

    fun classify(inputs: List<CollectionInput>, todayEpochDay: Long): Map<CollectionGroup, List<CollectionEntry>> {
        val result = CollectionGroup.entries.associateWith { mutableListOf<CollectionEntry>() }
        for (c in inputs) {
            val entry = CollectionEntry(c, recommend(c, todayEpochDay))
            for (g in groupsOf(c)) result.getValue(g) += entry
        }
        return result.mapValues { (_, v) -> v.sortedByDescending { it.input.overdueMinor + it.input.dueTodayMinor } }
    }
}
