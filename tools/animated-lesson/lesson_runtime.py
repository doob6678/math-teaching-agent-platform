# -*- coding: utf-8 -*-
"""一题一课 · 分镜剧本 → Manim 场景运行时。

设计边界（与 AGENTS.md 一致）：
- AI/作者只产出分镜 JSON（封闭 op 枚举 + 几何构造定义），永远不填裸像素坐标、不直写代码；
  坐标由本文件按数学约束解析（切点、垂足等都是符号解），从根上规避 LLM 直写代码的
  ManiBench 两类失败（API 幻觉 / 视觉-逻辑漂移）。
- 本文件是"确定性播放器"：同一份分镜 JSON 渲染结果稳定，失败只会是参数非法，容易定位。

布局（1920x1080，纸面风）：
- 左侧约 60%：题目几何画布（坐标系 + 图形，逐笔绘制）
- 右侧约 40%：解题笔记本（推导逐行累积，超限自动上移）
- 底部：中文字幕带；顶部：章节横幅（每章出现 2 秒后缩小常驻左上角）

中文一律 Pango Text（Microsoft YaHei，生产 Linux 换思源黑体），公式走 MathTex，
MathTex 不可用时自动降级为 Unicode 文本，保证交付率。
"""
from __future__ import annotations

import json
import math
import os
import re

from manim import (
    DOWN, LEFT, ORIGIN, RIGHT, UP,
    Arc, Arrow, Circle, Create, Dot, DashedLine, FadeIn, FadeOut,
    Indicate, Line, MathTex, NumberPlane, Polygon, Scene, SurroundingRectangle,
    Text, Transform, ValueTracker, VGroup, WHITE, linear,
)

FONT = os.environ.get("LESSON_FONT", "Microsoft YaHei")

# 配色：纸面 + 墨色 + 功能色（蓝=主体、绿=辅助线、橙=强调/变量、红=结论）
# 用 hex 字符串而非 Color 对象：manim 0.21 顶层未导出 Color，字符串处处可解析
PAPER = "#FAF8F2"
INK = "#1F2937"
BLUE = "#4D6BFE"
GREEN = "#0E9F6E"
ORANGE = "#E8890C"
RED = "#DC2626"
GRAY = "#9CA3AF"

STYLE_COLORS = {"main": BLUE, "aux": GREEN, "hl": ORANGE, "ans": RED}

# 每种 op 的基准时长（秒）；章节总时长由配音音频决定，步骤按比例缩放
BASE_TIME = {
    "draw": 1.3, "show": 0.7, "label": 0.7, "caption": 0.35, "note": 1.1,
    "formula": 1.2, "highlight": 1.3, "dim": 0.6, "hide": 0.6,
    "trace_move": 3.2, "answer": 1.6, "wait": 0.5, "banner_hold": 1.6,
}


def _deg(rad: float) -> float:
    return math.degrees(rad)


def tangent_points(px, py, cx, cy, r):
    """外点 P 到圆 (C,r) 的两个切点（数学坐标）。|PC|<r 时返回 []。"""
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
    return out  # [0]=逆时针侧(左切点), [1]=顺时针侧(右切点)


class LessonScene(Scene):
    """通用分镜场景：由 make_lesson_scene() 绑定具体 JSON 后交给 manim CLI。"""

    def __init__(self, storyboard: dict, timing: dict, **kw):
        super().__init__(**kw)
        self.sb = storyboard
        self.timing = timing  # {chapter_id: 目标秒数}
        self.axes = None
        self.objs: dict[str, object] = {}   # name -> 定义
        self.mobs: dict[str, object] = {}   # name -> 已创建 mobject
        self.labels: dict[str, object] = {} # name -> label mobject
        self.caption_mob = None
        self.note_lines = []
        self.note_group = None
        self.note_y = 0.0
        self.banner_small = None

    # ---------- 几何解析：数学坐标 → 场景坐标 ----------

    def _m2s(self, x, y):
        """math → screen 坐标。"""
        return self.axes.c2p(x, y)

    def _scale(self):
        v = self.sb["view"]
        dx = v["x"][1] - v["x"][0]
        return self.axes.x_length / dx

    def _resolve(self, name):
        """惰性解析对象定义为具体几何参数（切点/垂足在这里符号求解，作者只声明约束）。"""
        if name in self.objs:
            return self.objs[name]
        spec = self.sb["objects"][name]
        t = spec["type"]
        if t == "point":
            self.objs[name] = ("point", tuple(spec["at"]))
        elif t == "tangent_point":
            p = self._resolve_xy(spec["from"])
            cs = self.sb["objects"][spec["circle"]]   # 圆心要经 circle 定义的 center 字段取点
            c = self._resolve_xy(cs["center"])
            r = float(cs["radius"])
            pts = tangent_points(p[0], p[1], c[0], c[1], r)
            idx = 0 if spec.get("branch", "ccw") == "ccw" else 1
            self.objs[name] = ("point", pts[idx])
        elif t == "foot":
            p = self._resolve_xy(spec["from"])
            a = self._resolve_xy(spec["line"][0])
            b = self._resolve_xy(spec["line"][1])
            vx, vy = b[0] - a[0], b[1] - a[1]
            tpar = ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / (vx * vx + vy * vy)
            self.objs[name] = ("point", (a[0] + tpar * vx, a[1] + tpar * vy))
        elif t == "mid":
            a = self._resolve_xy(spec["of"][0]); b = self._resolve_xy(spec["of"][1])
            self.objs[name] = ("point", ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2))
        elif t == "circle":
            self.objs[name] = ("circle", spec)
        elif t in ("segment", "line", "ray"):
            self.objs[name] = (t, spec)
        elif t == "angle":
            self.objs[name] = ("angle", spec)
        elif t == "function":
            self.objs[name] = ("function", spec)
        else:
            raise ValueError(f"未知对象类型: {t}")
        return self.objs[name]

    def _resolve_radius(self, name):
        spec = self.sb["objects"][name]
        return float(spec["radius"])

    def _resolve_xy(self, ref):
        """引用解析：数字对直接返回；字符串查对象。"""
        if isinstance(ref, (list, tuple)):
            return (float(ref[0]), float(ref[1]))
        kind, val = self._resolve(ref)
        if kind != "point":
            raise ValueError(f"{ref} 不是点")
        return val

    # ---------- 布局 ----------

    def _layout(self):
        """画布区/笔记本区/字幕带/横幅位置。frame 半宽 7.11 半高 4。
        fig 顶边压到 3.05：给顶部章节横幅让位，避免圆顶/小横幅叠字。"""
        self.fig_region = (-6.95, 0.35, -3.05, 3.05)   # x0,x1,y0,y1
        self.note_region = (0.95, 6.95, -2.65, 3.05)
        self.caption_y = -3.62
        self.banner_y = 3.55
        self.note_y = self.note_region[3] - 0.35  # 首行从笔记本顶部开始，不是屏幕中线

    def _build_axes(self):
        v = self.sb["view"]
        x0, x1 = v["x"]; y0, y1 = v["y"]
        w = self.fig_region[1] - self.fig_region[0]
        h = self.fig_region[3] - self.fig_region[2]
        s = min(w / (x1 - x0), h / (y1 - y0))
        ax_len, ay_len = (x1 - x0) * s, (y1 - y0) * s
        cx = (self.fig_region[0] + self.fig_region[1]) / 2
        cy = (self.fig_region[2] + self.fig_region[3]) / 2
        ax = NumberPlane(
            x_range=[x0, x1, 1], y_range=[y0, y1, 1],
            x_length=ax_len, y_length=ay_len,
            background_line_style={"stroke_color": str(GRAY), "stroke_width": 0.6, "stroke_opacity": 0.0},
            axis_config={"stroke_color": INK, "stroke_width": 2.5,
                         "tip_shape": None, "include_ticks": False},
        )
        ax.move_to([cx, cy, 0])
        # 坐标轴箭头与名称
        ax.add(Arrow(ax.c2p(x1 - (x1 - x0) * 0.02, 0), ax.c2p(x1, 0), buff=0,
                     stroke_color=INK, stroke_width=2.5, max_tip_length_to_length_ratio=0.35))
        ax.add(Arrow(ax.c2p(0, y1 - (y1 - y0) * 0.02), ax.c2p(0, y1), buff=0,
                     stroke_color=INK, stroke_width=2.5, max_tip_length_to_length_ratio=0.35))
        for txt, pos in (("x", ax.c2p(x1, 0) + DOWN * 0.3), ("y", ax.c2p(0, y1) + LEFT * 0.32),
                         ("O", ax.c2p(0, 0) + LEFT * 0.3 + DOWN * 0.3)):
            lab = Text(txt, font="Times New Roman", slant="ITALIC", font_size=30, color=INK).move_to(pos)
            ax.add(lab)
        self.axes = ax
        if v.get("axes", True):
            self.add(ax)
        # 刻度标注：view.ticks = {"x": [-4], "y": [5]}
        for axis_name, vals in (v.get("ticks") or {}).items():
            for val in vals:
                if axis_name == "x":
                    p1, p2 = ax.c2p(val, -0.08), ax.c2p(val, 0.08)
                    lp = ax.c2p(val, 0) + DOWN * 0.32
                else:
                    p1, p2 = ax.c2p(-0.08, val), ax.c2p(0.08, val)
                    lp = ax.c2p(0, val) + LEFT * 0.4
                tick = Line(p1, p2, stroke_color=INK, stroke_width=2)
                tl = Text(str(val), font=FONT, font_size=24, color=INK).move_to(lp)
                self.add(tick, tl)

    # ---------- 对象创建 ----------

    def _stroke(self, spec):
        style = spec.get("style", "main")
        return STYLE_COLORS.get(style, BLUE), 3.2 if style == "main" else 3.0

    def _create(self, name):
        """按定义实例化 mobject（缓存）。"""
        if name in self.mobs:
            return self.mobs[name]
        kind, val = self._resolve(name)
        spec = self.sb["objects"].get(name, {})
        if kind == "point":
            color = STYLE_COLORS.get(spec.get("style", "main"), BLUE)
            m = Dot(self._m2s(*val), radius=0.06, color=color)
        elif kind == "circle":
            c = self._resolve_xy(val["center"]) if isinstance(val.get("center"), str) else val["center"]
            r = self._resolve_radius(name)
            m = Circle(radius=r * self._scale(), color=BLUE, stroke_width=3)
            m.move_to(self._m2s(*c))
        elif kind == "segment":
            a = self._resolve_xy(val["from"]); b = self._resolve_xy(val["to"])
            color, sw = self._stroke(val)
            cls = DashedLine if val.get("dashed") else Line
            m = cls(self._m2s(*a), self._m2s(*b), stroke_color=color, stroke_width=sw)
        elif kind == "line":
            a = self._resolve_xy(val["from"]); b = self._resolve_xy(val["to"])
            color, sw = self._stroke(val)
            ext = val.get("extend", 0.15)
            va = [b[0] - a[0], b[1] - a[1]]
            m = Line(self._m2s(a[0] - va[0] * ext, a[1] - va[1] * ext),
                     self._m2s(b[0] + va[0] * ext, b[1] + va[1] * ext),
                     stroke_color=color, stroke_width=sw)
        elif kind == "ray":
            a = self._resolve_xy(val["from"]); b = self._resolve_xy(val["to"])
            color, sw = self._stroke(val)
            va = [b[0] - a[0], b[1] - a[1]]
            m = Arrow(self._m2s(*a), self._m2s(a[0] + va[0] * 1.4, a[1] + va[1] * 1.4),
                      buff=0, stroke_color=color, stroke_width=sw)
        elif kind == "angle":
            m = self._make_angle(val, self._resolve_xy(val["at"]),
                                 self._resolve_xy(val["from"]), self._resolve_xy(val["to"]))
        elif kind == "function":
            import sympy
            x = sympy.Symbol("x")
            expr = sympy.sympify(val["expr"])
            fn = lambda xx: float(expr.subs(x, xx)) if xx == xx else float("nan")
            m = self.axes.plot(fn, x_range=(val["x"][0], val["x"][1]),
                               use_smoothing=False, color=BLUE, stroke_width=3)
        else:
            raise ValueError(kind)
        self.mobs[name] = m
        return m

    # ---------- 混排：中文 Text + 行内 $LaTeX$ MathTex（公式必须真排版，禁 Unicode 伪公式） ----------

    def _mixed(self, text, font_size=25, color=None, math_size=None):
        """按 $...$ 切段：中文段 Pango Text、公式段 MathTex，底对齐横排。
        无 $ 时等价纯 Text；MathTex 失败降级回原文，保证交付率。"""
        color = color or INK
        parts = re.split(r"\$([^$]+)\$", text)
        if len(parts) == 1:
            return Text(text, font=FONT, font_size=font_size, color=color, line_spacing=0.9)
        mobs = []
        for i, seg in enumerate(parts):
            if not seg:
                continue
            if i % 2 == 1:
                try:
                    m = MathTex(seg, color=str(color), font_size=math_size or font_size + 8)
                except Exception:
                    m = Text(seg, font=FONT, font_size=font_size, color=color)
            else:
                m = Text(seg, font=FONT, font_size=font_size, color=color, line_spacing=0.9)
            mobs.append(m)
        return VGroup(*mobs).arrange(RIGHT, buff=0.14, aligned_edge=DOWN)

    # ---------- 笔记本 / 字幕 / 横幅 ----------

    def _note(self, step, scale_t):
        text = step.get("text", "")
        if step.get("kind") == "formula":
            try:
                # font_size 必须收窄：MathTex 默认 48pt 的分式高度会撑爆笔记本行距
                m = MathTex(text, color=str(INK), font_size=34)
            except Exception:
                m = Text(_unicode_math(text), font=FONT, font_size=26, color=INK)
        else:
            m = self._mixed(text, font_size=25)
        x0, x1, y0, _ = self.note_region
        m.move_to([(x0 + x1) / 2, self.note_y, 0])
        if m.width > x1 - x0:
            m.set_width(x1 - x0 - 0.1)
        if self.note_y - m.height / 2 < y0 and self.note_lines:
            shift = UP * (m.height + 0.18)
            self.play(Transform(self.note_group, self.note_group.copy().shift(shift)), run_time=0.35)
            for ln in self.note_lines:
                ln.shift(shift)
            self.note_y += m.height + 0.18
        self.play(FadeIn(m, shift=DOWN * 0.12), run_time=0.35 * scale_t)
        self.note_lines.append(m)
        if self.note_group is None:
            self.note_group = VGroup(m)
        else:
            self.note_group.add(m)
        self.note_y -= m.height + 0.22

    def _caption(self, step):
        new = self._mixed(step["text"], font_size=27, math_size=34)
        if new.width > 13.4:
            new.set_width(13.4)
        new.move_to([0, self.caption_y, 0])
        anims = [FadeIn(new)]
        if self.caption_mob:
            anims = [FadeOut(self.caption_mob), FadeIn(new)]
            self.play(*anims, run_time=0.35)
        else:
            self.play(anims[0], run_time=0.35)
        self.caption_mob = new

    def _banner(self, idx, ch):
        """章节横幅：出现 2s，然后缩小常驻左上角（对齐竞品章节感）。"""
        title = f"{idx}  {ch['title']}"
        sub = ch.get("subtitle", "")
        grp = VGroup(
            Text(title, font=FONT, font_size=27, color=WHITE),
            Text(sub, font=FONT, font_size=20, color=WHITE) if sub else Text(" ", font=FONT, font_size=1),
        ).arrange(DOWN, buff=0.07)
        box = SurroundingRectangle(grp, buff=0.14, corner_radius=0.12,
                                   color=BLUE, fill_color=BLUE, fill_opacity=0.92)
        banner = VGroup(box, grp).move_to([0, self.banner_y, 0])
        self.play(FadeIn(banner, shift=DOWN * 0.1), run_time=0.4)
        self.wait(BASE_TIME["banner_hold"])
        small = banner.copy().set_opacity(0.9)
        small.scale(0.38).move_to([-6.62, 3.82, 0])
        self.play(Transform(banner, small), run_time=0.4)
        if self.banner_small:
            self.remove(self.banner_small)
        self.banner_small = banner

    def _answer(self, step):
        text = step.get("text", "")
        try:
            m = MathTex(text, color=str(RED), font_size=40)
        except Exception:
            m = Text(_unicode_math(text), font=FONT, font_size=34, color=RED)
        box = SurroundingRectangle(m, buff=0.18, corner_radius=0.1, color=GREEN, stroke_width=3)
        grp = VGroup(m, box).move_to([4.0, 1.6, 0])
        self.play(FadeIn(grp, scale=0.9), run_time=0.5)
        self.play(Indicate(grp, color=GREEN), run_time=0.6)

    def _make_angle(self, spec, v, a, b):
        """角标记：style=square 为教材直角小方块（数学单位尺寸），否则为弧。"""
        if spec.get("style") == "square":
            s = spec.get("size", 0.35)

            def unit(p):
                dx, dy = p[0] - v[0], p[1] - v[1]
                d = math.hypot(dx, dy) or 1.0
                return dx / d, dy / d
            u1, u2 = unit(a), unit(b)
            pts = [(v[0] + s * u1[0], v[1] + s * u1[1]),
                   (v[0] + s * (u1[0] + u2[0]), v[1] + s * (u1[1] + u2[1])),
                   (v[0] + s * u2[0], v[1] + s * u2[1])]
            return Polygon(*[self._m2s(*p) for p in pts],
                           stroke_color=ORANGE, stroke_width=2.5)
        av = math.atan2(a[1] - v[1], a[0] - v[0])
        bv = math.atan2(b[1] - v[1], b[0] - v[0])
        return Arc(radius=spec.get("radius", 0.3), start_angle=min(av, bv), angle=abs(bv - av),
                   arc_center=self._m2s(*v), stroke_color=ORANGE, stroke_width=2.5)

    # ---------- 动点演示（核心镜头：P 沿轴移动，切线/半径/垂线/角/标签实时跟随） ----------

    def _trace_move(self, step, run_time):
        """links 只引用分镜里已存在的线段名，更新器按端点定义重算——不新建重复线。"""
        mover = step["mover"]
        spec = self.sb["objects"][mover]
        cs = self.sb["objects"][step["circle"]]
        c = self._resolve_xy(cs["center"])
        r = float(cs["radius"])
        branch = step.get("branch", "right")
        y_fixed = float(spec["at"][1])
        tracker = ValueTracker(float(step["from"]))
        p_dot = self.mobs[mover]
        tname = step.get("tangent_point")
        b_dot = self.mobs.get(tname) if tname else None
        segs = []
        for name in step.get("links", []):
            s = self.sb["objects"][name]
            segs.append((self.mobs[name], s["from"], s["to"]))
        arcs = []
        for name in step.get("arcs", []):
            s = self.sb["objects"][name]
            arcs.append((self.mobs[name], s["at"], s["from"], s["to"], s))
        foot_from = step.get("foot_line")
        foot_m = None
        if foot_from:
            foot_m = DashedLine(ORIGIN, ORIGIN, stroke_color=ORANGE, stroke_width=2.5)
            self.add(foot_m)

        def updater(_m):
            px = tracker.get_value()
            py = y_fixed
            pts = tangent_points(px, py, c[0], c[1], r)
            if not pts:
                return
            b = pts[0 if branch == "ccw" else 1]
            cur = {mover: (px, py), tname: b}
            p_dot.move_to(self._m2s(px, py))
            if b_dot is not None:
                b_dot.move_to(self._m2s(*b))
            for lab_name in step.get("labels", []):
                if lab_name in self.labels:
                    dot = p_dot if lab_name == mover else b_dot
                    off = self.labels.get("_off_" + lab_name, (0.28, 0.28))
                    self.labels[lab_name].move_to(dot.get_center()
                                                  + RIGHT * off[0] + UP * off[1])
            for m, a_ref, b_ref in segs:
                pa = cur.get(a_ref) or self._resolve_xy(a_ref)
                pb = cur.get(b_ref) or self._resolve_xy(b_ref)
                m.become(Line(self._m2s(*pa), self._m2s(*pb),
                              stroke_color=m.stroke_color, stroke_width=m.stroke_width))
            for m, v_ref, a_ref, b_ref, s in arcs:
                pv = cur.get(v_ref) or self._resolve_xy(v_ref)
                pa = cur.get(a_ref) or self._resolve_xy(a_ref)
                pb = cur.get(b_ref) or self._resolve_xy(b_ref)
                m.become(self._make_angle(s, pv, pa, pb))
            if foot_m is not None:
                fa = self._resolve_xy(foot_from)
                foot_m.become(DashedLine(self._m2s(*fa), self._m2s(px, y_fixed),
                                         stroke_color=ORANGE, stroke_width=2.5))

        p_dot.add_updater(updater)
        self.play(tracker.animate.set_value(float(step["to"])), run_time=run_time, rate_func=linear)
        p_dot.remove_updater(updater)

    # ---------- 动直线演示（过定点旋转，与圆锥曲线交点实时跟随：q-018 型镜头） ----------

    def _trace_line(self, step, run_time):
        """pivot 为定点（数学坐标），conic 为轴对齐椭圆 x²/a²+y²/b²=1（可平移 center）。
        直线与椭圆交点由二次方程闭式求解，逐帧 become——LLM 依然不填任何坐标。"""
        pivot = step["pivot"]
        conic = step["conic"]
        a2, b2 = conic["a"] ** 2, conic["b"] ** 2
        cx, cy = conic.get("center", [0, 0])
        tracker = ValueTracker(math.radians(step["from_deg"]))
        ext = step.get("extend", 1.8)
        l_name = step["line"]
        if l_name not in self.mobs:
            self.mobs[l_name] = Line(ORIGIN, RIGHT, stroke_color=BLUE, stroke_width=3)
            self.add(self.mobs[l_name])
        line_m = self.mobs[l_name]
        dyn = {}
        for key, name in step.get("points", {}).items():
            if name not in self.mobs:
                self.mobs[name] = Dot(ORIGIN, radius=0.06, color=ORANGE)
                self.add(self.mobs[name])
            dyn[name] = None
        labs = {}  # labels: {点名: 标签文字}，点名为 points 里的对象名
        for pname, text in step.get("labels", {}).items():
            lab = Text(text, font="Times New Roman", font_size=28, color=INK)
            self.add(lab)
            labs[pname] = lab
        links = []
        for pair in step.get("links", []):
            s = self.sb["objects"][pair]
            links.append((self.mobs[pair], s["from"], s["to"]))

        def updater(_m):
            th = tracker.get_value()
            dx, dy = math.cos(th), math.sin(th)
            ox, oy = pivot[0] - cx, pivot[1] - cy
            A = dx * dx / a2 + dy * dy / b2
            B = 2 * (ox * dx / a2 + oy * dy / b2)
            Cq = ox * ox / a2 + oy * oy / b2 - 1
            disc = B * B - 4 * A * Cq
            if disc < 0:
                return
            sq = math.sqrt(disc)
            s1 = (-B - sq) / (2 * A)
            s2 = (-B + sq) / (2 * A)
            p1 = (pivot[0] + s1 * dx, pivot[1] + s1 * dy)
            p2 = (pivot[0] + s2 * dx, pivot[1] + s2 * dy)
            names = list(step.get("points", {}).values())
            dyn[names[0]] = p1
            dyn[names[1]] = p2
            line_m.become(Line(self._m2s(pivot[0] - ext * dx, pivot[1] - ext * dy),
                               self._m2s(pivot[0] + ext * dx, pivot[1] + ext * dy),
                               stroke_color=line_m.stroke_color, stroke_width=line_m.stroke_width))
            for key, name in step.get("points", {}).items():
                self.mobs[name].move_to(self._m2s(*dyn[name]))
            for pname, lab in labs.items():
                if dyn.get(pname):
                    off = step.get("label_offsets", {}).get(pname, [0.3, 0.3])
                    lab.move_to(self._m2s(*dyn[pname]) + RIGHT * off[0] + UP * off[1])
            for m, a_ref, b_ref in links:
                pa = dyn.get(a_ref) or self._resolve_xy(a_ref)
                pb = dyn.get(b_ref) or self._resolve_xy(b_ref)
                m.become(Line(self._m2s(*pa), self._m2s(*pb),
                              stroke_color=m.stroke_color, stroke_width=m.stroke_width))

        line_m.add_updater(updater)
        self.play(tracker.animate.set_value(math.radians(step["to_deg"])),
                  run_time=run_time, rate_func=linear)
        line_m.remove_updater(updater)

    # ---------- 主流程 ----------

    def construct(self):
        self.camera.background_color = PAPER
        self._layout()
        self._build_axes()
        for idx, ch in enumerate(self.sb["chapters"], 1):
            self.next_section(ch["id"])
            steps = ch["steps"]
            target = self.timing.get(ch["id"])
            bases = [s.get("run_time", BASE_TIME.get(s["op"], 1.0)) for s in steps]
            banner_t = 2.4
            if target:
                total = sum(bases)
                scale = max(0.4, min(2.2, (target - banner_t) / total)) if total > 0 else 1
            else:
                scale = 1.0
            self._banner(idx, ch)
            for s, b in zip(steps, bases):
                self._run_op(s, b * scale)
            if target:
                rest = target - banner_t - sum(b * scale for b in bases)
                if rest > 0.05:
                    self.wait(rest)
            # 章节间清字幕，图形保留（板书累积）
            if self.caption_mob:
                self.play(FadeOut(self.caption_mob), run_time=0.3)
                self.caption_mob = None

    def _run_op(self, step, rt):
        op = step["op"]
        if op == "draw":
            m = self._create(step["ref"])
            self.play(Create(m), run_time=max(0.4, rt))
        elif op == "show":
            m = self._create(step["ref"])
            self.play(FadeIn(m), run_time=max(0.3, rt))
        elif op == "label":
            name = step["ref"]
            xy = step.get("at") or None
            text = step.get("text")
            if text is None:
                spec = self.sb["objects"].get(name, {})
                text = spec.get("label")
                if not text:
                    return
            if xy:
                pos = self._m2s(*xy)   # 显式 at 优先（如角标注 α 不贴点）
            else:
                if name not in self.mobs:
                    # 标注引用了尚未出现的点（如圆心）：直接落点，不占动画时长
                    self.add(self._create(name))
                pos = self.mobs[name].get_center()
            off = step.get("offset", [0.28, 0.28])
            if text.startswith("$") and text.endswith("$"):
                try:
                    lab = MathTex(text.strip("$"), color=str(INK))
                except Exception:
                    lab = Text(text.strip("$"), font=FONT, font_size=26, color=INK)
            else:
                lab = Text(text, font=FONT, font_size=26, color=INK)
            lab.move_to(pos + RIGHT * off[0] + UP * off[1])
            self.labels[name] = lab
            self.labels["_off_" + name] = tuple(off)
            self.play(FadeIn(lab, shift=UP * 0.08), run_time=max(0.3, rt))
        elif op == "caption":
            self._caption(step)
        elif op in ("note", "formula"):
            if op == "note" and step.get("kind") != "formula":
                self._note(step, rt)
            else:
                self._note({"kind": "formula", "text": step.get("latex", step.get("text", ""))}, rt)
        elif op == "highlight":
            refs = step.get("refs", [step["ref"]] if "ref" in step else [])
            color = STYLE_COLORS.get(step.get("color", "hl"), ORANGE)
            mobjs = [self.mobs[r] for r in refs if r in self.mobs]
            if mobjs:
                self.play(Indicate(VGroup(*mobjs), color=color), run_time=max(0.6, rt))
        elif op == "dim":
            for r in step.get("refs", [step.get("ref")]):
                if r in self.mobs:
                    self.play(self.mobs[r].animate.set_opacity(0.22), run_time=0.4)
        elif op == "hide":
            for r in step.get("refs", [step.get("ref")]):
                if r in self.mobs:
                    self.play(FadeOut(self.mobs[r]), run_time=0.4)
        elif op == "answer":
            self._answer(step)
        elif op == "wait":
            self.wait(max(0.1, rt))
        elif op == "trace_move":
            self._trace_move(step, max(1.0, rt))
        elif op == "trace_line":
            self._trace_line(step, max(1.0, rt))
        else:
            raise ValueError(f"未知 op: {op}")


def _unicode_math(s: str) -> str:
    """MathTex 不可用时的降级：LaTeX 子集 → Unicode。"""
    rep = {"\\sqrt": "√", "^{2}": "²", "^2": "²", "^{3}": "³", "_{1}": "₁", "_{2}": "₂",
           "\\frac{1}{2}": "½", "\\pi": "π", "\\angle": "∠", "\\triangle": "△",
           "\\le": "≤", "\\ge": "≥", "\\ne": "≠", "\\cdot": "·", "\\times": "×",
           "\\alpha": "α", "\\beta": "β", "\\theta": "θ", "\\lambda": "λ",
           "\\left": "", "\\right": "", "\\": "", "{": "", "}": ""}
    for k, v in rep.items():
        s = s.replace(k, v)
    import re
    s = re.sub(r"\\sqrt\s*", "√", s)
    s = re.sub(r"\\d?frac([^ ]+)([^ ]+)", r"(\1)/(\2)", s)
    return s


def make_lesson_scene(storyboard_path: str, timing_path: str | None = None):
    """生成绑定分镜 JSON 的 Scene 子类（manim CLI 入口约定：类名 Lesson）。"""
    with open(storyboard_path, "r", encoding="utf-8") as f:
        sb = json.load(f)
    timing = {}
    if timing_path and os.path.exists(timing_path):
        with open(timing_path, "r", encoding="utf-8") as f:
            timing = json.load(f)

    class Lesson(LessonScene):
        def __init__(self, **kw):
            super().__init__(sb, timing, **kw)

    return Lesson
