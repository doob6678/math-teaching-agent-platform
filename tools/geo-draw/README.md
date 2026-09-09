# geo-draw：几何题自动配图与高清导出

2026-09-05 建立。回答老板的问题：有没有"自动画几何图并导出高清图"的智能体，
能不能自己实现。结论：**能，且本机依赖已齐，无需新装任何东西**。
`demo_rhombus.tex` 就是示例题（菱形 ABCD、∠ABC=110°、AE=EF、∠AEF=110°）的复现，
`render.py` 一条命令出图。

## 一、GitHub 调研结论（2026-09-05 实时核实 star 数）

| 项目 | 星数 | 是什么 | 可用性判断 |
|---|---|---|---|
| potamides/DeTikZify | 1818 | 手绘草图/科学图 → TikZ 程序 | 偏科研示意图，非初中几何 |
| lupantech/Inter-GPS | 178 | ACL 2021，几何题形式语言求解 | 求解器，画图是附带 |
| PatrikBak/GeoGen | 110 | 自动生成平面几何**题目**（逆向） | 出题不是配图 |
| zhaoyu-li/PyEuclid | 15 | CAV 2025 平面几何约束系统 | 可作坐标求解后端 |
| MIT geometrydraw（NeurIPS 2024 神经符号几何配图） | — | 英文受限语法 → SVG | **原仓库已 404**，PyPI 不通，路线断了 |
| taohuahuhuha/-TikZ-Agent | 1 | 初中几何题 → 推理 → TikZ → PDF 编译验证闭环 | 场景完全一致，证明该路线已被别人跑通 |

行业现状：**没有一个拿来即用的"中文几何题 → 标准配图"开源智能体**。
MIT 那条神经符号路线（规则建图器）仓库已下架；活下来的主流路线就是
**LLM 写 TikZ/DSL + 编译器校验闭环**（TikZ-Agent 论文 2025 即此思路）。
所以我们自己实现是合理的，且正好复用本项目已有的 LaTeX 修复循环模式。

## 一点五、智能体已完整实现并真实跑通（2026-09-05）

`geo_draw_agent.py` 是完整闭环：题目文本 → Terra(gpt-5.6-terra, 网关
`OPENAI_BASE_URL`) 生成 TikZ → xelatex 编译（失败回喂日志自修复）→
同一视觉模型对照题干审图（输出 pass/issues，不过关回喂重修）→
中文题干与矢量配图合成一页 → 600dpi 栅格化。

实测运行（`agent_run.log`）：第一轮生成即编译通过，VLM 审阅
`{'pass': True, 'issues': []}`，产物 `final.png` 7705×3275px。
运行方式：`python geo_draw_agent.py problem.txt --out final.png --dpi 600`。
注意 Terra 单次调用约 8 分钟，整轮（生成+审阅）约 16 分钟，属网关延迟非管线问题。

真实踩到并修掉的三个坑（都写进了代码注释）：
1. 中文字体缺 ∠ 等数学符号字形，题干必须过 `sanitize_stem` 换成 LaTeX 命令，否则静默丢字；
2. `compile_tex` 只查 PDF 存在不查新鲜度，编译失败会静默复用旧图——已改为编译前删旧产物；
3. xelatex 工作目录在 build 子目录，插图路径必须绝对。

## 二、实现架构（已在本目录跑通）

```
题目文本 ──(LLM，DeepSeek/GLM 均可)──▶ TikZ 源码（坐标由约束推导，禁止手填像素）
                                          │
                            xelatex 编译（失败 → 日志尾部回喂 LLM 修复，
                                          │    同讲义 LaTeX 修复循环，≤3 轮）
                                       矢量 PDF
                                          │
                            PyMuPDF 按任意 DPI 栅格化 ──▶ 高清 PNG（600dpi 实测 2214×1454）
```

- 几何正确性靠**约束建点**：如示例中 F 点由"A 绕 E 顺时针转 110°"的
  `\pgfmathsetmacro` 公式推出，不是目测坐标。AI 只需写对构造逻辑。
- 高清度靠**矢量中转**：TikZ→PDF 全程矢量，最后一步才栅格化，DPI 随便给。
- TikZ 的 `angle` pic 从第一边逆时针扫到第二边，顶点顺序写反会画出优角——
  这是 AI 最常犯的错，编译不报错、只有视觉检查能拦住，接入时必须配 VLM 审阅
  （本项目已有 judge 环节可挂）。

## 三、依赖清单（本机全部已装）

| 依赖 | 用途 | 本机状态 |
|---|---|---|
| MiKTeX xelatex + tikz(pgf) | TikZ 编译出矢量 PDF | ✓ MiKTeX 25.12 |
| PyMuPDF (fitz) | PDF → 任意 DPI PNG | ✓ 1.27 |
| （备选）matplotlib + sympy | 纯 Python 路线，无 LaTeX 时用 | ✓ 3.10 |

Linux/容器侧对应物：`texlive-latex-extra`（含 tikz）+ `pip install pymupdf`。

## 四、用法

```bash
python render.py demo_rhombus.tex --dpi 600 --out figure.png
```

编译失败时退出码非 0 并打印 xelatex 日志尾部，可直接作为修复循环的回喂内容。

## 五、接入讲义链路的建议（未实施，待老板拍板）

1. AI Writer 在讲义正文输出 ```` ```tikz ```` 围栏块（与现有 LaTeX 公式同层），
   仍由 AI 独占教学语义，符合讲义架构边界。
2. Java 侧不解析语义，只把围栏块交给 worker 的 `render.py` 同款管线，
   产物按现有资产流程入库（assetId/evidenceRef），渲染器不选图不改图。
3. 失败回喂走已验证的 LaTeX 修复循环模式；VLM 视觉审阅拦"编译成功但画错"。
4. 备选纯 Python 路线（sympy 解约束 + matplotlib 出图）适合不想在容器里
   装 TeX 的场景，代价是标注美观度不如 TikZ。
