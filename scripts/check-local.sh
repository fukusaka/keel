#!/usr/bin/env bash
#
# check-local.sh — macOS local validation before PR creation
#
# GitHub Actions CI covers: JVM + linuxX64 (ubuntu-latest)
# This script covers:       macosArm64 (macOS runner not used in CI)
#
# Usage: ./scripts/check-local.sh

set -uo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: This script must be run on macOS." >&2
    exit 1
fi

cd "$(dirname "$0")/.."

PASS=0
FAIL=0

run_step() {
    local label="$1"
    shift
    echo ""
    echo "--- $label"
    if "$@"; then
        echo "    PASS"
        PASS=$((PASS + 1))
    else
        echo "    FAIL"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== keel local check (macOS) ==="
echo "    CI (ubuntu) : JVM + linuxX64"
echo "    This script : macosArm64 + JVM smoke test"

run_step "Compile macosArm64" ./gradlew compileKotlinMacosArm64
run_step "Compile JVM"        ./gradlew compileKotlinJvm
run_step "Test JVM"           ./gradlew jvmTest

echo ""
echo "================================="
echo "Results: ${PASS} passed, ${FAIL} failed"

if [[ $FAIL -gt 0 ]]; then
    exit 1
fi
