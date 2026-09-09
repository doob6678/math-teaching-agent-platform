"""阶段3：并发负载实测（真实 HTTP，raw 结果不重试，如实计入 429/5xx 错误率）。

代表接口：
  - 健康检查 GET /api/system/health           （网关/DB 就绪探测，轻）
  - 教材检索 GET /api/retrieval/textbooks/search （BGE 嵌入+Milvus+GPU rerank 链路）
  - 教师检索 GET /api/teacher/resources/search    （两段式 doc+block+RRF 重排，GPU 重）
  - 学生讲解同步 POST /api/students/explanations  （真实外呼 LLM，控总量≤30、最小 prompt）

并发梯度 1/5/10；轻/重检索每档 20 请求；LLM 档做小样本并如实记录花费风险。
用一次登录取 sa-token cookie，注入每个线程的独立 Session（requests.Session 非线程安全，逐线程复制）。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from statistics import mean

import requests

REPO = Path(r"C:\Users\doob\Desktop\code\dev\math_agent_rag")
sys.path.insert(0, str(REPO))
from benchmarks.http_client import MathAgentClient  # noqa: E402

CONFIG = json.loads((REPO / "benchmarks" / "config.json").read_text(encoding="utf-8"))
BASE = CONFIG["backendBaseUrl"].rstrip("/")
OUT_DIR = REPO / "output" / "link-eval-20260909"

TB_QUERIES = ["空间向量 法向量 线面角", "导数 单调性 极值", "椭圆 离心率 定义", "等差数列 前n项和", "三角函数 诱导公式"]
TE_QUERIES = ["椭圆离心率如何由焦半距和长半轴表示", "函数单调性应先检查定义域和端点", "空间向量建系把底面放在xOy平面",
              "概率题先区分放回独立试验与不放回抽取", "数列中Sn与an转换使用an等于Sn减Sn减一"]


def pctl(vals, p):
    s = sorted(vals)
    if not s:
        return None
    idx = max(0, min(len(s) - 1, int(round(p / 100.0 * (len(s) - 1)))))
    return s[idx]


def get_cookie_session(base_session: requests.Session) -> requests.Session:
    s = requests.Session()
    s.cookies.update(base_session.cookies)  # 复制登录 cookie，线程各持一份
    return s


def timed(fn):
    t0 = time.perf_counter()
    try:
        resp = fn()
        return {"status": resp.status_code, "elapsed_ms": round((time.perf_counter() - t0) * 1000),
                "err": None, "bytes": len(resp.content)}
    except Exception as exc:  # noqa: BLE001
        return {"status": 0, "elapsed_ms": round((time.perf_counter() - t0) * 1000),
                "err": type(exc).__name__ + ":" + str(exc)[:80], "bytes": 0}


def run_level(name, cookie, concurrency, total, worker, timeout):
    """worker(session, idx) -> requests.Response。返回该档统计。"""
    sessions = [get_cookie_session(cookie) for _ in range(concurrency)]

    def task(i):
        s = sessions[i % concurrency]
        return timed(lambda: worker(s, i))

    t_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        results = list(ex.map(task, range(total)))
    wall = time.perf_counter() - t_start
    lat = [r["elapsed_ms"] for r in results if r["status"] == 200]
    err2 = sum(1 for r in results if r["status"] == 429)
    err5 = sum(1 for r in results if 500 <= r["status"] < 600)
    errx = sum(1 for r in results if r["err"] is not None)
    okc = sum(1 for r in results if r["status"] == 200)
    if total == 0:
        return {"endpoint": name, "concurrency": concurrency, "requests": 0}
    return {
        "endpoint": name, "concurrency": concurrency, "requests": total,
        "success200": okc, "rate_limited_429": err2, "server_5xx": err5, "exceptions": errx,
        "error_rate": round((total - okc) / total, 4),
        "wall_seconds": round(wall, 2), "throughput_rps": round(total / wall, 2) if wall > 0 else None,
        "p50_ms": pctl(lat, 50), "p95_ms": pctl(lat, 95), "p99_ms": pctl(lat, 99),
        "max_ms": max(lat) if lat else None, "min_ms": min(lat) if lat else None,
        "avg_ms": round(mean(lat)) if lat else None,
        "error_samples": [r for r in results if r["status"] != 200][:3],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--heavy-per-level", type=int, default=20, help="检索/健康每档请求数")
    ap.add_argument("--student", action="store_true", help="是否压学生讲解 LLM 接口（真实外呼，产生费用）")
    ap.add_argument("--levels", default="1,5,10")
    args = ap.parse_args()
    levels = [int(x) for x in args.levels.split(",")]

    base = MathAgentClient(BASE, timeout=180)
    base.login(CONFIG["teacherUsername"], CONFIG["teacherPassword"])
    print("logged in, cookie domains:", list(base.session.cookies.keys()))

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = {"generatedAt": ts, "backend": BASE, "levels": levels, "results": []}

    def w_health(s, i):
        return s.get(BASE + "/api/system/health", timeout=30)

    def w_tb(s, i):
        return s.get(BASE + "/api/retrieval/textbooks/search",
                     params={"query": TB_QUERIES[i % len(TB_QUERIES)], "limit": 5}, timeout=60)

    def w_te(s, i):
        return s.get(BASE + "/api/teacher/resources/search",
                     params={"query": TE_QUERIES[i % len(TE_QUERIES)], "limit": 5, "library": "feishu"}, timeout=90)

    def w_st(s, i):
        body = {"questionText": "计算 1+1 等于几？只回答数字。", "searchTextbook": False,
                "searchTeacherResources": False, "searchKnowledgeGraph": False,
                "maxTextbookHits": 0, "maxTeacherResourceHits": 0,
                "clientRequestId": f"load-{ts}-{i}"}
        return s.post(BASE + "/api/students/explanations", data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                      headers={"Content-Type": "application/json; charset=utf-8"}, timeout=180)

    jobs = [("health", w_health, args.heavy_per_level),
            ("textbook_search", w_tb, args.heavy_per_level),
            ("teacher_search", w_te, args.heavy_per_level)]
    if args.student:
        # LLM 档做小样本：concurrency 档请求数=该档并发数（1/5/10 共 16 次），控制总花费。
        jobs.append(("student_explanation_llm", w_st, None))

    for name, worker, per in jobs:
        if name == "student_explanation_llm":
            # 每档请求数等于并发数，总 1+5+10=16 ≤30
            for c in levels:
                r = run_level(name, base.session, c, c, worker, 180)
                out["results"].append(r)
                print(f"[{name}] conc={c} p50={r['p50_ms']}ms p95={r['p95_ms']}ms "
                      f"ok={r['success200']}/{r['requests']} err={r['error_rate']}", flush=True)
            continue
        if per == 0:
            continue  # heavy-per-level=0 表示本轮只压学生 LLM 档，跳过轻/重检索避免重复消耗
        for c in levels:
            r = run_level(name, base.session, c, per, worker, 90)
            out["results"].append(r)
            print(f"[{name}] conc={c} p50={r['p50_ms']}ms p95={r['p95_ms']}ms max={r['max_ms']}ms "
                  f"rps={r['throughput_rps']} 429={r['rate_limited_429']} 5xx={r['server_5xx']} "
                  f"exc={r['exceptions']} err_rate={r['error_rate']}", flush=True)
            time.sleep(3)  # 档间冷却

    outfile = OUT_DIR / f"phase3_load_{ts}.json"
    outfile.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n===== PHASE3 SUMMARY =====")
    print(json.dumps(out, ensure_ascii=False, indent=2))
    print("PHASE3_JSON=" + str(outfile))


if __name__ == "__main__":
    main()
