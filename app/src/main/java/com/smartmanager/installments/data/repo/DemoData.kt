package com.smartmanager.installments.data.repo

import androidx.room.withTransaction
import com.smartmanager.installments.core.payments.AllocationRule
import com.smartmanager.installments.core.schedule.Frequency
import com.smartmanager.installments.core.schedule.ScheduleGenerator
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.db.ProductEntity
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.util.AppClock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * DEVELOPMENT-ONLY sample data (the generator is reachable only in debug builds). Everything it creates is flagged isDemo so it can
 * be removed with one tap and is never mixed up with real customers. It is NEVER created automatically.
 * Data is created through the normal LedgerRepository so every financial invariant holds.
 */
class DemoData(private val db: AppDatabase, private val ledger: LedgerRepository, private val clock: AppClock) {
    private val names = listOf(
        "أحمد بن علي" to "20111222", "فاطمة الزهراء" to "98333444", "Mohamed Trabelsi" to "55666777", "Salma Ben Salah" to "22888999",
        "Karim Jebali" to "50123456", "نور الهدى" to "29456789", "Youssef Mansouri" to "97654321", "Ines Gharbi" to "53987654",
        "عبد الله السعيد" to "21345678", "Rania Khelifi" to "94112233",
    )
    private val products = listOf("ثلاجة Samsung 400L" to 2_400_000L, "Machine à laver LG 8kg" to 1_800_000L, "iPhone 14 128GB" to 3_200_000L, "Laptop HP 15" to 2_100_000L, "طقم صالون" to 4_500_000L, "Smart TV 55\"" to 2_700_000L)

    suspend fun generate() {
        val now = clock.nowMillis()
        val today = clock.today()
        val customers = names.map { (n, p) -> CustomerEntity(UUID.randomUUID().toString(), n, p, null, "تونس", null, "demo", Status.ACTIVE, true, now, now) }
        val prods = products.map { (n, price) -> ProductEntity(UUID.randomUUID().toString(), n, null, "demo", price, null, null, "", true, true, now, now) }
        db.withTransaction { customers.forEach { db.customers().insert(it) }; prods.forEach { db.products().insert(it) } }
        val zone: ZoneId = clock.zone()
        customers.forEachIndexed { i, cu ->
            val prod = prods[i % prods.size]
            val monthsAgo = (i % 5) + 1L
            val start = today.minusMonths(monthsAgo)
            val down = prod.priceMinor / 6 / 1000 * 1000
            val financed = prod.priceMinor - down
            val sched = ScheduleGenerator.generate(financed, 10, Frequency.MONTHLY, start.plusMonths(1), 1000)
            val id = ledger.createContract(
                NewContract(cu.id, start, "TND", listOf(NewItem(prod.id, prod.name, 1, prod.priceMinor)), 0, down, Frequency.MONTHLY, sched, "", "demo", true, "CASH", null, true,
                    start.atStartOfDay(zone).toInstant().toEpochMilli()),
            )
            // pay a varying number of installments; some customers fall behind, one pays partially
            val paidCount = when (i % 4) { 0 -> monthsAgo.toInt(); 1 -> (monthsAgo - 1).coerceAtLeast(0).toInt(); 2 -> 0; else -> monthsAgo.toInt() }
            sched.take(paidCount).forEachIndexed { k, s ->
                ledger.recordPayment(PaymentRequest("demo-$id-$k", id, s.amountMinor, if (k % 2 == 0) "CASH" else "BANK_TRANSFER", null, "", s.dueDate.atStartOfDay(zone).toInstant().toEpochMilli() + 36_000_000L, null, AllocationRule.OLDEST_FIRST, true))
            }
            if (i % 4 == 3 && paidCount < sched.size) {
                ledger.recordPayment(PaymentRequest("demo-$id-partial", id, sched[paidCount].amountMinor / 3, "CASH", null, "partial", now, null, AllocationRule.OLDEST_FIRST, true))
            }
        }
    }

    /** Removes every demo record (and only those), children first. */
    suspend fun remove() {
        val b = db.bulk()
        db.withTransaction {
            val ids = db.contracts().demoContractIds()
            if (ids.isNotEmpty()) {
                b.deleteAllocationsOfContracts(ids)
                b.deletePaymentsOfContracts(ids)
                ids.forEach { id -> db.contracts().deleteContract(id) }
            }
            b.deleteDemoPromises(); b.deleteDemoReminders(); b.deleteDemoCustomers(); b.deleteDemoProducts()
        }
    }

    suspend fun count(): Int = db.bulk().demoCustomerCount()
}
