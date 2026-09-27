from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import List, Optional
from ..database import get_db
from ..auth.router import get_current_user
from ..models.base import User
from ..models.entities import Order, Customer, SettlementHistory, BeautyTransaction, UpiAccountTransaction
from ..schemas.business import (
    OrderListResponse, CustomerListResponse,
    SettlementSchema, BeautyTransactionListResponse, UpiAccountTransactionSchema,
    UpiAccountTransactionListResponse
)

router = APIRouter(prefix="/business", tags=["business"])

@router.get("/orders", response_model=OrderListResponse)
def get_orders(
    skip: int = 0,
    limit: int = 50,
    status: Optional[str] = None,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    query = db.query(Order).filter(Order.user_id == current_user.id)
    if status:
        query = query.filter(Order.order_status == status)

    orders = query.order_by(Order.date.desc()).offset(skip).limit(limit).all()
    total = query.count()

    return {"items": orders, "total": total}

@router.get("/customers", response_model=CustomerListResponse)
def get_customers(
    skip: int = 0,
    limit: int = 50,
    search: Optional[str] = None,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    query = db.query(Customer).filter(Customer.user_id == current_user.id)
    if search:
        query = query.filter(Customer.display_name.ilike(f"%{search}%"))

    customers = query.order_by(Customer.display_name.asc()).offset(skip).limit(limit).all()
    total = query.count()

    return {"items": customers, "total": total}

@router.get("/ledger/{customer_sync_id}", response_model=List[SettlementSchema])
def get_customer_ledger(
    customer_sync_id: str,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    history = db.query(SettlementHistory).filter(
        SettlementHistory.user_id == current_user.id,
        SettlementHistory.customer_sync_id == customer_sync_id
    ).order_by(SettlementHistory.timestamp.desc()).all()

    return history

@router.get("/beauty-account", response_model=UpiAccountTransactionListResponse)
@router.get("/upi-account", response_model=UpiAccountTransactionListResponse)
def get_upi_account_transactions(
    skip: int = 0,
    limit: int = 50,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    # Canonical UPI transaction ledger is the only active source for wallet activity.
    transactions = db.query(UpiAccountTransaction).filter(UpiAccountTransaction.user_id == current_user.id).order_by(UpiAccountTransaction.timestamp.desc()).offset(skip).limit(limit).all()
    total = db.query(UpiAccountTransaction).filter(UpiAccountTransaction.user_id == current_user.id).count()
    return {"items": transactions, "total": total}

# Backward-compatibility alias. The canonical route is /upi-account.
@router.get("/beauty-account-legacy", response_model=BeautyTransactionListResponse)
def get_beauty_transactions_legacy(
    skip: int = 0,
    limit: int = 50,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    return get_upi_account_transactions(skip=skip, limit=limit, current_user=current_user, db=db)
