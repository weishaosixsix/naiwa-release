# -*- coding: utf-8 -*-
"""发布脚本:创建 GitHub release 并上传 APK + latest.json。

用法:
  GITHUB_TOKEN_FILE=<token文件> python tools/publish_release.py <版本名,如 1.2.0>

token 获取(一次性设备授权,token 不经过对话):
  1. curl -s -x http://127.0.0.1:10808 -X POST https://github.com/login/device/code \
       -H "Accept: application/json" -d "client_id=178c6fc778ccc68e1d6a&scope=repo"
  2. 把 user_code 输入 https://github.com/login/device
  3. 轮询 login/oauth/access_token 直到拿到 access_token,存文件

上传的附件用固定名(UpdateChecker 的国内兜底依赖这一点):
  yuduoduo-latest.apk —— 应用更新检查的下载地址永远指向它
  latest.json         —— api.github.com 被墙时的检查通道
"""

import json
import os
import sys
import urllib.request

TOKEN = os.environ.get("GITHUB_TOKEN_FILE") and open(
    os.environ["GITHUB_TOKEN_FILE"], encoding="utf-8").read().strip()
if not TOKEN:
    sys.exit("缺少 GITHUB_TOKEN_FILE 环境变量")
if len(sys.argv) < 2:
    sys.exit("用法: python tools/publish_release.py <版本名,如 1.2.0> [说明文件]")
VERSION = sys.argv[1].lstrip("v")
TAG = "v" + VERSION
notes = ""
if len(sys.argv) > 2:
    notes = open(sys.argv[2], encoding="utf-8").read().strip()
PROXY = "http://127.0.0.1:10808"
OWNER, REPO = "weishaosixsix", "naiwa-release"
DIST = os.path.join(os.path.dirname(__file__), "..", "build", "dist")
APK = os.path.join(DIST, "鱼多多-%s.apk" % VERSION)
if not os.path.isfile(APK):
    sys.exit("找不到产物: %s\n先跑 ./gradlew distApk(确认 appVersionName 已抬到 %s)" % (APK, VERSION))

opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}))


def api(url, method="GET", data=None, headers=None, raw=False):
    h = {"Authorization": "Bearer " + TOKEN,
         "Accept": "application/vnd.github+json",
         "User-Agent": "yuduoduo-release"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    with opener.open(req, timeout=120) as r:
        body = r.read()
        return (r.status, body if raw else (json.loads(body) if body else None))


# 1) 建 release(tag 不存在则自动在 main 上创建)
code, rel = api(
    "https://api.github.com/repos/%s/%s/releases" % (OWNER, REPO),
    method="POST",
    data=json.dumps({
        "tag_name": TAG,
        "target_commitish": "main",
        "name": "鱼多多 " + VERSION,
        "body": notes,
    }).encode("utf-8"),
    headers={"Content-Type": "application/json"},
)
print("release 创建: HTTP %s  tag=%s" % (code, rel["tag_name"]))

# 2) 上传 APK(固定名)与 latest.json
latest_json = json.dumps({
    "versionName": VERSION,
    "sizeBytes": os.path.getsize(APK),
    "notes": notes,
}).encode("utf-8")

for name, data, ctype in (
    ("yuduoduo-latest.apk", open(APK, "rb").read(),
     "application/vnd.android.package-archive"),
    ("latest.json", latest_json, "application/json"),
):
    print("上传 %s (%.1f MB)..." % (name, len(data) / 1048576.0))
    code, _ = api(
        "https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s"
        % (OWNER, REPO, rel["id"], name),
        method="POST", data=data,
        headers={"Content-Type": ctype}, raw=True,
    )
    print("  HTTP %s" % code)

# 3) 验证
code, latest = api("https://api.github.com/repos/%s/%s/releases/latest" % (OWNER, REPO))
print("\n=== 验证 /releases/latest ===")
print("tag   :", latest["tag_name"])
for a in latest["assets"]:
    print("资产  : %s  %.1f MB" % (a["name"], a["size"] / 1048576.0))
ok = {a["name"] for a in latest["assets"]}
if {"yuduoduo-latest.apk", "latest.json"} <= ok:
    print("\n发布完成。国内兜底检查通道可用:releases/latest/download/latest.json")
else:
    sys.exit("\n附件不齐,检查上面输出")
