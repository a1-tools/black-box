#!/usr/bin/env bash
# 发版：把 release APK 放进仓库 releases/，并生成 update/app-version.json（做法参照 tv 项目）。
#
# 用法:
#   ./gradlew :app:assembleRelease
#   ./scripts/publish-update.sh app/build/outputs/apk/release/BlackBox_4.0.9_universal-release.apk ["更新说明"]
#
# 做了什么:
#   1. 复制 APK 到 releases/BlackBox-<versionName>.apk
#   2. 生成 update/app-version.json：versionCode / versionName 取自 output-metadata.json，
#      sha256 / size 由 APK 算出，url 指向 gh-proxy 包着的 raw 地址（客户端会再展开成多条镜像线路竞速下载）
#   3. releases/ 只保留最近 5 个版本：公共代理会缓存旧清单一段时间，旧清单指向的 APK 被删掉用户就下载失败
# 之后把 releases/ 和 update/app-version.json 一起提交并推送到 main。
#
# 清单只有一个 url，设备可能是 32 位，建议发 universal 包；发单一 ABI 的包会给出警告。
# 同一台机器的 debug 密钥签名的包才能互相覆盖安装；要给别人用请换成自己保管的固定发布密钥。
set -euo pipefail

APK="${1:?用法: publish-update.sh <apkFile> [更新说明]}"
NOTES="${2:-修复已知问题，优化使用体验。}"
REPO_PATH="${REPO_PATH:-a1-tools/black-box}"   # raw 地址里的 <owner>/<repo>
KEEP=5

cd "$(dirname "$0")/.."
[ -f "$APK" ] || { echo "APK 不存在: $APK" >&2; exit 1; }
mkdir -p releases

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
# 先写临时文件，生成成功才替换，失败时不会把现有清单截断成空文件
python3 - "$APK" "$REPO_PATH" "$NOTES" "$KEEP" > "$TMP" <<'PY'
import glob, hashlib, json, os, re, shutil, sys

apk, repo, notes, keep = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
meta_path = "app/build/outputs/apk/release/output-metadata.json"
if not os.path.exists(meta_path):
    sys.exit("找不到 %s：先 ./gradlew :app:assembleRelease" % meta_path)
name = os.path.basename(apk)
element = next((e for e in json.load(open(meta_path))["elements"] if e.get("outputFile") == name), None)
if element is None:
    sys.exit("构建信息里没有 %s：APK 不是最近一次 assembleRelease 的产物？" % name)
if "universal" not in name:
    print("警告：%s 不是 universal 包，另一种 ABI 的设备装不上" % name, file=sys.stderr)

version = element["versionName"]
dest = "releases/BlackBox-%s.apk" % version
shutil.copyfile(apk, dest)
data = open(dest, "rb").read()

# 只保留最近 keep 个版本（按版本号数字排序）
def key(path):
    m = re.search(r"BlackBox-(.+)\.apk$", path)
    return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", m.group(1))] if m else []
olds = sorted(glob.glob("releases/BlackBox-*.apk"), key=key)
for p in olds[:-keep]:
    os.remove(p)
    print("已删除旧版本 %s" % p, file=sys.stderr)

print(json.dumps({
    "versionCode": element["versionCode"],
    "versionName": version,
    "url": "https://gh-proxy.com/https://raw.githubusercontent.com/%s/main/%s" % (repo, dest),
    "sha256": hashlib.sha256(data).hexdigest(),
    "size": len(data),
    "notes": notes,
    "force": False,
}, ensure_ascii=False, indent=2))
PY
mv "$TMP" update/app-version.json
trap - EXIT
echo "已放入 releases/ 并生成 update/app-version.json：" >&2
cat update/app-version.json >&2
echo "下一步：git add releases update/app-version.json && git commit && git push" >&2
