Migration changelog — Beauty -> UPI Account migration

Summary:
- Canonical entity: `UpiAccountTransaction` created (table: `upi_account_transactions`).
- Room migration `MIGRATION_38_39` added to create `upi_account_transactions` and copy rows from `beauty_transactions` preserving PKs and sync metadata.
- DAO: Added `UpiAccountTransaction` insert/query/delete methods and compatibility queries for `beauty_transactions`.
- Repository: Dual-write approach implemented — writes go to canonical UPI methods, legacy writes retained for compatibility; read/aggregate methods combine results from both tables.
- Added verification in migration to log row counts and missing rows.

Files changed:
- app/src/main/java/com/tadiwaprintbuddy/app/data/UpiAccountTransaction.kt (new)
- app/src/main/java/com/tadiwaprintbuddy/app/data/AppDatabase.kt (modified) — added `MIGRATION_38_39` and verification checks
- app/src/main/java/com/tadiwaprintbuddy/app/data/PrintDao.kt (modified) — added UPI and compatibility DAO methods
- app/src/main/java/com/tadiwaprintbuddy/app/data/PrintRepository.kt (modified) — repository dual-write and combined-read logic
- MIGRATION_CHANGELOG.md (new) — this file

Runtime advice / verification steps to run locally before release:
1. Build and run app on a device with DB version 38 backed data.
2. Observe log tag `DatabaseMigration` during app start; ensure `upiCount == beautyCount` and `missingInUpi == 0`.
3. If `missingInUpi > 0`, run a diagnostic query to list missing `syncId`s and inspect anomalies.

Diagnostic SQL examples (use sqlite3 on the app DB file):

```sql
SELECT COUNT(*) FROM beauty_transactions;
SELECT COUNT(*) FROM upi_account_transactions;
SELECT b.syncId FROM beauty_transactions b LEFT JOIN upi_account_transactions u ON b.syncId = u.syncId WHERE u.syncId IS NULL;
```

Notes:
- Historical Room migrations were not modified; a new migration was added.
- Backend and web sync layers still reference legacy `BeautyTransaction` and must be updated separately.
- Tests and code referring to `BeautyTransaction` are currently kept as compatibility; plan a follow-up to convert tests and backend types.

Next steps:
- Update remaining DAO and repository write paths to only write to canonical UPI table once cutover is validated.
- Draft Alembic migration and backend model/schema updates.
- Update web sync types and provide compatibility acceptance of legacy entity types.
- Run full test suite and data-preservation verification.
