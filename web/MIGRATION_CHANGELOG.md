Web migration changelog — Beauty -> UPI Account

Summary:
- Added `UPI_ACCOUNT_TRANSACTION` to `EntityType` union in `web/src/lib/sync/types.ts`.
- Added TypeScript alias `UpiAccountTransaction` = `BeautyTransaction` in `web/src/lib/types.ts` to allow gradual renaming on frontend.

Files changed:
- web/src/lib/sync/types.ts
- web/src/lib/types.ts

Notes:
- Update UI routes and components (e.g., `/dashboard/beauty`) in a later step to use canonical wording.
- Sync code should accept both `BEAUTY_TRANSACTION` and `UPI_ACCOUNT_TRANSACTION` until backend cutover is complete.
