import { Customer, Order } from '@/lib/types';
import { enqueueLocalEvent, flushOutbox } from './queue';
import { EntityType, SyncEvent, SyncOperation } from './types';

const makeIdempotencyKey = (
  entityType: EntityType,
  entitySyncId: string,
  operation: SyncOperation,
  timestamp: number,
) => `${entityType}:${entitySyncId}:${operation}:${timestamp}`;

export function createCustomerSyncEvent(
  customer: Partial<Customer>,
  operation: SyncOperation,
  timestamp = Date.now(),
): SyncEvent {
  const entitySyncId = customer.syncId ?? `customer-${timestamp}`;

  return {
    entity_type: 'CUSTOMER',
    entity_sync_id: entitySyncId,
    operation,
    data: {
      id: customer.id ?? 0,
      displayName: customer.displayName ?? '',
      normalizedName: customer.normalizedName ?? (customer.displayName ?? '').trim().toLowerCase(),
      phoneNumber: customer.phoneNumber ?? null,
      createdAt: customer.createdAt ?? timestamp,
      updatedAt: timestamp,
    },
    timestamp,
    idempotency_key: makeIdempotencyKey('CUSTOMER', entitySyncId, operation, timestamp),
  };
}

export function createOrderSyncEvent(
  order: Partial<Order>,
  operation: SyncOperation,
  timestamp = Date.now(),
): SyncEvent {
  const entitySyncId = order.syncId ?? `order-${timestamp}`;

  return {
    entity_type: 'ORDER',
    entity_sync_id: entitySyncId,
    operation,
    data: {
      id: order.id ?? 0,
      totalAmount: order.totalAmount ?? '0',
      date: order.date ?? timestamp,
      customerName: order.customerName ?? '',
      paidAmount: order.paidAmount ?? '0',
      paymentMethod: order.paymentMethod ?? 'CASH',
      customerSyncId: order.customerSyncId ?? '',
      previousBalance: order.previousBalance ?? '0',
      transactionAmount: order.transactionAmount ?? '0',
      newBalance: order.newBalance ?? '0',
      paymentStatus: order.paymentStatus ?? 'PAID',
      orderStatus: order.orderStatus ?? 'ACTIVE',
      receivedAmount: order.receivedAmount ?? null,
      updatedAt: timestamp,
    },
    timestamp,
    idempotency_key: makeIdempotencyKey('ORDER', entitySyncId, operation, timestamp),
  };
}

export async function syncBusinessEntity(event: SyncEvent): Promise<{ ok: boolean; queued: number; failed: number }> {
  enqueueLocalEvent(event);
  const result = await flushOutbox();

  return {
    ok: result.failed === 0,
    queued: result.succeeded + result.failed,
    failed: result.failed,
  };
}
