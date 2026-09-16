from sqlalchemy import Column, Integer, String, BigInteger, DateTime, ForeignKey, UniqueConstraint
from sqlalchemy.sql import func
from ..database import Base

class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True)
    hashed_password = Column(String)
    business_name = Column(String, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

class BaseSyncModel(Base):
    __abstract__ = True
    id = Column(Integer, primary_key=True) # Server side ID
    user_id = Column(Integer, ForeignKey("users.id"), index=True)
    sync_id = Column(String, index=True) # Android UUID
    updated_at = Column(BigInteger) # Timestamp (Long from Android)
    deleted_at = Column(BigInteger, nullable=True)
    server_updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

class IdempotencyLog(Base):
    __tablename__ = "idempotency_logs"
    __table_args__ = (
        UniqueConstraint("user_id", "idempotency_key", name="uq_idempotency_user_key"),
    )

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id"), index=True)
    idempotency_key = Column(String, index=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
