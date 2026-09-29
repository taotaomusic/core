#!/usr/bin/env bash
# 维护某个组件 Release 的「版本历史」：把本次版本追加进说明(body)，去重、只留最新若干条。
# 用法：release-log.sh <tag> <组件标题> <版本号>
# 需环境变量 GH_TOKEN / GH_REPO。
set -euo pipefail

tag="$1"
title="$2"
ver="$3"

gh release view "$tag" >/dev/null 2>&1 || gh release create "$tag" --prerelease --title "$title" --notes "版本历史"

prev=$(gh release view "$tag" --json body -q .body 2>/dev/null || echo "")
tmp=$(mktemp)
{
  echo "- v${ver} — $(date -u +%Y-%m-%d)"
  printf '%s\n' "$prev"
} | grep -E '^- v' | awk '!seen[$0]++' | head -80 > "$tmp"

gh release edit "$tag" --title "$title" --notes-file "$tmp"
rm -f "$tmp"
echo "已记录 $title 版本 v$ver 到 $tag"
