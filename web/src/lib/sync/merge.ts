import { SyncEvent } from './types';

type LocalRecord = Record<string, unknown> & {
  updatedAt?: number;
  syncId?: string;
};

export function applyServerEvent<T extends LocalRecord>(
  currentState: Record<string, T>,
  event: SyncEvent,
): Record<string, T> {
  const nextState = { ...currentState };

  if (event.operation === 'DELETE') {
    delete nextState[event.entity_sync_id];
    return nextState;
  }

  const existing = nextState[event.entity_sync_id];
  const existingUpdatedAt = Number((existing as Record<string, unknown> | undefined)?.updatedAt ?? 0);

  if (existing && existingUpdatedAt > event.timestamp) {
    return nextState;
  }

  const mergedRecord = {
    ...(existing ?? {}),
    ...(event.data as Record<string, unknown>),
    updatedAt: event.timestamp,
    syncId: event.entity_sync_id,
  } as T;

  nextState[event.entity_sync_id] = mergedRecord;

  return nextState;
}

export function applyServerEvents<T extends LocalRecord>(
  currentState: Record<string, T>,
  events: SyncEvent[],
): Record<string, T> {
  let nextState = { ...currentState };

  for (const event of events) {
    nextState = applyServerEvent(nextState, event);
  }

  return nextState;
}
