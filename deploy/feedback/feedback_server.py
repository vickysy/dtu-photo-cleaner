#!/usr/bin/env python3
"""轻了相册官网反馈表的接收程序：只收反馈，存成一行一条的文件，再生成一个只给作者看的页面。

- nginx 把 POST /api/feedback 转到这里（127.0.0.1:8792），别的请求一律不接。
- 存：时间、问题描述、手机型号、App 版本、联系方式（选填）、从什么设备提交的。不存访客 IP（只在内存里拿来限流）。
- 防刷：隐藏的 website 字段填了就当机器人；同一个地址每小时最多 5 条、每天最多 20 条；全站每天最多 500 条。
- 只给作者看的页面生成在 /var/lib/qingle-feedback/page/，服务器上用一个随机名字的链接挂到官网下
  （/var/www/qingle/feedback-xxxx → 这个目录），随机名字只在服务器上，不进代码仓库。这个程序只能写自己的目录。
"""
import datetime
import html
import json
import os
import threading
import time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT = "127.0.0.1", 8792
STATE_DIR = "/var/lib/qingle-feedback"
STORE = os.path.join(STATE_DIR, "feedback.jsonl")
PAGE_DIR = os.path.join(STATE_DIR, "page")
TZ = datetime.timezone(datetime.timedelta(hours=8))
LIMITS = {"message": 2000, "phone": 60, "version": 30, "contact": 80}
PER_HOUR, PER_DAY, ALL_DAY = 5, 20, 500

lock = threading.Lock()
recent = defaultdict(deque)  # ip -> 提交时间
all_today = deque()


def device(ua: str) -> str:
    if "MicroMessenger" in ua:
        return "微信里"
    if "iPhone" in ua or "iPad" in ua:
        return "iPhone"
    if "Android" in ua or "HarmonyOS" in ua:
        return "安卓"
    return "电脑"


def allowed(ip: str, now: float) -> bool:
    q = recent[ip]
    while q and now - q[0] > 86400:
        q.popleft()
    while all_today and now - all_today[0] > 86400:
        all_today.popleft()
    if len(all_today) >= ALL_DAY or len(q) >= PER_DAY or sum(1 for t in q if now - t < 3600) >= PER_HOUR:
        return False
    q.append(now)
    all_today.append(now)
    return True


def render(page_dir: str) -> None:
    """把所有反馈写成一个静态页面，新的在前"""
    rows = []
    try:
        with open(STORE, encoding="utf-8") as f:
            rows = [json.loads(line) for line in f if line.strip()]
    except FileNotFoundError:
        pass
    rows.reverse()
    esc = html.escape
    items = []
    for r in rows:
        meta = " · ".join(x for x in [r.get("time", ""), r.get("device", ""), r.get("phone", ""), ("App " + r["version"]) if r.get("version") else ""] if x)
        contact = f'<p class="c">联系方式：{esc(r["contact"])}</p>' if r.get("contact") else ""
        items.append(f'<li><p class="m">{esc(meta)}</p><p class="t">{esc(r.get("message", ""))}</p>{contact}</li>')
    now = datetime.datetime.now(TZ).strftime("%Y-%m-%d %H:%M")
    body = "\n".join(items) or '<li><p class="t">还没有反馈。</p></li>'
    page = f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow">
<title>轻了相册 · 用户反馈</title>
<style>
body{{margin:0;background:#f7f8fa;color:#1d2129;font:16px/1.7 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}}
main{{max-width:760px;margin:0 auto;padding:24px 16px 60px}}h1{{font-size:24px;margin:8px 0}}
.s{{color:#5c6370;font-size:14px}}ul{{list-style:none;padding:0;margin:20px 0}}
li{{background:#fff;border:1px solid #e6e8eb;border-radius:14px;padding:14px 16px;margin-bottom:12px}}
.m{{color:#8a909a;font-size:13px;margin:0}}.t{{white-space:pre-wrap;margin:6px 0 0}}.c{{color:#119c7e;font-size:14px;margin:6px 0 0}}
</style></head><body><main>
<h1>用户反馈（{len(rows)} 条）</h1>
<p class="s">只有你知道这个地址。每收到一条就更新。最后更新：{now}</p>
<ul>
{body}
</ul></main></body></html>"""
    os.makedirs(page_dir, exist_ok=True)
    tmp = os.path.join(page_dir, ".index.html.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(page)
    os.replace(tmp, os.path.join(page_dir, "index.html"))


class Handler(BaseHTTPRequestHandler):
    server_version = "qingle-feedback"

    def reply(self, code: int, obj: dict) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.reply(405, {"ok": False, "error": "只收提交"})

    def do_POST(self):
        if self.path.split("?")[0] != "/api/feedback":
            return self.reply(404, {"ok": False, "error": "没有这个地址"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            n = 0
        if n <= 0 or n > 8192:
            return self.reply(400, {"ok": False, "error": "内容太长了"})
        try:
            data = json.loads(self.rfile.read(n).decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError
        except Exception:
            return self.reply(400, {"ok": False, "error": "格式不对"})
        if str(data.get("website", "")).strip():
            return self.reply(200, {"ok": True})  # 机器人：装作收到了
        rec = {k: str(data.get(k, "")).strip()[:lim] for k, lim in LIMITS.items()}
        if len(rec["message"]) < 2:
            return self.reply(400, {"ok": False, "error": "先写一下遇到的问题"})
        ip = self.headers.get("X-Real-IP") or self.client_address[0]
        with lock:
            if not allowed(ip, time.time()):
                return self.reply(429, {"ok": False, "error": "提交太多了，过一会儿再试"})
            rec["time"] = datetime.datetime.now(TZ).strftime("%Y-%m-%d %H:%M")
            rec["device"] = device(self.headers.get("User-Agent", ""))
            os.makedirs(STATE_DIR, exist_ok=True)
            with open(STORE, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            render(PAGE_DIR)
        return self.reply(200, {"ok": True})

    def log_message(self, fmt, *args):
        pass  # 不记访问日志（nginx 已经有了），也就不留 IP


if __name__ == "__main__":
    render(PAGE_DIR)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
