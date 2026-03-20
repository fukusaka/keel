#!/usr/bin/env bash
#
# bench-compare.sh — Compare wrk benchmark results across engines
#
# Usage:
#   ./scripts/bench-compare.sh                     # Latest results
#   ./scripts/bench-compare.sh 20260320-143000     # Specific timestamp
#
# Reads wrk output files from benchmark/results/ and produces a comparison table.

set -euo pipefail

cd "$(dirname "$0")/.."

RESULTS_DIR="benchmark/results"
TIMESTAMP="${1:-}"

if [ ! -d "$RESULTS_DIR" ] || [ -z "$(ls -A "$RESULTS_DIR" 2>/dev/null)" ]; then
    echo "No results found in $RESULTS_DIR/"
    echo "Run ./scripts/bench-run.sh first."
    exit 1
fi

# Auto-detect latest timestamp if not specified
if [ -z "$TIMESTAMP" ]; then
    TIMESTAMP=$(ls "$RESULTS_DIR"/*.txt 2>/dev/null | sed 's/.*-\([0-9]\{8\}-[0-9]\{6\}\)\.txt/\1/' | sort -u | tail -1)
fi

if [ -z "$TIMESTAMP" ]; then
    echo "No results found for any timestamp." >&2
    exit 1
fi

echo "=== keel benchmark results ($TIMESTAMP) ==="
echo ""

extract_metric() {
    local file="$1"
    local metric="$2"

    if [ ! -f "$file" ]; then
        echo "—"
        return
    fi

    case "$metric" in
        rps)
            grep "Requests/sec" "$file" 2>/dev/null | awk '{print $2}' || echo "—"
            ;;
        lat50)
            grep "50%" "$file" 2>/dev/null | awk '{print $2}' || echo "—"
            ;;
        lat99)
            grep "99%" "$file" 2>/dev/null | awk '{print $2}' || echo "—"
            ;;
        errors)
            local errs
            errs=$(grep "Socket errors" "$file" 2>/dev/null | head -1 || echo "")
            if [ -z "$errs" ]; then
                echo "0"
            else
                echo "$errs" | sed 's/.*connect \([0-9]*\), read \([0-9]*\), write \([0-9]*\), timeout \([0-9]*\)/c:\1 r:\2 w:\3 t:\4/'
            fi
            ;;
    esac
}

ENGINES=("keel-nio" "keel-netty" "ktor-cio" "ktor-netty" "spring" "vertx")
SCENARIOS=("hello" "large")
PROFILES=("low" "high" "ultra")
PROFILE_LABELS=("2t/10c/10s" "4t/100c/10s" "4t/500c/10s")

for i in "${!SCENARIOS[@]}"; do
    scenario="${SCENARIOS[$i]}"
    for j in "${!PROFILES[@]}"; do
        profile="${PROFILES[$j]}"
        label="${PROFILE_LABELS[$j]}"

        echo "Scenario: /${scenario} (${label})"
        printf "%-14s %12s %10s %10s %s\n" "Engine" "Req/sec" "Lat p50" "Lat p99" "Errors"
        printf "%-14s %12s %10s %10s %s\n" "--------------" "------------" "----------" "----------" "------"

        for engine in "${ENGINES[@]}"; do
            file="${RESULTS_DIR}/${engine}-${scenario}-${profile}-${TIMESTAMP}.txt"
            rps=$(extract_metric "$file" "rps")
            lat50=$(extract_metric "$file" "lat50")
            lat99=$(extract_metric "$file" "lat99")
            errors=$(extract_metric "$file" "errors")
            printf "%-14s %12s %10s %10s %s\n" "$engine" "$rps" "$lat50" "$lat99" "$errors"
        done
        echo ""
    done
done
