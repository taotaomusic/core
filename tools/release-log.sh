#!/usr/bin/env bash
# 维护某个组件 Release 的 body：滚动 latest 类 Release 永远只代表「当前这一个版本」，
# 往 body 里灌版本历史索引（每行 `- vX.Y.Z — 日期`）既没人看，又会被 webhook 原样搬进
# 后台「更新说明」、淹没真实说明。这里直接把 body 写成这一版本身：版本号 + 日期，
# 一行搞定。各端（安卓 / 后端 / 桌面 / 分享播放器 / 加密模块）都调本脚本，行为天然一致。
# 用法：release-log.sh <tag> <组件标题> <版本号>
# 需环境变量 GH_TOKEN / GH_REPO。
set -euo pipefail

tag="$1"
title="$2"
ver="$3"

gh release view "$tag" >/dev/null 2>&1 || gh release create "$tag" --prerelease --title "$title" --notes "版本历史"

tmp=$(mktemp)
# 只写当前版本这一行，不再追加历史。没有前导 `- `，这样 webhook 的索引清理正则不会
# 把它误判成噪音摘掉，后台「更新说明」就能正常显示「这个版本 + 日期」。
echo "v${ver} — $(date -u +%Y-%m-%d)" > "$tmp"

gh release edit "$tag" --title "$title" --notes-file "$tmp"
rm -f "$tmp"
echo "已记录 $title 版本 v$ver 到 $tag"
