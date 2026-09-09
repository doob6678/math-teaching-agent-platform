# -*- coding: utf-8 -*-
"""
LaTeX Studio 编译核心：真实 xelatex 引擎编译 + PyMuPDF 页面渲染。

为什么独立成模块：server.py（浏览器实时预览）与 review_cli.py（ZCode 单发审阅）
共用同一套编译/解析/渲染逻辑，保证"人在浏览器里看到的"与"ZCode 审阅拿到的"
是同一引擎同一参数的产物。编译参数与 Java 生产链路
（TeachingHandoutPdfExportPolicyPartA：-interaction=nonstopmode -halt-on-error
-file-line-error，本机 MiKTeX）保持一致，审阅结论才能外推到生产 PDF。

产物布局：编译输出统一放到 tex 同目录的 .latexstudio-build/ 下，
不污染源目录；xelatex 的 CWD 设为 tex 所在目录，
这样模板里的相对路径图片（figures/xxx.png）按生产习惯可被找到。
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path
from queue import Empty, Queue

import fitz  # PyMuPDF：把编译出的 PDF 逐页渲成 PNG，供审阅/前端取用

# 与 Java 侧同款候选列表：优先 PATH，找不到再按 MiKTeX 默认安装位置兜底
_XELATEX_CANDIDATES = [
    "xelatex",
    r"C:\Users\doob\AppData\Local\Programs\MiKTeX\miktex\bin\x64\xelatex.exe",
    r"C:\Program Files\MiKTeX\miktex\bin\x64\xelatex.exe",
    "/usr/bin/xelatex",
    "/usr/local/bin/xelatex",
]

BUILD_DIR_NAME = ".latexstudio-build"
LOG_TAIL_LINES = 60
MAX_WARNINGS = 20

# ---------- 片段包装：AI 产出的讲义正文（无 preamble）套上生产保真模板头 ----------
# 逐项对齐 Java TeachingHandoutPdfExportPolicyPartA 的正式 preamble（article + xeCJK
# + 字体 fallback 链 + vec/frac/times 修复 wrappers），让片段预览的字体、行距、数学
# glyph 与生产 PDF 一致；页眉页脚/标题块属完整讲义策略，此处不注入。
# tikz 按需加载：装载约半秒，绝大多数片段用不到。
_FRAGMENT_PREAMBLE = r"""\documentclass{article}
\usepackage[margin=2.4cm]{geometry}
\usepackage{fontspec}
\usepackage{xeCJK}
\usepackage{xcolor}
\usepackage{amsmath,amssymb}
\usepackage{graphicx}
\usepackage{caption}
\usepackage{enumitem}
\usepackage{fancyhdr}
\usepackage{lastpage}
\usepackage{titlesec}
\usepackage{needspace}
\let\MathAgentOriginalVec\vec
\renewcommand{\vec}[1]{\ensuremath{\MathAgentOriginalVec{#1}}}
\let\MathAgentOriginalOverrightarrow\overrightarrow
\renewcommand{\overrightarrow}[1]{\ensuremath{\MathAgentOriginalOverrightarrow{#1}}}
\let\MathAgentOriginalFrac\frac
\renewcommand{\frac}[2]{\ensuremath{\MathAgentOriginalFrac{#1}{#2}}}
\let\MathAgentOriginalTimes\times
\renewcommand{\times}{\ensuremath{\MathAgentOriginalTimes}}
\IfFontExistsTF{Noto Sans CJK SC}{\setCJKmainfont{Noto Sans CJK SC}}{\IfFontExistsTF{Noto Sans SC}{\setCJKmainfont{Noto Sans SC}}{\IfFontExistsTF{Microsoft YaHei UI}{\setCJKmainfont{Microsoft YaHei UI}}{\IfFontExistsTF{SimSun}{\setCJKmainfont{SimSun}}{}}}}
\IfFontExistsTF{Noto Sans CJK SC}{\setCJKsansfont{Noto Sans CJK SC}}{\IfFontExistsTF{Noto Sans SC}{\setCJKsansfont{Noto Sans SC}}{\IfFontExistsTF{Microsoft YaHei UI}{\setCJKsansfont{Microsoft YaHei UI}}{}}}
\IfFontExistsTF{Noto Serif CJK SC}{\newCJKfontfamily\HandoutDisplayFont{Noto Serif CJK SC}}{\IfFontExistsTF{Noto Serif SC}{\newCJKfontfamily\HandoutDisplayFont{Noto Serif SC}}{\newcommand{\HandoutDisplayFont}{}}}
\IfFontExistsTF{Arial}{\setmainfont{Arial}}{}
\setlength{\parindent}{0pt}
"""

_DOCUMENT_RE = re.compile(r"\\begin\{document\}")
_TIKZ_RE = re.compile(r"\\begin\{tikzpicture\}|\\tikz\b")

# 目录/交叉引用/文献命令缺失时一遍即可定稿页码，编译时间近似减半
_TWO_PASS_HINT_RE = re.compile(
    r"\\(?:tableofcontents|label\s*\{|(?:auto|page|eq)?ref\s*\{|cite[tp]?\s*\{)"
)


def is_full_document(source: str) -> bool:
    return bool(_DOCUMENT_RE.search(source))


def wrap_handout_fragment(body: str) -> str:
    """完整文档原样返回；片段套生产保真头。tikz 只在正文真正用到时装载。"""
    if is_full_document(body):
        return body
    tikz = "\n\\usepackage{tikz}\n" if _TIKZ_RE.search(body) else ""
    return (
        _FRAGMENT_PREAMBLE
        + tikz
        + "\n\\begin{document}\n"
        + body.rstrip()
        + "\n\\end{document}\n"
    )


def needs_two_passes(source: str) -> bool:
    return bool(_TWO_PASS_HINT_RE.search(source))


def resolve_xelatex() -> str:
    for cand in _XELATEX_CANDIDATES:
        found = shutil.which(cand)
        if found:
            return found
        if Path(cand).exists():
            return cand
    raise FileNotFoundError("未找到 xelatex，请确认 MiKTeX/TeX Live 已安装且在 PATH 中")


def _read_log_bytes(log_path: Path) -> str:
    """xelatex 日志以本地编码写出（Windows 下常见 GBK 混 UTF-8），逐级降级解码。"""
    data = log_path.read_bytes()
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


_FILE_LINE_ERROR_RE = re.compile(r"^(?:\./)?([^:()\n]+?\.tex):(\d+):\s?(.*)$", re.M)
_BANG_ERROR_RE = re.compile(r"^! (.*)$", re.M)
_L_LINE_RE = re.compile(r"^l\.(\d+)", re.M)
_WARNING_RE = re.compile(r"^(LaTeX Warning:.*)$", re.M)
_MISSING_CHAR_RE = re.compile(r"^(Missing character:.*)$", re.M)


def parse_log(log_text: str) -> dict:
    """从 xelatex 日志提取主错误（halt-on-error 下通常只有一处）、行号与告警。"""
    errors = []
    for m in _FILE_LINE_ERROR_RE.finditer(log_text):
        errors.append({"file": m.group(1), "line": int(m.group(2)), "message": m.group(3).strip()})
    line_no = errors[0]["line"] if errors else None
    if not errors:
        bang = _BANG_ERROR_RE.search(log_text)
        if bang:
            lmatch = _L_LINE_RE.search(log_text, bang.end())
            line_no = int(lmatch.group(1)) if lmatch else None
            errors.append({"file": "", "line": line_no, "message": bang.group(1).strip()})

    warnings: list[str] = []
    for rx in (_WARNING_RE, _MISSING_CHAR_RE):
        for m in rx.finditer(log_text):
            if m.group(1) not in warnings:
                warnings.append(m.group(1).strip())
    warnings = warnings[:MAX_WARNINGS]

    tail = "\n".join(log_text.splitlines()[-LOG_TAIL_LINES:])
    return {"errors": errors, "error_line": line_no, "warnings": warnings, "log_tail": tail}


class LatexJob:
    """单个 tex 文件的编译状态快照，字段直接喂给前端 /api/state。"""

    def __init__(self, tex_path: Path):
        self.tex_path = tex_path
        self.status = "idle"            # idle | queued | compiling | ok | error
        self.error_line = None
        self.error_msg = ""
        self.errors: list = []
        self.warnings: list = []
        self.log_tail = ""
        self.page_count = 0
        self.compile_ms = 0
        self.last_compiled_at = 0.0
        self.source_hash = ""           # 上次尝试编译的 tex 内容 hash，用于内容未变时短路

    def apply_parse(self, parsed: dict) -> None:
        self.errors = parsed["errors"]
        self.error_line = parsed["error_line"]
        self.warnings = parsed["warnings"]
        self.log_tail = parsed["log_tail"]
        if self.errors:
            self.status = "error"
            self.error_msg = self.errors[0]["message"]
        else:
            self.status = "ok"
            self.error_msg = ""

    def to_dict(self) -> dict:
        return {
            "path": str(self.tex_path),
            "status": self.status,
            "errorLine": self.error_line,
            "errorMsg": self.error_msg,
            "errors": self.errors,
            "warnings": self.warnings,
            "pageCount": self.page_count,
            "compileMs": self.compile_ms,
            "lastCompiledAt": self.last_compiled_at,
        }


class LatexCompileManager:
    """常驻服务的编译管理器：单 worker 队列串行编译，内容 hash 短路去重。

    为什么要串行：多 tex 并行编译会抢满 CPU 且 MiKTeX 首次自动装包（AutoInstall=1）
    存在并发写文件树的风险；讲义审阅一次只看一份稿，串行 + 排队足够，
    编辑风暴（连续按键）由 pending 去重 + hash 短路天然合并。
    """

    def __init__(self, pass_timeout: int = 300, resource_dirs: list[Path] | None = None):
        self.pass_timeout = pass_timeout
        # 资源根：讲义图片资产常不在 tex 同目录（assets/、corpus/ 等），
        # 通过 TEXINPUTS 注入 kpathsea 搜索路径，\includegraphics 按名即可命中
        self.resource_dirs = [Path(d).resolve() for d in (resource_dirs or [])]
        self._xelatex = resolve_xelatex()
        self._jobs: dict[str, LatexJob] = {}
        self._lock = threading.Lock()
        self._fitz_lock = threading.Lock()   # PyMuPDF 非线程安全，渲染统一串行
        self._queue: "Queue[Path]" = Queue()
        self._pending: set[str] = set()
        self._worker = threading.Thread(target=self._worker_loop, daemon=True)
        self._worker.start()

    # ---------- 对外 API ----------

    def job_for(self, tex_path: Path) -> LatexJob:
        key = str(tex_path)
        with self._lock:
            job = self._jobs.get(key)
            if job is None:
                job = LatexJob(tex_path)
                self._jobs[key] = job
            return job

    def request_compile(self, tex_path: Path) -> dict:
        """入队编译（异步）。同文件已在队列则合并；返回当前状态快照供前端立即渲染。"""
        job = self.job_for(tex_path)
        key = str(tex_path)
        with self._lock:
            if key not in self._pending:
                self._pending.add(key)
                self._queue.put(tex_path)
        return job.to_dict()

    def compile_sync(self, tex_path: Path, passes: int | None = None) -> LatexJob:
        """同步编译，review_cli 单发审阅用；跳过队列与 hash 短路（CLI 场景要强制结果）。"""
        job = self.job_for(tex_path)
        if passes is None:
            passes = 2 if needs_two_passes(self._read_source(tex_path)) else 1
        self._run_compile(job, passes=passes)
        return job

    def build_pdf_path(self, tex_path: Path) -> Path:
        return tex_path.parent / BUILD_DIR_NAME / (tex_path.stem + ".pdf")

    def build_log_path(self, tex_path: Path) -> Path:
        return tex_path.parent / BUILD_DIR_NAME / (tex_path.stem + ".log")

    def render_png(self, tex_path: Path, page: int, dpi: int = 130) -> bytes:
        """按需把编译产物 PDF 的某一页渲成 PNG。无磁盘缓存：fitz 单页渲染 <50ms，
        无状态化省掉缓存失效逻辑（pdf 一变 key 就变的老问题直接消失）。"""
        pdf_path = self.build_pdf_path(tex_path)
        if not pdf_path.exists():
            raise FileNotFoundError("PDF 不存在，请先编译")
        with self._fitz_lock:
            with fitz.open(pdf_path) as doc:
                if page < 1 or page > doc.page_count:
                    raise IndexError(f"页码 {page} 超出范围 1..{doc.page_count}")
                pix = doc[page - 1].get_pixmap(dpi=dpi)
                return pix.tobytes("png")

    # ---------- worker ----------

    def _worker_loop(self) -> None:
        while True:
            try:
                tex_path = self._queue.get(timeout=1.0)
            except Empty:
                continue
            key = str(tex_path)
            with self._lock:
                self._pending.discard(key)
            job = self.job_for(tex_path)
            try:
                current_hash = self._hash_source(tex_path)
                # 内容未变且上次就是这份内容的结果 → 直接复用，避免编辑风暴空转
                if current_hash == job.source_hash and job.status in ("ok", "error"):
                    continue
                source = self._read_source(tex_path)
            except OSError:
                current_hash = ""
                source = ""
            # 智能遍数：无目录/交叉引用的文档一遍即定稿，编译时间近似减半
            self._run_compile(job, passes=2 if needs_two_passes(source) else 1,
                              source_hash=current_hash)

    # ---------- 编译本体 ----------

    @staticmethod
    def _read_source(tex_path: Path) -> str:
        try:
            return tex_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""

    @staticmethod
    def _hash_source(tex_path: Path) -> str:
        return hashlib.md5(tex_path.read_bytes()).hexdigest()

    def _build_env(self) -> dict:
        """TEXINPUTS 注入资源根：'.' 优先，资源目录带 // 递归子目录，
        结尾分号 = 追加内置默认搜索树（必须保留，否则 ctex 等宏包全部失联）。"""
        env = os.environ.copy()
        if self.resource_dirs:
            paths = ["."] + [str(d) + "//" for d in self.resource_dirs]
            env["TEXINPUTS"] = ";".join(paths) + ";"
        return env

    def _run_compile(self, job: LatexJob, passes: int = 2, source_hash: str = "") -> None:
        tex_path = job.tex_path
        build_dir = tex_path.parent / BUILD_DIR_NAME
        build_dir.mkdir(parents=True, exist_ok=True)
        job.status = "compiling"
        job.error_line = None
        job.error_msg = ""
        started = time.monotonic()
        log_text = ""
        ok = False

        for _ in range(passes):
            try:
                proc = subprocess.run(
                    [
                        self._xelatex,
                        "-interaction=nonstopmode",
                        "-halt-on-error",
                        "-file-line-error",
                        f"-output-directory={build_dir}",
                        tex_path.name,
                    ],
                    cwd=str(tex_path.parent),      # CWD=tex 目录：相对路径图片按生产习惯解析
                    env=self._build_env(),          # TEXINPUTS 注入资源根（本地图片资产搜索）
                    capture_output=True,
                    timeout=self.pass_timeout,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
            except subprocess.TimeoutExpired:
                job.status = "error"
                job.error_msg = f"编译超时（>{self.pass_timeout}s），已终止"
                job.log_tail = job.error_msg
                break
            log_path = self.build_log_path(tex_path)
            if log_path.exists():
                log_text = _read_log_bytes(log_path)
            if proc.returncode == 0:
                ok = True
            else:
                break
        job.compile_ms = int((time.monotonic() - started) * 1000)

        if not ok:
            job.source_hash = source_hash
            parsed = parse_log(log_text) if log_text else {
                "errors": [{"file": "", "line": None, "message": "xelatex 运行失败且无日志"}],
                "error_line": None, "warnings": [], "log_tail": "",
            }
            job.apply_parse(parsed)
            job.last_compiled_at = time.time()
            return

        pdf_path = self.build_pdf_path(tex_path)
        if not pdf_path.exists():
            job.status = "error"
            job.error_msg = "编译返回 0 但未生成 PDF"
            job.last_compiled_at = time.time()
            job.source_hash = source_hash
            return

        parsed = parse_log(log_text)
        # 成功日志里也可能带告警，单独收一下
        job.warnings = parsed["warnings"]
        job.log_tail = parsed["log_tail"]
        with self._fitz_lock:
            with fitz.open(pdf_path) as doc:
                job.page_count = doc.page_count
        job.status = "ok"
        job.errors = []
        job.error_line = None
        job.error_msg = ""
        job.last_compiled_at = time.time()
        job.source_hash = source_hash
