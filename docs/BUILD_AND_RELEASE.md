# Building the APK and preparing a release

## Requirements
JDK 17, Android SDK (platform 34, build-tools 34.0.0), internet access for Gradle the first time. Python 3.10+ for the generator.
Everything is reproducible from source: Gradle wrapper 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, Room 2.6.1
(versions in `gradle/libs.versions.toml`).

```bash
# debug APK (accepts the published TEST license key; for development only)
./gradlew :app:assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk

# all tests
./gradlew :core:test                    # pure Kotlin business logic (no Android SDK needed: add -PcoreOnly)
./gradlew :app:testDebugUnitTest        # Robolectric + in-memory Room integration tests
(cd license-generator && python3 -m unittest discover -s tests -t .)
```
The GitHub Actions workflow (`.github/workflows/android.yml`) runs all of the above on every push and uploads the debug APK
and test reports as artifacts.

> **Non-technical owner? Use `docs/CUSTOMER_VERSION_SIMPLE.md`** (License Manager only: license key + browser APK signing).

## Release APK (customer build)
1. **Create your signing key and export the public key** (once, see `license-generator/README.md`):
   `python3 -m smilicgen init-keys` then
   `python3 -m smilicgen export-public --out app/src/main/res/raw/license_public_key.pem`.
   The release build refuses to run with the placeholder or the test key (`verifyProductionKey` Gradle task).
2. **Create the Android app-signing keystore** (different from the license key!). Keep it outside Git and back it up; losing it
   means customers can never receive updates over the installed app.
   ```bash
   keytool -genkeypair -v -keystore ~/smartmanager-release.jks -alias smartmanager -keyalg RSA -keysize 4096 -validity 10000
   ```
3. Create `keystore.properties` in the repo root (git-ignored):
   ```
   storeFile=/home/you/smartmanager-release.jks
   storePassword=...
   keyAlias=smartmanager
   keyPassword=...
   ```
4. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, then
   `./gradlew :app:assembleRelease` -> `app/build/outputs/apk/release/app-release.apk` (R8-minified, resource-shrunk, signed).
   Without `keystore.properties` the APK is produced **unsigned** (cannot be installed) - sign it with `apksigner`.
5. Verify: `apksigner verify --print-certs app-release.apk`; check there is no private key inside:
   `unzip -l app-release.apk | grep -i -E "pem|key"` should list only `res/raw/license_public_key.pem`; and
   `unzip -p app-release.apk res/raw/license_public_key.pem` must be YOUR public key.
6. Install on a clean phone, run the checklist in `docs/RELEASE_CHECKLIST.md`.

## Where the public key goes
`app/src/main/res/raw/license_public_key.pem` (production, all builds) and, **debug only**,
`app/src/debug/res/raw/license_public_key_test.pem` (the published test key). `LicenseKeys.load()` builds the key ring; each
license names its signing key via `kid`, so adding a second key during rotation is a one-line change.

## Distributing
Send customers only the signed `app-release.apk` (or host it for download). Never send: the license generator, any `.pem` private
key, `keystore.properties`/`.jks`, debug builds, test keys. Computer the SHA-256 of the APK and publish it with the download.

## Debug signing key (public on purpose)
`app/debug.keystore` (password `android`) is committed deliberately: it signs DEBUG builds only, so that every debug/CI APK has the same
signature and installs as an update over the previous one ("App not installed" otherwise). It protects nothing and is never used for customers.
Your **release** keystore is different, is created by you, and must never be committed (`.gitignore` blocks `*.jks` / `*.keystore`).

The debug key mimics the Android Gradle Plugin default debug key (RSA-2048, SHA256withRSA, ~30 years, `CN=Android Debug`). An earlier
version used SHA384withRSA with a 100-year validity (expiry year 2126); builds signed that way failed to install on at least one phone
("App not installed") while builds with the default-style certificate installed, so keep these parameters.
