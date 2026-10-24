# Architecture

```
┌────────────────────────────── app (Android) ──────────────────────────────┐
│ ui/            Compose screens + ViewModels (Flow/StateFlow), navigation,  │
│                theme, components, localisation via string resources        │
│ data/repo/     LedgerRepository (contracts, payments, reversal, reschedule)│
│                CustomerRepository, ProductRepository, CollectionRepository │
│                SettingsRepository, LicenseManager, BackupService,           │
│                ReportBuilder, DemoData  ← all writes go through WriteGuard  │
│ data/db/       Room: entities, DAOs, DatabaseView `installment_balances`    │
│ pdf/           PdfDoc (paginating writer) + PdfService (contract, receipt,  │
│                statement, reports, share, print)                            │
└───────────────────────────────┬────────────────────────────────────────────┘
                                │ depends on
┌──────────────────────── core (pure Kotlin/JVM) ───────────────────────────┐
│ money/  schedule/  payments/  contracts/  collection/  reminders/          │
│ licensing/ (verify, device id, activation request)   backup/ (container)   │
│ No Android, no Room, no UI → deterministic and unit-testable on any JVM    │
└────────────────────────────────────────────────────────────────────────────┘
license-generator/ (Python) — separate tool; shares only the documented license file format
```

## Principles
* **Financial logic is independent of UI and database.** `core` computes schedules, allocations, statuses and classifications; the
  repositories load fresh rows inside a transaction, call `core`, and persist the result. UI never calculates money.
* **Truth is in the records.** Balances are a SQL view over installments and posted allocations; nothing cached.
* **One transaction per financial operation**, append-only history, reversal instead of delete, audit events.
* **Write guard**: repositories call `WriteGuard.check()` first; the license manager makes it throw when the license does not allow
  changes. Read, export, backup and restore are never blocked → expiry cannot lock customers out of their data.
* **Manual DI** (`AppContainer`) — tests build the same container with an in-memory database, a fixed clock and an in-memory secret store.
* **Time is injected** (`AppClock`) so overdue logic and license expiry are testable; "today" is the device's local date.
* **Localisation**: every user-visible string is in `strings.xml` (ar/fr/en generated from `tools/strings_data.py`); layouts use
  start/end semantics and `AutoMirrored` icons; PDFs set the text direction explicitly.
* **Adaptive UI**: `WindowSizeClass` → bottom bar (compact) or navigation rail (medium/expanded); customers become list + detail panes;
  content is width-limited on tablets instead of stretched.

## Data model (Room v1, `app/schemas/` is exported)
`customers, products, contracts, contract_items, installments, payments, payment_allocations, promises, reminders, settings,
audit_events, license (device-bound, not backed up), sync_meta (reserved)` + view `installment_balances`.
UUID text primary keys, epoch-millis timestamps (UTC), epoch-day dates, money as `Long` minor units + currency code.
Indexes: customer name/phone/ref, contract number (unique)/customer/status/date, installment contract/due date/state, payment receipt
(unique)/requestKey (unique)/contract/customer/paidAt/status, allocation payment/installment, promise customer/outcome/date.

## Migrations
`AppDatabase.MIGRATIONS` is where every schema bump must add a `Migration`; `fallbackToDestructiveMigration` is never used.
When bumping `VERSION`: add the migration, keep the exported schema JSON, add a `MigrationTestHelper` test, bump the backup
`dbSchemaVersion` (older backups are still accepted; newer ones are rejected by both `inspect` and `restore`).
