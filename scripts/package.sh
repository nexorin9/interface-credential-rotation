#!/bin/sh
# 院内分发打包：JDK 17 jpackage 生成自包含 .app / .exe。
# 用法: sh scripts/package.sh [mac|win]   （Windows 侧需在 Windows + JDK 17 上执行）
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DIST="$ROOT/dist"
DEPS="$ROOT/target/dependency"
JAR="$ROOT/target/interface-credential-rotation.jar"

if [ ! -f "$JAR" ]; then
  echo "缺少 $JAR，请先执行 mvn -q -DskipTests package" >&2
  exit 1
fi

mkdir -p "$DIST"
TARGET=${1:-mac}
case "$TARGET" in
  mac)
    jpackage --type app-image \
      --name "接口账号密钥轮换系统" \
      --input "$DEPS" \
      --main-jar "$(basename "$JAR")" \
      --main-class com.med.icr.Main \
      --module-path "$DEPS" \
      --add-modules javafx.controls,javafx.fxml \
      --dest "$DIST" \
      --java-options "--add-modules javafx.controls"
    echo "已生成: $DIST/接口账号密钥轮换系统.app （拷贝即用，含私有运行时）"
    ;;
  win)
    echo "Windows 目标须在 Windows + JDK 17 + WiX 上执行：把 --type 改为 msi/exe，--dest 指向 dist/。"
    echo "命令模板见 docs/cli.md 的打包小节。"
    ;;
  *)
    echo "未知目标: $TARGET（仅支持 mac / win）" >&2
    exit 1
    ;;
esac
