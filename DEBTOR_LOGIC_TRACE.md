# Debtor Logic Trace & Financial Consistency Audit

This document is a forensic trace of the debtor lifecycle in the current implementation. It is based on the repository code in [app/src/main/java/com/tadiwaprintbuddy/app/data/PrintRepository.kt](app/src/main/java/com/tadiwaprintbuddy/app/data/PrintRepository.kt), [app/src/main/java/com/tadiwaprintbuddy/app/data/PrintDao.kt](app/src/main/java/com/tadiwaprintbuddy/app/data/PrintDao.kt), [app/src/main/java/com/tadiwaprintbuddy/app/data/SettlementHistory.kt](app/src/main/java/com/tadiwaprintbuddy/app/data/SettlementHistory.kt), [app/src/main/java/com/tadiwaprintbuddy/app/data/Order.kt](app/src/main/java/com/tadiwaprintbuddy/app/data/Order.kt), [app/src/main/java/com/tadiwaprintbuddy/app/data/DebtorCredit.kt](app/src/main/java/com/tadiwaprintbuddy/app/data/DebtorCredit.kt), and the relevant UI entry points such as [app/src/main/java/com/tadiwaprintbuddy/app/CartActivity.kt](app/src/main/java/com/tadiwaprintbuddy/app/CartActivity.kt), [app/src/main/java/com/tadiwaprintbuddy/app/MainViewModel.kt](app/src/main/java/com/tadiwaprintbuddy/app/MainViewModel.kt), and [app/src/main/java/com/tadiwaprintbuddy/app/SettlementHistoryViewModel.kt](app/src/main/java/com/tadiwaprintbuddy/app/SettlementHistoryViewModel.kt).

No application code was modified.

---

## 1. Executive Summary: Single Source of Truth?

Short answer: there is no single authoritative source of truth for customer debt in the current implementation.

The system currently mixes three different debt representations:

1. Order-based debt
   - Derived from active orders using `totalAmount - paidAmount`.
   - Used by the debtor list query and some receivables calculations.

2. Settlement-history balance
   - Derived from settlement entries and the latest `remainingBalance` row.
   - Used by `getCustomerBalanceById()` as the primary runtime balance.

3. Debtor projection cache
   - Stored in `debtor_credits.amount`.
   - Rebuilt by `rebuildCustomerProjection()` after many mutations.

### Conclusion

The closest thing to a runtime authority is the balance returned by `PrintRepository.getCustomerBalanceById()`:

- it reads the latest `settlement_history.remainingBalance` row when one exists;
- otherwise it falls back to the unpaid total from active orders.

However, that is not a true single source of truth because:

- other screens and queries use orders directly;
- the `debtor_credits` table is a projection that can drift;
- the settlement rows themselves contain snapshot values such as `balanceAfter` and `newBalance` that are explicitly marked as UI-only snapshots in the entity model;
- some debtor flows mutate orders but do not recompute the order-level debt fields stored on the order row itself.

### Bottom line

The app currently uses a hybrid model, not a single authoritative ledger. The most likely root cause of the debtor inconsistencies is that different subsystems assume different truths:

- the repository assumes settlement history is the current balance source;
- the debtor screen assumes orders are the truth;
- the debtor credit screen assumes the projection table is the truth.

---

## 2. Full Debtor Lifecycle Trace

### Scenario 1: Customer buys on credit

Flow:

Customer selected -> Order confirmed -> Order saved -> Settlement created? -> Customer balance updated? -> DebtorCredit inserted? -> Dashboard updated? -> Order history updated? -> Debtor list updated?

#### Step-by-step

1. UI flow
   - The customer name is entered in [app/src/main/java/com/tadiwaprintbuddy/app/CartActivity.kt](app/src/main/java/com/tadiwaprintbuddy/app/CartActivity.kt).
   - The order is saved through `PrintRepository.confirmOrder()`.

2. Repository logic
   - `PrintRepository.confirmOrder()` validates cart contents and stock.
   - It creates or reuses a customer via `getOrCreateCustomer()`.
   - It computes:
     - `total`
     - `finalPaidAmount`
     - `transactionAmount = total - finalPaidAmount`
     - `newBalance = previousBalance + transactionAmount`

3. DAO transaction
   - `PrintDao.recordOrderAtomic()` runs in one transaction.
   - It performs these writes:
     - deduct stock with `safeDeductStock()`
     - insert order into `orders`
     - insert order items into `OrderItem`
     - insert a settlement row into `settlement_history` if `order.transactionAmount != 0.0`
     - rebuild the customer projection in `debtor_credits`

4. Tables modified
   - `customers` if the customer is new
   - `orders`
   - `OrderItem`
   - `stock_items` (stock deducted)
   - `settlement_history`
   - `debtor_credits`

5. Screens affected
   - The order history is updated because `orders` changed.
   - The debtor list is updated only when the query is reloaded because it is based on active orders.
   - The settlement history screen is updated when settlement rows are reloaded.
   - The dashboard is not directly updated by this path; it is usually recomputed from queries when the screen reloads.

#### Methods called

- `CartActivity.saveOrderAndShowPayment()`
- `PrintRepository.confirmOrder()`
- `PrintRepository.getOrCreateCustomer()`
- `PrintDao.recordOrderAtomic()`
- `PrintDao.safeDeductStock()`
- `PrintDao.insertOrder()`
- `PrintDao.insertOrderItems()`
- `PrintDao.insertSettlement()`
- `PrintDao.rebuildCustomerProjection()`
- `PrintDao.insertOrUpdateDebtorCredit()`

---

### Scenario 2: Customer pays $20 in cash

#### Flow

Customer selects a debtor -> payment entered -> repository applies payment -> unpaid orders reduced -> settlement row inserted -> debtor projection updated.

#### What changes

1. UI entry
   - `DebtorsActivity.showReceivePaymentDialog()` calls `PrintRepository.applyPaymentToCustomerId()`.

2. Repository and DAO
   - `PrintDao.applyPaymentToCustomerIdAtomic()` loads the customer and current balance.
   - It pulls the unpaid orders for that customer from `getUnpaidOrdersForCustomer()`.
   - It applies the payment to unpaid orders oldest first.
   - It updates each order's `paidAmount` and status.
   - It inserts a settlement row with `transactionAmount = -paymentAmount`.
   - It rebuilds the customer projection.

#### Which rows change

- `orders` rows for all unpaid orders touched by the payment allocation
- `settlement_history` gets one new row
- `debtor_credits` gets one updated row for that customer

#### Which rows never change

- unrelated customer orders
- unrelated settlement rows
- unrelated debtor projection rows
- stock rows, unless the payment flow also triggers a later order action

#### Methods called

- `DebtorsActivity.showReceivePaymentDialog()`
- `PrintRepository.applyPaymentToCustomerId()`
- `PrintDao.applyPaymentToCustomerIdAtomic()`
- `PrintDao.getUnpaidOrdersForCustomer()`
- `PrintDao.updateOrderPaymentStatus()`
- `PrintDao.insertSettlement()`
- `PrintDao.rebuildCustomerProjection()`

---

### Scenario 3: Customer pays through Beauty Account (UPI)

#### Flow

Same as cash payment, but the method is `UPI` and the repository also writes a beauty transaction.

#### Extra write

- `PrintRepository.applyPaymentToCustomerId()` detects `paymentMethod == "UPI"` and calls `insertBeautyTransaction(paymentAmount, "ADD", ...)`.

#### Tables modified

- `orders` (for each touched order)
- `settlement_history`
- `debtor_credits`
- `beauty_transactions`

#### Important detail

Unlike the cash path, this path creates a cross-domain side effect in the beauty account ledger.

---

### Scenario 4: Customer partially pays a debt

Example:

- Debt = $100
- Pays $40
- Remaining = $60

#### How the app handles it

The app does not create an extra debt row for the remainder. Instead, it reduces the existing order's paid amount and derives the remaining balance from the order or the latest settlement balance.

#### What changes

- The order row's `paidAmount` increases.
- The order's `paymentStatus` changes to `PARTIALLY_PAID` or `PAID` if full payment is reached.
- A settlement row is created.
- The debtor projection row is updated.

#### What does not change

- The original order total remains the same.
- The old debt entry is not split into a new row.
- The remaining debt is determined from `totalAmount - paidAmount` for the relevant order, or from the latest settlement balance if the repository chooses that path.

---

### Scenario 5: Customer has multiple debts and pays $50

Example:

- Monday: $40
- Tuesday: $80
- Wednesday: $20
- Payment: $50

#### Actual behavior

The implementation applies payments to unpaid orders in ascending date order.

That is controlled by the DAO query:

- `getUnpaidOrdersForCustomer(customerId)` returns active orders with `paidAmount < totalAmount` ordered by `date ASC`.

#### Result

- The oldest unpaid order is reduced first.
- Once that order is fully paid, the next oldest unpaid order is reduced.
- This is oldest-first allocation, not proportional, newest-first, or random.

#### Exact logic

The relevant logic is in [app/src/main/java/com/tadiwaprintbuddy/app/data/PrintDao.kt](app/src/main/java/com/tadiwaprintbuddy/app/data/PrintDao.kt):

- `getUnpaidOrdersForCustomer(customerId)` returns unpaid orders ordered by `date ASC`.
- `applyPaymentToCustomerIdAtomic()` iterates those orders in order and applies the payment to each until the payment is exhausted.

#### What this means in practice

- A $50 payment against the example debt stack would reduce the Monday debt first, then partially reduce the Tuesday debt.

---

### Scenario 6: Order cancelled

#### Flow

- Cancelled order reverses the outstanding debt amount.
- Stock is restored.
- A settlement row is inserted.
- The debtor projection is rebuilt.
- If the original order was paid through UPI, a beauty return transaction is written.

#### Tables modified

- `orders` status becomes `CANCELLED`
- `stock_items` (restoration)
- `settlement_history`
- `debtor_credits`
- `beauty_transactions` if UPI and paid amount > 0

#### Side effects by domain

- Stock: restored
- Debtor: debt is reversed by `amountToReverse = totalAmount - paidAmount`
- Settlement: one new entry created
- Dashboard: updated only when reloaded from underlying queries
- Beauty: a return transaction inserted if UPI was used
- Analytics: depend on query reloads, not direct event propagation

---

## 3. Balance Fields and Their Meaning

| Field / Column | Meaning | Who updates it | Who reads it | Can it become stale? |
|---|---|---|---|---|
| `orders.totalAmount` | Order grand total | Order creation | Order details, debtor calculations, analytics | No, unless the order is edited or replaced |
| `orders.paidAmount` | Amount paid on that order | Payment updates, order creation | Order details, debtor summaries, receivables | Yes, if a payment is applied but the order row is not updated correctly |
| `orders.previousBalance` | Balance before the order was created | Order creation | Order row only; not used as a trusted source later | Yes, it is a snapshot and is not recalculated on later payments |
| `orders.transactionAmount` | Customer debt created/changed by this order | Order creation | Order row, settlement creation | Yes, it is a snapshot of the original event |
| `orders.newBalance` | Customer balance after the order | Order creation | Order row, settlement creation | Yes, it is a snapshot and not recomputed later |
| `settlement_history.previousBalance` | Previous balance before a settlement event | Settlement insertion | Settlement history screen, repository logic | Yes, it is a snapshot |
| `settlement_history.settledAmount` | Amount paid in that event | Settlement insertion | Settlement history screen | Yes, it is a snapshot |
| `settlement_history.remainingBalance` | Balance after that event | Settlement insertion | `getLatestBalanceForCustomer()` | Yes, it is a snapshot, but the repo uses it as a balance source |
| `settlement_history.transactionAmount` | Delta for that entry | Settlement insertion | Settlement history and ledger display | Yes, it is an event snapshot |
| `settlement_history.newBalance` | Balance after that event | Settlement insertion | UI display | Yes, it is a snapshot |
| `debtor_credits.amount` | Cached customer debt projection | `rebuildCustomerProjection()` | Debtor credit screen | Yes, this is the clearest stale-cache risk |
| `beauty_transactions.newBalance` | Beauty balance after each transaction | Beauty transaction insertion | Beauty account views | Yes, it is a derived balance chain |

---

## 4. Write Matrix

| Action | Orders | Customer | DebtorCredit | Settlement | Beauty | Inventory |
|---|---|---|---|---|---|---|
| New credit order | Insert/Update | Insert if new | Insert/Update | Insert | Nothing | Deduct |
| Additional payment on order | Update | Nothing | Update | Insert | Optional ADD | Nothing |
| Cash payment to customer | Update (touched orders) | Nothing | Update | Insert | Nothing | Nothing |
| UPI payment to customer | Update (touched orders) | Nothing | Update | Insert | Insert ADD | Nothing |
| Partial payment | Update | Nothing | Update | Insert | Nothing | Nothing |
| Cancellation | Update | Nothing | Update | Insert | Optional RETURN | Restore |
| Manual debtor adjustment | Nothing | Nothing | Update | Insert | Nothing | Nothing |
| Customer deletion | Delete/clear | Delete | Delete | Delete | Nothing | Nothing |

---

## 5. Read Matrix by Screen

| Screen | DAO query / source | Value shown |
|---|---|---|
| Dashboard | `getTotalRevenueFlow()`, `getTotalOrdersFlow()`, `getCashInHandFlow()`, `getTotalReceivablesFlow()` | Revenue, orders, cash, receivables |
| Debtors | `getDebtors()` | Customer-level debt derived from active orders |
| Customer Ledger | `getAllSettlements()` | Settlement history rows |
| Orders | `getAllOrders()` / `getOrderById()` | Order list and order details |
| Order Details | `getOrderById()`, `getItemsForOrder()` | Order details and line items |
| Insights | `getRevenueBreakdownBetween()`, `getPaymentBreakdownBetween()`, `getServiceBreakdownBetween()` | Revenue breakdowns |
| Beauty | `getAllBeautyTransactions()`, `getCurrentBeautyBalance()` | Beauty ledger and balance |
| Settlement History | `getAllSettlements()` | Settlement history grouped by customer |

---

## 6. Conflicting Calculations

### 6.1 Debtor balance from orders vs settlement history

- The debtor list uses `getDebtors()` which sums `totalAmount - paidAmount` from `orders`.
- `getCustomerBalanceById()` uses the latest `settlement_history.remainingBalance` when present, otherwise it falls back to unpaid orders.

This means the same customer can show different debt depending on which screen or path is used.

### 6.2 Debtor projection vs live order state

- `debtor_credits.amount` is rebuilt from the current balance.
- Screens that read `debtor_credits` are not guaranteed to match the order-based debtor query.

### 6.3 Snapshot fields vs derived values

- Settlement rows store `balanceBefore`, `balanceAfter`, `transactionAmount`, and `newBalance` as snapshots.
- The repository still uses a later settlement row to compute balance for the customer.

### 6.4 Revenue concepts are not aligned

- Revenue from paid orders is computed from `orders.paidAmount`.
- Settlement-based debt revenue uses settlement history entries of type `PAYMENT`.
- These are related but not identical and can diverge when payments are applied to existing orders or adjustments are introduced.

---

## 7. Duplicated Business Logic

Several calculations are implemented in more than one place:

- Remaining debt is calculated in multiple forms:
  - `totalAmount - paidAmount` in orders
  - latest settlement `remainingBalance`
  - `debtor_credits.amount`

- Payment application is implemented in two ways:
  - `updatePayment()` for an existing order
  - `applyPaymentToCustomerIdAtomic()` for customer-level payment application

- Balance calculation uses multiple sources:
  - repository-level `getCustomerBalanceById()`
  - DAO-level query for unpaid totals
  - view layer aggregation in activities and view models

- Cash and receivables are derived in multiple places:
  - `getCashInHandFlow()`
  - `getTotalReceivablesFlow()`
  - `getTotalReceivables()`

---

## 8. Stale Cached Values

The clearest stale-value risk is `debtor_credits.amount`.

### Why it can drift

It is written by `rebuildCustomerProjection()` after some operations, but it is not the source of truth for every calculation. If one write succeeds but another fails, the projection can become out of sync.

### Other stale values

- `orders.previousBalance`, `orders.transactionAmount`, and `orders.newBalance` are set when the order is created and are not recomputed for later payments.
- `settlement_history.balanceAfter` and `settlement_history.newBalance` are snapshot fields.

### Failure mode

If a settlement insert succeeds but the projection rebuild fails, the app can end with a new settlement row and an old debtor projection value.

---

## 9. Simulated Dataset Walkthrough

### Initial customer

Customer: John

### Step 1: Order A $40 on credit

- `orders` row inserted with `totalAmount = 40`, `paidAmount = 0`, `paymentStatus = UNPAID`
- `settlement_history` row inserted with `transactionAmount = 40`
- `debtor_credits.amount = 40`

### Step 2: Order B $60 on credit

- `orders` row inserted with `totalAmount = 60`, `paidAmount = 0`, `paymentStatus = UNPAID`
- `settlement_history` row inserted with `transactionAmount = 60`
- `debtor_credits.amount = 100`

### Step 3: Customer pays $50 in cash

- Payment is applied oldest-first.
- Order A is paid in full first.
- Remaining $10 is applied to Order B.
- `orders` state becomes:
  - Order A: `paidAmount = 40`
  - Order B: `paidAmount = 10`
- `settlement_history` gets a payment row.
- `debtor_credits.amount` becomes roughly $50 if reconstructed from the latest balance.

### Step 4: Customer pays $20 through UPI

- Payment is applied to the oldest unpaid order still in the queue.
- Order B receives the $20 payment.
- `orders` state becomes:
  - Order A: `paidAmount = 40`
  - Order B: `paidAmount = 30`
- `beauty_transactions` gets an ADD row.
- `debtor_credits.amount` is updated.

### Step 5: Order C $30 on credit

- New order inserted.
- `settlement_history` gets a new order row.
- `debtor_credits.amount` becomes $30 plus the remaining prior balance depending on the latest balance calculation path.

### Step 6: Cancel Order B

- Outstanding amount reversed is `60 - 30 = 30`.
- The balance is adjusted by that amount.
- `orders` row for Order B becomes `CANCELLED`.
- `settlement_history` gets a cancellation row.
- `debtor_credits.amount` is rebuilt.

### Expected database state after the full sequence

At the end of this sequence, the app should conceptually show:

- Order A fully paid
- Order B cancelled with remaining debt reversed
- Order C still outstanding if it was never paid
- The customer debt should be equal to the active outstanding order balance, not the old stale snapshot values

But because the app uses multiple representations, this is exactly where drift can occur.

---

## 10. Impossible States That the Current Code Can Allow

### 10.1 Customer balance negative

Possible because payments can exceed the current balance and there is no clamp.

### 10.2 Paid amount greater than total amount

Possible in the order payment update path because `newPaidAmount` is accepted without forcing it to be less than or equal to the order total.

### 10.3 Settlement exists without an order or customer

Possible in principle because settlement rows are inserted from several paths and the current code does not enforce a strict link to a valid order or customer in every case.

### 10.4 Order deleted but settlement remains

Possible because cancellation uses a settlement entry, and deletion is handled as a separate step.

### 10.5 Beauty transaction without a corresponding payment

Possible because UPI payment is treated as a side effect, but the code does not enforce that the beauty transaction is always matched to a reconciliation event.

### 10.6 Outstanding debt below zero

Possible if a payment or reversal over-applies and no clamp is used.

### 10.7 Debtor projection row exists without a matching customer balance

Possible because projection rebuild is a side effect and may fail or be skipped in some paths.

---

## 11. Invariant Verification

### Invariant 1: Customer balance equals unpaid debts

Status: not guaranteed.

Reason:

- one path uses settlement-history balance;
- another uses orders;
- another uses the debtor projection.

### Invariant 2: Inventory equals physical stock

Status: mostly intended, but not guaranteed under partial failure.

Reason:

- stock deduction and restoration happen inside database transactions, but a failure can still leave a partially applied state if the transaction aborts after some side effects.

### Invariant 3: Revenue equals collected money

Status: not guaranteed.

Reason:

- revenue calculations use orders and settlement history differently.
- debt settlements and direct payments are not always represented in the same way.

### Invariant 4: Cash drawer equals cash received minus cash expenses

Status: not guaranteed.

Reason:

- the cash logic is based on orders with `paymentMethod = CASH` and does not fully account for payment-method mix or other ledger sources.

### Invariant 5: Beauty balance equals beauty transactions

Status: likely closer to true, but still indirect and dependent on transaction correctness.

### Invariant 6: Dashboard totals equal database totals

Status: not guaranteed.

Reason:

- some totals are derived from active orders only, others from settlement history, and still others from projection rows.

---

## 12. Logic Dependency Graph

Customer debt
  -> `PrintRepository.confirmOrder()`
  -> `PrintDao.recordOrderAtomic()`
  -> `orders`
  -> `settlement_history`
  -> `debtor_credits`
  -> `DebtorsActivity` / `DebtorCreditActivity` / `SettlementHistoryActivity`

Customer payment
  -> `PrintRepository.applyPaymentToCustomerId()`
  -> `PrintDao.applyPaymentToCustomerIdAtomic()`
  -> `orders`
  -> `settlement_history`
  -> `debtor_credits`
  -> `beauty_transactions` (for UPI)

Order cancellation
  -> `PrintRepository.cancelOrder()`
  -> `PrintDao.cancelOrderAtomic()`
  -> `stock_items`
  -> `orders`
  -> `settlement_history`
  -> `debtor_credits`

---

## 13. Contradiction Report

| Expected rule | Actual implementation | Risk | Severity | Recommended correction |
|---|---|---|---|---|
| One debtor balance source of truth | Current code uses orders, settlement history, and debtor credits | Different screens can disagree | High | Consolidate all debtor reads to one authoritative ledger |
| Settlement rows should be true snapshots of balance state | The code uses them as runtime balance input | Balance can drift if snapshots are wrong | High | Derive balances from a single source and treat snapshots as read-only history |
| Debtor projection should be a cache, not a source of truth | It is written and read as if it were a real balance table | Projection drift is possible | High | Make the projection a derived view rather than a stored truth |
| Payments should not create negative or impossible balances | The code can accept over-payments and does not clamp values | Negative debt and overpayment states | High | Clamp applied amounts and validate against totals |
| Payment allocation should be deterministic | The implementation is deterministic but different screens use different calculations | Confusing screen behavior | Medium | Standardize allocation rules and expose the same formula everywhere |
| Cancellation should reverse only the outstanding debt | The current logic does this for the order amount, but the balance is then recomputed from several sources | Reversal can appear inconsistent | Medium | Recompute from a single canonical balance after each reversal |

---

## 14. Recommended Architectural Corrections

The important part of this trace is not the exact UI behavior; it is the architecture. The recommended correction is to establish one canonical debtor ledger and make every other representation derived from it.

### Best-practice model

- Keep one authoritative table or derived view for customer debt.
- Make `orders`, `settlement_history`, and `debtor_credits` all feed from that ledger.
- Treat settlement rows as immutable history, not as a second balance engine.
- Recompute balances from the canonical ledger on every read.
- Avoid storing duplicate balance snapshots on orders and settlement rows unless they are clearly marked as historical snapshots.

### In practical terms

- Use a single debt ledger table or a single aggregated balance query.
- Make the debtor screen, settlement history, and debtor credit screen all read the same source.
- Rebuild projections only from the canonical source after a mutation succeeds.

---

## 15. Final Conclusion

The debtor system is currently a mixed model with no single source of truth.

The app behaves as if the system has three truths at once:

- order-based debt,
- settlement-history balance,
- debtor projection cache.

That is the main reason the debtor logic feels inconsistent. The issue is not one isolated bug; it is an architectural split between multiple debt representations.
