import pytest
from decimal import Decimal
from app.models.entities import Order, SettlementHistory, Customer
from app.auth.utils import create_access_token

@pytest.fixture
def test_user(db):
    from app.models.base import User
    from app.auth.utils import get_password_hash
    user = User(username="bizuser", hashed_password=get_password_hash("pass"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user

@pytest.fixture
def auth_header(test_user):
    token = create_access_token(data={"sub": test_user.username})
    return {"Authorization": f"Bearer {token}"}

def test_ledger_balance_progression(client, db, test_user, auth_header):
    # 1. Initial State: Customer owes 0
    cust_sync_id = "cust-123"

    # 2. Add an order (ORDER_POST)
    s1 = SettlementHistory(
        user_id=test_user.id,
        sync_id="sh-1",
        customer_name="Alice",
        customer_sync_id=cust_sync_id,
        balance_before=Decimal("0.00"),
        amount_paid=Decimal("0.00"),
        balance_after=Decimal("100.00"),
        timestamp=1000,
        transaction_amount=Decimal("100.00"),
        new_balance=Decimal("100.00"),
        ledger_entry_type="ORDER_POST",
        updated_at=1000
    )

    # 3. Add a partial payment
    s2 = SettlementHistory(
        user_id=test_user.id,
        sync_id="sh-2",
        customer_name="Alice",
        customer_sync_id=cust_sync_id,
        balance_before=Decimal("100.00"),
        amount_paid=Decimal("40.00"),
        balance_after=Decimal("60.00"),
        timestamp=2000,
        transaction_amount=Decimal("-40.00"),
        new_balance=Decimal("60.00"),
        ledger_entry_type="PAYMENT",
        updated_at=2000
    )

    db.add_all([s1, s2])
    db.commit()

    # Verify Summary
    response = client.get("/api/v1/analytics/summary", headers=auth_header)
    data = response.json()
    assert data["total_revenue"] == "40.00"
    assert data["outstanding_debt"] == "60.00"

    # Verify Ledger
    response = client.get(f"/api/v1/business/ledger/{cust_sync_id}", headers=auth_header)
    ledger = response.json()
    assert len(ledger) == 2
    # Ordered by timestamp desc
    assert ledger[0]["sync_id"] == "sh-2"
    assert ledger[0]["new_balance"] == "60.00"
    assert ledger[1]["new_balance"] == "100.00"

def test_cancelled_order_exclusion(client, db, test_user, auth_header):
    # 1. Valid order
    o1_sync_id = "order-valid"
    s1 = SettlementHistory(
        user_id=test_user.id, sync_id="sh-v", customer_name="A", customer_sync_id="c",
        amount_paid=Decimal("10.00"), transaction_amount=Decimal("-10.00"),
        ledger_entry_type="PAYMENT", origin_sync_id=o1_sync_id, timestamp=1000, updated_at=1000
    )
    o1 = Order(user_id=test_user.id, sync_id=o1_sync_id, order_status="ACTIVE", total_amount=Decimal("10"), paid_amount=Decimal("10"), customer_name="A", date=1000, updated_at=1000)

    # 2. Cancelled order payment
    o2_sync_id = "order-cancelled"
    s2 = SettlementHistory(
        user_id=test_user.id, sync_id="sh-c", customer_name="B", customer_sync_id="c2",
        amount_paid=Decimal("50.00"), transaction_amount=Decimal("-50.00"),
        ledger_entry_type="PAYMENT", origin_sync_id=o2_sync_id, timestamp=2000, updated_at=2000
    )
    o2 = Order(user_id=test_user.id, sync_id=o2_sync_id, order_status="CANCELLED", total_amount=Decimal("50"), paid_amount=Decimal("50"), customer_name="B", date=2000, updated_at=2000)

    db.add_all([s1, s2, o1, o2])
    db.commit()

    response = client.get("/api/v1/analytics/summary", headers=auth_header)
    data = response.json()
    # Revenue should only be 10.00
    assert Decimal(data["total_revenue"]) == Decimal("10.00")
