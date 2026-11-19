# Consolidated Issue Register — Tadiwa Print Buddy

This register consolidates all known issues, logic findings, and sync gaps identified in the project documentation (`LOGIC_ISSUES_LOG.md`, `WEB_SYNC_STATUS.md`, `QA_INVESTIGATION.md`).

---

## Critical Financial & Ledger Issues (Phase 1)

| ID | Issue Title | Location / Component | Status | Description & Impact |
|---|---|---|---|---|
| **L-001** | Store credit omitted from `order.paidAmount` | `PrintDao.recordOrderAtomic()` | Still Present | Settlement ledger balance can be correct while orders show unpaid or partially paid, breaking `getUnpaidOrders()` and `DebtActivity`. |
| **L-002** | `availableCredit` treats debt as spendable credit | `PrintDao.recordOrderAtomic()` | Still Present | DAO allows credit usage up to absolute balance even when the customer owes money. |
| **L-003** | Non-atomic overpayment handling | `PrintRepository.updatePayment()` | Still Present | Order payment and customer credit overpayment split across separate steps, risking data drift on crash. |
| **L-004** | Cart checkout flow divergence | `CartActivity.saveOrderAndShowPayment()` | Still Present | Bypasses credit application logic and executes a two-step `OWES_ME`-then-`UPI` flow. |

---

## High Priority Reporting, UX & Integrity Issues (Phase 2)

| ID | Issue Title | Location / Component | Status | Description & Impact |
|---|---|---|---|---|
| **L-005** | Dashboard average job metric distortion | `DashboardViewModel.calculateInsights()` | Still Present | Mixes settled settlement count with active order volume. |
| **L-006** | Dual revenue definitions | Dashboard vs `PrintRepository.getNetProfit()` | Still Present | Settlements vs sales revenue can disagree across screens. |
| **L-007** | Event timestamp collisions | `BusinessEventMapper.map()` | Still Present | Orders and payments at the same millisecond merge into single UI events. |
| **L-008** | Weak analytics refresh trigger | `PrintDao.observeAnalyticsDataChanges()` | Still Present | Raw `COUNT(*)` ignores soft deletes; dashboard may fail to reload. |
| **L-009** | Broken receivables flow | `PrintRepository.getTotalReceivablesFlow()` | Still Present | Dead code calling suspend functions inside non-suspend Flow map. |
| **L-010** | Debt activity total mismatch | `DebtActivity.updateTotalOwed()` | Still Present | Sums unpaid order balance while ignoring credit, disagreeing with settlement balance. |

---

## Sync & Infrastructure Issues (Phase 3 & 4)

| ID | Issue Title | Location / Component | Status | Description & Impact |
|---|---|---|---|---|
| **L-011** | Photo blob synchronization gap | `SyncOutbox` / Photo entities | Still Present | Photos emit to outbox but lack remote storage contract; outbox items must be retained rather than silently dropped. |
| **L-012** | Test thread-masking in sync tests | `NetworkSyncRepositoryTest` | Still Present | Use of `allowMainThreadQueries()` masks thread-order and transaction bugs in production sync. |
| **L-013** | Web sync CRUD wiring | `web/src/lib/sync` | Partial | Foundation exists, but complete CRUD wiring across all business entities needs final integration. |
