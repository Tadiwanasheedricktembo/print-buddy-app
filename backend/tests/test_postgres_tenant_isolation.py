import importlib.util
import os
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

from app.auth.utils import create_access_token, get_password_hash
from app.database import get_db
from app.main import app
from app.models.base import User
from app.models.entities import Customer, Order


# Prepare the recovery drill module for safety helper use
SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "recovery_restore_drill.py"
spec = importlib.util.spec_from_file_location("recovery_restore_drill", SCRIPT_PATH)
recovery_module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = recovery_module
spec.loader.exec_module(recovery_module)


def _pg_bin_dir():
    return os.getenv("PG_BIN_DIR", r"C:\Program Files\PostgreSQL\17\bin")


def _drop_and_create_disposable_db(db_name: str):
    env = {**os.environ, "PGPASSWORD": "postgres"}
    psql = str(Path(_pg_bin_dir()) / "psql.exe")
    dropdb = str(Path(_pg_bin_dir()) / "dropdb.exe")
    createdb = str(Path(_pg_bin_dir()) / "createdb.exe")

    subprocess.run(
        [psql, "-h", "localhost", "-p", "5432", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '{db_name}' AND pid <> pg_backend_pid();"],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    subprocess.run([dropdb, "-h", "localhost", "-p", "5432", "-U", "postgres", "--if-exists", "--force", db_name], env=env, capture_output=True, text=True, check=False)
    result = subprocess.run([createdb, "-h", "localhost", "-p", "5432", "-U", "postgres", db_name], env=env, capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr


def _seed_tenant_data(engine):
    SessionLocal = sessionmaker(bind=engine)
    with SessionLocal() as db:
        user_a = User(username="tenant_a_pg", hashed_password=get_password_hash("pass_a"))
        user_b = User(username="tenant_b_pg", hashed_password=get_password_hash("pass_b"))
        db.add_all([user_a, user_b])
        db.commit()
        db.refresh(user_a)
        db.refresh(user_b)

        customer_a = Customer(user_id=user_a.id, sync_id="cust-a-pg", display_name="Alpha Customer", normalized_name="alpha customer", created_at=1000, updated_at=1000)
        customer_b = Customer(user_id=user_b.id, sync_id="cust-b-pg", display_name="Beta Customer", normalized_name="beta customer", created_at=2000, updated_at=2000)
        db.add_all([customer_a, customer_b])
        db.add_all([
            Order(user_id=user_a.id, sync_id="order-a-pg", total_amount=100.00, paid_amount=50.00, date=1000, customer_name="Alpha Customer", customer_sync_id=customer_a.sync_id, payment_method="UPI", previous_balance=0.00, transaction_amount=50.00, new_balance=50.00, payment_status="PAID", order_status="ACTIVE", received_amount=50.00, updated_at=1000),
            Order(user_id=user_b.id, sync_id="order-b-pg", total_amount=200.00, paid_amount=75.00, date=2000, customer_name="Beta Customer", customer_sync_id=customer_b.sync_id, payment_method="CASH", previous_balance=0.00, transaction_amount=75.00, new_balance=75.00, payment_status="PAID", order_status="ACTIVE", received_amount=75.00, updated_at=2000),
        ])
        db.commit()


def test_postgres_tenant_isolation_requires_user_scoped_access():
    db_name = "tadiwa_restore_test"
    _drop_and_create_disposable_db(db_name)

    url = f"postgresql://postgres:postgres@localhost:5432/{db_name}"
    engine = create_engine(url)
    try:
        from app.database import Base

        Base.metadata.create_all(bind=engine)
        _seed_tenant_data(engine)

        SessionLocal = sessionmaker(bind=engine)

        def override_get_db():
            db = SessionLocal()
            try:
                yield db
            finally:
                db.close()

        app.dependency_overrides[get_db] = override_get_db
        client = TestClient(app)

        token_a = create_access_token(data={"sub": "tenant_a_pg"})
        token_b = create_access_token(data={"sub": "tenant_b_pg"})

        users_response_a = client.get("/api/v1/business/customers", headers={"Authorization": f"Bearer {token_a}"})
        users_response_b = client.get("/api/v1/business/customers", headers={"Authorization": f"Bearer {token_b}"})

        assert users_response_a.status_code == 200
        assert users_response_b.status_code == 200
        assert [row["sync_id"] for row in users_response_a.json()["items"]] == ["cust-a-pg"]
        assert [row["sync_id"] for row in users_response_b.json()["items"]] == ["cust-b-pg"]

        customer_b_sync_id = "cust-b-pg"
        customer_a_sync_id = "cust-a-pg"
        forced_a_cross = client.get(f"/api/v1/business/ledger/{customer_b_sync_id}", headers={"Authorization": f"Bearer {token_a}"})
        forced_b_cross = client.get(f"/api/v1/business/ledger/{customer_a_sync_id}", headers={"Authorization": f"Bearer {token_b}"})

        assert forced_a_cross.status_code == 200
        assert forced_a_cross.json() == []
        assert forced_b_cross.status_code == 200
        assert forced_b_cross.json() == []
    finally:
        app.dependency_overrides.clear()
        engine.dispose()
        subprocess.run([str(Path(_pg_bin_dir()) / "dropdb.exe"), "-h", "localhost", "-p", "5432", "-U", "postgres", "--if-exists", "--force", db_name], env={**os.environ, "PGPASSWORD": "postgres"}, capture_output=True, text=True, check=False)


def test_backup_and_restore_target_safety_guard_rejects_app_db_name():
    assert recovery_module.validate_disposable_target("localhost", 5432, "tadiwa_recovery_test", "postgresql://postgres:postgres@localhost:5432/tadiwa_db") is True
    with pytest.raises(ValueError, match="configured database"):
        recovery_module.validate_disposable_target("localhost", 5432, "tadiwa_db", "postgresql://postgres:postgres@localhost:5432/tadiwa_db")
