import { fetchWithAuth } from '@/lib/api';
import { PullResponse, PushResponse, SyncCursor, SyncEvent } from './types';

export async function pullSync(cursor: SyncCursor, batchSize = 100): Promise<PullResponse> {
  const body = {
    last_sync_timestamp: cursor.lastSyncTimestamp,
    last_sync_id: cursor.lastSyncId,
    batch_size: batchSize,
  };

  return fetchWithAuth('/sync/pull', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function pushSync(events: SyncEvent[]): Promise<PushResponse> {
  return fetchWithAuth('/sync/push', {
    method: 'POST',
    body: JSON.stringify({ events }),
  });
}

export function createSyncEvent(
  entityType: SyncEvent['entity_type'],
  entitySyncId: string,
  operation: SyncEvent['operation'],
  data: Record<string, unknown>,
  timestamp = Date.now(),
  idempotencyKey?: string,
): SyncEvent {
  const resolvedKey =
    idempotencyKey ?? `${entityType}:${entitySyncId}:${operation}:${timestamp}`;

  return {
    entity_type: entityType,
    entity_sync_id: entitySyncId,
    operation,
    data,
    timestamp,
    idempotency_key: resolvedKey,
  };
}
