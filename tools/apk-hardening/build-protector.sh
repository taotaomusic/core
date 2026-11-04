#!/usr/bin/env bash
# 从固定提交构建 XopProtector 加固引擎（packer jar + shell-files + 许可证）。
#
# 上游：https://github.com/xopJack/XopProtector
# 版本：0.7.9（包含 16KB page 对齐与首页黑屏修复）
#
# 该脚本只在 CI 的 protector-tool job 或本地首次加固时运行；产物 .xop-engine
# 由 CI 缓存/artifact 流转，harden-apk.sh 只消费产物，不再重新构建。
set -euo pipefail

XOP_REPO="https://github.com/xopJack/XopProtector.git"
XOP_TAG="0.7.9"
XOP_COMMIT="b4b3c8ab3c47e536adb324cfafeb5e24964cc2bd"
NDK_VERSION="25.1.8937393"
CMAKE_VERSION="3.22.1"
SDK_PLATFORM="android-34"
BUILD_TOOLS="34.0.0"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT=".xop-engine"
ABI="arm64-v8a"
SOURCE_DIR=""

usage() {
  cat <<'EOF'
用法: build-protector.sh [--out <dir>] [--abi arm64-v8a] [--source-dir <dir>]

  --out        引擎输出目录，默认 .xop-engine
  --abi        只支持 arm64-v8a（桃桃音乐 APK 仅打包该 ABI）
  --source-dir 复用本地 XopProtector 克隆（仅调试；CI 始终按固定提交拉取）

环境要求: JDK 17、Android SDK、网络；脚本会自动安装 NDK/CMake。
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --out)
      [ "$#" -ge 2 ] || { echo "缺少 --out 参数值" >&2; exit 2; }
      OUT="$2"
      shift 2
      ;;
    --abi)
      [ "$#" -ge 2 ] || { echo "缺少 --abi 参数值" >&2; exit 2; }
      ABI="$2"
      shift 2
      ;;
    --source-dir)
      [ "$#" -ge 2 ] || { echo "缺少 --source-dir 参数值" >&2; exit 2; }
      SOURCE_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ "$ABI" != "arm64-v8a" ]; then
  echo "只支持 --abi arm64-v8a（当前 APK 只包含 arm64-v8a）。" >&2
  exit 2
fi

fail() {
  echo "错误: $*" >&2
  exit 1
}

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "找不到 java；请安装 JDK 17 并设置 JAVA_HOME"
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
if [ "$JAVA_MAJOR" != "17" ]; then
  fail "Gradle 8.2 需要 JDK 17，当前为 ${JAVA_MAJOR:-未知}。请把 JAVA_HOME 指向 JDK 17。"
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for candidate in "$HOME/Android/Sdk" "/usr/local/lib/android/sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      SDK="$candidate"
      break
    fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || fail "找不到 Android SDK；请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

SDKMANAGER=""
for d in "$SDK/cmdline-tools"/*/bin "$SDK/tools/bin"; do
  if [ -x "$d/sdkmanager" ]; then
    SDKMANAGER="$d/sdkmanager"
    break
  fi
  if [ -x "$d/sdkmanager.bat" ]; then
    SDKMANAGER="$d/sdkmanager.bat"
    break
  fi
done

need=()
[ -d "$SDK/platforms/$SDK_PLATFORM" ] || need+=("platforms;$SDK_PLATFORM")
[ -d "$SDK/build-tools/$BUILD_TOOLS" ] || need+=("build-tools;$BUILD_TOOLS")
[ -d "$SDK/ndk/$NDK_VERSION" ] || need+=("ndk;$NDK_VERSION")
[ -d "$SDK/cmake/$CMAKE_VERSION" ] || need+=("cmake;$CMAKE_VERSION")
if [ "${#need[@]}" -gt 0 ]; then
  [ -n "$SDKMANAGER" ] || fail "缺少 SDK 组件（${need[*]}），但没有找到 sdkmanager"
  echo "==> 安装 Android SDK 组件: ${need[*]}"
  yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" --sdk_root="$SDK" --install "${need[@]}"
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/xop-protector.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

SRC="$WORK/XopProtector"
if [ -n "$SOURCE_DIR" ]; then
  SRC="$SOURCE_DIR"
  [ -d "$SRC/.git" ] || fail "--source-dir 不是 git 仓库: $SRC"
else
  echo "==> 拉取 XopProtector $XOP_TAG ($XOP_COMMIT)"
  GIT_TERMINAL_PROMPT=0 git clone --depth 1 --branch "$XOP_TAG" "$XOP_REPO" "$SRC"
fi

CURRENT="$(git -C "$SRC" rev-parse HEAD)"
if [ "$CURRENT" != "$XOP_COMMIT" ]; then
  echo "==> HEAD=$CURRENT，切换到固定提交 $XOP_COMMIT"
  if ! GIT_TERMINAL_PROMPT=0 git -C "$SRC" fetch --depth 1 origin "$XOP_COMMIT"; then
    GIT_TERMINAL_PROMPT=0 git -C "$SRC" fetch --depth 1 origin "refs/tags/$XOP_TAG"
  fi
  git -C "$SRC" checkout --detach "$XOP_COMMIT"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$XOP_COMMIT" ] \
  || fail "上游提交校验失败：期望 $XOP_COMMIT"

# 只编译 arm64-v8a：上游默认构建四个 ABI，桃桃音乐 APK 只打包 arm64。
NATIVE_GRADLE="$SRC/native/build.gradle.kts"
[ -f "$NATIVE_GRADLE" ] || fail "找不到 $NATIVE_GRADLE"
if ! grep -Fq 'abiFilters += listOf("arm64-v8a")' "$NATIVE_GRADLE"; then
  sed -i.bak -E 's/^([[:space:]]*)abiFilters \+= listOf\(.*\)/\1abiFilters += listOf("arm64-v8a")/' "$NATIVE_GRADLE"
  rm -f "$NATIVE_GRADLE.bak"
fi
grep -Fq 'abiFilters += listOf("arm64-v8a")' "$NATIVE_GRADLE" \
  || fail "无法把 native ABI 收窄到 arm64-v8a"

GRADLE_PROPS="$SRC/gradle.properties"
if [ -f "$GRADLE_PROPS" ]; then
  if grep -q '^org\.gradle\.jvmargs=' "$GRADLE_PROPS"; then
    sed -i.bak -E 's/^org\.gradle\.jvmargs=.*/org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true/' "$GRADLE_PROPS"
    rm -f "$GRADLE_PROPS.bak"
  else
    printf '\norg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true\n' >> "$GRADLE_PROPS"
  fi
fi

echo "==> 构建 packer 与 shell-files（ABI=$ABI，首次约 10-20 分钟）"
(
  cd "$SRC"
  ./gradlew --no-daemon --console=plain :packer:jar exportShellFiles
)

STAGE="$WORK/engine"
mkdir -p "$STAGE/shell-files" "$STAGE/LICENSES"

JAR="$(find "$SRC/packer/build/libs" -maxdepth 1 -type f -name 'protector-packer-*.jar' -print -quit)"
[ -n "$JAR" ] || fail "没有找到 protector-packer-*.jar"
cp "$JAR" "$STAGE/protector-packer.jar"
cp -R "$SRC/executable/shell-files/." "$STAGE/shell-files/"

[ -f "$STAGE/shell-files/dex/classes.dex" ] || fail "shell-files/dex/classes.dex 缺失"
[ -f "$STAGE/shell-files/libs/$ABI/libprotector.so" ] || fail "shell-files/libs/$ABI/libprotector.so 缺失"

cp "$SRC/LICENSE" "$STAGE/LICENSE"
cp "$SRC/NOTICE" "$STAGE/NOTICE"
cp "$SRC/native/src/main/cpp/external/Dobby/LICENSE" "$STAGE/LICENSES/Dobby-LICENSE.txt"
cp "$SRC/native/src/main/cpp/external/bhook/LICENSE" "$STAGE/LICENSES/bhook-LICENSE.txt"
cp "$SRC/native/src/main/cpp/external/bhook/bytehook/src/main/cpp/third_party/lss/LICENSE" "$STAGE/LICENSES/LSS-LICENSE.txt"
sed -n '1,/^\*\//p' "$SRC/native/src/main/cpp/external/json/json.hpp" > "$STAGE/LICENSES/nlohmann-json-LICENSE.txt"
cp "$SCRIPT_DIR/LICENSES/"* "$STAGE/LICENSES/"

cat > "$STAGE/engine.properties" <<EOF
xop_version=$XOP_TAG
xop_commit=$XOP_COMMIT
abi=$ABI
ndk=$NDK_VERSION
cmake=$CMAKE_VERSION
built_by=tools/apk-hardening/build-protector.sh
EOF

mkdir -p "$(dirname "$OUT")"
rm -rf "$OUT"
mv "$STAGE" "$OUT"

echo "==> 加固引擎已生成: $OUT"
ls -lh "$OUT" "$OUT/shell-files" "$OUT/shell-files/dex" "$OUT/shell-files/libs/$ABI"
