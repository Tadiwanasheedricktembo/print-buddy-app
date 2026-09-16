# Tadiwa Print Buddy Recovery Runbook

## Status

Current repository evidence indicates:
- Android local backup exists and is used for device-side restore.
- Backend authoritative database backup automation is not present in the repository.
- Restore drill is not verified in a safe environment.
- This means disaster recovery is not yet proven for production business data.

## Authority and data flow

### Authoritative source of truth
- The authoritative business data is the backend PostgreSQL database configured in [backend/app/config.py](backend/app/config.py) and [backend/app/database.py](backend/app/database.py).
- Android local Room storage is local device data, not the authoritative server state.
- The backend sync layer reconciles app data to the server. The app does not replace the server as the system of record.

### How data moves
1. Android app stores local business records in Room / SQLite.
2. Sync endpoints push local rows and metadata to the backend.
3. The backend stores records in PostgreSQL and maintains sync metadata in tables such as the models in [backend/app/models/base.py](backend/app/models/base.py) and [backend/app/models/entities.py](backend/app/models/entities.py).
4. Android later pulls server state back down.

## Proven recovery capability

### Isolated PostgreSQL recovery drill
- A disposable PostgreSQL database was created and used as a safe restore target.
- The actual Tadiwa Print Buddy schema was created using the project SQLAlchemy model metadata.
- Representative synthetic data was inserted for users, customers, orders, settlement records, expenses, stock, notes, sync metadata, and financial rows.
- `pg_dump -Fc` created a binary dump successfully.
- The disposable target was destroyed and recreated.
- `pg_restore` restored the backup into the fresh database and the script verified row counts and exact financial values.
- The exact value checks included `1234.56`, `0.01`, `123.45`, and `999999.99`.

### Repository-proved status
- The restore drill is a proven isolated recovery capability.
- This does not equal production DR readiness.

## Not operationally implemented

- Automated production PostgreSQL backup scheduling: NOT IMPLEMENTED
- Retention policy: NOT IMPLEMENTED
- Off-site storage: NOT IMPLEMENTED
- Encryption at rest: NOT IMPLEMENTED
- Backup monitoring or alerting: NOT IMPLEMENTED
- RPO/RTO definition: NOT DEFINED

## Not yet verified

- Android end-to-end post-restore synchronization: NOT VERIFIED
- Migration recovery drill: NOT VERIFIED
- Production database restore: NOT VERIFIED

## Safe local recovery workflow

The repository’s safe local workflow is:

1. Use a disposable PostgreSQL target only.
2. Require explicit local host and allowed target naming.
3. Refuse to operate on `postgres`, `template0`, `template1`, or the configured app database.
4. Create the disposable database and initialize the app schema.
5. Insert representative data and capture a baseline.
6. Run `pg_dump` to a local dump file.
7. Validate the dump file exists and is non-empty.
8. Destroy and recreate the disposable target database.
9. Run `pg_restore` into the recreated target.
10. Validate schema, row counts, relationships, ownership, sync metadata, and Decimal precision.
11. Preserve the restored database as a local verification artifact only.
12. Do not run the same commands against production without explicit authorization.

## Commands that must not be copied blindly

The following are examples only; they are for disposable verification and must not be used against the real application database:

- `createdb ...`
- `dropdb ...`
- `pg_dump ...`
- `pg_restore ...`

They must be issued only against a positively classified disposable database.

## Current status

ISOLATED RECOVERY CAPABILITY: PROVEN
PRODUCTION DISASTER RECOVERY OPERATIONAL: NOT VERIFIED
