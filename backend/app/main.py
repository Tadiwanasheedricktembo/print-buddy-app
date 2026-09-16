from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from .database import engine, Base
from .models import base, entities
from .config import settings, validate_settings

validate_settings(settings)

# Initialize database tables
base.Base.metadata.create_all(bind=engine)

app = FastAPI(title="Tadiwa Print Buddy API")

allowed_origins = settings.cors_allowed_origins

app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from .auth.router import router as auth_router
from .sync.router import router as sync_router
from .routers.analytics import router as analytics_router
from .routers.business import router as business_router

app.include_router(auth_router, prefix="/api/v1")
app.include_router(sync_router, prefix="/api/v1")
app.include_router(analytics_router, prefix="/api/v1")
app.include_router(business_router, prefix="/api/v1")
