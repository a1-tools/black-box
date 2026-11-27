#!/usr/bin/env bash
# 发版辅助：由打好的 release APK 生成 update/app-version.json（做法参照 tv 项目的 make-update-manifest.py）。
#
# 用法:
#   ./scripts/publish-update.sh <apkFile> <apkUrl> ["更新说明"]
#
# 示例:
#   ./gradlew :app:assembleRelease
#   ./scripts/publish-update.sh app/build/outputs/apk/release/BlackBox_4.0.9_universal-release.apk \
#       https://github.com/a1-tools/black-box/releases/download/v4.0.9/BlackBox_4.0.9_universal-release.apk "修复……"
#
# versionCode / versionName 取自构建产物 app/build/outputs/apk/release/output-metadata.json，
# sha256 / size 由 APK 算出（必填，不再允许留空），所以要发布的就是刚打出来的那一版。
# 清单只有一个 url：设备可能是 32 位，建议发 universal 包；发单一 ABI 的包会给出警告。
# 生成后把 APK 上传到 url、把 update/app-version.json 提交推送到 main，已安装的客户端下次检查即可收到更新。
set -euo pipefail

APK="${1:?用法: publish-update.sh <apkFile> <apkUrl> [更新说明]}"
URL="${2:?缺少 apkUrl}"
NOTES="${3:-修复已知问题，优化使用体验。}"

cd "$(dirname "$0")/.."
[ -f "$APK" ] || { echo "APK 不存在: $APK" >&2; exit 1; }
case "$URL" in http://*|https://*) ;; *) echo "apkUrl 必须是 http(s) 地址: $URL" >&2; exit 1;; esac

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
# 先写临时文件，生成成功才替换，失败时不会把现有清单截断成空文件
python3 - "$APK" "$URL" "$NOTES" > "$TMP" <<'PY'
import hashlib, json, os, sys

apk, url, notes = sys.argv[1:4]
meta_path = "app/build/outputs/apk/release/output-metadata.json"
if not os.path.exists(meta_path):
    sys.exit("找不到 %s：先 ./gradlew :app:assembleRelease" % meta_path)
name = os.path.basename(apk)
element = next((e for e in json.load(open(meta_path))["elements"] if e.get("outputFile") == name), None)
if element is None:
    sys.exit("构建信息里没有 %s：APK 不是最近一次 assembleRelease 的产物？" % name)
if "universal" not in name:
    print("警告：%s 不是 universal 包，另一种 ABI 的设备装不上" % name, file=sys.stderr)
data = open(apk, "rb").read()
print(json.dumps({
    "versionCode": element["versionCode"],
    "versionName": element["versionName"],
    "url": url,
    "sha256": hashlib.sha256(data).hexdigest(),
    "size": len(data),
    "notes": notes,
    "force": False,
}, ensure_ascii=False, indent=2))
PY
mv "$TMP" update/app-version.json
trap - EXIT
echo "已生成 update/app-version.json：" >&2
cat update/app-version.json >&2
echo "下一步：上传 APK 到 url，然后 git add update/app-version.json && git commit && git push" >&2
