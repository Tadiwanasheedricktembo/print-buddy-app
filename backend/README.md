# Tadiwa Print Buddy Backend

This backend supports the Android-first Tadiwa Print Buddy business system and the newer web sync layer. It is designed to provide JWT-authenticated cloud services, sync endpoints, analytics, and a trusted data layer for the app’s business operations.

## Current status

The backend environment has been restored and verified under Python 3.11. The dependency set in requirements.txt is the supported runtime for this project, and the backend test suite is currently passing in that environment.

### Live validation log (timestamped)
- 2026-09-16 01:15 — PostgreSQL 17 runtime installed and the service was started successfully.
- 2026-09-16 01:20 — Project database created and reached successfully via localhost:5432.
- 2026-09-16 01:35 — Backend config issue fixed after env-file parsing and BOM handling problems were identified.
- 2026-09-16 01:45 — PostgreSQL schema issue fixed by removing the invalid FK relationship that prevented table creation.
- 2026-09-16 01:55 — Real SQLAlchemy model creation against PostgreSQL succeeded.
- 2026-09-16 02:00 — Backend booted successfully on the live PostgreSQL-backed configuration.
- 2026-09-16 02:08 — Real auth register/login requests passed over HTTP.
- 2026-09-16 02:12 — Real sync push path passed against PostgreSQL storage.
- 2026-09-16 02:25 — Per-user idempotency contract fixed and the sync regression tests passed.
- 2026-09-16 02:30 — Final live pull verification completed: a real user successfully logged in, pulled, pushed a CUSTOMER event, and confirmed it appeared on the subsequent pull.

## Stack
- FastAPI
- SQLAlchemy
- PostgreSQL-compatible database layer
- Alembic migrations
- JWT auth
- Sync API for pull/push semantics

## Supported runtime

Use Python 3.11 for this project.

The project was previously blocked by library incompatibilities under newer Python versions, and the verified setup is the Python 3.11 environment in backend/.venv311.

## Setup

1. Open the backend folder:
   ```bash
   cd backend
   ```

2. Create and activate a Python 3.11 virtual environment:
   ```bash
   py -3.11 -m venv .venv311
   .\.venv311\Scripts\Activate.ps1   # PowerShell
   ```

3. Install dependencies:
   ```bash
   python -m pip install --upgrade pip setuptools wheel
   python -m pip install -r requirements.txt
   ```

4. Create a local environment file from .env.example:
   ```bash
   copy .env.example .env
   ```

5. Fill in the required values, especially:
   - DATABASE_URL
   - JWT_SECRET

6. Run the API:
   ```bash
   uvicorn app.main:app --host 127.0.0.1 --port 8000
   ```

## Testing

Run the backend validation suite with:

```bash
python -m pytest -q
```

Historical result: 23 passed in a prior run. On 2026-10-10, the safe sync-focused SQLite regression set passed 18 tests:

```bash
.\.venv311\Scripts\python.exe -m pytest tests\test_sync.py tests\test_sync_hardening.py tests\test_precision.py tests\test_isolation.py -q
```

The complete suite was not run because `test_postgres_tenant_isolation.py` drops and recreates a local PostgreSQL database. Android-to-backend live synchronization is not verified; see the root [WEB_SYNC_STATUS.md](../WEB_SYNC_STATUS.md).

## API docs

Once the server is running, the OpenAPI docs are available at:
- Swagger UI: http://127.0.0.1:8000/docs
- ReDoc: http://127.0.0.1:8000/redoc

## Notes

- DATABASE_URL is expected to point to the project’s PostgreSQL environment.
- The SQLite config used in tests is for local validation only and is not the production sync runtime.
- A historical browser login + pull + push smoke test is documented in [WEB_SYNC_STATUS.md](../WEB_SYNC_STATUS.md). It is not evidence of Android synchronization and was not rerun in the current audit.
- This repository currently configures Alembic to use `migrations/`, which has no `versions/` directory. Do not treat the separate `alembic/versions/` file as an applicable migration; it is outside the configured migration path and has an unresolved parent revision.
- No Supabase client integration or safe deployed Android test environment was identified in the current configuration.
