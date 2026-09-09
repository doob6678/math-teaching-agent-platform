# -*- coding: utf-8 -*-
"""把 4 份分镜的 problem 字段升级为 LaTeX 源文本（$...$ 包裹公式）。
同一份数据既供视频（分镜步骤），也供 demo/前端结构化解析卡片（KaTeX 渲染），单一事实源。
幂等：已含 $ 的字段跳过。"""
import json

NEW = {
    "tangent-min": {
        "stem": "如图，在直角坐标系中，点 $A$ 的坐标为 $(-4,5)$，$\\odot A$ 的半径为 $3$，$P$ 为 $x$ 轴上一动点，$PB$ 切 $\\odot A$ 于点 $B$，则 $PB$ 的最小值是____。",
        "known": ["圆心 $A(-4,5)$，半径 $3$", "点 $P$ 在 $x$ 轴上运动", "$PB$ 是 $\\odot A$ 的切线，$B$ 为切点"],
        "goal": "求切线段 $PB$ 的最小值",
        "core_observation": "$PB$ 是切线，连接半径 $AB$ 可构造直角三角形；$PB$ 随 $P$ 位置变化，且与 $AP$ 满足勾股关系 $PB=\\sqrt{AP^2-AB^2}$。",
        "answer": "$4$",
    },
    "two-circle-tangents": {
        "stem": "写出与圆 $x^2+y^2=1$ 和 $(x-3)^2+(y-4)^2=16$ 都相切的一条直线的方程。",
        "known": ["圆 $O$：圆心 $(0,0)$，半径 $1$", "圆 $O_1$：圆心 $(3,4)$，半径 $4$", "求两圆的公切线"],
        "goal": "写出一条与两圆都相切的直线方程",
        "core_observation": "圆心距 $d=\\sqrt{3^2+4^2}=5$ 恰等于半径之和，两圆外切，公切线共三条，分内公切线与外公切线讨论。",
        "answer": "$y=-\\frac{3}{4}x+\\frac{5}{4}$（或 $y=\\frac{7}{24}x-\\frac{25}{24}$，或 $x=-1$）",
    },
    "tangent-angle": {
        "stem": "过点 $(0,-2)$ 与圆 $x^2+y^2-4x-1=0$ 相切的两条直线的夹角为 $\\alpha$，则 $\\sin\\alpha=$（  ）A. $1$  B. $\\frac{\\sqrt{15}}{4}$  C. $\\frac{\\sqrt{10}}{4}$  D. $\\frac{\\sqrt{6}}{4}$",
        "known": ["圆 $x^2+y^2-4x-1=0$，即 $(x-2)^2+y^2=5$", "外点 $P(0,-2)$", "两切线夹角为 $\\alpha$"],
        "goal": "求 $\\sin\\alpha$",
        "core_observation": "连半径构造直角三角形：切线长 $\\sqrt{3}$，半角正弦余弦易求，倍角公式合成 $\\sin\\angle APB$；$\\angle APB$ 为钝角，$\\alpha$ 与其互补、正弦相同。",
        "answer": "B（$\\frac{\\sqrt{15}}{4}$）",
    },
    "ellipse-moving-line": {
        "stem": "已知椭圆 $C:\\frac{x^2}{a^2}+\\frac{y^2}{b^2}=1$ $(a>b>0)$ 的离心率为 $\\frac{1}{2}$，左顶点为 $A$，下顶点为 $B$，$C$ 是 $OB$ 的中点，$\\triangle ABC$ 的面积为 $\\frac{3\\sqrt{3}}{2}$。(2) 过点 $(0,-\\frac{3}{2})$ 的直线与椭圆交于 $P$、$Q$，$y$ 轴上是否存在定点 $T$，使 $\\overrightarrow{TP}\\cdot\\overrightarrow{TQ}\\le 0$ 恒成立？",
        "known": ["离心率 $e=\\frac{1}{2}$", "$C$ 是 $OB$ 中点，$S_{\\triangle ABC}=\\frac{3\\sqrt{3}}{2}$", "动直线过定点 $(0,-\\frac{3}{2})$"],
        "goal": "求椭圆方程，并判断 $y$ 轴上满足 $\\overrightarrow{TP}\\cdot\\overrightarrow{TQ}\\le 0$ 恒成立的 $T$ 的范围",
        "core_observation": "$e=\\frac{1}{2}$ 定形状（$a=2c$，$b=\\sqrt{3}c$），面积定大小（$\\frac{ab}{4}=\\frac{3\\sqrt{3}}{2}$）；$\\overrightarrow{TP}\\cdot\\overrightarrow{TQ}\\le 0$ 即 $\\angle PTQ$ 不小于直角，$T$ 在以 $PQ$ 为直径的圆内。",
        "answer": "(1) $\\frac{x^2}{12}+\\frac{y^2}{9}=1$；(2) 存在，$t\\in[-3,\\frac{3}{2}]$",
    },
}

for lid, prob in NEW.items():
    path = f"lessons/{lid}.json"
    sb = json.load(open(path, encoding="utf-8"))
    if "$" in sb["problem"]["stem"]:
        print(lid, "skip")
        continue
    sb["problem"] = prob
    json.dump(sb, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(lid, "upgraded")
