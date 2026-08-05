"""Static deployment checks for the Python AI runtime credential boundary."""

from __future__ import annotations

from pathlib import Path
import re
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPOSITORY_ROOT / "docker-compose.yml"


def _service_block(compose: str, service: str) -> str:
    """Returns one top-level Compose service block without requiring a YAML dependency in tests."""
    match = re.search(rf"(?ms)^  {re.escape(service)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)", compose)
    if match is None:
        raise AssertionError(f"Compose service is missing: {service}")
    return match.group(1)


class ComposeRuntimeSecurityTest(unittest.TestCase):
    """Ensures production worker credentials stay narrower than the Java control plane credentials."""

    def test_ai_worker_uses_dedicated_database_identity_without_dns_override(self):
        compose = COMPOSE_FILE.read_text(encoding="utf-8")
        worker = _service_block(compose, "ai-worker")

        self.assertNotIn("MYSQL_ROOT_PASSWORD", worker)
        self.assertIn("MATH_AGENT_AI_RUNTIME_DB_USERNAME", worker)
        self.assertIn("MATH_AGENT_AI_RUNTIME_DB_PASSWORD", worker)
        self.assertNotIn("\n    dns:", compose)
        self.assertNotRegex(compose, r"\bextra_hosts\s*:")

