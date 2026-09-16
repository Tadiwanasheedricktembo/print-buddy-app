# Implementation Plan - Phase 2: Cloud Backend Foundation

Establish the cloud backend foundation for Tadiwa Print Buddy, including a FastAPI server, Supabase integration, authentication, and secure synchronization endpoints.

## User Review Required

> [!IMPORTANT]
> The backend infrastructure partially exists. I will be refining and hardening it to meet the Phase 2 requirements, especially around idempotency and financial integrity.

> [!WARNING]
> I will be adding Retrofit and OkHttp dependencies to the Android project to support cloud communication.

## Proposed Changes

### Backend Foundation

#### [MODIFY] [entities.py](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/backend/app/models/entities.py)
- Ensure all financial fields match the Room entities' precision (using `Numeric`).
- Verify multi-tenancy support via `user_id`.

#### [MODIFY] [sync.py](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/backend/app/schemas/sync.py)
- Add `idempotency_key` to `SyncEvent`.

#### [MODIFY] [router.py](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/backend/app/sync/router.py)
- Implement idempotency check using `idempotency_key`.
- Wrap batch push in a single transaction for atomicity.
- Refine conflict handling (Server Wins logic based on Android `updated_at`).

### Android Integration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/gradle/libs.versions.toml)
- Add Retrofit, OkHttp, and Logging Interceptor versions.

#### [MODIFY] [build.gradle.kts](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/build.gradle.kts)
- Add networking dependencies.

#### [NEW] [TadiwaApi.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/api/TadiwaApi.kt)
- Define Retrofit interface for Auth and Sync endpoints.

#### [NEW] [AuthRepository.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/data/AuthRepository.kt)
- Manage user authentication, token storage (using EncryptedSharedPreferences).

#### [NEW] [NetworkSyncRepository.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/data/NetworkSyncRepository.kt)
- Handle API calls for push/pull synchronization.

#### [MODIFY] [SyncWorker.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/SyncWorker.kt)
- Wire up the actual synchronization logic:
    1. Upload pending `SyncOutbox` entries.
    2. Download changes from the server.
    3. Update local database with server changes.

### Documentation

#### [MODIFY] [WEB_SYNC_STATUS.md](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/WEB_SYNC_STATUS.md)
- Update phase status to reflect completion of Phase 2.

## Verification Plan

### Automated Tests
- Run existing backend tests: `pytest backend/tests/`
- Add new backend tests for idempotency and financial atomic transactions.
- Run Android unit tests for `BusinessEventMapper` and `SyncOutbox` logic.

### Manual Verification
- Deploy backend locally or to a dev Supabase instance.
- Trigger sync from Android and verify data appears in PostgreSQL.
- Verify that duplicate sync events do not result in duplicate records.
- Verify that authentication restricts data access to the correct user.
