#!/usr/bin/env python3
"""TikZ 源文件 -> 高清 PNG 的确定性渲染管线（无 AI，纯编译）。

用法:
    python render.py demo_rhombus.tex [--dpi 300] [--out figure.png]

为什么走 PDF 中转: TikZ 是矢量语言，xelatex 产出矢量 PDF，再用 PyMuPDF
以任意 DPI 栅格化，边缘锐度只受 DPI 控制；不经 SVG/浏览器，避免字体与
渲染器差异。依赖仅两项: MiKTeX(xelatex + tikz) 与 PyMuPDF，本机均已装。

编译失败时打印 xelatex 日志尾部并退出码非 0 —— 该退出码就是上层
"生成->编译->回喂修复" 闭环（同讲义 LaTeX 修复循环模式）的判据。
"""
import argparse
import subprocess
import sys
from pathlib import Path


def compile_tex(tex: Path, workdir: Path) -> Path:
    """xelatex 单次编译 standalone 文档，返回 PDF 路径；失败即抛错。"""
    workdir.mkdir(exist_ok=True)
    # 先删旧 PDF: 只查"存在"不查"新鲜"会在编译失败时静默复用上一轮产物（实测踩坑）
    stale = workdir / (tex.stem + ".pdf")
    if stale.exists():
        stale.unlink()
    cmd = [
        "xelatex", "-interaction=nonstopmode", "-halt-on-error",
        f"-output-directory={workdir}", str(tex),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=workdir)
    pdf = workdir / (tex.stem + ".pdf")
    if not pdf.exists():
        # 把日志尾部交给调用方（AI 修复循环读的就是这段）
        tail = "\n".join((proc.stdout or "").splitlines()[-40:])
        raise RuntimeError(f"xelatex 编译失败:\n{tail}")
    return pdf


def pdf_to_png(pdf: Path, out: Path, dpi: int) -> tuple[int, int]:
    import fitz  # PyMuPDF：矢量栅格化器
    with fitz.open(pdf) as doc:
        pix = doc[0].get_pixmap(dpi=dpi, alpha=False)
        out.parent.mkdir(parents=True, exist_ok=True)
        pix.save(out)
        return pix.width, pix.height


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("tex", help="standalone TikZ .tex 文件")
    ap.add_argument("--dpi", type=int, default=300, help="输出分辨率，默认 300")
    ap.add_argument("--out", default=None, help="输出 PNG 路径，默认与 tex 同名")
    args = ap.parse_args()

    tex = Path(args.tex).resolve()
    out = Path(args.out).resolve() if args.out else tex.with_suffix(".png")
    try:
        pdf = compile_tex(tex, tex.parent / (tex.stem + ".build"))
        w, h = pdf_to_png(pdf, out, args.dpi)
    except RuntimeError as e:
        print(e, file=sys.stderr)
        return 1
    print(f"{out}  {w}x{h}px @{args.dpi}dpi")
    return 0


if __name__ == "__main__":
    sys.exit(main())
