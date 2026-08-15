#!/usr/bin/env bash
#
# Runs one filtered Gradle test task and fails if the filter selected nothing.
#
# A `--tests` pattern that matches no test does not fail the build on a
# Kotlin/Native test task: measured 2026-08-15, `:keel-testing-internal:macosArm64Test
# --tests '*NoSuchTestName*'` exits 0 with BUILD SUCCESSFUL and writes no XML
# report. The same pattern against a JVM task exits 1 with "No tests found for
# given includes", which is what Gradle documents (`failOnNoMatchingTests`
# defaults to true) — so the guard is needed only for the Native half, but it is
# applied to both because the caller should not have to know which is which.
#
# Without it, renaming a stress class turns every one of these invocations into a
# silent no-op that reports success.
#
# Usage: run-filtered-test.sh <:module:task> <--tests pattern>
set -euo pipefail

spec="${1:?usage: run-filtered-test.sh <:module:task> <pattern>}"
pattern="${2:?usage: run-filtered-test.sh <:module:task> <pattern>}"

module="${spec%:*}"
module="${module#:}"
task="${spec##*:}"
results="${module}/build/test-results/${task}"

# A previous invocation's XML would satisfy the count below, so the directory
# starts empty. Fresh CI runners have nothing here; local reruns do.
rm -rf "$results"

./gradlew "$spec" --tests "$pattern"

# One awk rather than a grep pipeline: under `set -euo pipefail` a pipeline whose
# first stage finds nothing aborts the script before the check below, which is
# the failure this guard exists to report — it would exit non-zero with no
# message saying why.
ran=$(awk 'match($0, /tests="[0-9]+"/) { s += substr($0, RSTART + 7, RLENGTH - 8) } END { print s + 0 }' \
    "$results"/*.xml 2>/dev/null || echo 0)

if [ "$ran" -eq 0 ]; then
    echo "::error::${spec} --tests '${pattern}' selected no tests." >&2
    echo "A filter matching nothing exits 0 on a Kotlin/Native test task, so this would have passed silently." >&2
    echo "Check the pattern against the class names that exist, or drop the invocation if the suite is gone." >&2
    exit 1
fi

echo "${spec} --tests '${pattern}' -> ${ran} test(s)"
