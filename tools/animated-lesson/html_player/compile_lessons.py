# -*- coding: utf-8 -*-
"""分镜 JSON（lesson-plan/v1）→ HTML 播放器数据编译器。

为什么存在：lesson_runtime.py 是 Manim 视频路线的解析器；本文件把同一份分镜
的"几何语义"（派生点符号求解、直线延长、角标记参数）解析成纯数学坐标 JSON，
交给 html_player/player.js 做点击式 SVG 动画。两者共享同一套几何公式
（tangent_points 等直接对齐 lesson_runtime.py，保证视频版/网页版图形一致）。

用法：python compile_lessons.py [lesson.json ...]
     缺省编译 ../lessons/tangent-min.json 与 ../lessons/tangent-angle.json（交付指定两课）。
输出：data/<id>.js —— window.LESSONS["<id>"] = {...}，JSONP 风格避免 file:// 与
     静态服务的 fetch/CORS 差异，index.html 用 <script src> 直接挂载。
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LESSONS_DIR = HERE.parent / "lessons"


def tangent_points(px, py, cx, cy, r):
    """外点 P 到圆 (C,r) 的两切点；[0]=逆时针侧, [1]=顺时针侧。与 lesson_runtime 同公式。"""
    dx, dy = px - cx, py - cy
    d = math.hypot(dx, dy)
    if d < r - 1e-12:
        return []
    ux, uy = dx / d, dy / d
    phi = math.acos(max(-1.0, min(1.0, r / d)))
    out = []
    for sign in (+1, -1):
        c, s = math.cos(sign * phi), math.sin(sign * phi)
        rx, ry = ux * c - uy * s, ux * s + uy * c
        out.append((cx + r * rx, cy + r * ry))
    return out


class Resolver:
    """把 objects 的约束定义解析为具体几何参数（数学坐标），供前端直接绘制。"""

    def __init__(self, sb: dict):
        self.sb = sb
        self.cache: dict[str, tuple] = {}

    def point(self, ref):
        """引用 → (x, y)。数字对直返；字符串查对象。"""
        if isinstance(ref, (list, tuple)):
            return (float(ref[0]), float(ref[1]))
        kind, val = self.resolve(ref)
        if kind != "point":
            raise ValueError(f"{ref} 不是点")
        return val

    def resolve(self, name):
        if name in self.cache:
            return self.cache[name]
        spec = self.sb["objects"][name]
        t = spec["type"]
        if t == "point":
            out = ("point", tuple(float(v) for v in spec["at"]))
        elif t == "tangent_point":
            p = self.point(spec["from"])
            cs = self.sb["objects"][spec["circle"]]
            c = self.point(cs["center"])
            pts = tangent_points(p[0], p[1], c[0], c[1], float(cs["radius"]))
            idx = 0 if spec.get("branch", "ccw") == "ccw" else 1
            out = ("point", pts[idx])
        elif t == "foot":
            p = self.point(spec["from"])
            a = self.point(spec["line"][0])
            b = self.point(spec["line"][1])
            vx, vy = b[0] - a[0], b[1] - a[1]
            tpar = ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / (vx * vx + vy * vy)
            out = ("point", (a[0] + tpar * vx, a[1] + tpar * vy))
        elif t == "mid":
            a = self.point(spec["of"][0])
            b = self.point(spec["of"][1])
            out = ("point", ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2))
        elif t in ("circle", "segment", "line", "ray", "angle", "function"):
            out = (t, spec)
        else:
            raise ValueError(f"未知对象类型: {t}")
        self.cache[name] = out
        return out


def compile_objects(sb: dict) -> dict:
    """objects → 可绘制描述（全部为数学坐标，无派生 ambiguity）。"""
    r = Resolver(sb)
    out = {}
    for name, spec in sb["objects"].items():
        t = spec["type"]
        style = spec.get("style", "main")
        if t in ("point", "tangent_point", "foot", "mid"):
            x, y = r.point(name)
            out[name] = {"kind": "point", "x": x, "y": y, "style": style,
                         "label": spec.get("label")}
        elif t == "circle":
            c = r.point(spec["center"])
            out[name] = {"kind": "circle", "cx": c[0], "cy": c[1],
                         "r": float(spec["radius"]), "style": style}
        elif t == "segment":
            a = r.point(spec["from"]); b = r.point(spec["to"])
            # refs 保留原始端点引用：trace_move 时按名字判断哪端是动点（对齐 lesson_runtime）
            out[name] = {"kind": "segment", "x1": a[0], "y1": a[1], "x2": b[0], "y2": b[1],
                         "style": style, "dashed": bool(spec.get("dashed")),
                         "refs": {"from": spec["from"], "to": spec["to"]}}
        elif t == "line":
            a = r.point(spec["from"]); b = r.point(spec["to"])
            ext = spec.get("extend", 0.15)
            vx, vy = b[0] - a[0], b[1] - a[1]
            out[name] = {"kind": "segment", "x1": a[0] - vx * ext, "y1": a[1] - vy * ext,
                         "x2": b[0] + vx * ext, "y2": b[1] + vy * ext,
                         "style": style, "dashed": bool(spec.get("dashed")),
                         "refs": {"from": spec["from"], "to": spec["to"], "extend": ext}}
        elif t == "ray":
            a = r.point(spec["from"]); b = r.point(spec["to"])
            vx, vy = b[0] - a[0], b[1] - a[1]
            out[name] = {"kind": "ray", "x1": a[0], "y1": a[1],
                         "x2": a[0] + vx * 1.4, "y2": a[1] + vy * 1.4, "style": style}
        elif t == "angle":
            v = r.point(spec["at"]); a = r.point(spec["from"]); b = r.point(spec["to"])
            if spec.get("style") == "square":
                s = spec.get("size", 0.35)

                def unit(p):
                    dx, dy = p[0] - v[0], p[1] - v[1]
                    d = math.hypot(dx, dy) or 1.0
                    return (dx / d, dy / d)
                u1, u2 = unit(a), unit(b)
                pts = [(v[0] + s * u1[0], v[1] + s * u1[1]),
                       (v[0] + s * (u1[0] + u2[0]), v[1] + s * (u1[1] + u2[1])),
                       (v[0] + s * u2[0], v[1] + s * u2[1])]
                out[name] = {"kind": "angle_square", "pts": pts,
                             "refs": {"at": spec["at"], "from": spec["from"], "to": spec["to"]},
                             "size": s}
            else:
                av = math.atan2(a[1] - v[1], a[0] - v[0])
                bv = math.atan2(b[1] - v[1], b[0] - v[0])
                out[name] = {"kind": "angle_arc", "vx": v[0], "vy": v[1],
                             "a0": min(av, bv), "a1": max(av, bv),
                             "r": spec.get("radius", 0.3),
                             "refs": {"at": spec["at"], "from": spec["from"], "to": spec["to"]}}
        elif t == "function":
            # 函数曲线：采样成折线。sympy 与 manim 路线同源；缺库时报错而不是静默画错。
            import sympy
            x = sympy.Symbol("x")
            expr = sympy.sympify(spec["expr"])
            x0, x1 = spec["x"]
            n = 240
            pts = []
            for i in range(n + 1):
                xx = x0 + (x1 - x0) * i / n
                yy = float(expr.subs(x, xx))
                if math.isfinite(yy):
                    pts.append((xx, yy))
            out[name] = {"kind": "polyline", "pts": pts, "style": style}
        else:
            raise ValueError(t)
    return out


def compile_trace(step: dict, sb: dict, r: Resolver) -> dict:
    """trace_move 需要 mover 的固定 y 与圆的解析结果；trace_line 参数本身已具体。"""
    s = dict(step)
    if step["op"] == "trace_move":
        spec = sb["objects"][step["mover"]]
        cs = sb["objects"][step["circle"]]
        c = r.point(cs["center"])
        s["moverY"] = float(spec["at"][1])
        s["circleCenter"] = [c[0], c[1]]
        s["circleRadius"] = float(cs["radius"])
    return s


def compile_lesson(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        sb = json.load(f)
    r = Resolver(sb)
    chapters = []
    for ch in sb["chapters"]:
        chapters.append({
            "id": ch["id"], "title": ch["title"], "subtitle": ch.get("subtitle", ""),
            "steps": [compile_trace(s, sb, r) for s in ch["steps"]],
        })
    return {
        "schema": "lesson-html/v1",
        "id": sb["id"], "title": sb["title"],
        "problem": sb["problem"], "view": sb["view"],
        "objects": compile_objects(sb),
        "chapters": chapters,
    }


def main(argv):
    paths = [Path(a) for a in argv] or [LESSONS_DIR / "tangent-min.json",
                                        LESSONS_DIR / "tangent-angle.json"]
    out_dir = HERE / "data"
    out_dir.mkdir(exist_ok=True)
    for p in paths:
        lesson = compile_lesson(p)
        dst = out_dir / f"{lesson['id']}.js"
        payload = json.dumps(lesson, ensure_ascii=False, separators=(",", ":"))
        dst.write_text(
            "// 由 compile_lessons.py 生成，勿手改；源头是 lessons/*.json\n"
            "window.LESSONS = window.LESSONS || {};\n"
            f"window.LESSONS[{json.dumps(lesson['id'])}] = {payload};\n",
            encoding="utf-8")
        print(f"compiled {p.name} -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1:])
