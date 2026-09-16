import { pushSync } from './client';
import { appendToOutbox, loadOutbox, removeFromOutbox, saveOutbox } from './storage';
import { SyncEvent } from './types';

export function enqueueLocalEvent(event: SyncEvent): void {
  appendToOutbox(event);
}

export async function flushOutbox(): Promise<{ succeeded: number; failed: number }> {
  const outbox = loadOutbox();

  if (outbox.length === 0) {
    return { succeeded: 0, failed: 0 };
  }

  try {
    const response = await pushSync(outbox);
    const successfulSyncIds = new Set(
      response.results
        .filter(
          (item) =>
            item.status === 'SUCCESS' ||
            item.status === 'SERVER_WINS' ||
            item.status === 'ALREADY_PROCESSED',
        )
        .map((item) => item.sync_id),
    );

    const remaining = outbox.filter((event) => !successfulSyncIds.has(event.entity_sync_id));

    if (remaining.length !== outbox.length) {
      saveOutbox(remaining);
    }

    for (const event of outbox) {
      if (successfulSyncIds.has(event.entity_sync_id)) {
        if (event.idempotency_key) {
          removeFromOutbox(event.idempotency_key);
        }
      }
    }

    return {
      succeeded: outbox.length - remaining.length,
      failed: remaining.length,
    };
  } catch {
    return {
      succeeded: 0,
      failed: outbox.length,
    };
  }
}

export function getQueuedEvents(): SyncEvent[] {
  return loadOutbox();
}
