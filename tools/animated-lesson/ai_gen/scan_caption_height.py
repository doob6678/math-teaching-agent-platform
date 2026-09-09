# -*- coding: utf-8 -*-
"""扫描 lessons/*.json 的字幕步：含 \\frac/\\sqrt 的混排字幕行高约 1，
在 caption_y=-3.62 中心锚点下分母会沉出画面底边（lesson_runtime 已加钳制，
但旧视频需按此清单重渲染）。输出需要重渲染的课。"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
TALL = ("\\frac", "\\dfrac", "\\sqrt", "\\sum", "\\int")

def main() -> int:
    need = []
    for f in sorted((HERE / "lessons").glob("*.json")):
        doc = json.loads(f.read_text(encoding="utf-8"))
        hits = []
        for ch in doc.get("chapters", []):
            for st in ch.get("steps", []):
                if st.get("op") == "caption":
                    text = st.get("text", "")
                    if any(t in text for t in TALL):
                        hits.append((ch.get("title"), text[:40]))
        if hits:
            need.append((f.name, hits))
    for name, hits in need:
        print(f"{name}: {len(hits)} 条高字幕")
        for title, frag in hits:
            print(f"  [{title}] {frag}")
    if not need:
        print("无含分式/根号的字幕")
    return 0

if __name__ == "__main__":
    sys.exit(main())
