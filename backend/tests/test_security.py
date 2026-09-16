import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.auth.utils import create_access_token
from app.models.base import User
from app.models.entities import Customer, Order

@pytest.fixture
def user1(db):
    from app.auth.utils import get_password_hash
    user = User(username="user1", hashed_password=get_password_hash("pass1"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user

@pytest.fixture
def user2(db):
    from app.auth.utils import get_password_hash
    user = User(username="user2", hashed_password=get_password_hash("pass2"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user

@pytest.fixture
def auth_user1(user1):
    token = create_access_token(data={"sub": user1.username})
    return {"Authorization": f"Bearer {token}"}

@pytest.fixture
def auth_user2(user2):
    token = create_access_token(data={"sub": user2.username})
    return {"Authorization": f"Bearer {token}"}

def test_unauthorized_access(client):
    endpoints = [
        ("/api/v1/analytics/summary", "GET"),
        ("/api/v1/business/orders", "GET"),
        ("/api/v1/business/customers", "GET"),
        ("/api/v1/sync/pull", "POST")
    ]
    for ep, method in endpoints:
        if method == "GET":
            response = client.get(ep)
        else:
            response = client.post(ep, json={})
        assert response.status_code == 401

        # Test with invalid token
        headers = {"Authorization": "Bearer invalid-token"}
        if method == "GET":
            response = client.get(ep, headers=headers)
        else:
            response = client.post(ep, json={}, headers=headers)
        assert response.status_code == 401

def test_data_isolation(client, db, user1, user2, auth_user1, auth_user2):
    # User 1 has a customer
    c1 = Customer(user_id=user1.id, sync_id="u1-cust", display_name="User 1 Customer", normalized_name="user 1 customer", created_at=1000, updated_at=1000)
    db.add(c1)

    # User 2 has a customer
    c2 = Customer(user_id=user2.id, sync_id="u2-cust", display_name="User 2 Customer", normalized_name="user 2 customer", created_at=1000, updated_at=1000)
    db.add(c2)
    db.commit()

    # User 1 should only see their customer
    response = client.get("/api/v1/business/customers", headers=auth_user1)
    data = response.json()
    assert data["total"] == 1
    assert data["items"][0]["display_name"] == "User 1 Customer"

    # User 2 should only see their customer
    response = client.get("/api/v1/business/customers", headers=auth_user2)
    data = response.json()
    assert data["total"] == 1
    assert data["items"][0]["display_name"] == "User 2 Customer"

def test_ledger_isolation(client, db, user1, user2, auth_user1):
    # User 2 has a customer
    c2 = Customer(user_id=user2.id, sync_id="u2-cust", display_name="U2", normalized_name="u2", created_at=1000, updated_at=1000)
    db.add(c2)
    db.commit()

    # User 1 attempts to access User 2's ledger via sync_id
    response = client.get(f"/api/v1/business/ledger/{c2.sync_id}", headers=auth_user1)
    # The endpoint currently returns [] if no records found for that user/sync_id
    assert response.status_code == 200
    assert response.json() == []
