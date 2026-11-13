#!/usr/bin/env bash
# 把纯逻辑层的 PathMap 编成 app_process 能直接跑的 dexjar，推上模拟器扫真实目录树。
#
# 用途：不依赖 Xposed 注入，也能回答「这个 App 的哪些路径会被重定向、哪些漏了」，
# 同时作为映射规则的回归工具（映射越界 / 幂等性破坏会直接 FAIL）。
#
# 用法：tools/build-pathmap-cli.sh [包名]
set -euo pipefail

PKG="${1:-com.android.deskclock}"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
ADB="$ANDROID_HOME/platform-tools/adb -s $SERIAL"
SRC="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/java/com/xavier/subdata"

echo "==> build-tools: $BT"
echo "==> serial:      $SERIAL"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "==> javac（PathMap / SlotConfig 不依赖 Android）"
javac -encoding UTF-8 -d "$OUT/classes" \
    "$SRC/PathMap.java" "$SRC/SlotConfig.java"

echo "==> d8 -> classes.dex"
find "$OUT/classes" -name '*.class' -print0 | xargs -0 "$BT/d8" --min-api 26 --output "$OUT"
cp "$OUT/classes.dex" "$OUT/pathmap.jar"

echo "==> push 到设备"
$ADB push "$OUT/pathmap.jar" /data/local/tmp/pathmap.jar
HERE="$(cd "$(dirname "$0")" && pwd)"
$ADB push "$HERE/pathmap-run.sh" /data/local/tmp/pathmap-run.sh
$ADB shell chmod 755 /data/local/tmp/pathmap-run.sh

echo "==> 在设备上扫 $PKG"
$ADB shell sh /data/local/tmp/pathmap-run.sh "$PKG"
