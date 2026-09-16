import pytest
from decimal import Decimal
from app.auth.utils import create_access_token
from app.models.base import User
from app.models.entities import Order, SettlementHistory

@pytest.fixture
def test_user(db):
    from app.auth.utils import get_password_hash
    user = db.query(User).filter(User.username == "testuser").first()
    if not user:
        user = User(username="testuser", hashed_password=get_password_hash("testpass"))
        db.add(user)
        db.commit()
        db.refresh(user)
    return user

@pytest.fixture
def auth_header(test_user):
    token = create_access_token(data={"sub": test_user.username})
    return {"Authorization": f"Bearer {token}"}

def test_decimal_serialization_analytics(client, db, test_user, auth_header):
    # Setup some data with precise decimals
    # Clean up existing data for this user
    db.query(Order).filter(Order.user_id == test_user.id).delete()
    db.query(SettlementHistory).filter(SettlementHistory.user_id == test_user.id).delete()

    # Add an order with precise amount
    order = Order(
        user_id=test_user.id,
        sync_id="precise-order-1",
        total_amount=Decimal("123.45"),
        paid_amount=Decimal("123.45"),
        date=1000,
        customer_name="Test",
        order_status="ACTIVE",
        updated_at=1000
    )
    db.add(order)

    # Add a settlement with precise amount
    settlement = SettlementHistory(
        user_id=test_user.id,
        sync_id="precise-set-1",
        customer_name="Test",
        customer_sync_id="cust-1",
        balance_before=Decimal("0.00"),
        amount_paid=Decimal("123.45"),
        balance_after=Decimal("0.00"),
        timestamp=1000,
        transaction_amount=Decimal("123.45"),
        ledger_entry_type="PAYMENT",
        updated_at=1000
    )
    db.add(settlement)
    db.commit()

    response = client.get("/api/v1/analytics/summary", headers=auth_header)
    assert response.status_code == 200
    data = response.json()

    # CRITICAL: Verify that the numbers are strings in the JSON
    assert isinstance(data["total_revenue"], str)
    assert data["total_revenue"] == "123.45"
    assert isinstance(data["outstanding_debt"], str)
    assert data["outstanding_debt"] == "123.45"

def test_decimal_serialization_business(client, db, test_user, auth_header):
    db.query(Order).filter(Order.user_id == test_user.id).delete()

    order = Order(
        user_id=test_user.id,
        sync_id="precise-biz-1",
        total_amount=Decimal("999999.99"),
        paid_amount=Decimal("0.00"),
        date=1000,
        customer_name="Rich Customer",
        order_status="ACTIVE",
        updated_at=1000
    )
    db.add(order)
    db.commit()

    response = client.get("/api/v1/business/orders", headers=auth_header)
    assert response.status_code == 200
    data = response.json()

    # Verify precision of large value
    assert data["items"][0]["total_amount"] == "999999.99"
    assert isinstance(data["items"][0]["total_amount"], str)

def test_precision_arithmetic_01_02(client, db, test_user, auth_header):
    db.query(Order).filter(Order.user_id == test_user.id).delete()
    db.query(SettlementHistory).filter(SettlementHistory.user_id == test_user.id).delete()

    # 0.1 + 0.2 should be 0.3, not 0.30000000000000004
    # We use settlement_history for summary total_revenue now
    s1 = SettlementHistory(user_id=test_user.id, sync_id="s1", customer_name="T", customer_sync_id="c1", balance_before=Decimal("0"), amount_paid=Decimal("0.1"), balance_after=Decimal("0.1"), timestamp=1000, transaction_amount=Decimal("0.1"), ledger_entry_type="PAYMENT", updated_at=1000)
    s2 = SettlementHistory(user_id=test_user.id, sync_id="s2", customer_name="T", customer_sync_id="c1", balance_before=Decimal("0.1"), amount_paid=Decimal("0.2"), balance_after=Decimal("0.3"), timestamp=1001, transaction_amount=Decimal("0.1"), ledger_entry_type="PAYMENT", updated_at=1001)
    db.add_all([s1, s2])
    db.commit()

    response = client.get("/api/v1/analytics/summary", headers=auth_header)
    assert response.status_code == 200
    data = response.json()
    # Note: Depending on scale, it might be "0.3" or "0.30"
    assert Decimal(data["total_revenue"]) == Decimal("0.3")
    assert isinstance(data["total_revenue"], str)
