# -*- coding: utf-8 -*-
"""
LaTeX Studio 常驻服务：左编辑右实时渲染的后端。

零 pip 依赖（仅标准库 + PyMuPDF），绑定 127.0.0.1 不触发 Windows 防火墙。
路径安全：所有 file/compile/pdf/png 请求里的 path 参数 resolve 后必须落在
允许根（默认仓库根，可用 --root 追加）内，防止把任意文件喂进编译或读接口。

API 一览（前端与 ZCode 共用）：
  GET  /api/file?path=          读 tex 文本 {content, mtime}
  POST /api/save {path,content} 原子写 tex（保存即自动触发编译）
  POST /api/compile {path}      手动触发编译（入队，异步）
  GET  /api/state?path=         编译状态快照（前端 1s 轮询）
  GET  /api/pdf?path=           编译产物 PDF
  GET  /api/png?path=&page=&dpi= 某页 PNG（ZCode 审阅直读）
  GET  /api/health              存活探针
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

sys.path.insert(0, str(Path(__file__).parent))
from compiler import (  # noqa: E402
    LatexCompileManager,
    is_full_document,
    wrap_handout_fragment,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
STATIC_DIR = Path(__file__).resolve().parent / "static"
WORKSPACE_DIR = Path(__file__).resolve().parent / "workspace"

MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".pdf": "application/pdf",
    ".map": "application/json",
}

manager: LatexCompileManager
allowed_roots: list[Path]


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def resolve_inside_roots(raw: str) -> Path:
    """把请求里的路径解析为绝对路径并校验白名单根。

    为什么不直接信前端：本服务面向"ZCode + 人"共同使用，任何写/编译入口都必须
    兜底防路径逃逸（即便当前只绑 127.0.0.1）。
    """
    raw = unquote(raw)
    p = Path(raw)
    if not p.is_absolute():
        p = REPO_ROOT / p
    resolved = p.resolve()
    for root in allowed_roots:
        if resolved == root or root in resolved.parents:
            return resolved
    raise ApiError(403, f"路径超出允许根范围: {resolved}")


def get_tex(query: dict) -> Path:
    tex = resolve_inside_roots(query["path"][0])
    if tex.suffix.lower() != ".tex":
        raise ApiError(400, "仅支持 .tex 文件")
    return tex


def atomic_write(path: Path, content: str) -> None:
    """原子写 + 重试：Windows 下 xelatex 编译期间持有 tex 文件句柄，
    AI 连续推送会撞上 os.replace 的 PermissionError；等待编译结束再写入，
    多次重试仍失败才报 409，避免丢内容。"""
    last_err: Exception | None = None
    for _ in range(10):
        fd, tmp = tempfile.mkstemp(dir=str(path.parent), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
                f.write(content)
            os.replace(tmp, path)
            return
        except PermissionError as e:
            last_err = e
            try:
                os.unlink(tmp)
            except OSError:
                pass
            time.sleep(0.5)
    raise ApiError(409, f"文件正被编译占用，请稍后重试: {last_err}")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静默默认访问日志，长跑不留垃圾
        pass

    # ---------- 响应工具 ----------

    def _send(self, status: int, body: bytes, ctype: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")  # 预览必须永远新鲜，禁缓存
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj: dict, status: int = 200) -> None:
        self._send(status, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _file(self, path: Path) -> None:
        ctype = MIME.get(path.suffix.lower(), "application/octet-stream")
        self._send(200, path.read_bytes(), ctype)

    # ---------- 路由 ----------

    def do_GET(self):
        try:
            url = urlparse(self.path)
            q = parse_qs(url.query)
            if url.path == "/" or url.path == "/index.html":
                self._file(STATIC_DIR / "index.html")
            elif url.path.startswith("/static/"):
                rel = url.path[len("/static/"):]
                target = (STATIC_DIR / rel).resolve()
                if STATIC_DIR not in target.parents and target != STATIC_DIR:
                    raise ApiError(403, "静态目录逃逸")
                if not target.is_file():
                    raise ApiError(404, f"静态资源不存在: {rel}")
                self._file(target)
            elif url.path == "/api/file":
                tex = resolve_inside_roots(q["path"][0])
                if not tex.exists():
                    raise ApiError(404, f"文件不存在: {tex}")
                self._json({"content": tex.read_text(encoding="utf-8"),
                            "mtime": tex.stat().st_mtime, "path": str(tex)})
            elif url.path == "/api/state":
                tex = get_tex(q)
                job = manager.job_for(tex)
                data = job.to_dict()
                data["texMtime"] = tex.stat().st_mtime if tex.exists() else 0
                data["pdfExists"] = manager.build_pdf_path(tex).exists()
                self._json(data)
            elif url.path == "/api/pdf":
                tex = get_tex(q)
                pdf = manager.build_pdf_path(tex)
                if not pdf.exists():
                    raise ApiError(404, "PDF 尚未编译")
                self._file(pdf)
            elif url.path == "/api/png":
                tex = get_tex(q)
                page = int(q.get("page", ["1"])[0])
                dpi = int(q.get("dpi", ["130"])[0])
                try:
                    body = manager.render_png(tex, page, dpi)
                except FileNotFoundError as e:
                    raise ApiError(404, str(e))
                except IndexError as e:
                    raise ApiError(400, str(e))
                self._send(200, body, "image/png")
            elif url.path == "/api/health":
                self._json({"ok": True})
            else:
                raise ApiError(404, "未知路由")
        except ApiError as e:
            self._json({"error": e.message}, e.status)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # 兜底：任何未预期异常都返回 500 JSON，不让前端挂着
            self._json({"error": f"服务器内部错误: {e}"}, 500)

    def do_POST(self):
        try:
            url = urlparse(self.path)
            length = int(self.headers.get("Content-Length", 0))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
            except (UnicodeDecodeError, json.JSONDecodeError):
                # Windows 下 curl 默认按 GBK 发中文，必须显式 400 提示而不是 500
                raise ApiError(400, "请求体必须是 UTF-8 编码的 JSON")
            if url.path == "/api/save":
                tex = get_tex({"path": [payload.get("path", "")]})
                content = payload.get("content", "")
                atomic_write(tex, content)
                manager.request_compile(tex)
                self._json({"ok": True, "mtime": tex.stat().st_mtime})
            elif url.path == "/api/live/push":
                # 推送式实时渲染：AI 写讲义每产出一段正文就 POST 到这里，
                # 片段自动套生产保真模板头，落盘即触发编译，浏览器秒级出稿。
                content = payload.get("content", "")
                session = payload.get("session") or "default"
                if not re.fullmatch(r"[\w.-]{1,40}", session):
                    raise ApiError(400, "session 仅限字母数字与 -_.，≤40 字符")
                if not content.strip():
                    raise ApiError(400, "content 为空")
                full = is_full_document(content)
                body = content if full else wrap_handout_fragment(content)
                WORKSPACE_DIR.mkdir(exist_ok=True)
                tex = WORKSPACE_DIR / f"live-{session}.tex"
                atomic_write(tex, body)
                manager.request_compile(tex)
                self._json({"ok": True, "file": str(tex), "wrapped": not full})
            elif url.path == "/api/compile":
                tex = get_tex({"path": [payload.get("path", "")]})
                if not tex.exists():
                    raise ApiError(404, f"文件不存在: {tex}")
                self._json(manager.request_compile(tex))
            else:
                raise ApiError(404, "未知路由")
        except ApiError as e:
            self._json({"error": e.message}, e.status)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            self._json({"error": f"服务器内部错误: {e}"}, 500)


def main() -> None:
    global manager, allowed_roots
    ap = argparse.ArgumentParser(description="LaTeX Studio 实时预览服务")
    ap.add_argument("--port", type=int, default=8764)
    ap.add_argument("--root", action="append", default=[],
                    help="额外允许读写的根目录（可多次）；默认仓库根已包含")
    ap.add_argument("--resource", action="append", default=[],
                    help="本地图片资产根目录（可多次），注入 TEXINPUTS 供 \\includegraphics 搜索")
    args = ap.parse_args()

    allowed_roots = [REPO_ROOT]
    for r in args.root:
        allowed_roots.append(Path(r).resolve())

    manager = LatexCompileManager(resource_dirs=[Path(r) for r in args.resource])
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print(f"[latex-studio] http://127.0.0.1:{args.port}  xelatex={manager._xelatex}")
    server.serve_forever()


if __name__ == "__main__":
    main()
