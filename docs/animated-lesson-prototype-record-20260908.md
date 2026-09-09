# 一题一课·分步动画讲解 原型交付记录（2026-09-08）

老板需求：对标竞品"一题一课智能体"（动图分步作图、辅助线逐条画出、视频章节导航、结构化解析），
调研方案并做出效果，题目多来几道。本记录为本次运行的真实证据链。

## 结论

**能做，且原型已跑通。** 技术路线：Manim CE 0.21.0 + 封闭 op 分镜 JSON + 确定性编译 +
edge-tts 配音驱动时长 + ffmpeg 中文章节封装。工程位置 `tools/animated-lesson/`。

## 本次交付物（全部真实渲染，非模拟）

| 课题 | 来源 | 成片 | 章节 | 时长 |
|---|---|---|---|---|
| 圆的切线最值（竞品同款题） | 老板截图题 | out/tangent-min/final.mp4 | 7 | 97.2s |
| 两圆公切线 | 2022 新高考Ⅰ卷 T14（corpus q-014） | out/two-circle-tangents/final.mp4 | 6 | 82.2s |
| 圆外两切线夹角 | 2023 新课标Ⅰ卷 T6（corpus q-006） | out/tangent-angle/final.mp4 | 5 | 55.1s |
| 椭圆与动直线恒成立 | 2024 天津卷 T18（corpus q-018） | out/ellipse-moving-line/final.mp4 | 5 | 78.9s |

每课产物含 final.mp4（内嵌中文 ffmetadata 章节 + AAC 配音轨）、chapters.json（前端导航）、
narration/*.mp3、lesson.json 分镜快照。预览：`python range_server.py 8791` →
http://127.0.0.1:8791/demo/index.html（视频+章节侧栏+已知条件/解题目标/核心观察/答案四卡，对标竞品布局）。

## 效果验收（本次运行证据）

- 逐帧目检：纸面配色、章节横幅（出现→缩小常驻左上）、辅助线绿色逐笔绘制、直角小方块标记、
  右侧板书逐行累积（中文 Text + LaTeX 公式混排）、底部中文字幕、动点 P 沿 x 轴移动时
  切线/半径/直角弧/标签全部实时跟随（tangent-min ch5/ch6）、动直线绕定点旋转且与椭圆交点
  P/Q 及 TP/TQ 虚线跟随（ellipse ch4）。
- 浏览器验收（IAB）：视频真实加载播放（readyState 4）、章节点击 seek 生效（跳 68.3s 后
  currentTime 69.3、侧栏高亮同步）、四题 tab 切换正常、答案卡内容正确。
- 音画对齐：章节时长由 TTS 实测驱动（如 tangent-min 七章 16.6/15.4/15.5/14.1/14.0/15.9/18.7s）。

## 过程中修掉的真实问题（教训落盘）

1. `Scene.add_updater` 在 Cairo 渲染器下不驱动 mobject 重绘（manim 源码注释明示）→
   动点镜头改用 mobject 级 updater；此前 tangent-min 恰好生效、椭圆题不生效，属渲染器实现细节，不可依赖。
2. manim 0.21 顶层不导出 `Color` → 配色用 hex 字符串。
3. sections 清单改名 `<场景类名>.json`、type=`default.normal`、duration 为字符串 → 解析适配。
4. 雅黑缺字 `⟺` 渲染成豆腐块 → SCHEMA 禁用生僻符号，改"当且仅当"。
5. MathTex 默认 48pt 撑爆板书记行距 → 统一 font_size=34。
6. python `http.server` 不支持 Range → 视频 seek 复位，自写 `range_server.py`（生产 nginx 原生支持）。
7. ffmpeg 混流漏传输出文件参数，报"amix output unconnected"误导排查 → argv 落盘 mux_cmd.json 定位。

## 与主工程边界的对齐

- 教学正文（旁白/字幕/章节/板书）全部在分镜 JSON 内，由 AI 作者产出——Java/前端不补写教学语义。
- 题目来自 corpus 规范语料（题干+答案+解析真实摘录）；题图资产链（assetId/evidenceRef）本原型未接入，
  动画图形由几何约束符号生成，不读取任何图片二进制。
- 生产化接入点已在内部盘点报告列明：新 workload `animated_lesson` 走 agent_worker_task outbox 异步队列，
  渲染放独立 manim-render-worker（Docker 基底预装依赖），配音换自托管 CosyVoice3。

## 2026-09-09 补记：公式真排版、主链路接入、生成引擎事故与教训

### 公式渲染（老板令"把那些公式渲染好"）

- 全部板书/字幕/题干从 Unicode 伪公式升级为 `$...$` 混排真排版：`LessonScene._mixed()` 把文本按
  `$` 切段，中文走 Pango Text、数学段走 MathTex(xelatex)，`arrange(RIGHT, aligned_edge=DOWN)` 拼行。
- 四片重渲染抽帧目检通过：分式、根式、上下标、角度弧标注均为真 LaTeX，与中文混排基线对齐，无豆腐块。
- demo 页（make_demo.py）结构化解析卡接入 KaTeX 0.16.11，同源读 lessons/*.json 的 LaTeX 源。
- SCHEMA.md 增补纪律：公式一律真排版，JSON 内 LaTeX 反斜杠双写；生僻符号（⟺⇔∴∽）禁用。

### 主链路接入（老板令"做好的当主链路，作为讲题 agent"）

- **Python 执行体已完成并真实验证**：`ai-worker-python/app/animated_lesson_runtime.py`
  新 workload `animated_lesson`——合同 `AnimatedLessonRunRequest`（runId+providerRoute+problemText，
  lessonId slug 防路径注入，grant 绑定校验在模型层）、生成回路（SCHEMA+范例 → JSON →
  validate_storyboard 回喂 ≤3 轮）、渲染子进程（LESSON_OUT_DIR 注入，失败 500 带日志尾部）。
  路由 `POST /v1/animated-lessons/sync`（require_worker_key），provider 调用走
  `migrated_workload_runtime().chat_result`（UsageLedger 记账 + providerName/usage 上报，
  新增 timeout_seconds/max_tokens 显式预算参数，缺省行为不变）。
- 单测全绿（含真实 4 分镜过 worker 加载的原型校验器=schema 漂移哨兵）；
  端到端 smoke 真实通过：投喂 tangent-angle 分镜 → 200 COMPLETED，58.63s/5 章成片
  `output/animated-lessons/smoke-ta/final.mp4`（ffprobe 实测），并发冲突导致的渲染失败
  路径也真实触发过（500 + 日志尾部，语义正确）。
- Java 下发（agent_worker_task stage_code=animated_lesson + outbox，零迁移）、产物 Range 服务、
  前端"动画讲题"块由子代理并行实现中；worker 容器基底尚无 manim/ffmpeg/xelatex，
  生产渲染走 `ANIMATED_LESSON_TOOLS_DIR`/`ANIMATED_LESSON_PYTHON` 指向具备环境的解释器，
  基底预装依赖是明日立项项（AGENTS.md 基底纪律）。

### 生成引擎事故：Terra 403 真因（老板问"怎么没有超时检测模型存活"）

- q-016 首轮"挂死"复盘：不是网络故障，是 **Terra 网关内容安全对提示词中"答案/解析"字样的
  确定性 403 拒绝**（geo-draw 同款题目正常通过），叠加网关延迟劣化（ping 7.9s）与
  16000 max_tokens 预算被上下文校验拒绝，三个坑互相掩盖。
- 修复（子代理执行）：corpus 标记归一（【答案】→【结论】、【解析】→【推导过程】，
  worker 侧 `_sanitize_problem_text` 同词表）；预算回 8000；单次调用超时 900s→300s，
  超时即换 fallback provider；`ai_gen/probe_providers.py` 探活脚本（五家最小真实调用）。
- **老板拍板：分镜生成弃用 Terra，链改 GLM 主（glm-5.3-flash → deepseek → dashscope）**，
  GLM 三坑（Anthropic 线格式/思考吃预算/无 json_object）由子代理在 gen 脚本适配；
  探活+回退纪律沉淀为 skill `model-liveness-failover`。主链路 route 由 Java 签发 GLM。
- 教训：长任务挂死先探网关（存活+延迟+内容安全三类失败模式），再怀疑代码；
  "模型没死"与"模型不能用"是两回事。

### 链路稳定性与负载实测（同日并行，详见 docs/link-stability-eval-20260909.md）

- 检索/健康链路 10 并发 0% 错误（教材检索 p95 95ms）；教师检索 GPU rerank 串行 ~5.5rps 饱和；
  学生讲解 conc10 成功率 50%，根因 ai-worker 流执行器 max_workers=4 触发 409 抛在 SSE 内——
  修复立项明日（讲题 agent 接入时同场处理）。动画讲题走异步队列正是为避开这类长任务形态。

### 子代理结果（09-09 深夜补记，全部真实运行）

**q-016 生成引擎重跑（探活回退链）**：链 glm→deepseek→dashscope（Terra 仍探活但不进链）。
实际交付出自 **dashscope(qwen-plus)**：glm 对这份 ~1.2 万字符提示词同步端点三次全超时
（300/600/300s，小请求 3.4s 正常——**GLM 主位在大分镜规模上目前跑不出来**，改流式是唯一
出路，口径待定）、deepseek 首轮 `finish=length` 空正文（思考吃光预算，关思考参数实测为
`thinking:{type:disabled}` 而非仓库档案记的 `enable_thinking`——worker 侧档案待明日修正）、
dashscope 连中 3 次出稿。生成 3 次尝试、校验修复 0 轮、渲染 1 轮成功；
`lessons/q-016.json` VALID、5 章、`out/q-016/final.mp4` ffprobe 115.43s/1080p。
回退路径另有两次真实换家证据（坏链演练日志 `q016_drill3.log`）。
**遗留（抽帧复阅发现，校验器拦不住）**：note 文本含伪 LaTeX 源码、副标题漏裸 `$c$`、
view 裁掉椭圆下半、标签重叠——明日给 validate_storyboard 加两条通用规则
（非 formula 字段禁 `\frac|circ|Rightarrow|$`；conic 曲线必须整体在 view 内）并回喂重生成。

**Java+前端主链路接线**：worker `/v1/animated-lessons/sync` 合同冒烟 200/500 双路径真实通过；
Java `PythonMigratedWorkloadClient.runAnimatedLesson`（长超时可配，默认 1800s）、
`agent_worker_task` stage_code=animated_lesson 走 outbox（零迁移）、
`AnimatedLessonController` meta+video（HttpRange+ResourceRegion/206，产物路径受控解析防注入），
route 签发按老板口径 **glm 主位、deepseek 备、不回退 Terra**；mvn compile 绿
（接手修掉 ResourceRegion 包名与 contentLength IOException 两处）。
前端 AI 讲题页新增浮动胶囊「动画讲题」+模态播放器（视频+章节侧栏 seek+KaTeX 解析卡，
浮层不侵入 100dvh 对话壳）；npm build 绿。端到端联调（起后端全栈真跑一题）明日做。

**HTML 点击式动画（B 站参考风格判定）**：参考视频真实下载抽帧 25 张目检——**不是 3B1B**，
是浅色纸面"逐条揭示+荧光高亮+章节进度"风（与竞品同路数）。据此实现 `html_player/`：
分镜 JSON→SVG+KaTeX 数据编译器 + 无构建纯静态播放器（点击/键盘逐步、trace 动点 rAF 闭式重算、
跳章、双课 tab），两课 62 步 playwright 无头全流程 SELFTEST PASS、0 console 错误。
与 Manim 视频版共享同一分镜 schema，双形态成立。

**稳定性修复（同日 agent）**：讲解流 conc>4 的 500 根因修复——worker 流执行器改
env 可配并发（默认 4）+BoundedSemaphore 有界等待 15s，409/429 冲突改为 error 事件携带
真实状态码不再在 SSE 头后 raise；provider 429 透传+Retry-After。全量 222 测试 OK。

**字幕公式验收修复（09-09 中午，老板实拍反馈）**：老板在 demo 页看到椭圆课题目字幕
"根号没套上、分数不平铺"——两层根因：①该视频渲染时间早于分镜文件的 LaTeX 修正，是旧片；
重渲后公式正确（\frac 竖排、\sqrt 覆盖、\triangle 下标）。②抽帧复阅发现新缺陷：
分式竖排后字幕行高约 1，`_caption` 用中心锚点 caption_y=-3.62，分母沉出 1080p 画面底边。
lesson_runtime 加通用钳制（底边低于 -3.9 整行上移，只抬不压，普通单行不受影响），
含 \frac/\sqrt 高字幕的课全部按新编译器重渲（ellipse/tangent-angle/two-circle/tangent-min，
q-016 待明日校验器规则后随重生成一并覆盖）。新增 ai_gen/scan_unicode_math.py、
scan_bad_latex.py、scan_caption_height.py 三个扫描器：前者确认无课再犯伪数学，
后者列出高字幕清单作为重渲依据。
