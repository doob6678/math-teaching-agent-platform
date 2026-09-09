# animated-lesson — 一题一课·分步动画讲解（原型，2026-09-08）

给一道数学题 → 分镜 JSON（AI 或人工编写）→ 确定性编译为 Manim 场景 → 配音驱动时长 →
渲染 1080p 带中文章节与音轨的讲解视频 → demo 页（视频+章节侧栏+结构化解析）。

对标竞品"一题一课智能体"：辅助线逐条画出、动点/动直线实时跟随、章节时间戳导航、
已知条件/解题目标/核心观察/答案四卡。已验证 4 道真题（含竞品同款"圆切线最值"）。

## 文件

| 文件 | 职责 |
|---|---|
| `SCHEMA.md` | 分镜剧本契约 lesson-plan/v1：封闭 op 枚举 + 几何约束对象（AI 只产 JSON，不填派生坐标、不写代码） |
| `lesson_runtime.py` | 分镜→Manim 场景确定性播放器：布局、几何符号求解（切点/垂足）、动点/动直线更新器、板书笔记本、章节横幅、字幕带 |
| `render_lesson.py` | 出片流水线：edge-tts 逐章配音→ffprobe 实测时长→渲染（--save_sections）→adelay 混音→ffmetadata 中文章节封装→chapters.json |
| `lessons/*.json` | 4 份已验收分镜：tangent-min / two-circle-tangents / tangent-angle / ellipse-moving-line |
| `make_demo.py` | 生成 `demo/index.html`（扫描 out/*/chapters.json 内联） |
| `range_server.py` | 带 HTTP Range 的本地静态服务（视频 seek 必需；生产由 nginx 承担） |
| `ai_gen/` | Terra 自动生成分镜的可行性实验（gen_storyboard.py + validate_storyboard.py，由子任务维护） |
| `out/<id>/` | 每课产物：final.mp4、chapters.json、timing.json、narration/*.mp3、lesson.json 快照 |

## 用法

```bash
# 环境（一次性）：python -m venv .venv && .venv/Scripts/pip install manim==0.21.0 edge-tts sympy
.venv/Scripts/python.exe render_lesson.py lessons/tangent-min.json   # 出片（首次会 TTS+全量渲染，约 3~8 分钟）
.venv/Scripts/python.exe make_demo.py                                # 重建 demo 页
python range_server.py 8791                                          # 预览 http://127.0.0.1:8791/demo/index.html
```

改旁白文字后需删 `out/<id>/narration/` 重新配音；改分镜几何/步骤直接重渲（manim 段级缓存只重渲变化部分）。

## 关键设计决策（为什么这么做）

1. **封闭 op + 符号几何求解**：LLM 直写 Manim 代码最优也只有 ~94% 渲染成功率（ManiBench/ManimTrainer），
   且"Visual-Logic Drift"是主要失败模式；分镜 JSON 让 AI 只表达教学语义，坐标全部由
   tangent_points()/垂足/交点公式在编译期求解，从根上消灭坐标幻觉。
2. **音长驱动画面**（MathLens 纪律）：章节时长 = TTS 实测 + 0.8s，步骤按 run_time 比例缩放，
   不硬编码秒数；章节时间戳以渲染实测 sections JSON 为权威，前端导航读 chapters.json。
3. **中文走 Pango Text、公式走 MathTex**：雅黑缺字（⟺ 等）会渲染豆腐块，SCHEMA 已禁；
   生产 Linux 渲染服务需换思源黑体（字体版权，LESSON_FONT 环境变量可切）。
4. **mobject 级 updater**：Cairo 渲染器不追踪 scene 级 updater 修改的 mobject（源码注释明示），
   动点/动直线镜头必须把 updater 挂在被动画的 mobject 上。
5. 调研与论文依据见 `docs/animated-lesson-research-20260908.md`；
   仓库复用点盘点见 `docs/animated-lesson-internal-inventory-20260908.md`。

## 已知边界（原型 ≠ 生产）

- 配音是 edge-tts（微软在线、商用 ToS 灰色）——生产换自托管 CosyVoice3（GPU）。
- 渲染在本机 CPU——生产走独立 manim-render-worker（Docker 基底预装依赖，任务经 agent_worker_task outbox 异步下发）。
- 雅黑仅本机原型用；Linux 渲染分发需思源黑体。
- 学生可见内容全部来自分镜 JSON（AI 作者），Java/前端不补写教学语义——符合 AGENTS.md 边界。
