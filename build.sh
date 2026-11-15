#!/bin/bash
# 构建脚本：版本号自增 → 静态校验 → 单测 + 打包
#
# 注意：gradle 的输出不能直接接管道。管道会把退出码换成最后一个命令（grep）的，
# 测试失败也会判成功并出包——之前就出过一次这样的包。
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

mkdir -p build
GRADLE_LOG=build/gradle.log
set +e
~/tools/gradle-8.11.1/bin/gradle :app:testDebugUnitTest :app:assembleRelease \
  --no-daemon > "$GRADLE_LOG" 2>&1
GRADLE_STATUS=$?
set -e

grep -E 'error:|FAILED|tests completed|BUILD' -A3 "$GRADLE_LOG" | head -40 || true

if [ "$GRADLE_STATUS" -ne 0 ]; then
  echo "构建失败（退出码 $GRADLE_STATUS），完整日志: $GRADLE_LOG"
  exit 1
fi

cp app/build/outputs/apk/release/app-release.apk "sk5-v${NEXT}.apk"
echo "输出: sk5-v${NEXT}.apk"
ls -lh "sk5-v${NEXT}.apk"
