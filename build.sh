#!/bin/bash
# 构建脚本：版本号自增 → 静态校验 → 单测 + 打包
set -e

cd "$(dirname "$0")"

VERSION_FILE="version.properties"
CURRENT=$(grep '^version.code=' "$VERSION_FILE" | cut -d= -f2)
NEXT=$((CURRENT + 1))
printf 'version.code=%s\nversion.name=1.%s\n' "$NEXT" "$NEXT" > "$VERSION_FILE"

echo "版本: $CURRENT -> $NEXT"

node tools/verify.js

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=/root/android/sdk
export PATH="$JAVA_HOME/bin:$PATH"

~/tools/gradle-8.11.1/bin/gradle :app:testDebugUnitTest :app:assembleRelease \
  --no-daemon 2>&1 \
  | grep -E 'error:|FAILED|tests completed|BUILD' -A3 | head -40

cp app/build/outputs/apk/release/app-release.apk "sk5-v${NEXT}.apk"
echo "输出: sk5-v${NEXT}.apk"
ls -lh "sk5-v${NEXT}.apk"
