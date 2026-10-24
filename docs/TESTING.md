# Testing

Everything below is automated and runs in CI on every push (`.github/workflows/android.yml`). Results of the latest local/CI
runs are in the section "Latest results" at the end (filled in from real runs - never assumed).

## Automated suites

| Suite | Where | What it proves |
|---|---|---|
| Financial core | `core/.../FinancialTests.kt` | money parsing/formatting without floats; weekly/bi-weekly/monthly/bi-monthly/quarterly dates (no month-end drift); custom schedules; refrigerator example (2,400 TND, 400 down -> 10 x 200); rounding policy (always sums exactly); contract totals/validation; allocation (full, partial, multi-installment, repeated partials, latest-first, manual, over-payment, 500 random cases); installment status (due today, 1 day late, many days, paid, partially paid, rescheduled, date change); collection groups and recommendations |
| Licensing core | `core/.../LicensingTests.kt` | valid lifetime; valid duration (+days left, expiring soon, boundary second); expired; not yet valid; invalid signature; tampered payload; bit-flip fuzz; other device; malformed files; unknown key / product / schema version; clock rollback (duration only); one-line text form; device replacement workflow; activation-request checksum; device id format |
| Cross-language | `core/.../PythonInteropTests.kt` | licenses signed by the **real Python generator** (committed test key + samples) verify in the Kotlin verifier; Python-made activation request decodes in Kotlin |
| Backup core | `core/.../BackupAndRemindersTests.kt` | round trip (plain + AES-GCM), wrong/missing passphrase, tampered bytes, tampered encrypted header, incompatible version, garbage; reminder templates, phone normalisation, WhatsApp URL encoding |
| Ledger integration | `app/src/test/.../LedgerTests.kt` (Robolectric + in-memory Room) | contract creation (numbers, down-payment receipt, atomic failure), drafts, cancellation, full/partial/multi/manual payments, over-payment rejected with nothing written, **duplicate submission** (same key, same-amount window), completion, receipt numbering, early settlement + discount, **reversal** (history kept, balance restored, contract re-opened), audit trail, **reschedule** (original rows preserved, audit with old/new), overdue/due-today follow the device date, collection aggregates, customer deletion rules, duplicate warning, read-only guard, 60-step randomised consistency check |
| Backup/restore integration | `app/src/test/.../BackupAndPersistenceTests.kt` | restore replaces data exactly (counts, balances, collected, allocations, settings, Arabic text), numbering continues, encrypted backups, invalid/corrupt/incompatible/inconsistent backups rejected **without changing data**, backups contain no license, **data survives closing and re-opening a file-backed database** |
| License manager integration | `app/src/test/.../LicenseManagerTests.kt` | activation, persistence across restart, other-device/forged/garbage rejected and not stored, **expiry -> read-only while data stays readable/exportable**, renewal, clock rollback, lifetime ignores clock, reinstall = new device id, activation request round trip |
| Generator | `license-generator/tests` | issuing, time-zone boundaries, tamper, reissue, history search, key encryption/backup/refusals, repo hygiene, CLI end to end, sample files |
| CI release check | workflow step | release build with a throwaway key succeeds (R8), **no INTERNET permission**, no key files inside the APK |

## Not automated - verify by hand before release
These need a device/emulator or a human eye (see also `docs/RELEASE_CHECKLIST.md`):
* **RTL / Arabic rendering** of every screen and of the PDFs (text shaping, mirrored arrows, number/date placement), French and English too.
  The code uses `AutoMirrored` icons, logical start/end layout, `StaticLayout` with explicit text direction in PDFs, and Unicode isolates around money.
* Tablet layouts (navigation rail, two-pane customers, dashboard 4-across).
* WhatsApp hand-off on a phone with WhatsApp / WhatsApp Business; Storage Access Framework save/open dialogs; print dialog.
* Keystore behaviour on real hardware (Robolectric uses an in-memory secret store).
* Process-kill during a payment on a device (Room/SQLite transactions guarantee atomicity; the file-backed reopen test covers committed data).
* Performance with thousands of records on a low-end phone (queries are indexed and lists are paged; no load test was run).

## Known gaps / roadmap
* No app PIN or biometric lock in v1 (the device screen lock is the protection). A PIN lock needs a recovery design; not shipped half-done.
* Credit balances / overpayments are rejected (must not exceed the outstanding balance).
* A payment made before a later reschedule cannot be reversed (documented in `FINANCIAL_RULES.md`).
* Contract "attachment" is stored as a reference field only; no file picker UI yet.
* Per-currency aggregates: totals only include the business currency.
