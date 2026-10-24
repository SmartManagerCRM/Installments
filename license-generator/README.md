# SmartManager Installments - License Generator (owner tool)

A small **offline desktop tool** (Python + Tkinter) that you, the product owner, use to create licenses for your
customers. It is **not** part of the customer app, the APK or the customer release package. Nothing here talks to
a server; no paid API is used.

```
license-generator/
  smilicgen/          source (formats, keys, history, service, cli, gui)
  tests/              unit tests (python -m unittest discover -s tests -t .)
  test-keys/          TEST-ONLY key pair (public on purpose; trusted only by DEBUG app builds + tests)
  samples/            sample licenses signed with the TEST key (for the fixed test device id)
  tools/              make_test_assets.py (regenerates test key + samples)
  smilicgen.spec, build_exe.sh, build_exe.bat   executable build
```

## 1. Run it

```bash
python3 -m pip install -r requirements.txt     # cryptography, tzdata  (Tkinter ships with python.org / Windows Python)
python3 -m smilicgen                           # opens the graphical interface
python3 -m smilicgen --help                    # command line interface (same logic)
```

Linux note: install Tk with your package manager if missing (`sudo apt install python3-tk`).

## 2. First-time setup: create your signing key (once)

```bash
python3 -m smilicgen init-keys                 # asks for a passphrase (>= 10 characters), twice
```

* The **private key** is created in your *data directory* (default `~/.smartmanager-licensegen/signing_key.pem`, or
  `%APPDATA%\SmartManagerLicenseGen\` on Windows; override with `--home` or `SMI_LICGEN_HOME`), encrypted with your
  passphrase (PKCS#8 / PBES2), file mode `0600`.
* The tool **refuses** to create it inside a Git working tree and refuses to overwrite an existing key.
* Algorithm: **ECDSA P-256 with SHA-256 ("ES256")** - supported natively on every Android version and by the Python
  `cryptography` package. (Ed25519 is not available on all Android versions the app supports.)

Then export the **public** key and put it into the Android project:

```bash
python3 -m smilicgen export-public --out ../app/src/main/res/raw/license_public_key.pem
```

Rebuild the app. Release builds **fail** while that file still contains the placeholder, and they never trust the test key.

### Back up the key (do this now)

```bash
python3 -m smilicgen backup-key --out /path/on/usb/smi-signing-key-BACKUP.pem
```

The backup is the same passphrase-encrypted file. Keep **two offline copies in different places** and store the
passphrase separately (password manager + paper). If you lose the key or its passphrase you can no longer issue licenses
that already-shipped apps accept (you would have to ship a new app build with a new public key).
**Recovery** = copy the backup file to `<data dir>/signing_key.pem` and unlock it with the passphrase.

### Rotating the key

1. `init-keys --home <new dir>` creates a new pair (new key id = first 8 hex chars of SHA-256 of the public key).
2. Add the new public key to the app (`LicenseKeys` accepts a *list* of PEMs: put the new file in `res/raw` and extend
   `LicenseKeys.load`), release the new app version. The old key stays accepted so existing licenses keep working.
3. Issue new licenses with `--key <new key file>`. After all customers updated, remove the old public key.
Every license carries `kid`, so the app picks the right key.

## 3. Issue a license

1. The customer opens the app -> *Activation* screen -> **Copy / Share** the *activation request* (`SMI-ACT-1:...`) and sends it to you (WhatsApp, e-mail...).
2. In the generator: *New license* tab -> customer name, optional reference -> type:
   * **Lifetime**: no expiry date (never expires with time).
   * **Custom duration**: start date + *last valid day* (inclusive). Quick buttons +1/3/6/12/24 months.
3. Paste the activation request (or load it from a file). The device id inside is bound into the license.
4. Press **Generate and save license...** -> save the `.smilic` file -> send it to the customer.
5. The customer opens *Activation* -> **Import license file** (or pastes the one-line text form). Done, offline.

CLI equivalent:

```bash
python3 -m smilicgen issue --customer "Boutique Elnour" --customer-ref C-0042 --type lifetime \
        --request "SMI-ACT-1:...." --out elnour.smilic
python3 -m smilicgen issue --customer "Boutique Elnour" --type duration --start 2026-11-01 --expiry 2027-10-31 \
        --tz Africa/Tunis --request-file request.txt
```

**Time zones are explicit**: the start is `00:00:00` and the expiry `23:59:59` *in the time zone you choose* (default
`Africa/Tunis`), converted to UTC epoch seconds inside the signed payload. Duration licenses are evaluated against the
phone's clock; see `docs/LICENSING.md` for clock-tampering behaviour.

## 4. Replacement (new phone, reset, reinstall)

*History / Reissue* tab -> select the license -> **Reissue replacement for new device...** -> paste the NEW activation
request, enter the reason, tick *I verified the customer*. The replacement keeps the same license id (revision + 1), customer
and validity, bound to the new device. CLI: `python3 -m smilicgen reissue --license-id SMI-... --reason "phone lost" --verified --request ...`.

Verify the customer before reissuing: an offline license **cannot be revoked remotely**, the old license keeps working on the old device.

## 5. History and search

Every issued license is stored in `<data dir>/license_history.sqlite` (customer, device id, validity, key id, notes,
replacement reason and the license text - **never** key material). Search by customer, license id, reference or device id
in the GUI or `python3 -m smilicgen list "elnour"`. Back this file up with your key backup.

## 6. Build the executable (reproducible)

```bash
./build_exe.sh            # Linux/macOS  -> dist/SmartManagerLicenseGenerator
build_exe.bat             # Windows      -> dist\SmartManagerLicenseGenerator.exe
```

Steps performed: fresh virtualenv -> pinned minimum versions from `requirements-build.txt` -> unit tests -> PyInstaller
with `smilicgen.spec` -> SHA-256 printed. PyInstaller builds only for the OS it runs on. The executable contains no keys.
It is for **you** - do not hand it to customers.

## 7. Tests

```bash
python3 -m unittest discover -s tests -t .
```

Covers: lifetime/duration issuing, time-zone boundaries, tamper detection, wrong device/key, malformed input, reissue,
history search, key encryption/backup/refusals, repository hygiene (no private key outside `test-keys/`), CLI end-to-end,
and the committed sample licenses (which the Kotlin tests also verify - cross-language compatibility).

## Test keys vs production keys

`test-keys/TEST_ONLY_signing_key.pem` is deliberately public. It is trusted only by **debug** builds of the app (and by
tests) so developers can try activation with `samples/`. Release builds never contain the test public key. Regenerate
test assets with `python3 tools/make_test_assets.py` (only needed if you change the format).
