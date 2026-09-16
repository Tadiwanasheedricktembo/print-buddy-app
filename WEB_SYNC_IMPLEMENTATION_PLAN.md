# Web Sync Implementation Plan

## Objective

Add a non-invasive, additive web synchronization layer that lets the browser app pull server changes and push local changes through the existing backend sync API without altering the current analytics or dashboard behavior.

This plan is intentionally designed to be safe for a live app: the current working dashboard, auth flow, and analytics code remain intact while a dedicated sync layer is introduced alongside them.

---

## Project Constraints

- Do not rewrite existing dashboard or analytics pages in place.
- Do not change the backend sync contract already defined in [backend/app/schemas/sync.py](backend/app/schemas/sync.py) and implemented in [backend/app/sync/router.py](backend/app/sync/router.py).
- Do not break the current auth flow in [web/src/lib/auth.ts](web/src/lib/auth.ts) and [web/src/app/login/page.tsx](web/src/app/login/page.tsx).
- Keep the web app additive: new sync modules, local storage state, and a lightweight sync loop.
- Preserve the current server-wins conflict semantics from the backend.

---

## Target Architecture

### New sync layer location

Add new files under the web app, without modifying the existing feature pages unless a compatibility wrapper is absolutely required.

Suggested structure:

- web/src/lib/sync/types.ts
- web/src/lib/sync/storage.ts
- web/src/lib/sync/client.ts
- web/src/lib/sync/queue.ts
- web/src/lib/sync/merge.ts
- web/src/lib/sync/useSync.ts
- web/src/lib/sync/index.ts

### Planned responsibilities

- `types.ts`: sync event and cursor types
- `storage.ts`: localStorage persistence for cursor and queued events
- `client.ts`: backend pull/push API calls
- `queue.ts`: enqueue local changes and retry failed sends
- `merge.ts`: apply server-side data into local state without overwriting newer backend changes
- `useSync.ts`: optional hook used by pages that need a refresh cycle
- `index.ts`: single public import point for other agents

---

## Phase 0: Safety and Scope Lock

### Goals

- Freeze the current app behavior.
- Create a separate sync-only surface area.
- Make it obvious where new work belongs.

### Checklist

- [ ] Confirm the current analytics pages remain untouched: [web/src/app/dashboard/page.tsx](web/src/app/dashboard/page.tsx)
- [ ] Confirm the auth flow stays as-is: [web/src/lib/auth.ts](web/src/lib/auth.ts)
- [ ] Create a dedicated `web/src/lib/sync` folder instead of editing existing modules in place
- [ ] Add a top-level note in this file to mark the sync work as additive, not a rewrite
- [ ] Create a short onboarding note for future agents: "Sync work belongs in web/src/lib/sync and should not modify dashboard analytics logic unless explicitly approved"

### Hard rule

- If a page already works and is not part of the sync layer, do not refactor it during this work.

---

## Phase 1: Sync Contract Definitions

### Backend contract to match exactly

The web sync layer must adhere to the backend contract in [backend/app/schemas/sync.py](backend/app/schemas/sync.py):

- `PushRequest`: `events: SyncEvent[]`
- `PullRequest`: `last_sync_timestamp`, `last_sync_id`, `batch_size`
- `PullResponse`: `events`, `next_cursor`, `next_cursor_id`
- `SyncEvent` fields:
  - `entity_type`
  - `entity_sync_id`
  - `operation`
  - `data`
  - `timestamp`
  - `idempotency_key`
  - `server_updated_at` (optional)
  - `server_id` (optional)

### Checklist

- [ ] Add `web/src/lib/sync/types.ts` with types matching backend payloads
- [ ] Add `EntityType` union: `CUSTOMER | ORDER | ORDER_ITEM | SETTLEMENT | EXPENSE | STOCK | NOTE | BEAUTY_TRANSACTION | EXTERNAL_LEDGER | PRINTER_REFERENCE`
- [ ] Add `SyncOperation` union: `CREATE | UPDATE | DELETE`
- [ ] Add `SyncCursor` type:

```ts
export type SyncCursor = {
  lastSyncTimestamp: string | null;
  lastSyncId: number | null;
};
```

- [ ] Add `SyncEvent` interface mirroring the backend shape exactly
- [ ] Add `PushRequest` and `PullRequest` types
- [ ] Add `PushResponse` and `PullResponse` types
- [ ] Keep all money values as strings when transporting to/from the backend, matching the backend precision rules

### Important rule

- Do not invent a different schema from the backend; keep the web contract aligned with the server.

---

## Phase 2: Local Sync State Storage

### Goal

Persist cursor state and queued outbound events without touching the dashboard pages.

### Files to add

- `web/src/lib/sync/storage.ts`

### Checklist

- [ ] Add localStorage keys for sync state, such as:
  - `tadiwa_sync_cursor`
  - `tadiwa_sync_outbox`
  - `tadiwa_sync_last_run`
- [ ] Implement `loadCursor()`
- [ ] Implement `saveCursor(cursor)`
- [ ] Implement `loadOutbox()`
- [ ] Implement `saveOutbox(events)`
- [ ] Implement `appendToOutbox(event)`
- [ ] Implement `removeFromOutbox(idempotencyKey)`
- [ ] Ensure storage is safe when `window` is undefined (Next.js SSR-safe)
- [ ] Keep storage read-only from the existing UI layers; only the sync service should mutate it

### Example skeleton

```ts
export type SyncStorageState = {
  cursor: SyncCursor;
  outbox: SyncEvent[];
};

export function loadCursor(): SyncCursor { ... }
export function saveCursor(cursor: SyncCursor): void { ... }
export function loadOutbox(): SyncEvent[] { ... }
export function saveOutbox(events: SyncEvent[]): void { ... }
```

---

## Phase 3: Backend Sync Client

### Goal

Create a single web sync client that speaks the backend API contract.

### Files to add

- `web/src/lib/sync/client.ts`

### Checklist

- [ ] Implement `pullSync(cursor: SyncCursor, batchSize = 100)` using `POST /api/v1/sync/pull`
- [ ] Implement `pushSync(events: SyncEvent[])` using `POST /api/v1/sync/push`
- [ ] Reuse the auth token from [web/src/lib/auth.ts](web/src/lib/auth.ts)
- [ ] Use the existing fetch wrapper pattern from [web/src/lib/api.ts](web/src/lib/api.ts) to keep auth handling consistent
- [ ] Add error handling for 401, 400, 500, and network failures
- [ ] Return strongly typed response objects
- [ ] Do not call the sync service from dashboard code until a separate synchronization hook is ready

### Example skeleton

```ts
export async function pullSync(cursor: SyncCursor, batchSize = 100): Promise<PullResponse> {
  return fetchWithAuth('/sync/pull', {
    method: 'POST',
    body: JSON.stringify({
      last_sync_timestamp: cursor.lastSyncTimestamp,
      last_sync_id: cursor.lastSyncId,
      batch_size: batchSize,
    }),
  });
}

export async function pushSync(events: SyncEvent[]): Promise<PushResponse> {
  return fetchWithAuth('/sync/push', {
    method: 'POST',
    body: JSON.stringify({ events }),
  });
}
```

---

## Phase 4: Outbox Queue and Retry Logic

### Goal

Allow local web changes to be stored and retried safely without breaking the current UI behavior.

### Files to add

- `web/src/lib/sync/queue.ts`

### Checklist

- [ ] Add `enqueueLocalEvent(event: SyncEvent)`
- [ ] Add `flushOutbox()` that sends all queued events in batches
- [ ] Add `markEventSuccessful(idempotencyKey)`
- [ ] Add `markEventFailed(idempotencyKey)`
- [ ] Keep failed entries in the outbox for retry
- [ ] Add a max retry threshold and log failure state without crashing the app
- [ ] Ensure event deduplication by `idempotency_key`
- [ ] Keep payload order stable so the backend receives deterministic batches

### Example queue behavior

```ts
export async function flushOutbox(): Promise<void> {
  const outbox = loadOutbox();
  if (outbox.length === 0) return;

  const response = await pushSync(outbox);
  const successful = response.results.filter(r => r.status === 'SUCCESS' || r.status === 'SERVER_WINS' || r.status === 'ALREADY_PROCESSED');

  const remaining = outbox.filter(event => {
    const result = successful.find(r => r.sync_id === event.entity_sync_id);
    return !result;
  });

  saveOutbox(remaining);
}
```

### Rule

- The outbox should never be allowed to break UI rendering; it should be a background concern.

---

## Phase 5: Pull/Merge Logic

### Goal

Apply backend changes into local web state without clobbering newer server values.

### Files to add

- `web/src/lib/sync/merge.ts`

### Checklist

- [ ] Implement `applyServerEvent(localState, event)`
- [ ] Handle `CREATE` operations by inserting a new record into local state
- [ ] Handle `UPDATE` operations by replacing the local row only when the local copy is older than the server update
- [ ] Handle `DELETE` by marking the record deleted or removing it from state
- [ ] Use server-wins semantics as the authoritative rule
- [ ] Ignore stale client updates when the local copy is newer than the backend version
- [ ] Update cursor after a successful pull batch
- [ ] Preserve per-entity maps keyed by `entity_sync_id`
- [ ] Keep merge logic separate from rendering logic so it can be reused by all pages

### Example rule

```ts
if (event.operation === 'DELETE') {
  delete localMap[event.entity_sync_id];
  return;
}

const current = localMap[event.entity_sync_id];
if (!current || current.updatedAt <= event.timestamp) {
  localMap[event.entity_sync_id] = {
    ...current,
    ...event.data,
    updatedAt: event.timestamp,
  };
}
```

### Important note

This is where the app must remain compatible: merge logic only touches local sync state, not the already-working dashboard components.

---

## Phase 6: Sync Hook and Lifecycle

### Goal

Run the sync loop without modifying existing app pages in place.

### Files to add

- `web/src/lib/sync/useSync.ts`

### Checklist

- [ ] Implement a hook that calls `pullSync` on startup
- [ ] Trigger a `flushOutbox()` after a successful pull
- [ ] Trigger a timed refresh loop (for example every 30-60 seconds)
- [ ] Expose a boolean `isSyncing` state
- [ ] Expose a `lastSyncError` string if needed for debugging
- [ ] Keep the hook optional; it should be used by pages that need live sync without affecting all pages

### Example skeleton

```ts
export function useSync() {
  useEffect(() => {
    let mounted = true;

    async function cycle() {
      const cursor = loadCursor();
      const response = await pullSync(cursor);
      if (!mounted) return;

      applyServerEvents(response.events);
      saveCursor({
        lastSyncTimestamp: response.next_cursor ?? cursor.lastSyncTimestamp,
        lastSyncId: response.next_cursor_id ?? cursor.lastSyncId,
      });

      await flushOutbox();
    }

    cycle();
    const timer = setInterval(cycle, 30000);
    return () => {
      mounted = false;
      clearInterval(timer);
    };
  }, []);
}
```

---

## Phase 7: Local Change Capture from UI

### Goal

Add a safe path to capture user edits and convert them into sync events.

### Checklist

- [ ] Add a helper function `toSyncEvent(entityType, entitySyncId, operation, data, timestamp)`
- [ ] Add a helper for idempotency key generation:

```ts
const idempotencyKey = `${entityType}:${entitySyncId}:${operation}:${timestamp}`;
```

- [ ] Add a generic local-state mutation method for each business model
- [ ] Keep all UI components unchanged while the sync wrapper captures local changes
- [ ] Ensure `updatedAt` and `timestamp` use the same time unit expected by the backend
- [ ] Keep all monetary values as strings when serializing to the backend

### Important compatibility rule

- UI pages should continue to work with their current local objects; the sync layer should convert them when they are ready to be sent.

---

## Phase 8: Integration Without Breaking Current Components

### Goal

Introduce sync without touching working pages.

### Checklist

- [ ] Keep [web/src/app/dashboard/page.tsx](web/src/app/dashboard/page.tsx) as analytics-first unless sync becomes required there
- [ ] Keep [web/src/lib/api.ts](web/src/lib/api.ts) usable for existing authenticated calls
- [ ] Add new sync API calls in a separate file instead of modifying existing dashboard invocations
- [ ] Add a compatibility wrapper only if some page needs live sync data
- [ ] Do not refactor business logic in existing components during the initial sync integration
- [ ] Prefer sidecar modules and optional hooks rather than editing current UI logic

### Design decision

If a page needs synced data, add a thin wrapper or hook around it; do not rewrite the page itself.

---

## Phase 9: Validation and Safety Checks

### Checklist

- [ ] Verify login still works
- [ ] Verify analytics still render without change
- [ ] Verify a fresh sync pull populates local state without errors
- [ ] Verify a push with a single event succeeds
- [ ] Verify duplicate idempotency keys are ignored by the backend
- [ ] Verify server-wins conflict resolution remains correct
- [ ] Verify cursor advances after a successful pull
- [ ] Verify failed push events remain in the outbox
- [ ] Verify retry succeeds after network recovery
- [ ] Verify the app does not crash when storage is empty

### Validation command examples

- `cd backend && pytest tests/test_sync.py tests/test_sync_hardening.py tests/test_isolation.py -q`
- Start web app and log sync lifecycle events
- Inspect localStorage keys for cursor and outbox state

---

## Phase 10: Agent Handoff Notes

### Reference statement for future work

This web sync work is intentionally additive and isolated. The backend sync API is authoritative and already validated. The web layer should be implemented in the new sync modules, not by modifying the existing dashboard logic in place.

### Required handoff items

- [ ] Link to [backend/app/sync/router.py](backend/app/sync/router.py)
- [ ] Link to [backend/app/schemas/sync.py](backend/app/schemas/sync.py)
- [ ] Link to [web/src/lib/api.ts](web/src/lib/api.ts)
- [ ] Link to [web/src/lib/auth.ts](web/src/lib/auth.ts)
- [ ] Link to this plan file
- [ ] Note the current safe path: add sync modules only, and only integrate where truly needed

---

## Recommended Implementation Order

1. Create sync types
2. Create storage layer
3. Create backend sync client
4. Create outbox queue and retry logic
5. Create merge logic
6. Add optional sync hook
7. Add minimal UI integration if needed
8. Validate with backend tests and browser checks

---

## Final Rule

Do not “improve” working app behavior while implementing sync. The web sync layer must be a safe sidecar integration that respects the backend contract and leaves the currently functioning analytics and dashboard features intact.
