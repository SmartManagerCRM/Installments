package com.smartmanager.installments.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.smartmanager.installments.BuildConfig
import com.smartmanager.installments.core.licensing.ActivationRequest
import com.smartmanager.installments.core.licensing.DeviceIdentity
import com.smartmanager.installments.core.licensing.LicenseCheck
import com.smartmanager.installments.core.licensing.LicenseStatus
import com.smartmanager.installments.core.licensing.LicenseVerifier
import com.smartmanager.installments.core.licensing.PublicKeyRing
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.LicenseEntity
import com.smartmanager.installments.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted key/value store for local secrets (installation secret, last-seen clock). */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class InMemorySecretStore : SecretStore {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
}

/**
 * Values are AES-256-GCM encrypted with a key that lives in the Android Keystore (non-exportable). Copying the app's
 * data directory to another device (or restoring it from a cloud backup) therefore yields undecryptable values, which makes
 * the installation secret - and with it the device binding - non-transferable. Unreadable values are treated as missing.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("smi_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    override fun get(key: String): String? = try {
        val raw = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    override fun put(key: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply()
    }

    private companion object { const val ALIAS = "smi_master_key_v1" }
}

object LicenseKeys {
    /** Production key (res/raw/license_public_key.pem) plus, in DEBUG builds only, the published test key. */
    fun load(context: Context): PublicKeyRing {
        val pems = mutableListOf<String>()
        fun raw(name: String) = context.resources.getIdentifier(name, "raw", context.packageName).takeIf { it != 0 }?.let {
            context.resources.openRawResource(it).bufferedReader().use { r -> r.readText() }
        }
        raw("license_public_key")?.let(pems::add)
        if (BuildConfig.ALLOW_TEST_LICENSE_KEY) raw("license_public_key_test")?.let(pems::add)
        // A malformed/placeholder key is skipped instead of crashing the app; it simply verifies nothing.
        val usable = pems.filter { runCatching { PublicKeyRing.fromPems(listOf(it)) }.isSuccess }
        return PublicKeyRing.fromPems(usable)
    }
}

class LicenseManager(
    private val db: AppDatabase,
    private val clock: AppClock,
    private val secrets: SecretStore,
    private val keyRing: PublicKeyRing,
    private val appVersion: String,
) {
    private val _state = MutableStateFlow(LicenseCheck(LicenseStatus.NOT_ACTIVATED))
    val state: StateFlow<LicenseCheck> = _state

    /**
     * Stable device-binding id derived from a random installation secret created on first launch. No IMEI/serial/MAC is read.
     * If the secret is lost (uninstall, factory reset, restored app data) a NEW id is created: the license no longer matches and
     * the retailer follows the documented reactivation path (send the new activation request, receive a replacement license).
     */
    val deviceId: String by lazy {
        val existing = secrets.get(SECRET_KEY)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        val secret = existing?.takeIf { it.size == 16 } ?: ByteArray(16).also { SecureRandom().nextBytes(it); secrets.put(SECRET_KEY, Base64.encodeToString(it, Base64.NO_WRAP)) }
        DeviceIdentity.deviceIdFromSecret(secret)
    }

    fun activationRequest(): String = ActivationRequest.encode(deviceId, appVersion, clock.nowMillis() / 1000)

    val writeGuard = WriteGuard {
        if (!_state.value.allowsWrites) throw LedgerException(LedgerError.LICENSE_READ_ONLY)
    }

    private fun lastSeen(): Long? = secrets.get(LAST_SEEN_KEY)?.toLongOrNull()

    /** Re-verifies the stored license against the current clock; call on start and whenever the app returns to the foreground. */
    suspend fun refresh(): LicenseCheck {
        val stored = db.license().current()
        val now = clock.nowMillis() / 1000
        val check = if (stored == null) LicenseCheck(LicenseStatus.NOT_ACTIVATED)
        else LicenseVerifier.verify(stored.licenseText, keyRing, deviceId, now, lastSeen())
        // Only ever move the remembered clock forward: winding the clock back is then visible for duration licenses.
        if (lastSeen()?.let { it < now } != false) secrets.put(LAST_SEEN_KEY, now.toString())
        _state.value = check
        return check
    }

    /** Verifies and, only if valid for THIS device right now, stores the license. Returns the verification result either way. */
    suspend fun importLicense(text: String): LicenseCheck {
        val check = LicenseVerifier.verify(text, keyRing, deviceId, clock.nowMillis() / 1000, lastSeen())
        if (check.status == LicenseStatus.VALID) {
            db.license().put(LicenseEntity("current", text.trim(), check.payload!!.licenseId, clock.nowMillis()))
            refresh()
        }
        return check
    }

    private companion object {
        const val SECRET_KEY = "install_secret"
        const val LAST_SEEN_KEY = "clock_last_seen"
    }
}

