#!/usr/bin/env python3
"""
RikkaHub 浏览器测试靶站（零依赖，只用标准库）。

为什么不用 example.com / 别人的站：
  - example.com 只有一个内联脚本 → CDP 断点按 URL 匹配，永远下不上
  - 别人的站是服务端渲染 → 抓不到 XHR，hook 测了个寂寞
  - 外网依赖 → 断网/被墙就测不了，结果不可重复

这个靶站把要测的东西全摆明面上：
  GET  /              HTML，外链 /app.js
  GET  /app.js        脚本，第 2 行(0-based)是 fetch('/api/ping') —— 断点必中
  GET  /api/ping      JSON，相对路径请求，用来验「相对 URL 是否被绝对化」
  GET  /robots.txt    纯文本，用来验 mock / capture_body
  POST /api/post      回显 body，用来验请求体捕获
  GET  /slow?ms=800   延迟响应，用来验暂停时的超时行为

跑：
    python3 server.py          # 监听 0.0.0.0:8099
    python3 server.py 9000     # 换端口

浏览器打开：
    http://127.0.0.1:8099/
"""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# 注意下面这段 JS 的行号是测试契约：文档里写「断点下 line=2」，改这里必须同步改文档。
APP_JS = """// 测试靶站脚本。行号是 0-based，CDP 断点按这个下。
function boot() {
  fetch('/api/ping')
    .then(function (r) { return r.json(); })
    .then(function (d) {
      window.__lastPing = d;
      document.getElementById('out').textContent = JSON.stringify(d);
    })
    .catch(function (e) {
      document.getElementById('out').textContent = 'ERR ' + e.message;
    });
}

function postOnce() {
  fetch('/api/post', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ from: 'target-page', at: Date.now() })
  })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      window.__lastPost = d;
      document.getElementById('out').textContent = JSON.stringify(d);
    });
}

boot();
"""

PAGE = """<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RikkaHub 测试靶站</title>
<style>
  body { background:#0e1116; color:#e6edf3; font:15px/1.6 system-ui,sans-serif; margin:0; padding:24px; }
  h1 { font-size:18px; margin:0 0 4px; }
  p  { color:#8b949e; margin:0 0 18px; }
  button { background:#238636; color:#fff; border:0; border-radius:8px; padding:12px 18px; font-size:15px; }
  pre { background:#161b22; border:1px solid #30363d; border-radius:8px; padding:14px; white-space:pre-wrap; word-break:break-all; }
  code { color:#79c0ff; }
</style>
</head>
<body>
  <h1>RikkaHub 测试靶站</h1>
  <p>外链脚本 <code>/app.js</code> 第 <b>2</b> 行是断点位置（0-based）。页面加载即发一次 <code>/api/ping</code>。</p>
  <button id="btn" onclick="postOnce()">POST /api/post</button>
  <pre id="out">（这里显示 fetch 结果）</pre>
  <script src="/app.js"></script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    server_version = "RikkaTestTarget/1.0"

    def _send(self, code: int, body: str, ctype: str = "text/html; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        # 明确不缓存：否则 reload 后拿不到最新脚本，断点行号会对不上
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/":
            return self._send(200, PAGE)
        if path == "/app.js":
            return self._send(200, APP_JS, "application/javascript; charset=utf-8")
        if path == "/robots.txt":
            return self._send(200, "User-agent: *\n# test-target\n", "text/plain; charset=utf-8")
        if path == "/api/ping":
            return self._send(
                200,
                json.dumps(
                    {"ok": True, "path": self.path, "at": int(time.time() * 1000)},
                    ensure_ascii=False,
                ),
                "application/json; charset=utf-8",
            )
        if path == "/slow":
            ms = 800
            qs = parse_qs(parsed.query)
            if "ms" in qs:
                try:
                    ms = max(0, min(10000, int(qs["ms"][0])))
                except ValueError:
                    pass
            time.sleep(ms / 1000.0)
            return self._send(200, json.dumps({"ok": True, "sleptMs": ms}), "application/json; charset=utf-8")

        return self._send(404, "not found", "text/plain; charset=utf-8")

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        body = raw.decode("utf-8", "replace")
        payload = {"ok": True, "echo": body, "len": n, "at": int(time.time() * 1000)}
        self._send(200, json.dumps(payload, ensure_ascii=False), "application/json; charset=utf-8")

    def log_message(self, fmt, *args):
        # 打日志到 stdout，方便对照「服务器收到几个请求」和「hook 抓到几个」
        sys.stdout.write("[target] " + (fmt % args) + "\n")
        sys.stdout.flush()


def main() -> None:
    port = 8099
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print("端口必须是数字", file=sys.stderr)
            raise SystemExit(2)

    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"测试靶站已启动： http://127.0.0.1:{port}/", flush=True)
    print("浏览器里打开上面这个地址（App 和它是同一台设备，loopback 直通）", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止", flush=True)


if __name__ == "__main__":
    main()
