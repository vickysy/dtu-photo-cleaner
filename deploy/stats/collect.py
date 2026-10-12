#!/usr/bin/env python3
"""轻了相册下载跟踪：每小时跑一次，读 nginx 日志 + GitHub 公开接口，生成一个只给作者看的统计页。

- 服务器日志只留 14 天，所以每天的数字都存进 /var/lib/qingle-stats/daily.json，日志滚掉以后照样能看。
- 只存汇总数字，不存访客 IP。
- 页面地址里的随机目录名放在 /etc/qingle-stats.conf（STATS_DIR=stats-xxxx），不进代码仓库。
"""
import datetime
import gzip
import html
import json
import os
import re
import urllib.request
from collections import Counter, defaultdict

LOG_DIR = "/var/log/nginx"
DL_DIR = "/var/www/qingle/download"
STATE = "/var/lib/qingle-stats/daily.json"
WEB_ROOT = "/var/www/qingle"
REPO = "vickysy/qingle-album"
# 测试用的出口 IP：作者电脑、另一台服务器、本机
OURS = {"108.202.51.166", "47.76.78.153", "124.221.210.254", "127.0.0.1"}
BOTS = re.compile(r"bot|spider|crawl|slurp|curl|python|wget|Go-http|headless|monitor|preview", re.I)
LINE = re.compile(r'^(\S+) \S+ \S+ \[([^\]]+)\] "(\S+) (\S+) [^"]*" (\d{3}) (\d+) "[^"]*" "([^"]*)"')
APK = re.compile(r"^/download/(qingle-album-v[\d.]+\.apk)")
VIEW = "/img/welcome.jpg"  # 官网首屏图，只有轻了官网有：用它算访客


def conf() -> dict:
    out = {}
    try:
        for line in open("/etc/qingle-stats.conf", encoding="utf-8"):
            if "=" in line and not line.startswith("#"):
                k, v = line.strip().split("=", 1)
                out[k] = v
    except FileNotFoundError:
        pass
    return out


def scan_logs():
    sizes = {f: os.path.getsize(os.path.join(DL_DIR, f)) for f in os.listdir(DL_DIR) if f.endswith(".apk")}
    dl = defaultdict(int)       # (day, ip, ua, file) -> bytes
    views = defaultdict(set)    # day -> {(ip, ua)}
    wechat = defaultdict(set)
    device = defaultdict(Counter)
    for name in sorted(os.listdir(LOG_DIR)):
        if not name.startswith("access.log"):
            continue
        path = os.path.join(LOG_DIR, name)
        opener = gzip.open if name.endswith(".gz") else open
        with opener(path, "rt", errors="replace") as f:
            for line in f:
                m = LINE.match(line)
                if not m:
                    continue
                ip, ts, method, req, status, size, ua = m.groups()
                if ip in OURS or BOTS.search(ua) or method != "GET":
                    continue
                day = datetime.datetime.strptime(ts.split()[0], "%d/%b/%Y:%H:%M:%S").strftime("%Y-%m-%d")
                a = APK.match(req)
                if a and status in ("200", "206"):
                    dl[(day, ip, ua, a.group(1))] += int(size)
                elif req.startswith(VIEW) and status in ("200", "304"):
                    key = (ip, ua)
                    if key not in views[day]:
                        views[day].add(key)
                        device[day]["iphone" if re.search("iPhone|iPad", ua) else "android" if "Android" in ua else "pc"] += 1
                        if "MicroMessenger" in ua:
                            wechat[day].add(key)
    days = {}
    for (day, ip, ua, f), b in dl.items():
        d = days.setdefault(day, {"done": 0, "partial": 0, "by_version": {}})
        if b >= 0.95 * sizes.get(f, 47_800_000):
            d["done"] += 1
            d["by_version"][f] = d["by_version"].get(f, 0) + 1
        elif b > 1_000_000:
            d["partial"] += 1
    for day, v in views.items():
        d = days.setdefault(day, {"done": 0, "partial": 0, "by_version": {}})
        d["visitors"] = len(v)
        d["wechat"] = len(wechat[day])
        d.update(device[day])
    return days


def github(prev: dict) -> dict:
    try:
        def get(url):
            req = urllib.request.Request(url, headers={"User-Agent": "qingle-stats"})
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read().decode("utf-8"))
        repo = get(f"https://api.github.com/repos/{REPO}")
        rel = get(f"https://api.github.com/repos/{REPO}/releases?per_page=100")
        assets = [{"tag": r["tag_name"], "name": a["name"], "count": a["download_count"]} for r in rel for a in r.get("assets", [])]
        return {"stars": repo["stargazers_count"], "forks": repo["forks_count"], "assets": assets,
                "total": sum(a["count"] for a in assets), "at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M")}
    except Exception as e:  # 取不到就沿用上次的数字
        prev = dict(prev or {})
        prev["error"] = str(e)[:120]
        return prev


def render(state: dict) -> str:
    days = dict(sorted(state["days"].items()))
    today = datetime.date.today().isoformat()
    total_site = sum(d.get("done", 0) for d in days.values())
    gh = state.get("github", {})
    total_gh = gh.get("total", 0)
    visitors = sum(d.get("visitors", 0) for d in days.values())
    by_version = Counter()
    for d in days.values():
        by_version.update(d.get("by_version", {}))
    recent = list(days.items())[-30:]
    peak = max([d.get("done", 0) for _, d in recent] + [1])
    bars = "".join(
        f'<div class="b" title="{k}：完整下载 {d.get("done",0)} 次"><span>{d.get("done",0)}</span>'
        f'<i style="height:{max(2, round(d.get("done",0) / peak * 180))}px"></i><em>{k[5:].replace("-", "/")}</em></div>'
        for k, d in recent)
    rows = "".join(
        f'<tr><td>{k}</td><td>{d.get("done",0)}</td><td>{d.get("partial",0)}</td><td>{d.get("visitors",0)}</td>'
        f'<td>{d.get("wechat",0)}</td><td>{d.get("android",0)} / {d.get("iphone",0)} / {d.get("pc",0)}</td></tr>'
        for k, d in reversed(list(days.items())))
    vers = "".join(f"<li><b>{html.escape(v.replace('qingle-album-','').replace('.apk',''))}</b>{n} 次</li>"
                   for v, n in sorted(by_version.items(), reverse=True))
    gh_rows = "".join(f"<li><b>{html.escape(a['tag'])}</b>{a['count']} 次</li>" for a in gh.get("assets", []))
    err = f'<p class="muted">GitHub 这次没取到，显示的是 {html.escape(gh.get("at",""))} 的数字。</p>' if gh.get("error") else ""
    td = days.get(today, {})
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow">
<title>轻了相册 · 下载跟踪</title><style>
:root{{--ink:#1d2129;--sub:#5c6370;--faint:#8a909a;--line:#e6e8eb;--card:#f7f8fa;--g:#119c7e;--gs:#e8f8f3}}
*{{box-sizing:border-box}}body{{margin:0;background:#fff;color:var(--ink);font:15px/1.6 -apple-system,"PingFang SC","Hiragino Sans GB",sans-serif}}
.w{{max-width:880px;margin:0 auto;padding:28px 16px 48px}}h1{{font-size:24px;margin:0}}.muted{{color:var(--faint);font-size:13px}}
.tiles{{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:20px 0}}.t{{background:var(--card);border-radius:14px;padding:14px}}
.t b{{display:block;font-size:30px;line-height:1.2}}.t span{{font-size:13px;color:var(--sub)}}.t.h{{background:var(--gs)}}
h2{{font-size:17px;margin:28px 0 10px}}.bars{{display:flex;gap:6px;align-items:flex-end;height:230px;border-bottom:1px solid var(--line);overflow-x:auto;padding-top:8px}}
.b{{flex:1;min-width:30px;display:flex;flex-direction:column;align-items:center;justify-content:flex-end}}.b span{{font-size:12px;font-weight:600}}
.b i{{display:block;width:70%;max-width:40px;background:var(--g);border-radius:4px 4px 0 0;margin-top:3px}}.b em{{font-style:normal;font-size:11px;color:var(--faint);margin-top:4px;white-space:nowrap}}
table{{width:100%;border-collapse:collapse;font-size:14px}}th,td{{text-align:right;padding:7px 6px;border-bottom:1px solid var(--line)}}th:first-child,td:first-child{{text-align:left}}th{{color:var(--sub);font-weight:500}}
ul{{list-style:none;padding:0;margin:0;display:flex;flex-wrap:wrap;gap:8px}}li{{background:var(--card);border-radius:10px;padding:6px 12px}}li b{{margin-right:8px}}
@media(max-width:600px){{.tiles{{grid-template-columns:repeat(2,1fr)}}}}
</style></head><body><div class="w">
<h1>轻了相册 · 下载跟踪</h1><p class="muted">每小时自动更新 · 最近一次：{state["updated"]} · 这个页面不公开，地址别外传</p>
<div class="tiles">
<div class="t h"><b>{total_site + total_gh}</b><span>累计完整下载（官网 + GitHub）</span></div>
<div class="t"><b>{total_site}</b><span>官网累计完整下载</span></div>
<div class="t"><b>{total_gh}</b><span>GitHub 安装包下载</span></div>
<div class="t"><b>{td.get("done",0)}</b><span>今天官网完整下载</span></div>
<div class="t"><b>{visitors}</b><span>官网访客（按天去重后相加）</span></div>
<div class="t"><b>{td.get("visitors",0)}</b><span>今天官网访客</span></div>
<div class="t"><b>{gh.get("stars",0)}</b><span>GitHub Star</span></div>
<div class="t"><b>{sum(d.get("partial",0) for d in days.values())}</b><span>开始下载但没下完</span></div>
</div>
<h2>官网每天完整下载（最近 30 天）</h2><div class="bars">{bars}</div>
<h2>每天明细</h2><table><tr><th>日期</th><th>完整下载</th><th>没下完</th><th>访客</th><th>微信里打开</th><th>安卓 / iPhone / 电脑</th></tr>{rows}</table>
<h2>官网各版本下载</h2><ul>{vers}</ul>
<h2>GitHub 各版本下载</h2><ul>{gh_rows}</ul>{err}
<h2>统计口径</h2><p class="muted">完整下载：同一台设备下完一个版本算一次（断点续传合并计算）；没下完：下了 1MB 以上但没下完。访客：加载过官网首屏图的设备，按天去重。已排除作者自己的测试网络和各类爬虫。同一个 Wi-Fi 下的多个人会被算成一个。GitHub 的数字来自它的公开接口。</p>
</div></body></html>"""


def main():
    c = conf()
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    try:
        state = json.load(open(STATE, encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        state = {"days": {}}
    state["days"].update(scan_logs())   # 日志里还有的天数用最新算的，滚掉的天数保留旧值
    state["github"] = github(state.get("github"))
    state["updated"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    tmp = STATE + ".tmp"
    json.dump(state, open(tmp, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    os.replace(tmp, STATE)
    stats_dir = c.get("STATS_DIR")
    if stats_dir:
        out = os.path.join(WEB_ROOT, stats_dir)
        os.makedirs(out, exist_ok=True)
        page = os.path.join(out, "index.html")
        open(page + ".tmp", "w", encoding="utf-8").write(render(state))
        os.replace(page + ".tmp", page)
        os.chmod(page, 0o644)
    print(state["updated"], "ok")


if __name__ == "__main__":
    main()
