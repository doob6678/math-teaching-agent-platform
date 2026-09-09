# 「一题一课」讲解视频技术路线外部调研报告

日期：2026-09-08（所有 URL 与数据均为当日实测/实抓，验证日期同）
调研人备注：本机网络对 arxiv.org / export.arxiv.org / huggingface.co / duckduckgo 均被阻断（ECONNRESET），论文事实改经 papers.cool（arXiv 镜像，实测可用）、alphaxiv.org（全文可读）、GitHub API 与 Semantic Scholar 交叉验证；Manim 关键能力在本机 Windows + Git Bash 真实渲染验证，非文档转述。

---

## 一、结论先行

**推荐主路线：Manim Community Edition（v0.21.0）+ "结构化分镜 JSON → 确定性代码编译器（模板化几何 op）→ 渲染 → VLM 审阅 → 局部修复重试" 的受控生成管线**，配音用自托管 CosyVoice3（GPU）逐句合成、以音频时长驱动章节时长，章节用 `Scene.next_section()` + `--save_sections` 产出分段 mp4 + JSON 清单，再用 ffmpeg `;FFMETADATA1` 封装 mp4 章节标记，同时把章节时间戳以 JSON 下发给 React 播放器做导航（不要依赖 HTML5 video 的原生章节 UI）。理由：(1) 本机实测 pip 安装 + 中文 Text（微软雅黑）+ 1080p30 渲染 + 分段 + 章节封装全链路可行，12 秒简单场景仅 19 秒渲染完成；(2) 竞品效果（辅助线逐条画出、点逐个标注、颜色高亮、中文字幕、章节导航）全部是 Manim 的原生能力；(3) 2025-2026 学术与开源生态（TheoremExplainAgent、Code2Video、SGA、ManiBench、ManimCat、MathLens）已完整验证该路线并给出可借鉴的失败模式与修复策略；我们已有 Terra/Luna VLM 审阅 + xelatex 修复经验，与 Manim 修复循环同构。

**备选路线**：
- **Web SVG 实时动画**（manim-web 0.3.24 / anime.js v4 / GSAP 免费 + KaTeX）：适合"可交互重播、免渲染队列、即时生成"的场景（学生点"重看这一步"、拖动步骤条），生成成本≈0、失败面小，但审美上限低于 Manim（无摄像机/3D/平滑形变体系），且 LLM 生成 SVG 代码的幻觉问题与 Manim 同样存在。建议作为二期"交互复习模式"，不替代一期视频交付。
- **GeoGebra 路线不建议用于自动视频生产**：官方 Apps API 只有 `evalCommand`/`exportSVG`，无任何程序化视频导出（2026-09-08 实测手册全文检索无 Video 接口）；Web 应用闭源，无 headless CLI，GitHub 上无成熟的 ggb→video 工具。适合做前端可拖拽交互题板组件，不适合批量渲染管线。

---

## 二、Manim Community Edition 事实清单

来源：https://github.com/ManimCommunity/manim 、https://docs.manim.community/en/stable/ 、https://pypi.org/pypi/manim/json （均 2026-09-08 验证）

| 项 | 事实 | 证据 |
|---|---|---|
| 最新版本 | v0.21.0，发布于 2026-08-10；仓库 40,719★，MIT，pushed 2026-09-08（活跃） | GitHub API releases/latest |
| 发版节奏 | v0.19.1(2025-12)→0.19.2(2026-01)→0.20.0/0.20.1(2026-02)→0.21.0(2026-08) | GitHub API releases |
| Python 要求 | >=3.11（依赖 manimpango>=0.6.1、pycairo>=1.14） | pyproject.toml (raw.githubusercontent) |
| Windows pip 可行性 | **可行（本机实测）**：manim 为纯 Python wheel；manimpango 0.6.1 提供 cp311-cp313 的 win_amd64 预编译 wheel（Pango/Cairo 已打包）；pycairo 1.29.1 提供 win_amd64 wheel；只需系统装 ffmpeg。本机用清华镜像 `pip install manim edge-tts` 成功，`manim render` 正常出片 | pypi.org/pypi/manimpango/json、pypi.org/pypi/pycairo/json + 本机 venv 实测（.research-tmp/mbench） |
| 官方安装途径 | conda（推荐，自动带 ffmpeg/pango）、uv、Docker（docs 有 docker 页；hub.docker.com 本机被墙未能验证 tag）、无 Windows 安装包 | docs.manim.community/en/stable/installation.html |
| 渲染耗时（实测） | 12.0s、1080p30、11 个动画（圆/直线/中文 Text 若干）CPU 渲染 **19 秒**（含 ~5s 解释器启动）≈1.2-1.5× 实时；简单 2D 场景 2-3 分钟视频全量渲染估 3-8 分钟。LaTeX 密集场景会显著变慢（MathTex 走 xelatex 子进程）。manim-generator 项目默认 `--scene-timeout 400s`/场景，可作工程上限参考 | 本机实测 + github.com/makefinks/manim-generator README |
| 中文文字 | **Text 对象（Pango 渲染）指定 `font="Microsoft YaHei"` 在本机 Windows 直接可用，已抽帧目检确认字形正确**；Text 是 VGroup of chars，支持逐字动画、t2c 着色 | 本机实测（.research-tmp/frame_title.png、frame_sub.png）；docs Text 参考页（"Text rendered using Pango"，font/warn_missing_font 参数） |
| 中文的坑 | LaTeX 路线（Tex/MathTex）写中文需 xelatex+ctex，历史 issue：#4052 xelatex 无法显示中文（closed）、#3254 BraceLabel 不支持中文（closed）、#4237 ctex 下 `\div` 渲染错乱（open）→ **策略：中文一律 Text/Pango，公式一律 MathTex（仅 LaTeX 数学），两者混排分层摆放** | github.com/ManimCommunity/manim/issues 检索 2026-09-08 |
| 分段/章节 | `Scene.next_section(name=..., skip_animations=...)` 原生支持；CLI `--save_sections` 实测产出：每章独立 mp4（文件名含中文章节名）+ `bs3.mp4.json` 清单（name/type/video/duration/帧数/编码），**即天然的章节时间戳元数据**；另有 `disable_caching`/`flush_cache`/`max_files_cached` 配置 | 本机实测 + docs ManimConfig 页 |
| 增量渲染 | Manim 按"部分影片文件"缓存（partial_movie_files 实测存在），未变更动画直接复用；v0.19+ 的 section 缓存使"只重渲失败章节"成为可能；不是逐帧增量，而是逐动画/逐段增量 | docs + 本机 media 目录实测 |
| mp4 章节封装 | ffmpeg `;FFMETADATA1` 元数据文件 + `-map_metadata 1 -codec copy` 实测成功，ffprobe 回读 3 个中文 chapter 标题；文档：https://ffmpeg.org/ffmpeg-formats.html（ffmetadata 节） | 本机 ffmpeg 8.1 实测 |
| 官方配音插件 | **ManimCommunity/manim-voiceover**（314★，2026-06 仍更新）：支持 Azure/gTTS/pyttsx3/Gemini/OpenAI TTS 及自定义 CLI 配音，**用 OpenAI Whisper 实现"动画在指定词触发"的词级时间对齐**，旁白时长自动决定动画节奏 | github.com/ManimCommunity/manim-voiceover |

### "LLM 生成 Manim 代码"已知项目（GitHub API 实抓，2026-09-08）

| 仓库 | Stars | 最近 push | 要点 |
|---|---|---|---|
| TIGER-AI-Lab/TheoremExplainAgent | 1498 | 2025-07 | ACL 2025 oral，arXiv 2502.19400；长视频定理讲解 agent，含 Manim 文档 RAG、VLM 看渲染结果修代码、retry_limit |
| Wing900/ManimCat | 452 | 2026-08-08 | 中文 AI 数学动画生成器（MIT+AGPL 双许可）；**静态检查 py_compile+mypy 前置守门 + AI 自动打补丁 ≤3 轮 + 渲染失败错误回灌重生成 + 锚点分段渲染 + 成功率/耗时仪表盘** |
| maloyan/manim-web | 476 | 2026-09-04 | Manim 的 TypeScript 移植（npm 0.3.24，MIT），Text/LaTeX(KaTeX) 支持 |
| makefinks/manim-generator | 117 | 2026-09-04 | Code Writer + Code Reviewer 双模型回路（LiteLLM 路由），review-cycles 默认 5，多帧抽样视觉审查 |
| qnguyen3/STEMViz | 80 | 2025-10 | Manim+LLM+多模态的叙述式教学动画 |
| HyperCluster-Tech/manimator | 74 | 2025-02 | 论文→视觉解释（arXiv 2507.14306），两阶段：场景描述 JSON→代码 |
| shuyicc/MathLens | 353 | 2026-03-10 | **中文题解视频 Agent Skill：题面→分析→HTML/SVG 可视化→分镜脚本→edge-tts 逐幕音频+WordBoundary 同步点→Manim 脚手架→渲染验证失败自动修复**（与我们需求几乎同构） |
| calesthio/OpenMontage | 56670 | 2026-09-06 | 开源 agentic 视频生产系统（AGPL-3.0），12 条流水线含 math_animate(Manim) 工具 |
| hesamsheikh/AnimAI-Trainer | 23 | 2025-03 | 微调 LLM 生成 Manim |
| SuienS/manim-trainer | 21 | 2026-04-21 | ManimTrainer 论文配套：SFT+GRPO 微调与渲染回路推理 |
| Avik-creator/manim-mcp | 16 | 2025-05 | Manim MCP server（自然语言→渲染） |
| KacemMathlouthi/animus | 10 | 2026-09-04 | 交互式 Manim coding agent：render→读 traceback→修复循环；manim-voiceover+ElevenLabs 旁白自动定时 |

---

## 三、学术方案（LLM 自动生成数学教学动画，2024-2026）

注：用户提到的《Towards Large Visual Models for Mathematical Animation》(Ling-Vision/MathAnimate 数据集) **未能在本网络找到**——papers.cool 全量 arXiv 索引搜 "MathAnimate" 0 命中、Semantic Scholar 0 命中、GitHub 无对应仓库；不排除名称有误或未发 arXiv。以下为可验证的同方向论文（arXiv ID 经 papers.cool 镜像与 alphaxiv 全文双重确认）：

1. **TheoremExplainAgent**（arXiv 2502.19400，2025-02-26，ACL 2025 oral）：agent 规划→分镜→Manim 代码→渲染→组合，产出 5-10 分钟长视频；无 agent 分解的方案只能做 ~20 秒短片。失败模式（全文实证）：**"幻觉函数+错误签名"、LaTeX 渲染错误、缺 import 等通用 Python 错误；有 retry 仍脆弱；Manim 文档 RAG 效果参差**。评测集 TheoremExplainBench 240 定理。全文：https://www.alphaxiv.org/abs/2502.19400
2. **Code2Video**（arXiv 2510.01174，2025-10）：教育视频"代码为中心"范式，三 agent 协作：**Planner（讲义结构+视觉资产）/ Coder（可执行 Python + scope-guided auto-fix）/ Critic（VLM + 视觉锚点提示修空间布局与可读性）**——即"脚本→动画程序→渲染→审阅修复"完整闭环，是 SGA 的底座管线。
3. **SGA: Plug&Play Geometric Verification**（arXiv 2607.18116，2026-07-20，KAUST）：拦截 LLM 生成代码，**部分执行提取符号场景图，检测几何遮挡/越界后定向修复**；提出免渲染的确定性质量分 MVQS；在 4 个 LLM×2 管线上 8 组配置中 7 组提升 MVQS（GPT-5.1+Code2Video 达 73.11，相对 +16.1%）。→ 可借鉴：**几何正确性别只靠 VLM 看图，用符号校验器兜底**。
4. **ManiBench**（arXiv 2603.13251，2026-02-24）：Manim CE 代码生成基准（150-200 题，5 级难度，基于 3b1b ManimGL 5.3 万行源码分析），定义两类失败：**Syntactic Hallucinations（合法 Python 但调用不存在/废弃的 Manim API）与 Visual-Logic Drift（时序错误/因果缺失导致画面偏离数学逻辑）**；四维评测 Executability/Version-Conflict/Alignment/Coverage。代码：https://github.com/nabin2004/ManiBench
5. **ManimTrainer + ManimAgent(RITL)**（arXiv 2604.18364，2026-04-20）：SFT+GRPO（融合代码+视觉双信号奖励）训练，推理侧 **Renderer-in-the-loop**；17 个 <30B 开源模型×9 种策略：**最佳组合 Qwen3-Coder-30B + GRPO + 文档增强 RITL 达 94% 渲染成功率（RSR）**、视觉质量 85.7%。→ 事实：**即便最优回路仍有 ~6% 渲染失败率，生产必须留修复重试与降级路径**。
6. **ManimAgent 自进化记忆**（arXiv 2606.30296，2026-06-29）：每段动画收敛后用 VLM 给关键帧打分，经验沉淀为双通道情景记忆（M+ 成功案例作软参考 / M- 已验证失败模式作硬禁忌），跨任务复用，**免权重更新**，人类盲测 Pass@1 上升、反思轮数下降。→ 可直接抄的轻量运营机制：失败案例库+成功模板库。
7. **LLM2Manim**（arXiv 2604.05266，2026-04-07）：人在回路管线，**约束提示模板 + symbol ledger（符号账本保证全片变量一致）+ 只重生成出错部分 + 专家审后终渲**；100 名本科生 A/B：动画组后测 83% vs PPT 组 78%（p<.001），参与度 d=0.94。→ 教学效果背书 + 工程纪律。
8. **See Before You Code**（arXiv 2605.15585，2026-05-15）：先生成"视觉先验"（布局草图）再写动画代码，提升空间正确率。
9. 其他：Manimator（2507.14306，论文→动画）、Manim for STEM Education（2510.01187）、ALGOGEN（2605.12159，可验证算法可视化轨迹）、PhysicsSolutionAgent（2601.13453，物理题多模态讲解）。
10. SVG/矢量动画侧（Web 路线相关）：Reason-SVG（2505.24499）、VAnim（2605.01517，渲染感知稀疏状态建模）、LottieGPT（2604.11792）、VectorGym（2603.29852）、SVG-Score（2609.03806）、Decomate（2511.06297，人机协作 SVG 动画）、LogoMotion（2405.07065，视觉接地动画代码合成）。

**共识 pipeline 结构**：题目/讲义文本 → 分镜 JSON（章节+旁白+视觉步骤）→ 代码生成（模板编译或 LLM 直写）→ 静态检查（py_compile/mypy）→ 渲染（失败则 traceback 回灌修复，≤3 轮）→ VLM 关键帧审阅 + 符号几何校验（遮挡/越界/文字重叠）→ 局部重渲 → 配音对齐 → 章节封装。**可借鉴的失败模式清单**：API 幻觉（版本漂移最重）、LaTeX/CJK 编译失败、元素重叠遮挡、画面-逻辑漂移（时序/因果）、变量符号前后不一致、长视频必须分章节规划否则崩。

---

## 四、GeoGebra 路线

来源：https://geogebra.github.io/docs/manual/en/ 与 https://geogebra.github.io/docs/reference/en/GeoGebra_Apps_API/ （2026-09-08 全文抓取验证）

- 脚本体系：GGBScript 命令 + JavaScript 双轨（manual/Scripting/）；动画控制命令 `StartAnimation(<点/滑块>...)`（点必须在路径上）、`SetAnimationSpeed`、`SetConstructionStep`（逐步显示）等**只控制应用内动画，无导出接口**。
- Apps API：`evalCommand()`（等价输入栏）、`exportSVG()`（当前 Graphics View 导出 SVG 字符串/下载）、`setPerspective` 等；**全文检索无 Video/视频导出函数**；`StartRecord` 命令是"记录到表格"而非录像。
- 无官方 headless/CLI；GitHub 当日检索 "geogebra video export"、"ggb to video" 均无成熟工具；官方镜像仓库 geogebra/geogebra（2326★）无 license 字段（Web 应用闭源），自动化渲染不可控。
- 结论：**GeoGebra 适合做 React 内嵌的"可交互题目探究组件"（学生自己拖点看性质），不适合做我们的批量分步动画视频管线**；若走浏览器内自动化（Playwright 驱动 applet 逐帧 exportSVG 拼视频），工程量与稳定性全面劣于 Manim 源码路线。审美上 GGB 默认样式偏"教材插图"，定制自由度低于 Manim 的 mobject 体系；中文两者都无障碍（GGB 走浏览器字体，Manim 走 Pango）。

---

## 五、Web 端 SVG/JS 分步动画路线（不渲染视频）

| 组件 | 事实（2026-09-08） |
|---|---|
| manim-web | MIT，npm 0.3.24，476★，2026-09-04 活跃；3b1b Manim 语义的 TS 移植（Scene/Create/MathTex via KaTeX），浏览器直跑 |
| anime.js | v4.5.0，MIT，72.7k★（npm registry + GitHub API） |
| GSAP | 28.3k★；Webflow 收购后**全库含 SplitText/MorphSVG 等插件 100% 免费（含商用）**，标准"no charge"许可 https://gsap.com/standard-license |
| D3 | 113.7k★，ISC |

评估（对比竞品视频方案）：
- **交互性**：全面占优——步骤条拖动、单步重播、点击高亮回溯、深浅色/字号跟随系统、无障碍（读屏）都可行；视频是死文件。
- **生成成本**：无渲染环节（省 3-8 分钟/条 + 服务器 CPU），交付即 JSON 分镜→前端解释执行；**前提是分镜 schema 足够封闭（op 枚举 + 参数），前端做确定性播放器而非执行 LLM 生成的任意 JS**。
- **失败率**：播放器代码固定后，运行时失败面收敛到"几何参数非法"（可前端校验）；若让 LLM 直写 SVG/D3 代码，则幻觉问题与 Manim 相同（ManiBench/VectorGym 均证实），且无渲染期报错兜底。
- **移动端兼容**：SVG+CSS/JS 动画 iOS Safari/Android Chrome 均成熟；中文字体交给系统（无 Manim 字体分发问题）；视频方案则要做播放器章节 UI。
- **短板**：复杂形变（Transform 平滑插值）、摄像机推拉、粒子等表现力弱于 Manim；离线分享/投屏场景仍需导出视频。
- **定位建议**：一期用 Manim 视频对齐竞品并超越（配音+章节），二期把同一份分镜 JSON 同时喂给 manim-web/anime.js 播放器做"交互版"，一份内容两种交付。

---

## 六、商用/开源同类产品（题解视频自动生成）

- **shuyicc/MathLens（353★，中文）**：最接近我们需求的开源实现——数学题（图/文）→ 8 步全流程 → 带配音 Manim 视频；edge-tts（默认晓晓音色）逐幕 wav + **WordBoundary 事件精确计算每句起始秒**；分镜"幕"结构含画面/字幕/读白；明确工程纪律："不要写硬编码秒数（TTS 前无法预知时长）"、渲染验证失败自动修复。https://github.com/shuyicc/MathLens
- **Wing900/ManimCat（452★，中英双语）**：描述即视频，静态检查+AI 补丁+失败回灌+分段渲染+背景音乐混音+成功率仪表盘。https://github.com/Wing900/ManimCat
- **TheoremExplainAgent（1498★）**：学术侧最完整的"定理→长视频"agent。
- **OpenMontage（56.7k★，AGPL）**：agentic 视频生产全家桶（含 math_animate），可参考其工具边界设计，**AGPL 注意隔离**（只当外部工具链参考，不引入代码）。
- **animus（10★，MIT）**：对话式 Manim 编码 agent，render-diagnose-repair 循环 + 旁白自动定时。
- 未发现成熟的中文 K12"题解视频自动生成"开源商用级项目（GitHub 中文检索 "题解 视频/讲题 AI/数学 动画 生成" 当日结果如上），竞品大概率自研闭源——**窗口期仍在**。

---

## 七、中文开源 TTS（轻量调研）

| 方案 | 事实（2026-09-08 实抓） | 成本/对齐 |
|---|---|---|
| **CosyVoice（QwenAudio/CosyVoice，原 FunAudioLLM 已改名迁移）** | 23,516★，Apache-2.0，pushed 2026-05-25；**Fun-CosyVoice 3.0（0.5B，2025-12 版模型）**，论文 arXiv 2505.17589；9 语言+18 中文方言，零样本音色克隆（跨语言），双向流式最低 150ms 延迟；自带 **FastAPI/gRPC server** 与 vLLM 支持，官方 Docker(nvidia runtime) | 0.5B 模型，我们现有 GPU 完全够；对齐：逐句合成 + ffprobe 测时长（或 ttsfrd/whisper 强制对齐） |
| **GPT-SoVITS** | 61,674★，MIT，pushed 2026-08-18；zero-shot 5 秒样本 / few-shot 1 分钟语料微调；中英日韩+粤语；WebUI+Docker；最新 release 20250606v2pro | 克隆特定老师音色最强；GPU 可跑；时间戳非原生 |
| **edge-tts** | 11,891★，v7.2.8（2026-03-22）；**免费调用微软在线神经音色（zh-CN-XiaoxiaoNeural 等），原生输出 SRT 字幕（word boundary）**；本机实测一句中文 4 秒完成 | 零 GPU 成本；**风险：非官方 API（README 明示 SSML 被微软限制），网络依赖（本机到微软服务通），商用 ToS 灰色**——适合原型/降级，不适合长期生产主链路 |
| **manim-voiceover（官方插件）** | 见上；Whisper 词级对齐，动画按旁白词自动定时 | 把"音画对齐"变成渲染期自动行为，省自研时间轴 |

**对齐结论**：逐句（或逐幕）TTS → 取音频时长 → 驱动该幕动画 run_time 总和 → SRT 由 edge-tts SubMaker 或 whisper 词级时间戳生成 → 章节时间戳 = 幕时长累加（与 manim sections JSON 天然一致）。

---

## 八、与现有 geo-draw 链路结合的落地建议

1. **生成脚本 schema（AI 只产 JSON，不直写代码；Java 不碰语义）**——`animated_lesson_plan`：
```json
{
  "schema": "lesson-plan/v1",
  "runId": "task_xxx",
  "title": "一题一课：圆的切线长",
  "problem": {"known": ["..."], "goal": "...", "core_observation": "...", "answer": "..."},
  "style": {"palette": "default", "font": "SourceHanSansSC"},
  "chapters": [
    {"id": "c1", "title": "破题思路",
     "narration": "连接圆心和外一点……",
     "steps": [
       {"op": "draw_circle", "ref": "geo:circle1"},
       {"op": "draw_line", "from": "geo:P", "to": "geo:T1", "style": "aux", "color": "green", "run_time": 1.2},
       {"op": "annotate_point", "ref": "geo:P", "label": "P"},
       {"op": "caption", "text": "切线长相等", "at": "bottom"},
       {"op": "highlight", "refs": ["geo:T1", "geo:T2"]},
       {"op": "formula", "latex": "PT=\\sqrt{OP^2-r^2}"},
       {"op": "transform", "from": "geo:fig1", "to": "geo:fig2"}
     ]}
  ],
  "assets": [{"assetId": "...", "evidenceRef": "...", "role": "problem_figure"}]
}
```
   - `op` 为封闭枚举（draw_*/annotate/highlight/caption/formula/transform/camera），几何坐标由 Python 端符号求解（sympy/pyglet 计算切点等），**LLM 永远不填裸坐标**——直接规避 ManiBench 的 Visual-Logic Drift 与 SGA 的遮挡问题主因。
   - Python worker 内 `plan_compiler` 把 op 模板化编译成 Manim 代码（LLM2Manim 的"约束模板+symbol ledger"思路）；保留 `free_code` 逃生舱（复杂镜头才允许 LLM 直写，走完整修复回路）。
2. **渲染放独立服务，不放 FastAPI worker 进程内**：新建 `manim-render-worker`（复用现有 Docker/GPU 基底镜像，pip 层预装 manim+ffmpeg+中文字体，遵守"源码层重建"约定），FastAPI 经队列（现有任务表）提交/回调。理由：渲染是 CPU 密集子进程风暴（每场景 fork xelatex/ffmpeg），与在线 API 隔离；并发用目录级 `media/<runId>/` 隔离 + 信号量限并发（本机 12s 场景 19s 渲染，2-3 分钟课 ≈ 3-8 分钟 CPU，单条视频可接受但必须异步）。
3. **失败修复重试（分层，全部有学术/开源先例）**：
   - L0 静态守门：py_compile + mypy（ManimCat），拦语法/API 拼写；
   - L1 渲染回路：traceback 回灌 LLM 局部修补 ≤3 轮（ManimCat/animus/MathLens），**只重渲失败 section**（next_section + 缓存）；
   - L2 符号校验：执行前部分求值提取场景图，检测越界/重叠（SGA 思路，确定性代码）；
   - L3 VLM 审阅：复用 geo-draw 的 Luna 审图回路，每章抽 2-3 关键帧，检查中文/公式/遮挡；
   - L4 降级：全失败则回退"静态配图 + 逐章字幕 + TTS"幻灯片式视频（保证交付率）；
   - 运营：失败模式入库形成 M- 禁忌清单 + 成功模板 M+（arXiv 2606.30296 机制），随 run 统计 RSR 仪表盘（ManimCat 同款）。
4. **音画与章节**：CosyVoice3 GPU 服务逐句合成 → 时长驱动幕时长 → manim sections JSON + ffmetadata 封装章节 → 交付 mp4 + `chapters.json` + `subtitles.srt`；前端播放器用 chapters.json 做导航（竞品同款），mp4 章节仅作附带。
5. **与现有讲义链路衔接**：plan JSON 由 Python writer（plan_writer 之后新增 animation_writer 节点）产出，图片资产仍走 evidenceRef/assetId 授权物化（ImageMobject 仅接收 worker 落盘后的相对路径），Java 只存任务与产物，不碰教学语义——符合 AGENTS.md 边界。

---

## 九、风险清单

| 风险 | 程度 | 依据与缓解 |
|---|---|---|
| 渲染耗时 | 中 | 实测简单场景 1.2-1.5× 实时；LaTeX 密集/3D 场景可到 3-10×；重渲整片 3-15 分钟。缓解：section 级缓存只重渲失败段、`-qm` 720p 草稿审片 + `-qh` 终渲、夜间批量 |
| LLM 代码编译失败率 | 中-高 | 最优开源方案 RSR 也只有 94%（2604.18364）；API 幻觉/版本漂移是主因（ManiBench）。缓解：模板编译为主、锁 manim==0.21.0 与文档 RAG、四级修复回路、幻灯片降级 |
| 中文排版 | 低（Text 路线） | 本机实测雅黑 OK；但 **MathTex 内中文/xelatex 历史坑多**（issue #4052/#3254/#4237）→ 中文与公式严格分对象分层；Pango 换行/标点对齐需审图确认 |
| 字体版权 | 中 | 微软雅黑不可随 Linux 渲染服务分发（Windows 桌面字体 EULA）；生产 worker 用思源黑体/Noto Sans SC（OFL 可商用），风格一致性提前定 |
| edge-tts 依赖微软在线服务 | 中 | 非官方 API、SSML 受限（README 自证）、网络与 ToS 风险；主链路用自托管 CosyVoice3，edge-tts 仅原型/备份 |
| mp4 章节兼容性 | 低 | HTML5 video 无原生章节 UI；已验证 ffmetadata 封装可行，但导航 UI 必须前端用 JSON 自建 |
| 环境/网络 | 低 | 本机到 arxiv/HF/ddg 被阻断（调研已改道）；Docker 内预装依赖避免运行时 pip 拉包失败；禁止 prune（AGENTS.md） |
| 并发与磁盘 | 低 | 每 run 独立 media 目录、partial 缓存文件增长快（max_files_cached 配置）、2 分钟 1080p30 视频约 0.5-20MB 不等（实测 12s 场景 481KB，复杂场景大一个量级），产物走对象存储+本地清理 |
| 教学语义越界 | 流程 | 分镜/旁白/章节标题全部由 AI writer 产出并入库审计，Java/前端不得补写（AGENTS.md 门禁），验收清单需新增"动画交付"专项证据项 |

---

## 附：本机验证工件（可复跑）

- 环境：`C:\Users\doob\Desktop\code\dev\math_agent_rag\.research-tmp\mbench`（venv：manim 0.21.0 + edge-tts 7.2.8，Python 3.12.12，ffmpeg 8.1 gyan 全功能版）
- 场景：`bench_scene.py`（12s 1080p30，中文标题+逐条辅助线+中文标注）、`bench_sections.py`（next_section 三章）
- 产物：`media/videos/bench_scene/1080p30/bench_1080p30.mp4`、`media/videos/bench_sections/1080p30/sections/bs3.mp4.json`（章节清单含中文 name/duration）、`bench_chapters.mp4`（ffprobe 回读 3 章）、`frame_title.png`/`frame_sub.png`（中文渲染目检帧）、`tts_demo.mp3/.srt`（edge-tts 4s 出句带字幕）
- 复跑命令：`./mbench/Scripts/python.exe -m manim render bench_scene.py Bench -r 1920,1080 --fps 30 --save_sections`
- 调研完成后 `.research-tmp` 可整目录删除（约 400MB），不影响主工程。
