import test from 'node:test';
import assert from 'node:assert/strict';

import { evaluatePersistedSyncCheck, evaluatePushStatus } from './diagnostics';

test('push success statuses are accepted', () => {
  assert.equal(evaluatePushStatus('SUCCESS'), true);
  assert.equal(evaluatePushStatus('SERVER_WINS'), true);
  assert.equal(evaluatePushStatus('ALREADY_PROCESSED'), true);
  assert.equal(evaluatePushStatus('ERROR'), false);
});

test('persisted sync verification matches the specific entity returned by a pull', () => {
  const events = [
    {
      entity_sync_id: 'demo-123',
      data: { displayName: 'Sync Check Demo', normalizedName: 'sync check demo' },
    },
  ];

  assert.equal(evaluatePersistedSyncCheck(events, 'demo-123', 'Sync Check Demo'), true);
  assert.equal(evaluatePersistedSyncCheck(events, 'demo-999', 'Sync Check Demo'), false);
  assert.equal(evaluatePersistedSyncCheck(events, 'demo-123', 'Different Name'), false);
});
