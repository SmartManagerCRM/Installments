# Licensing design, procedures and known limitations

## Summary
* **Offline, asymmetric**: the owner's generator signs a license with a **private** ECDSA P-256 key; the app contains only the
  **public** key and verifies the signature locally. No server, no internet, no hidden master key, no password.
* **One device per license**: the license is bound to a *device id* produced by the app.
* **Lifetime** (never expires with time) and **custom-duration** (start + last valid day) licenses.
* **Expiry never destroys data**: the app switches to *read-only mode* (view, search, export PDFs/CSV, back up, restore).

## License file (`.smilic`, JSON)
```json
{"fmt":"SMI-LIC","v":1,"alg":"ES256","kid":"4df8c077","payload":"<base64url canonical JSON>","sig":"<base64url DER ECDSA>"}
```
Payload (UTF-8 canonical JSON: sorted keys, no whitespace; **the signature covers exactly these bytes**, so every field is protected):
`v, product, licenseId, revision, customerName, customerRef, type (lifetime|duration), issuedAt, startAt, expiresAt (duration only),
deviceId, features, tz`. Times are UTC epoch seconds. A one-line text form `SMI-LIC-1.<base64url(file)>` can be pasted.

The app rejects: malformed files, unknown key id, bad signature (any bit flipped), unsupported schema version, other product,
other device, inconsistent type/expiry, not-yet-valid, expired, clock rolled back (duration licenses).

## Device binding (no personal data)
On first launch the app creates a random 16-byte **installation secret**, encrypted with an **Android Keystore AES-GCM key**
(non-exportable) and stored in app-private storage. `deviceId = SHA-256("SMI-DEVICE-V1" || secret)` truncated to 80 bits, shown as
`XXXX-XXXX-XXXX-XXXX-XXXX`. **No IMEI, serial number, MAC, Android ID or advertising id is read.**
* `allowBackup=false` and data-extraction rules exclude all data from cloud backup / device transfer, and because the secret is
  wrapped by a Keystore key that cannot leave the phone, a copied data folder cannot reproduce the id on another phone.
* The **activation request** (`SMI-ACT-1:<base64url json>.<checksum>`) carries the device id, product and app version and
  can be copied/shared offline. The checksum catches truncated copy/paste.

## Procedures
**First activation** - see `license-generator/README.md` section 3 and `docs/INSTALL_AND_ACTIVATE.md`.

**Lifetime license**: generator -> type *Lifetime*; no expiry field. **Duration license**: type *Custom duration*, start date and
*last valid day* (inclusive; expiry instant = 23:59:59 in the chosen time zone).

**Device replacement / reactivation** (new phone, factory reset, uninstall, data cleared):
1. Old phone (if available): *Settings -> Backup -> Create backup*; new phone: install the app, *Restore*.
   (If the old phone is gone, restore the latest backup the customer saved.)
2. New phone: *Activation* screen shows a **new** device id/request - copy and send it to the owner.
3. Owner verifies the customer (call, known number, invoice, etc.), then *History -> Reissue replacement*, enters the reason, ticks the
   verification box and sends the new `.smilic`. Same license id, revision + 1, same validity, new device.
4. Customer imports it. The license is never "silently transferable".
Backups deliberately **do not contain the license** (it is device-bound).

**Renewal**: issue a new duration license (new validity) for the same device id and import it from *Settings -> License*.

## Expiry behaviour
* 30 days before expiry the license screen and a banner warn the retailer.
* After expiry: *read-only mode* with a clear message. Export of backups, PDFs and CSV keeps working, restore works,
  nothing is deleted or encrypted. Entering a renewed license re-enables writing immediately.
* Policy statement for customers: *"If your license expires you keep full access to your own data for viewing and export."*

## Clock changes (documented limitation, partially mitigated)
Duration licenses depend on the phone's clock. The app remembers the **greatest time it has ever seen** (encrypted with the
Keystore) and the license `issuedAt`. If the clock is set back by more than 24 h the status becomes *Clock rolled back* and writing
is blocked until the date is corrected (tolerance avoids false alarms from time-zone/NTP changes). Winding the clock back
**before** the first run after install, wiping app data (which also changes the device id, i.e. needs a new license), or a rooted
phone with a hooked Keystore can defeat this. **Lifetime licenses ignore clock history** (no online check, never expire).

## Key management (summary - details in license-generator/README.md)
* Create once with `init-keys` (outside Git, passphrase-encrypted, `0600`). Export only the public key to
  `app/src/main/res/raw/license_public_key.pem`.
* Back up the encrypted private key to **two offline places**; keep the passphrase separately. Recovery = copy back + passphrase.
* Rotation: new key pair -> ship an app update that trusts **both** public keys -> issue new licenses with the new key -> later drop the old.
* Release builds fail if the public key file is the placeholder or equals the test key. **Debug builds** also trust the published
  TEST key (`license-generator/test-keys`) - debug APKs are for development only and must never be given to customers.

## Honest limitations (please read)
* An offline licence **cannot be made uncrackable**. Anyone who can modify the APK (decompile, patch the check, replace the public
  key, re-sign) can bypass it. R8 minification/obfuscation and signature verification raise the bar for casual sharing but are
  not DRM. The design goal is *reasonable protection against ordinary unauthorized sharing* while preserving legitimate recovery.
* A license **cannot be revoked remotely**; a replaced license keeps working on the old device until it expires (use duration
  licenses if you need leverage) - verify customers before reissuing.
* Because the device id is app-generated, a determined user who wipes app data gets a new id and could request another license;
  your history table (customer, previous device ids, reasons) is the control.
* Time-limited licences rely on the device clock (see above).
* The app has no `INTERNET` permission, so it cannot "phone home" - by design.
