#!/usr/bin/env bash
set -euo pipefail

repo="${1:-$(git rev-parse --show-toplevel)}"
artifact_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$artifact_dir/DIFF_FILE.patch"

cd "$repo"
git apply --unidiff-zero --reverse --check "$patch_file"
git apply --unidiff-zero --reverse "$patch_file"

# 这些文件在原工作区使用 LF；显式恢复行尾并刷新索引，保证回滚哈希完全一致。
lf_files=(
  "androidApp/src/main/java/com/taotao/music/ui/Theme.kt"
  "androidApp/src/main/java/com/taotao/music/ui/QualitySheet.kt"
  "androidApp/src/main/java/com/taotao/music/ui/SettingsPage.kt"
  "androidApp/src/main/java/com/taotao/music/ui/TaotaoMusicApp.kt"
)
sed -i 's/\r$//' "${lf_files[@]}"
git add -- "${lf_files[@]}"
git diff --cached --quiet -- "${lf_files[@]}"
printf 'ROLLBACK_RESULT=dark theme color fields restored\n'
