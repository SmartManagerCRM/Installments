# SmartManager Installments

Offline Android app for retailers who sell on installments (electronics, appliances, phones, computers, furniture...), plus a
**separate** offline license generator for the product owner.

* Kotlin · Jetpack Compose · Material 3 · Room/SQLite · Navigation · Paging · coroutines/Flow · Android PDF
* **Arabic (RTL), French and English**, phone and tablet layouts (navigation rail + two-pane customers on wide screens)
* No server, no account, no paid API, **no INTERNET permission** (WhatsApp/sharing open other apps via intents)
* Signed, device-bound licenses verified offline (ECDSA P-256) - lifetime or custom duration

## Repository layout

| Path | What |
|---|---|
| `core/` | **Pure Kotlin/JVM** business logic: money, schedules, payment allocation, installment status, collection rules, reminders/WhatsApp links, license verification, backup container. Fully unit-tested, no Android/DB/UI dependency |
| `app/` | Android application: `data/db` (Room entities, DAOs, view), `data/repo` (ledger, customers, products, settings, license manager, backup, reports, demo), `pdf/`, `ui/` (dashboard, customers, contracts, payments, collection, reports, settings, license, products, navigation, theme, components), `util/` |
| `license-generator/` | Python/Tkinter owner tool (GUI + CLI), tests, test keys, sample licenses, PyInstaller build |
| `docs/` | Financial rules, licensing, build/release, install & activation, release checklist, future cloud sync, testing |
| `tools/` | `gen_strings.py` generates `strings.xml` for ar/fr/en from one table and verifies keys/placeholders |
| `.github/workflows/android.yml` | CI: generator tests, core tests, app tests, debug APK |

## Quick start (developers)

```bash
./gradlew :core:test -PcoreOnly         # business-logic tests, no Android SDK needed
./gradlew :app:testDebugUnitTest        # Room/Robolectric integration tests (Android SDK 34)
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
cd license-generator && python3 -m unittest discover -s tests -t .
```

Debug builds accept licenses signed with the **published test key** - try `license-generator/samples/` or issue your own with
`python3 -m smilicgen --key test-keys/TEST_ONLY_signing_key.pem issue ...`. In a debug build use *Settings -> Demo data* to generate sample
customers/contracts (flagged and removable).

## First customer license - the 6 steps
1. One time: `cd license-generator && python3 -m smilicgen init-keys`, back the key up, export the public key into
   `app/src/main/res/raw/license_public_key.pem`, build the signed release APK (`docs/BUILD_AND_RELEASE.md`).
2. Install the APK on the customer's phone (`docs/INSTALL_AND_ACTIVATE.md`).
3. Customer taps **Copy/Share** on the Activation screen and sends you the `SMI-ACT-1:...` request.
4. In the generator (`python3 -m smilicgen`): customer name, *Lifetime* or *Custom duration* (+ dates), paste the request, **Generate and save**.
5. Send the `.smilic` file to the customer.
6. Customer taps **Import license file**. Activated - fully offline.

## Documentation
* `docs/FINANCIAL_RULES.md` - money, rounding, status, allocation, reversal, reschedule, transactions
* `docs/LICENSING.md` - format, device binding, procedures (lifetime, duration, replacement), limitations
* `docs/BUILD_AND_RELEASE.md`, `docs/RELEASE_CHECKLIST.md`, `docs/INSTALL_AND_ACTIVATE.md`
* `docs/FUTURE_CLOUD_SYNC.md` - how optional sync can be added without rewriting the business logic
* `docs/TESTING.md` - what is tested, how, and what must still be checked manually
* `license-generator/README.md` - owner tool, key creation/backup/recovery/rotation, executable build

## Security & privacy at a glance
No network permission; no analytics; `allowBackup=false` + extraction rules exclude data from cloud backup; installation secret protected by
Android Keystore; Room parameterised queries only; backups are validated (format, version, SHA-256, referential and financial
consistency) before an atomic all-or-nothing restore (with an automatic safety copy); optional AES-256-GCM backup encryption;
PDFs/CSV are written to the cache and shared through `FileProvider`, then purged after 24 h; CSV cells are protected against formula injection;
logs contain no customer data. **Not implemented in v1:** app PIN/biometric lock (see `docs/TESTING.md` -> roadmap).
