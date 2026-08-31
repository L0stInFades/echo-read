#!/bin/bash
# EchoRead 安卓发布脚本：打正式签名包 → 生成 android/update.json（应用内更新清单）→ 提交 → 建 GitHub Release 并上传 APK
# 用法：./release.sh <versionName> <versionCode> "<更新说明，可多行>" [exp]
#   例：./release.sh 0.1.2 3 "修复翻页闪烁；新增音量键翻页"
#   例：./release.sh 0.2.0-exp 8 "Material 3 Expressive 改版" exp   # 第 4 个参数为 exp 时标记为实验版
# 实验版会在 update.json 里写 "experimental": true，客户端据此在更新卡片上打「实验版」标记并加风险提示。
# （该字段对 0.1.x 旧客户端安全：旧版解析器带 ignoreUnknownKeys=true，会直接忽略。）
# 依赖：JDK 17+、Android SDK、gh（已 gh auth login）、keystore.properties（正式签名）
set -euo pipefail
cd "$(dirname "$0")"
VER="${1:?versionName}"; CODE="${2:?versionCode}"; NOTES="${3:-}"; EXP="${4:-}"
REPO="L0stInFades/echo-read"; TAG="android-v$VER"; APK="dist/EchoRead-v$VER.apk"
[ -f keystore.properties ] || { echo "缺少 keystore.properties（正式签名）"; exit 1; }
sed -i '' "s/^APP_VERSION_CODE=.*/APP_VERSION_CODE=$CODE/; s/^APP_VERSION_NAME=.*/APP_VERSION_NAME=$VER/" gradle.properties
./gradlew :app:assembleRelease --console=plain -q
mkdir -p dist && cp app/build/outputs/apk/release/app-release.apk "$APK"
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
python3 - "$VER" "$CODE" "$SHA" "$NOTES" "$REPO" "$TAG" "$(basename "$APK")" "$EXP" <<'PY'
import json, sys
ver, code, sha, notes, repo, tag, name, exp = sys.argv[1:9]
experimental = exp == "exp" or any(s in ver for s in ("-exp", "-alpha", "-beta"))
# 关键：0.1.x 的老客户端只渲染 notes，看不到下面新增的 experimental 字段。
# 所以实验版的风险提示必须**写进 notes 本身**，否则存量用户那边只会看到一条普通更新。
if experimental and "实验版" not in notes:
    notes = ("【实验版】改动较大，可能有未发现的问题。不想升级可以点更新卡片上的「稍后再说 / ✕」。\n" + notes).strip()
manifest = {
  "versionCode": int(code), "versionName": ver,
  "apkUrl": f"https://github.com/{repo}/releases/download/{tag}/{name}",
  # 镜像按实测可达性排序（ghproxy.net 与 mirror.ghproxy.com 已失效，直接连接被拒）。
  # 客户端会按顺序逐个尝试，首字节 12s 未到就换下一个。
  "mirrors": [f"https://{h}/https://github.com/{repo}/releases/download/{tag}/{name}"
              for h in ("gh-proxy.com", "ghfast.top", "gh.llkk.cc")],
  "notes": notes, "sha256": sha, "minSdk": 26,
}
if experimental:
    manifest["experimental"] = True
json.dump(manifest, open("update.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
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
