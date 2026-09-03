"""S2/S3 公共夹具：从 .env 读验收账号登录真栈，按需注册学生测试账号。

约定：
- 全部请求打真实 Compose 后端（http://127.0.0.1:8080），禁 mock；
- 凭据只从 .env/.local-secrets 读取，不写入任何报告；
- 学生账号经公开注册端点动态创建，用户名带秒级时间戳+随机后缀防撞名。
"""
from __future__ import annotations

import json
import random
import time
from pathlib import Path

import pytest
import requests

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = "http://127.0.0.1:8080"


def load_local_env() -> dict[str, str]:
    env: dict[str, str] = {}
    for line in (REPO_ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            env[key.strip()] = value.strip().strip('"').strip("'")
    return env


class ApiClient:
    """带 Sa-Token 会话的后端客户端。"""

    def __init__(self, base_url: str = BACKEND) -> None:
        self.base = base_url.rstrip("/")
        self.session = requests.Session()

    def login(self, username: str, password: str) -> dict:
        response = self.session.post(
            f"{self.base}/api/auth/login", json={"username": username, "password": password}, timeout=30
        )
        response.raise_for_status()
        return response.json()

    def register_student(self, username: str, password: str) -> dict:
        response = self.session.post(
            f"{self.base}/api/auth/register", json={"username": username, "password": password}, timeout=30
        )
        response.raise_for_status()
        return response.json()

    def get(self, path: str, **kwargs) -> requests.Response:
        return self.session.get(f"{self.base}{path}", timeout=kwargs.pop("timeout", 60), **kwargs)

    def post(self, path: str, **kwargs) -> requests.Response:
        return self.session.post(f"{self.base}{path}", timeout=kwargs.pop("timeout", 60), **kwargs)

    def delete(self, path: str, **kwargs) -> requests.Response:
        return self.session.delete(f"{self.base}{path}", timeout=kwargs.pop("timeout", 60), **kwargs)


def new_student_credentials() -> tuple[str, str]:
    stamp = time.strftime("%m%d%H%M%S")
    return f"e2e-py-{stamp}-{random.randint(100, 999)}", "e2e-student-pass-001"


@pytest.fixture(scope="session")
def admin_client() -> ApiClient:
    env = load_local_env()
    client = ApiClient()
    client.login(env["MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_USERNAME"], env["MATH_AGENT_LOCAL_ACCEPTANCE_ACCOUNT_PASSWORD"])
    return client


@pytest.fixture()
def student_client() -> ApiClient:
    """每个用例独立的新学生账号与会话。"""
    username, password = new_student_credentials()
    client = ApiClient()
    client.register_student(username, password)
    return client


def poll_task_until_terminal(client: ApiClient, task_id: str, timeout_s: int = 1500, interval_s: int = 15) -> dict:
    """轮询讲义任务至 COMPLETED/FAILED（真实异步状态机）。"""
    deadline = time.time() + timeout_s
    last: dict | None = None
    while time.time() < deadline:
        response = client.get(f"/api/teaching/tasks/{task_id}")
        response.raise_for_status()
        last = response.json()
        status = json.dumps(last, ensure_ascii=False)
        if "COMPLETED" in status:
            return last
        if "FAILED" in status:
            return last
        time.sleep(interval_s)
    raise AssertionError(f"任务 {task_id} 在 {timeout_s}s 内未到终态，最后状态: {last}")
