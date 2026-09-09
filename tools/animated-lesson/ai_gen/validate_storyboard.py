# -*- coding: utf-8 -*-
"""分镜 JSON 的 schema 校验器（lesson-plan/v1）。

目的：在烧渲染时间之前，用确定性规则拦截 LLM 生成 JSON 的常见错误
（op 幻觉、引用悬空、派生点几何退化、越界、缺字），错误清单回喂模型自修。
用法：python validate_storyboard.py lessons/xxx.json  → 退出码 0=通过；非 0 打印错误清单。
"""
from __future__ import annotations

import json
import math
import sys

OBJ_TYPES = {"point", "circle", "segment", "line", "ray", "angle", "function",
             "tangent_point", "foot", "mid"}
OPS = {"draw", "show", "label", "caption", "note", "formula", "highlight", "dim",
       "hide", "trace_move", "trace_line", "answer", "wait"}
# 微软雅黑实测缺字/易渲染成豆腐块的符号（SCHEMA 纪律 3）
BAD_GLYPHS = ["⟺", "⇔", "∴", "∵", "≌", "∽"]


def _xy(v):
    return (float(v[0]), float(v[1]))


def validate(sb: dict) -> list[str]:
    errs: list[str] = []
    if sb.get("schema") != "lesson-plan/v1":
        errs.append("schema 必须为 lesson-plan/v1")
    for k in ("id", "title", "problem", "view", "objects", "chapters"):
        if k not in sb:
            errs.append(f"缺顶层字段 {k}")
    if errs:
        return errs
    p = sb["problem"]
    for k in ("stem", "known", "goal", "core_observation", "answer"):
        if not p.get(k):
            errs.append(f"problem.{k} 缺失")
    view = sb["view"]
    (x0, x1), (y0, y1) = _xy(view["x"]), _xy(view["y"])
    if x0 >= x1 or y0 >= y1:
        errs.append("view 范围非法")
    objs = sb["objects"]

    def ref(name, where):
        if name not in objs:
            errs.append(f"{where}: 引用不存在的对象 {name}")
            return None
        return objs[name]

    # 逐对象解析出数学坐标（含派生点），供越界/几何检查
    resolved: dict[str, tuple] = {}
    for _ in range(3):  # 依赖最多两层（切点引用圆，圆引用圆心）
        for name, spec in objs.items():
            t = spec.get("type")
            if t not in OBJ_TYPES:
                errs.append(f"objects.{name}: 未知类型 {t}")
                continue
            try:
                if t == "point":
                    resolved[name] = _xy(spec["at"])
                elif t == "circle":
                    c = spec["center"]
                    if isinstance(c, str):
                        if c in resolved:
                            resolved[name] = resolved[c]
                    else:
                        resolved[name] = _xy(c)
                elif t in ("segment", "line", "ray"):
                    for side in ("from", "to"):
                        v = spec[side]
                        if isinstance(v, str):
                            if v in resolved:
                                pass
                            else:
                                errs.append(f"objects.{name}.{side}: 未解析点 {v}")
                elif t == "tangent_point":
                    pf, cs = spec["from"], spec.get("circle")
                    if pf in resolved and cs in objs and cs in resolved:
                        r = float(objs[cs]["radius"])
                        d = math.hypot(resolved[pf][0] - resolved[cs][0],
                                       resolved[pf][1] - resolved[cs][1])
                        if d <= r + 1e-9:
                            errs.append(f"objects.{name}: 外点 {pf} 在圆 {cs} 内/上，切点不存在")
                        else:
                            phi = math.acos(r / d)
                            ux, uy = ((resolved[pf][0] - resolved[cs][0]) / d,
                                      (resolved[pf][1] - resolved[cs][1]) / d)
                            ang = math.atan2(uy, ux) + (phi if spec.get("branch", "ccw") == "ccw" else -phi)
                            resolved[name] = (resolved[cs][0] + r * math.cos(ang),
                                              resolved[cs][1] + r * math.sin(ang))
                elif t == "mid":
                    a, b = spec["of"]
                    if a in resolved and b in resolved:
                        resolved[name] = ((resolved[a][0] + resolved[b][0]) / 2,
                                          (resolved[a][1] + resolved[b][1]) / 2)
                elif t == "foot":
                    pv = spec["from"]
                    la, lb = spec["line"]
                    pa = resolved.get(pv)
                    a = resolved.get(la) if isinstance(la, str) else _xy(la)
                    b = resolved.get(lb) if isinstance(lb, str) else _xy(lb)
                    if pa and a and b:
                        vx, vy = b[0] - a[0], b[1] - a[1]
                        tpar = ((pa[0] - a[0]) * vx + (pa[1] - a[1]) * vy) / (vx * vx + vy * vy)
                        resolved[name] = (a[0] + tpar * vx, a[1] + tpar * vy)
            except Exception as e:  # 解析失败回喂模型修
                errs.append(f"objects.{name}: 解析失败 {e}")
    # 越界检查（留 3% 余量）
    mx, my = (x1 - x0) * 0.03, (y1 - y0) * 0.03
    for name, (px, py) in resolved.items():
        if objs[name]["type"] in ("point", "tangent_point", "mid", "foot") and \
           not (x0 - mx <= px <= x1 + mx and y0 - my <= py <= y1 + my):
            errs.append(f"objects.{name} 坐标 ({px:.2f},{py:.2f}) 超出 view，需扩范围或挪点")
    # 章节与步骤
    seen_ids = set()
    for ch in sb["chapters"]:
        cid = ch.get("id")
        if not cid or cid in seen_ids:
            errs.append(f"章节 id 缺失或重复: {cid}")
        seen_ids.add(cid)
        if not ch.get("title") or not ch.get("narration"):
            errs.append(f"{cid}: title/narration 不能为空")
        for s in ch.get("steps", []):
            op = s.get("op")
            if op not in OPS:
                errs.append(f"{cid}: 未知 op {op}")
                continue
            if op in ("draw", "show", "label") and s.get("ref") and s["ref"] not in objs:
                errs.append(f"{cid}/{op}: ref {s['ref']} 不存在")
            if op in ("dim", "hide", "highlight"):
                for r in s.get("refs", [s.get("ref")]):
                    if r and r not in objs:
                        errs.append(f"{cid}/{op}: refs 含 {r} 不存在")
            if op == "trace_move":
                if s.get("mover") not in objs or s.get("circle") not in objs:
                    errs.append(f"{cid}/trace_move: mover/circle 引用缺失")
                for lk in s.get("links", []):
                    if lk not in objs:
                        errs.append(f"{cid}/trace_move: link {lk} 不存在")
            if op == "trace_line":
                ax, ay = _xy(s["pivot"])
                if not (x0 <= ax <= x1 and y0 <= ay <= y1):
                    errs.append(f"{cid}/trace_line: pivot 出画")
                con = s.get("conic", {})
                if con.get("a", 0) <= 0 or con.get("b", 0) <= 0:
                    errs.append(f"{cid}/trace_line: conic a/b 非法")
                for lk in s.get("links", []):
                    if lk not in objs:
                        errs.append(f"{cid}/trace_line: link {lk} 不存在")
            for txt in (s.get("text", ""),):
                for g in BAD_GLYPHS:
                    if g in txt:
                        errs.append(f"{cid}/{op}: 文本含雅黑缺字 {g}")
    return errs


if __name__ == "__main__":
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    errors = validate(data)
    if errors:
        print("INVALID:")
        for e in errors:
            print(" -", e)
        sys.exit(1)
    print("VALID")
