# Tadiwa Print Buddy — Web Sync Status

This is a living document that tracks the architectural design and implementation progress of the Web Synchronization layer for Tadiwa Print Buddy.

---

## A. Current Project Status
- **Android Application**: CONFIRMED. Local-first print business system with business logic, debt tracking, payment flows, and local financial integrity in place.
- **Cloud Backend**: Backend code and historical PostgreSQL smoke-test notes are present. The current local configuration points to loopback PostgreSQL; this audit did not connect to it or verify a deployed test environment.
- **Web Application**: A historical browser-style login/pull/push smoke test is documented below; it is not evidence of Android synchronization and was not rerun in this audit.
- **Android Synchronization**: Push, pull, worker, and authentication implementations exist. Android-to-backend synchronization has **NOT BEEN LIVE VERIFIED**. Current Phase 2 status is **PARTIAL — NOT COMPLETE**.

### Current synchronization audit (2026-10-10)
- Android implements sync for customers, orders, order items, settlements, expenses, stock, notes, legacy beauty transactions, UPI account transactions, external ledger entries, and printer references.
- `PHOTO` is emitted to the Android outbox but is not a supported remote entity. Photo bytes and the device-local `filePath` have no remote storage contract; Android now fails and retains that outbox item rather than silently deleting it.
- Android has Retrofit auth repository methods for register/login but no login/register UI integration was found in the app source.
- Backend push is per-event partial success for invalid/unsupported events. A database failure rolls back the whole submitted batch and returns `BATCH_ERROR`; retrying the same idempotency keys is safe.
- Push acknowledgements include the idempotency key so Android removes only precisely acknowledged outbox entries. Missing acknowledgements leave entries pending.
- Android credentials are encrypted and the local database is bound to the first cloud username that signs in; signing in as another username is refused to avoid uploading the same local records under a different tenant. Legacy saved tokens are resolved through `/auth/me` before sync or account switching.
- HTTP 401 clears the saved token. The worker stops that run instead of retrying an expired token. Transient transport/server failures remain eligible for WorkManager retry.
- The backend uses SQLAlchemy with a PostgreSQL `DATABASE_URL`; no Supabase SDK/client integration was found. Supabase environment variables in the example are optional placeholders, not evidence of integration.
- Alembic is configured to load `backend/migrations/`, which currently has no revision files. A separate untracked revision under `backend/alembic/versions/` is outside that path and contains an unresolved `down_revision`; `alembic heads` produced no revision head. Schema deployment through Alembic is therefore **NOT VERIFIED / NOT READY**.
- No deployment manifest or Supabase credentials were found in the inspected project configuration. The configured backend database and Android debug URL are loopback/local; neither is evidence of a safe deployed test target.
- No live Android verification was attempted because a dedicated test account/database and a safe deployed endpoint were not established.
- Safe backend regression command: `.\.venv311\Scripts\python.exe -m pytest tests\test_sync.py tests\test_sync_hardening.py tests\test_precision.py tests\test_isolation.py -q` from `backend/` — **18 passed** on 2026-10-10. The PostgreSQL integration test that drops/recreates a local database was deliberately not run.
- Android unit-test sources compiled with `.\gradlew.bat :app:compileDebugUnitTestKotlin`. Nine exact-name Android regression tests passed: seven selected `NetworkSyncRepositoryTest` cases and both `AuthRepositoryTest` cases. A broader class-wide run did not complete: it stalled in the existing `test pull settlement links to local customer and order` case while waiting on Room's SQLite connection pool; the test worker was stopped after 16m28s and the run is not counted as passing. Android live sync remains **NOT VERIFIED**.

### Historical runtime validation notes (2026-09-16; not a current Android verification)
- [x] PostgreSQL 17 runtime installed and the service is running.
- [x] Project database created and reachable over localhost:5432.
- [x] Python 3.11 backend environment restored and dependencies installed.
- [x] Backend config fixed to read the real environment values correctly from the project env file.
- [x] PostgreSQL schema issues fixed: invalid foreign-key references removed and ORM relationship corrected.
- [x] Real backend startup succeeded on a clean live port.
- [x] Real register/login endpoint validation succeeded over HTTP.
- [x] Real sync push validation succeeded against PostgreSQL-backed storage.
- [x] Final live pull verification completed successfully on the real backend.
- [x] Live browser-style login + pull + push smoke test passed with a subsequent pull-confirmation.

---

## PRE-LIVE AUDIT
- Pull endpoint: POST /api/v1/sync/pull via the browser helper in `web/src/lib/sync/client.ts`.
- Push endpoint: POST /api/v1/sync/push via the same client wrapper.
- Auth mechanism: JWT bearer token created by FastAPI login and validated in `backend/app/auth/router.py` with OAuth2PasswordBearer and a username-sub claim.
- Token storage: browser `localStorage` key `tadiwa_auth_token` via `web/src/lib/auth.ts`.
- Token attachment: request `Authorization: Bearer <token>` added in `web/src/lib/api.ts` `fetchWithAuth`.
- HTTP 401 handling: the frontend redirects to /login when `response.status === 401`; it does not surface a sync-specific failure mode beyond the redirect.
- Push body: JSON object `{ events: [...] }` where each event includes `entity_type`, `entity_sync_id`, `operation`, `data`, `timestamp`, `idempotency_key` and optional server metadata.
- Push response contract: backend returns `{ results: [{ sync_id, status, message? }] }` per `backend/app/schemas/sync.py` and `backend/app/sync/router.py`.
- Pull cursor: composite cursor using `last_sync_timestamp` and `last_sync_id`, with global ordering by `(server_updated_at, id)`.
- Deletions: represented as `operation: "DELETE"` with `deleted_at` metadata maintained server-side and included in the bucketed records.
- Retry handling: the browser has an outbox queue and flush logic under `web/src/lib/sync`, but the current diagnostics runner calls `pullSync`/`pushSync` directly and does not verify persisted backend state beyond the HTTP response.
- Idempotency: backend deduplicates by `(user_id, idempotency_key)` via `IdempotencyLog` before processing a push event; if an event is already processed, it returns `SUCCESS` with "Already processed". This is user-scoped and server-side.
- Authenticated user/tenant resolution: the backend resolves the user from the JWT subject (`sub`) and restricts data using `model_class.user_id == current_user.id` in the sync queries and persistence logic.
- Browser trusted identifier risk: the browser does not send an explicit user/tenant field to the backend beyond the JWT; the backend trusts the JWT-derived identity, not any browser-supplied user identifier.
- Current diagnostics flow: `runSyncDiagnostics()` performs a pull request and then creates a demo customer event, pushes it, and marks it pass if the backend returns `SUCCESS`, `SERVER_WINS`, or `ALREADY_PROCESSED`. It does not independently verify that the record was persisted and returned in a subsequent pull.

---

## B. Architecture (Forensic Hardened)

### 1. Android (Local-First)
- **Persistence**: Room Database (v36). Monetary values stored as `TEXT` to prevent floating-point drift.
- **Sync Foundation**: 
    - **SyncOutbox**: Atomic mutation + outbox logging in Room transactions.
    - **Global Identifiers**: UUID-based `syncId` for all entities.
    - **Conflict Resolution**: "Server Wins" using authoritative entity `updatedAt` timestamps. Older updates are ignored for both active and deleted records.
    - **Causal Consistency**: Multi-pass dependency resolution ensures children (e.g. OrderItems) are correctly linked to parents (e.g. Orders) even when batches arrive out of order.

### 2. Cloud Infrastructure
- **Stack**: FastAPI + PostgreSQL (Numeric(10,2) for money).
- **Security**: JWT-based multi-tenant isolation.
- **Pull Cursor**: Gapless cursor management ensures no records are skipped when multiple items share a microsecond timestamp.

---

## C. Implemented features (Verified by Tests)
- [x] **POS / Order Creation**: Atomic stock deduction and dual-entry ledger posting.
- [x] **Financial Integrity**: Kotlin-side summation of `BigDecimal` for all analytics to avoid SQL `SUM(REAL)` inaccuracies.
- [x] **Dependency Resolution**: Multi-pass pull processing handles out-of-order parent/child arrival.
- [x] **Tombstone Sync**: Deletions synchronized for ALL 11 entities (Orders, Customers, Settlements, etc.). Stale updates cannot resurrect deleted records.

---

## D. Path to FULL WEB SYNC — Forensic Verification Results

### Phase 1 — Sync Infrastructure (IMPLEMENTED; historical status)
- Atomic outbox logging implemented for all 11 entities.
- `updatedAt` field enforced for deterministic conflict resolution.

### Phase 2 — Cloud Foundation (IMPLEMENTED, NOT END-TO-END VERIFIED)
- Backend tenant isolation and idempotency have focused automated tests.
- Android networking/auth/sync code is implemented and has local tests.
- Backend/browser historical tests do not prove that the Android app synchronized to a deployed environment.
- Remaining gate: resolve photo blob synchronization or formally exclude it from cloud sync, run the relevant automated suites, and perform a safe Android sync against a dedicated deployed test environment.

### Phase 3 — Basic Synchronization (IMPLEMENTED; historical status)
- Pull logic hardened with multi-pass dependency resolution.
- Cursor management uses globally ordered `server_updated_at`.

### Phase 4 — Financial Synchronization (IMPLEMENTED; historical status)
- `SettlementHistory` duplication prevented via syncId uniqueness.
- All monetary fields use String-based transport for 100% precision.

### Phase 5 — Web Frontend (PARTIAL - VERIFIED FOUNDATION)
- Dashboard uses authoritative ledger for revenue tracking.
- Financial formatting avoids `parseFloat` for core display.
- Added a standalone web sync layer under `web/src/lib/sync` with types, storage, API client, outbox queue, merge logic, and lifecycle hook.
- Added a minimal dashboard-level sync status integration without altering current dashboard behavior.
- Verified the web app still builds successfully after the sync foundation was introduced.

---

## E. Web Sync Implementation Log

### 2026-09-16
- [x] Added dedicated sync module under `web/src/lib/sync`.
- [x] Implemented backend-aligned sync event types and cursor storage.
- [x] Implemented authenticated pull/push client for `/api/v1/sync/*`.
- [x] Implemented outbox queue and retry-safe flush logic.
- [x] Implemented merge logic with server-wins semantics.
- [x] Added a minimal lifecycle hook for sync polling and status reporting.
- [x] Integrated a non-invasive sync status banner into the dashboard layout.
- [x] Added a dashboard-triggered sync smoke action that runs the real backend pull call.
- [x] Added a dedicated dashboard sync test page to push a generated customer sync event through the backend.
- [x] Added a diagnostics action to validate both pull and push against the backend in one step.
- [x] Verified the Next.js app compiles successfully after the foundation changes.
- [x] PostgreSQL 17 runtime was installed and the database service was restored.
- [x] The backend was moved from a broken/incomplete runtime to a valid PostgreSQL-backed runtime.
- [x] Real backend config and schema issues were fixed so the app could bootstrap on Postgres.
- [x] Real register/login over HTTP was validated against the live backend.
- [x] Real sync push over HTTP was validated against PostgreSQL-backed storage.
- [x] Final live pull verification and browser-level smoke test completed successfully.
- [ ] Connect the sync layer to real business entity workflows for actual web-side CRUD sync.
- [x] Validate a real browser-style login + pull + push smoke test against the live backend.

### 2026-09-16 — Live PostgreSQL validation trail
- 2026-09-16 01:15 — Installed official PostgreSQL 17 runtime and confirmed the service was live.
- 2026-09-16 01:20 — Created the project database and verified SQLAlchemy connectivity to Postgres.
- 2026-09-16 01:35 — Fixed the backend env contract after discovering the .env BOM / lowercase alias issue.
- 2026-09-16 01:45 — Fixed the invalid Postgres schema issue caused by a bad FK using `orders.sync_id`.
- 2026-09-16 01:55 — Verified `base.Base.metadata.create_all()` succeeded on the real PostgreSQL database.
- 2026-09-16 02:00 — Started the backend on a clean port and confirmed `uvicorn` startup success.
- 2026-09-16 02:08 — Real register/login endpoint validation succeeded over HTTP.
- 2026-09-16 02:12 — Real sync push validation succeeded against the PostgreSQL-backed backend.
- 2026-09-16 02:20 — Identified the remaining live pull gap and added a regression check for per-user idempotency correctness.
- 2026-09-16 02:25 — Fixed the per-user idempotency contract and verified the focused sync test suite passed.
- 2026-09-16 02:30 — Status: real backend runtime restored and validated; final live pull/browser completion is the remaining gate, not a runtime-install issue.

---

## G. Known Risks / Inconsistencies
- **Historical Precision**: Data created before v35 migration may contain minor floating-point inaccuracies. Post-v35 data is 100% precise.
- **Production URL**: `NetworkConfig.kt` must be updated with the final production domain upon deployment.

---

## H. Forensic Audit Summary (2026-09-12)
- **Fixed**: Missing SyncOutbox entries for all business entities.
- **Fixed**: Deletion sync ignored for 5 critical entity types.
- **Fixed**: Potential "missing data" bug in backend pull cursor logic.
- **Proven**: Dependency resolution, conflict handling, and tombstone persistence verified via `HardenedSyncTest.kt`.
