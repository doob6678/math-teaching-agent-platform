# LaTeX Studio — 左编辑右实时渲染的 LaTeX 审阅工作台

浏览器里左边写/看 `.tex` 源码，右边用**真实 xelatex**（与讲义生产 Java 链路
`TeachingHandoutPdfExportPolicyPartA` 同参数：`-interaction=nonstopmode -halt-on-error -file-line-error`）
实时编译渲染。三个使用方：

1. **人盯中间过程**：AI 迭代写讲义 tex 时，浏览器自动跟随外部文件修改并重编译，老板看着 PDF 一步步成形。
2. **AI 推送式直播（live push）**：AI/worker 每产出一段正文就 POST 一次，片段自动套生产模板头，秒级出稿。
3. **ZCode 自审阅**：`review_cli.py` 一条命令出逐页 PNG，AI 直接读图检查公式/中文/图片渲染质量；
   无 preamble 的讲义片段（如 `output/acceptance/*.tex`）自动包装，错误行号折算回片段原文。

## 目录结构

```
tools/latex-studio/
├── compiler.py      # 编译核心（xelatex 智能遍数 + 日志解析 + PyMuPDF 渲染 + 片段包装），服务与 CLI 共用
├── server.py        # 常驻预览服务（标准库实现，零 pip 依赖；PyMuPDF 仅渲染用）
├── review_cli.py    # ZCode 单发审阅 CLI：编译 → 逐页 PNG → 打印路径
├── test_live_push.py      # live push 冒烟测试（服务启动后可重跑）
├── start.cmd / stop.cmd   # 后台启动/停止（独立于终端会话常驻）
├── static/          # 前端（CodeMirror 5 + PDF.js v6，全部本地 vendor，离线可用）
├── workspace/demo.tex     # 演示样例（ctexart 生产模板头 + 公式/表格/图片/目录）
└── review-output/         # CLI 审阅产物（gitignore）
```

## 启动

```
tools\latex-studio\start.cmd     # 后台常驻 http://127.0.0.1:8764
tools\latex-studio\stop.cmd      # 停止
```

打开页面后顶栏输入任意仓库内 `.tex` 路径（或直接带 `?file=<路径>` 参数），
「最近文件」下拉可快速切换；`Ctrl+S` 立即保存编译（跳过防抖）。
右侧默认「适应宽度」，可切 100%/125%/150%。

**本地图片**：`figures/xxx.png` 这类相对路径（相对 tex 所在目录）直接可渲染；
图片资产在别处时启动服务加 `--resource <目录>`（可多次，递归子目录搜索，
经 TEXINPUTS 注入 kpathsea），例如：

```
python server.py --resource C:/data/handout-assets --resource ../output
```

## AI 写讲义实时输出（live push）

AI/worker 每写完一段正文就推送一次，浏览器端零操作自动跟随：

```
POST /api/live/push   {"content": "<tex 正文或片段>", "session": "任务名"}
```

- 片段（无 `\begin{document}`）自动套**生产保真模板头**（article + xeCJK +
  字体 fallback 链 + vec/frac/times 修复 wrappers，逐项对齐 Java 正式 preamble；
  tikz 按需加载），完整文档原样使用。
- 落盘 `workspace/live-<session>.tex` 并触发编译；返回 `{ok, file, wrapped}`，
  浏览器打开 `http://127.0.0.1:8764/?file=tools/latex-studio/workspace/live-<session>.tex` 即看。
- 编译中再次推送不会丢内容（原子写带撞锁重试）。
- 冒烟测试：`python test_live_push.py`（服务运行中执行）。

## ZCode 审阅（AI 用）

首选 CLI（无需服务）：

```
python tools/latex-studio/review_cli.py <tex路径> [--pages 1-3] [--dpi 130]
                                         [--resource 图片资产根 ...] [--raw]
```

- 成功：打印 `[OK] 页数=... 耗时=...` 和每页 PNG 绝对路径（`PNG ...`），AI 逐张 Read 看图审阅。
- 失败：打印错误行号/消息 + 日志尾部 40 行，退出码 1；片段编译失败时额外打印
  「片段原文行号」（包装头占的行数自动折算）。
- `--raw` 禁用片段包装；编译产物在 tex 同目录 `.latexstudio-build/` 下，不污染源目录。

已注册为 ZCode skill：`latex-live-review`。

## HTTP API（服务模式）

| 路由 | 说明 |
|---|---|
| `GET /api/file?path=` | 读 tex 文本 `{content, mtime}` |
| `POST /api/save {path, content}` | 原子写盘（撞锁自动重试）并触发编译 |
| `POST /api/live/push {content, session}` | 推送式直播渲染（片段自动套模板） |
| `POST /api/compile {path}` | 入队编译（内容未变时 hash 短路） |
| `GET /api/state?path=` | 编译状态快照（status/errorLine/pageCount/texMtime） |
| `GET /api/png?path=&page=&dpi=` | 某页 PNG（服务常驻时 AI 也可走这条） |
| `GET /api/pdf?path=` | 编译产物 PDF |

`path` 支持仓库相对路径；所有路径 resolve 后必须落在仓库根内（`--root` 可追加白名单根）。

## 前端行为

- 编辑防抖 900ms 自动保存并编译；`Ctrl+S` 立即保存编译；编译中右侧半透明蒙层 + 琥珀色徽章。
- 编译失败：右侧错误条列出「第 N 行 + 消息」，点击跳编辑器对应行；错误行红色高亮。
- 外部修改（AI 写文件）：编辑器无未保存内容时自动重载并重编译；有未保存内容时顶部出提示条由人决定加载与否。
- 编译完成刷新 PDF 时保持滚动位置。

## 设计约束（为什么这么做）

- **真实 xelatex 而非 KaTeX**：审阅结论要能外推到生产 PDF，KaTeX 无法渲染文档级结构。
- **CWD=tex 所在目录 + 输出进 `.latexstudio-build/`**：模板里 `figures/xxx.png` 相对路径按生产习惯解析。
- **智能遍数**：正文无目录/交叉引用时一遍即定稿（编译时间近似减半）；含 `\tableofcontents`/`\ref`/`\label` 才跑两遍。
- **单 worker 串行编译**：避免多文件并行抢 CPU 和 MiKTeX 自动装包（AutoInstall=1）并发写文件树；编辑风暴由 pending 去重 + 内容 hash 短路合并。
- **PNG 无磁盘缓存按需渲染**：fitz 单页渲染 <50ms，无状态化消灭缓存失效问题。
- **原子写带重试**：Windows 下 xelatex 编译期间持有 tex 文件句柄，AI 连续推送会撞 `os.replace` 的 PermissionError，重试等待而非丢内容。
