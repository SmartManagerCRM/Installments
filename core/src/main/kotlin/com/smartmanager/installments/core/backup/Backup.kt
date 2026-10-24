package com.smartmanager.installments.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

const val BACKUP_FORMAT = "smi-backup"
const val BACKUP_FORMAT_VERSION = 1

enum class BackupError { NOT_A_BACKUP, CORRUPT, INTEGRITY_FAILED, UNSUPPORTED_VERSION, PASSPHRASE_REQUIRED, WRONG_PASSPHRASE }

class BackupException(val error: BackupError, message: String = error.name) : Exception(message)

data class BackupHeader(
    val formatVersion: Int,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val dbSchemaVersion: Int,
    val encrypted: Boolean,
    val tableCounts: Map<String, Int>,
)

data class DecodedBackup(val header: BackupHeader, val tables: Map<String, JsonElement>)

/**
 * Versioned, integrity-checked backup container.
 *
 *   file    = gzip( headerJson + '\n' + payloadBytes )
 *   header  = { format, formatVersion, createdAt, appVersion, dbSchemaVersion, encrypted, tableCounts,
 *               payloadSha256, kdf{salt,iterations}, iv }
 *   payload = {"tables":{...}} as UTF-8 JSON, or (encrypted) AES-256-GCM ciphertext of it.
 *
 * Encryption key = PBKDF2-HMAC-SHA256(passphrase, salt, 210000 iterations). The header is bound as GCM
 * additional authenticated data, so tampering with the header of an encrypted backup is detected.
 * A lost passphrase cannot be recovered: the backup is then unreadable by design.
 */
object BackupCodec {
    private const val PBKDF2_ITERATIONS = 210_000
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(
        tables: Map<String, JsonElement>,
        appVersion: String,
        dbSchemaVersion: Int,
        createdAtEpochMs: Long,
        passphrase: CharArray? = null,
    ): ByteArray {
        val plain = json.encodeToString(JsonElement.serializer(), JsonObject(mapOf("tables" to JsonObject(tables)))).toByteArray(Charsets.UTF_8)
        val counts = tables.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonArray)?.size ?: 0 }
        val baseHeader = mutableMapOf<String, JsonElement>(
            "format" to JsonPrimitive(BACKUP_FORMAT),
            "formatVersion" to JsonPrimitive(BACKUP_FORMAT_VERSION),
            "createdAt" to JsonPrimitive(createdAtEpochMs),
            "appVersion" to JsonPrimitive(appVersion),
            "dbSchemaVersion" to JsonPrimitive(dbSchemaVersion),
            "encrypted" to JsonPrimitive(passphrase != null),
            "tableCounts" to JsonObject(counts.mapValues { JsonPrimitive(it.value) }),
        )
        val payload: ByteArray
        if (passphrase != null) {
            val rnd = SecureRandom()
            val salt = ByteArray(16).also(rnd::nextBytes)
            val iv = ByteArray(12).also(rnd::nextBytes)
            baseHeader["kdfSalt"] = JsonPrimitive(Base64.getEncoder().encodeToString(salt))
            baseHeader["kdfIterations"] = JsonPrimitive(PBKDF2_ITERATIONS)
            baseHeader["iv"] = JsonPrimitive(Base64.getEncoder().encodeToString(iv))
            val aad = aadOf(baseHeader)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, PBKDF2_ITERATIONS), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            payload = cipher.doFinal(plain)
        } else {
            payload = plain
        }
        baseHeader["payloadSha256"] = JsonPrimitive(sha256Hex(payload))
        val headerLine = json.encodeToString(JsonElement.serializer(), JsonObject(baseHeader))
        check('\n' !in headerLine)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(headerLine.toByteArray(Charsets.UTF_8)); it.write('\n'.code); it.write(payload) }
        return out.toByteArray()
    }

    private class Split(val headerObj: JsonObject, val payload: ByteArray)

    private fun split(file: ByteArray): Split {
        val raw = try {
            GZIPInputStream(ByteArrayInputStream(file)).use { it.readBytes() }
        } catch (e: Exception) {
            throw BackupException(BackupError.NOT_A_BACKUP)
        }
        val nl = raw.indexOf('\n'.code.toByte())
        if (nl <= 0) throw BackupException(BackupError.NOT_A_BACKUP)
        val header = try {
            json.parseToJsonElement(String(raw, 0, nl, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw BackupException(BackupError.NOT_A_BACKUP)
        }
        if (header["format"]?.jsonPrimitive?.contentOrNull != BACKUP_FORMAT) throw BackupException(BackupError.NOT_A_BACKUP)
        return Split(header, raw.copyOfRange(nl + 1, raw.size))
    }

    private fun headerOf(o: JsonObject): BackupHeader {
        val version = o["formatVersion"]?.jsonPrimitive?.intOrNull ?: throw BackupException(BackupError.CORRUPT)
        return BackupHeader(
            formatVersion = version,
            createdAtEpochMs = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            appVersion = o["appVersion"]?.jsonPrimitive?.contentOrNull ?: "?",
            dbSchemaVersion = o["dbSchemaVersion"]?.jsonPrimitive?.intOrNull ?: 0,
            encrypted = o["encrypted"]?.jsonPrimitive?.booleanOrNull ?: false,
            tableCounts = (o["tableCounts"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 } ?: emptyMap(),
        )
    }

    /** Cheap validation that needs no passphrase: format, version and payload checksum. */
    fun inspect(file: ByteArray, maxSupportedVersion: Int = BACKUP_FORMAT_VERSION): BackupHeader {
        val s = split(file)
        val h = headerOf(s.headerObj)
        if (h.formatVersion > maxSupportedVersion || h.formatVersion < 1) throw BackupException(BackupError.UNSUPPORTED_VERSION)
        val expected = s.headerObj["payloadSha256"]?.jsonPrimitive?.contentOrNull ?: throw BackupException(BackupError.CORRUPT)
        if (!sha256Hex(s.payload).equals(expected, ignoreCase = true)) throw BackupException(BackupError.INTEGRITY_FAILED)
        return h
    }

    fun decode(file: ByteArray, passphrase: CharArray? = null, maxSupportedVersion: Int = BACKUP_FORMAT_VERSION): DecodedBackup {
        val header = inspect(file, maxSupportedVersion)
        val s = split(file)
        val plain: ByteArray = if (header.encrypted) {
            if (passphrase == null) throw BackupException(BackupError.PASSPHRASE_REQUIRED)
            try {
                val salt = Base64.getDecoder().decode(s.headerObj.getValue("kdfSalt").jsonPrimitive.content)
                val iv = Base64.getDecoder().decode(s.headerObj.getValue("iv").jsonPrimitive.content)
                val iterations = s.headerObj.getValue("kdfIterations").jsonPrimitive.int()
                val withoutHash = s.headerObj.filterKeys { it != "payloadSha256" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
                cipher.updateAAD(aadOf(withoutHash))
                cipher.doFinal(s.payload)
            } catch (e: java.security.GeneralSecurityException) {
                throw BackupException(BackupError.WRONG_PASSPHRASE)
            }
        } else s.payload
        val tables = try {
            json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject.getValue("tables").jsonObject.toMap()
        } catch (e: Exception) {
            throw BackupException(BackupError.CORRUPT)
        }
        return DecodedBackup(header, tables)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = intOrNull ?: throw BackupException(BackupError.CORRUPT)

    private fun aadOf(header: Map<String, JsonElement>): ByteArray =
        json.encodeToString(JsonElement.serializer(), JsonObject(header.toSortedMap())).toByteArray(Charsets.UTF_8)

    private fun deriveKey(pass: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(pass, salt, iterations, 256)).encoded, "AES")
    }

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
