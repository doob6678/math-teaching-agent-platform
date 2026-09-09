"""定位学生讲解同步接口 500 真因：并发突发 + 抓取响应体原文。"""
from __future__ import annotations
import json, sys, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import requests

REPO = Path(r"C:\Users\doob\Desktop\code\dev\math_agent_rag")
sys.path.insert(0, str(REPO))
from benchmarks.http_client import MathAgentClient  # noqa: E402

CONFIG = json.loads((REPO / "benchmarks" / "config.json").read_text(encoding="utf-8"))
BASE = CONFIG["backendBaseUrl"].rstrip("/")
base = MathAgentClient(BASE, timeout=180)
base.login(CONFIG["teacherUsername"], CONFIG["teacherPassword"])

def one(i):
    s = requests.Session()
    s.cookies.update(base.session.cookies)
    body = {"questionText": f"计算 {i}+{i} 等于几？只回答数字。", "searchTextbook": False,
            "searchTeacherResources": False, "searchKnowledgeGraph": False,
            "maxTextbookHits": 0, "maxTeacherResourceHits": 0,
            "clientRequestId": f"p500-{int(time.time())}-{i}"}
    t0 = time.perf_counter()
    try:
        r = s.post(BASE + "/api/students/explanations",
                   data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                   headers={"Content-Type": "application/json; charset=utf-8"}, timeout=180)
        return {"i": i, "status": r.status_code, "ms": round((time.perf_counter()-t0)*1000),
                "text": r.text[:400]}
    except Exception as e:  # noqa: BLE001
        return {"i": i, "status": 0, "ms": round((time.perf_counter()-t0)*1000), "text": type(e).__name__+":"+str(e)[:200]}

with ThreadPoolExecutor(max_workers=10) as ex:
    res = list(ex.map(one, range(10)))
for r in sorted(res, key=lambda x: x["status"]):
    print(f"i={r['i']} status={r['status']} {r['ms']}ms body={r['text']}")
