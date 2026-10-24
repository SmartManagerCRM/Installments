package com.smartmanager.installments

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smartmanager.installments.core.schedule.Frequency
import com.smartmanager.installments.core.schedule.ScheduleGenerator
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.repo.CustomerInput
import com.smartmanager.installments.data.repo.CustomerRepository
import com.smartmanager.installments.data.repo.LedgerRepository
import com.smartmanager.installments.data.repo.NewContract
import com.smartmanager.installments.data.repo.NewItem
import com.smartmanager.installments.data.repo.WriteGuard
import com.smartmanager.installments.util.AppClock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class FakeClock(var now: Long = LocalDate.of(2026, 3, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()) : AppClock {
    override fun nowMillis() = now
    override fun zone(): ZoneId = ZoneOffset.UTC
    fun advanceDays(d: Long) { now += d * 86_400_000L }
}

class Env(val guard: WriteGuard = WriteGuard.AllowAll, val dbName: String? = null) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = FakeClock()
    var db: AppDatabase = open()
    var ledger = LedgerRepository(db, clock, guard)
    var customers = CustomerRepository(db, clock, guard)

    private fun open(): AppDatabase =
        if (dbName == null) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        else Room.databaseBuilder(context, AppDatabase::class.java, dbName).allowMainThreadQueries().build()

    /** Simulates closing and re-opening the app (file-backed databases only). */
    fun reopen() {
        db.close()
        db = open()
        ledger = LedgerRepository(db, clock, guard)
        customers = CustomerRepository(db, clock, guard)
    }

    suspend fun newCustomer(name: String = "Ali Ben Salah", phone: String = "20123456"): String =
        customers.save(CustomerInput(null, name, phone, null, "", null, ""))

    /** Refrigerator example: 2,400 TND, 400 down, 10 monthly installments of 200 TND (TND = 3 decimals). */
    suspend fun fridgeContract(customerId: String, first: LocalDate = LocalDate.of(2026, 4, 1), activate: Boolean = true): String =
        ledger.createContract(
            NewContract(
                customerId, LocalDate.of(2026, 3, 1), "TND", listOf(NewItem(null, "Refrigerator", 1, 2_400_000)), 0, 400_000,
                Frequency.MONTHLY, ScheduleGenerator.generate(2_000_000, 10, Frequency.MONTHLY, first, 1000), "terms", "", activate,
            ),
        )

    suspend fun outstanding(contractId: String) = db.contracts().balances(contractId).sumOf { it.outstandingMinor }
}

inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("expected ${T::class.java.simpleName} but got $t", t)
    }
    throw AssertionError("expected ${T::class.java.simpleName} but nothing was thrown")
}
