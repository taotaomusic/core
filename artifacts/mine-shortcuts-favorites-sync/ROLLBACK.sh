#!/usr/bin/env bash
set -euo pipefail

repo="${1:-$(git rev-parse --show-toplevel)}"
artifact_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$artifact_dir/DIFF_FILE.patch"

cd "$repo"
git apply --unidiff-zero --reverse --check "$patch_file"
git apply --unidiff-zero --reverse "$patch_file"

# 这些文件在原工作区是 LF；Windows 的 core.autocrlf 会让 git apply 恢复成 CRLF，
# 显式还原行尾后再刷新索引，确保字节哈希与回滚前基线完全一致。
lf_files=(
  "androidApp/src/main/java/com/taotao/music/data/FavoritesStore.kt"
  "androidApp/src/main/java/com/taotao/music/ui/TaotaoMusicApp.kt"
  "androidApp/src/main/java/com/taotao/music/ui/MineLibraryPages.kt"
)
sed -i 's/\r$//' "${lf_files[@]}"
git add -- "${lf_files[@]}"
git diff --cached --quiet -- "${lf_files[@]}"
printf 'ROLLBACK_RESULT=mine shortcuts and favorite source restored\n'
