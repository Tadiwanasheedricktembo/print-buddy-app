from sqlalchemy import Column, Integer, String, Float, BigInteger, ForeignKey, Boolean, Numeric
from sqlalchemy.orm import relationship
from .base import BaseSyncModel, User

class Customer(BaseSyncModel):
    __tablename__ = "customers"
    display_name = Column(String)
    normalized_name = Column(String, index=True)
    phone_number = Column(String, nullable=True)
    created_at = Column(BigInteger)

class Order(BaseSyncModel):
    __tablename__ = "orders"
    total_amount = Column(Numeric(precision=10, scale=2))
    date = Column(BigInteger)
    customer_name = Column(String)
    paid_amount = Column(Numeric(precision=10, scale=2), default=0.0)
    payment_method = Column(String, default="CASH")
    customer_sync_id = Column(String, index=True, nullable=True)

    previous_balance = Column(Numeric(precision=10, scale=2), default=0.0)
    transaction_amount = Column(Numeric(precision=10, scale=2), default=0.0)
    new_balance = Column(Numeric(precision=10, scale=2), default=0.0)
    payment_status = Column(String, default="PAID")
    order_status = Column(String, default="ACTIVE")
    received_amount = Column(Numeric(precision=10, scale=2), nullable=True)

    items = relationship(
        "OrderItem",
        back_populates="order",
        foreign_keys="OrderItem.order_id",
        cascade="all, delete-orphan"
    )

class OrderItem(BaseSyncModel):
    __tablename__ = "order_items"
    order_id = Column(Integer, ForeignKey("orders.id"), index=True, nullable=True)
    order_sync_id = Column(String, index=True)
    service_name = Column(String)
    price = Column(Numeric(precision=10, scale=2))
    quantity = Column(Integer)

    order = relationship("Order", back_populates="items", foreign_keys=[order_id])

class SettlementHistory(BaseSyncModel):
    __tablename__ = "settlement_history"
    customer_name = Column(String)
    customer_sync_id = Column(String, index=True)
    balance_before = Column(Numeric(precision=10, scale=2))
    amount_paid = Column(Numeric(precision=10, scale=2))
    balance_after = Column(Numeric(precision=10, scale=2))
    timestamp = Column(BigInteger)
    type = Column(String, default="PAYMENT")
    note = Column(String, default="")
    transaction_amount = Column(Numeric(precision=10, scale=2), default=0.0)
    new_balance = Column(Numeric(precision=10, scale=2), default=0.0)
    origin_id = Column(Integer, nullable=True)
    origin_sync_id = Column(String, index=True, nullable=True)
    ledger_entry_type = Column(String, default="")
    is_shadow_duplicate = Column(Boolean, default=False)
    reconciliation_status = Column(String, default="VERIFIED")
    received_amount = Column(Numeric(precision=10, scale=2), nullable=True)

class Expense(BaseSyncModel):
    __tablename__ = "expenses"
    title = Column(String)
    category = Column(String)
    amount = Column(Numeric(precision=10, scale=2))
    timestamp = Column(BigInteger)
    note = Column(String, nullable=True)
    payment_method = Column(String, default="CASH")

class StockItem(BaseSyncModel):
    __tablename__ = "stock_items"
    name = Column(String, index=True)
    current_quantity = Column(Integer)
    low_stock_threshold = Column(Integer, default=10)
    unit = Column(String, default="pcs")

class Note(BaseSyncModel):
    __tablename__ = "notes"
    title = Column(String)
    content = Column(String)
    created_at = Column(BigInteger)

class BeautyTransaction(BaseSyncModel):
    __tablename__ = "beauty_transactions"
    amount = Column(Numeric(precision=10, scale=2))
    type = Column(String)
    note = Column(String, nullable=True)
    timestamp = Column(BigInteger)
    previous_balance = Column(Numeric(precision=10, scale=2), default=0.0)
    transaction_amount = Column(Numeric(precision=10, scale=2), default=0.0)
    new_balance = Column(Numeric(precision=10, scale=2), default=0.0)

class ExternalLedger(BaseSyncModel):
    __tablename__ = "external_ledger"
    transaction_type = Column(String)
    amount = Column(Numeric(precision=10, scale=2))
    timestamp = Column(BigInteger)
    customer_name = Column(String, nullable=True)
    customer_sync_id = Column(String, nullable=True)
    order_sync_id = Column(String, nullable=True)
    note = Column(String, nullable=True)
    account_holder = Column(String, default="Mr Tadiwanashe Edrick Tembo")
    upi_id = Column(String, default="9319994350@ptyes")

class PrinterReference(BaseSyncModel):
    __tablename__ = "printer_references"
    title = Column(String)
    notes = Column(String, nullable=True)
    image_path = Column(String)
    timestamp = Column(BigInteger)
