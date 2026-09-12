#!/usr/bin/env bash
set -euo pipefail

ref="${1:-HEAD}"
baseline_file="$(dirname "$0")/attribution-baseline"

cursor_pattern='^(author|committer) .*cursoragent@cursor\.com|^(Co-authored-by|Made-with|Signed-off-by):.*[Cc]ursor'
coauthor_pattern='^Co-authored-by:'

found=0

# Cursor attribution, whole history
while read -r sha; do
  if git cat-file commit "$sha" | grep -qE "$cursor_pattern"; then
    echo "Cursor attribution: $(git log -1 --format='%h %an <%ae> %s' "$sha")"
    found=1
  fi
done < <(git rev-list "$ref")

# Any co-author trailer, commits after the baseline
range="$ref"
if [ -f "$baseline_file" ]; then
  baseline="$(tr -d '[:space:]' < "$baseline_file")"
  if git cat-file -e "$baseline^{commit}" 2>/dev/null; then
    range="$baseline..$ref"
  else
    echo "Baseline $baseline not found, checking whole history for co-author trailers."
  fi
fi

while read -r sha; do
  if git cat-file commit "$sha" | grep -qE "$coauthor_pattern"; then
    echo "Co-authored-by trailer: $(git log -1 --format='%h %s' "$sha")"
    found=1
  fi
done < <(git rev-list "$range")

if [ "$found" -ne 0 ]; then
  echo
  echo "Reauthor to the repository owner and drop the offending trailers."
  exit 1
fi

echo "Attribution clean: $(git rev-list --count "$ref") commits checked for Cursor identity, $(git rev-list --count "$range") for co-author trailers."
