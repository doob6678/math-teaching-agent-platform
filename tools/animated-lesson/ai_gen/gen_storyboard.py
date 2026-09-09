# -*- coding: utf-8 -*-
"""LLM 自动生成动画分镜实验：题目原文 → lesson-plan/v1 JSON → 校验回喂 → 渲染回喂。

目的：量化"AI 直接产分镜"的首轮通过率与修复轮数，验证受控 schema 管线的可行性。
调用方式与 tools/geo-draw/geo_draw_agent.py 完全一致（纯 requests + 根 .env）。

用法：python gen_storyboard.py <lesson_id> <题目md路径> [--rounds 3] [--no-render] [--timeout 300]
产物：lessons/<lesson_id>.json；渲染走 render_lesson.py（out/<lesson_id>/final.mp4）。

韧性（2026-09-09）：Terra 网关劣化时 900s 长调用会静默挂死整条实验。现在开工前先
复用 probe_providers 探活，按声明的链顺序取存活者，单次调用超时降到可配（默认 300s），
超时/HTTP 错就换下一家并把失败原因记进 stats，失败过的一家本次之后不再重试。

链口径（2026-09-09 老板拍板）：分镜生成弃用 Terra，改 GLM 主、deepseek/dashscope 备，
见 CHAIN_ORDER 注释；probe 仍探全部五家，剔除原因要能在 stats 里复盘。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).parent))
from validate_storyboard import validate
import probe_providers

HERE = Path(__file__).parent                 # ai_gen/
ROOT = HERE.parent                           # animated-lesson/
REPO = ROOT.parent.parent                    # math_agent_rag/
FENCE_RE = re.compile(r"```(?:json)?\s*\n(.*?)```", re.S)
# 单次生成的默认超时：900s 的实测教训是"等下去不如换供应商"，300s 够正常长输出。
DEFAULT_CALL_TIMEOUT = 300.0
# 探活 ping 的短超时：只为判存活，不为生成。
PROBE_TIMEOUT = 30.0
# 调用链顺序（声明式，不再按延迟自动排）：2026-09-09 老板拍板弃用 Terra(gpt-5.6-terra)，
# 原因是内容安全 403 误杀 + 网关 ping 延迟劣化到 8~15s（当天一轮 300s 直接 ReadTimeout）。
# GLM 主位（glm-5.3-flash，Anthropic 线格式），deepseek/dashscope 兜底。
CHAIN_ORDER = ("glm", "deepseek", "dashscope")
# 推理型网关把隐藏思考也算进 completion 配额：只按正文预算 8000 申请时，思考先吃满配额、
# 可见正文返空（finish_reason=length），会被误判成"模型写坏了"。两种处置：
# - GLM 强制思考关不掉（网关 error 1210），只能追加等量预留 → 16000；
# - deepseek 可以关思考，正文预算回到 8000，与其余家严格等价。
# 可见正文预算对所有家始终是传进来的 max_tokens。
REASONING_PROVIDERS = ("glm",)
THINKING_RESERVE = 8000
# provider -> 关闭隐藏推理所需的请求体片段。2026-09-09 对 api.deepseek.com 实测四种形状：
# `thinking:{"type":"disabled"}` 才真把 reasoning_content 打到 0；仓库 provider_profiles 里记的
# `enable_thinking:false` 被该端点静默忽略（仍返回 216 字符推理），加大 max_tokens 也仍被吃满。
# 即 ai-worker-python 的 thinking_toggle_param 档案与本端点实际行为不一致（本任务不改那边）。
NO_THINKING_PAYLOAD = {"deepseek": {"thinking": {"type": "disabled"}}}


def load_env() -> None:
    """根 .env 灌进 os.environ（进程已有变量优先，便于临时把 GLM_BASE_URL 指坏做回退演练）。

    不再单独要求 OPENAI_*：Terra 已不在链上，可用性由 build_chain 的探活结果决定，
    整条链没人存活时 main() 会带着探活明细退出。
    """
    probe_providers.load_env()


def build_chain(probe_timeout: float) -> tuple[list[str], list[dict]]:
    """探活全部五家，按 CHAIN_ORDER 取存活者出链（Terra 已按老板拍板不在链上）。

    五家都探：NO_KEY / NO_MODEL_CONFIGURED / 超时 / 被剔除家的状态要留在 stats 里，
    事后才能区分"链上没人了"和"链被我们主动缩短了"。
    """
    probes = [probe_providers.probe(name, probe_timeout) for name in probe_providers.PROVIDERS]
    for p in probes:
        print(f"[probe] {p['provider']}: {p['status']}"
              + (f" {p['latency_s']}s" if "latency_s" in p else "")
              + (f" {p.get('error', '')}" if p.get("error") else ""), flush=True)
    alive = {p["provider"] for p in probes if p["status"] == "ALIVE"}
    chain = [n for n in CHAIN_ORDER if n in alive]
    for n in CHAIN_ORDER:
        if n not in alive:
            print(f"[chain] 跳过 {n}：探活未通过", flush=True)
    return chain, probes


def _chat_once(name: str, messages: list[dict], max_tokens: int, timeout: float) -> tuple[str | None, str]:
    """按 probe_providers.PROVIDERS 的配置调用一家，返回 (正文, 记账原因)。

    正文为 None 表示这家这次不可用（换下一家）。提示词与"可见正文预算"各家一致，
    只有协议形状（_budget 的隐藏思考追加除外）不同，避免换供应商导致分镜质量/截断行为漂移。
    """
    key_env, base_env, default_base, model_env, default_model, wire = probe_providers.PROVIDERS[name]
    api_key, model = os.getenv(key_env), os.getenv(model_env, default_model)
    if not api_key or not model:
        return None, "NO_KEY_OR_MODEL"
    base = os.getenv(base_env, default_base).rstrip("/")
    if wire == "anthropic":
        # GLM 的 Anthropic 线格式三条硬约束（契约见 ai-worker-python/app/anthropic_compat.py）：
        # system 提到顶层字段；temperature 与 thinking 互斥所以不发；thinking 关不掉且与正文
        # 共用 max_tokens，故追加 THINKING_RESERVE 并保住网关下限 MATH_AGENT_GLM_MIN_MAX_TOKENS。
        # 正文只取 type=="text" 的块，thinking 块绝不混进分镜 JSON。
        effort = os.getenv("MATH_AGENT_GLM_THINKING_EFFORT", "low")
        floor = int(os.getenv("MATH_AGENT_GLM_MIN_MAX_TOKENS", "2048"))
        payload = {"model": model,
                   "max_tokens": max(_budget(name, max_tokens), floor),
                   "thinking": {"type": "enabled", "effort": effort},
                   "messages": [m for m in messages if m["role"] != "system"]}
        system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
        if system:
            payload["system"] = system
        resp = requests.post(base + "/v1/messages", timeout=timeout, json=payload,
                             headers={"x-api-key": api_key, "anthropic-version": "2023-06-01"})
        resp.raise_for_status()
        body = resp.json()
        text = "".join(b.get("text", "") for b in (body.get("content") or []) if b.get("type") == "text")
        return _ok(text, body.get("stop_reason"))
    payload = {"model": model, "messages": messages, "temperature": 0.2,
               "max_tokens": _budget(name, max_tokens)}
    payload.update(NO_THINKING_PAYLOAD.get(name, {}))
    resp = requests.post(base + "/chat/completions", timeout=timeout, json=payload,
                         headers={"Authorization": f"Bearer {api_key}"})
    resp.raise_for_status()
    body = (resp.json().get("choices") or [{}])[0]
    # 只取可见 content；deepseek 的 reasoning_content 是私有轨迹，禁止混进分镜。
    return _ok((body.get("message") or {}).get("content") or "", body.get("finish_reason"))


def _budget(name: str, max_tokens: int) -> int:
    """可见正文预算各家一致；REASONING_PROVIDERS 追加隐藏思考占用的配额。"""
    return max_tokens + THINKING_RESERVE if name in REASONING_PROVIDERS else max_tokens


def _ok(text: str, finish):
    """空正文一律判为"这家这次不可用"，并带上 finish_reason 便于区分截断与拒绝。

    实测：推理型网关吃满配额时 HTTP 仍是 200、content 为空，只看状态码会把环境问题
    当成模型质量问题，白烧一整轮回喂。
    """
    if text.strip():
        return text, "OK"
    return None, f"EMPTY_TEXT(finish={finish} 思考/配额吃满，无可见正文)"


def chat(chain: list[str], messages: list[dict], stats: dict, max_tokens: int = 8000) -> str:
    """沿链调用：一家失败就换下一家，并把它移出本次运行的链（同一轮任务不重复烧一次超时）。"""
    for name in list(chain):
        try:
            text, reason = _chat_once(name, messages, max_tokens, stats["call_timeout_s"])
        except requests.RequestException as exc:
            text, reason = None, f"{type(exc).__name__}: {str(exc)[:160]}"
        stats["provider_attempts"].append({"provider": name, "result": reason})
        print(f"[provider] {name} -> {reason}", flush=True)
        if text is not None:
            if not stats["providers_used"] or stats["providers_used"][-1] != name:
                stats["providers_used"].append(name)
            return text
        chain.remove(name)
    # 链空＝全部供应商都试过且失败，属环境故障，打印记账后退出，不让 traceback 掩盖原因。
    print("[provider] 链已全部失败，本次不可继续", flush=True)
    print(json.dumps(stats, ensure_ascii=False))
    sys.exit(2)


def main():
    if len(sys.argv) < 3:
        sys.exit("用法: gen_storyboard.py <lesson_id> <题目md> [--rounds 3] [--no-render] [--timeout 300]")
    lid, qpath = sys.argv[1], sys.argv[2]
    rounds = int(sys.argv[sys.argv.index("--rounds") + 1]) if "--rounds" in sys.argv else 3
    no_render = "--no-render" in sys.argv
    call_timeout = float(sys.argv[sys.argv.index("--timeout") + 1]) if "--timeout" in sys.argv \
        else float(os.getenv("GEN_CHAT_TIMEOUT", DEFAULT_CALL_TIMEOUT))

    schema_doc = (ROOT / "SCHEMA.md").read_text(encoding="utf-8")
    example = (ROOT / "lessons" / "tangent-min.json").read_text(encoding="utf-8")
    problem = Path(qpath).read_text(encoding="utf-8")

    system = (
        "你是数学教学动画的分镜编剧。只输出一个 ```json 围栏块，内容是一份符合下述 schema 的 "
        "lesson-plan/v1 分镜 JSON，禁止输出任何其他代码或解释。\n\n# SCHEMA\n" + schema_doc +
        "\n\n# 完整输出范例（另一道题，学其结构与教学语气，几何内容不得照抄）\n" + example
    )
    user = ("请为下面这道高考真题编写分镜。题目原文含【答案】【解析】，教学步骤必须与解析一致，"
            "章节 4~6 个，每章 narration 为口语化中文 2~4 句。id 用 " + lid + "。\n\n# 题目\n" + problem)
    messages = [{"role": "system", "content": system}, {"role": "user", "content": user}]

    out_path = ROOT / "lessons" / f"{lid}.json"
    load_env()
    # 先探活再干活：链空说明网关全挂，直接退出，不浪费一整轮长调用的时间。
    chain, probes = build_chain(PROBE_TIMEOUT)
    if not chain:
        sys.exit("[probe] 无任何存活 provider，放弃生成：" + json.dumps(probes, ensure_ascii=False))
    print(f"[chain] 本次调用链：{' → '.join(chain)}（单次超时 {call_timeout:.0f}s）", flush=True)
    stats = {"attempts": 0, "validate_rounds": 0, "render_rounds": 0,
             "call_timeout_s": call_timeout, "probe": probes,
             "provider_attempts": [], "providers_used": []}
    for r in range(rounds):
        stats["attempts"] += 1
        reply = chat(chain, messages, stats)
        m = FENCE_RE.search(reply)
        if not m:
            messages.append({"role": "assistant", "content": reply[:2000]})
            messages.append({"role": "user", "content": "没有看到 ```json 围栏块，请只输出一个 JSON 围栏块。"})
            continue
        try:
            sb = json.loads(m.group(1))
        except json.JSONDecodeError as e:
            messages.append({"role": "assistant", "content": m.group(1)[:3000]})
            messages.append({"role": "user", "content": f"JSON 解析失败：{e}。请修正后重新输出完整围栏块。"})
            continue
        errs = validate(sb)
        if errs:
            stats["validate_rounds"] += 1
            messages.append({"role": "assistant", "content": m.group(1)[:4000]})
            messages.append({"role": "user", "content": "校验发现以下问题，逐条修复后重新输出完整围栏块：\n"
                             + "\n".join("- " + e for e in errs)})
            continue
        out_path.write_text(json.dumps(sb, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[gen] 通过校验，写入 {out_path}（尝试 {stats['attempts']} 次，校验修复 {stats['validate_rounds']} 轮）")
        if no_render:
            print(json.dumps(stats, ensure_ascii=False))
            return
        # 渲染回路：失败把 manim traceback 尾部回喂修 JSON
        for rr in range(2):
            stats["render_rounds"] += 1
            p = subprocess.run([str(ROOT / ".venv" / "Scripts" / "python.exe"),
                                str(ROOT / "render_lesson.py"), str(out_path)],
                               capture_output=True, text=True, encoding="utf-8", errors="replace",
                               cwd=str(ROOT))
            if p.returncode == 0:
                print(f"[render] 成片成功（渲染修复 {stats['render_rounds']} 轮）")
                print(json.dumps(stats, ensure_ascii=False))
                return
            tail = (p.stdout + p.stderr)[-2500:]
            print(f"[render] 第 {rr+1} 轮失败，回喂修复")
            cur = json.loads(out_path.read_text(encoding="utf-8"))
            messages.append({"role": "assistant", "content": json.dumps(cur, ensure_ascii=False)[:4000]})
            messages.append({"role": "user", "content":
                             "这份分镜在渲染时报错如下，请只修复导致报错的字段（保持教学结构不变），"
                             "重新输出完整围栏块：\n" + tail})
            reply = chat(chain, messages, stats)
            m = FENCE_RE.search(reply)
            if not m:
                continue
            try:
                sb = json.loads(m.group(1))
            except json.JSONDecodeError:
                continue
            if validate(sb):
                continue
            out_path.write_text(json.dumps(sb, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[render] 渲染回路耗尽，分镜已存 {out_path}，需人工介入")
        print(json.dumps(stats, ensure_ascii=False))
        return
    print(f"[gen] {rounds} 轮未通过校验，最后回复：\n{reply[:800]}")
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
