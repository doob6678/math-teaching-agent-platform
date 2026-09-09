# -*- coding: utf-8 -*-
"""扫描分镜 $...$ 段内的不规范 LaTeX（老板 09-09 反馈：根号没套上、分数没叠起）。

三类口径问题（MathTex 能编译但排版不标准，视频里一眼可见）：
1. 斜杠分数：`.../2`、`.../4` 等——应写 \\frac{分子}{分母}；
2. 上标三角形：`^{\\triangle ...}`——面积记法应为下标 `S_{\\triangle ABC}`；
3. 裸幂：`x**2`——应写 `x^2`（expr 字段除外，那是编译器语法）。
用法：python scan_bad_latex.py [lesson_id ...]
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
DEFAULT = ["tangent-min", "two-circle-tangents", "tangent-angle", "ellipse-moving-line"]
MATH_SEG = re.compile(r"\$([^$]+)\$")
SLASH_FRACTION = re.compile(r"[0-9a-zA-Z}\)\]]\s*/\s*\d")
SUP_TRIANGLE = re.compile(r"\^\{?\\triangle")
BARE_POW = re.compile(r"\*\*")


def walk(x, path, out):
    if isinstance(x, str):
        for seg in MATH_SEG.findall(x):
            bad = []
            if SLASH_FRACTION.search(seg):
                bad.append("slash-fraction")
            if SUP_TRIANGLE.search(seg):
                bad.append("sup-triangle")
            if BARE_POW.search(seg):
                bad.append("bare-**")
            if bad:
                out.append((path, seg, bad))
    elif isinstance(x, dict):
        for k, v in x.items():
            if k == "expr":
                continue  # 函数表达式是编译器语法，不展示
            walk(v, f"{path}.{k}", out)
    elif isinstance(x, list):
        for i, v in enumerate(x):
            walk(v, f"{path}[{i}]", out)


def main():
    ids = sys.argv[1:] or DEFAULT
    total = 0
    for lid in ids:
        sb = json.loads((HERE / "lessons" / f"{lid}.json").read_text(encoding="utf-8"))
        hits = []
        walk(sb, "", hits)
        for path, seg, bad in hits:
            print(f"{lid} {path} | {seg[:70]} | {bad}")
            total += 1
    print(f"total {total}")
    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
