package com.smartmanager.installments

import com.smartmanager.installments.core.backup.BackupCodec
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.data.repo.BackupService
import com.smartmanager.installments.data.repo.PaymentRequest
import com.smartmanager.installments.data.repo.RestoreRejected
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupAndPersistenceTests {

    private fun pay(contract: String, amount: Long) = PaymentRequest(java.util.UUID.randomUUID().toString(), contract, amount, "CASH", confirmPossibleDuplicate = true)

    private data class Snapshot(val customers: Int, val contracts: Int, val payments: Int, val allocations: Int, val outstanding: Long, val collected: Long)

    private suspend fun snapshot(env: Env): Snapshot {
        val b = env.db.bulk()
        val contracts = b.contracts()
        return Snapshot(
            b.customers().size, contracts.size, b.payments().size, b.allocations().size,
            contracts.sumOf { c -> env.db.contracts().balances(c.id).filter { c.status == Status.ACTIVE }.sumOf { it.outstandingMinor } },
            contracts.sumOf { env.db.contracts().observeCollected(it.id).first() ?: 0L },
        )
    }

    private suspend fun populate(env: Env): String {
        val a = env.newCustomer("عميل عربي", "20111222"); val b = env.newCustomer("Élodie Martin", "55666777")
        val c1 = env.fridgeContract(a); val c2 = env.fridgeContract(b)
        env.ledger.recordPayment(pay(c1, 450_000)); env.ledger.recordPayment(pay(c1, 30_000)); env.ledger.recordPayment(pay(c2, 2_000_000))
        env.ledger.reversePayment(env.db.bulk().payments().first { it.amountMinor == 30_000L }.id, "test")
        env.db.settings().put(com.smartmanager.installments.data.db.SettingEntity("business.name", "متجر النور"))
        return c1
    }

    @Test fun backupAndRestoreRoundTripKeepsEveryBalance() = runBlocking {
        val env = Env()
        populate(env)
        val before = snapshot(env)
        val svc = BackupService(env.context, env.db, env.clock, "1.0.0")
        val file = svc.createBackup(null)

        // mutate the live data after the backup, then restore: the backup must win completely
        val extra = env.newCustomer("Added later", "29999999"); env.fridgeContract(extra)
        assertTrue(snapshot(env) != before)

        assertEquals(2, svc.inspect(file).header.tableCounts["customers"])
        svc.restore(file, null)
        assertEquals(before, snapshot(env))
        assertEquals("متجر النور", env.db.settings().get("business.name"))
        // restored allocations still drive balances: keep working after the restore
        val c = env.db.bulk().contracts().first { env.db.contracts().balances(it.id).sumOf { b -> b.outstandingMinor } > 0 }
        env.ledger.recordPayment(pay(c.id, 10_000))
        assertEquals(before.outstanding - 10_000, snapshot(env).outstanding)
        // numbering continues from the restored counters (no duplicate receipt/contract numbers)
        env.fridgeContract(env.db.bulk().customers().first().id)
        val numbers = env.db.bulk().contracts().map { it.number }
        assertEquals(numbers.size, numbers.toSet().size)
        env.db.close()
    }

    @Test fun encryptedBackupNeedsThePassphrase() = runBlocking {
        val env = Env(); populate(env)
        val svc = BackupService(env.context, env.db, env.clock, "1.0.0")
        val file = svc.createBackup("s3cret-pass".toCharArray())
        assertTrue(svc.inspect(file).header.encrypted)
        assertEquals(RestoreRejected.Reason.PASSPHRASE_REQUIRED, expectThrows<RestoreRejected> { runBlocking { svc.restore(file, null) } }.reason)
        assertEquals(RestoreRejected.Reason.WRONG_PASSPHRASE, expectThrows<RestoreRejected> { runBlocking { svc.restore(file, "nope".toCharArray()) } }.reason)
        val before = snapshot(env)
        svc.restore(file, "s3cret-pass".toCharArray())
        assertEquals(before, snapshot(env))
        env.db.close()
    }

    @Test fun invalidBackupsAreRejectedAndNothingChanges() = runBlocking {
        val env = Env(); populate(env)
        val svc = BackupService(env.context, env.db, env.clock, "1.0.0")
        val before = snapshot(env)
        fun reject(bytes: ByteArray, reason: RestoreRejected.Reason) {
            assertEquals(reason, expectThrows<RestoreRejected> { runBlocking { svc.restore(bytes, null) } }.reason)
            assertEquals(before, runBlocking { snapshot(env) })
        }
        reject("not a backup".toByteArray(), RestoreRejected.Reason.NOT_A_BACKUP)
        val good = svc.createBackup(null)
        // flipped byte => integrity failure
        val raw = GZIPInputStream(good.inputStream()).readBytes().also { it[it.size - 9] = (it[it.size - 9] + 1).toByte() }
        reject(ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray(), RestoreRejected.Reason.INTEGRITY_FAILED)
        // incompatible (future) format version
        val future = String(GZIPInputStream(good.inputStream()).readBytes(), Charsets.ISO_8859_1).replace("\"formatVersion\":1", "\"formatVersion\":9")
        reject(ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(future.toByteArray(Charsets.ISO_8859_1)) } }.toByteArray(), RestoreRejected.Reason.UNSUPPORTED_VERSION)
        // newer database schema than this app understands
        val newer = String(GZIPInputStream(good.inputStream()).readBytes(), Charsets.ISO_8859_1).replace("\"dbSchemaVersion\":1", "\"dbSchemaVersion\":99")
        reject(ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(newer.toByteArray(Charsets.ISO_8859_1)) } }.toByteArray(), RestoreRejected.Reason.NEWER_SCHEMA)
        env.db.close()
    }

    @Test fun internallyInconsistentBackupIsRejectedBeforeAnythingIsReplaced() = runBlocking {
        val env = Env(); populate(env)
        val svc = BackupService(env.context, env.db, env.clock, "1.0.0")
        val before = snapshot(env)
        // Build a structurally valid container whose allocations exceed the payment amount.
        val decoded = BackupCodec.decode(svc.createBackup(null))
        val tables = decoded.tables.toMutableMap()
        val allocs = tables.getValue("payment_allocations") as kotlinx.serialization.json.JsonArray
        val first = allocs[0] as kotlinx.serialization.json.JsonObject
        tables["payment_allocations"] = kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(first + ("amountMinor" to kotlinx.serialization.json.JsonPrimitive(999_999_999L)))) + allocs.drop(1))
        val tampered = BackupCodec.encode(tables, "1.0.0", 1, 1L)
        assertEquals(RestoreRejected.Reason.INCONSISTENT_DATA, expectThrows<RestoreRejected> { runBlocking { svc.restore(tampered, null) } }.reason)
        assertEquals(before, snapshot(env))
        env.db.close()
    }

    @Test fun backupNeverContainsLicenseOrSecrets() = runBlocking {
        val env = Env(); populate(env)
        env.db.license().put(com.smartmanager.installments.data.db.LicenseEntity("current", "LICENSE-TEXT-SECRET", "L1", 1))
        val svc = BackupService(env.context, env.db, env.clock, "1.0.0")
        val text = String(GZIPInputStream(svc.createBackup(null).inputStream()).readBytes(), Charsets.UTF_8)
        assertTrue(!text.contains("LICENSE-TEXT-SECRET") && !text.contains("\"license\""))
        env.db.close()
    }

    /** Committed financial operations survive closing and re-opening the app (file-backed database). */
    @Test fun dataSurvivesReopeningTheDatabase() = runBlocking {
        val env = Env(dbName = "persist-test.db")
        val c1 = populate(env)
        val before = snapshot(env)
        env.reopen()
        assertEquals(before, snapshot(env))
        env.ledger.recordPayment(pay(c1, 5_000))
        env.reopen()
        assertEquals(before.outstanding - 5_000, snapshot(env).outstanding)
        env.db.close()
    }
}
