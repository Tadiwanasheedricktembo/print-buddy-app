import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.auth.utils import create_access_token
from app.models.base import User
from app.models.entities import Customer

@pytest.fixture
def user_a(db):
    from app.auth.utils import get_password_hash
    user = User(username="user_a", hashed_password=get_password_hash("pass_a"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user

@pytest.fixture
def user_b(db):
    from app.auth.utils import get_password_hash
    user = User(username="user_b", hashed_password=get_password_hash("pass_b"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user

@pytest.fixture
def auth_a(user_a):
    token = create_access_token(data={"sub": user_a.username})
    return {"Authorization": f"Bearer {token}"}

@pytest.fixture
def auth_b(user_b):
    token = create_access_token(data={"sub": user_b.username})
    return {"Authorization": f"Bearer {token}"}

def test_tenant_data_isolation(client, db, user_a, user_b, auth_a, auth_b):
    # 1. User A creates a customer
    db.add(Customer(user_id=user_a.id, sync_id="cust-a", display_name="Tenant A Customer", normalized_name="tenant a customer", created_at=1000, updated_at=1000))
    # 2. User B creates a customer
    db.add(Customer(user_id=user_b.id, sync_id="cust-b", display_name="Tenant B Customer", normalized_name="tenant b customer", created_at=1000, updated_at=1000))
    db.commit()

    # 3. User A pulls changes - should only see cust-a
    response = client.post("/api/v1/sync/pull", json={}, headers=auth_a)
    assert response.status_code == 200
    events = response.json()["events"]
    assert len(events) == 1
    assert events[0]["entity_sync_id"] == "cust-a"

    # 4. User B pulls changes - should only see cust-b
    response = client.post("/api/v1/sync/pull", json={}, headers=auth_b)
    assert response.status_code == 200
    events = response.json()["events"]
    assert len(events) == 1
    assert events[0]["entity_sync_id"] == "cust-b"

def test_unauthorized_access(client):
    endpoints = ["/api/v1/sync/pull", "/api/v1/sync/push", "/api/v1/analytics/summary"]
    for ep in endpoints:
        response = client.post(ep, json={}) if "sync" in ep else client.get(ep)
        assert response.status_code == 401
