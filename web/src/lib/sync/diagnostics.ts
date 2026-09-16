import { createCustomerSyncEvent, pullSync, pushSync } from './index';
import { loadCursor } from './storage';

export type AuthenticatedSyncSmokeResult = {
  ok: boolean;
  loginOk: boolean;
  pullOk: boolean;
  pushOk: boolean;
  persistedOk: boolean;
  status?: string;
  error?: string;
};

export type SyncDiagnosticsResult = {
  pull: {
    ok: boolean;
    count: number;
    nextCursor: string | null;
    nextCursorId: number | null;
    error?: string;
  };
  push: {
    ok: boolean;
    status?: string;
    error?: string;
    persistedOk?: boolean;
    persistedError?: string;
  };
};

export function evaluatePushStatus(status?: string): boolean {
  return status === 'SUCCESS' || status === 'SERVER_WINS' || status === 'ALREADY_PROCESSED';
}

export function evaluatePersistedSyncCheck(
  events: Array<{ entity_sync_id?: string; data?: Record<string, unknown> } | undefined> | null | undefined,
  expectedEntitySyncId: string,
  expectedDisplayName?: string,
): boolean {
  if (!events?.length) {
    return false;
  }

  return events.some((event) => {
    if (!event || event.entity_sync_id !== expectedEntitySyncId) {
      return false;
    }

    if (!expectedDisplayName) {
      return true;
    }

    const actualDisplayName = typeof event.data?.displayName === 'string' ? event.data.displayName : undefined;
    return actualDisplayName === expectedDisplayName;
  });
}

export async function runAuthenticatedLiveSyncSmokeTest(
  username: string,
  password: string,
  baseUrl = '/api/v1',
): Promise<AuthenticatedSyncSmokeResult> {
  const formData = new URLSearchParams();
  formData.append('username', username);
  formData.append('password', password);

  const loginResponse = await fetch(`${baseUrl}/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: formData,
  });

  if (!loginResponse.ok) {
    const errorText = await loginResponse.text().catch(() => 'Invalid username or password');
    return {
      ok: false,
      loginOk: false,
      pullOk: false,
      pushOk: false,
      persistedOk: false,
      error: errorText || 'Invalid username or password',
    };
  }

  const loginPayload = await loginResponse.json();
  const token = loginPayload.access_token as string | undefined;

  if (!token) {
    return {
      ok: false,
      loginOk: true,
      pullOk: false,
      pushOk: false,
      persistedOk: false,
      error: 'Login succeeded but no access token was returned.',
    };
  }

  const authHeaders = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  const pullRequest = {
    batch_size: 25,
  };

  const pullResponse = await fetch(`${baseUrl}/sync/pull`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify(pullRequest),
  });

  if (!pullResponse.ok) {
    const detail = await pullResponse.text().catch(() => 'Pull failed');
    return {
      ok: false,
      loginOk: true,
      pullOk: false,
      pushOk: false,
      persistedOk: false,
      error: detail,
    };
  }

  const pullPayload = await pullResponse.json();
  const demoCustomer = createCustomerSyncEvent(
    {
      displayName: `Live Smoke ${Date.now()}`,
      normalizedName: 'live smoke',
      phoneNumber: '+263000000099',
    },
    'CREATE',
  );

  const pushResponse = await fetch(`${baseUrl}/sync/push`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({ events: [demoCustomer] }),
  });

  if (!pushResponse.ok) {
    const detail = await pushResponse.text().catch(() => 'Push failed');
    return {
      ok: false,
      loginOk: true,
      pullOk: true,
      pushOk: false,
      persistedOk: false,
      error: detail,
    };
  }

  const pushPayload = await pushResponse.json();
  const firstStatus = pushPayload.results?.[0]?.status ?? 'UNKNOWN';
  const pushOk = evaluatePushStatus(firstStatus);

  const confirmResponse = await fetch(`${baseUrl}/sync/pull`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({ batch_size: 100 }),
  });

  if (!confirmResponse.ok) {
    return {
      ok: false,
      loginOk: true,
      pullOk: true,
      pushOk,
      persistedOk: false,
      status: firstStatus,
      error: 'Follow-up pull failed after a successful push.',
    };
  }

  const confirmPayload = await confirmResponse.json();
  const persistedOk = evaluatePersistedSyncCheck(
    confirmPayload.events,
    demoCustomer.entity_sync_id,
    demoCustomer.data.displayName as string,
  );

  return {
    ok: pushOk && persistedOk,
    loginOk: true,
    pullOk: true,
    pushOk,
    persistedOk,
    status: firstStatus,
    error: pushOk && persistedOk ? undefined : 'Push succeeded but the item was not confirmed by a follow-up pull.',
  };
}

export async function runSyncDiagnostics(): Promise<SyncDiagnosticsResult> {
  const cursor = loadCursor();

  try {
    const pullResponse = await pullSync(cursor, 10);
    const pullCount = pullResponse.events?.length ?? 0;

    const demoCustomer = createCustomerSyncEvent(
      {
        displayName: `Sync Check ${Date.now()}`,
        normalizedName: 'sync check',
        phoneNumber: '+263000000001',
      },
      'CREATE',
    );

    try {
      const pushResponse = await pushSync([demoCustomer]);
      const firstStatus = pushResponse.results?.[0]?.status ?? 'UNKNOWN';
      const pushOk = evaluatePushStatus(firstStatus);

      let persistedOk = false;
      let persistedError: string | undefined;

      if (pushOk) {
        try {
          const postPushPull = await pullSync(cursor, 100);
          persistedOk = evaluatePersistedSyncCheck(
            postPushPull.events,
            demoCustomer.entity_sync_id,
            demoCustomer.data.displayName as string,
          );

          if (!persistedOk) {
            persistedError = `Pushed customer ${demoCustomer.entity_sync_id} was not confirmed in a follow-up pull.`;
          }
        } catch (verificationError) {
          persistedError = verificationError instanceof Error ? verificationError.message : 'Follow-up pull verification failed';
        }
      }

      return {
        pull: {
          ok: true,
          count: pullCount,
          nextCursor: pullResponse.next_cursor ?? null,
          nextCursorId: pullResponse.next_cursor_id ?? null,
        },
        push: {
          ok: pushOk && persistedOk,
          status: firstStatus,
          persistedOk,
          persistedError,
          error: pushOk && persistedOk ? undefined : persistedError ?? (pushOk ? 'Push accepted but persisted verification failed' : 'Push response was not successful'),
        },
      };
    } catch (pushError) {
      return {
        pull: {
          ok: true,
          count: pullCount,
          nextCursor: pullResponse.next_cursor ?? null,
          nextCursorId: pullResponse.next_cursor_id ?? null,
        },
        push: {
          ok: false,
          error: pushError instanceof Error ? pushError.message : 'Push failed',
        },
      };
    }
  } catch (pullError) {
    return {
      pull: {
        ok: false,
        count: 0,
        nextCursor: null,
        nextCursorId: null,
        error: pullError instanceof Error ? pullError.message : 'Pull failed',
      },
      push: {
        ok: false,
        error: 'Skipped because pull failed',
      },
    };
  }
}
