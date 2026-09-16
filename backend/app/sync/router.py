from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy import desc, or_, and_
from typing import List, Dict, Any, Optional
from datetime import datetime
from ..database import get_db
from ..auth.router import get_current_user
from ..models.base import User, IdempotencyLog
from ..models.entities import (
    Customer, Order, OrderItem, SettlementHistory,
    Expense, StockItem, Note, BeautyTransaction,
    ExternalLedger, PrinterReference
)
from ..schemas.sync import PushRequest, PushResponse, PullRequest, PullResponse, SyncEvent

router = APIRouter(prefix="/sync", tags=["sync"])

MODEL_MAP = {
    "CUSTOMER": Customer,
    "ORDER": Order,
    "ORDER_ITEM": OrderItem,
    "SETTLEMENT": SettlementHistory,
    "EXPENSE": Expense,
    "STOCK": StockItem,
    "NOTE": Note,
    "BEAUTY_TRANSACTION": BeautyTransaction,
    "EXTERNAL_LEDGER": ExternalLedger,
    "PRINTER_REFERENCE": PrinterReference
}

def map_data_to_model(entity_type: str, data: Dict[str, Any]):
    mapped = {}
    for k, v in data.items():
        snake_k = "".join(["_" + c.lower() if c.isupper() else c for c in k]).lstrip("_")
        mapped[snake_k] = v
    return mapped

@router.post("/push", response_model=PushResponse)
def push_sync(
    request: PushRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    results = []
    try:
        for event in request.events:
            is_processed = db.query(IdempotencyLog).filter(
                IdempotencyLog.user_id == current_user.id,
                IdempotencyLog.idempotency_key == event.idempotency_key
            ).first()

            if is_processed:
                results.append({"sync_id": event.entity_sync_id, "status": "SUCCESS", "message": "Already processed"})
                continue

            model_class = MODEL_MAP.get(event.entity_type)
            if not model_class:
                results.append({"sync_id": event.entity_sync_id, "status": "ERROR", "message": f"Unknown entity type: {event.entity_type}"})
                continue

            existing = db.query(model_class).filter(
                model_class.sync_id == event.entity_sync_id,
                model_class.user_id == current_user.id
            ).first()

            if existing and existing.updated_at > event.timestamp:
                results.append({"sync_id": event.entity_sync_id, "status": "SERVER_WINS"})
                db.add(IdempotencyLog(user_id=current_user.id, idempotency_key=event.idempotency_key))
                continue

            data = map_data_to_model(event.entity_type, event.data)
            data["user_id"] = current_user.id
            data["sync_id"] = event.entity_sync_id
            data["updated_at"] = event.timestamp

            if event.operation == "DELETE":
                if existing:
                    existing.deleted_at = event.timestamp
                else:
                    new_entity = model_class(**data)
                    new_entity.deleted_at = event.timestamp
                    db.add(new_entity)
            else:
                if existing:
                    for key, value in data.items():
                        if hasattr(existing, key):
                            setattr(existing, key, value)
                    existing.deleted_at = None
                else:
                    new_entity = model_class(**data)
                    db.add(new_entity)

            db.add(IdempotencyLog(user_id=current_user.id, idempotency_key=event.idempotency_key))
            results.append({"sync_id": event.entity_sync_id, "status": "SUCCESS"})

        db.commit()
    except Exception as e:
        db.rollback()
        return {"results": [{"sync_id": "BATCH", "status": "ERROR", "message": str(e)}]}

    return {"results": results}

@router.post("/pull", response_model=PullResponse)
def pull_sync(
    request: PullRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
):
    # Forensic Hardened Pull: Ensure global order and gapless batching with composite cursor
    all_candidates = []

    for entity_type, model_class in MODEL_MAP.items():
        query = db.query(model_class).filter(model_class.user_id == current_user.id)
        if request.last_sync_timestamp:
            if request.last_sync_id:
                 query = query.filter(
                     or_(
                         model_class.server_updated_at > request.last_sync_timestamp,
                         and_(
                             model_class.server_updated_at == request.last_sync_timestamp,
                             model_class.id > request.last_sync_id
                         )
                     )
                 )
            else:
                query = query.filter(model_class.server_updated_at > request.last_sync_timestamp)

        records = query.order_by(model_class.server_updated_at.asc(), model_class.id.asc()).limit(request.batch_size).all()
        for r in records:
            all_candidates.append((r.server_updated_at, r.id, entity_type, r))

    if not all_candidates:
        return {"events": [], "next_cursor": request.last_sync_timestamp, "next_cursor_id": request.last_sync_id}

    # Sort all candidates globally by (server_updated_at, id)
    all_candidates.sort(key=lambda x: (x[0], x[1]))

    # Take actual batch
    batch_records = all_candidates[:request.batch_size]

    events = []
    for server_ts, server_id, entity_type, record in batch_records:
        data = {}
        for c in record.__table__.columns:
            val = getattr(record, c.name)
            camel_k = "".join(x.capitalize() or "_" for x in c.name.split("_"))
            camel_k = camel_k[0].lower() + camel_k[1:]
            if hasattr(val, "to_eng_string"): # Decimal
                data[camel_k] = str(val)
            else:
                data[camel_k] = val

        events.append(SyncEvent(
            entity_type=entity_type,
            entity_sync_id=record.sync_id,
            operation="DELETE" if record.deleted_at else ("UPDATE" if request.last_sync_timestamp else "CREATE"),
            data=data,
            timestamp=record.updated_at,
            server_updated_at=record.server_updated_at,
            server_id=record.id
        ))

    last_event = events[-1]
    return {
        "events": events,
        "next_cursor": last_event.server_updated_at,
        "next_cursor_id": last_event.server_id
    }
