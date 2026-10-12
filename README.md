# Tadiwa Print Buddy

Tadiwa Print Buddy is an offline-first print business operating system built around the real workflow of a small print shop: order intake, quotations, payment collection, debt tracking, stock movement, customer history, financial reporting, and operational notes.

This repository contains the Android app, a FastAPI backend, and a web dashboard layer. The underlying goal is not just "an app" but a practical business operating system that can run without internet, keep trusted records locally, and sync cleanly to a cloud layer.

---

## What the project contains

### Android app
- Kotlin + Jetpack / Room + MVVM repository architecture
- Local-first transaction ledger for customers, orders, settlements, stock, expenses, and notes
- **Total Number of Prints Made tracking**: First-class, independent `printCount` tracking per transaction, decoupled from monetary calculations.
- **Collapsible Customer Balances**: Independent summary cards (`OWES ME` and `I OWE CHANGE`) with sorting, filtering, and customer management.
- **Automatic Order Saving**: Quick Order on the Home Screen automatically saves valid orders upon selecting Cash, UPI, or Credit payment methods.
- **Notes Feature**: Fully integrated notes management with sorting, search, and backup/restore coverage.
- Offline data integrity checks and reconciliation logic
- Local backup/export support (`print_database` `.zip` archives) ensuring 100% data preservation across restore operations.

Location:
- app/

### Backend API
- FastAPI application with JWT auth and sync endpoints
- PostgreSQL-compatible data layer with SQLAlchemy models and Alembic support
- User-scoped sync isolation and server-side idempotency
- API routers for auth, sync, analytics, and business data

Location:
- backend/

### Web dashboard / sync layer
- Next.js web app for dashboard and browser-side data access
- Authenticated API wrappers and local storage token handling
- Sync primitives for pull/push, outbox queue, merge logic, and diagnostics
- Dashboard integration for monitoring sync status and smoke tests

Location:
- web/

---

## Current project status

### Verified and working
- Android business logic, local data integrity, and analytics are fully verified.
- **Newly Implemented Features**:
  1. Independent `printCount` tracking system (persisted, backed up, and reflected in analytics and customer summaries).
  2. Collapsible customer balance summary cards (`OWES ME` and `I OWE CHANGE`).
  3. Automatic order saving on payment method selection (`Cash`, `UPI`, `Credit`).
  4. Fully wired Notes feature with navigation menu access and backup integration.
  5. Comprehensive automated unit and integration test suites (`OrderValidationTest`, `NotesFeatureTest`, `PrintCountIntegrityTest`, `FinancialIntegrityTest`).

---

## What I am trying to achieve

The project aims to be a complete business system for a real print operation with these goals:

1. Keep daily operations fast, automated, and reliable offline.
2. Preserve accurate financial records with precise decimal handling and independent print count metrics.
3. Support customer debt, order flow, settlement history, and operational notes without confusion.
4. Add cloud sync and web access without breaking the local-first trust model.
5. Keep the app useful in the real world as a practical personal business tool.

---

## Repository layout

- app/ — Android application source
- backend/ — FastAPI backend and test suite
- web/ — Next.js dashboard and browser sync layer
- docs and project notes — status audits, migration files, and project planning artifacts at the repo root
  - **[CURRENT_PROJECT_STATE.md](CURRENT_PROJECT_STATE.md)** — session resume point for humans and agents
  - **[LOGIC_ISSUES_LOG.md](LOGIC_ISSUES_LOG.md)** — known business-logic issues and design logs

---

## Development commands

### Android
- From Windows PowerShell, compile app Kotlin: `.\gradlew.bat :app:compileDebugKotlin`
- Compile unit-test Kotlin when test sources change: `.\gradlew.bat :app:compileDebugUnitTestKotlin`
- Run one unit test with `:app:testDebugUnitTest --tests "fully.qualified.TestClass.test method name"`
- Run the complete Android unit-test suite for final verification: `.\gradlew.bat :app:testDebugUnitTest`

### Backend
- Setup Python 3.11 environment in backend/
- Install: pip install -r requirements.txt
- Run API: uvicorn app.main:app --host 127.0.0.1 --port 8000
- Test: python -m pytest -q

### Web dashboard
- Install: npm install
- Run: npm run dev
- Build: npm run build

---

**Project purpose:** real-world operations for a print business, with local-first stability and a controlled move toward cloud sync.

**Last updated:** September 2026
