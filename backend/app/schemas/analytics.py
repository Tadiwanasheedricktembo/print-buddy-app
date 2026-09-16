from pydantic import BaseModel, field_validator
from typing import List, Optional
from decimal import Decimal

class AnalyticsSummary(BaseModel):
    total_revenue: str
    order_count: int
    outstanding_debt: str
    customer_count: int

    @field_validator("total_revenue", "outstanding_debt", mode="before")
    @classmethod
    def convert_decimal(cls, v):
        if isinstance(v, Decimal):
            return str(v)
        return v

class RevenuePoint(BaseModel):
    date: str
    amount: str

    @field_validator("amount", mode="before")
    @classmethod
    def convert_decimal(cls, v):
        if isinstance(v, Decimal):
            return str(v)
        return v

class ServiceBreakdown(BaseModel):
    service_name: str
    total_amount: str

    @field_validator("total_amount", mode="before")
    @classmethod
    def convert_decimal(cls, v):
        if isinstance(v, Decimal):
            return str(v)
        return v
