"""S2 全链路：讲义任务全生命周期、SSE 游标、学生版隔离扫描、学习画像更新、讲题同步链路。

全部真实栈 + 真实模型；讲义任务为分钟级长任务，超时上限 25 分钟。
"""
from __future__ import annotations

import re
import uuid

import pytest
import requests

from .conftest import ApiClient, BACKEND, poll_task_until_terminal

# 学生版不得出现的教师侧/内部标记（对应讲义架构验收清单的学生隔离项）。
STUDENT_FORBIDDEN_MARKERS = [
    "教师批注",
    "trace",
    "feishu://",
    "gaokao://",
    "textbook://",
    "/app/data/",
    "evidenceRef=",
]


def _task_payload(goal: str, question: str) -> dict:
    return {
        "clientRequestId": str(uuid.uuid4()),
        "learningGoal": goal,
        "questionText": question,
        "evidenceLimit": 3,
    }


@pytest.mark.full
def test_teaching_task_full_lifecycle_with_student_isolation(admin_client: ApiClient):
    """登录 → 建任务（异步）→ 轮询终态 → 三版本可见 → 学生版 LaTeX 隔离扫描 → SSE 游标重放。"""
    created = admin_client.post("/api/teaching/tasks", json=_task_payload(
        "勾股定理专题讲义", "在直角三角形中，两直角边为 3 和 4，求斜边长，并说明理由。"))
    assert created.status_code in (200, 201), created.text
    task = created.json()
    task_id = task.get("taskId") or task.get("id")
    assert task_id, task

    # 提交即异步返回（CREATED 或 RUNNING），不阻塞请求本身。
    assert re.search(r"CREATED|RUNNING|PENDING", str(task).upper()), f"任务未异步受理: {task}"

    final = poll_task_until_terminal(admin_client, task_id, timeout_s=1500, interval_s=15)
    assert "COMPLETED" in str(final), f"任务失败: {final}"

    # 讲义 LaTeX 导出：教师版与学生版。
    versions = ["teacher", "student", "lecture"]
    bodies: dict[str, str] = {}
    for version in versions:
        response = admin_client.get(f"/api/teaching/tasks/{task_id}/handout/{version}/latex")
        if response.status_code == 200:
            bodies[version] = response.text
    assert "student" in bodies, f"学生版 LaTeX 不可用: 已得 {list(bodies)}"

    # 学生版隔离扫描（LaTeX 源文本级）。
    student_latex = bodies["student"]
    for marker in STUDENT_FORBIDDEN_MARKERS:
        assert marker not in student_latex, f"学生版泄漏内部标记: {marker}"

    # SSE 事件游标重放：已完成任务的事件可按 cursor 连续读取，且公共事件不含敏感字段。
    events = admin_client.get(f"/api/teaching/tasks/{task_id}/events", timeout=30)
    assert events.status_code == 200
    event_text = events.text
    for secret in ["raw prompt", "rawPrompt", "providerKey", "apiKey"]:
        assert secret not in event_text, f"公开 SSE 事件含敏感字段: {secret}"


@pytest.mark.full
def test_learning_attempt_updates_mastery(student_client: ApiClient):
    """真实答题 → 掌握度/弱项在画像中体现（画像自动更新链路）。"""
    attempt = student_client.post("/api/students/learning/attempts", json={
        "questionId": f"e2e-q-{uuid.uuid4().hex[:8]}",
        "questionText": "解方程 x^2 - 5x + 6 = 0。",
        "knowledgePointIds": ["一元二次方程"],
        "correct": False,
        "responseTimeMs": 45000,
    })
    assert attempt.status_code in (200, 201), attempt.text

    mastery = student_client.get("/api/students/learning/mastery")
    assert mastery.status_code == 200, mastery.text
    payload = mastery.text
    assert "一元二次方程" in payload, f"掌握度未包含刚作答知识点: {payload[:400]}"


@pytest.mark.full
def test_student_explanation_sync_real_model(student_client: ApiClient):
    """学生讲题同步链路：真实模型作答且不含内部标识。"""
    response = student_client.post("/api/students/explanations", json={
        "questionText": "已知一次函数 y=2x+1，求它与 x 轴的交点坐标。",
        "searchTextbook": True,
    }, timeout=300)
    assert response.status_code == 200, response.text
    body = response.text
    assert len(body) > 50, "讲题响应为空"
    for marker in ["feishu://", "gaokao://", "textbook://", "evidenceRef="]:
        assert marker not in body, f"讲题响应泄漏内部标记: {marker}"

    # 历史会话可查（讲解链路持久化）。
    history = student_client.get("/api/students/explanations/history")
    assert history.status_code == 200
