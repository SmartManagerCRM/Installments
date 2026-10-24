package com.smartmanager.installments

import com.smartmanager.installments.core.licensing.LicenseStatus
import com.smartmanager.installments.core.licensing.PublicKeyRing
import com.smartmanager.installments.data.repo.InMemorySecretStore
import com.smartmanager.installments.data.repo.LedgerError
import com.smartmanager.installments.data.repo.LedgerException
import com.smartmanager.installments.data.repo.LedgerRepository
import com.smartmanager.installments.data.repo.LicenseManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicenseManagerTests {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val ring = PublicKeyRing.fromPems(listOf("-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.public.encoded) + "\n-----END PUBLIC KEY-----"))
    private val kid = PublicKeyRing.keyIdOf(kp.public.encoded)
    private val e64 = Base64.getUrlEncoder().withoutPadding()

    private fun sign(device: String, type: String = "lifetime", start: Long, expires: Long? = null, issued: Long = start, key: PrivateKey = kp.private): String {
        val exp = if (expires != null) "\"expiresAt\":$expires," else ""
        val payload = "{\"customerName\":\"Shop\",\"customerRef\":null,\"deviceId\":\"$device\",$exp\"features\":[\"core\"],\"issuedAt\":$issued,\"licenseId\":\"L-1\",\"product\":\"smartmanager-installments\",\"revision\":1,\"startAt\":$start,\"type\":\"$type\",\"tz\":\"UTC\",\"v\":1}".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(payload); sign() }
        return "{\"alg\":\"ES256\",\"fmt\":\"SMI-LIC\",\"kid\":\"$kid\",\"payload\":\"${e64.encodeToString(payload)}\",\"sig\":\"${e64.encodeToString(sig)}\",\"v\":1}"
    }

    private fun manager(env: Env, secrets: InMemorySecretStore = InMemorySecretStore()) = LicenseManager(env.db, env.clock, secrets, ring, "1.0.0")

    @Test fun activationFlowLifetime() = runBlocking {
        val env = Env(); val secrets = InMemorySecretStore(); val m = manager(env, secrets)
        assertEquals(LicenseStatus.NOT_ACTIVATED, m.refresh().status)
        assertFalse(m.state.value.allowsWrites)
        val now = env.clock.now / 1000
        assertEquals(LicenseStatus.VALID, m.importLicense(sign(m.deviceId, start = now - 86400)).status)
        assertTrue(m.state.value.allowsWrites)
        // "restart the app": a new manager with the same secret store re-reads the stored license from the database
        assertEquals(LicenseStatus.VALID, manager(env, secrets).refresh().status)
        env.db.close()
    }

    @Test fun licenseForAnotherDeviceIsRejectedAndNotStored() = runBlocking {
        val env = Env(); val m = manager(env)
        val now = env.clock.now / 1000
        val r = m.importLicense(sign("AAAA-BBBB-CCCC-DDDD-EEEE", start = now - 100))
        assertEquals(LicenseStatus.WRONG_DEVICE, r.status)
        assertEquals(null, env.db.license().current())
        assertEquals(LicenseStatus.NOT_ACTIVATED, m.refresh().status)
        env.db.close()
    }

    @Test fun forgedAndGarbageLicensesAreRejected() = runBlocking {
        val env = Env(); val m = manager(env)
        val now = env.clock.now / 1000
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertEquals(LicenseStatus.INVALID_SIGNATURE, m.importLicense(sign(m.deviceId, start = now - 100, key = other.private)).status)
        assertEquals(LicenseStatus.MALFORMED, m.importLicense("hello").status)
        assertEquals(null, env.db.license().current())
        env.db.close()
    }

    @Test fun expiredDurationLicenseGoesReadOnlyButKeepsAllData() = runBlocking {
        val env = Env(); val m = manager(env)
        env.guardedLedger(m).let { ledger ->
            val now = env.clock.now / 1000
            m.importLicense(sign(m.deviceId, "duration", start = now - 10 * 86400, expires = now + 5 * 86400))
            assertTrue(m.state.value.allowsWrites)
            assertTrue(m.state.value.daysRemaining!! in 4..5 && m.state.value.expiringSoon)
            val cust = env.customers.let { com.smartmanager.installments.data.repo.CustomerRepository(env.db, env.clock, m.writeGuard).save(com.smartmanager.installments.data.repo.CustomerInput(null, "Kept", "20123456", null, "", null, "")) }
            // license runs out
            env.clock.advanceDays(6)
            assertEquals(LicenseStatus.EXPIRED, m.refresh().status)
            assertFalse(m.state.value.allowsWrites)
            val err = expectThrows<LedgerException> { runBlocking { com.smartmanager.installments.data.repo.CustomerRepository(env.db, env.clock, m.writeGuard).save(com.smartmanager.installments.data.repo.CustomerInput(null, "New", "20999999", null, "", null, "")) } }
            assertEquals(LedgerError.LICENSE_READ_ONLY, err.error)
            // nothing destroyed: records are still readable and exportable
            assertEquals("Kept", env.db.customers().get(cust)!!.fullName)
            val backup = com.smartmanager.installments.data.repo.BackupService(env.context, env.db, env.clock, "1.0.0").createBackup(null)
            assertEquals(1, com.smartmanager.installments.core.backup.BackupCodec.inspect(backup).tableCounts["customers"])
            // renewal restores write access
            val now2 = env.clock.now / 1000
            m.importLicense(sign(m.deviceId, "duration", start = now2 - 86400, expires = now2 + 365 * 86400))
            assertTrue(m.state.value.allowsWrites)
        }
        env.db.close()
    }

    @Test fun clockRollbackBlocksDurationLicenses() = runBlocking {
        val env = Env(); val m = manager(env)
        val now = env.clock.now / 1000
        m.importLicense(sign(m.deviceId, "duration", start = now - 86400, expires = now + 30 * 86400))
        env.clock.advanceDays(20); m.refresh()                 // the app sees "20 days later"
        env.clock.now -= 15 * 86_400_000L                      // user winds the clock back 15 days
        assertEquals(LicenseStatus.CLOCK_ROLLBACK, m.refresh().status)
        assertFalse(m.state.value.allowsWrites)
        env.clock.advanceDays(15); assertEquals(LicenseStatus.VALID, m.refresh().status) // correcting the clock restores it
        env.db.close()
    }

    @Test fun lifetimeLicenseIgnoresClockGamesAndTimePassing() = runBlocking {
        val env = Env(); val m = manager(env)
        val now = env.clock.now / 1000
        m.importLicense(sign(m.deviceId, start = now - 86400))
        env.clock.advanceDays(365 * 30); m.refresh()
        env.clock.now -= 365L * 20 * 86_400_000L
        assertEquals(LicenseStatus.VALID, m.refresh().status)
        env.db.close()
    }

    @Test fun deviceReplacementProducesANewIdentity() {
        // Reinstall / reset = new random secret = new device id => old license no longer matches, replacement is needed.
        val env = Env()
        val a = manager(env, InMemorySecretStore()).deviceId
        val b = manager(env, InMemorySecretStore()).deviceId
        assertNotEquals(a, b)
        // the same secret store yields a stable id
        val store = InMemorySecretStore(); assertEquals(manager(env, store).deviceId, manager(env, store).deviceId)
        assertTrue(Regex("^([0-9A-F]{4}-){4}[0-9A-F]{4}$").matches(a))
        env.db.close()
    }

    @Test fun activationRequestContainsTheDeviceId() {
        val env = Env(); val m = manager(env)
        assertEquals(m.deviceId, com.smartmanager.installments.core.licensing.ActivationRequest.decodeDeviceId(m.activationRequest()))
        env.db.close()
    }

    private fun Env.guardedLedger(m: LicenseManager) = LedgerRepository(db, clock, m.writeGuard)
}
