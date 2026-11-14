#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

status=0
missing=0
broken=0

echo "==> Documentation front-matter"
while IFS= read -r -d '' file; do
    case "$file" in
        */adr/*) continue ;;
    esac
    if [[ "$(head -n 1 "$file")" != "---" ]]; then
        echo "FAIL missing front-matter: ${file#"$ROOT_DIR/"}" >&2
        missing=$((missing + 1))
    fi
done < <(find docs -type f -name '*.md' -print0)

echo "==> Markdown links"
while IFS= read -r -d '' file; do
    relative_file="${file#"$ROOT_DIR/"}"
    directory="$(dirname "$file")"
    while IFS= read -r link; do
        [[ -z "$link" ]] && continue
        case "$link" in
            http*|mailto:*|\#*) continue ;;
        esac
        target="${link%%#*}"
        [[ -z "$target" ]] && continue
        if [[ "$target" = /* ]]; then
            resolved="$ROOT_DIR$target"
        else
            resolved="$directory/$target"
        fi
        if [[ ! -e "$resolved" ]]; then
            echo "FAIL broken link: $relative_file -> $target" >&2
            broken=$((broken + 1))
        fi
    done < <(
        awk '/^```/ { in_code = !in_code; next } !in_code { print }' "$file" \
            | grep -oE '\]\([^)]*\)' \
            | sed -E 's/^\]\(//; s/\)$//' || true
    )
done < <(find docs -type f -name '*.md' -print0)

echo "front-matter missing: $missing"
echo "broken links: $broken"

if (( missing > 0 || broken > 0 )); then
    exit 1
fi

echo "Documentation validation passed."
