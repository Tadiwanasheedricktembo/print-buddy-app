import importlib.util
import sys
from pathlib import Path

import pytest

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "recovery_restore_drill.py"
spec = importlib.util.spec_from_file_location("recovery_restore_drill", SCRIPT_PATH)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


@pytest.mark.parametrize(
    "database_name",
    ["postgres", "template0", "template1"],
)
def test_recovery_tool_rejects_system_databases(database_name):
    with pytest.raises(ValueError, match="protected system database"):
        module.validate_disposable_target("localhost", 5432, database_name, "postgresql://postgres:postgres@localhost:5432/tadiwa_db")


def test_recovery_tool_rejects_configured_application_database():
    with pytest.raises(ValueError, match="application.*configured database"):
        module.validate_disposable_target("localhost", 5432, "tadiwa_db", "postgresql://postgres:postgres@localhost:5432/tadiwa_db")


def test_recovery_tool_rejects_production_mode(monkeypatch):
    monkeypatch.setenv("ENVIRONMENT", "production")
    with pytest.raises(ValueError, match="production mode"):
        module.validate_disposable_target("localhost", 5432, "tadiwa_recovery_test", "postgresql://postgres:postgres@localhost:5432/tadiwa_db")


def test_recovery_tool_rejects_unknown_and_ambiguous_targets():
    with pytest.raises(ValueError, match="unknown or ambiguous"):
        module.validate_disposable_target("localhost", 5432, "example_db", "postgresql://postgres:postgres@localhost:5432/tadiwa_db")

    with pytest.raises(ValueError, match="unknown or ambiguous"):
        module.validate_disposable_target("localhost", 5432, "prod_backup_db", "postgresql://postgres:postgres@localhost:5432/tadiwa_db")


def test_recovery_tool_accepts_known_disposable_name():
    assert module.validate_disposable_target("localhost", 5432, "tadiwa_recovery_test", "postgresql://postgres:postgres@localhost:5432/tadiwa_db") is True
