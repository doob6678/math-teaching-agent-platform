# -*- coding: utf-8 -*-
"""扫描分镜 JSON 里 $...$ 之外的 Unicode 伪公式（老板 09-09 截图抓到的漏网行）。

判定：去掉所有 $...$ 真排版段后，剩余文本若含 √²³△×÷ 或裸分数 x/y 或 S△ 等，
即为应转 $...$ 而未转的行。用法：python scan_unicode_math.py [lesson_id ...]
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
DEFAULT = ["tangent-min", "two-circle-tangents", "tangent-angle", "ellipse-moving-line"]
MATH_CHARS = "√²³△×÷≤≥≠Ⅰ"
BARE_FRACTION = re.compile(r"\d/\d")


def walk(x, path, out):
    if isinstance(x, str):
        bare = re.sub(r"\$[^$]+\$", "", x)  # 移除真排版段
        bad = [c for c in MATH_CHARS if c in bare] + BARE_FRACTION.findall(bare)
        if bad:
            out.append((path, x, bad))
    elif isinstance(x, dict):
        for k, v in x.items():
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
        for path, text, bad in hits:
            print(f"{lid} {path} | {text[:80]} | {bad}")
            total += 1
    print(f"total {total}")
    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
