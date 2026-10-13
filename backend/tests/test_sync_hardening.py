import pytest
from decimal import Decimal
from sqlalchemy import event as sqlalchemy_event
from sqlalchemy.exc import IntegrityError
from app.models.base import IdempotencyLog, User
from app.models.entities import BeautyTransaction, Note, Order, SettlementHistory
from app.auth.utils import create_access_token, get_password_hash

@pytest.fixture
def test_user(db):
    from app.models.base import User
    from app.auth.utils import get_password_hash
    user = User(username="sync_user", hashed_password=get_password_hash("pass"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user

@pytest.fixture
def auth_header(test_user):
    token = create_access_token(data={"sub": test_user.username})
    return {"Authorization": f"Bearer {token}"}

def test_beauty_transaction_push_pull_and_user_isolation(client, db, test_user, auth_header):
    second_user = User(username="other_sync_user", hashed_password=get_password_hash("pass"))
    db.add(second_user)
    db.commit()
    db.refresh(second_user)
    second_token = create_access_token(data={"sub": second_user.username})
    second_header = {"Authorization": f"Bearer {second_token}"}
    event = {
        "entity_type": "BEAUTY_TRANSACTION",
        "entity_sync_id": "beauty-sync-1",
        "operation": "CREATE",
        "data": {
            "amount": "12.34",
            "type": "ADD",
            "note": "Decimal precision check",
            "timestamp": 1000,
            "previousBalance": "0.00",
            "transactionAmount": "12.34",
            "newBalance": "12.34",
        },
        "timestamp": 1001,
        "idempotency_key": "beauty-user-one",
    }

    pushed = client.post("/api/v1/sync/push", json={"events": [event]}, headers=auth_header)

    assert pushed.status_code == 200
    assert pushed.json()["results"] == [{
        "sync_id": "beauty-sync-1",
        "idempotency_key": "beauty-user-one",
        "status": "SUCCESS",
    }]
    saved = db.query(BeautyTransaction).filter_by(user_id=test_user.id).one()
    assert saved.amount == Decimal("12.34")
    assert saved.transaction_amount == Decimal("12.34")

    first_pull = client.post("/api/v1/sync/pull", json={}, headers=auth_header)
    second_pull = client.post("/api/v1/sync/pull", json={}, headers=second_header)
    assert [item["entity_type"] for item in first_pull.json()["events"]] == ["BEAUTY_TRANSACTION"]
    assert first_pull.json()["events"][0]["data"]["amount"] == "12.34"
    assert second_pull.json()["events"] == []

    second_event = {**event, "idempotency_key": "beauty-user-two"}
    second_push = client.post(
        "/api/v1/sync/push",
        json={"events": [second_event]},
        headers=second_header,
    )
    assert second_push.json()["results"][0]["status"] == "SUCCESS"
    assert db.query(BeautyTransaction).count() == 2


def test_mixed_validity_batch_has_per_event_results(client, db, auth_header):
    events = [
        {
            "entity_type": "UNSUPPORTED_ENTITY",
            "entity_sync_id": "unsupported-1",
            "operation": "CREATE",
            "data": {},
            "timestamp": 1000,
            "idempotency_key": "unsupported-key",
        },
        {
            "entity_type": "NOTE",
            "entity_sync_id": "missing-key",
            "operation": "CREATE",
            "data": {},
            "timestamp": 1000,
        },
        {
            "entity_type": "NOTE",
            "entity_sync_id": "valid-note-1",
            "operation": "CREATE",
            "data": {"title": "Valid", "content": "Saved", "createdAt": 1000},
            "timestamp": 1000,
            "idempotency_key": "valid-note-key",
        },
    ]

    response = client.post("/api/v1/sync/push", json={"events": events}, headers=auth_header)

    assert response.status_code == 200
    results = response.json()["results"]
    assert [(item["idempotency_key"], item["status"]) for item in results] == [
        ("unsupported-key", "ERROR"),
        (None, "ERROR"),
        ("valid-note-key", "SUCCESS"),
    ]
    assert "Idempotency key is required" in results[1]["message"]
    assert db.query(Note).filter_by(sync_id="valid-note-1").count() == 1


def test_duplicate_batch_keys_are_rejected_and_safe_to_retry(client, db, test_user, auth_header):
    event = {
        "entity_type": "BEAUTY_TRANSACTION",
        "entity_sync_id": "duplicate-beauty-1",
        "operation": "CREATE",
        "data": {
            "amount": "0.10",
            "type": "ADD",
            "timestamp": 1000,
            "previousBalance": "0.00",
            "transactionAmount": "0.10",
            "newBalance": "0.10",
        },
        "timestamp": 1000,
        "idempotency_key": "duplicate-key",
    }
    duplicate = {
        **event,
        "entity_sync_id": "duplicate-beauty-2",
        "data": {**event["data"], "amount": "0.20", "transactionAmount": "0.20", "newBalance": "0.20"},
    }

    rejected = client.post(
        "/api/v1/sync/push",
        json={"events": [event, duplicate]},
        headers=auth_header,
    )
    assert [item["status"] for item in rejected.json()["results"]] == ["ERROR", "ERROR"]
    assert db.query(BeautyTransaction).filter_by(user_id=test_user.id).count() == 0

    accepted = client.post("/api/v1/sync/push", json={"events": [event]}, headers=auth_header)
    replayed = client.post("/api/v1/sync/push", json={"events": [event]}, headers=auth_header)
    assert accepted.json()["results"][0]["status"] == "SUCCESS"
    assert replayed.json()["results"][0]["status"] == "SUCCESS"
    assert replayed.json()["results"][0]["idempotency_key"] == "duplicate-key"
    assert db.query(BeautyTransaction).filter_by(user_id=test_user.id).count() == 1


def test_database_failure_rolls_back_batch_and_retry_succeeds(client, db, test_user, auth_header):
    event = {
        "entity_type": "NOTE",
        "entity_sync_id": "rollback-note-1",
        "operation": "CREATE",
        "data": {"title": "Retry", "content": "After failure", "createdAt": 1000},
        "timestamp": 1000,
        "idempotency_key": "rollback-note-key",
    }

    def fail_before_flush(session, flush_context, instances):
        raise IntegrityError("injected failure", {}, RuntimeError("injected"))

    sqlalchemy_event.listen(db, "before_flush", fail_before_flush)
    try:
        failed = client.post("/api/v1/sync/push", json={"events": [event]}, headers=auth_header)
    finally:
        sqlalchemy_event.remove(db, "before_flush", fail_before_flush)

    assert failed.status_code == 200
    assert failed.json()["results"][0]["status"] == "BATCH_ERROR"
    assert db.query(Note).filter_by(user_id=test_user.id).count() == 0
    assert db.query(IdempotencyLog).filter_by(user_id=test_user.id).count() == 0

    retried = client.post("/api/v1/sync/push", json={"events": [event]}, headers=auth_header)
    assert retried.json()["results"][0]["status"] == "SUCCESS"
    assert db.query(Note).filter_by(user_id=test_user.id).count() == 1


def test_push_idempotency(client, db, test_user, auth_header):
    # Setup a push request
    sync_id = "idemp-1"
    idemp_key = "token-1"

    event = {
        "entity_type": "CUSTOMER",
        "entity_sync_id": sync_id,
        "operation": "CREATE",
        "data": {
            "displayName": "Alice",
            "normalizedName": "alice"
        },
        "timestamp": 1000,
        "idempotency_key": idemp_key
    }

    request_data = {"events": [event]}

    # First push
    response = client.post("/api/v1/sync/push", json=request_data, headers=auth_header)
    assert response.status_code == 200
    assert response.json()["results"][0]["status"] == "SUCCESS"

    # Second push (identical)
    response = client.post("/api/v1/sync/push", json=request_data, headers=auth_header)
    assert response.status_code == 200
    # Should be reported as success (already processed)
    assert response.json()["results"][0]["status"] == "SUCCESS"

    # Verify only one customer exists
    from app.models.entities import Customer
    count = db.query(Customer).filter(Customer.user_id == test_user.id).count()
    assert count == 1

def test_sync_conflict_server_wins(client, db, test_user, auth_header):
    # 1. Server has a newer record
    from app.models.entities import Customer
    c = Customer(
        user_id=test_user.id,
        sync_id="conf-1",
        display_name="Server Version",
        normalized_name="server version",
        updated_at=2000,
        created_at=1000
    )
    db.add(c)
    db.commit()

    # 2. Client pushes an older record
    event = {
        "entity_type": "CUSTOMER",
        "entity_sync_id": "conf-1",
        "operation": "UPDATE",
        "data": {
            "displayName": "Client Version",
            "normalizedName": "client version"
        },
        "timestamp": 1500, # Older than server (2000)
        "idempotency_key": "idemp-conf"
    }

    response = client.post("/api/v1/sync/push", json={"events": [event]}, headers=auth_header)
    assert response.status_code == 200
    assert response.json()["results"][0]["status"] == "SERVER_WINS"

    # 3. Verify server data is untouched
    db.refresh(c)
    assert c.display_name == "Server Version"
