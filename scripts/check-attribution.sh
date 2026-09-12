#!/usr/bin/env bash
set -euo pipefail

ref="${1:-HEAD}"
pattern='^(author|committer) .*cursoragent@cursor\.com|^(Co-authored-by|Made-with|Signed-off-by):.*[Cc]ursor'

found=0
while read -r sha; do
  if git cat-file commit "$sha" | grep -qE "$pattern"; then
    echo "Cursor attribution: $(git log -1 --format='%h %an <%ae> %s' "$sha")"
    found=1
  fi
done < <(git rev-list "$ref")

if [ "$found" -ne 0 ]; then
  echo
  echo "Reauthor these commits to the repository owner and drop any Cursor trailer."
  exit 1
fi

echo "No Cursor attribution found in $(git rev-list --count "$ref") commits."
