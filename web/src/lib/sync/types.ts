export type SyncOperation = 'CREATE' | 'UPDATE' | 'DELETE';

export type EntityType =
  | 'CUSTOMER'
  | 'ORDER'
  | 'ORDER_ITEM'
  | 'SETTLEMENT'
  | 'EXPENSE'
  | 'STOCK'
  | 'NOTE'
  | 'BEAUTY_TRANSACTION'
  | 'EXTERNAL_LEDGER'
  | 'PRINTER_REFERENCE';

export type SyncCursor = {
  lastSyncTimestamp: string | null;
  lastSyncId: number | null;
};

export type SyncEvent = {
  entity_type: EntityType;
  entity_sync_id: string;
  operation: SyncOperation;
  data: Record<string, unknown>;
  timestamp: number;
  idempotency_key?: string | null;
  server_updated_at?: string | null;
  server_id?: number | null;
};

export type PushResult = {
  sync_id: string;
  status: 'SUCCESS' | 'SERVER_WINS' | 'ERROR' | 'ALREADY_PROCESSED';
  message?: string;
};

export type PushResponse = {
  results: PushResult[];
};

export type PullRequest = {
  last_sync_timestamp?: string | null;
  last_sync_id?: number | null;
  batch_size?: number;
};

export type PullResponse = {
  events: SyncEvent[];
  next_cursor?: string | null;
  next_cursor_id?: number | null;
};

export type SyncStorageState = {
  cursor: SyncCursor;
  outbox: SyncEvent[];
};
