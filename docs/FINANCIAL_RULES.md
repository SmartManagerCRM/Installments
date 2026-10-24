# Financial rules (single source of truth)

All rules below are implemented in the pure-Kotlin `core` module (no Android, no database, no UI) and are covered by unit
tests (`core/src/test/.../FinancialTests.kt`) plus Room integration tests (`app/src/test/.../LedgerTests.kt`).

## Money
* Stored as a **`Long` count of minor units** (TND: 1 dinar = 1000 millimes; SAR: 1 riyal = 100 halalas). No `Double`/`Float`
  anywhere in financial code. `CurrencyInfo(code, decimals)` carries the precision; **TND is not hard-coded**.
* Every contract and payment stores its **currency code**. The app never converts currencies. Dashboard/customer/collection
  aggregates only include records in the *business currency* (Settings); contracts in another currency are shown in their
  own currency in the Contracts tab and are listed as a note on the dashboard.
* User input is parsed strictly (`MoneyMath.parse`): too many decimals, negatives, garbage or ambiguous separators are
  **rejected, never silently rounded**. Arabic-Indic digits are accepted.

## Rounding policy (installment generation)
The only place a division happens is splitting the financed amount into `n` installments.
* The schedule **always sums exactly** to the financed amount.
* `step` = granularity of regular installments (1 minor unit, or one whole currency unit when *Round installments to whole
  currency units* is on - the default).
* `units = total / step`, `leftover = total - units*step` (< 1 step). The units are split evenly; the **first `units mod n`
  installments get one extra step**; the **leftover goes to the last installment**.
  Example (TND, whole dinars): 1,000.000 / 7 -> 143.000 x 6 + 142.000.
* If the step is too coarse for the count (fewer whole units than installments) it falls back to 1 minor unit.
* Custom schedules: the retailer edits dates/amounts; saving requires the total to equal the financed amount exactly.

## Contract totals
`price = sum(quantity x unit price)`; `net = price - discount`; `financed = net - down payment`.
Discount <= price, down payment <= net, financed must be > 0. Item description and price are **snapshots** inside the
contract: editing the product catalog later never changes an existing contract.
The down payment is a real payment (kind `DOWN_PAYMENT`) with its own receipt number, created when the contract is activated.
Drafts have a schedule but no payments and are excluded from every balance.

## Balances are derived, never stored
`paid(installment) = SUM(allocations whose payment is POSTED)`; `outstanding = amount - paid`.
This is the SQL view `installment_balances`. The dashboard, collection center, customer profile and reports all read it,
so they cannot disagree and they are correct after any restart. A reversed payment disappears from the sum automatically.

## Installment status (device local date)
1. `RESCHEDULED` / `CANCELLED` rows are excluded from balances and overdue detection.
2. nothing outstanding -> **Paid**
3. due date < today -> **Overdue** (also when partially paid; the unpaid part is overdue)
4. due date == today -> **Due today**
5. some money paid, not yet due -> **Partially paid**; otherwise **Upcoming**.
`daysOverdue = today - dueDate` (only when something is outstanding). The date is the phone's local date; changing the
phone's date changes the statuses (and is tested). Dashboard "today" refreshes on resume/after midnight.

## Payments and allocation
* Amount must be > 0 and **<= the contract's outstanding balance** (no overpayment/credit balances in v1).
* Default rule (Settings): **oldest due date first** (or *latest first*). The retailer may allocate manually; the lines must
  be > 0, each <= that installment's outstanding, unique, and **sum exactly to the amount received**.
* The wizard shows the allocation and the balances before/after before confirming.
* One payment may cover several installments; several payments may hit one installment (partial payments).
* The contract becomes `COMPLETED` automatically at zero balance and is re-opened by a reversal.

### Double-payment protection (three layers)
1. The payment screen creates an **idempotency key** when it opens; the `payments.requestKey` column is UNIQUE and the
   repository returns the already-posted payment for a repeated key (double tap, retry, process restart).
2. Same contract + same amount within **2 minutes** requires an explicit "record anyway" confirmation.
3. The save button is disabled while the operation runs.

### Transactions
Every financial write (`createContract`, `recordPayment`, `settleContract`, `reversePayment`, `reschedule`, `cancel`) runs in
**one Room transaction**: payment + allocations + receipt counter + contract status + audit event commit together or not at all.
SQLite/Room therefore guarantees consistency if the app is killed or the phone restarts mid-operation.

### Early settlement
Pays the remaining balance in one operation. An optional **discount** is recorded as a separate `ADJUSTMENT` payment (waives the
*latest* installments first, method "Adjustment", no cash). The cash payment clears the rest (oldest first). Adjustments are
**excluded from "collected" reports**.

### Corrections (reversal process)
Payments are **never edited or deleted**. A correction is a *reversal*: status `POSTED -> REVERSED` with date and mandatory
reason, an audit event, balance restored, contract re-opened if needed. To change an amount: reverse, then record the right
payment. A payment cannot be reversed twice. A payment that touched installments that were **later rescheduled** cannot be
reversed (it would corrupt the new schedule); this is reported with an explanation.

## Rescheduling
The unpaid balance gets a new schedule whose total must equal it exactly. Old unpaid rows are kept with state `RESCHEDULED`
(revision preserved), fully paid rows stay, new rows get the next `revision`. The audit trail stores the old and the new
schedule and the reason; `contracts.financedMinor` and the original installments are never rewritten. The contract PDF prints
the **original** schedule and, separately, the current one.

## Audit
`audit_events` (append-only): contract created/draft/cancelled/rescheduled/completed/reopened, payment posted/reversed,
customer created/updated/archived, promise created/outcome. Included in backups.

## Receipt / contract numbers
`C-<year>-<seq 4>` and `R-<year>-<seq 5>` from counters stored in `settings` (restored with a backup, so numbering continues
without duplicates); UNIQUE indexes enforce uniqueness.
