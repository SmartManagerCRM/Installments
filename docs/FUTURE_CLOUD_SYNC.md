# How optional cloud synchronization can be added later

The v1 app is offline-only: **no `INTERNET` permission**, no remote SDK. The architecture already separates what a sync layer needs.

## What is already in place
* **UUID primary keys** (`String`) on every business table - no device-local auto-increment ids leak into data.
* `createdAt` / `updatedAt` on mutable records; payments, allocations and audit events are **append-only** (reversal instead of delete).
* Soft states instead of deletion: customers/products are *archived*, contracts *cancelled*, payments *reversed*.
* A reserved `sync_meta(entityType, entityId, lastModified, syncState, remoteId, conflict)` table (unused in v1).
* Idempotency key on payments (`requestKey` UNIQUE): retries after a network failure can never double-post.
* Business logic lives in `core` (pure Kotlin) and in repositories (`LedgerRepository`, ...) behind which the UI has no database knowledge.
* Versioned database schema (`schemas/` exported) and a versioned, checksummed backup format that already serialises every table.

## Suggested design (Supabase/PostgreSQL, but any backend works)
1. **Opt-in**: a Settings switch + explicit sign-in (Supabase Auth); nothing leaves the phone before consent. Add `INTERNET` only in the
   cloud flavor (`productFlavors { offline; cloud }`) so the offline edition stays provably offline.
2. **Tables mirror Room** with the same UUIDs plus `org_id`, `updated_at`, `deleted_at`, `device_id`, `row_version`; Row Level Security by `org_id`.
3. **Transport**: TLS only; push local changes (`updatedAt > lastSyncedAt`) as batches; pull remote changes; store the server cursor in `sync_meta`.
4. **Conflict rules**
   * Customers/products/contracts header fields: last-writer-wins per field with `updatedAt`, losers kept in `sync_meta.conflict` for review.
   * **Payments/allocations/audit are never overwritten**: they are insert-only; the server upserts by UUID and by `requestKey`
     (same key => same payment), so duplicate uploads are harmless. Reversals are separate status events.
   * Receipt/contract numbers: replace the single counter with a per-device prefix (e.g. `R-<deviceCode>-<year>-<seq>`) or server-allocated blocks
     to avoid collisions between devices.
   * Balances stay derived (view), so there is nothing to reconcile numerically - only records.
5. **Licensing**: multi-device use is a different license policy: extend the payload (`maxDevices`, `features: ["sync"]`), keep offline verification,
   and have the server enforce device counts.
6. **Security**: encrypt tokens in the Keystore-backed `SecretStore`; encrypted backups remain the escape hatch.
7. **Audit**: sync events are written to `audit_events` like any other change.

No code in v1 depends on any of this.
