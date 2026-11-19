# Project Completion Roadmap — Tadiwa Print Buddy

This roadmap prioritises the steps required to transition Tadiwa Print Buddy from its current state to a fully verified, production-ready application.

---

## Phase 1 — Critical Correctness (Financial Integrity & Data Safety)
*Focus: Eliminate financial drift, incorrect credit calculation, and non-atomic mutations.*
1. **Fix Store Credit & `paidAmount` (L-001):** Ensure `recordOrderAtomic` and related methods correctly factor applied customer store credit into `order.paidAmount`.
2. **Correct `availableCredit` Logic (L-002):** Restrict credit application strictly to negative customer balances (store credit) rather than absolute debt balances.
3. **Atomic Overpayment Processing (L-003):** Wrap order payment and excess credit allocation in a single Room transaction within `PrintRepository.updatePayment()`.
4. **Data Integrity & Migration Checks:** Verify migration robustness across all versions up to v46.

## Phase 2 — Core Business Functionality (Workflows & UI Consistency)
*Focus: Unify checkout, reporting metrics, and debtor views.*
1. **Unify Cart Checkout Flow (L-004):** Align `CartActivity` with `MainViewModel` for unified credit application and atomic payment handling.
2. **Align Dashboard & Ledger Metrics (L-005, L-006):** Standardize revenue and average job calculations between settlement ledgers and sales revenue.
3. **Fix Event Mappings & Analytics Triggers (L-007, L-008):** Update `BusinessEventMapper` and `observeAnalyticsDataChanges` to handle soft deletes and prevent timestamp collisions.
4. **Clean up Dead Code (L-009):** Remove or properly rewrite `getTotalReceivablesFlow()`.

## Phase 3 — Backend and Synchronisation
*Focus: End-to-end sync reliability, outbox processing, and conflict resolution.*
1. **Hardened Outbox & Photo Handling (L-011):** Implement explicit handling for photo sync payloads without silent drop failures.
2. **End-to-End Android-Cloud Sync Verification:** Execute live sync tests against a dedicated deployed test environment with a test account.
3. **Retry & Authentication Hardening:** Verify token expiration handling (HTTP 401 propagation and worker pauses).

## Phase 4 — Web Dashboard
*Focus: Complete web workflows operating on actual backend data.*
1. **Wire CRUD Workflows:** Connect the established web sync client and types to live dashboard data views.
2. **Dashboard Verification:** Validate that web actions correctly persist to backend PostgreSQL storage and reflect in subsequent sync pulls.

## Phase 5 — Testing and Production Readiness
*Focus: Comprehensive test coverage without thread-masking, backup/restore verification.*
1. **Remove Thread-Masking in Tests (L-012):** Refactor `NetworkSyncRepositoryTest` and related tests to run without `allowMainThreadQueries()`.
2. **Regression Suite Execution:** Run complete test suites (`./gradlew test` and backend `pytest`) and achieve a clean green pass.
3. **Backup & Restore Verification:** Validate local backup creation and restoration workflows.
