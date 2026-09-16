from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import func, case
from typing import List
from datetime import datetime, timedelta
from ..database import get_db
from ..auth.router import get_current_user
from ..models.base import User
from ..models.entities import Order, SettlementHistory, Customer, OrderItem
from ..schemas.analytics import AnalyticsSummary, RevenuePoint, ServiceBreakdown

router = APIRouter(prefix="/analytics", tags=["analytics"])

@router.get("/summary", response_model=AnalyticsSummary)
def get_summary(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    # 1. Total Revenue (Authoritative - Money In from Ledger)
    # Logic: settled_amount from settlement_history where type in ['PAYMENT', 'CREDIT']
    # and if origin_id is present, the corresponding order must not be cancelled.

    revenue_query = db.query(func.sum(SettlementHistory.amount_paid)).outerjoin(
        Order, SettlementHistory.origin_sync_id == Order.sync_id
    ).filter(
        SettlementHistory.user_id == current_user.id,
        SettlementHistory.ledger_entry_type.in_(["PAYMENT", "CREDIT"]),
        SettlementHistory.deleted_at == None,
        (Order.id == None) | ((Order.order_status == "ACTIVE") & (Order.deleted_at == None))
    )

    total_rev = revenue_query.scalar() or 0

    # 2. Total Orders (Active)
    order_count = db.query(func.count(Order.id)).filter(
        Order.user_id == current_user.id,
        Order.order_status == "ACTIVE",
        Order.deleted_at == None
    ).scalar() or 0

    # 3. Outstanding Debt (Authoritative - derived from transaction deltas)
    # SUM(transaction_amount) from settlement_history
    outstanding = db.query(func.sum(SettlementHistory.transaction_amount)).filter(
        SettlementHistory.user_id == current_user.id,
        SettlementHistory.deleted_at == None
    ).scalar() or 0

    # 4. Total Customers
    customer_count = db.query(func.count(Customer.id)).filter(
        Customer.user_id == current_user.id,
        Customer.deleted_at == None
    ).scalar() or 0

    return {
        "total_revenue": total_rev,
        "order_count": order_count,
        "outstanding_debt": outstanding,
        "customer_count": customer_count
    }

@router.get("/revenue-chart", response_model=List[RevenuePoint])
def get_revenue_chart(days: int = 30, current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    start_date = datetime.utcnow() - timedelta(days=days)

    points = db.query(
        func.date(func.to_timestamp(SettlementHistory.timestamp / 1000)).label("day"),
        func.sum(SettlementHistory.amount_paid).label("rev")
    ).outerjoin(
        Order, SettlementHistory.origin_sync_id == Order.sync_id
    ).filter(
        SettlementHistory.user_id == current_user.id,
        SettlementHistory.ledger_entry_type.in_(["PAYMENT", "CREDIT"]),
        SettlementHistory.deleted_at == None,
        (Order.id == None) | ((Order.order_status == "ACTIVE") & (Order.deleted_at == None)),
        SettlementHistory.timestamp >= start_date.timestamp() * 1000
    ).group_by("day").order_by("day").all()

    return [{"date": str(p.day), "amount": p.rev} for p in points]

@router.get("/service-breakdown", response_model=List[ServiceBreakdown])
def get_service_breakdown(current_user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    breakdown = db.query(
        OrderItem.service_name,
        func.sum(OrderItem.price * OrderItem.quantity).label("total")
    ).join(Order, Order.sync_id == OrderItem.order_sync_id).filter(
        Order.user_id == current_user.id,
        Order.order_status == "ACTIVE",
        Order.deleted_at == None,
        OrderItem.deleted_at == None
    ).group_by(OrderItem.service_name).all()

    return [{"service_name": b.service_name, "total_amount": b.total} for b in breakdown]
