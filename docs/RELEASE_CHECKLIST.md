# Release checklist

**Code & tests**
- [ ] `./gradlew :core:test :app:testDebugUnitTest` green; generator `python -m unittest` green (CI is green on the release commit)
- [ ] `python3 tools/gen_strings.py` reports no missing strings / placeholder mismatches
- [ ] `app/schemas/*.json` committed; if the schema changed: a `Migration` exists in `AppDatabase.MIGRATIONS` and was tested with real data
- [ ] `versionCode` / `versionName` bumped

**Licensing**
- [ ] Production key created with `init-keys`, **backed up twice offline**, passphrase stored separately
- [ ] `app/src/main/res/raw/license_public_key.pem` = production public key (not the placeholder, not the test key)
- [ ] Issued a lifetime and a duration license to a **release** build on a real phone; both activate; a license for another device is rejected
- [ ] Expired license test (issue a duration license ending yesterday): read-only banner, export still works, no data lost
- [ ] `git ls-files | xargs grep -l "PRIVATE KEY"` lists only `license-generator/test-keys/TEST_ONLY_signing_key.pem` (and the test that scans for it)

**APK**
- [ ] Built with `assembleRelease`, signed with the release keystore (`apksigner verify` ok), SHA-256 recorded
- [ ] `unzip -l app-release.apk` shows no `.pem` except the public key; no `license-generator`, no debug test key
- [ ] Manifest has **no INTERNET permission** (`aapt dump permissions app-release.apk`)
- [ ] Installs on a clean phone (Android 8+, a small phone and a tablet)

**Manual smoke test (Arabic RTL and French)**
- [ ] Airplane mode on: create customer -> contract (refrigerator example) -> record partial then full payment -> receipt PDF opens/shares
- [ ] Dashboard figures match the records; overdue flips after changing the phone date; Collection Center groups customers
- [ ] WhatsApp button opens a chat with the prefilled message (nothing sent automatically)
- [ ] Backup to Drive/USB, wipe/reinstall, restore: balances identical; encrypted backup with wrong password fails cleanly
- [ ] Arabic names render correctly in contract/receipt/statement PDFs; layouts mirror correctly; arrows point the right way
- [ ] Demo data not present on a fresh install; Settings -> Demo data not visible in release builds

**Delivery**
- [ ] Customer package = signed APK + `docs/INSTALL_AND_ACTIVATE.md` only (no generator, no keys)
- [ ] Generator executable archived with its SHA-256, key backups verified (can unlock the backup copy)
