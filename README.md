# Tadiwa Print Buddy

Tadiwa Print Buddy is an offline-first print business operating system built around the real workflow of a small print shop: order intake, quotations, payment collection, debt tracking, stock movement, customer history, and financial reporting.

This repository contains the Android app, a FastAPI backend, and a web dashboard layer. The underlying goal is not just "an app" but a practical business operating system that can run without internet, keep trusted records locally, and later sync cleanly to a cloud layer when the backend is live and verified.

---

## What the project contains

### Android app
- Kotlin + Jetpack / Room + MVVM repository architecture
- Local-first transaction ledger for customers, orders, settlements, stock, and expenses
- Daily business calculations for revenue, debt, and profit
- Offline data integrity checks and reconciliation logic
- Payment, cancellation, reversal, and customer debt flows
- Local backup/export support for operational resilience

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
- Android business logic and local data integrity are in place.
- Backend environment has been restored to a consistent Python 3.11 setup.
- The backend dependency stack and app import path are working from the project’s real Python environment.
- The backend test suite is passing under the project configuration.
- Live login + pull + push sync validation against the PostgreSQL-backed backend succeeded with real JWT authentication and subsequent pull confirmation.

### Current status
- The web sync foundation is implemented, build-safe, and live-validated.
- The backend sync API exists and is test-covered.
- Real end-to-end browser-style login + pull + push validation against a live backend has been completed successfully.
- No new business logic is being introduced while the live sync gate is still being validated.

---

## What I am trying to achieve

The project is aiming to become a complete business system for a real print operation with these goals:

1. Keep daily operations fast and reliable offline.
2. Preserve accurate financial records with precise money handling.
3. Support customer debt, order flow, and settlement history without confusion.
4. Add cloud sync and web access without breaking the local-first trust model.
5. Keep the app useful in the real world rather than as a demo or generic template.

This is a practical personal business tool, not a side project built for aesthetics alone.

---

## Repository layout

- app/ — Android application source
- backend/ — FastAPI backend and test suite
- web/ — Next.js dashboard and browser sync layer
- build/ — generated build output and reports
- docs and project notes — status audits, migration files, and project planning artifacts at the repo root

---

## Development commands

### Android
- Build: ./gradlew assembleDebug
- Test: ./gradlew test

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

## Important note on sync

The sync layer is intentionally additive and isolated. It is designed to preserve existing Android business logic while providing a path toward a verified web/backend sync model. The current honest status is:

- backend API and sync logic: present and tested
- browser sync foundation: present and build-safe
- live browser-to-backend verification: pending

This project stays disciplined by validating the real environment before claiming full sync success.

---

**Project purpose:** real-world operations for a print business, with local-first stability and a controlled move toward cloud sync.

**Last updated:** September 2026
