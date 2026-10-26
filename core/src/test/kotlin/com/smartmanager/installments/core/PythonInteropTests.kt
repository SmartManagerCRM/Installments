package com.smartmanager.installments.core

import com.smartmanager.installments.core.licensing.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

/** Verifies licenses signed by the real Python generator (committed TEST key + samples) with the Kotlin verifier. */
class PythonInteropTests {
    private val gen = File("../license-generator")
    private val ring = PublicKeyRing.fromPems(listOf(File(gen, "test-keys/TEST_ONLY_public_key.pem").readText()))
    private val device = "0000-1111-2222-3333-4444"
    private val now = LocalDate.of(2026, 6, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    private fun check(name: String, dev: String = device, at: Long = now, lastSeen: Long? = null) =
        LicenseVerifier.verify(File(gen, "samples/$name").readText(), ring, dev, at, lastSeen)

    @Test fun lifetime() {
        val r = check("sample-lifetime.smilic")
        assertEquals(LicenseStatus.VALID, r.status)
        assertEquals("Boutique Test / متجر تجريبي", r.payload!!.customerName)
        assertEquals("Africa/Tunis", r.payload!!.timeZone)
        assertEquals(LicenseType.LIFETIME, r.payload!!.type)
    }

    @Test fun durationValid() {
        val r = check("sample-duration-valid-until-2099.smilic")
        assertEquals(LicenseStatus.VALID, r.status); assertTrue(r.daysRemaining!! > 365 * 70)
    }

    @Test fun durationExpiredAndFuture() {
        assertEquals(LicenseStatus.EXPIRED, check("sample-duration-expired-2021.smilic").status)
        assertEquals(LicenseStatus.NOT_YET_VALID, check("sample-duration-not-yet-valid-2098.smilic").status)
    }

    @Test fun otherDevice() {
        assertEquals(LicenseStatus.WRONG_DEVICE, check("sample-lifetime-other-device.smilic").status)
        assertEquals(LicenseStatus.WRONG_DEVICE, check("sample-lifetime.smilic", dev = "FFFF-FFFF-FFFF-FFFF-FFFF").status)
    }

    @Test fun activationRequestFromTheAppIsAcceptedByTheGeneratorFormat() {
        val req = File(gen, "samples/test-device-activation-request.txt").readText().trim()
        assertEquals(device, ActivationRequest.decodeDeviceId(req)) // Python-made request decoded by Kotlin
    }

    @Test fun editedSampleIsRejected() {
        val text = File(gen, "samples/sample-lifetime.smilic").readText().replace("Boutique", "Boutiqux")
        // the customer name lives inside the base64 payload, so flip a payload char instead
        val tampered = File(gen, "samples/sample-lifetime.smilic").readText().let {
            val i = it.indexOf("\"payload\":\"") + 40
            it.substring(0, i) + (if (it[i] == 'A') 'B' else 'A') + it.substring(i + 1)
        }
        assertNotEquals(LicenseStatus.VALID, LicenseVerifier.verify(tampered, ring, device, now, null).status)
        assertNotNull(text)
    }
}

/** Licenses issued by the JAVASCRIPT license manager (license-manager/samples) must verify in the Kotlin verifier too. */
class JavaScriptInteropTests {
    private val dir = File("../license-manager/samples")
    private val ring = PublicKeyRing.fromPems(listOf(File("../license-generator/test-keys/TEST_ONLY_public_key.pem").readText()))
    private val device = "0000-1111-2222-3333-4444"
    private val now = LocalDate.of(2026, 6, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    private fun check(name: String, dev: String = device) = LicenseVerifier.verify(File(dir, name).readText(), ring, dev, now, null)

    @Test fun lifetimeAndDurationFromJavaScript() {
        assertEquals(LicenseStatus.VALID, check("js-lifetime.smilic").status)
        val d = check("js-duration-valid-until-2099.smilic")
        assertEquals(LicenseStatus.VALID, d.status); assertTrue(d.daysRemaining!! > 365 * 70)
        assertEquals(LicenseStatus.EXPIRED, check("js-duration-expired-2021.smilic").status)
        assertEquals(LicenseStatus.WRONG_DEVICE, check("js-lifetime.smilic", "FFFF-FFFF-FFFF-FFFF-FFFF").status)
        assertEquals("Boutique Test JS", check("js-lifetime.smilic").payload!!.customerName)
    }
}
