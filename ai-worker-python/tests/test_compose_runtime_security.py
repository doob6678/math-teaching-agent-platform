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
        # 只有 ai-worker 允许显式 dns：WSL 生成的解析器可能指向不可达网关，provider 归属的
        # worker 需要可达递归解析器；其余服务（尤其 backend/mysql）仍禁止 dns 覆盖。
        non_worker_blocks = [
            _service_block(compose, name)
            for name in ("mysql", "redis", "rabbitmq", "backend", "frontend")
        ]
        for block in non_worker_blocks:
            self.assertNotRegex(block, r"(?m)^\s{4}dns:\s")
        self.assertNotRegex(compose, r"\bextra_hosts\s*:")

    def test_teacher_source_mounts_are_durable_and_backend_only(self):
        compose = COMPOSE_FILE.read_text(encoding="utf-8")
        backend = _service_block(compose, "backend")
        worker = _service_block(compose, "ai-worker")

        self.assertIn("./.local-storage/teacher-resource-uploads:/app/data/teacher-resource-uploads", backend)
        self.assertIn("MATH_AGENT_LOCAL_TEACHER_RESOURCES_HOST_ROOT", backend)
        self.assertIn("MATH_AGENT_LOCAL_TEACHER_RESOURCES_ROOT", backend)
        self.assertRegex(backend, r"local-teacher-resources[^\n]*:ro")
        self.assertNotIn("teacher-resource-uploads", worker)
        self.assertNotIn("local-teacher-resources", worker)

