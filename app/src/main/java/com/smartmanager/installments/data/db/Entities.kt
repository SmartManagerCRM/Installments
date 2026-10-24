package com.smartmanager.installments.data.db

import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Schema notes
 *  - Every business record has a random UUID primary key (never a device-local autoincrement) so records can be synchronised
 *    safely in a future cloud edition, plus createdAt/updatedAt timestamps (epoch millis, UTC).
 *  - Money is a Long count of minor units; every monetary record carries its currency code.
 *  - Dates without a time (due dates, contract dates, promises) are epoch DAYS (LocalDate.toEpochDay()).
 *  - Financial history is append-only: payments are never deleted; a correction is a REVERSAL (status change + audit event).
 *  - Enum-like columns are stored as readable strings; unknown values written by a newer version are tolerated.
 */

object Status {
    const val ACTIVE = "ACTIVE"
    const val ARCHIVED = "ARCHIVED"
    const val DRAFT = "DRAFT"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    const val RESCHEDULED = "RESCHEDULED"
    const val POSTED = "POSTED"
    const val REVERSED = "REVERSED"
    const val PENDING = "PENDING"
}

object PaymentKind {
    const val PAYMENT = "PAYMENT"
    const val DOWN_PAYMENT = "DOWN_PAYMENT"
    /** Non-cash reduction of the balance (e.g. early-settlement discount). Never counted as money collected. */
    const val ADJUSTMENT = "ADJUSTMENT"
}

@Serializable
@Entity(tableName = "customers", indices = [Index("fullName"), Index("phone"), Index("nationalRef"), Index("status")])
data class CustomerEntity(
    @PrimaryKey val id: String,
    val fullName: String,
    val phone: String,
    val altPhone: String? = null,
    val address: String = "",
    val nationalRef: String? = null,
    val notes: String = "",
    val status: String = Status.ACTIVE,
    val isDemo: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "products", indices = [Index("name"), Index("sku"), Index("category")])
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sku: String? = null,
    val category: String = "",
    val priceMinor: Long,
    val costMinor: Long? = null,
    val serialNumber: String? = null,
    val notes: String = "",
    val active: Boolean = true,
    val isDemo: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "contracts",
    foreignKeys = [ForeignKey(CustomerEntity::class, ["id"], ["customerId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("number", unique = true), Index("customerId"), Index("status"), Index("contractDate")],
)
data class ContractEntity(
    @PrimaryKey val id: String,
    val number: String,
    val customerId: String,
    val contractDate: Long,
    val currency: String,
    val originalPriceMinor: Long,
    val discountMinor: Long = 0,
    val downPaymentMinor: Long = 0,
    val financedMinor: Long,
    val installmentCount: Int,
    val frequency: String,
    val status: String,
    val rescheduleCount: Int = 0,
    val terms: String = "",
    val notes: String = "",
    val attachmentUri: String? = null,
    val isDemo: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "contract_items",
    foreignKeys = [ForeignKey(ContractEntity::class, ["id"], ["contractId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("contractId"), Index("productId")],
)
data class ContractItemEntity(
    @PrimaryKey val id: String,
    val contractId: String,
    /** Informational link only: description and price below are a SNAPSHOT, later catalog edits never change a contract. */
    val productId: String? = null,
    val description: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val serialNumber: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
@Entity(
    tableName = "installments",
    foreignKeys = [ForeignKey(ContractEntity::class, ["id"], ["contractId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("contractId"), Index("dueDate"), Index("state")],
)
data class InstallmentEntity(
    @PrimaryKey val id: String,
    val contractId: String,
    val sequence: Int,
    val dueDate: Long,
    val amountMinor: Long,
    /** ACTIVE | RESCHEDULED (superseded by a newer revision, kept for history) | CANCELLED */
    val state: String = Status.ACTIVE,
    /** Schedule revision this row belongs to (0 = original schedule). */
    val revision: Int = 0,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(CustomerEntity::class, ["id"], ["customerId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(ContractEntity::class, ["id"], ["contractId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("receiptNumber", unique = true), Index("requestKey", unique = true), Index("contractId"), Index("customerId"), Index("paidAt"), Index("status")],
)
data class PaymentEntity(
    @PrimaryKey val id: String,
    val receiptNumber: String,
    val customerId: String,
    val contractId: String,
    val kind: String = PaymentKind.PAYMENT,
    val amountMinor: Long,
    val currency: String,
    val paidAt: Long,
    val method: String,
    val reference: String? = null,
    val notes: String = "",
    val attachmentUri: String? = null,
    val status: String = Status.POSTED,
    val reversedAt: Long? = null,
    val reversalReason: String? = null,
    /** Idempotency token generated when the payment screen opens: a repeated submit can never create a second payment. */
    val requestKey: String,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "payment_allocations",
    foreignKeys = [
        ForeignKey(PaymentEntity::class, ["id"], ["paymentId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(InstallmentEntity::class, ["id"], ["installmentId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("paymentId"), Index("installmentId")],
)
data class PaymentAllocationEntity(
    @PrimaryKey val id: String,
    val paymentId: String,
    val installmentId: String,
    val amountMinor: Long,
)

@Serializable
@Entity(
    tableName = "promises",
    foreignKeys = [ForeignKey(CustomerEntity::class, ["id"], ["customerId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("customerId"), Index("outcome"), Index("promisedDate")],
)
data class PromiseEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val contractId: String? = null,
    val amountMinor: Long,
    val promisedDate: Long,
    val notes: String = "",
    /** PENDING | KEPT | BROKEN | CANCELLED - always set explicitly by the retailer. */
    val outcome: String = Status.PENDING,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(CustomerEntity::class, ["id"], ["customerId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("customerId"), Index("preparedAt")],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val contractId: String? = null,
    val type: String,
    val message: String,
    /** Time the retailer opened WhatsApp with this prefilled message. It does NOT mean the message was sent or delivered. */
    val preparedAt: Long,
    /** PREPARED | CUSTOMER_REPLIED | PAID | NO_RESPONSE | PROMISED - outcome is entered by the retailer. */
    val outcome: String = "PREPARED",
    val outcomeAt: Long? = null,
)

@Serializable
@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Serializable
@Entity(tableName = "audit_events", indices = [Index("at"), Index("entityId")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val entityType: String,
    val entityId: String,
    val action: String,
    val detail: String = "",
)

/** Device-bound activation record. NOT part of backups (a restored backup must never carry an old device's activation). */
@Entity(tableName = "license")
data class LicenseEntity(
    @PrimaryKey val id: String = "current",
    val licenseText: String,
    val licenseId: String,
    val importedAt: Long,
)

/** Reserved for the optional future cloud edition; unused by the offline edition. */
@Entity(tableName = "sync_meta", primaryKeys = ["entityType", "entityId"])
data class SyncMetaEntity(
    val entityType: String,
    val entityId: String,
    val lastModified: Long,
    val syncState: String = "LOCAL_ONLY",
    val remoteId: String? = null,
    val conflict: Boolean = false,
)

/**
 * Single source of truth for money owed: paid = SUM of allocations of POSTED payments (reversed payments vanish from the sum
 * automatically). Nothing is cached, so balances always follow the real records.
 */
@DatabaseView(
    viewName = "installment_balances",
    value = """
SELECT i.id AS installmentId, i.contractId AS contractId, c.customerId AS customerId, c.number AS contractNumber,
       c.currency AS currency, c.status AS contractStatus, i.sequence AS sequence, i.dueDate AS dueDate,
       i.amountMinor AS amountMinor, i.state AS state, i.revision AS revision,
       IFNULL((SELECT SUM(a.amountMinor) FROM payment_allocations a JOIN payments p ON p.id = a.paymentId
               WHERE a.installmentId = i.id AND p.status = 'POSTED'), 0) AS paidMinor
FROM installments i JOIN contracts c ON c.id = i.contractId""",
)
data class InstallmentBalanceView(
    val installmentId: String,
    val contractId: String,
    val customerId: String,
    val contractNumber: String,
    val currency: String,
    val contractStatus: String,
    val sequence: Int,
    val dueDate: Long,
    val amountMinor: Long,
    val state: String,
    val revision: Int,
    val paidMinor: Long,
) {
    val outstandingMinor: Long get() = (amountMinor - paidMinor).coerceAtLeast(0)
}
