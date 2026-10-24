package com.smartmanager.installments.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.smartmanager.installments.core.backup.BackupCodec
import com.smartmanager.installments.core.backup.BackupError
import com.smartmanager.installments.core.backup.BackupException
import com.smartmanager.installments.core.backup.BackupHeader
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.AuditEventEntity
import com.smartmanager.installments.data.db.ContractEntity
import com.smartmanager.installments.data.db.ContractItemEntity
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.db.InstallmentEntity
import com.smartmanager.installments.data.db.PaymentAllocationEntity
import com.smartmanager.installments.data.db.PaymentEntity
import com.smartmanager.installments.data.db.PaymentKind
import com.smartmanager.installments.data.db.ProductEntity
import com.smartmanager.installments.data.db.PromiseEntity
import com.smartmanager.installments.data.db.ReminderEntity
import com.smartmanager.installments.data.db.SettingEntity
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.util.AppClock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

class RestoreRejected(val reason: Reason, val detail: String = "") : Exception("$reason $detail") {
    enum class Reason { NOT_A_BACKUP, CORRUPT, INTEGRITY_FAILED, UNSUPPORTED_VERSION, NEWER_SCHEMA, PASSPHRASE_REQUIRED, WRONG_PASSPHRASE, INCONSISTENT_DATA }
}

data class RestorePreview(val header: BackupHeader)

/**
 * Backup = every business table (customers, products, contracts, items, installments, payments, allocations, promises,
 * reminders, settings, audit). Deliberately EXCLUDED: the license/activation (device-bound) and sync metadata. No secrets,
 * keys or passwords exist in the database, so none can be exported.
 */
class BackupService(private val context: Context, private val db: AppDatabase, private val clock: AppClock, private val appVersion: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Snapshot(
        val customers: List<CustomerEntity>, val products: List<ProductEntity>, val contracts: List<ContractEntity>,
        val items: List<ContractItemEntity>, val installments: List<InstallmentEntity>, val payments: List<PaymentEntity>,
        val allocations: List<PaymentAllocationEntity>, val promises: List<PromiseEntity>, val reminders: List<ReminderEntity>,
        val settings: List<SettingEntity>, val audit: List<AuditEventEntity>,
    )

    private fun <T> enc(s: KSerializer<T>, list: List<T>): JsonElement = json.encodeToJsonElement(ListSerializer(s), list)
    private fun <T> dec(s: KSerializer<T>, e: JsonElement?): List<T> = if (e == null) emptyList() else json.decodeFromJsonElement(ListSerializer(s), e)

    suspend fun createBackup(passphrase: CharArray?): ByteArray {
        val b = db.bulk()
        val tables = db.withTransaction {
            linkedMapOf(
                "customers" to enc(CustomerEntity.serializer(), b.customers()),
                "products" to enc(ProductEntity.serializer(), b.products()),
                "contracts" to enc(ContractEntity.serializer(), b.contracts()),
                "contract_items" to enc(ContractItemEntity.serializer(), b.contractItems()),
                "installments" to enc(InstallmentEntity.serializer(), b.installments()),
                "payments" to enc(PaymentEntity.serializer(), b.payments()),
                "payment_allocations" to enc(PaymentAllocationEntity.serializer(), b.allocations()),
                "promises" to enc(PromiseEntity.serializer(), b.promises()),
                "reminders" to enc(ReminderEntity.serializer(), b.reminders()),
                "settings" to enc(SettingEntity.serializer(), b.settings()),
                "audit_events" to enc(AuditEventEntity.serializer(), b.audit()),
            )
        }
        return BackupCodec.encode(tables, appVersion, AppDatabase.VERSION, clock.nowMillis(), passphrase)
    }

    /** Validates the file without touching the database. */
    fun inspect(bytes: ByteArray): RestorePreview = try {
        val h = BackupCodec.inspect(bytes)
        if (h.dbSchemaVersion > AppDatabase.VERSION) throw RestoreRejected(RestoreRejected.Reason.NEWER_SCHEMA, "${h.dbSchemaVersion}")
        RestorePreview(h)
    } catch (e: BackupException) {
        throw map(e)
    }

    private fun map(e: BackupException) = RestoreRejected(
        when (e.error) {
            BackupError.NOT_A_BACKUP -> RestoreRejected.Reason.NOT_A_BACKUP
            BackupError.CORRUPT -> RestoreRejected.Reason.CORRUPT
            BackupError.INTEGRITY_FAILED -> RestoreRejected.Reason.INTEGRITY_FAILED
            BackupError.UNSUPPORTED_VERSION -> RestoreRejected.Reason.UNSUPPORTED_VERSION
            BackupError.PASSPHRASE_REQUIRED -> RestoreRejected.Reason.PASSPHRASE_REQUIRED
            BackupError.WRONG_PASSPHRASE -> RestoreRejected.Reason.WRONG_PASSPHRASE
        },
    )

    private fun parse(bytes: ByteArray, passphrase: CharArray?): Snapshot {
        val d = try {
            BackupCodec.decode(bytes, passphrase)
        } catch (e: BackupException) {
            throw map(e)
        }
        // Restore must apply the same compatibility rule as inspect(): a backup from a NEWER database schema is refused.
        if (d.header.dbSchemaVersion > AppDatabase.VERSION) throw RestoreRejected(RestoreRejected.Reason.NEWER_SCHEMA, "${d.header.dbSchemaVersion}")
        val t = d.tables
        val s = try {
            Snapshot(
                dec(CustomerEntity.serializer(), t["customers"]), dec(ProductEntity.serializer(), t["products"]),
                dec(ContractEntity.serializer(), t["contracts"]), dec(ContractItemEntity.serializer(), t["contract_items"]),
                dec(InstallmentEntity.serializer(), t["installments"]), dec(PaymentEntity.serializer(), t["payments"]),
                dec(PaymentAllocationEntity.serializer(), t["payment_allocations"]), dec(PromiseEntity.serializer(), t["promises"]),
                dec(ReminderEntity.serializer(), t["reminders"]), dec(SettingEntity.serializer(), t["settings"]),
                dec(AuditEventEntity.serializer(), t["audit_events"]),
            )
        } catch (e: Exception) {
            throw RestoreRejected(RestoreRejected.Reason.CORRUPT, e.javaClass.simpleName)
        }
        verifyConsistency(s)
        return s
    }

    /** Referential and financial consistency checks BEFORE anything is replaced. */
    private fun verifyConsistency(s: Snapshot) {
        fun bad(msg: String): Nothing = throw RestoreRejected(RestoreRejected.Reason.INCONSISTENT_DATA, msg)
        val customers = s.customers.map { it.id }.toSet()
        val contracts = s.contracts.associateBy { it.id }
        val installments = s.installments.associateBy { it.id }
        val payments = s.payments.associateBy { it.id }
        if (s.customers.size != customers.size) bad("duplicate customer ids")
        s.contracts.forEach { if (it.customerId !in customers) bad("contract ${it.number} has no customer") }
        s.items.forEach { if (it.contractId !in contracts) bad("orphan contract item") }
        s.installments.forEach { if (it.contractId !in contracts) bad("orphan installment") }
        s.payments.forEach {
            if (it.customerId !in customers || it.contractId !in contracts) bad("payment ${it.receiptNumber} has no customer/contract")
            if (it.amountMinor <= 0) bad("payment ${it.receiptNumber} amount")
        }
        s.promises.forEach { if (it.customerId !in customers) bad("orphan promise") }
        s.reminders.forEach { if (it.customerId !in customers) bad("orphan reminder") }
        if (s.payments.map { it.receiptNumber }.toSet().size != s.payments.size) bad("duplicate receipt numbers")
        if (s.payments.map { it.requestKey }.toSet().size != s.payments.size) bad("duplicate payment keys")
        if (s.contracts.map { it.number }.toSet().size != s.contracts.size) bad("duplicate contract numbers")
        val allocByPayment = s.allocations.groupBy { it.paymentId }
        s.allocations.forEach {
            if (it.paymentId !in payments || it.installmentId !in installments) bad("orphan allocation")
            if (it.amountMinor <= 0) bad("allocation amount")
        }
        for (p in s.payments) {
            val total = allocByPayment[p.id].orEmpty().sumOf { it.amountMinor }
            val expected = if (p.kind == PaymentKind.DOWN_PAYMENT) 0L else p.amountMinor
            if (total != expected) bad("payment ${p.receiptNumber}: allocations $total != $expected")
        }
        // No installment may be paid beyond its amount by POSTED payments.
        val paid = HashMap<String, Long>()
        s.allocations.forEach { a -> if (payments[a.paymentId]?.status == Status.POSTED) paid.merge(a.installmentId, a.amountMinor, Long::plus) }
        for ((id, sum) in paid) if (sum > installments.getValue(id).amountMinor) bad("installment overpaid")
        for (c in s.contracts) {
            if (c.status == Status.DRAFT || c.status == Status.CANCELLED) continue
            val active = s.installments.filter { it.contractId == c.id && it.state == Status.ACTIVE }
            if (active.isNotEmpty() && c.rescheduleCount == 0 && active.sumOf { it.amountMinor } != c.financedMinor) bad("contract ${c.number}: schedule != financed")
        }
    }

    /**
     * Replaces ALL business data with the backup's content in a single transaction (all-or-nothing). Before replacing, an
     * automatic safety copy of the current data is written to the app's private folder.
     */
    suspend fun restore(bytes: ByteArray, passphrase: CharArray?): Int {
        val snapshot = parse(bytes, passphrase)
        writeSafetyCopy()
        val b = db.bulk()
        db.withTransaction {
            b.clearAllocations(); b.clearPayments(); b.clearInstallments(); b.clearContractItems(); b.clearContracts()
            b.clearPromises(); b.clearReminders(); b.clearProducts(); b.clearCustomers(); b.clearSettings(); b.clearAudit()
            b.putCustomers(snapshot.customers); b.putProducts(snapshot.products); b.putContracts(snapshot.contracts)
            b.putContractItems(snapshot.items); b.putInstallments(snapshot.installments); b.putPayments(snapshot.payments)
            b.putAllocations(snapshot.allocations); b.putPromises(snapshot.promises); b.putReminders(snapshot.reminders)
            b.putSettings(snapshot.settings); b.putAudit(snapshot.audit)
            if (b.customers().size != snapshot.customers.size || b.payments().size != snapshot.payments.size) error("restore verification failed")
        }
        return snapshot.customers.size
    }

    private suspend fun writeSafetyCopy() {
        val dir = File(context.filesDir, "safety-backups").apply { mkdirs() }
        val out = File(dir, "before-restore-${clock.nowMillis()}.smibak")
        out.writeBytes(createBackup(null))
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }
}
