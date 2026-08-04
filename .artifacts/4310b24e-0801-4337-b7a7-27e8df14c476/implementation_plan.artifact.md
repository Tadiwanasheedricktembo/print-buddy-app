# Completion of Customer Identity Refactor and Debug Infrastructure

This plan addresses the "unfinished fixes" identified in the codebase, specifically focusing on the debug logging and data integrity infrastructure recommended in the `QA_INVESTIGATION.md` and `DEBUG_LOGGING_ADDITIONS.kt`.

## User Review Required

> [!IMPORTANT]
> This will add new DAO queries and a new DAO interface to the `AppDatabase`. It will also add detailed logging to the `PrintRepository` which can be seen in Logcat. This logging is primarily for debugging customer duplication and debt tracking issues.

## Proposed Changes

### [Data Layer]

#### [NEW] [DebugTags.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/data/DebugTags.kt)
Create a central object for logging tags to be used across the repository and data layer.

#### [NEW] [IntegrityCheckDao.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/data/IntegrityCheckDao.kt)
Implement the `IntegrityCheckDao` interface and associated data classes (`DuplicateCustomerReport`, `BalanceMismatchReport`) to detect database inconsistencies.

#### [MODIFY] [AppDatabase.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/data/AppDatabase.kt)
Add `IntegrityCheckDao` to the `AppDatabase` so it can be accessed by the repository.

#### [MODIFY] [PrintRepository.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/data/PrintRepository.kt)
Integrate detailed debug logging into key methods:
- `getOrCreateCustomer`
- `confirmOrder`
- `updatePayment`
- `applyPaymentToCustomerId`
- `getCustomerBalanceById`

### [Application Layer]

#### [MODIFY] [TadiwaPrintBuddyApp.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/app/src/main/java/com/tadiwaprintbuddy/app/TadiwaPrintBuddyApp.kt)
Add a call to run periodic database integrity checks on app startup in `DEBUG` builds.

#### [DELETE] [DEBUG_LOGGING_ADDITIONS.kt](file:///C:/Users/tadiw/AndroidStudioProjects/TadiwaPrintBuddy/DEBUG_LOGGING_ADDITIONS.kt)
Remove the draft file once its contents are fully integrated.

## Verification Plan

### Automated Tests
- Run existing Room tests to ensure no regressions in data operations.
- The build task `:app:kspDebugKotlin` will verify the new DAO queries.

### Manual Verification
- Check Logcat for tags like `CustomerLookup`, `OrderCreation`, `DebtCalculation` when performing operations in the app.
- Trigger the integrity check on startup and verify no errors are reported in a clean database.
