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
# The Native half is not the macOS half: `linuxX64Test` is Kotlin/Native too, so
# four of this workflow's eight invocations are exposed, not two.
#
# Without it, renaming a stress class turns every one of these invocations into a
# silent no-op that reports success.
#
# Usage: run-filtered-test.sh <:module:task> <--tests pattern>
set -euo pipefail

# Both `./gradlew` and the results path are relative to the repository root, and
# the script is two levels below it. Resolving from its own location rather than
# requiring a working directory means a caller outside CI gets the same result.
cd "$(dirname "$0")/../.."

spec="${1:?usage: run-filtered-test.sh <:module:task> <pattern>}"
pattern="${2:?usage: run-filtered-test.sh <:module:task> <pattern>}"

# The results path is derived by string surgery and then handed to `rm -rf`, so
# the shape is checked first. `:jvmTest` alone would derive an empty module and
# delete `/build/test-results/jvmTest` — an absolute path — before `./gradlew`
# ever sees that the spec is wrong. A nested path like `:a:b:task` would derive
# `a:b/…`, which exists nowhere and fails the count for the wrong reason.
case "$spec" in
    :*:*:*) echo "nested project paths are not supported: $spec" >&2; exit 2 ;;
    :?*:?*) ;;
    *) echo "expected :module:task, got: $spec" >&2; exit 2 ;;
esac

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
#
# Only `<testsuite` lines, and every occurrence on each: a bare `match()` reads
# one per line, which undercounts a document written on a single line, and an
# unanchored one would also count a `tests="…"` appearing inside the CDATA of
# `<system-out>`. A document with no line starting at `<testsuite` counts zero
# and fires the guard, which is the safe direction to be wrong in.
ran=$(awk '
    /^[[:space:]]*<testsuite[[:space:]]/ {
        rest = $0
        while (match(rest, /tests="[0-9]+"/)) {
            s += substr(rest, RSTART + 7, RLENGTH - 8)
            rest = substr(rest, RSTART + RLENGTH)
        }
    }
    END { print s + 0 }' "$results"/*.xml 2>/dev/null || echo 0)

if [ "$ran" -eq 0 ]; then
    # stdout, not stderr: GitHub reads workflow commands such as `::error::` from
    # a step's stdout, and the explanation follows it so the annotation and its
    # reason stay together in the log.
    echo "::error::${spec} --tests '${pattern}' selected no tests."
    echo "A filter matching nothing exits 0 on a Kotlin/Native test task, so this would have passed silently."
    echo "Check the pattern against the class names that exist, or drop the invocation if the suite is gone."
    exit 1
fi

echo "${spec} --tests '${pattern}' -> ${ran} test(s)"
