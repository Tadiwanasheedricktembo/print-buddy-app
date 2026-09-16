# Implementation Plan - Phase 2: Cloud Backend Foundation

Establish a secure, transactionally safe FastAPI backend with Supabase PostgreSQL integration to support synchronization for Tadiwa Print Buddy.

## User Review Required

> [!IMPORTANT]
> The backend will use **Supabase PostgreSQL** as the production database. You will need to provide the Supabase credentials in the `.env` file (copied from `.env.example`).
> The synchronization logic follows a **Server Wins** strategy for conflicts, using the latest `updatedAt` timestamp.

## Proposed Changes

### Backend Project

#### [NEW] [backend structure](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/backend)
Create the following directory structure:
- `backend/app/main.py`: Entry point for FastAPI.
- `backend/app/config.py`: Environment configuration.
- `backend/app/database.py`: SQLAlchemy setup.
- `backend/app/models/`: SQLAlchemy models (User, Business, Customer, Order, etc.).
- `backend/app/schemas/`: Pydantic models for API request/response.
- `backend/app/routers/`: Auth and Sync routers.
- `backend/app/auth/`: JWT and password hashing logic.
- `backend/app/sync/`: Core synchronization engine logic.
- `backend/requirements.txt`: Python dependencies (FastAPI, SQLAlchemy, Pydantic, Alembic, etc.).
- `backend/.env.example`: Template for environment variables.
- `backend/README.md`: Setup and usage instructions.

### Android Application Integration

#### [MODIFY] [NetworkSyncRepository.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/data/NetworkSyncRepository.kt)
Update to match the finalized API contract and ensure robust error handling.

## Verification Plan

### Automated Tests
- **Backend Tests**:
  - `pytest` for Auth endpoints (Register/Login).
  - `pytest` for Sync endpoints (Push/Pull) with multitenancy checks.
  - Idempotency tests for `push` requests.
- **Android Tests**:
  - Verify `NetworkSyncRepository` correctly interacts with the new backend (using mock server or local dev instance).

### Manual Verification
- Deploy backend locally and verify OpenAPI docs (`/docs`).
- Perform a manual sync from the Android app and verify records appear in the PostgreSQL database.
