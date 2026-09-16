from pydantic import BaseModel
from typing import List, Optional, Any, Dict
from datetime import datetime

class SyncEvent(BaseModel):
    entity_type: str
    entity_sync_id: str
    operation: str # CREATE, UPDATE, DELETE
    data: Dict[str, Any]
    timestamp: int # Android updatedAt
    idempotency_key: Optional[str] = None
    server_updated_at: Optional[datetime] = None
    server_id: Optional[int] = None # Added for composite cursor

class PushRequest(BaseModel):
    events: List[SyncEvent]

class PushResponse(BaseModel):
    results: List[Dict[str, Any]]

class PullRequest(BaseModel):
    last_sync_timestamp: Optional[datetime] = None
    last_sync_id: Optional[int] = None # Added for composite cursor
    batch_size: int = 100

class PullResponse(BaseModel):
    events: List[SyncEvent]
    next_cursor: Optional[datetime] = None
    next_cursor_id: Optional[int] = None # Added for composite cursor
