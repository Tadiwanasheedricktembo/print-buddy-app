import pytest

from app import config


def test_production_rejects_default_jwt_secret(monkeypatch):
    monkeypatch.setenv("ENVIRONMENT", "production")
    monkeypatch.setenv("DATABASE_URL", "postgresql://prod-user:prod-pass@prod-db:5432/tadiwa_prod")
    monkeypatch.setenv("JWT_SECRET", "your_jwt_secret_here")
    monkeypatch.setenv("DEBUG", "false")

    with pytest.raises(ValueError, match="JWT_SECRET"):
        config.validate_settings(config.Settings())


def test_development_allows_local_defaults(monkeypatch):
    monkeypatch.setenv("ENVIRONMENT", "development")
    monkeypatch.setenv("DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/tadiwa_db")
    monkeypatch.setenv("JWT_SECRET", "dev-secret")
    monkeypatch.setenv("DEBUG", "true")

    settings = config.Settings()

    assert settings.ENVIRONMENT == "development"
    assert settings.DEBUG is True
    assert any("localhost" in origin for origin in settings.cors_allowed_origins)


def test_cors_values_are_split_into_allowlist(monkeypatch):
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://app.example.com, https://admin.example.com")

    settings = config.Settings()

    assert settings.cors_allowed_origins == [
        "https://app.example.com",
        "https://admin.example.com",
    ]
