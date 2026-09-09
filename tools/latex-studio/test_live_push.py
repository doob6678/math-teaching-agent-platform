# -*- coding: utf-8 -*-
"""live push 端到端测试：AI 连续迭代推送（含编译中撞锁场景），验证服务端编译与状态。"""
import json
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8764"


def push(content, session="handout-demo"):
    data = json.dumps({"content": content, "session": session}).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/live/push", data=data,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req).read().decode("utf-8"))


def state(path):
    url = BASE + "/api/state?path=" + urllib.parse.quote(path)
    return json.loads(urllib.request.urlopen(url).read().decode("utf-8"))


frag1 = "\\section{AI 直播写入}\n这是 AI 写讲义时推送的第一个片段，公式 $x^2+1$。\n"
r1 = push(frag1)
print("push1:", r1["ok"], "wrapped:", r1["wrapped"])

# 立即第二次推送：此时第一次大概率还在编译 → 验证撞锁重试不丢内容
frag2 = frag1 + "\n\\section{第二段}\nAI 又追加了一段，带表格：\n" \
    "\\begin{tabular}{|c|c|}\\hline 题号 & 得分 \\\\ \\hline 1 & 10 \\\\ \\hline\\end{tabular}\n"
r2 = push(frag2)
print("push2 (编译中撞锁):", r2["ok"])

deadline = time.time() + 30
while time.time() < deadline:
    s = state(r2["file"])
    if s["status"] in ("ok", "error"):
        break
    time.sleep(1)
print("final:", s["status"], "pages:", s["pageCount"], "compileMs:", s["compileMs"])
assert s["status"] == "ok" and s["pageCount"] >= 1, "live push 编译未成功"
print("LIVE PUSH E2E PASS")
