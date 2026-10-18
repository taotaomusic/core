#!/usr/bin/env bash
# 维护某个组件 Release 的 body：写入本次版本号 + 本次改动条目，并留下下次聚合用的基准 sha。
#
# 背景：滚动 latest 类 Release 永远只代表「当前这一个版本」，往 body 里堆版本历史索引
# （每行 `- vX.Y.Z — 日期`）既没人看，又会被 webhook 原样搬进后台「更新说明」淹没真实说明。
# 所以 body 只写**这一版**：一行版本号 + 本次真实改动条目。
#
# 更新条目从 git 历史聚合：脚本在上次发布的 body 里存了一行隐藏标记
# `<!-- release-base: <sha> -->`，本次据此取 `git log <上次sha>..HEAD` 的提交标题，
# 滤掉 merge / CI 回写 / [skip ci] 这类噪音。首次发布（或换了新 tag）没有标记时，
# 退回最近若干条提交，至少让首个版本有内容可看。
#
# 浅克隆：CI 里 checkout 默认只取 1 个提交，`git log <base>..HEAD` 会因基准提交不可达
# 而退化。这里按需 `git fetch --deepen` 逐级补历史够用即止；不过 CI 各发布 job 已显式
# `fetch-depth: 0`（本仓库全量历史 pack 仅约 9MB），此分支只作兜底。
#
# 路径过滤：第 4 个及之后的参数全部当作 git pathspec（相对仓库根），只统计本组件相关的
# 改动 —— 桌面端的说明里不该出现后端的提交。不给则统计全部改动。
#
# 用法：release-log.sh <tag> <组件标题> <版本号> [pathspec...]
# 需环境变量 GH_TOKEN / GH_REPO。
#
# 可选环境变量：
#   RELEASE_NOTES_OUT=<路径>   把生成的说明额外写一份到这个文件。
#   RELEASE_NOTES_FROM=<路径>  直接采用该文件作为说明，不再本地聚合。
#   RELEASE_LOG_DRY_RUN=1      只生成、不写入 Release。
# 安卓发布用 OUT/FROM 两步：先 dry-run 生成说明塞进 metadata.json 再上传资产，
# 最后用 FROM 落库，保证 webhook 登记到的说明与 Release 页面完全一致。
set -euo pipefail

tag="$1"
title="$2"
ver="$3"
shift 3
# 其余参数即 pathspec。数组化以正确处理含空格的路径。
pathspec=("$@")

# 无论调用方当前在哪个子目录（crypto-src / server / 仓库根），统一回到仓库根，
# 这样 git log 与 pathspec 的语义才稳定。
cd "$(git rev-parse --show-toplevel)"

# dry-run 阶段不碰 Release（此时可能压根还没建 latest）；正式路径先保证 tag 存在。
if [ "${RELEASE_LOG_DRY_RUN:-}" != "1" ]; then
  gh release view "$tag" >/dev/null 2>&1 || gh release create "$tag" --prerelease --title "$title" --notes "版本历史"
fi

tmp=$(mktemp)

if [ -n "${RELEASE_NOTES_FROM:-}" ]; then
  # 复用上一步已生成好的说明文本：避免再聚合一次，也杜绝 pathspec 两处写法漂移。
  [ -f "${RELEASE_NOTES_FROM}" ] || { echo "RELEASE_NOTES_FROM 指向的文件不存在：${RELEASE_NOTES_FROM}" >&2; exit 1; }
  cp "${RELEASE_NOTES_FROM}" "$tmp"
  entries=""
else
  # 读上次留下的基准 sha。取不到（首次发布 / 旧格式 body / 尚无该 Release）就留空走回退。
  prev=$(gh release view "$tag" --json body -q .body 2>/dev/null || echo "")
  base=$(printf '%s' "$prev" \
    | sed -n 's/.*<!-- *release-base: *\([0-9a-f]\{7,40\}\) *-->.*/\1/p' \
    | head -1)

  # 基准提交可能因浅克隆而本地不可达：逐级加深直到它出现，或到加深上限 / 历史尽头。
  if [ -n "$base" ]; then
    for _ in 0 1 2 3 4; do
      git rev-parse --verify --quiet "${base}^{commit}" >/dev/null 2>&1 && break
      git fetch --deepen=100 --quiet 2>/dev/null || break
      [ -f "$(git rev-parse --git-dir)/shallow" ] || break
    done
  fi

  # 繁忙仓库里这些行对客户端用户毫无意义，属纯噪音，一律不进更新说明。
  noise='^(build: CI 刷新|chore\(release\)|Merge )|\[skip ci\]'

  if [ -n "$base" ] && git rev-parse --verify --quiet "${base}^{commit}" >/dev/null 2>&1; then
    # 正常路径：上次发布到本次的区间。--no-merges 去掉合并提交，避免同一改动重复出现。
    raw=$(git log --no-merges --pretty=format:'%s' "${base}..HEAD" -- "${pathspec[@]}" 2>/dev/null || true)
  else
    # 回退：没有基准 sha，或浅克隆里补不回来。取最近若干条，至少让这一版有内容。
    raw=$(git log --no-merges --pretty=format:'%s' -n 10 -- "${pathspec[@]}" 2>/dev/null || true)
  fi

  # 过滤噪音、去重、限长：更新说明是给人扫一眼的，不是完整 changelog。
  entries=$(printf '%s\n' "$raw" \
    | grep -vE "$noise" \
    | awk 'NF && !seen[$0]++' \
    | head -20 || true)

  # 首行只写当前版本号 + 日期。**没有前导 `- `**，这样 webhook 的版本索引清理正则
  # 不会把它误判成历史噪音摘掉，后台「更新说明」就能正常显示「这个版本 + 日期」。
  {
    echo "v${ver} — $(date -u +%Y-%m-%d)"
    if [ -n "$entries" ]; then
      echo
      printf '%s\n' "$entries" | sed 's/^/- /'
    fi
    echo
    # 供下次聚合定位区间。HTML 注释不参与渲染，后端登记时也会把它摘掉。
    echo "<!-- release-base: $(git rev-parse HEAD) -->"
  } > "$tmp"
fi

# 需要导出时先落文件：这一步与是否写 Release 无关，dry-run 也照样导出。
if [ -n "${RELEASE_NOTES_OUT:-}" ]; then
  cp "$tmp" "$RELEASE_NOTES_OUT"
  echo "说明已导出到 $RELEASE_NOTES_OUT"
fi

if [ "${RELEASE_LOG_DRY_RUN:-}" = "1" ]; then
  echo "已生成 $title 版本 v$ver 的说明（dry-run，未写入 Release）"
else
  gh release edit "$tag" --title "$title" --notes-file "$tmp"
  echo "已记录 $title 版本 v$ver 到 $tag"
fi
rm -f "$tmp"

# 报告本次聚合出的条目。dry-run 只导出、FROM 模式下说明是外部传入的（不重复汇报），
# 这两种情况都不打印，避免误报「没有更新条目」。
if [ "${RELEASE_LOG_DRY_RUN:-}" != "1" ] && [ -z "${RELEASE_NOTES_FROM:-}" ]; then
  if [ -n "$entries" ]; then
    printf '更新条目：\n%s\n' "$entries"
  else
    echo "本次区间内没有可展示的更新条目"
  fi
fi
