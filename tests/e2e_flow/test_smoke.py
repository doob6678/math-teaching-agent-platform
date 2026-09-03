"""S2 冒烟：健康、会话、MCP key 生命周期与越权边界。"""
from __future__ import annotations

import pytest

from .conftest import ApiClient, BACKEND, new_student_credentials


@pytest.mark.smoke
def test_backend_and_worker_health(admin_client: ApiClient):
    response = admin_client.get("/api/system/health")
    assert response.status_code == 200


@pytest.mark.smoke
def test_register_student_session_role_is_student():
    username, password = new_student_credentials()
    client = ApiClient()
    session = client.register_student(username, password)
    assert session.get("role") == "student"

    me = client.get("/api/auth/session")
    assert me.status_code == 200
    assert me.json().get("role") == "student"


@pytest.mark.smoke
def test_mcp_key_lifecycle_and_role_boundary(admin_client: ApiClient, student_client: ApiClient):
    # 管理员创建 key → 列表可见 → 吊销 → 物理删除。
    created = admin_client.post("/api/mcp/keys")
    assert created.status_code == 200, created.text
    key = created.json()
    key_id = key.get("keyId") or key.get("id")
    assert key_id

    listed = admin_client.get("/api/mcp/keys")
    assert listed.status_code == 200
    assert key_id in str(listed.json())

    revoked = admin_client.post(f"/api/mcp/keys/{key_id}/revoke")
    assert revoked.status_code == 200, revoked.text
    deleted = admin_client.delete(f"/api/mcp/keys/{key_id}")
    assert deleted.status_code == 200, deleted.text

    # 边界：学生账号不能吊销/删除他人（管理员）的 key。
    other = admin_client.post("/api/mcp/keys").json()
    other_id = other.get("keyId") or other.get("id")
    try:
        forbidden = student_client.post(f"/api/mcp/keys/{other_id}/revoke")
        assert forbidden.status_code in (401, 403), f"学生吊销他人 key 未被拒绝: {forbidden.status_code}"
    finally:
        admin_client.delete(f"/api/mcp/keys/{other_id}")
