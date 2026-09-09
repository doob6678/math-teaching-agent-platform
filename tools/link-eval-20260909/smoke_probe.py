"""阶段2 前置 smoke：真实登录 + 一次教师检索 + 一次教材检索，确认响应结构与链路可用。

复用仓库既有 MathAgentClient（带 sa-token cookie 会话、UTF-8 显式编码）。只读，不改代码。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(r"C:\Users\doob\Desktop\code\dev\math_agent_rag")
sys.path.insert(0, str(REPO))

from benchmarks.http_client import MathAgentClient  # noqa: E402

CONFIG = json.loads((REPO / "benchmarks" / "config.json").read_text(encoding="utf-8"))
BASE = CONFIG["backendBaseUrl"]


def main() -> int:
    client = MathAgentClient(BASE, timeout=120)
    # 1) 登录（真实 acceptance 账号）
    login = client.login(CONFIG["teacherUsername"], CONFIG["teacherPassword"])
    print("LOGIN status=", login if isinstance(login, str) else json.dumps(login, ensure_ascii=False)[:300])

    # 2) 教师资源检索：真实语料题干
    q = "椭圆离心率如何由焦半距和长半轴表示，a、b、c之间还有什么关系？"
    t = client.get("/api/teacher/resources/search", params={"query": q, "limit": 5, "library": "feishu"})
    body = t.body if isinstance(t.body, dict) else {}
    hits = body.get("hits") or []
    print("TEACHER_SEARCH status=", t.status, "elapsed_ms=", t.elapsed_ms,
          "retrievalMode=", body.get("retrievalMode"), "hitCount=", body.get("hitCount"),
          "n_hits=", len(hits))
    if hits and isinstance(hits[0], dict):
        print("  hit[0] keys=", list(hits[0].keys()))
        print("  hit[0] documentId=", hits[0].get("documentId"), "blockId=", hits[0].get("blockId"))

    # 3) 教材检索：确认 per-stage elapsedMs（可拆 Milvus/向量层）
    t2 = client.get("/api/retrieval/textbooks/search", params={"query": "空间向量 法向量 线面角", "limit": 5})
    b2 = t2.body if isinstance(t2.body, dict) else {}
    print("TEXTBOOK_SEARCH status=", t2.status, "elapsed_ms=", t2.elapsed_ms,
          "total=", b2.get("total"), "strategy=", b2.get("retrievalStrategy"))
    stages = b2.get("retrievalStages") or []
    for s in stages:
        if isinstance(s, dict):
            print("  stage code=", s.get("code"), "status=", s.get("status"), "elapsedMs=", s.get("elapsedMs"))
    hits2 = b2.get("hits") or []
    if hits2 and isinstance(hits2[0], dict):
        print("  tb hit[0] keys=", list(hits2[0].keys()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
