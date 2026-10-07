#!/usr/bin/env bash
# What changed in one app since its last release: the commits that touched its own files.
#   notes.sh <tag pattern> <this tag> <path>...   e.g. notes.sh 'lg-v*' lg-v0.58.0 webos
# Writes notes.md. Before an app had releases of its own, all of them shared the v* tags,
# so the last of those is where its history starts.
set -euo pipefail
pattern="$1"; this="$2"; shift 2
previous=$(git tag -l "$pattern" --sort=-v:refname | grep -vx "$this" | head -1 || true)
if [ -z "$previous" ]; then
  previous=$(git tag -l 'v*' --sort=-v:refname | grep -vx "$this" | head -1 || true)
fi
if [ -n "$previous" ]; then
  echo "Changes since $previous:" > notes.md
  echo >> notes.md
  git log --no-merges --pretty='- %s' "$previous..HEAD" -- "$@" >> notes.md
else
  echo "Recent changes:" > notes.md
  echo >> notes.md
  git log --no-merges --pretty='- %s' -25 -- "$@" >> notes.md
fi
echo >> notes.md
echo "Built from \`${GITHUB_SHA:-$(git rev-parse HEAD)}\`." >> notes.md
