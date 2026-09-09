# -*- coding: utf-8 -*-
"""
ZCode 单发审阅 CLI：编译一个 tex 并输出逐页 PNG 的绝对路径，供 ZCode 直接 Read 看图。

为什么独立于 server：AI 审阅是"一次性批处理"（写完 tex → 编译 → 看图 → 修），
不需要常驻服务；而老板盯中间过程才需要浏览器实时预览。两条路共用 compiler.py，
保证看到的是同一引擎同一参数的产物。

讲义正文片段（无 \\begin{document}）会自动套上生产保真模板头再编译，
因此 output/acceptance/*.tex 这类真实讲义产物可以直接指定审阅。

用法：
  python review_cli.py <tex路径> [--pages 1-3] [--dpi 130] [--out 目录]
                       [--resource 图片资产根 ...] [--raw]
成功：逐行打印 PNG 绝对路径 + 页数/耗时，退出码 0
失败：打印主错误行号/消息 + 日志尾部，退出码 1
"""
from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from compiler import (  # noqa: E402
    BUILD_DIR_NAME,
    LatexCompileManager,
    is_full_document,
    wrap_handout_fragment,
)


def parse_pages(spec: str, page_count: int) -> list[int]:
    if not spec:
        return list(range(1, page_count + 1))
    pages: list[int] = []
    for part in spec.split(","):
        if "-" in part:
            a, b = part.split("-", 1)
            pages.extend(range(int(a), int(b) + 1))
        else:
            pages.append(int(part))
    return [p for p in pages if 1 <= p <= page_count]


def main() -> int:
    ap = argparse.ArgumentParser(description="编译 tex 并输出审阅 PNG")
    ap.add_argument("tex", help="tex 文件路径（绝对或相对仓库根）")
    ap.add_argument("--pages", default="", help="页码，如 1-3 或 1,4；默认全部")
    ap.add_argument("--dpi", type=int, default=130, help="渲染 DPI，默认 130")
    ap.add_argument("--out", default="", help="PNG 输出目录，默认 tools/latex-studio/review-output/")
    ap.add_argument("--resource", action="append", default=[],
                    help="本地图片资产根目录（可多次），注入 TEXINPUTS 搜索")
    ap.add_argument("--raw", action="store_true",
                    help="不对片段套模板头，按原文编译")
    args = ap.parse_args()

    tex = Path(args.tex)
    if not tex.is_absolute():
        tex = Path(__file__).resolve().parents[2] / tex
    tex = tex.resolve()
    if not tex.exists():
        print(f"[FAIL] 文件不存在: {tex}")
        return 1

    source = tex.read_text(encoding="utf-8", errors="replace")
    wrapped = not is_full_document(source) and not args.raw
    target = tex
    header_lines = 0
    if wrapped:
        body = wrap_handout_fragment(source)
        # 包装头占的行数 = 总行数 - 正文行数 - 1（\end{document} 行），
        # 用于把编译错误行号折算回片段原文
        header_lines = len(body.splitlines()) - len(source.rstrip().splitlines()) - 1
        target = tex.parent / f"latexstudio-wrapped-{tex.stem}.tex"
        target.write_text(body, encoding="utf-8")

    started = time.monotonic()
    manager = LatexCompileManager(resource_dirs=[Path(r) for r in args.resource])
    job = manager.compile_sync(target)
    elapsed = time.monotonic() - started

    if job.status != "ok":
        print(f"[FAIL] 编译失败（{elapsed:.1f}s）")
        if job.error_line:
            print(f"  错误行: {job.errors[0]['file'] or target.name}:{job.error_line}")
            if wrapped and job.error_line > header_lines:
                print(f"  片段原文行号: 第 {job.error_line - header_lines} 行（包装头占 {header_lines} 行）")
        print(f"  错误: {job.error_msg}")
        print("  ---- 日志尾部 ----")
        for line in job.log_tail.splitlines()[-40:]:
            print(f"  {line}")
        _cleanup_wrapped(tex, target, wrapped)
        return 1

    out_dir = Path(args.out) if args.out else (
        Path(__file__).resolve().parent / "review-output" /
        f"{tex.stem}-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    )
    out_dir.mkdir(parents=True, exist_ok=True)

    pages = parse_pages(args.pages, job.page_count)
    wrap_note = " 片段已套生产模板" if wrapped else ""
    print(f"[OK] 页数={job.page_count} 耗时={elapsed:.1f}s 警告={len(job.warnings)}{wrap_note}")
    for w in job.warnings:
        print(f"  [warn] {w}")
    try:
        for p in pages:
            png_path = out_dir / f"page-{p}.png"
            png_path.write_bytes(manager.render_png(target, p, dpi=args.dpi))
            print(f"PNG {png_path}")
    finally:
        # 包装文件必须活到 PNG 渲染之后：PDF 产物以它的 stem 命名
        _cleanup_wrapped(tex, target, wrapped)
    return 0


def _cleanup_wrapped(tex: Path, target: Path, wrapped: bool) -> None:
    if not wrapped:
        return
    target.unlink(missing_ok=True)
    build = tex.parent / BUILD_DIR_NAME
    if build.is_dir():
        for stale in build.glob(f"latexstudio-wrapped-{tex.stem}.*"):
            stale.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
