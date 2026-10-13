Backend migration changelog — Beauty -> UPI Account

Summary:
- Added SQLAlchemy model `UpiAccountTransaction` mirroring `BeautyTransaction`.
- Drafted Alembic migration `20260925_add_upi_account_transactions.py` to create `upi_account_transactions` and copy rows from `beauty_transactions`.
- Updated `business` router to prefer `UpiAccountTransaction` in API responses, falling back to `BeautyTransaction` if canonical rows are absent.
- Added Pydantic `UpiAccountTransactionSchema` alias for compatibility.

Files changed:
- backend/app/models/entities.py (added `UpiAccountTransaction` model)
- backend/alembic/versions/20260925_add_upi_account_transactions.py (new draft migration)
- backend/app/schemas/business.py (added `UpiAccountTransactionSchema`)
- backend/app/routers/business.py (API now prefers UPI model and falls back)

Notes & next steps:
- Replace `<previous_revision_id>` in the Alembic migration with the actual down_revision used by your migration chain before applying.
- Test migration on a staging DB backup. Verify row counts and `sync_id` preservation.
- Add compatibility routes or aliases if external clients rely on `/beauty-account` route name.
- Update backend tests and client documentation to reference the canonical term `UPI Account`.
