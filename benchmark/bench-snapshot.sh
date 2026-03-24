#!/usr/bin/env bash
# Snapshot benchmark results used in a summary.
#
# Usage: ./benchmark/bench-snapshot.sh <summary-name>
#   e.g.: ./benchmark/bench-snapshot.sh 2026-03-24-phase5b-final
#
# Copies the latest results from all hosts into
# benchmark/results-summary/<summary-name>/ alongside the .md file.
# This preserves the raw wrk output that backs a specific summary.

set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -lt 1 ]; then
    echo "Usage: $0 <summary-name>"
    echo "  e.g.: $0 2026-03-24-phase5b-final"
    exit 1
fi

SUMMARY_NAME="$1"
SNAPSHOT_DIR="benchmark/results-summary/${SUMMARY_NAME}"

mkdir -p "$SNAPSHOT_DIR"

# Copy all host results
if [ -d benchmark/results ]; then
    for host_dir in benchmark/results/*/; do
        if [ -d "$host_dir" ]; then
            cp -r "$host_dir" "$SNAPSHOT_DIR/"
        fi
    done
    echo "Snapshot saved to ${SNAPSHOT_DIR}/"
    ls -R "$SNAPSHOT_DIR/" | head -30
else
    echo "No results found in benchmark/results/"
    exit 1
fi
