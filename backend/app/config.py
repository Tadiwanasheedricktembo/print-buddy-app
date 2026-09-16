from typing import List

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    ENVIRONMENT: str = "development"
    DEBUG: bool = True
    DATABASE_URL: str = "postgresql://postgres:postgres@localhost:5432/tadiwa_db"
    JWT_SECRET: str = "development-secret-change-me"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 10080  # 7 days
    CORS_ALLOWED_ORIGINS: str = "http://localhost:3000,http://127.0.0.1:3000"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8-sig",
        case_sensitive=False,
        extra="ignore",
    )

    @property
    def cors_allowed_origins(self) -> List[str]:
        if not self.CORS_ALLOWED_ORIGINS:
            return []
        return [origin.strip() for origin in self.CORS_ALLOWED_ORIGINS.split(",") if origin.strip()]

    @field_validator("ENVIRONMENT")
    @classmethod
    def normalize_environment(cls, value: str) -> str:
        return value.strip().lower()


def validate_settings(settings: Settings) -> None:
    env_name = settings.ENVIRONMENT.lower()
    cors_origins = settings.cors_allowed_origins

    if env_name == "production":
        if settings.DEBUG:
            raise ValueError("Production requires DEBUG=false.")
        if settings.JWT_SECRET in {"development-secret-change-me", "your_jwt_secret_here", "changeme", ""}:
            raise ValueError("Production requires a non-default JWT_SECRET.")
        if "localhost" in settings.DATABASE_URL.lower() or "127.0.0.1" in settings.DATABASE_URL.lower():
            raise ValueError("Production requires a non-local DATABASE_URL.")
        if not cors_origins or "*" in cors_origins:
            raise ValueError("Production requires an explicit CORS allowlist.")


settings = Settings()
validate_settings(settings)

