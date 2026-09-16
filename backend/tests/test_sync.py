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
