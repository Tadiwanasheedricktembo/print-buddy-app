import pytest
from decimal import Decimal
from app.models.entities import Order, SettlementHistory
from app.auth.utils import create_access_token

@pytest.fixture
def test_user(db):
    from app.models.base import User
    from app.auth.utils import get_password_hash
    user = User(username="precision_user", hashed_password=get_password_hash("pass"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user

@pytest.fixture
def auth_header(test_user):
    token = create_access_token(data={"sub": test_user.username})
    return {"Authorization": f"Bearer {token}"}

def test_arithmetic_precision_01_02(client, db, test_user, auth_header):
    # Setup: 0.10 + 0.20 should be 0.30 exactly
    s1 = SettlementHistory(
        user_id=test_user.id,
        sync_id="s1",
        customer_name="A",
        customer_sync_id="c1",
        balance_before=Decimal("0.00"),
        amount_paid=Decimal("0.10"),
        balance_after=Decimal("0.10"),
        timestamp=1000,
        transaction_amount=Decimal("0.10"),
        ledger_entry_type="PAYMENT",
        updated_at=1000
    )
    s2 = SettlementHistory(
        user_id=test_user.id,
        sync_id="s2",
        customer_name="A",
        customer_sync_id="c1",
        balance_before=Decimal("0.10"),
        amount_paid=Decimal("0.20"),
        balance_after=Decimal("0.30"),
        timestamp=2000,
        transaction_amount=Decimal("0.20"),
        ledger_entry_type="PAYMENT",
        updated_at=2000
    )
    db.add_all([s1, s2])
    db.commit()

    response = client.get("/api/v1/analytics/summary", headers=auth_header)
    assert response.status_code == 200
    data = response.json()

    # Assert exact string representation
    assert data["total_revenue"] == "0.30"
    assert isinstance(data["total_revenue"], str)

def test_large_value_precision(client, db, test_user, auth_header):
    # Setup: Trillions
    large_val = Decimal("1234567890123.45")
    s = SettlementHistory(
        user_id=test_user.id,
        sync_id="large-1",
        customer_name="B",
        customer_sync_id="c2",
        balance_before=Decimal("0.00"),
        amount_paid=large_val,
        balance_after=large_val,
        timestamp=1000,
        transaction_amount=large_val,
        ledger_entry_type="PAYMENT",
        updated_at=1000
    )
    db.add(s)
    db.commit()

    response = client.get("/api/v1/analytics/summary", headers=auth_header)
    data = response.json()
    assert data["total_revenue"] == "1234567890123.45"
