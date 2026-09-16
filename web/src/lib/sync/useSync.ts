import { useEffect, useRef, useState } from 'react';
import { pullSync } from './client';
import { flushOutbox } from './queue';
import { loadCursor, saveCursor } from './storage';
import { applyServerEvents } from './merge';

export function useSync<T extends Record<string, unknown>>(initialState: Record<string, T> = {}) {
  const [state, setState] = useState<Record<string, T>>(initialState);
  const [isSyncing, setIsSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  const syncNow = async () => {
    setIsSyncing(true);
    setError(null);

    try {
      const cursor = loadCursor();
      const response = await pullSync(cursor, 100);

      if (!mountedRef.current) {
        return;
      }

      setState((current) => applyServerEvents(current, response.events));

      const nextCursor = {
        lastSyncTimestamp: response.next_cursor ?? cursor.lastSyncTimestamp,
        lastSyncId: response.next_cursor_id ?? cursor.lastSyncId,
      };

      saveCursor(nextCursor);

      await flushOutbox();
    } catch (syncError) {
      const message = syncError instanceof Error ? syncError.message : 'Sync failed';
      setError(message);
    } finally {
      if (mountedRef.current) {
        setIsSyncing(false);
      }
    }
  };

  useEffect(() => {
    mountedRef.current = true;

    void syncNow();
    const intervalId = window.setInterval(() => {
      void syncNow();
    }, 30000);

    return () => {
      mountedRef.current = false;
      window.clearInterval(intervalId);
    };
  }, []);

  return {
    state,
    setState,
    isSyncing,
    error,
    syncNow,
  };
}
