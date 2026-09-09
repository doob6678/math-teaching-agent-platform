# -*- coding: utf-8 -*-
"""支持 HTTP Range 的静态服务器：视频 seek 依赖 206 分段响应，
python -m http.server 不支持 Range（章节跳转会被浏览器复位），故自写。
生产环境由 frontend nginx 承担同等职责。
用法：python range_server.py [port]，服务根目录=本文件所在目录。"""
import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

CHUNK = 64 * 1024


class RangeHandler(SimpleHTTPRequestHandler):
    def send_head(self):
        path = self.translate_path(self.path)
        rng = self.headers.get("Range")
        if not rng or not os.path.isfile(path):
            self._range_len = None
            return super().send_head()
        m = re.match(r"bytes=(\d*)-(\d*)", rng)
        if not m:
            self._range_len = None
            return super().send_head()
        size = os.path.getsize(path)
        start = int(m.group(1) or 0)
        end = int(m.group(2)) if m.group(2) else size - 1
        end = min(end, size - 1)
        if start >= size or start > end:
            self.send_error(416, "Invalid range")
            return None
        f = open(path, "rb")
        f.seek(start)
        length = end - start + 1
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(length))
        self.end_headers()
        self._range_len = length
        return f

    def copyfile(self, source, outputfile):
        length = getattr(self, "_range_len", None)
        if length is None:
            return super().copyfile(source, outputfile)
        while length > 0:
            data = source.read(min(CHUNK, length))
            if not data:
                break
            outputfile.write(data)
            length -= len(data)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8792
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    print(f"serving with Range on 127.0.0.1:{port}")
    ThreadingHTTPServer(("127.0.0.1", port), RangeHandler).serve_forever()
