package com.smartmanager.installments.core

import com.smartmanager.installments.core.backup.*
import com.smartmanager.installments.core.reminders.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class BackupTests {
    private val tables: Map<String, JsonElement> = mapOf(
        "customers" to buildJsonArray { add(buildJsonObject { put("id", "c1"); put("fullName", "عميل تجريبي Élodie") }) },
        "payments" to buildJsonArray { add(buildJsonObject { put("id", "p1"); put("amountMinor", 200000) }) },
        "payment_allocations" to buildJsonArray { },
    )

    @Test fun roundTripPlain() {
        val bytes = BackupCodec.encode(tables, "1.0.0", 1, 123L)
        val h = BackupCodec.inspect(bytes)
        assertEquals(1, h.formatVersion); assertFalse(h.encrypted); assertEquals(1, h.tableCounts["customers"])
        val d = BackupCodec.decode(bytes)
        assertEquals(tables, d.tables)
        assertEquals("عميل تجريبي Élodie", d.tables.getValue("customers").jsonArray[0].jsonObject.getValue("fullName").jsonPrimitive.content)
    }

    @Test fun roundTripEncrypted() {
        val bytes = BackupCodec.encode(tables, "1.0.0", 1, 123L, "correct horse".toCharArray())
        assertTrue(BackupCodec.inspect(bytes).encrypted)
        assertFailsWith<BackupException> { BackupCodec.decode(bytes) }.also { assertEquals(BackupError.PASSPHRASE_REQUIRED, it.error) }
        assertFailsWith<BackupException> { BackupCodec.decode(bytes, "wrong".toCharArray()) }.also { assertEquals(BackupError.WRONG_PASSPHRASE, it.error) }
        assertEquals(tables, BackupCodec.decode(bytes, "correct horse".toCharArray()).tables)
        // The customer name must not appear in cleartext inside the (decompressed) encrypted file.
        val raw = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("Élodie"))
    }

    @Test fun rejectsGarbageAndTampering() {
        assertEquals(BackupError.NOT_A_BACKUP, assertFailsWith<BackupException> { BackupCodec.inspect("hello".toByteArray()) }.error)
        val bytes = BackupCodec.encode(tables, "1.0.0", 1, 123L)
        val raw = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes()
        val flipped = raw.copyOf(); flipped[flipped.size - 5] = (flipped[flipped.size - 5] + 1).toByte()
        val bos = ByteArrayOutputStream(); GZIPOutputStream(bos).use { it.write(flipped) }
        assertEquals(BackupError.INTEGRITY_FAILED, assertFailsWith<BackupException> { BackupCodec.inspect(bos.toByteArray()) }.error)
    }

    @Test fun rejectsIncompatibleVersion() {
        val bytes = BackupCodec.encode(tables, "1.0.0", 1, 123L)
        val e = assertFailsWith<BackupException> { BackupCodec.inspect(bytes, maxSupportedVersion = 0) }
        assertEquals(BackupError.UNSUPPORTED_VERSION, e.error)
        // A "future" file: patch formatVersion to 99.
        val raw = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":99")
        val bos = ByteArrayOutputStream(); GZIPOutputStream(bos).use { it.write(raw.toByteArray()) }
        assertEquals(BackupError.UNSUPPORTED_VERSION, assertFailsWith<BackupException> { BackupCodec.inspect(bos.toByteArray()) }.error)
    }

    @Test fun encryptedHeaderTamperingDetected() {
        val bytes = BackupCodec.encode(tables, "1.0.0", 1, 123L, "pw".toCharArray())
        val raw = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.ISO_8859_1)
        val patched = raw.replace("\"appVersion\":\"1.0.0\"", "\"appVersion\":\"9.9.9\"")
        val bos = ByteArrayOutputStream(); GZIPOutputStream(bos).use { it.write(patched.toByteArray(Charsets.ISO_8859_1)) }
        assertEquals(BackupError.WRONG_PASSPHRASE, assertFailsWith<BackupException> { BackupCodec.decode(bos.toByteArray(), "pw".toCharArray()) }.error)
    }
}

class ReminderTests {
    @Test fun rendersTemplates() {
        val t = ReminderTemplates.default("fr", ReminderType.OVERDUE)
        val msg = ReminderTemplates.render(t, mapOf("customer_name" to "Ali", "amount" to "200.000 TND", "date" to "01/03/2026", "business_name" to "Shop"))
        assertTrue("Ali" in msg && "200.000 TND" in msg && "01/03/2026" in msg)
        assertFalse("{" in msg)
        assertTrue("{typo}" in ReminderTemplates.render("x {typo}", emptyMap()))
    }

    @Test fun everyLanguageAndTypeHasATemplate() {
        for (lang in listOf("ar", "fr", "en")) for (t in ReminderType.entries) assertTrue(ReminderTemplates.default(lang, t).contains("{customer_name}"))
    }

    @Test fun phoneNormalization() {
        assertEquals("21620123456", WhatsAppLink.normalizePhone("20 123 456", "216"))
        assertEquals("21620123456", WhatsAppLink.normalizePhone("+216 20 123 456", "216"))
        assertEquals("21620123456", WhatsAppLink.normalizePhone("00216 20123456", "+216"))
        assertEquals("21620123456", WhatsAppLink.normalizePhone("21620123456", "216"))
        assertEquals("966512345678", WhatsAppLink.normalizePhone("0512345678", "966"))
        assertEquals("21620123456", WhatsAppLink.normalizePhone("٢٠ ١٢٣ ٤٥٦", "216"))
        assertNull(WhatsAppLink.normalizePhone("123", ""))
        assertNull(WhatsAppLink.normalizePhone("abc", "216"))
    }

    @Test fun linkIsCorrectlyEncoded() {
        val url = WhatsAppLink.build("20123456", "216", "مرحباً Ali & Co: 200 TND?")!!
        assertTrue(url.startsWith("https://wa.me/21620123456?text="))
        assertFalse(url.contains(" ")); assertFalse(url.contains("+")); assertFalse(url.substringAfter("text=").contains("&"))
        assertEquals("مرحباً Ali & Co: 200 TND?", java.net.URLDecoder.decode(url.substringAfter("text="), "UTF-8"))
        assertNull(WhatsAppLink.build("xx", "216", "hi"))
    }
}
