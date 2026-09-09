# 分镜剧本 schema（lesson-plan/v1）

一题一课动画的唯一输入。AI（Terra）只允许产出符合本 schema 的 JSON；
编译器（lesson_runtime.py）负责全部坐标计算与代码生成。封闭 op 枚举，禁止发明新 op。

## 顶层

```json
{
  "schema": "lesson-plan/v1",
  "id": "小写连字符英文标识",
  "title": "一题一课 · XXX",
  "problem": {
    "stem": "完整题干（中文，数学符号用 Unicode）",
    "known": ["已知条件列表"],
    "goal": "解题目标一句话",
    "core_observation": "核心观察一句话",
    "answer": "最终答案"
  },
  "view": {"x": [-7.5, 3.5], "y": [-1.5, 8.5], "axes": true, "ticks": {"x": [-4], "y": [5]}},
  "objects": { "名字": 对象定义 },
  "chapters": [ 章节 ]
}
```

- `view.x/y`：数学坐标可视范围。图形会等比缩放放入左侧画布区，**保证图形不越界、不被横幅遮挡**（画布区高度对应 y 范围留 15% 余量）。
- `ticks`：需要在坐标轴上标注数值的刻度，只标讲解用到的数。

## 对象（objects）——只声明几何约束，不填派生坐标

| type | 字段 | 说明 |
|---|---|---|
| point | at:[x,y], label?, style? | 已知点。style: main/aux/hl/ans |
| circle | center:点名或[x,y], radius:数 | 圆 |
| segment | from, to, style?, dashed? | 线段（端点可为点名或坐标） |
| line | from, to, style?, extend? | 直线（extend 向两端延长比例，默认 0.15） |
| ray | from, to | 射线 |
| angle | at:顶点, from, to, style?, radius?, size? | 角标记：默认弧（radius 场景单位）；style="square" 为直角小方块（size 数学单位，默认 0.35） |
| function | expr:"sympy表达式(x)", x:[a,b] | 函数曲线，如 "3*sqrt(1-x**2/12)" |
| tangent_point | from:外点, circle:圆名, branch:"ccw"/"cw" | 切点，编译器符号求解 |
| foot | from:点, line:[点A,点B] | 垂足 |
| mid | of:[点A,点B] | 中点 |

派生点（切点/垂足/中点）必须用约束类型声明，由 Python 求解，**禁止手填算出来的坐标**。

## 章节（chapters）与步骤（steps）

```json
{"id": "ch1", "title": "辅助线构造", "subtitle": "遇切线，连半径",
 "narration": "本章配音全文（2~4 句，口语化，中文）",
 "steps": [ 步骤... ]}
```

- `narration` 决定本章时长（TTS 实测 + 0.8s 余量），步骤按 run_time 比例缩放。
- 每章 3~8 步；步骤 op 封闭枚举：

| op | 参数 | 默认时长 |
|---|---|---|
| draw | ref | 1.3（逐笔绘制，主力动画） |
| show | ref | 0.7（淡入） |
| label | ref, text?, at?, offset?[dx,dy 场景单位] | 0.7（点旁标注；text 缺省用对象 label） |
| caption | text | 0.35（底部字幕，一章内可多次更换；支持 $..$ 混排） |
| note | text 或 kind:"formula"+text(latex) | 1.1（右侧板书逐行累积；text 支持 $..$ 混排） |
| highlight | refs:[名...], color?:main/aux/hl | 1.3 |
| dim / hide | refs | 0.6 |
| trace_move | mover, circle, tangent_point?, branch, from, to, links:[线段名], arcs?, labels?, foot_line?, run_time | 3.2（动点沿水平线移动，切线/角/标签跟随） |
| trace_line | pivot:[x,y], conic:{a,b,center?}, from_deg, to_deg, line:名, points:{P:点名,Q:点名}, labels:{点名:文字}, label_offsets?, links:[线段名], extend, run_time | 3.2（过定点动直线旋转，与椭圆交点跟随） |
| answer | text(latex) | 1.6（绿框红字结论卡） |
| wait | — | 0.5 |

## 教学纪律（AI 必须遵守）

1. 章节顺序 = 解题认知顺序：读题 → 破题（构造）→ 转化 → 计算 → 结论；标题 2~6 字，副标题一句话。
2. 辅助线一律 style:"aux"（绿色），关键变量 highlight 用橙色，结论 answer。
3. 中文文本禁止生僻 Unicode 符号（雅黑缺字）：不用 ⟺ ⇒（用文字"当且仅当"）。
4. **公式一律真排版，禁止 Unicode 伪公式**（"√15/4""x²+y²"这类纯文本不合格）：
   - 独立公式行：kind:"formula" + LaTeX；
   - 中文夹公式（note/caption/label）：用 `$...$` 包裹 LaTeX 片段，运行时自动混排
     （如 "半角：$\\sin\\angle APC=\\frac{\\sqrt{10}}{4}$"）；
   - 点标签带坐标写成 `$A(-4,5)$`；LaTeX 反斜杠在 JSON 里必须双写（`\\frac`）。
5. 动点/动直线镜头只在"最值/恒成立/轨迹"类题使用，且 from/to 必须保证全程几何构型不退化（动点不进入圆内、判别式非负）。
6. 每章 caption 至少 1 条，与 narration 同义但更短（≤18 字）。
