#!/usr/bin/env bash
# Verify docs/contracts/ stays in sync with the code it references.
#
# For each file:line link in docs/contracts/*.md, check that:
#   1. The referenced file exists.
#   2. The line number is in range.
#
# Reports any broken refs and exits non-zero. Run in CI to catch contract
# drift when someone moves code without updating the matching contract doc.
#
# Optional second pass: warn when a referenced file is newer than the
# contract doc referencing it. Off by default (passing local edits would
# trigger noise); pass --strict to enable.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONTRACTS_DIR="$REPO_ROOT/docs/contracts"

if [[ ! -d "$CONTRACTS_DIR" ]]; then
    echo "no contracts directory found at $CONTRACTS_DIR — skipping check"
    exit 0
fi

strict=0
if [[ "${1:-}" == "--strict" ]]; then
    strict=1
fi

errors=0
warnings=0

# Markdown links of the form [label](../../path#L42) or (..#L42-L43)
# Capture the path and optional line / range.
link_pattern='\[[^]]+\]\(\.\.\/\.\.\/[^)]+\)'

for doc in "$CONTRACTS_DIR"/*.md; do
    while IFS= read -r match; do
        # Strip the leading [...] label and the trailing ).
        target="${match##*(}"
        target="${target%)}"
        target="${target#../../}"

        # Split off the #Lxx[-Lyy] fragment.
        if [[ "$target" == *"#L"* ]]; then
            file="${target%%#L*}"
            frag="${target##*#L}"
        else
            file="$target"
            frag=""
        fi

        abs_file="$REPO_ROOT/$file"
        if [[ ! -f "$abs_file" ]]; then
            echo "BROKEN: $doc → $file (file not found)"
            errors=$((errors + 1))
            continue
        fi

        if [[ -n "$frag" ]]; then
            start_line="${frag%%-*}"
            end_line="${frag##*-L}"
            # If there's no '-L' in frag, end_line == frag — same as start.
            [[ "$frag" == *"-L"* ]] || end_line="$start_line"
            total_lines=$(wc -l <"$abs_file" | tr -d ' ')
            if [[ "$start_line" -gt "$total_lines" || "$end_line" -gt "$total_lines" ]]; then
                echo "BROKEN: $doc → $file#L$frag (file has $total_lines lines)"
                errors=$((errors + 1))
                continue
            fi
        fi

        if [[ "$strict" -eq 1 ]]; then
            if [[ "$abs_file" -nt "$doc" ]]; then
                echo "STALE:  $doc references $file but the file is newer (--strict)"
                warnings=$((warnings + 1))
            fi
        fi
    done < <(grep -oE "$link_pattern" "$doc" || true)
done

echo "---"
echo "contract-drift check: $errors broken refs, $warnings stale (strict mode: $strict)"

if [[ "$errors" -gt 0 ]]; then
    exit 1
fi
if [[ "$strict" -eq 1 && "$warnings" -gt 0 ]]; then
    exit 2
fi
exit 0
