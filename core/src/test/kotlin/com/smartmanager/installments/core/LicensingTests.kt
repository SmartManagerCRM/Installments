package com.smartmanager.installments.core

import com.smartmanager.installments.core.licensing.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.*

/** Signs licenses exactly like the Python generator does (ES256, DER signature over canonical payload bytes). */
object TestLicenseFactory {
    val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val otherKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val ring = PublicKeyRing.fromPems(listOf(pem(keyPair.public.encoded)))
    val kid = PublicKeyRing.keyIdOf(keyPair.public.encoded)

    fun pem(der: ByteArray) = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END PUBLIC KEY-----"

    const val DEVICE = "AAAA-BBBB-CCCC-DDDD-EEEE"
    const val NOW = 1_800_000_000L // fixed clock for deterministic tests
    const val DAY = 86_400L

    fun payloadJson(
        type: String = "lifetime", device: String = DEVICE, start: Long = NOW - DAY, expires: Long? = null, issued: Long = NOW - DAY,
        product: String = PRODUCT_ID, version: Int = 1, name: String = "متجر النور / Boutique Élégance",
    ): String {
        val exp = if (expires != null) "\"expiresAt\":$expires," else ""
        // keys in sorted order, no whitespace = canonical form
        return "{\"customerName\":\"$name\",\"customerRef\":null,\"deviceId\":\"$device\",$exp\"features\":[\"core\"],\"issuedAt\":$issued," +
            "\"licenseId\":\"SMI-TEST-0001\",\"product\":\"$product\",\"revision\":1,\"startAt\":$start,\"type\":\"$type\",\"tz\":\"Africa/Tunis\",\"v\":$version}"
    }

    fun sign(payload: String, signer: java.security.PrivateKey = keyPair.private, kidOverride: String = kid, b64Payload: String? = null): String {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(signer); update(bytes); sign() }
        val e = Base64.getUrlEncoder().withoutPadding()
        return "{\"alg\":\"ES256\",\"fmt\":\"SMI-LIC\",\"kid\":\"$kidOverride\",\"payload\":\"${b64Payload ?: e.encodeToString(bytes)}\",\"sig\":\"${e.encodeToString(sig)}\",\"v\":1}"
    }
}

class LicensingTests {
    private val F = TestLicenseFactory
    private fun check(file: String, device: String = F.DEVICE, now: Long = F.NOW, lastSeen: Long? = null) =
        LicenseVerifier.verify(file, F.ring, device, now, lastSeen)

    @Test fun validLifetimeLicense() {
        val r = check(F.sign(F.payloadJson()))
        assertEquals(LicenseStatus.VALID, r.status)
        assertEquals(LicenseType.LIFETIME, r.payload!!.type)
        assertNull(r.daysRemaining)
        assertEquals("متجر النور / Boutique Élégance", r.payload!!.customerName)
        // Lifetime never expires with the passage of time.
        assertEquals(LicenseStatus.VALID, check(F.sign(F.payloadJson()), now = F.NOW + 50 * 365 * F.DAY).status)
    }

    @Test fun validDurationLicenseShowsRemainingDays() {
        val file = F.sign(F.payloadJson("duration", expires = F.NOW + 100 * F.DAY))
        val r = check(file)
        assertEquals(LicenseStatus.VALID, r.status)
        assertEquals(100, r.daysRemaining)
        assertFalse(r.expiringSoon)
    }

    @Test fun expiringSoonWarning() {
        val r = check(F.sign(F.payloadJson("duration", expires = F.NOW + 10 * F.DAY + 3600)))
        assertEquals(LicenseStatus.VALID, r.status); assertTrue(r.expiringSoon); assertEquals(10, r.daysRemaining)
    }

    @Test fun expiredLicenseBlocksWritesOnly() {
        val r = check(F.sign(F.payloadJson("duration", start = F.NOW - 400 * F.DAY, issued = F.NOW - 400 * F.DAY, expires = F.NOW - F.DAY)))
        assertEquals(LicenseStatus.EXPIRED, r.status)
        assertFalse(r.allowsWrites)
        assertTrue(r.daysRemaining!! < 0)
    }

    @Test fun expiryBoundary() {
        val file = F.sign(F.payloadJson("duration", expires = F.NOW))
        assertEquals(LicenseStatus.VALID, check(file, now = F.NOW).status)       // expires AT this second
        assertEquals(LicenseStatus.EXPIRED, check(file, now = F.NOW + 1).status)
    }

    @Test fun notYetValid() {
        assertEquals(LicenseStatus.NOT_YET_VALID, check(F.sign(F.payloadJson("duration", start = F.NOW + 5 * F.DAY, expires = F.NOW + 50 * F.DAY))).status)
        assertEquals(LicenseStatus.NOT_YET_VALID, check(F.sign(F.payloadJson("lifetime", start = F.NOW + 5 * F.DAY))).status)
    }

    @Test fun invalidSignature() {
        val forged = F.sign(F.payloadJson(), signer = F.otherKeyPair.private)
        assertEquals(LicenseStatus.INVALID_SIGNATURE, check(forged).status)
    }

    @Test fun tamperedPayloadIsRejected() {
        val original = F.payloadJson("duration", expires = F.NOW + 10 * F.DAY)
        val file = F.sign(original)
        // Attacker extends the expiry but keeps the old signature.
        val tampered = original.replace("${F.NOW + 10 * F.DAY}", "${F.NOW + 9999 * F.DAY}")
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.toByteArray())
        val oldPayloadB64 = Regex("\"payload\":\"([^\"]+)\"").find(file)!!.groupValues[1]
        assertEquals(LicenseStatus.INVALID_SIGNATURE, check(file.replace(oldPayloadB64, b64)).status)
        // Switching lifetime/duration type or device without re-signing fails too.
        val asLifetime = F.sign(original, b64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(F.payloadJson("lifetime").toByteArray()))
        assertEquals(LicenseStatus.INVALID_SIGNATURE, check(asLifetime).status)
    }

    @Test fun everyBitFlipInSignatureOrPayloadFailsVerification() {
        val file = F.sign(F.payloadJson())
        val payloadB64 = Regex("\"payload\":\"([^\"]+)\"").find(file)!!.groupValues[1]
        for (i in payloadB64.indices step 7) {
            val c = payloadB64[i]
            val repl = if (c == 'A') 'B' else 'A'
            val mutated = file.replace(payloadB64, payloadB64.substring(0, i) + repl + payloadB64.substring(i + 1))
            assertNotEquals(LicenseStatus.VALID, check(mutated).status, "mutation at $i")
        }
    }

    @Test fun licenseForAnotherDeviceIsRejected() {
        val r = check(F.sign(F.payloadJson(device = "1111-2222-3333-4444-5555")))
        assertEquals(LicenseStatus.WRONG_DEVICE, r.status)
        assertFalse(r.allowsWrites)
        // device ids compare ignoring case and dashes
        assertEquals(LicenseStatus.VALID, check(F.sign(F.payloadJson()), device = "aaaabbbbccccddddeeee").status)
    }

    @Test fun malformedFiles() {
        for (bad in listOf("", "not json", "{}", "{\"fmt\":\"SMI-LIC\"}", "[1,2]", "{\"fmt\":\"X\",\"alg\":\"ES256\",\"kid\":\"a\",\"payload\":\"!!\",\"sig\":\"!!\"}")) {
            val s = check(bad).status
            assertTrue(s == LicenseStatus.MALFORMED, "'$bad' -> $s")
        }
    }

    @Test fun unknownKeyAndWrongProductAndVersion() {
        assertEquals(LicenseStatus.UNKNOWN_KEY, check(F.sign(F.payloadJson(), kidOverride = "deadbeef")).status)
        assertEquals(LicenseStatus.WRONG_PRODUCT, check(F.sign(F.payloadJson(product = "other-product"))).status)
        assertEquals(LicenseStatus.UNSUPPORTED_VERSION, check(F.sign(F.payloadJson(version = 99))).status)
    }

    @Test fun inconsistentTypeAndExpiryIsMalformed() {
        assertEquals(LicenseStatus.MALFORMED, check(F.sign(F.payloadJson("duration", expires = null))).status)
        assertEquals(LicenseStatus.MALFORMED, check(F.sign(F.payloadJson("lifetime", expires = F.NOW + F.DAY))).status)
    }

    @Test fun clockRollbackIsDetectedForDurationLicensesOnly() {
        val dur = F.sign(F.payloadJson("duration", expires = F.NOW + 10 * F.DAY))
        // Device previously saw a time 40 days later; now the clock was wound back.
        assertEquals(LicenseStatus.CLOCK_ROLLBACK, check(dur, lastSeen = F.NOW + 40 * F.DAY).status)
        // Small adjustments (< 1 day) are tolerated.
        assertEquals(LicenseStatus.VALID, check(dur, lastSeen = F.NOW + 3600).status)
        // Lifetime licenses ignore the clock history.
        assertEquals(LicenseStatus.VALID, check(F.sign(F.payloadJson()), lastSeen = F.NOW + 400 * F.DAY).status)
        // A clock before the license was even issued is a rollback as well.
        assertEquals(LicenseStatus.CLOCK_ROLLBACK, check(dur, now = F.NOW - 30 * F.DAY).status)
    }

    @Test fun oneLineTextFormRoundTrips() {
        val file = F.sign(F.payloadJson())
        val text = LicenseCodec.toTextForm(file)
        assertTrue(text.startsWith(LICENSE_TEXT_PREFIX))
        assertEquals(LicenseStatus.VALID, check(text).status)
        assertEquals(LicenseStatus.VALID, check("﻿  $file \n").status)
    }

    @Test fun deviceReplacementWorkflow() {
        // 1. old device licensed
        val oldDevice = "1111-1111-1111-1111-1111"; val newDevice = "2222-2222-2222-2222-2222"
        val licOld = F.sign(F.payloadJson(device = oldDevice))
        assertEquals(LicenseStatus.VALID, check(licOld, device = oldDevice).status)
        // 2. the license cannot silently move to the new device
        assertEquals(LicenseStatus.WRONG_DEVICE, check(licOld, device = newDevice).status)
        // 3. the owner issues a replacement bound to the new request
        val request = ActivationRequest.encode(newDevice, "1.0.0", F.NOW)
        assertEquals(newDevice, ActivationRequest.decodeDeviceId(request))
        val licNew = F.sign(F.payloadJson(device = ActivationRequest.decodeDeviceId(request)!!))
        assertEquals(LicenseStatus.VALID, check(licNew, device = newDevice).status)
    }

    @Test fun activationRequestChecksum() {
        val req = ActivationRequest.encode(F.DEVICE, "1.0.0", F.NOW)
        assertEquals(F.DEVICE, ActivationRequest.decodeDeviceId(req))
        assertNull(ActivationRequest.decodeDeviceId(req.dropLast(1) + (if (req.last() == 'a') 'b' else 'a')))
        assertNull(ActivationRequest.decodeDeviceId("garbage"))
    }

    @Test fun deviceIdIsStableAndFormatted() {
        val secret = ByteArray(16) { it.toByte() }
        val id = DeviceIdentity.deviceIdFromSecret(secret)
        assertEquals(id, DeviceIdentity.deviceIdFromSecret(secret))
        assertTrue(Regex("^([0-9A-F]{4}-){4}[0-9A-F]{4}$").matches(id), id)
        assertNotEquals(id, DeviceIdentity.deviceIdFromSecret(ByteArray(16) { 9 }))
    }
}
