from pydantic import BaseModel, field_validator
from typing import List, Optional
from decimal import Decimal

class OrderSchema(BaseModel):
    id: int
    total_amount: str
    date: int
    customer_name: str
    paid_amount: str
    payment_method: str
    customer_sync_id: Optional[str]
    previous_balance: str
    transaction_amount: str
    new_balance: str
    payment_status: str
    order_status: str
    received_amount: Optional[str]
    sync_id: str

    @field_validator("total_amount", "paid_amount", "previous_balance", "transaction_amount", "new_balance", "received_amount", mode="before")
    @classmethod
    def convert_decimal(cls, v):
        if isinstance(v, Decimal):
            return str(v)
        return v

    class Config:
        from_attributes = True

class OrderListResponse(BaseModel):
    items: List[OrderSchema]
    total: int

class CustomerSchema(BaseModel):
    id: int
    display_name: str
    normalized_name: str
    phone_number: Optional[str]
    created_at: int
    sync_id: str

    class Config:
        from_attributes = True

class CustomerListResponse(BaseModel):
    items: List[CustomerSchema]
    total: int

class SettlementSchema(BaseModel):
    id: int
    customer_name: str
    customer_sync_id: str
    balance_before: str
    amount_paid: str
    balance_after: str
    timestamp: int
    type: str
    note: str
    transaction_amount: str
    new_balance: str
    origin_id: Optional[int]
    origin_sync_id: Optional[str]
    ledger_entry_type: str
    reconciliation_status: str
    received_amount: Optional[str]
    sync_id: str

    @field_validator("balance_before", "amount_paid", "balance_after", "transaction_amount", "new_balance", "received_amount", mode="before")
    @classmethod
    def convert_decimal(cls, v):
        if isinstance(v, Decimal):
            return str(v)
        return v

    class Config:
        from_attributes = True

class BeautyTransactionSchema(BaseModel):
    id: int
    amount: str
    type: str
    note: Optional[str]
    timestamp: int
    previous_balance: str
    transaction_amount: str
    new_balance: str
    sync_id: str

    @field_validator("amount", "previous_balance", "transaction_amount", "new_balance", mode="before")
    @classmethod
    def convert_decimal(cls, v):
        if isinstance(v, Decimal):
            return str(v)
        return v

    class Config:
        from_attributes = True

class BeautyTransactionListResponse(BaseModel):
    items: List[BeautyTransactionSchema]
    total: int
