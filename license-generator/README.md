# SmartManager Installments - License Generator (Python edition)

> **Recommended tool: `../license-manager/` (JavaScript, one offline page - just double-click `index.html`).**
> It issues the same licenses. This Python edition remains as a command-line alternative; both are tested against each other.


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

## 1. Run it (double-click)

* **Windows:** double-click `START_LICENSE_MANAGER.bat` (needs Python 3.10+ from python.org, "Add to PATH" ticked; it installs the 2 small
  dependencies by itself the first time).
* **macOS / Linux:** `./start_license_manager.sh` (Linux: `sudo apt install python3-tk` once).
* Or by hand: `pip install -r requirements.txt` then `python3 -m smilicgen`.

The window has four tabs: **1. Keys**, **2. Issue license**, **3. Customers & licenses**, **4. Verify license**.

## 2. Issue a license (the everyday job - 30 seconds)

1. The customer opens the app -> activation screen -> **Copy/Share** and sends you the text (`SMI-ACT-1:...`) or just the **Device ID**.
2. Open **tab 2. Issue license**:
   * **Private key file / Passphrase** - for your production key type its passphrase. For the *debug* test app press **Use TEST key**
     (no passphrase).
   * **Device ID or Activation request** - paste what the customer sent (either form works).
   * **Customer / note** - the customer's name (private; shown only in your register).
   * **Order reference** - optional invoice/order number.
   * **Validity** - *Lifetime* (never expires) or *Subscription: valid for N days* (counted from today; works until the end of that day).
   * **Save license as** - leave empty: the file is saved automatically in `<data folder>/licenses/` and its path is shown.
3. Press **Issue license**. The output pane shows the license ID, the summary and the **one-line license code**.
   Send the saved `.smilic` file (or the code: **Copy license code**) to the customer.
4. The customer taps **Import license file** (or pastes the code and taps **Activate**). Done, offline.

## 3. Customers & licenses (tab 3)

A register of every license (latest revision of each): order ref, customer/note, license ID, **ACTIVE / EXPIRED / NOT YET VALID** (colour coded),
valid-to date, revision, device ID. Search box on top. Buttons:

| Button | What it does |
|---|---|
| **Renew / change validity...** | new code for the **same phone** (revision + 1): renew a subscription for N more days, or switch to lifetime. No new request code needed |
| **New phone (replacement)...** | customer changed/lost the phone: paste the NEW request code, give a reason, tick the identity check -> replacement license bound to the new phone |
| **History** | all revisions of the selected license (dates, device, reason) |
| **Save license file... / Copy license code** | re-send a license you issued earlier |
| **Export CSV...** | every issued license to Excel-friendly CSV |

How control works offline: a subscription ends by itself on its end date unless you send a renewal code - that is how you stop a customer who does not pay.
An app that has no internet connection **cannot be switched off remotely**; there are no "suspend/cancel codes" in this version.

## 4. First-time setup of the production key

Only needed for customer builds (the debug app uses the TEST key button). In **tab 1. Keys**:
type a passphrase (10+ characters) in the Passphrase box, press **Create new signing key**, then **Back up private key...** (two offline copies) and
**Export public key for the app...** (save it as `app/src/main/res/raw/license_public_key.pem` and rebuild the app).
Command line equivalent:

```bash
python3 -m smilicgen init-keys
python3 -m smilicgen export-public --out ../app/src/main/res/raw/license_public_key.pem
python3 -m smilicgen backup-key --out /path/on/usb/smi-signing-key-BACKUP.pem
```

* The **private key** lives in your data folder (`~/.smartmanager-licensegen/` or `%APPDATA%\SmartManagerLicenseGen\`), encrypted with your passphrase.
  The tool refuses to create it inside a Git folder or to overwrite an existing key.
* Algorithm: ECDSA P-256 / SHA-256. Losing the key or passphrase means you can't issue licenses that already-shipped apps accept - keep the backups.
* **Rotation:** create a new key pair, ship an app update that trusts both public keys (`LicenseKeys.load`), issue new licenses with the new key, later drop the old one.

### Command line (same logic as the window)

```bash
python3 -m smilicgen --key test-keys/TEST_ONLY_signing_key.pem issue --customer "Boutique Elnour" --order INV-1042 --type duration --days 30 --request "SMI-ACT-1:..." 
python3 -m smilicgen --key <key> renew --license-id SMI-... --type duration --days 30
python3 -m smilicgen list "elnour"          # register with status
python3 -m smilicgen export-csv --out issued.csv
python3 -m smilicgen verify file.smilic --public-key test-keys/TEST_ONLY_public_key.pem --device XXXX-XXXX-XXXX-XXXX-XXXX
```
Time zones are explicit: start `00:00:00` / expiry `23:59:59` in the chosen zone (default `Africa/Tunis`, `--tz` to change).

## 4. Replacement (new phone, reset, reinstall)

*Customers & licenses* tab -> select the license -> **New phone (replacement)...** -> paste the NEW activation
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
