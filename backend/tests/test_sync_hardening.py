import pytest
from decimal import Decimal
from app.models.entities import Order, SettlementHistory
from app.auth.utils import create_access_token

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
