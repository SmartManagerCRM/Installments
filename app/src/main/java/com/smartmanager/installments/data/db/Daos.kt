package com.smartmanager.installments.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---------- Result rows ---------------------------------------------------------------------------------------------

data class CustomerRow(
    val id: String, val fullName: String, val phone: String, val status: String,
    val outstandingMinor: Long, val overdueMinor: Long,
)

data class ContractRow(
    val id: String, val number: String, val customerId: String, val customerName: String, val status: String,
    val currency: String, val contractDate: Long, val originalPriceMinor: Long, val discountMinor: Long,
    val downPaymentMinor: Long, val financedMinor: Long, val installmentCount: Int, val rescheduleCount: Int,
    val outstandingMinor: Long, val nextDueDate: Long?,
)

data class InstallmentRow(
    @Embedded val b: InstallmentBalanceView,
    val customerName: String,
    val phone: String,
)

data class PaymentRow(
    @Embedded val p: PaymentEntity,
    val customerName: String,
    val contractNumber: String,
)

data class AmountCount(val amountMinor: Long, val count: Int)

data class OverdueCustomerRow(
    val customerId: String, val customerName: String, val phone: String, val overdueMinor: Long, val oldestDueDate: Long,
)

data class CollectionRow(
    val customerId: String, val customerName: String, val phone: String, val outstandingMinor: Long,
    val overdueMinor: Long, val dueTodayMinor: Long, val oldestOverdueDate: Long, val hasPartial: Int,
)

data class CustomerBalanceRow(
    val customerName: String, val phone: String, val contracts: Int, val outstandingMinor: Long,
)

data class MethodTotal(val method: String, val totalMinor: Long)

// ---------- DAOs ----------------------------------------------------------------------------------------------------

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(c: CustomerEntity)
    @Update suspend fun update(c: CustomerEntity)
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun get(id: String): CustomerEntity?
    @Query("SELECT * FROM customers WHERE id = :id") fun observe(id: String): Flow<CustomerEntity?>
    @Query("SELECT COUNT(*) FROM customers WHERE status = 'ACTIVE'") fun observeActiveCount(): Flow<Int>
    @Query("SELECT * FROM customers WHERE status = 'ACTIVE' ORDER BY fullName COLLATE NOCASE LIMIT :limit")
    suspend fun activeCustomers(limit: Int): List<CustomerEntity>

    @Query(
        """SELECT * FROM customers WHERE status = 'ACTIVE' AND (:q = '' OR fullName LIKE '%' || :q || '%' OR phone LIKE '%' || :q || '%'
           OR IFNULL(nationalRef,'') LIKE '%' || :q || '%') ORDER BY fullName COLLATE NOCASE LIMIT 50"""
    )
    suspend fun search(q: String): List<CustomerEntity>

    /** Possible duplicates: same phone digits or same name (case-insensitive). The retailer may still proceed. */
    @Query(
        """SELECT * FROM customers WHERE id != :excludeId AND ((:phone != '' AND (phone = :phone OR IFNULL(altPhone,'') = :phone))
           OR LOWER(fullName) = LOWER(:name)) LIMIT 5"""
    )
    suspend fun similar(name: String, phone: String, excludeId: String): List<CustomerEntity>

    @Query("SELECT COUNT(*) FROM contracts WHERE customerId = :id") suspend fun contractCount(id: String): Int
    @Query("SELECT COUNT(*) FROM payments WHERE customerId = :id") suspend fun paymentCount(id: String): Int
    @Delete suspend fun delete(c: CustomerEntity)

    @Query(
        """SELECT * FROM (
             SELECT c.id AS id, c.fullName AS fullName, c.phone AS phone, c.status AS status,
               IFNULL((SELECT SUM(b.amountMinor - b.paidMinor) FROM installment_balances b WHERE b.customerId = c.id
                        AND b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur), 0) AS outstandingMinor,
               IFNULL((SELECT SUM(b.amountMinor - b.paidMinor) FROM installment_balances b WHERE b.customerId = c.id
                        AND b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur
                        AND b.dueDate < :today AND b.amountMinor > b.paidMinor), 0) AS overdueMinor
             FROM customers c
             WHERE c.status = :status AND (:q = '' OR c.fullName LIKE '%' || :q || '%' OR c.phone LIKE '%' || :q || '%'
                    OR IFNULL(c.altPhone,'') LIKE '%' || :q || '%' OR IFNULL(c.nationalRef,'') LIKE '%' || :q || '%')
           ) WHERE (:filter = 0 OR (:filter = 1 AND outstandingMinor > 0) OR (:filter = 2 AND overdueMinor > 0)
                    OR (:filter = 3 AND outstandingMinor = 0))
           ORDER BY fullName COLLATE NOCASE"""
    )
    fun paged(q: String, status: String, filter: Int, today: Long, cur: String): PagingSource<Int, CustomerRow>
}

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(p: ProductEntity)
    @Update suspend fun update(p: ProductEntity)
    @Query("SELECT * FROM products WHERE id = :id") suspend fun get(id: String): ProductEntity?
    @Query("SELECT COUNT(*) FROM contract_items WHERE productId = :id") suspend fun usageCount(id: String): Int
    @Delete suspend fun delete(p: ProductEntity)

    @Query(
        """SELECT * FROM products WHERE active = :active AND (:q = '' OR name LIKE '%' || :q || '%' OR IFNULL(sku,'') LIKE '%' || :q || '%'
           OR category LIKE '%' || :q || '%') ORDER BY name COLLATE NOCASE"""
    )
    fun paged(q: String, active: Boolean): PagingSource<Int, ProductEntity>

    @Query("SELECT * FROM products WHERE active = 1 AND (:q = '' OR name LIKE '%' || :q || '%' OR IFNULL(sku,'') LIKE '%' || :q || '%') ORDER BY name COLLATE NOCASE LIMIT 40")
    suspend fun search(q: String): List<ProductEntity>
}

private const val CONTRACT_SELECT = """
SELECT c.id AS id, c.number AS number, c.customerId AS customerId, cu.fullName AS customerName, c.status AS status,
  c.currency AS currency, c.contractDate AS contractDate, c.originalPriceMinor AS originalPriceMinor,
  c.discountMinor AS discountMinor, c.downPaymentMinor AS downPaymentMinor, c.financedMinor AS financedMinor,
  c.installmentCount AS installmentCount, c.rescheduleCount AS rescheduleCount,
  CASE WHEN c.status = 'ACTIVE' THEN IFNULL((SELECT SUM(b.amountMinor - b.paidMinor) FROM installment_balances b
       WHERE b.contractId = c.id AND b.state = 'ACTIVE'), 0) ELSE 0 END AS outstandingMinor,
  (SELECT MIN(b.dueDate) FROM installment_balances b WHERE b.contractId = c.id AND b.state = 'ACTIVE'
       AND b.amountMinor > b.paidMinor AND c.status = 'ACTIVE') AS nextDueDate
FROM contracts c JOIN customers cu ON cu.id = c.customerId"""

@Dao
interface ContractDao {
    @Insert suspend fun insert(c: ContractEntity)
    @Update suspend fun update(c: ContractEntity)
    @Insert suspend fun insertItems(items: List<ContractItemEntity>)
    @Insert suspend fun insertInstallments(list: List<InstallmentEntity>)
    @Update suspend fun updateInstallments(list: List<InstallmentEntity>)
    @Query("DELETE FROM contract_items WHERE contractId = :id") suspend fun deleteItems(id: String)
    @Query("DELETE FROM installments WHERE contractId = :id") suspend fun deleteInstallments(id: String)
    @Query("DELETE FROM contracts WHERE id = :id") suspend fun deleteContract(id: String)

    @Query("SELECT * FROM contracts WHERE id = :id") suspend fun get(id: String): ContractEntity?
    @Query("SELECT * FROM contracts WHERE id = :id") fun observe(id: String): Flow<ContractEntity?>
    @Query("SELECT * FROM contract_items WHERE contractId = :id ORDER BY sortOrder") suspend fun items(id: String): List<ContractItemEntity>
    @Query("SELECT * FROM contract_items WHERE contractId = :id ORDER BY sortOrder") fun observeItems(id: String): Flow<List<ContractItemEntity>>
    @Query("SELECT * FROM installments WHERE contractId = :id ORDER BY revision, sequence") suspend fun installments(id: String): List<InstallmentEntity>

    /** Current (ACTIVE) schedule with real paid amounts. */
    @Query("SELECT * FROM installment_balances WHERE contractId = :id AND state = 'ACTIVE' ORDER BY dueDate, sequence")
    suspend fun balances(id: String): List<InstallmentBalanceView>

    @Query("SELECT * FROM installment_balances WHERE contractId = :id AND state = 'ACTIVE' ORDER BY dueDate, sequence")
    fun observeBalances(id: String): Flow<List<InstallmentBalanceView>>

    /** Whole history including superseded (rescheduled) rows. */
    @Query("SELECT * FROM installment_balances WHERE contractId = :id ORDER BY revision, sequence")
    fun observeAllBalances(id: String): Flow<List<InstallmentBalanceView>>

    @Query("SELECT * FROM installment_balances WHERE installmentId = :id") suspend fun balanceOf(id: String): InstallmentBalanceView?

    @Query("SELECT SUM(amountMinor) FROM payments WHERE contractId = :id AND status = 'POSTED' AND kind IN ('PAYMENT','DOWN_PAYMENT')")
    fun observeCollected(id: String): Flow<Long?>

    @Query("$CONTRACT_SELECT WHERE c.id = :id") fun observeRow(id: String): Flow<ContractRow?>

    @Query(
        """$CONTRACT_SELECT WHERE (:status = 'ALL' OR c.status = :status) AND (:customerId = '' OR c.customerId = :customerId)
           AND (:q = '' OR c.number LIKE '%' || :q || '%' OR cu.fullName LIKE '%' || :q || '%') ORDER BY c.contractDate DESC, c.number DESC"""
    )
    fun paged(status: String, customerId: String, q: String): PagingSource<Int, ContractRow>

    @Query("$CONTRACT_SELECT WHERE c.customerId = :customerId ORDER BY c.contractDate DESC, c.number DESC")
    fun observeForCustomer(customerId: String): Flow<List<ContractRow>>

    @Query("$CONTRACT_SELECT WHERE c.status = :status ORDER BY c.contractDate DESC") suspend fun byStatus(status: String): List<ContractRow>

    @Query("SELECT id FROM contracts WHERE isDemo = 1 OR customerId IN (SELECT id FROM customers WHERE isDemo = 1)")
    suspend fun demoContractIds(): List<String>

    @Query("SELECT COUNT(*) FROM contracts WHERE status = 'ACTIVE'") fun observeActiveCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM contracts WHERE status = 'ACTIVE' AND currency != :cur") fun observeOtherCurrencyActive(cur: String): Flow<Int>
}

@Dao
interface PaymentDao {
    @Insert suspend fun insert(p: PaymentEntity)
    @Insert suspend fun insertAllocations(list: List<PaymentAllocationEntity>)
    @Query("UPDATE payments SET status = 'REVERSED', reversedAt = :at, reversalReason = :reason WHERE id = :id AND status = 'POSTED'")
    suspend fun markReversed(id: String, at: Long, reason: String): Int

    @Query("SELECT * FROM payments WHERE id = :id") suspend fun get(id: String): PaymentEntity?
    @Query("SELECT * FROM payments WHERE id = :id") fun observe(id: String): Flow<PaymentEntity?>
    @Query("SELECT * FROM payments WHERE requestKey = :key") suspend fun byRequestKey(key: String): PaymentEntity?
    @Query("SELECT * FROM payment_allocations WHERE paymentId = :id") suspend fun allocations(id: String): List<PaymentAllocationEntity>
    @Query("SELECT * FROM payment_allocations WHERE paymentId = :id") fun observeAllocations(id: String): Flow<List<PaymentAllocationEntity>>

    @Query("SELECT COUNT(*) FROM payments WHERE contractId = :contractId AND amountMinor = :amount AND status = 'POSTED' AND createdAt >= :since AND kind = 'PAYMENT'")
    suspend fun recentSame(contractId: String, amount: Long, since: Long): Int

    @Query(
        """SELECT COUNT(*) FROM payment_allocations a JOIN installments i ON i.id = a.installmentId
           WHERE a.paymentId = :paymentId AND i.state != 'ACTIVE'"""
    )
    suspend fun allocationsOnSupersededInstallments(paymentId: String): Int

    @Query("SELECT p.*, cu.fullName AS customerName, c.number AS contractNumber FROM payments p JOIN customers cu ON cu.id = p.customerId JOIN contracts c ON c.id = p.contractId WHERE p.id = :id")
    fun observeRow(id: String): Flow<PaymentRow?>

    @Query(
        """SELECT p.*, cu.fullName AS customerName, c.number AS contractNumber FROM payments p
           JOIN customers cu ON cu.id = p.customerId JOIN contracts c ON c.id = p.contractId
           WHERE (:contractId = '' OR p.contractId = :contractId) AND (:customerId = '' OR p.customerId = :customerId)
             AND (:q = '' OR cu.fullName LIKE '%' || :q || '%' OR p.receiptNumber LIKE '%' || :q || '%' OR c.number LIKE '%' || :q || '%')
           ORDER BY p.paidAt DESC, p.createdAt DESC"""
    )
    fun paged(q: String, contractId: String, customerId: String): PagingSource<Int, PaymentRow>

    @Query(
        """SELECT p.*, cu.fullName AS customerName, c.number AS contractNumber FROM payments p
           JOIN customers cu ON cu.id = p.customerId JOIN contracts c ON c.id = p.contractId
           WHERE p.customerId = :customerId ORDER BY p.paidAt DESC"""
    )
    fun observeForCustomer(customerId: String): Flow<List<PaymentRow>>

    @Query(
        """SELECT p.*, cu.fullName AS customerName, c.number AS contractNumber FROM payments p
           JOIN customers cu ON cu.id = p.customerId JOIN contracts c ON c.id = p.contractId
           WHERE p.paidAt BETWEEN :from AND :to AND p.currency = :cur ORDER BY p.paidAt"""
    )
    suspend fun inPeriod(from: Long, to: Long, cur: String): List<PaymentRow>

    @Query("SELECT * FROM payments WHERE contractId = :contractId ORDER BY paidAt DESC")
    fun observeForContract(contractId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE customerId = :customerId ORDER BY paidAt") suspend fun forCustomer(customerId: String): List<PaymentEntity>

    @Query(
        """SELECT method, SUM(amountMinor) AS totalMinor FROM payments WHERE status = 'POSTED' AND kind IN ('PAYMENT','DOWN_PAYMENT')
           AND paidAt BETWEEN :from AND :to AND currency = :cur GROUP BY method"""
    )
    suspend fun totalsByMethod(from: Long, to: Long, cur: String): List<MethodTotal>
}

@Dao
interface InstallmentDao {
    @Query(
        """SELECT b.*, cu.fullName AS customerName, cu.phone AS phone FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.contractStatus IN ('ACTIVE','COMPLETED') AND b.state = 'ACTIVE' AND b.currency = :cur
             AND ( (:mode = 0 AND b.amountMinor > b.paidMinor)
                OR (:mode = 1 AND b.amountMinor > b.paidMinor AND b.dueDate BETWEEN :from AND :to)
                OR (:mode = 2 AND b.amountMinor > b.paidMinor AND b.dueDate < :today)
                OR (:mode = 3 AND b.paidMinor > 0 AND b.amountMinor > b.paidMinor)
                OR (:mode = 4 AND b.amountMinor <= b.paidMinor)
                OR (:mode = 5) )
             AND (:q = '' OR cu.fullName LIKE '%' || :q || '%' OR b.contractNumber LIKE '%' || :q || '%' OR cu.phone LIKE '%' || :q || '%')
           ORDER BY (CASE WHEN :sort = 1 THEN (b.amountMinor - b.paidMinor) END) DESC,
                    (CASE WHEN :sort = 2 THEN cu.fullName END) COLLATE NOCASE ASC,
                    (CASE WHEN :mode = 4 THEN b.dueDate END) DESC, b.dueDate ASC, b.sequence ASC"""
    )
    fun paged(mode: Int, from: Long, to: Long, today: Long, q: String, sort: Int, cur: String): PagingSource<Int, InstallmentRow>

    @Query(
        """SELECT b.*, cu.fullName AS customerName, cu.phone AS phone FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur AND b.amountMinor > b.paidMinor
             AND b.dueDate BETWEEN :from AND :to ORDER BY b.dueDate, cu.fullName COLLATE NOCASE"""
    )
    suspend fun dueBetween(from: Long, to: Long, cur: String): List<InstallmentRow>

    @Query(
        """SELECT b.*, cu.fullName AS customerName, cu.phone AS phone FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur AND b.amountMinor > b.paidMinor
             AND b.dueDate < :today ORDER BY b.dueDate, cu.fullName COLLATE NOCASE"""
    )
    suspend fun overdue(today: Long, cur: String): List<InstallmentRow>

    @Query(
        """SELECT b.*, cu.fullName AS customerName, cu.phone AS phone FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.customerId = :customerId AND b.state = 'ACTIVE' ORDER BY b.dueDate, b.sequence"""
    )
    fun observeForCustomer(customerId: String): Flow<List<InstallmentRow>>

    @Query(
        """SELECT b.*, cu.fullName AS customerName, cu.phone AS phone FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.customerId = :customerId ORDER BY b.contractNumber, b.revision, b.sequence"""
    )
    suspend fun allForCustomer(customerId: String): List<InstallmentRow>
}

@Dao
interface DashboardDao {
    @Query(
        """SELECT IFNULL(SUM(amountMinor - paidMinor), 0) FROM installment_balances
           WHERE contractStatus = 'ACTIVE' AND state = 'ACTIVE' AND currency = :cur AND amountMinor > paidMinor"""
    )
    fun outstanding(cur: String): Flow<Long>

    @Query(
        """SELECT IFNULL(SUM(amountMinor - paidMinor), 0) AS amountMinor, COUNT(DISTINCT customerId) AS count FROM installment_balances
           WHERE contractStatus = 'ACTIVE' AND state = 'ACTIVE' AND currency = :cur AND amountMinor > paidMinor AND dueDate = :today"""
    )
    fun dueToday(today: Long, cur: String): Flow<AmountCount>

    @Query(
        """SELECT IFNULL(SUM(amountMinor - paidMinor), 0) AS amountMinor, COUNT(DISTINCT customerId) AS count FROM installment_balances
           WHERE contractStatus = 'ACTIVE' AND state = 'ACTIVE' AND currency = :cur AND amountMinor > paidMinor AND dueDate < :today"""
    )
    fun overdue(today: Long, cur: String): Flow<AmountCount>

    @Query(
        """SELECT b.customerId AS customerId, cu.fullName AS customerName, cu.phone AS phone,
                  SUM(b.amountMinor - b.paidMinor) AS overdueMinor, MIN(b.dueDate) AS oldestDueDate
           FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur AND b.amountMinor > b.paidMinor AND b.dueDate < :today
           GROUP BY b.customerId ORDER BY MIN(b.dueDate) ASC, SUM(b.amountMinor - b.paidMinor) DESC LIMIT :limit"""
    )
    fun overdueCustomers(today: Long, cur: String, limit: Int): Flow<List<OverdueCustomerRow>>

    @Query(
        """SELECT b.*, cu.fullName AS customerName, cu.phone AS phone FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur AND b.amountMinor > b.paidMinor AND b.dueDate >= :today
           ORDER BY b.dueDate, cu.fullName COLLATE NOCASE LIMIT :limit"""
    )
    fun upcoming(today: Long, cur: String, limit: Int): Flow<List<InstallmentRow>>

    @Query(
        """SELECT cu.id AS customerId, cu.fullName AS customerName, cu.phone AS phone,
             SUM(b.amountMinor - b.paidMinor) AS outstandingMinor,
             SUM(CASE WHEN b.dueDate < :today THEN b.amountMinor - b.paidMinor ELSE 0 END) AS overdueMinor,
             SUM(CASE WHEN b.dueDate = :today THEN b.amountMinor - b.paidMinor ELSE 0 END) AS dueTodayMinor,
             IFNULL(MIN(CASE WHEN b.dueDate < :today THEN b.dueDate END), :today) AS oldestOverdueDate,
             MAX(CASE WHEN b.paidMinor > 0 THEN 1 ELSE 0 END) AS hasPartial
           FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur AND b.amountMinor > b.paidMinor
           GROUP BY cu.id"""
    )
    fun collectionRows(today: Long, cur: String): Flow<List<CollectionRow>>

    @Query(
        """SELECT cu.fullName AS customerName, cu.phone AS phone, COUNT(DISTINCT b.contractId) AS contracts,
                  SUM(b.amountMinor - b.paidMinor) AS outstandingMinor
           FROM installment_balances b JOIN customers cu ON cu.id = b.customerId
           WHERE b.contractStatus = 'ACTIVE' AND b.state = 'ACTIVE' AND b.currency = :cur AND b.amountMinor > b.paidMinor
           GROUP BY cu.id ORDER BY outstandingMinor DESC"""
    )
    suspend fun outstandingByCustomer(cur: String): List<CustomerBalanceRow>
}

@Dao
interface PromiseDao {
    @Insert suspend fun insert(p: PromiseEntity)
    @Update suspend fun update(p: PromiseEntity)
    @Query("SELECT * FROM promises WHERE id = :id") suspend fun get(id: String): PromiseEntity?
    @Query("SELECT * FROM promises WHERE outcome = 'PENDING' ORDER BY promisedDate") fun observeOpen(): Flow<List<PromiseEntity>>
    @Query("SELECT * FROM promises WHERE customerId = :customerId ORDER BY promisedDate DESC") fun observeForCustomer(customerId: String): Flow<List<PromiseEntity>>
}

@Dao
interface ReminderDao {
    @Insert suspend fun insert(r: ReminderEntity)
    @Query("UPDATE reminders SET outcome = :outcome, outcomeAt = :at WHERE id = :id") suspend fun setOutcome(id: String, outcome: String, at: Long)
    @Query("SELECT * FROM reminders WHERE customerId = :customerId ORDER BY preparedAt DESC LIMIT 50") fun observeForCustomer(customerId: String): Flow<List<ReminderEntity>>
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: SettingEntity)
    @Query("SELECT value FROM settings WHERE key = :key") suspend fun get(key: String): String?
    @Query("SELECT * FROM settings") fun observeAll(): Flow<List<SettingEntity>>
    @Query("SELECT * FROM settings") suspend fun all(): List<SettingEntity>
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(e: AuditEventEntity)
    @Query("SELECT * FROM audit_events WHERE entityId = :entityId ORDER BY at DESC") fun observeFor(entityId: String): Flow<List<AuditEventEntity>>
    @Query("SELECT * FROM audit_events ORDER BY at DESC LIMIT :limit") suspend fun recent(limit: Int): List<AuditEventEntity>
}

@Dao
interface LicenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(l: LicenseEntity)
    @Query("SELECT * FROM license WHERE id = 'current'") suspend fun current(): LicenseEntity?
    @Query("SELECT * FROM license WHERE id = 'current'") fun observe(): Flow<LicenseEntity?>
}

/** Bulk access used by backup / restore / demo-data removal. */
@Dao
interface BulkDao {
    @Query("SELECT * FROM customers") suspend fun customers(): List<CustomerEntity>
    @Query("SELECT * FROM products") suspend fun products(): List<ProductEntity>
    @Query("SELECT * FROM contracts") suspend fun contracts(): List<ContractEntity>
    @Query("SELECT * FROM contract_items") suspend fun contractItems(): List<ContractItemEntity>
    @Query("SELECT * FROM installments") suspend fun installments(): List<InstallmentEntity>
    @Query("SELECT * FROM payments") suspend fun payments(): List<PaymentEntity>
    @Query("SELECT * FROM payment_allocations") suspend fun allocations(): List<PaymentAllocationEntity>
    @Query("SELECT * FROM promises") suspend fun promises(): List<PromiseEntity>
    @Query("SELECT * FROM reminders") suspend fun reminders(): List<ReminderEntity>
    @Query("SELECT * FROM settings") suspend fun settings(): List<SettingEntity>
    @Query("SELECT * FROM audit_events") suspend fun audit(): List<AuditEventEntity>

    @Insert suspend fun putCustomers(l: List<CustomerEntity>)
    @Insert suspend fun putProducts(l: List<ProductEntity>)
    @Insert suspend fun putContracts(l: List<ContractEntity>)
    @Insert suspend fun putContractItems(l: List<ContractItemEntity>)
    @Insert suspend fun putInstallments(l: List<InstallmentEntity>)
    @Insert suspend fun putPayments(l: List<PaymentEntity>)
    @Insert suspend fun putAllocations(l: List<PaymentAllocationEntity>)
    @Insert suspend fun putPromises(l: List<PromiseEntity>)
    @Insert suspend fun putReminders(l: List<ReminderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSettings(l: List<SettingEntity>)
    @Insert suspend fun putAudit(l: List<AuditEventEntity>)

    @Query("DELETE FROM payment_allocations") suspend fun clearAllocations()
    @Query("DELETE FROM payments") suspend fun clearPayments()
    @Query("DELETE FROM installments") suspend fun clearInstallments()
    @Query("DELETE FROM contract_items") suspend fun clearContractItems()
    @Query("DELETE FROM contracts") suspend fun clearContracts()
    @Query("DELETE FROM promises") suspend fun clearPromises()
    @Query("DELETE FROM reminders") suspend fun clearReminders()
    @Query("DELETE FROM products") suspend fun clearProducts()
    @Query("DELETE FROM customers") suspend fun clearCustomers()
    @Query("DELETE FROM settings") suspend fun clearSettings()
    @Query("DELETE FROM audit_events") suspend fun clearAudit()

    // demo-data removal
    @Query("DELETE FROM payment_allocations WHERE paymentId IN (SELECT id FROM payments WHERE contractId IN (:ids))") suspend fun deleteAllocationsOfContracts(ids: List<String>)
    @Query("DELETE FROM payments WHERE contractId IN (:ids)") suspend fun deletePaymentsOfContracts(ids: List<String>)
    @Query("DELETE FROM promises WHERE customerId IN (SELECT id FROM customers WHERE isDemo = 1)") suspend fun deleteDemoPromises()
    @Query("DELETE FROM reminders WHERE customerId IN (SELECT id FROM customers WHERE isDemo = 1)") suspend fun deleteDemoReminders()
    @Query("DELETE FROM customers WHERE isDemo = 1") suspend fun deleteDemoCustomers()
    @Query("DELETE FROM products WHERE isDemo = 1") suspend fun deleteDemoProducts()
    @Query("SELECT COUNT(*) FROM customers WHERE isDemo = 1") suspend fun demoCustomerCount(): Int
    @Query("SELECT COUNT(*) FROM customers") suspend fun customerCount(): Int
}
