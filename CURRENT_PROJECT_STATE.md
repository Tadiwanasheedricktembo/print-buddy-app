# Tadiwa Print Buddy - Project State Log

**Last Updated:** September 28, 2026  
**Audience:** Developers and AI agents resuming work on this repo.

---

## 1. PROJECT METADATA

- **Branch:** (check `git branch` at session start)
- **Database version:** 41 (`AppDatabase.kt`)
- **JVM toolchain:** See `app/build.gradle.kts` / `libs.versions.toml`

---

## 2. AGENT START HERE

| Priority | Document | Use when |
|----------|----------|----------|
| **1** | **[LOGIC_ISSUES_LOG.md](LOGIC_ISSUES_LOG.md)** | Fixing money, debt, orders, credit, dashboard metrics, cart checkout, or sync |
| 2 | [BUSINESS_LOGIC_AUDIT.md](BUSINESS_LOGIC_AUDIT.md) | Understanding intended flows (may lag code; cross-check with logic log) |
| 3 | [MIGRATION_INVENTORY.md](MIGRATION_INVENTORY.md) / [MIGRATION_CHANGELOG.md](MIGRATION_CHANGELOG.md) | Schema / migration work |
| 4 | [RECOVERY_RUNBOOK.md](RECOVERY_RUNBOOK.md) | Data recovery |
| 5 | [WEB_SYNC_STATUS.md](WEB_SYNC_STATUS.md) | Cloud sync / web layer |

**Do not assume** “financial logic is fully verified” from older notes below without reading **LOGIC_ISSUES_LOG.md** (open issues L-001–L-021 as of 2026-09-28).

---

## 3. RECENT FINANCIAL LOGIC FIXES (HISTORICAL — PRE–LOG REVIEW)

These were documented as resolved earlier in 2025; they remain relevant context but do not supersede the logic issues log.

### A. Metric consistency (ledger-driven revenue)

- Revenue and order counts aligned toward **settlement ledger** timing where implemented.

### B. UPI sync & mixed payments

- DAO filtering extended for UPI / mixed payment identification.

### C. Manual UPI vs sales revenue

- Sales revenue excludes manual wallet top-ups; UPI inflow includes them; dashboard bindings for inflow/outflow.

### D. History / settlement UI

- Settlement history filters; `BusinessEventMapper` wallet labeling.

---

## 4. TEST STATUS (POINT-IN-TIME)

**Suite (reference):** `com.tadiwaprintbuddy.app.data.FinancialIntegrityTest`  
Historical note: 7 passed at time of prior session.

**Also run when touching ledger:**

- `OverpaymentTest.kt` — credit / overpayment (expectations may conflict with L-001; see logic log)
- `DatabaseIntegrityTest.kt` — customer normalization, payment allocation
- `UPIBehaviorTest.kt`, `OrderValidationTest.kt`

Command: `./gradlew test`

---

## 5. OPEN WORK / NEXT AGENT PRIORITIES

1. **Financial dual-model (L-001, L-002):** Align `orders.paidAmount` / status with credit applied at order time; fix `availableCredit` validation in `PrintDao.recordOrderAtomic`.
2. **Cart path (L-004):** Match `MainViewModel` credit behavior; reconsider OWES_ME-then-UPI two-step flow.
3. **Atomic overpayment (L-003):** Single transaction for order payment + customer credit from overpay.
4. **Analytics (L-005–L-008):** Dashboard averages, refresh trigger, mapper grouping.
5. **Sync hardening (L-011–L-013):** Outbox ack handling, deferred retry policy, avoid `runBlocking` in transactions.
6. **Test fidelity / sync regression coverage (L-022):** `NetworkSyncRepositoryTest` uses `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`, which masks threading and transaction-order bugs that can appear in production sync paths. Treat sync test pass as partial validation until worker-thread / real-transaction coverage is added.
7. **Current verification note:** The last direct validation command was `./gradlew.bat :app:testDebugUnitTest --tests "com.tadiwaprintbuddy.app.CustomerCreditAnalyticsUiTest"`, which exited with code 1. This is a reminder that current analytics/sync coverage is not yet “green,” and the `allowMainThreadQueries()` test pattern is a known false-confidence risk.

Full detail and file index: **[LOGIC_ISSUES_LOG.md](LOGIC_ISSUES_LOG.md)**.

---

## 6. FILES OF INTEREST (FINANCIAL CORE)

- `app/src/main/java/com/tadiwaprintbuddy/app/data/PrintDao.kt`
- `app/src/main/java/com/tadiwaprintbuddy/app/data/PrintRepository.kt`
- `app/src/main/java/com/tadiwaprintbuddy/app/MainViewModel.kt`
- `app/src/main/java/com/tadiwaprintbuddy/app/CartActivity.kt`
- `app/src/main/java/com/tadiwaprintbuddy/app/DashboardViewModel.kt`
- `app/src/main/java/com/tadiwaprintbuddy/app/data/NetworkSyncRepository.kt`
- `app/src/test/java/com/tadiwaprintbuddy/app/data/OverpaymentTest.kt`

---

## 7. SESSION CHANGELOG

| Date | Summary |
|------|---------|
| 2026-09-28 | Added **LOGIC_ISSUES_LOG.md** (L-001–L-021). Refreshed this state log; flagged open financial logic work for agents. Added sync-test fidelity note for `NetworkSyncRepositoryTest` (`allowMainThreadQueries` masks thread-order bugs). Recorded the recent failed analytics/sync validation command: `./gradlew.bat :app:testDebugUnitTest --tests "com.tadiwaprintbuddy.app.CustomerCreditAnalyticsUiTest"` exited with code 1. |
| 2025-02-24 | Prior entry: ledger metrics, UPI dashboard, `FinancialIntegrityTest` (7 passed). |
