#!/usr/bin/env python3
"""几何题自动配图智能体：题目文本 -> LLM 写 TikZ -> 编译自修复 -> VLM 审阅 -> 高清成图。

用法（在项目根 .env 提供 OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_CHAT_MODEL）:
    python geo_draw_agent.py problem.txt --out final.png [--dpi 600] [--rounds 3]

闭环设计（与讲义 LaTeX 修复循环同模式，消息为追加式以命中前缀缓存）:
    1. 生成: 系统提示词约束"坐标必须由几何约束推导，禁止目测坐标"，模型输出完整
       standalone TikZ 文档。
    2. 编译: xelatex 失败时把日志尾部追加回对话，让模型自己改，最多 --rounds 轮。
    3. 审阅: 编译成功后渲染 300dpi 图交给同一视觉模型（Terra），对照题干输出
       {"pass","issues"}；不过关则把 issues 追加回对话再改再编译。
    4. 成图: 最终矢量 PDF 与中文题干在同一 standalone 文档里合成（图以 PDF 嵌入，
       全程矢量），最后一步才按 --dpi 栅格化，保证高清。
"""
import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path

import requests

from render import compile_tex, pdf_to_png

HERE = Path(__file__).resolve().parent

SYSTEM_PROMPT = """你是几何配图编译器。给定一道中文平面几何题，输出一个完整可编译的 standalone TikZ LaTeX 文档，画出题目描述的配图。
硬性规则:
1. 文档结构: \\documentclass[border=10pt]{standalone}; \\usepackage{tikz}; \\usetikzlibrary{calc,angles,quotes,decorations.markings}; 只输出一个 ```latex 围栏块，块外无任何文字。
2. 所有点坐标必须由题目约束推导: 用 \\pgfmathsetmacro 做旋转/交点/等长计算，禁止凭感觉手填坐标。先定基线（如把 BC 放在 x 轴），再逐点推导。
3. 角度弧用 angle pic。注意 TikZ 的 angle 从第一条边逆时针扫到第二条边: 要画 ∠XYZ 就写 {angle=Z--Y--X}，写反会画出优角。
4. 图上只允许拉丁字母、数字和度数符号，不允许中文。顶点字母标注在点外侧合适方位。
5. 等长线段画刻度线（decoration markings），题目强调的角用红色弧+红色数值标注，未知角用红色 ? 标注。
6. 线宽 thick，顶点画实心小圆点，整体布局紧凑、标注不得与线段重叠。"""

REVIEW_PROMPT = """你是几何配图审校。对照题干检查配图: 点、线段、角度弧的位置与开合方向是否正确（尤其角弧必须画在题目所指的角内，不能画成优角）、等长标记是否正确、标注是否与线重叠、图形拓扑是否与题干一致。
只输出一个 JSON 对象: {"pass": true 或 false, "issues": ["具体可执行的修改意见", ...]}。有任一问题即 pass=false。"""

FENCE_RE = re.compile(r"```(?:latex|tikz)?\s*\n(.*?)```", re.S)


def load_env() -> dict:
    """优先取进程环境变量，缺失时回退读项目根 .env（工具独立可跑）。"""
    env = dict(os.environ)
    env_file = HERE.parent.parent / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^([A-Z_][A-Z0-9_]*)=(.*)$", line.strip())
            if m and m.group(1) not in env:
                env[m.group(1)] = m.group(2)
    missing = [k for k in ("OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_CHAT_MODEL") if not env.get(k)]
    if missing:
        sys.exit(f"缺少配置: {missing}")
    return env


def chat(env: dict, messages: list, images: list | None = None, max_tokens: int = 4000) -> str:
    """追加式对话调用；images 非空时最后一条 user 消息带图（视觉审阅）。"""
    msgs = list(messages)
    if images:
        content = [{"type": "text", "text": msgs[-1]["content"]}]
        for p in images:
            b64 = base64.b64encode(Path(p).read_bytes()).decode()
            content.append({"type": "image_url",
                            "image_url": {"url": f"data:image/png;base64,{b64}"}})
        msgs[-1] = {"role": "user", "content": content}
    resp = requests.post(
        env["OPENAI_BASE_URL"].rstrip("/") + "/chat/completions",
        headers={"Authorization": f"Bearer {env['OPENAI_API_KEY']}"},
        json={"model": env["OPENAI_CHAT_MODEL"], "messages": msgs,
              "temperature": 0.2, "max_tokens": max_tokens},
        timeout=300,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


def extract_tex(reply: str) -> str:
    m = FENCE_RE.search(reply)
    if not m:
        raise RuntimeError("模型回复中没有 ```latex 围栏块")
    return m.group(1)


def review(env: dict, stem: str, messages: list, png: Path) -> dict:
    """VLM 审阅：把渲染图和题干交给模型，要求输出 json 判定。"""
    verdict = chat(env, messages + [{"role": "user",
                  "content": f"题干: {stem}\n\n{REVIEW_PROMPT}"}], images=[png])
    m = re.search(r"\{.*\}", verdict, re.S)
    if not m:
        return {"pass": False, "issues": [f"审阅输出无法解析: {verdict[:200]}"]}
    return json.loads(m.group(0))


# 中文字体（SimSun/SimHei）缺数学符号字形，题干里的 Unicode 数学符号必须
# 换成 LaTeX 命令，否则静默丢字（实测 ∠ 不清洗就整页消失）。
STEM_SYMBOL_FIX = {"∠": "$\\angle$", "△": "$\\triangle$", "≌": "$\\cong$",
                   "≅": "$\\cong$", "⊥": "$\\perp$", "∥": "$\\parallel$",
                   "°": "${}^{\\circ}$"}


def sanitize_stem(stem: str) -> str:
    for uni, tex in STEM_SYMBOL_FIX.items():
        stem = stem.replace(uni, tex)
    return stem


def compose_final(figure_pdf: Path, stem: str, out_png: Path, dpi: int) -> Path:
    """中文题干 + 矢量配图合成一页 standalone 文档，再按 dpi 栅格化。

    图以 PDF 形式嵌入（保持矢量），整页最后一步才栅格化，文字与线条同为高清。
    """
    tex = HERE / "final_page.tex"
    # xelatex 的工作目录是 build 子目录，插图路径必须绝对，相对路径会解析失败
    figure_pdf = Path(figure_pdf).resolve()
    tex.write_text(
        "\\documentclass[border=14pt,12pt]{standalone}\n"
        "\\usepackage{ctex}\\usepackage{graphicx}\n"
        "\\begin{document}\n"
        "\\begin{minipage}{900pt}\n"
        f"{sanitize_stem(stem)}\n\n"
        "\\centering\\includegraphics[width=0.82\\textwidth]{"
        + str(figure_pdf).replace("\\", "/") + "}\n"
        "\\end{minipage}\n\\end{document}\n", encoding="utf-8")
    pdf = compile_tex(tex, HERE / "final_page.build")
    pdf_to_png(pdf, out_png, dpi)
    return out_png


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("problem", help="题干文本文件（UTF-8）")
    ap.add_argument("--out", default="final.png")
    ap.add_argument("--dpi", type=int, default=600)
    ap.add_argument("--rounds", type=int, default=3, help="生成/修复最大轮数")
    ap.add_argument("--skip-review", action="store_true", help="跳过 VLM 审阅（调试）")
    args = ap.parse_args()

    env = load_env()
    stem = Path(args.problem).read_text(encoding="utf-8").strip()
    messages = [{"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"题目: {stem}"}]
    fig_tex = HERE / "agent_figure.tex"
    workdir = HERE / "agent_figure.build"

    for rnd in range(1, args.rounds + 1):
        reply = chat(env, messages)
        messages.append({"role": "assistant", "content": reply})
        try:
            fig_tex.write_text(extract_tex(reply), encoding="utf-8")
        except RuntimeError as e:
            messages.append({"role": "user", "content": f"格式错误: {e}。请重新只输出一个 ```latex 围栏块。"})
            continue
        try:
            pdf = compile_tex(fig_tex, workdir)
        except RuntimeError as e:
            print(f"[round {rnd}] 编译失败，回喂日志尾部修复", file=sys.stderr)
            messages.append({"role": "user", "content": f"编译失败，日志:\n{e}\n请修正后重新输出完整 ```latex 围栏块。"})
            continue
        review_png = HERE / "agent_figure_review.png"
        pdf_to_png(pdf, review_png, 300)
        if args.skip_review:
            break
        verdict = review(env, stem, messages, review_png)
        # flush 必开：后台运行时 stdout 重定向到文件是块缓冲，不加就看不到进度
        print(f"[round {rnd}] 审阅: {verdict}", flush=True)
        if verdict.get("pass"):
            break
        messages.append({"role": "user", "content": "审校意见:\n- "
                         + "\n- ".join(verdict.get("issues", []))
                         + "\n请按意见修正后重新输出完整 ```latex 围栏块。"})
    else:
        print("达到轮数上限仍未通过，使用最后一次可编译结果", file=sys.stderr)

    out = Path(args.out).resolve()
    compose_final(workdir / "agent_figure.pdf", stem, out, args.dpi)
    print(f"完成: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
