# -*- coding: utf-8 -*-
"""模型存活探针：对根 .env 配置的各家 provider 做最小真实调用，输出状态/延迟/错误。

背景（2026-09-09）：Terra 网关宕机时 gen_storyboard 的 900s 长调用会静默挂死，
整条生成实验停摆。本探针给"先探活再干活"提供最小工具：每家 max_tokens=8 的 ping
调用、独立短超时、失败不重试，结果一行一家。密钥只从环境变量读，绝不打印。

用法：python probe_providers.py [--timeout 30]
退出码：0=至少一家存活；1=全部不可用。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))

# provider -> (key_env, base_env, default_base, model_env, default_model, wire)
# wire=anthropic 的走 /v1/messages 形状（GLM），其余 OpenAI 兼容 /chat/completions。
PROVIDERS = {
    "openai": ("OPENAI_API_KEY", "OPENAI_BASE_URL", "https://api1.aisz.mom/v1",
               "OPENAI_CHAT_MODEL", "gpt-5.6-terra", "openai"),
    "deepseek": ("DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1",
                 "DEEPSEEK_CHAT_MODEL", "deepseek-chat", "openai"),
    "dashscope": ("DASHSCOPE_API_KEY", "DASHSCOPE_BASE_URL",
                  "https://dashscope.aliyuncs.com/compatible-mode/v1",
                  "DASHSCOPE_CHAT_MODEL", "qwen-plus", "openai"),
    "ark": ("ARK_API_KEY", "ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3",
            "ARK_CHAT_MODEL", "", "openai"),
    "glm": ("GLM_API_KEY", "GLM_BASE_URL", "https://api.z.ai/api/anthropic",
            "GLM_CHAT_MODEL", "glm-5.3-flash", "anthropic"),
}


def load_env() -> None:
    env_file = os.path.join(REPO, ".env")
    if not os.path.exists(env_file):
        return
    for line in open(env_file, encoding="utf-8"):
        m = re.match(r"^([A-Z_][A-Z0-9_]*)=(.*)$", line.strip())
        if m and m.group(1) not in os.environ:
            os.environ[m.group(1)] = m.group(2)


def probe(name: str, timeout: float) -> dict:
    key_env, base_env, default_base, model_env, default_model, wire = PROVIDERS[name]
    api_key = os.getenv(key_env)
    if not api_key:
        return {"provider": name, "status": "NO_KEY"}
    base = os.getenv(base_env, default_base).rstrip("/")
    model = os.getenv(model_env, default_model)
    if not model:
        return {"provider": name, "status": "NO_MODEL_CONFIGURED"}
    payload = {"model": model, "max_tokens": 8,
               "messages": [{"role": "user", "content": "ping"}]}
    if wire == "anthropic":
        # GLM Anthropic 线格式：system 顶层、强制 thinking 由网关处理，最小形即 messages+max_tokens。
        url, headers = base + "/v1/messages", {"x-api-key": api_key, "anthropic-version": "2023-06-01"}
    else:
        url = base + "/chat/completions"
        headers = {"Authorization": f"Bearer {api_key}"}
        payload["temperature"] = 0
    started = time.monotonic()
    try:
        resp = requests.post(url, headers=headers, json=payload, timeout=timeout)
        latency = round(time.monotonic() - started, 2)
        if resp.status_code == 200:
            return {"provider": name, "model": model, "status": "ALIVE", "latency_s": latency}
        return {"provider": name, "model": model, "status": f"HTTP_{resp.status_code}",
                "latency_s": latency, "error": resp.text[:160]}
    except requests.RequestException as exc:
        return {"provider": name, "model": model, "status": "UNREACHABLE",
                "latency_s": round(time.monotonic() - started, 2), "error": type(exc).__name__}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--providers", default=",".join(PROVIDERS))
    args = parser.parse_args()
    load_env()
    results = [probe(p.strip(), args.timeout) for p in args.providers.split(",") if p.strip() in PROVIDERS]
    print(json.dumps(results, ensure_ascii=False, indent=1))
    return 0 if any(r["status"] == "ALIVE" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
