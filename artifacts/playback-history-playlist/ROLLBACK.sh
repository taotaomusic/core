#!/usr/bin/env bash
set -euo pipefail

repo="${1:-$(git rev-parse --show-toplevel)}"
artifact_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$artifact_dir/DIFF_FILE.patch"

cd "$repo"
git apply --reverse --check "$patch_file"
git apply --reverse "$patch_file"
printf 'ROLLBACK_RESULT=source changes restored\n'
