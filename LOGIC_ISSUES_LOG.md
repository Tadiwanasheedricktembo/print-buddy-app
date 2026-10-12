# Logic Issues Log (Agent Reference)

**Purpose:** Known business-logic and data-integrity findings for Tadiwa Print Buddy Android app. Use this before changing financial flows, debt UI, analytics, sync, or checkout.

**Last reviewed:** September 28, 2026  
**Review scope:** `PrintDao`, `PrintRepository`, ViewModels, `CartActivity`, `DebtActivity`, `NetworkSyncRepository`, dashboard analytics, related tests.

**Recommended fix order (highest leverage):**
1. Unify “satisfied on order” as `paidAmount = cashPaid + creditUsed` and derive `paymentStatus` from that.
2. Fix `availableCredit` to only allow negative customer balance (store credit), not `abs(balance)`.
3. Make `PrintRepository.updatePayment` overpayment path fully atomic with the order payment path.
4. Align `CartActivity` with `MainViewModel` (credit application + checkout flow).

---

## Critical (money / ledger)

### L-001 — Customer store credit not reflected in `order.paidAmount`

| Field | Detail |
|--------|--------|
| **Location** | `PrintDao.recordOrderAtomic()` — `finalPaidAmount = cashPaid` only |
| **Symptom** | Settlement ledger balance can be correct while orders still show unpaid or partially paid. |
| **Impact** | `getUnpaidOrders()`, `DebtActivity`, `applyPaymentToCustomerIdAtomic` (uses `totalAmount - paidAmount`), order detail “Pay now” UI. |
| **Tests** | `OverpaymentTest.scenario2`, `scenario5` expect `paidAmount` to include credit; implementation often leaves `paidAmount = 0` for `OWES_ME` + credit. |
| **Root cause** | Dual model: authoritative balance = sum of settlement `transactionAmount`; order columns ignore credit applied at order time. |

### L-002 — `availableCredit` treats debt as spendable credit

| Field | Detail |
|--------|--------|
| **Location** | `PrintDao.recordOrderAtomic()` — `availableCredit = previousBalance.abs()` |
| **Symptom** | DAO allows `appliedCredit` up to absolute balance even when customer owes money. |
| **Impact** | UI (`MainViewModel`) only offers credit when `balance < 0`, but repository/DAO bypass can reduce `cashPaid` without real credit. |
| **Fix direction** | `availableCredit = if (previousBalance < 0) previousBalance.negate() else ZERO`. |

### L-003 — Overpayment in `updatePayment` is not one transaction

| Field | Detail |
|--------|--------|
| **Location** | `PrintRepository.updatePayment()` |
| **Symptom** | Order portion uses `recordPaymentWithWalletAtomic`; excess uses separate `insertSettlement` + `rebuildCustomerProjection`. |
| **Impact** | Crash/failure between steps → wallet, settlement, and order out of sync. |

### L-004 — `CartActivity` checkout flow

| Field | Detail |
|--------|--------|
| **Location** | `CartActivity.saveOrderAndShowPayment()` |
| **Behavior** | Always `confirmOrder(..., "OWES_ME")` first, then `updatePayment(orderId, total, "UPI")` after QR. |
| **Issues** | (1) Dismissing QR leaves a credit order on the ledger. (2) Never passes `appliedCredit` (unlike `MainViewModel`). (3) `onFragmentDestroyed` finishes activity when dialog closes. |

---

## High (reporting, UX, integrity)

### L-005 — Dashboard “avg job” mixes metrics

| Field | Detail |
|--------|--------|
| **Location** | `DashboardViewModel.calculateInsights()` |
| **Issue** | `ordersCount` = settled settlement count; `salesVolume` = sum of all active order `totalAmount` in period. Ratio is not a true average job value. |

### L-006 — Two “revenue” definitions

| Field | Detail |
|--------|--------|
| **Locations** | Dashboard: `getSettledRevenueByMethodBetween` (settlements). `PrintRepository.getNetProfit()`: `getSalesRevenueBetween` → `orders.paidAmount`. |
| **Impact** | Net profit / revenue can disagree across screens unless documented and aligned. |

### L-007 — `BusinessEventMapper` groups by `timestamp` only

| Field | Detail |
|--------|--------|
| **Location** | `BusinessEventMapper.map()` |
| **Issue** | ORDER_POST + PAYMENT at same ms (common in `recordOrderAtomic`) merge into one UI event; collisions possible across orders/customers. |

### L-008 — `observeAnalyticsDataChanges` weak refresh signal

| Field | Detail |
|--------|--------|
| **Location** | `PrintDao.observeAnalyticsDataChanges()` |
| **Issue** | Raw `COUNT(*)` without `deletedAt`; soft deletes / in-place edits may not change counts → dashboard may not reload. |

### L-009 — `getTotalReceivablesFlow()` broken (dead code)

| Field | Detail |
|--------|--------|
| **Location** | `PrintRepository.getTotalReceivablesFlow()` |
| **Issue** | Calls suspend `getCustomerBalanceById` inside non-suspend `Flow.map`; sums signed balances (credits net against receivables). Not referenced elsewhere—do not wire up without rewrite (e.g. `mapLatest` + `getCustomerBalanceTotals()`). |

### L-010 — `DebtActivity` total vs authoritative receivables

| Field | Detail |
|--------|--------|
| **Location** | `DebtActivity.updateTotalOwed()` |
| **Issue** | Sums `totalAmount - paidAmount` on unpaid orders only; ignores credit on orders (see L-001); can disagree with settlement-based balance. |

---

## Medium (sync, concurrency, validation)

### L-011 — Sync push: silent stuck outbox

| Field | Detail |
|--------|--------|
| **Location** | `NetworkSyncRepository.pushPendingChanges()` |
| **Issue** | If server response has no matching result for an outbox row, entry is neither deleted nor marked failed. |

### L-012 — Deferred sync items dropped on error

| Field | Detail |
|--------|--------|
| **Location** | `NetworkSyncRepository.processDeferredItems()` |
| **Issue** | Generic `Exception` → `deleteDeferred(item)`; remote events can be lost locally. |

### L-013 — `runBlocking` inside Room transactions

| Field | Detail |
|--------|--------|
| **Location** | `NetworkSyncRepository.pullChanges()`, `applyRemoteEventSync()` |
| **Issue** | `runBlocking` inside `runInTransaction`; fragile threading/ordering. |

### L-014 — `MainViewModel.calculateTotals` race

| Field | Detail |
|--------|--------|
| **Location** | `MainViewModel.calculateTotals()` |
| **Issue** | Cancelled jobs may still complete; balance fetch uses snapshot from job start → stale credit / `balanceToPay` under fast input. |

### L-015 — Payment validation gaps

| Field | Detail |
|--------|--------|
| **Locations** | `MainViewModel.completeOrder()`, `PaymentBottomSheet` |
| **Issues** | UPI without “Paid” returns silently (no error). Cash: no domain check that `receivedAmount >= balanceToPay`. |

### L-016 — `normalizePaymentMethod` maps credit labels to CASH

| Field | Detail |
|--------|--------|
| **Location** | `PrintRepository.normalizePaymentMethod()` |
| **Issue** | `OWES_ME`, `NONE`, `MIXED` → `CASH`; can mis-label analytics/filters if used on persist paths. |

### L-017 — `deleteUpiAccountTransaction` swallows errors

| Field | Detail |
|--------|--------|
| **Location** | `PrintRepository.deleteUpiAccountTransaction()` |
| **Issue** | Empty catch blocks; dual delete (UPI + legacy beauty) may half-complete. |

### L-018 — Restore without projection rebuild

| Field | Detail |
|--------|--------|
| **Location** | `PrintRepository.restoreSettlements()`, `restoreExpenses()` |
| **Issue** | Bulk insert without rebuilding `debtor_credits` / wallet projections. |

---

## Lower / consistency

### L-019 — Tests vs validation rules

| Field | Detail |
|--------|--------|
| **Examples** | `OverpaymentTest` placeholder `CartItem(ZERO, qty 0)` vs `confirmOrder` requiring total > 0. Credit-on-order expectations vs `finalPaidAmount = cashPaid`. |
| **Action** | Reconcile tests with intended product rules when fixing L-001. |

### L-020 — `getCashInHandFlow()` semantics

| Field | Detail |
|--------|--------|
| **Location** | `PrintRepository.getCashInHandFlow()` |
| **Issue** | Sums lifetime `orders.paidAmount`, not period or settlement-based cash. |

### L-021 — Cancel order UPI refund aggregation

| Field | Detail |
|--------|--------|
| **Location** | `PrintRepository.cancelOrder()` — sums UPI-related settlement `amountPaid.abs()` |
| **Action** | Verify no double-refund vs `insertUpiAccountTransactionAtomic` on cancel. |

### L-022 — Sync tests use main-thread Room queries and can hide production failures

| Field | Detail |
|--------|--------|
| **Location** | `app/src/test/java/com/tadiwaprintbuddy/app/data/NetworkSyncRepositoryTest.kt` — `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()` |
| **Symptom** | Sync and transaction ordering issues are easier to pass in test because Room is allowed to execute on the main thread instead of simulating the real worker-thread / transaction timing. |
| **Impact** | `pullChanges()`, outbox replay, and `runBlocking`-inside-transaction behavior can appear stable in tests while still failing under production threading or retry sequencing. |
| **Action** | Keep this as a known validation gap. Prefer thread-safe test patterns and real concurrency coverage before treating sync fixes as verified. |
| **Current evidence** | Last direct command: `./gradlew.bat :app:testDebugUnitTest --tests "com.tadiwaprintbuddy.app.CustomerCreditAnalyticsUiTest"` → exit code 1. This runtime failure reinforces that analytics/sync validation still needs a clean pass before the repository can be considered stable. |

---

## Architecture note (for agents)

The app maintains **two parallel models**:

1. **Settlement ledger** — `getAuthoritativeCustomerBalance()` sums `settlement_history.transactionAmount` (fallback: unpaid order diffs if no settlements).
2. **Order row** — `paidAmount`, `paymentStatus`, used by debt lists, some revenue queries, and payment allocation.

Credit applied at order time is partially recorded in settlements (`ORDER_POST.amountPaid`) but not consistently in `orders.paidAmount`. Most L-00x items stem from this split.

---

## Key files (quick index)

| Area | Files |
|------|--------|
| Order + credit core | `app/.../data/PrintDao.kt` (`recordOrderAtomic`, `applyPaymentToCustomerIdAtomic`) |
| Payment API | `app/.../data/PrintRepository.kt` (`confirmOrder`, `updatePayment`, `normalizePaymentMethod`) |
| Home checkout | `app/.../MainViewModel.kt`, `PaymentBottomSheet.kt` |
| Cart checkout | `app/.../CartActivity.kt` |
| Debt UI | `app/.../DebtActivity.kt` |
| Analytics | `app/.../DashboardViewModel.kt`, `PrintDao` settlement queries |
| History UI | `app/.../data/BusinessEventMapper.kt` |
| Sync | `app/.../data/NetworkSyncRepository.kt`, `SyncWorker.kt` |
| Regression tests | `OverpaymentTest.kt`, `FinancialIntegrityTest.kt`, `DatabaseIntegrityTest.kt` |

---

## Changelog (this log)

| Date | Author | Notes |
|------|--------|--------|
| 2026-09-28 | Code review (agent) | Initial capture of 21 tracked issues L-001–L-021 from senior logic review. |
