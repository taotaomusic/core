#!/usr/bin/env bash
# 用 XopProtector 引擎加固一个已签名的桃桃音乐 APK。
#
# 产物是独立的 *-protected.apk，不覆盖 canonical APK：
# patch/build.gradle.kts 的 printMethodKeys 仍从
# androidApp/build/outputs/apk/release/androidApp-release.apk 读取插桩方法键。
#
# 默认策略（与已验证的 tide-engine 配置对齐）：
#   - DEX 整体加密，不启用 hollow（避免空方法兼容问题）
#   - 只对 business-prefixes.txt 中的业务类开启 True-VMP
#   - 不加密 SO / assets / res，避免与 crypto JNI、资源读取冲突
#   - RASP: risk_flags=50（禁用 CRC/root/emulator），action=1（降级不杀进程）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INPUT=""
OUTPUT=""
ENGINE=""
KEYSTORE=""
ALIAS="${TAOTAO_KEY_ALIAS:-}"
STOREPASS="${TAOTAO_STORE_PASSWORD:-}"
KEYPASS="${TAOTAO_KEY_PASSWORD:-}"
PREFIXES="$SCRIPT_DIR/business-prefixes.txt"
APPLICATION="com.taotao.music.TaotaoApplication"
REPORT=""
PROFILE="balanced"
RISK_FLAGS="50"
RASP_ACTION="1"

usage() {
  cat <<'EOF'
用法: harden-apk.sh --input <apk> --engine <dir> --keystore <jks> --alias <alias>
                     [--storepass <pass>] [--keypass <pass>] [--output <apk>]
                     [--prefixes <file>] [--application <fqcn>] [--report <file>]
                     [--profile balanced] [--risk-flags 50] [--rasp-action 1]

  --input      待加固的 APK（保留原文件）
  --output     输出路径，默认 <input>-protected.apk
  --engine     build-protector.sh 生成的引擎目录
  --keystore   重签用 keystore；也可用 TAOTAO_KEY_ALIAS 等环境变量
  --storepass  keystore 口令；缺省读 TAOTAO_STORE_PASSWORD
  --keypass    key 口令；缺省读 TAOTAO_KEY_PASSWORD，再缺省复用 storepass
  --prefixes   True-VMP 前缀清单，默认 business-prefixes.txt
  --application 真实 Application 类名，默认 com.taotao.music.TaotaoApplication
  --report     日志与校验报告，默认与输出同目录的 hardening-report.txt
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --input) INPUT="${2:?--input 需要参数}"; shift 2 ;;
    --output) OUTPUT="${2:?--output 需要参数}"; shift 2 ;;
    --engine) ENGINE="${2:?--engine 需要参数}"; shift 2 ;;
    --keystore) KEYSTORE="${2:?--keystore 需要参数}"; shift 2 ;;
    --alias) ALIAS="${2:?--alias 需要参数}"; shift 2 ;;
    --storepass) STOREPASS="${2:?--storepass 需要参数}"; shift 2 ;;
    --keypass) KEYPASS="${2:?--keypass 需要参数}"; shift 2 ;;
    --prefixes) PREFIXES="${2:?--prefixes 需要参数}"; shift 2 ;;
    --application) APPLICATION="${2:?--application 需要参数}"; shift 2 ;;
    --report) REPORT="${2:?--report 需要参数}"; shift 2 ;;
    --profile) PROFILE="${2:?--profile 需要参数}"; shift 2 ;;
    --risk-flags) RISK_FLAGS="${2:?--risk-flags 需要参数}"; shift 2 ;;
    --rasp-action) RASP_ACTION="${2:?--rasp-action 需要参数}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() {
  echo "错误: $*" >&2
  exit 1
}

[ -n "$INPUT" ] || { usage >&2; exit 2; }
[ -f "$INPUT" ] || fail "输入 APK 不存在: $INPUT"
[ -n "$ENGINE" ] || { usage >&2; exit 2; }
[ -n "$KEYSTORE" ] || { usage >&2; exit 2; }
[ -f "$KEYSTORE" ] || fail "keystore 不存在: $KEYSTORE"
[ -n "$ALIAS" ] || fail "缺少 --alias（或 TAOTAO_KEY_ALIAS）"
[ -n "$STOREPASS" ] || fail "缺少 --storepass（或 TAOTAO_STORE_PASSWORD）"
[ -n "$KEYPASS" ] || KEYPASS="$STOREPASS"
[ -f "$PREFIXES" ] || fail "前缀清单不存在: $PREFIXES"

if [ -z "$OUTPUT" ]; then
  OUTPUT="${INPUT%.apk}-protected.apk"
fi
mkdir -p "$(dirname "$OUTPUT")"
input_abs="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"
output_abs="$(cd "$(dirname "$OUTPUT")" && pwd)/$(basename "$OUTPUT")"
[ "$input_abs" != "$output_abs" ] || fail "输出路径不能覆盖输入 APK: $OUTPUT"
if [ -z "$REPORT" ]; then
  REPORT="$(dirname "$OUTPUT")/hardening-report.txt"
fi
mkdir -p "$(dirname "$REPORT")"

JAR="$ENGINE/protector-packer.jar"
SHELL_DIR="$ENGINE/shell-files"
[ -f "$JAR" ] || fail "引擎缺少 protector-packer.jar: $JAR"
[ -f "$SHELL_DIR/dex/classes.dex" ] || fail "引擎缺少 shell-files/dex/classes.dex"
[ -d "$SHELL_DIR/libs/arm64-v8a" ] || fail "引擎缺少 shell-files/libs/arm64-v8a"

VMP_ARGS=()
PREFIX_COUNT=0
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  line="${line#"${line%%[![:space:]]*}"}"
  case "$line" in
    ''|'#'*) continue ;;
    L*/*) ;;
    *) fail "非法 VMP 前缀（应以 L 开头并包含 /）: $line" ;;
  esac
  VMP_ARGS+=("--true-vmp-prefix" "$line")
  PREFIX_COUNT=$((PREFIX_COUNT + 1))
done < "$PREFIXES"
[ "$PREFIX_COUNT" -gt 0 ] || fail "前缀清单为空: $PREFIXES"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/harden-apk.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "==> 加固 APK"
echo "    输入: $INPUT"
echo "    输出: $OUTPUT"
echo "    引擎: $ENGINE"
echo "    VMP 前缀: $PREFIX_COUNT"
echo "    报告: $REPORT"
: > "$REPORT"

set +e
java -jar "$JAR" "$INPUT" -o "$OUTPUT" \
  --shell-dir "$SHELL_DIR" \
  --profile "$PROFILE" \
  --auto-true-vmp off \
  "${VMP_ARGS[@]}" \
  --no-protect-so \
  --no-encrypt-assets \
  --no-res-protect \
  --dex-scan-compat \
  --risk-flags "$RISK_FLAGS" \
  --rasp-action "$RASP_ACTION" \
  --report-enabled 1 \
  --application "$APPLICATION" \
  --keystore "$KEYSTORE" \
  --alias "$ALIAS" \
  --storepass "$STOREPASS" \
  --keypass "$KEYPASS" 2>&1 | tee "$REPORT"
rc=${PIPESTATUS[0]}
set -e
[ "$rc" -eq 0 ] || fail "XopProtector 失败（exit=$rc），详见 $REPORT"
[ -f "$OUTPUT" ] || fail "未生成输出 APK: $OUTPUT"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
find_tool() {
  local name="$1"
  [ -n "$SDK" ] || return 1
  find "$SDK/build-tools" -maxdepth 2 -type f \( -name "$name" -o -name "$name.bat" \) 2>/dev/null \
    | sort -V | tail -1
}
find_apkanalyzer() {
  [ -n "$SDK" ] || return 1
  find "$SDK/cmdline-tools" -maxdepth 4 -type f \
    \( -name apkanalyzer -o -name apkanalyzer.bat \) 2>/dev/null | sort -V | tail -1
}

APKSIGNER="$(find_tool apksigner || true)"
ZIPALIGN="$(find_tool zipalign || true)"
APKANALYZER="$(find_apkanalyzer || true)"
CERT_SHA=""

if [ -n "$APKSIGNER" ]; then
  if ! "$APKSIGNER" verify --verbose --print-certs "$OUTPUT" >> "$REPORT" 2>&1; then
    fail "apksigner verify 失败，详见 $REPORT"
  fi
  # 直接解析首次 verify 写入 report 的输出。Linux 上 apksigner 的 --print-certs
  # 输出可能走 stderr，单独再跑一次并丢弃 stderr 会得到空值。
  CERT_SHA="$(sed -n 's/.*Signer #1 certificate SHA-256 digest:[[:space:]]*//p' "$REPORT" \
    | head -1 | tr -d '\r' | tr 'A-F' 'a-f')"
  [ -n "$CERT_SHA" ] || fail "无法读取签名证书 SHA-256（详见 $REPORT）"
else
  echo "警告: 未找到 apksigner，跳过签名验证" | tee -a "$REPORT"
fi

if [ -n "$ZIPALIGN" ]; then
  if ! "$ZIPALIGN" -c -P 16 4 "$OUTPUT" >> "$REPORT" 2>&1; then
    if "$ZIPALIGN" -c 4 "$OUTPUT" >> "$REPORT" 2>&1; then
      echo "警告: 当前 zipalign 不支持 -P 16，仅通过 4 字节对齐校验" | tee -a "$REPORT"
    else
      fail "zipalign 校验失败，详见 $REPORT"
    fi
  fi
else
  echo "警告: 未找到 zipalign，跳过对齐校验" | tee -a "$REPORT"
fi

command -v unzip >/dev/null 2>&1 || fail "需要 unzip 检查 APK 内容"
unzip -l "$OUTPUT" > "$WORK/list.txt"
grep -Fq 'assets/protector/dexes.zip' "$WORK/list.txt" || fail "缺少 assets/protector/dexes.zip（DEX 未加密？）"
grep -Fq 'assets/protector/config.json' "$WORK/list.txt" || fail "缺少 assets/protector/config.json"
for lib in libprotector.so libshadowhook.so libc++_shared.so; do
  grep -Fq "lib/arm64-v8a/$lib" "$WORK/list.txt" || fail "缺少 lib/arm64-v8a/$lib"
done

SHELL_DEX_SIZE="$(awk '$NF == "classes.dex" {print $1; exit}' "$WORK/list.txt")"
[ -n "$SHELL_DEX_SIZE" ] || fail "APK 中没有 classes.dex"
if [ "$SHELL_DEX_SIZE" -gt 4194304 ]; then
  fail "classes.dex 仍有 ${SHELL_DEX_SIZE} 字节，疑似原业务 DEX 未被收起"
fi
SHELL_DEX_TOTAL="$(awk '$NF ~ /^classes[0-9]*\.dex$/ {sum += $1} END {print sum + 0}' "$WORK/list.txt")"
if [ "$SHELL_DEX_TOTAL" -gt 2097152 ]; then
  fail "根目录 classes*.dex 合计 ${SHELL_DEX_TOTAL} 字节，疑似原业务 DEX 未收进 assets/protector/dexes.zip"
fi

unzip -p "$OUTPUT" assets/protector/config.json > "$WORK/config.json"
node - "$WORK/config.json" "$APPLICATION" "$RISK_FLAGS" "$RASP_ACTION" "$CERT_SHA" <<'NODE'
const fs = require("fs");
const [file, app, risk, rasp, cert] = process.argv.slice(2);
const cfg = JSON.parse(fs.readFileSync(file, "utf8"));
const problems = [];
if (cfg.application_name !== app) {
  problems.push(`application_name=${cfg.application_name}，期望 ${app}`);
}
if (Number(cfg.risk_flags) !== Number(risk)) {
  problems.push(`risk_flags=${cfg.risk_flags}，期望 ${risk}`);
}
if (Number(cfg.rasp_action) !== Number(rasp)) {
  problems.push(`rasp_action=${cfg.rasp_action}，期望 ${rasp}`);
}
if (cfg.protect_so !== false) problems.push("protect_so 应为 false");
if (cfg.encrypt_assets !== false) problems.push("encrypt_assets 应为 false");
if (!cfg.app_sign_sha256) problems.push("app_sign_sha256 缺失");
if (cert && String(cfg.app_sign_sha256).toLowerCase() !== cert.toLowerCase()) {
  problems.push(`app_sign_sha256=${cfg.app_sign_sha256} 与证书 ${cert} 不一致`);
}
if (!cfg.dex_hmac || !cfg.code_hmac) problems.push("dex_hmac/code_hmac 缺失");
if (problems.length) {
  console.error("config.json 校验失败:\n- " + problems.join("\n- "));
  process.exit(1);
}
console.log(`config.json OK: application=${cfg.application_name} risk_flags=${cfg.risk_flags} rasp_action=${cfg.rasp_action}`);
NODE

if [ -n "$APKANALYZER" ]; then
  if ! "$APKANALYZER" manifest print "$OUTPUT" 2>/dev/null \
      | grep -Fq 'com.yqsh.protector.shell.ProxyApplication'; then
    fail "AndroidManifest 未指向 com.yqsh.protector.shell.ProxyApplication"
  fi
  echo "manifest application=ProxyApplication OK" >> "$REPORT"
else
  echo "警告: 未找到 apkanalyzer，跳过 manifest 检查" | tee -a "$REPORT"
fi

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

VMP_COUNT="$(grep -c '^TRUE_VMP Lcom/' "$REPORT" || true)"
POLICY_LINE="$(grep 'True-VMP policy:' "$REPORT" | tail -1 || true)"
ADMISSION_LINE="$(grep 'PVM2 admission:' "$REPORT" | tail -1 || true)"
VMP_SKIP_COUNT="$(grep -c '^TRUE_VMP skip ' "$REPORT" || true)"

{
  echo
  echo "===== 加固校验汇总 ====="
  echo "input=$INPUT"
  echo "output=$OUTPUT"
  echo "input_sha256=$(sha256_of "$INPUT")"
  echo "output_sha256=$(sha256_of "$OUTPUT")"
  echo "signer_sha256=${CERT_SHA:-unknown}"
  echo "vmp_prefixes=$PREFIX_COUNT"
  echo "vmp_methods=$VMP_COUNT"
  echo "vmp_skipped=$VMP_SKIP_COUNT"
  echo "$POLICY_LINE"
  echo "$ADMISSION_LINE"
} | tee -a "$REPORT"

[ "$VMP_COUNT" -gt 0 ] || fail "没有生成任何 TRUE_VMP 方法，检查前缀是否与 R8 后的类名一致"

echo "==> 加固完成: $OUTPUT"
