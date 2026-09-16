import { SyncCursor, SyncEvent, SyncStorageState } from './types';

const CURSOR_KEY = 'tadiwa_sync_cursor';
const OUTBOX_KEY = 'tadiwa_sync_outbox';

const emptyCursor: SyncCursor = {
  lastSyncTimestamp: null,
  lastSyncId: null,
};

function safeStorageRead<T>(key: string, fallback: T): T {
  if (typeof window === 'undefined') {
    return fallback;
  }

  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) {
      return fallback;
    }

    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function safeStorageWrite(key: string, value: unknown) {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Intentionally ignore storage write errors so sync remains non-blocking.
  }
}

export function loadCursor(): SyncCursor {
  return safeStorageRead<SyncCursor>(CURSOR_KEY, emptyCursor);
}

export function saveCursor(cursor: SyncCursor): void {
  safeStorageWrite(CURSOR_KEY, cursor);
}

export function loadOutbox(): SyncEvent[] {
  return safeStorageRead<SyncEvent[]>(OUTBOX_KEY, []);
}

export function saveOutbox(events: SyncEvent[]): void {
  safeStorageWrite(OUTBOX_KEY, events);
}

export function appendToOutbox(event: SyncEvent): void {
  const current = loadOutbox();
  const existing = current.some((item) => item.idempotency_key === event.idempotency_key);

  if (!existing) {
    const next = [...current, event];
    saveOutbox(next);
  }
}

export function removeFromOutbox(idempotencyKey: string): void {
  const next = loadOutbox().filter((event) => event.idempotency_key !== idempotencyKey);
  saveOutbox(next);
}

export function getSyncStorageState(): SyncStorageState {
  return {
    cursor: loadCursor(),
    outbox: loadOutbox(),
  };
}

export function clearSyncStorage(): void {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(CURSOR_KEY);
    window.localStorage.removeItem(OUTBOX_KEY);
  }
}
