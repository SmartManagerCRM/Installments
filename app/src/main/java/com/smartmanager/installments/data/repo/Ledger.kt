package com.smartmanager.installments.data.repo

import androidx.room.withTransaction
import com.smartmanager.installments.core.contracts.ContractMath
import com.smartmanager.installments.core.contracts.LineItem
import com.smartmanager.installments.core.contracts.ReschedulePlanner
import com.smartmanager.installments.core.payments.AllocationError
import com.smartmanager.installments.core.payments.AllocationLine
import com.smartmanager.installments.core.payments.AllocationResult
import com.smartmanager.installments.core.payments.AllocationRule
import com.smartmanager.installments.core.payments.InstallmentBalance
import com.smartmanager.installments.core.payments.PaymentAllocator
import com.smartmanager.installments.core.schedule.Frequency
import com.smartmanager.installments.core.schedule.ScheduleGenerator
import com.smartmanager.installments.core.schedule.ScheduledInstallment
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.AuditEventEntity
import com.smartmanager.installments.data.db.ContractEntity
import com.smartmanager.installments.data.db.ContractItemEntity
import com.smartmanager.installments.data.db.InstallmentBalanceView
import com.smartmanager.installments.data.db.InstallmentEntity
import com.smartmanager.installments.data.db.PaymentAllocationEntity
import com.smartmanager.installments.data.db.PaymentEntity
import com.smartmanager.installments.data.db.PaymentKind
import com.smartmanager.installments.data.db.SettingEntity
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.util.AppClock
import java.time.LocalDate
import java.util.UUID

enum class LedgerError {
    LICENSE_READ_ONLY, INVALID_AMOUNT, CONTRACT_NOT_ACTIVE, NOT_FOUND, ALLOCATION_INVALID, EXCEEDS_OUTSTANDING, POSSIBLE_DUPLICATE,
    SCHEDULE_INVALID, CONTRACT_INVALID, ALREADY_REVERSED, REVERSAL_BLOCKED_RESCHEDULED, CUSTOMER_ARCHIVED, ONLY_DRAFT, VALIDATION,
    HAS_HISTORY, NOTHING_TO_SETTLE, DISCOUNT_INVALID,
}

class LedgerException(val error: LedgerError, val detail: String? = null) : Exception("$error${detail?.let { ": $it" } ?: ""}")

/** Raised before every state-changing operation; implemented by the license manager (expired license => read-only). */
fun interface WriteGuard {
    fun check()

    companion object {
        val AllowAll = WriteGuard { }
    }
}

data class NewItem(val productId: String?, val description: String, val quantity: Int, val unitPriceMinor: Long, val serialNumber: String? = null)

data class NewContract(
    val customerId: String,
    val contractDate: LocalDate,
    val currency: String,
    val items: List<NewItem>,
    val discountMinor: Long,
    val downPaymentMinor: Long,
    val frequency: Frequency,
    val schedule: List<ScheduledInstallment>,
    val terms: String,
    val notes: String,
    val activate: Boolean,
    val downPaymentMethod: String = "CASH",
    val attachmentUri: String? = null,
    val isDemo: Boolean = false,
    val paidAtMillis: Long? = null,
)

data class PaymentRequest(
    val requestKey: String,
    val contractId: String,
    val amountMinor: Long,
    val method: String,
    val reference: String? = null,
    val notes: String = "",
    val paidAtMillis: Long? = null,
    /** null => allocate automatically using [rule]. */
    val manualLines: List<AllocationLine>? = null,
    val rule: AllocationRule = AllocationRule.OLDEST_FIRST,
    val confirmPossibleDuplicate: Boolean = false,
    val attachmentUri: String? = null,
)

data class PaymentOutcome(
    val payment: PaymentEntity,
    val allocations: List<PaymentAllocationEntity>,
    val remainingOutstandingMinor: Long,
    val contractCompleted: Boolean,
    /** True when the same requestKey had already been posted: nothing new was written. */
    val alreadyPosted: Boolean = false,
)

data class SettlementQuote(val outstandingMinor: Long)

/**
 * All financial writes. Every public mutating function runs in ONE database transaction, so a crash can never leave a
 * payment without its allocations or a contract in a half-written state. Balances are never stored: they are derived from
 * installments minus allocations of POSTED payments (see the `installment_balances` view).
 */
class LedgerRepository(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val guard: WriteGuard,
) {
    private val contracts get() = db.contracts()
    private val payments get() = db.payments()

    // ------------------------------------------------------------------------------------------------ helpers

    private suspend fun audit(type: String, id: String, action: String, detail: String = "") {
        db.audit().insert(AuditEventEntity(UUID.randomUUID().toString(), clock.nowMillis(), type, id, action, detail))
    }

    private suspend fun nextCounter(key: String): Long {
        val current = db.settings().get(key)?.toLongOrNull() ?: 0L
        db.settings().put(SettingEntity(key, (current + 1).toString()))
        return current + 1
    }

    private suspend fun nextContractNumber(date: LocalDate): String =
        "C-%d-%04d".format(date.year, nextCounter("counter.contract.${date.year}"))

    private suspend fun nextReceiptNumber(): String {
        val year = clock.today().year
        return "R-%d-%05d".format(year, nextCounter("counter.receipt.$year"))
    }

    private fun InstallmentBalanceView.toCore() =
        InstallmentBalance(installmentId, sequence, LocalDate.ofEpochDay(dueDate), amountMinor, paidMinor)

    // ------------------------------------------------------------------------------------------------ contracts

    suspend fun createContract(req: NewContract, existingDraftId: String? = null): String = db.withTransaction {
        guard.check()
        val customer = db.customers().get(req.customerId) ?: throw LedgerException(LedgerError.NOT_FOUND, "customer")
        if (customer.status != Status.ACTIVE) throw LedgerException(LedgerError.CUSTOMER_ARCHIVED)
        val totals = ContractMath.totals(req.items.map { LineItem(it.quantity, it.unitPriceMinor) }, req.discountMinor, req.downPaymentMinor)
        if (!totals.ok) throw LedgerException(LedgerError.CONTRACT_INVALID, totals.errors.joinToString())
        if (req.items.any { it.description.isBlank() }) throw LedgerException(LedgerError.VALIDATION, "item description")
        val sched = ScheduleGenerator.validate(req.schedule, totals.financedMinor)
        if (!sched.ok) throw LedgerException(LedgerError.SCHEDULE_INVALID, sched.errors.joinToString())

        val now = clock.nowMillis()
        val id: String
        val number: String
        val createdAt: Long
        if (existingDraftId != null) {
            val draft = contracts.get(existingDraftId) ?: throw LedgerException(LedgerError.NOT_FOUND, "draft")
            if (draft.status != Status.DRAFT) throw LedgerException(LedgerError.ONLY_DRAFT)
            contracts.deleteItems(draft.id)
            contracts.deleteInstallments(draft.id)
            id = draft.id; number = draft.number; createdAt = draft.createdAt
        } else {
            id = UUID.randomUUID().toString(); number = nextContractNumber(req.contractDate); createdAt = now
        }
        val status = if (req.activate) Status.ACTIVE else Status.DRAFT
        val entity = ContractEntity(
            id = id, number = number, customerId = req.customerId, contractDate = req.contractDate.toEpochDay(), currency = req.currency,
            originalPriceMinor = totals.originalPriceMinor, discountMinor = req.discountMinor, downPaymentMinor = req.downPaymentMinor,
            financedMinor = totals.financedMinor, installmentCount = req.schedule.size, frequency = req.frequency.name, status = status,
            terms = req.terms, notes = req.notes, attachmentUri = req.attachmentUri, isDemo = req.isDemo, createdAt = createdAt, updatedAt = now,
        )
        if (existingDraftId != null) contracts.update(entity) else contracts.insert(entity)
        contracts.insertItems(req.items.mapIndexed { i, it ->
            ContractItemEntity(UUID.randomUUID().toString(), id, it.productId, it.description.trim(), it.quantity, it.unitPriceMinor, it.serialNumber, i)
        })
        contracts.insertInstallments(ScheduleGenerator.renumber(req.schedule).map {
            InstallmentEntity(UUID.randomUUID().toString(), id, it.sequence, it.dueDate.toEpochDay(), it.amountMinor, Status.ACTIVE, 0, now)
        })
        audit("CONTRACT", id, if (req.activate) "CREATED_ACTIVE" else "SAVED_DRAFT", "number=$number financed=${totals.financedMinor} ${req.currency} n=${req.schedule.size}")
        if (req.activate && req.downPaymentMinor > 0) {
            val p = PaymentEntity(
                id = UUID.randomUUID().toString(), receiptNumber = nextReceiptNumber(), customerId = req.customerId, contractId = id,
                kind = PaymentKind.DOWN_PAYMENT, amountMinor = req.downPaymentMinor, currency = req.currency, paidAt = req.paidAtMillis ?: now,
                method = req.downPaymentMethod, notes = "", status = Status.POSTED, requestKey = "down-$id", createdAt = now,
            )
            payments.insert(p)
            audit("PAYMENT", p.id, "POSTED", "down payment ${p.amountMinor} ${p.currency} receipt=${p.receiptNumber}")
        }
        id
    }

    suspend fun loadDraft(id: String): NewContract? {
        val c = contracts.get(id) ?: return null
        val items = contracts.items(id)
        val inst = contracts.installments(id)
        return NewContract(
            c.customerId, LocalDate.ofEpochDay(c.contractDate), c.currency,
            items.map { NewItem(it.productId, it.description, it.quantity, it.unitPriceMinor, it.serialNumber) },
            c.discountMinor, c.downPaymentMinor, runCatching { Frequency.valueOf(c.frequency) }.getOrDefault(Frequency.CUSTOM),
            inst.map { ScheduledInstallment(it.sequence, LocalDate.ofEpochDay(it.dueDate), it.amountMinor) }, c.terms, c.notes, false,
        )
    }

    suspend fun deleteDraft(id: String) = db.withTransaction {
        guard.check()
        val c = contracts.get(id) ?: throw LedgerException(LedgerError.NOT_FOUND)
        if (c.status != Status.DRAFT) throw LedgerException(LedgerError.ONLY_DRAFT)
        contracts.deleteContract(id) // items + installments cascade; a draft never has payments
        audit("CONTRACT", id, "DRAFT_DELETED", c.number)
    }

    suspend fun cancelContract(id: String, reason: String) = db.withTransaction {
        guard.check()
        val c = contracts.get(id) ?: throw LedgerException(LedgerError.NOT_FOUND)
        if (c.status != Status.ACTIVE && c.status != Status.DRAFT) throw LedgerException(LedgerError.CONTRACT_NOT_ACTIVE)
        contracts.update(c.copy(status = Status.CANCELLED, updatedAt = clock.nowMillis()))
        audit("CONTRACT", id, "CANCELLED", reason) // payments already received stay on record untouched
    }

    /**
     * Reschedules the unpaid balance. The ORIGINAL rows are preserved (state RESCHEDULED, revision kept); new rows get the next
     * revision. The old and new schedules are written to the audit trail.
     */
    suspend fun reschedule(contractId: String, newSchedule: List<ScheduledInstallment>, reason: String) = db.withTransaction {
        guard.check()
        val c = contracts.get(contractId) ?: throw LedgerException(LedgerError.NOT_FOUND)
        if (c.status != Status.ACTIVE) throw LedgerException(LedgerError.CONTRACT_NOT_ACTIVE)
        val balances = contracts.balances(contractId)
        val outstanding = balances.sumOf { it.outstandingMinor }
        val errors = ReschedulePlanner.validate(newSchedule, outstanding)
        if (errors.isNotEmpty()) throw LedgerException(LedgerError.SCHEDULE_INVALID, errors.joinToString())
        val now = clock.nowMillis()
        val open = balances.filter { it.outstandingMinor > 0 }
        val openIds = open.map { it.installmentId }.toSet()
        val rows = contracts.installments(contractId)
        contracts.updateInstallments(rows.filter { it.id in openIds }.map { it.copy(state = Status.RESCHEDULED) })
        val revision = c.rescheduleCount + 1
        val firstSeq = (rows.maxOfOrNull { it.sequence } ?: 0) + 1
        val ordered = newSchedule.sortedBy { it.dueDate }
        contracts.insertInstallments(ordered.mapIndexed { i, s ->
            InstallmentEntity(UUID.randomUUID().toString(), contractId, firstSeq + i, s.dueDate.toEpochDay(), s.amountMinor, Status.ACTIVE, revision, now)
        })
        val activeCount = balances.size - open.size + ordered.size
        contracts.update(c.copy(rescheduleCount = revision, installmentCount = activeCount, updatedAt = now))
        val old = open.joinToString(";") { "#${it.sequence}:${it.dueDate}:${it.outstandingMinor}" }
        val new = ordered.joinToString(";") { "${it.dueDate.toEpochDay()}:${it.amountMinor}" }
        audit("CONTRACT", contractId, "RESCHEDULED", "revision=$revision reason=$reason old=[$old] new=[$new]")
    }

    // ------------------------------------------------------------------------------------------------ payments

    suspend fun recordPayment(req: PaymentRequest): PaymentOutcome = db.withTransaction {
        guard.check()
        payments.byRequestKey(req.requestKey)?.let { existing ->
            val allocs = payments.allocations(existing.id)
            val remaining = contracts.balances(existing.contractId).sumOf { it.outstandingMinor }
            return@withTransaction PaymentOutcome(existing, allocs, remaining, contracts.get(existing.contractId)?.status == Status.COMPLETED, alreadyPosted = true)
        }
        if (req.amountMinor <= 0) throw LedgerException(LedgerError.INVALID_AMOUNT)
        val c = contracts.get(req.contractId) ?: throw LedgerException(LedgerError.NOT_FOUND, "contract")
        if (c.status != Status.ACTIVE) throw LedgerException(LedgerError.CONTRACT_NOT_ACTIVE)
        val now = clock.nowMillis()
        if (!req.confirmPossibleDuplicate && payments.recentSame(c.id, req.amountMinor, now - DUPLICATE_WINDOW_MS) > 0) {
            throw LedgerException(LedgerError.POSSIBLE_DUPLICATE)
        }
        val balances = contracts.balances(c.id)
        val core = balances.map { it.toCore() }
        val result = if (req.manualLines != null) PaymentAllocator.validateManual(req.amountMinor, req.manualLines, core)
        else PaymentAllocator.auto(req.amountMinor, core, req.rule)
        val lines = when (result) {
            is AllocationResult.Success -> result.lines
            is AllocationResult.Failure -> throw LedgerException(
                if (result.error == AllocationError.EXCEEDS_OUTSTANDING) LedgerError.EXCEEDS_OUTSTANDING else LedgerError.ALLOCATION_INVALID, result.error.name,
            )
        }
        postPayment(c, PaymentKind.PAYMENT, req.amountMinor, lines, req.method, req.reference, req.notes, req.paidAtMillis ?: now, req.requestKey, req.attachmentUri, balances)
    }

    private suspend fun postPayment(
        c: ContractEntity, kind: String, amount: Long, lines: List<AllocationLine>, method: String, reference: String?, notes: String,
        paidAt: Long, requestKey: String, attachment: String?, balancesBefore: List<InstallmentBalanceView>,
    ): PaymentOutcome {
        val now = clock.nowMillis()
        val p = PaymentEntity(
            id = UUID.randomUUID().toString(), receiptNumber = nextReceiptNumber(), customerId = c.customerId, contractId = c.id, kind = kind,
            amountMinor = amount, currency = c.currency, paidAt = paidAt, method = method, reference = reference?.trim()?.ifBlank { null },
            notes = notes.trim(), attachmentUri = attachment, requestKey = requestKey, createdAt = now,
        )
        payments.insert(p)
        val allocs = lines.map { PaymentAllocationEntity(UUID.randomUUID().toString(), p.id, it.installmentId, it.amountMinor) }
        payments.insertAllocations(allocs)
        check(allocs.sumOf { it.amountMinor } == amount) { "allocation total must equal payment" }
        val remaining = balancesBefore.sumOf { it.outstandingMinor } - amount
        var completed = false
        if (remaining == 0L && c.status == Status.ACTIVE) {
            contracts.update(c.copy(status = Status.COMPLETED, updatedAt = now))
            audit("CONTRACT", c.id, "COMPLETED", "last payment ${p.receiptNumber}")
            completed = true
        }
        audit("PAYMENT", p.id, "POSTED", "kind=$kind amount=$amount ${c.currency} receipt=${p.receiptNumber} contract=${c.number} allocations=${allocs.size}")
        return PaymentOutcome(p, allocs, remaining, completed)
    }

    suspend fun settlementQuote(contractId: String): SettlementQuote =
        SettlementQuote(contracts.balances(contractId).sumOf { it.outstandingMinor })

    /**
     * Early settlement: pays the whole remaining balance. An optional [discountMinor] is recorded as a separate ADJUSTMENT
     * (never counted as money collected) that waives the LATEST installments first; the cash payment clears the rest.
     */
    suspend fun settleContract(
        requestKey: String, contractId: String, discountMinor: Long, method: String, reference: String?, notes: String, paidAtMillis: Long? = null,
    ): PaymentOutcome = db.withTransaction {
        guard.check()
        payments.byRequestKey(requestKey)?.let { existing ->
            return@withTransaction PaymentOutcome(existing, payments.allocations(existing.id), 0, true, alreadyPosted = true)
        }
        val c = contracts.get(contractId) ?: throw LedgerException(LedgerError.NOT_FOUND)
        if (c.status != Status.ACTIVE) throw LedgerException(LedgerError.CONTRACT_NOT_ACTIVE)
        val balances = contracts.balances(contractId)
        val total = balances.sumOf { it.outstandingMinor }
        if (total <= 0) throw LedgerException(LedgerError.NOTHING_TO_SETTLE)
        if (discountMinor < 0 || discountMinor >= total) throw LedgerException(LedgerError.DISCOUNT_INVALID)
        val now = clock.nowMillis()
        var working = balances
        if (discountMinor > 0) {
            val adj = (PaymentAllocator.auto(discountMinor, balances.map { it.toCore() }, AllocationRule.LATEST_FIRST) as AllocationResult.Success).lines
            postPayment(c, PaymentKind.ADJUSTMENT, discountMinor, adj, "ADJUSTMENT", null, "early settlement discount", paidAtMillis ?: now, "$requestKey-adj", null, working)
            val byId = adj.associate { it.installmentId to it.amountMinor }
            working = working.map { it.copy(paidMinor = it.paidMinor + (byId[it.installmentId] ?: 0L)) }
        }
        val cash = total - discountMinor
        val lines = (PaymentAllocator.auto(cash, working.map { it.toCore() }, AllocationRule.OLDEST_FIRST) as AllocationResult.Success).lines
        val fresh = contracts.get(contractId)!!
        postPayment(fresh, PaymentKind.PAYMENT, cash, lines, method, reference, notes, paidAtMillis ?: now, requestKey, null, working)
    }

    /**
     * Correction process: a posted payment is never deleted or edited. It is REVERSED (kept on record with date and reason),
     * the balance reappears automatically, and a completed contract is re-opened. To correct an amount: reverse, then record again.
     */
    suspend fun reversePayment(paymentId: String, reason: String) = db.withTransaction {
        guard.check()
        if (reason.isBlank()) throw LedgerException(LedgerError.VALIDATION, "reason")
        val p = payments.get(paymentId) ?: throw LedgerException(LedgerError.NOT_FOUND)
        if (p.status != Status.POSTED) throw LedgerException(LedgerError.ALREADY_REVERSED)
        if (payments.allocationsOnSupersededInstallments(paymentId) > 0) throw LedgerException(LedgerError.REVERSAL_BLOCKED_RESCHEDULED)
        val now = clock.nowMillis()
        if (payments.markReversed(paymentId, now, reason.trim()) != 1) throw LedgerException(LedgerError.ALREADY_REVERSED)
        audit("PAYMENT", paymentId, "REVERSED", "receipt=${p.receiptNumber} amount=${p.amountMinor} reason=${reason.trim()}")
        val c = contracts.get(p.contractId)!!
        if (c.status == Status.COMPLETED && contracts.balances(c.id).sumOf { it.outstandingMinor } > 0) {
            contracts.update(c.copy(status = Status.ACTIVE, updatedAt = now))
            audit("CONTRACT", c.id, "REOPENED", "payment ${p.receiptNumber} reversed")
        }
    }

    companion object {
        /** Same contract + same amount within this window needs an explicit confirmation (accidental double payment guard). */
        const val DUPLICATE_WINDOW_MS = 2 * 60 * 1000L
    }
}
