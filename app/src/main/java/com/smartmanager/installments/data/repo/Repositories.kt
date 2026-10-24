package com.smartmanager.installments.data.repo

import androidx.room.withTransaction
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.AuditEventEntity
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.db.ProductEntity
import com.smartmanager.installments.data.db.PromiseEntity
import com.smartmanager.installments.data.db.ReminderEntity
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.util.AppClock
import java.util.UUID

data class CustomerInput(
    val id: String?,
    val fullName: String,
    val phone: String,
    val altPhone: String?,
    val address: String,
    val nationalRef: String?,
    val notes: String,
)

class CustomerRepository(private val db: AppDatabase, private val clock: AppClock, private val guard: WriteGuard) {
    private suspend fun audit(id: String, action: String, detail: String = "") =
        db.audit().insert(AuditEventEntity(UUID.randomUUID().toString(), clock.nowMillis(), "CUSTOMER", id, action, detail))

    /** Normalised digits used for duplicate detection. */
    fun phoneKey(phone: String): String = com.smartmanager.installments.core.money.MoneyMath.normalizeDigits(phone).filter { it.isDigit() }

    suspend fun findSimilar(name: String, phone: String, excludeId: String?): List<CustomerEntity> =
        db.customers().similar(name.trim(), phone.trim(), excludeId ?: "")

    suspend fun save(input: CustomerInput): String = db.withTransaction {
        guard.check()
        if (input.fullName.isBlank() || input.phone.isBlank()) throw LedgerException(LedgerError.VALIDATION, "name/phone")
        val now = clock.nowMillis()
        val clean = input.copy(
            fullName = input.fullName.trim(), phone = input.phone.trim(), altPhone = input.altPhone?.trim()?.ifBlank { null },
            nationalRef = input.nationalRef?.trim()?.ifBlank { null }, address = input.address.trim(), notes = input.notes.trim(),
        )
        if (clean.id == null) {
            val id = UUID.randomUUID().toString()
            db.customers().insert(CustomerEntity(id, clean.fullName, clean.phone, clean.altPhone, clean.address, clean.nationalRef, clean.notes, Status.ACTIVE, false, now, now))
            audit(id, "CREATED")
            id
        } else {
            val old = db.customers().get(clean.id) ?: throw LedgerException(LedgerError.NOT_FOUND)
            db.customers().update(old.copy(fullName = clean.fullName, phone = clean.phone, altPhone = clean.altPhone, address = clean.address, nationalRef = clean.nationalRef, notes = clean.notes, updatedAt = now))
            audit(clean.id, "UPDATED")
            clean.id
        }
    }

    suspend fun setArchived(id: String, archived: Boolean) = db.withTransaction {
        guard.check()
        val c = db.customers().get(id) ?: throw LedgerException(LedgerError.NOT_FOUND)
        db.customers().update(c.copy(status = if (archived) Status.ARCHIVED else Status.ACTIVE, updatedAt = clock.nowMillis()))
        audit(id, if (archived) "ARCHIVED" else "RESTORED")
    }

    /** A customer with any contract or payment can never be deleted (archive instead). */
    suspend fun deleteIfNoHistory(id: String) = db.withTransaction {
        guard.check()
        val c = db.customers().get(id) ?: throw LedgerException(LedgerError.NOT_FOUND)
        if (db.customers().contractCount(id) > 0 || db.customers().paymentCount(id) > 0) throw LedgerException(LedgerError.HAS_HISTORY)
        db.customers().delete(c)
        audit(id, "DELETED", "no transactions")
    }
}

data class ProductInput(
    val id: String?, val name: String, val sku: String?, val category: String, val priceMinor: Long, val costMinor: Long?,
    val serialNumber: String?, val notes: String,
)

class ProductRepository(private val db: AppDatabase, private val clock: AppClock, private val guard: WriteGuard) {
    suspend fun save(i: ProductInput): String = db.withTransaction {
        guard.check()
        if (i.name.isBlank() || i.priceMinor < 0) throw LedgerException(LedgerError.VALIDATION)
        val now = clock.nowMillis()
        if (i.id == null) {
            val id = UUID.randomUUID().toString()
            db.products().insert(ProductEntity(id, i.name.trim(), i.sku?.trim()?.ifBlank { null }, i.category.trim(), i.priceMinor, i.costMinor, i.serialNumber?.trim()?.ifBlank { null }, i.notes.trim(), true, false, now, now))
            id
        } else {
            val old = db.products().get(i.id) ?: throw LedgerException(LedgerError.NOT_FOUND)
            // Contracts keep their own price/description snapshot, so editing the catalog never rewrites history.
            db.products().update(old.copy(name = i.name.trim(), sku = i.sku?.trim()?.ifBlank { null }, category = i.category.trim(), priceMinor = i.priceMinor, costMinor = i.costMinor, serialNumber = i.serialNumber?.trim()?.ifBlank { null }, notes = i.notes.trim(), updatedAt = now))
            i.id
        }
    }

    suspend fun setActive(id: String, active: Boolean) {
        guard.check()
        val p = db.products().get(id) ?: return
        db.products().update(p.copy(active = active, updatedAt = clock.nowMillis()))
    }

    suspend fun deleteIfUnused(id: String) {
        guard.check()
        val p = db.products().get(id) ?: return
        if (db.products().usageCount(id) > 0) throw LedgerException(LedgerError.HAS_HISTORY)
        db.products().delete(p)
    }
}

class CollectionRepository(private val db: AppDatabase, private val clock: AppClock, private val guard: WriteGuard) {
    suspend fun addPromise(customerId: String, contractId: String?, amountMinor: Long, promisedEpochDay: Long, notes: String): String {
        guard.check()
        if (amountMinor <= 0) throw LedgerException(LedgerError.INVALID_AMOUNT)
        val now = clock.nowMillis()
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            db.promises().insert(PromiseEntity(id, customerId, contractId, amountMinor, promisedEpochDay, notes.trim(), Status.PENDING, now, now))
            db.audit().insert(AuditEventEntity(UUID.randomUUID().toString(), now, "PROMISE", id, "CREATED", "amount=$amountMinor date=$promisedEpochDay"))
        }
        return id
    }

    /** outcome: KEPT | BROKEN | CANCELLED (entered explicitly by the retailer). */
    suspend fun setPromiseOutcome(id: String, outcome: String) {
        guard.check()
        val p = db.promises().get(id) ?: return
        db.withTransaction {
            db.promises().update(p.copy(outcome = outcome, updatedAt = clock.nowMillis()))
            db.audit().insert(AuditEventEntity(UUID.randomUUID().toString(), clock.nowMillis(), "PROMISE", id, "OUTCOME_$outcome"))
        }
    }

    /**
     * Records that the retailer OPENED a prefilled WhatsApp conversation. This is deliberately not called "sent":
     * the app cannot know whether the retailer pressed send or whether the message was delivered.
     */
    suspend fun recordReminderPrepared(customerId: String, contractId: String?, type: ReminderType, message: String): String {
        val id = UUID.randomUUID().toString()
        db.reminders().insert(ReminderEntity(id, customerId, contractId, type.name, message, clock.nowMillis()))
        return id
    }

    suspend fun setReminderOutcome(id: String, outcome: String) = db.reminders().setOutcome(id, outcome, clock.nowMillis())
}
