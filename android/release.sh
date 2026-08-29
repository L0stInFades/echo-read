#!/bin/bash
# EchoRead 安卓发布脚本：打正式签名包 → 生成 android/update.json（应用内更新清单）→ 提交 → 建 GitHub Release 并上传 APK
# 用法：./release.sh <versionName> <versionCode> "<更新说明，可多行>"
#   例：./release.sh 0.1.2 3 "修复翻页闪烁；新增音量键翻页"
# 依赖：JDK 17+、Android SDK、gh（已 gh auth login）、keystore.properties（正式签名）
set -euo pipefail
cd "$(dirname "$0")"
VER="${1:?versionName}"; CODE="${2:?versionCode}"; NOTES="${3:-}"
REPO="L0stInFades/echo-read"; TAG="android-v$VER"; APK="dist/EchoRead-v$VER.apk"
[ -f keystore.properties ] || { echo "缺少 keystore.properties（正式签名）"; exit 1; }
sed -i '' "s/^APP_VERSION_CODE=.*/APP_VERSION_CODE=$CODE/; s/^APP_VERSION_NAME=.*/APP_VERSION_NAME=$VER/" gradle.properties
./gradlew :app:assembleRelease --console=plain -q
mkdir -p dist && cp app/build/outputs/apk/release/app-release.apk "$APK"
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
python3 - "$VER" "$CODE" "$SHA" "$NOTES" "$REPO" "$TAG" "$(basename "$APK")" <<'PY'
import json, sys
ver, code, sha, notes, repo, tag, name = sys.argv[1:8]
json.dump({
  "versionCode": int(code), "versionName": ver,
  "apkUrl": f"https://github.com/{repo}/releases/download/{tag}/{name}",
  "mirrors": [f"https://ghproxy.net/https://github.com/{repo}/releases/download/{tag}/{name}"],
  "notes": notes, "sha256": sha, "minSdk": 26
}, open("update.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
echo "APK: $APK  sha256=$SHA"
cd ..
git add android/update.json android/gradle.properties
git commit -q -m "release(android): v$VER ($CODE)" || true
git push origin HEAD
gh release create "$TAG" "android/$APK" --repo "$REPO" --title "EchoRead Android v$VER" --notes "${NOTES:-EchoRead Android v$VER}"
# 刷新 jsDelivr 缓存，让新清单尽快可见
curl -s "https://purge.jsdelivr.net/gh/$REPO@main/android/update.json" >/dev/null || true
echo "发布完成：$TAG"
