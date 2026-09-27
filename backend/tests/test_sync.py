import time
import pytest

def get_auth_header(client, username, password):
    client.post("/api/v1/auth/register", json={"username": username, "password": password})
    res = client.post("/api/v1/auth/login", data={"username": username, "password": password})
    return {"Authorization": f"Bearer {res.json()['access_token']}"}

def test_push_customer(client):
    headers = get_auth_header(client, "user1", "pass1")

    sync_id = "cust-123"
    event = {
        "entity_type": "CUSTOMER",
        "entity_sync_id": sync_id,
        "operation": "CREATE",
        "data": {
            "displayName": "Test Customer",
            "normalizedName": "test customer",
            "phoneNumber": "1234567890",
            "createdAt": int(time.time() * 1000)
        },
        "timestamp": int(time.time() * 1000),
        "idempotency_key": "idemp-cust-123"
    }

    response = client.post("/api/v1/sync/push", json={"events": [event]}, headers=headers)
    assert response.status_code == 200
    assert response.json()["results"][0]["status"] == "SUCCESS"


def test_manual_upi_account_order_and_canonical_transaction_sync_round_trip(client):
    headers = get_auth_header(client, "upi-account-sync", "pass1")
    sync_id = "manual-upi-canonical-sync"
    timestamp = int(time.time() * 1000)
    events = [
        {
            "entity_type": "UPI_ACCOUNT_TRANSACTION",
            "entity_sync_id": sync_id,
            "operation": "CREATE",
            "data": {
                "id": 91,
                "amount": "1000.00",
                "type": "ADD",
                "note": "Manual business receipt",
                "timestamp": timestamp,
                "previousBalance": "0.00",
                "transactionAmount": "1000.00",
                "newBalance": "1000.00",
                "syncId": sync_id,
                "updatedAt": timestamp,
            },
            "timestamp": timestamp,
            "idempotency_key": "manual-upi-wallet-create",
        },
        {
            "entity_type": "ORDER",
            "entity_sync_id": sync_id,
            "operation": "CREATE",
            "data": {
                "id": 91,
                "totalAmount": "1000.00",
                "date": timestamp,
                "customerName": "UPI Account",
                "paidAmount": "1000.00",
                "paymentMethod": "UPI",
                "customerId": 0,
                "customerSyncId": "",
                "previousBalance": "0.00",
                "transactionAmount": "0.00",
                "newBalance": "0.00",
                "paymentStatus": "PAID",
                "orderStatus": "ACTIVE",
                "receivedAmount": None,
                "syncId": sync_id,
                "updatedAt": timestamp,
            },
            "timestamp": timestamp,
            "idempotency_key": "manual-upi-order-create",
        },
        {
            "entity_type": "SETTLEMENT",
            "entity_sync_id": "manual-upi-settlement-sync",
            "operation": "CREATE",
            "data": {
                "customerName": "UPI Account",
                "customerSyncId": "",
                "balanceBefore": "0.00",
                "amountPaid": "1000.00",
                "balanceAfter": "1000.00",
                "timestamp": timestamp,
                "type": "UPI_ACCOUNT",
                "note": "Manual business receipt",
                "transactionAmount": "1000.00",
                "newBalance": "1000.00",
                "originId": 91,
                "originSyncId": sync_id,
                "ledgerEntryType": "UPI_ACCOUNT_TOPUP",
                "isShadowDuplicate": False,
                "reconciliationStatus": "VERIFIED",
                "receivedAmount": None,
                "paymentMethod": "UPI",
                "syncId": "manual-upi-settlement-sync",
                "updatedAt": timestamp,
            },
            "timestamp": timestamp,
            "idempotency_key": "manual-upi-settlement-create",
        },
    ]

    pushed = client.post("/api/v1/sync/push", json={"events": events}, headers=headers)
    assert pushed.status_code == 200
    assert all(result["status"] == "SUCCESS" for result in pushed.json()["results"])

    pulled = client.post("/api/v1/sync/pull", json={}, headers=headers)
    assert pulled.status_code == 200
    remote_events = pulled.json()["events"]
    remote_order = next(event for event in remote_events if event["entity_type"] == "ORDER")
    remote_wallet = next(event for event in remote_events if event["entity_type"] == "UPI_ACCOUNT_TRANSACTION")
    remote_settlement = next(event for event in remote_events if event["entity_type"] == "SETTLEMENT")
    assert remote_order["data"]["syncId"] == sync_id
    assert remote_order["data"]["customerName"] == "UPI Account"
    assert remote_order["data"]["paidAmount"] == "1000.00"
    assert remote_wallet["data"]["syncId"] == sync_id
    assert remote_settlement["data"]["isShadowDuplicate"] is False

    summary = client.get("/api/v1/analytics/summary", headers=headers).json()
    assert summary["total_revenue"] == "1000.00"
    assert summary["order_count"] == 1
    assert summary["outstanding_debt"] == "0.00"

def test_ownership_isolation(client):
    headers1 = get_auth_header(client, "user1", "pass1")
    headers2 = get_auth_header(client, "user2", "pass2")

    sync_id = "shared-id"
    event1 = {
        "entity_type": "NOTE",
        "entity_sync_id": sync_id,
        "operation": "CREATE",
        "data": {"title": "User 1 Note", "content": "Secret 1", "createdAt": 1000},
        "timestamp": 1000,
        "idempotency_key": "user1-note-1"
    }

    # User 1 pushes
    client.post("/api/v1/sync/push", json={"events": [event1]}, headers=headers1)

    # User 2 tries to pull - should be empty
    pull_res = client.post("/api/v1/sync/pull", json={}, headers=headers2)
    assert len(pull_res.json()["events"]) == 0

    # User 2 tries to update User 1's record by same sync_id - should create a new record for User 2
    event2 = {
        "entity_type": "NOTE",
        "entity_sync_id": sync_id,
        "operation": "CREATE",
        "data": {"title": "User 2 Note", "content": "Secret 2", "createdAt": 2000},
        "timestamp": 2000,
        "idempotency_key": "user2-note-1"
    }
    push_res2 = client.post("/api/v1/sync/push", json={"events": [event2]}, headers=headers2)
    assert push_res2.json()["results"][0]["status"] == "SUCCESS"

    # Verify User 1 still has their own note
    pull_res1 = client.post("/api/v1/sync/pull", json={}, headers=headers1)
    assert pull_res1.json()["events"][0]["data"]["title"] == "User 1 Note"

def test_idempotency(client):
    headers = get_auth_header(client, "user1", "pass1")
    sync_id = "idemp-1"
    event = {
        "entity_type": "NOTE",
        "entity_sync_id": sync_id,
        "operation": "CREATE",
        "data": {"title": "Test", "content": "X", "createdAt": 1000},
        "timestamp": 1000,
        "idempotency_key": "idemp-key-1"
    }

    # Push twice
    client.post("/api/v1/sync/push", json={"events": [event]}, headers=headers)
    response = client.post("/api/v1/sync/push", json={"events": [event]}, headers=headers)

    assert response.status_code == 200
    assert response.json()["results"][0]["status"] == "SUCCESS"

    # Verify only one record
    pull_res = client.post("/api/v1/sync/pull", json={}, headers=headers)
    assert len(pull_res.json()["events"]) == 1


def test_idempotency_is_scoped_per_user(client):
    headers1 = get_auth_header(client, "user1", "pass1")
    headers2 = get_auth_header(client, "user2", "pass2")

    event = {
        "entity_type": "NOTE",
        "entity_sync_id": "shared-idemp",
        "operation": "CREATE",
        "data": {"title": "Test", "content": "X", "createdAt": 1000},
        "timestamp": 1000,
        "idempotency_key": "same-key-for-both-users"
    }

    first = client.post("/api/v1/sync/push", json={"events": [event]}, headers=headers1)
    second = client.post("/api/v1/sync/push", json={"events": [event]}, headers=headers2)

    assert first.status_code == 200
    assert second.status_code == 200
    assert second.json()["results"][0]["status"] == "SUCCESS"

    pull1 = client.post("/api/v1/sync/pull", json={}, headers=headers1)
    pull2 = client.post("/api/v1/sync/pull", json={}, headers=headers2)

    assert len(pull1.json()["events"]) == 1
    assert len(pull2.json()["events"]) == 1


def test_conflict_server_wins(client):
    headers = get_auth_header(client, "user1", "pass1")
    sync_id = "conflict-1"

    # Push initial
    event1 = {
        "entity_type": "NOTE",
        "entity_sync_id": sync_id,
        "operation": "CREATE",
        "data": {"title": "V1", "content": "X", "createdAt": 1000},
        "timestamp": 2000,
        "idempotency_key": "conflict-v1"
    }
    client.post("/api/v1/sync/push", json={"events": [event1]}, headers=headers)

    # Push older update
    event2 = {
        "entity_type": "NOTE",
        "entity_sync_id": sync_id,
        "operation": "UPDATE",
        "data": {"title": "V0", "content": "X", "createdAt": 1000},
        "timestamp": 1500,
        "idempotency_key": "conflict-v0"
    }
    response = client.post("/api/v1/sync/push", json={"events": [event2]}, headers=headers)
    assert response.json()["results"][0]["status"] == "SERVER_WINS"

def test_pull_incremental(client):
    headers = get_auth_header(client, "user1", "pass1")

    # 1. Create a record
    sync_id = "inc-1"
    event = {
        "entity_type": "NOTE",
        "entity_sync_id": sync_id,
        "operation": "CREATE",
        "data": {"title": "V1", "content": "X", "createdAt": 1000},
        "timestamp": 1000,
        "idempotency_key": "inc-key-1"
    }
    client.post("/api/v1/sync/push", json={"events": [event]}, headers=headers)

    # 2. Pull all
    pull1 = client.post("/api/v1/sync/pull", json={}, headers=headers)
    assert len(pull1.json()["events"]) == 1
    cursor = pull1.json()["next_cursor"]
    assert cursor is not None

    time.sleep(1.1) # SQLite now() resolution is 1s by default usually

    # 3. Pull incremental (should be empty)
    pull2 = client.post("/api/v1/sync/pull", json={"last_sync_timestamp": cursor}, headers=headers)
    assert len(pull2.json()["events"]) == 0

    # 4. Create another record
    event2 = {
        "entity_type": "NOTE",
        "entity_sync_id": "inc-2",
        "operation": "CREATE",
        "data": {"title": "V2", "content": "Y", "createdAt": 2000},
        "timestamp": 2000,
        "idempotency_key": "inc-key-2"
    }
    client.post("/api/v1/sync/push", json={"events": [event2]}, headers=headers)

    # 5. Pull incremental again (should have 1 new record)
    pull3 = client.post("/api/v1/sync/pull", json={"last_sync_timestamp": cursor}, headers=headers)
    assert len(pull3.json()["events"]) == 1
    assert pull3.json()["events"][0]["entity_sync_id"] == "inc-2"
