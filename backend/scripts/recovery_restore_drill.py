#!/usr/bin/env python3
import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlparse

from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker
from app.config import settings

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

PROTECTED_DATABASES = {"postgres", "template0", "template1"}
DISPOSABLE_TARGETS = {
    "tadiwa_recovery_test",
    "tadiwa_restore_test",
    "tadiwa_disposable_test",
}

# import app models after env is configured
os.environ.setdefault("DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/tadiwa_recovery_test")

from app.database import Base
from app.models.base import User, IdempotencyLog
from app.models.entities import (
    BeautyTransaction,
    Customer,
    Expense,
    ExternalLedger,
    Note,
    Order,
    OrderItem,
    PrinterReference,
    SettlementHistory,
    StockItem,
)


def parse_database_url(database_url: str):
    if not database_url:
        return None, None, None
    parsed = urlparse(database_url)
    return parsed.hostname, parsed.port or 5432, (parsed.path or "/").lstrip("/")


def validate_disposable_target(host: str, port: int, database_name: str, configured_database_url: str | None = None):
    target_name = (database_name or "").strip()
    if not target_name:
        raise ValueError("Target database name is required.")
    if target_name.lower() in PROTECTED_DATABASES:
        raise ValueError(f"Refusing to operate on protected system database '{target_name}'.")

    env_name = (os.getenv("ENVIRONMENT") or os.getenv("APP_ENV") or getattr(settings, "ENVIRONMENT", "development") or "development").lower()
    if env_name == "production":
        raise ValueError("Refusing to run recovery tooling in production mode.")

    if configured_database_url:
        configured_host, configured_port, configured_db = parse_database_url(configured_database_url)
        if configured_db and configured_host and configured_port:
            if target_name.lower() == configured_db.lower() and str(host).lower() == str(configured_host).lower() and int(port) == int(configured_port):
                raise ValueError(
                    "Refusing to target the application's configured database. "
                    f"Target {target_name} matches configured DB {configured_db}."
                )

    if str(host).lower() not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError(f"Disposable recovery target host must be explicitly local. Received host '{host}'.")
    if int(port) != 5432:
        raise ValueError(f"Disposable recovery target port must be 5432. Received port '{port}'.")
    if target_name.lower() not in {name.lower() for name in DISPOSABLE_TARGETS}:
        raise ValueError(
            "Refusing to act on an unknown or ambiguous target database. "
            f"'{target_name}' is not in the approved disposable allowlist."
        )
    return True


def run_command(args, cwd=None):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    stdout = (result.stdout or "").strip()
    stderr = (result.stderr or "").strip()
    if stdout:
        print(stdout)
    if stderr:
        print(stderr, file=sys.stderr)
    if result.returncode != 0:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(args)}")
    return stdout, stderr


def create_database_if_needed(host, port, user, password, database, psql_bin, createdb_bin, dropdb_bin):
    env = os.environ.copy()
    env["PGPASSWORD"] = password
    db_exists = subprocess.run(
        [psql_bin, "-h", host, "-p", str(port), "-U", user, "-d", "postgres", "-tAc", f"SELECT 1 FROM pg_database WHERE datname = '{database}';"],
        text=True,
        capture_output=True,
        env=env,
    )
    if db_exists.returncode != 0:
        raise RuntimeError(f"Failed to inspect database existence for '{database}'")
    if db_exists.stdout.strip() == "1":
        return
    createdb = subprocess.run([createdb_bin, "-h", host, "-p", str(port), "-U", user, database], capture_output=True, text=True, env=env)
    if createdb.returncode != 0:
        raise RuntimeError(f"Failed to create database '{database}': {createdb.stderr}")


def drop_database_if_exists(host, port, user, password, database, psql_bin, dropdb_bin):
    env = os.environ.copy()
    env["PGPASSWORD"] = password
    result = subprocess.run(
        [psql_bin, "-h", host, "-p", str(port), "-U", user, "-d", "postgres", "-tAc", f"SELECT 1 FROM pg_database WHERE datname = '{database}';"],
        text=True,
        capture_output=True,
        env=env,
    )
    if result.returncode == 0 and result.stdout.strip() == "1":
        terminate = subprocess.run(
            [psql_bin, "-h", host, "-p", str(port), "-U", user, "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '{database}' AND pid <> pg_backend_pid();"],
            text=True,
            capture_output=True,
            env=env,
        )
        if terminate.returncode != 0:
            raise RuntimeError(f"Failed to terminate active sessions on '{database}': {terminate.stderr}")
        drop = subprocess.run([dropdb_bin, "-h", host, "-p", str(port), "-U", user, "--if-exists", "--force", database], capture_output=True, text=True, env=env)
        if drop.returncode != 0:
            raise RuntimeError(f"Failed to drop database '{database}': {drop.stderr}")


def prepare_database(url):
    engine = create_engine(url)
    Base.metadata.create_all(bind=engine)
    return engine


def build_test_data(session):
    user_a = User(username="tenant_a", hashed_password="hash_a", business_name="Tenant A")
    user_b = User(username="tenant_b", hashed_password="hash_b", business_name="Tenant B")
    session.add_all([user_a, user_b])
    session.flush()

    customer_a = Customer(
        user_id=user_a.id,
        sync_id="cust-a-001",
        updated_at=1700000000000,
        display_name="Alpha Customer",
        normalized_name="alpha customer",
        phone_number="1111111111",
        created_at=1700000000000,
    )
    customer_b = Customer(
        user_id=user_b.id,
        sync_id="cust-b-001",
        updated_at=1700000000100,
        display_name="Beta Customer",
        normalized_name="beta customer",
        phone_number="2222222222",
        created_at=1700000000100,
    )
    session.add_all([customer_a, customer_b])
    session.flush()

    order_a = Order(
        user_id=user_a.id,
        sync_id="order-a-001",
        updated_at=1700000001000,
        total_amount=Decimal("1234.56"),
        date=1700000001000,
        customer_name="Alpha Customer",
        paid_amount=Decimal("500.00"),
        payment_method="UPI",
        customer_sync_id=customer_a.sync_id,
        previous_balance=Decimal("0.00"),
        transaction_amount=Decimal("734.56"),
        new_balance=Decimal("734.56"),
        payment_status="PAID",
        order_status="ACTIVE",
        received_amount=Decimal("500.00"),
    )
    session.add(order_a)
    session.flush()

    order_item_a = OrderItem(
        user_id=user_a.id,
        sync_id="order-item-a-001",
        updated_at=1700000001100,
        order_id=order_a.id,
        order_sync_id=order_a.sync_id,
        service_name="Poster Print",
        price=Decimal("999.99"),
        quantity=1,
    )
    session.add(order_item_a)
    session.flush()

    settlement_a = SettlementHistory(
        user_id=user_a.id,
        sync_id="settlement-a-001",
        updated_at=1700000001200,
        customer_name="Alpha Customer",
        customer_sync_id=customer_a.sync_id,
        balance_before=Decimal("0.00"),
        amount_paid=Decimal("500.00"),
        balance_after=Decimal("734.56"),
        timestamp=1700000001200,
        type="PAYMENT",
        note="tenant a settlement",
        transaction_amount=Decimal("500.00"),
        new_balance=Decimal("734.56"),
        ledger_entry_type="PAYMENT",
        is_shadow_duplicate=False,
        reconciliation_status="VERIFIED",
        received_amount=Decimal("500.00"),
    )
    session.add(settlement_a)

    expense_a = Expense(
        user_id=user_a.id,
        sync_id="expense-a-001",
        updated_at=1700000001300,
        title="Ink Refill",
        category="SUPPLIES",
        amount=Decimal("123.45"),
        timestamp=1700000001300,
        note="tenant a expense",
        payment_method="CASH",
    )
    session.add(expense_a)

    stock_item_a = StockItem(
        user_id=user_a.id,
        sync_id="stock-a-001",
        updated_at=1700000001400,
        name="A4 Paper",
        current_quantity=42,
        low_stock_threshold=10,
        unit="pcs",
    )
    session.add(stock_item_a)

    note_a = Note(
        user_id=user_a.id,
        sync_id="note-a-001",
        updated_at=1700000001500,
        title="Follow up",
        content="important activity",
        created_at=1700000001500,
    )
    session.add(note_a)

    beauty_txn_a = BeautyTransaction(
        user_id=user_a.id,
        sync_id="beauty-a-001",
        updated_at=1700000001600,
        amount=Decimal("0.01"),
        type="TOPUP",
        note="cash addition",
        timestamp=1700000001600,
        previous_balance=Decimal("999999.99"),
        transaction_amount=Decimal("0.01"),
        new_balance=Decimal("1000000.00"),
    )
    session.add(beauty_txn_a)

    external_ledger_a = ExternalLedger(
        user_id=user_a.id,
        sync_id="external-a-001",
        updated_at=1700000001700,
        transaction_type="UPI",
        amount=Decimal("999999.99"),
        timestamp=1700000001700,
        customer_name="Alpha Customer",
        customer_sync_id=customer_a.sync_id,
        order_sync_id=order_a.sync_id,
        note="external payment",
        account_holder="Mr Tadiwanashe Edrick Tembo",
        upi_id="9319994350@ptyes",
    )
    session.add(external_ledger_a)

    printer_ref_a = PrinterReference(
        user_id=user_a.id,
        sync_id="printer-a-001",
        updated_at=1700000001800,
        title="Printer One",
        notes="required for production",
        image_path="/tmp/printer-1.png",
        timestamp=1700000001800,
    )
    session.add(printer_ref_a)

    idempotency_log = IdempotencyLog(
        user_id=user_a.id,
        idempotency_key="sync-key-001",
    )
    session.add(idempotency_log)

    session.commit()
    return {
        "user_a": user_a.id,
        "user_b": user_b.id,
        "customer_a": customer_a.id,
        "customer_b": customer_b.id,
        "order_a": order_a.id,
        "expense_a": expense_a.id,
        "stock_a": stock_item_a.id,
        "beauty_a": beauty_txn_a.id,
    }


def validate_restore(url, expected):
    engine = create_engine(url)
    with engine.begin() as conn:
        counts = {
            "users": conn.execute(text("SELECT COUNT(*) FROM users")).scalar_one(),
            "customers": conn.execute(text("SELECT COUNT(*) FROM customers")).scalar_one(),
            "orders": conn.execute(text("SELECT COUNT(*) FROM orders")).scalar_one(),
            "order_items": conn.execute(text("SELECT COUNT(*) FROM order_items")).scalar_one(),
            "settlement_history": conn.execute(text("SELECT COUNT(*) FROM settlement_history")).scalar_one(),
            "expenses": conn.execute(text("SELECT COUNT(*) FROM expenses")).scalar_one(),
            "stock_items": conn.execute(text("SELECT COUNT(*) FROM stock_items")).scalar_one(),
            "notes": conn.execute(text("SELECT COUNT(*) FROM notes")).scalar_one(),
            "beauty_transactions": conn.execute(text("SELECT COUNT(*) FROM beauty_transactions")).scalar_one(),
            "external_ledger": conn.execute(text("SELECT COUNT(*) FROM external_ledger")).scalar_one(),
            "printer_references": conn.execute(text("SELECT COUNT(*) FROM printer_references")).scalar_one(),
            "idempotency_logs": conn.execute(text("SELECT COUNT(*) FROM idempotency_logs")).scalar_one(),
        }

        values = {
            "order_total": str(conn.execute(text("SELECT total_amount FROM orders WHERE sync_id='order-a-001' ")).scalar_one()),
            "beauty_amount": str(conn.execute(text("SELECT amount FROM beauty_transactions WHERE sync_id='beauty-a-001' ")).scalar_one()),
            "expense_amount": str(conn.execute(text("SELECT amount FROM expenses WHERE sync_id='expense-a-001' ")).scalar_one()),
            "ledger_amount": str(conn.execute(text("SELECT amount FROM external_ledger WHERE sync_id='external-a-001' ")).scalar_one()),
            "user_a_customers": conn.execute(text("SELECT COUNT(*) FROM customers WHERE user_id = (SELECT id FROM users WHERE username='tenant_a')")).scalar_one(),
            "user_b_customers": conn.execute(text("SELECT COUNT(*) FROM customers WHERE user_id = (SELECT id FROM users WHERE username='tenant_b')")).scalar_one(),
        }

    return {"counts": counts, "values": values}


def main():
    parser = argparse.ArgumentParser(description='Run a disposable PostgreSQL backup and restore drill for Tadiwa Print Buddy.')
    parser.add_argument('--host', default=os.getenv('PGHOST', 'localhost'))
    parser.add_argument('--port', default=os.getenv('PGPORT', '5432'))
    parser.add_argument('--user', default=os.getenv('PGUSER', 'postgres'))
    parser.add_argument('--password', default=os.getenv('PGPASSWORD', 'postgres'))
    parser.add_argument('--database', default=os.getenv('PGDATABASE', 'tadiwa_recovery_test'))
    parser.add_argument('--backup-dir', default=str(ROOT / 'backups'))
    parser.add_argument('--bin-dir', default=os.getenv('PG_BIN_DIR', r'C:\Program Files\PostgreSQL\17\bin'))
    parser.add_argument('--reset-target', action='store_true', help='Destroy and recreate the disposable target DB before restore.')
    args = parser.parse_args()

    configured_database_url = os.getenv('DATABASE_URL') or getattr(settings, 'DATABASE_URL', None)
    validate_disposable_target(args.host, int(args.port), args.database, configured_database_url)

    if args.database in {'postgres', 'template0', 'template1'}:
        raise SystemExit('Refusing to use a protected system database as the disposable restore target.')

    psql_bin = os.path.join(args.bin_dir, 'psql.exe')
    createdb_bin = os.path.join(args.bin_dir, 'createdb.exe')
    dropdb_bin = os.path.join(args.bin_dir, 'dropdb.exe')
    pg_dump_bin = os.path.join(args.bin_dir, 'pg_dump.exe')
    pg_restore_bin = os.path.join(args.bin_dir, 'pg_restore.exe')

    for required in [psql_bin, createdb_bin, dropdb_bin, pg_dump_bin, pg_restore_bin]:
        if not os.path.exists(required):
            raise SystemExit(f'Required PostgreSQL tool not found: {required}')

    backup_dir = Path(args.backup_dir)
    backup_dir.mkdir(parents=True, exist_ok=True)

    # initial disposable DB setup
    if args.reset_target:
        drop_database_if_exists(args.host, args.port, args.user, args.password, args.database, psql_bin, dropdb_bin)
    create_database_if_needed(args.host, args.port, args.user, args.password, args.database, psql_bin, createdb_bin, dropdb_bin)

    url = f'postgresql://{args.user}:{args.password}@{args.host}:{args.port}/{args.database}'
    engine = prepare_database(url)
    SessionLocal = sessionmaker(bind=engine)
    with SessionLocal() as session:
        baseline = build_test_data(session)

    session = SessionLocal()
    baseline_count = {
        'users': session.query(User).count(),
        'customers': session.query(Customer).count(),
        'orders': session.query(Order).count(),
        'order_items': session.query(OrderItem).count(),
        'settlement_history': session.query(SettlementHistory).count(),
        'expenses': session.query(Expense).count(),
        'stock_items': session.query(StockItem).count(),
        'idempotency_logs': session.query(IdempotencyLog).count(),
    }
    session.close()

    backup_name = f'{args.database}_{datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%SZ")}.dump'
    backup_path = backup_dir / backup_name
    env = os.environ.copy()
    env['PGPASSWORD'] = args.password
    result = subprocess.run([pg_dump_bin, '-h', args.host, '-p', str(args.port), '-U', args.user, '-d', args.database, '-Fc', '-f', str(backup_path)], capture_output=True, text=True, env=env)
    if result.returncode != 0:
        raise SystemExit(f'Backup failed: {result.stderr}')
    if not backup_path.exists() or backup_path.stat().st_size <= 0:
        raise SystemExit(f'Backup file missing or empty: {backup_path}')

    if args.reset_target:
        engine.dispose()
        drop_database_if_exists(args.host, args.port, args.user, args.password, args.database, psql_bin, dropdb_bin)
        create_database_if_needed(args.host, args.port, args.user, args.password, args.database, psql_bin, createdb_bin, dropdb_bin)

    restore_result = subprocess.run([pg_restore_bin, '-h', args.host, '-p', str(args.port), '-U', args.user, '-d', args.database, '--clean', '--if-exists', str(backup_path)], capture_output=True, text=True, env=env)
    if restore_result.returncode != 0:
        raise SystemExit(f'Restore failed: {restore_result.stderr}')

    restored = validate_restore(url, baseline)
    print(json.dumps({
        'baseline_counts': baseline_count,
        'restored': restored,
        'backup_file': str(backup_path),
    }, indent=2, sort_keys=True))

    if restored['counts']['users'] != baseline_count['users']:
        raise SystemExit('User row count mismatch after restore.')
    if restored['values']['order_total'] != '1234.56':
        raise SystemExit(f'Financial precision mismatch: expected 1234.56 but got {restored["values"]["order_total"]}')
    if restored['values']['beauty_amount'] != '0.01':
        raise SystemExit(f'Financial precision mismatch for beauty transaction: expected 0.01 but got {restored["values"]["beauty_amount"]}')
    if restored['values']['expense_amount'] != '123.45':
        raise SystemExit(f'Expense value mismatch: expected 123.45 but got {restored["values"]["expense_amount"]}')
    if restored['values']['ledger_amount'] != '999999.99':
        raise SystemExit(f'Ledger value mismatch: expected 999999.99 but got {restored["values"]["ledger_amount"]}')

    print('DISPOSABLE RECOVERY DRILL SUCCEEDED')


if __name__ == '__main__':
    main()
