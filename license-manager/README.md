# SmartManager Installments - License Manager (JavaScript)

One offline web page for the product owner: paste the customer's **Device ID / activation request**, choose **Lifetime** or
**Subscription (N days)**, press **Issue license**. No server, no install, no Python - nothing is uploaded.

```
license-manager/
  index.html        the tool (double-click it; Chrome, Edge or Firefox)
  smilic-core.js    all logic (WebCrypto ECDSA P-256) - used by the page and by the tests
  test-key.js       the published TEST key (debug app only)
  samples/          sample licenses issued by this tool (verified by the Kotlin tests too)
  tests/            node tests (+ browser end-to-end test)
```

## Use it

1. Open **`index.html`** (double-click). It works from the file system, offline.
2. **Tab 2 - Issue license**
   * **Signing key:** `TEST key` for the *debug* app (no passphrase). `My production key` once you created one (tab 1) - type its passphrase.
   * **Device ID or Activation request:** paste what the customer copies from the app's activation screen (either form).
   * **Customer / note** (required, private) and **Order reference** (optional).
   * **Validity:** *Lifetime*, or *Subscription: valid for N days* (counted from today, valid until the end of that day). Time zone defaults to `Africa/Tunis`.
3. Press **Issue license**. A `.smilic` file is downloaded (see your Downloads folder) and the output shows the summary and the
   **one-line license code**. Send the file or the code to the customer, who taps *Import license file* (or pastes the code and taps *Activate*).

## Tab 3 - Customers & licenses
Register of every license (latest revision of each), colour-coded **ACTIVE / EXPIRED / NOT YET VALID**, searchable. Select a row, then:
**Renew / change validity** (same phone, new code - no new request needed), **New phone (replacement)** (needs the new phone's request, a reason and the identity check),
**History**, **Download license file**, **Copy license code**, **Export CSV**.
A subscription stops by itself at its end date unless you send a renewal; an app without internet cannot be switched off remotely.

## Tab 1 - Keys (production)
*Create new production key* (passphrase >= 10 characters) -> stored **encrypted in this browser** (PBKDF2-SHA256 600k iterations + AES-256-GCM) and a
`.smikey` backup is downloaded. Then *Export public key for the app (.pem)* -> save as `app/src/main/res/raw/license_public_key.pem` and rebuild the release APK.

**Important - browser storage:** the key and the register live in this browser profile. Clearing site data, using another browser/profile or another
computer loses them. So: keep **two offline copies of the `.smikey` backup** (and the passphrase separately) and use *Download register backup* regularly.
*Restore key / register from backup* brings them back on any computer. Losing the key = you cannot issue licenses that already-shipped apps accept.

## Tab 4 - Verify
Runs the same checks as the app against a public key and a device ID.

## Compatibility
Same license format as the Python tool and the Android app: payload = canonical JSON (sorted keys, no whitespace) signed with **ECDSA P-256 / SHA-256**,
DER signature, key id = first 4 bytes of SHA-256(SPKI). Verified in tests: JS -> Python verifier, Python -> JS verifier, JS -> Kotlin verifier.
Keys made by the Python tool (`.pem`, passphrase-encrypted) cannot be imported (WebCrypto cannot read that encryption); the unencrypted TEST key can.

## Tests
```bash
cd license-manager
node --test tests/*.test.js                      # 16 unit tests (Node >= 18); includes the cross-check with the Python verifier
NODE_PATH=$(npm root -g) node tests/e2e/ui.e2e.js  # browser end-to-end test of index.html (needs Playwright + Chromium)
node tools/make-samples.js                       # regenerate samples/ (test key)
```

## Honest limits
Same as every offline licensing scheme (see `docs/LICENSING.md`): not uncrackable, no remote revocation, subscriptions rely on the phone's clock.
The page runs entirely in your browser and makes no network requests; still, only open a copy of `index.html` you trust (it handles your private key).
