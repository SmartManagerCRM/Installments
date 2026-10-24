package com.smartmanager.installments.core.licensing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

const val PRODUCT_ID = "smartmanager-installments"
const val LICENSE_FORMAT = "SMI-LIC"
const val LICENSE_SCHEMA_VERSION = 1
const val ACTIVATION_PREFIX = "SMI-ACT-1:"
const val LICENSE_TEXT_PREFIX = "SMI-LIC-1."

enum class LicenseType { LIFETIME, DURATION }

/**
 * The signed payload. Every field here is covered by the signature (the signature is computed over the exact
 * payload bytes, which are canonical JSON: sorted keys, no whitespace, UTF-8).
 * All instants are UTC epoch seconds; the generator documents which time zone it used for start/expiry boundaries.
 */
data class LicensePayload(
    val schemaVersion: Int,
    val product: String,
    val licenseId: String,
    val revision: Int,
    val customerName: String,
    val customerRef: String?,
    val type: LicenseType,
    val issuedAt: Long,
    val startAt: Long,
    val expiresAt: Long?,
    val deviceId: String,
    val features: List<String>,
    val timeZone: String?,
)

enum class LicenseStatus {
    NOT_ACTIVATED, VALID, EXPIRED, NOT_YET_VALID, WRONG_DEVICE, INVALID_SIGNATURE, MALFORMED,
    UNSUPPORTED_VERSION, WRONG_PRODUCT, UNKNOWN_KEY, CLOCK_ROLLBACK
}

data class LicenseCheck(
    val status: LicenseStatus,
    val payload: LicensePayload? = null,
    /** Whole days left for duration licenses (null for lifetime). Negative when expired. */
    val daysRemaining: Long? = null,
    val expiringSoon: Boolean = false,
) {
    /** Writes (new contracts, payments, ...) are allowed only for a valid license. Reading and exporting always works. */
    val allowsWrites: Boolean get() = status == LicenseStatus.VALID
}

class PublicKeyRing(private val keys: Map<String, PublicKey>) {
    fun find(keyId: String): PublicKey? = keys[keyId]

    companion object {
        fun keyIdOf(spkiDer: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(spkiDer).take(4).joinToString("") { "%02x".format(it) }

        /** @param spkiBase64 X.509 SubjectPublicKeyInfo DER, base64 (PEM header/footer and whitespace tolerated). */
        fun fromPems(pems: List<String>): PublicKeyRing {
            val kf = KeyFactory.getInstance("EC")
            val map = LinkedHashMap<String, PublicKey>()
            for (pem in pems) {
                val b64 = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").filter { !it.isWhitespace() }
                if (b64.isEmpty()) continue
                val der = Base64.getDecoder().decode(b64)
                map[keyIdOf(der)] = kf.generatePublic(X509EncodedKeySpec(der))
            }
            return PublicKeyRing(map)
        }
    }
}

object LicenseCodec {
    private val b64u = Base64.getUrlEncoder().withoutPadding()
    private val b64uDec = Base64.getUrlDecoder()

    fun b64uEncode(bytes: ByteArray): String = b64u.encodeToString(bytes)
    fun b64uDecode(s: String): ByteArray = b64uDec.decode(s.trim())

    /** Accepts the JSON license file, or the one-line text form `SMI-LIC-1.<base64url(json)>`. */
    fun normalizeInput(text: String): String {
        val t = text.trim().removePrefix("﻿").trim()
        return if (t.startsWith(LICENSE_TEXT_PREFIX)) String(b64uDecode(t.removePrefix(LICENSE_TEXT_PREFIX)), Charsets.UTF_8) else t
    }

    fun toTextForm(licenseFileJson: String): String = LICENSE_TEXT_PREFIX + b64uEncode(licenseFileJson.toByteArray(Charsets.UTF_8))
}

object LicenseVerifier {
    /** Clock rollback tolerance: small adjustments (time zone / NTP drift) must not lock anybody out. */
    const val CLOCK_TOLERANCE_SECONDS = 24L * 3600
    const val WARN_DAYS = 30L

    private class ParsedFile(val kid: String, val payloadBytes: ByteArray, val sig: ByteArray)

    /**
     * Fully offline verification.
     * @param nowEpoch      current time (UTC epoch seconds)
     * @param lastSeenEpoch the greatest device time ever observed by the app (persisted), or null on first use
     */
    fun verify(
        licenseText: String,
        keyRing: PublicKeyRing,
        deviceId: String,
        nowEpoch: Long,
        lastSeenEpoch: Long?,
    ): LicenseCheck {
        val parsed = try {
            parseFile(LicenseCodec.normalizeInput(licenseText))
        } catch (e: Exception) {
            return LicenseCheck(LicenseStatus.MALFORMED)
        }
        val key = keyRing.find(parsed.kid) ?: return LicenseCheck(LicenseStatus.UNKNOWN_KEY)
        val sigOk = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(parsed.payloadBytes)
                verify(parsed.sig)
            }
        } catch (e: Exception) {
            false
        }
        if (!sigOk) return LicenseCheck(LicenseStatus.INVALID_SIGNATURE)

        val p = try {
            parsePayload(parsed.payloadBytes)
        } catch (e: Exception) {
            return LicenseCheck(LicenseStatus.MALFORMED)
        }
        if (p.schemaVersion > LICENSE_SCHEMA_VERSION || p.schemaVersion < 1) return LicenseCheck(LicenseStatus.UNSUPPORTED_VERSION, p)
        if (p.product != PRODUCT_ID) return LicenseCheck(LicenseStatus.WRONG_PRODUCT, p)
        if (!normalizeDeviceId(p.deviceId).equals(normalizeDeviceId(deviceId), ignoreCase = true)) return LicenseCheck(LicenseStatus.WRONG_DEVICE, p)
        if (p.type == LicenseType.DURATION && p.expiresAt == null) return LicenseCheck(LicenseStatus.MALFORMED, p)
        if (p.type == LicenseType.LIFETIME && p.expiresAt != null) return LicenseCheck(LicenseStatus.MALFORMED, p)

        if (p.type == LicenseType.LIFETIME) {
            // A lifetime license never expires. A future start date still applies (issued ahead of time).
            return if (nowEpoch < p.startAt - CLOCK_TOLERANCE_SECONDS) LicenseCheck(LicenseStatus.NOT_YET_VALID, p)
            else LicenseCheck(LicenseStatus.VALID, p)
        }

        val floor = maxOf(lastSeenEpoch ?: Long.MIN_VALUE, p.issuedAt)
        if (nowEpoch + CLOCK_TOLERANCE_SECONDS < floor) return LicenseCheck(LicenseStatus.CLOCK_ROLLBACK, p)
        if (nowEpoch < p.startAt) return LicenseCheck(LicenseStatus.NOT_YET_VALID, p)
        val expires = p.expiresAt!!
        val secondsLeft = expires - nowEpoch
        val days = Math.floorDiv(secondsLeft, 86_400L)
        return if (secondsLeft < 0) LicenseCheck(LicenseStatus.EXPIRED, p, days)
        else LicenseCheck(LicenseStatus.VALID, p, days, expiringSoon = days < WARN_DAYS)
    }

    fun normalizeDeviceId(id: String): String = id.uppercase().filter { it.isLetterOrDigit() }

    private fun parseFile(json: String): ParsedFile {
        val o = Json.parseToJsonElement(json).jsonObject
        require(o["fmt"]?.jsonPrimitive?.contentOrNull == LICENSE_FORMAT)
        require(o["alg"]?.jsonPrimitive?.contentOrNull == "ES256")
        return ParsedFile(
            kid = o.getValue("kid").jsonPrimitive.content,
            payloadBytes = LicenseCodec.b64uDecode(o.getValue("payload").jsonPrimitive.content),
            sig = LicenseCodec.b64uDecode(o.getValue("sig").jsonPrimitive.content),
        )
    }

    fun parsePayload(bytes: ByteArray): LicensePayload {
        val o = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        fun str(k: String) = o.getValue(k).jsonPrimitive.content
        fun optStr(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        fun lng(k: String) = o.getValue(k).jsonPrimitive.longOrNull ?: error("bad $k")
        val type = when (str("type")) {
            "lifetime" -> LicenseType.LIFETIME
            "duration" -> LicenseType.DURATION
            else -> error("bad type")
        }
        return LicensePayload(
            schemaVersion = lng("v").toInt(),
            product = str("product"),
            licenseId = str("licenseId"),
            revision = (o["revision"]?.jsonPrimitive?.longOrNull ?: 1L).toInt(),
            customerName = str("customerName"),
            customerRef = optStr("customerRef"),
            type = type,
            issuedAt = lng("issuedAt"),
            startAt = lng("startAt"),
            expiresAt = (o["expiresAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull,
            deviceId = str("deviceId"),
            features = (o["features"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            timeZone = optStr("tz"),
        )
    }
}

/** Activation request shown to the retailer; the owner pastes/imports it into the license generator. */
object ActivationRequest {
    fun encode(deviceId: String, appVersion: String, createdAtEpoch: Long): String {
        val body = JsonObject(
            mapOf(
                "v" to JsonPrimitive(1),
                "product" to JsonPrimitive(PRODUCT_ID),
                "deviceId" to JsonPrimitive(deviceId),
                "app" to JsonPrimitive(appVersion),
                "at" to JsonPrimitive(createdAtEpoch),
            )
        )
        val raw = Json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8)
        val check = MessageDigest.getInstance("SHA-256").digest(raw).take(3).joinToString("") { "%02x".format(it) }
        return ACTIVATION_PREFIX + LicenseCodec.b64uEncode(raw) + "." + check
    }

    /** Returns the device id inside a request, validating the checksum; null if invalid. */
    fun decodeDeviceId(request: String): String? = try {
        val t = request.trim().removePrefix(ACTIVATION_PREFIX)
        val (b64, check) = t.split(".")
        val raw = LicenseCodec.b64uDecode(b64)
        val expected = MessageDigest.getInstance("SHA-256").digest(raw).take(3).joinToString("") { "%02x".format(it) }
        if (expected != check) null
        else Json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject.getValue("deviceId").jsonPrimitive.content
    } catch (e: Exception) {
        null
    }
}

object DeviceIdentity {
    /**
     * Derives the human-readable device-binding identifier from the app's random installation secret.
     * No hardware identifiers (IMEI, serial, MAC, ...) are used.
     * Format: XXXX-XXXX-XXXX-XXXX-XXXX (80 bits).
     */
    fun deviceIdFromSecret(installationSecret: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest("SMI-DEVICE-V1".toByteArray() + installationSecret)
        val hex = d.take(10).joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString("-")
    }
}
