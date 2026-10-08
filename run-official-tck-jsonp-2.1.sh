#!/usr/bin/env bash
# run-official-tck-jsonp-2.1.sh — runs the official Jakarta JSON-P 2.1 TCK
#
# Usage:
#   ./run-official-tck-jsonp-2.1.sh             # full suite (default, same as `all`)
#   ./run-official-tck-jsonp-2.1.sh all         # full suite
#   ./run-official-tck-jsonp-2.1.sh -Dtest=Foo  # targeted test
#
# The official jakarta.json:jakarta-json-tck TCK must be installed in the
# local M2 repository (see TCK.md).

set -euo pipefail
cd "$(dirname "$0")"

# `all` is the default: the `smoke` mode below names BasicSmokeTest, which exists
# nowhere in this repository, so the default run failed — silently, until the exit
# code was fixed. Keep the mode for the day a smoke test exists again.
MODE="${1:-all}"
TCK_DIR="champollion-tck"
LOG="$TCK_DIR/target/tck-jsonp-output.log"
REPORT="$TCK_DIR/target/tck-report-jsonp.txt"

# Check that the TCK is present in the local M2 repository
TCK_JAR="$HOME/.m2/repository/jakarta/json/jakarta.json-tck-tests/2.1.0/jakarta.json-tck-tests-2.1.0.jar"
if [ ! -f "$TCK_JAR" ]; then
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "  Jakarta JSON-P 2.1 TCK not found in the local M2 repository."
    echo "  Expected: $TCK_JAR"
    echo ""
    echo "  Run ./install-tck.sh to download and install it."
    echo "  See also TCK.md."
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    exit 78  # EX_CONFIG (skip)
fi

echo ">>> 1. Build Champollion reactor (skip tests)"
./mvnw -ntp install -DskipTests >/dev/null 2>&1 || mvn -ntp install -DskipTests >/dev/null

echo ">>> 2. Run TCK JSON-P 2.1 ($MODE)"
mkdir -p "$TCK_DIR/target"

# champollion-tck is in the reactor, enabled by the `tck` Maven profile
# (TCK harmonisation, same pattern as the vidocq-runtime-tck-* runners).
# Exit code of the TCK itself. `set -o pipefail` above makes each pipeline below carry
# mvn's status rather than tee's, and the trailing `||` keeps `set -e` from aborting so the
# report is still produced on a red suite. It used to be `|| true`, which also threw the
# status away: the script exited 0 whatever happened, and a run that cannot fail certifies
# nothing (Vidocq/GestionProjet#9).
tck_rc=0

case "$MODE" in
    smoke)
        mvn -B -ntp -P"tck,jsonp-tck" -pl champollion-tck test -Dtest=BasicSmokeTest 2>&1 | tee "$LOG" || tck_rc=1
        ;;
    all)
        # Two separate invocations: api/* with the Champollion provider, then
        # pluggability/* with the third-party MyJsonProvider. The two suites cannot
        # share one classpath (ServiceLoader collision).
        echo "    [1/2] api suite with the Champollion provider"
        mvn -B -ntp -P"tck,jsonp-tck" -pl champollion-tck test 2>&1 | tee "$LOG" || tck_rc=1
        echo "    [2/2] pluggability suite with MyJsonProvider"
        mvn -B -ntp -P"tck,jsonp-tck-pluggability" -pl champollion-tck test 2>&1 | tee -a "$LOG" || tck_rc=1
        ;;
    *)
        mvn -B -ntp -P"tck,jsonp-tck" -pl champollion-tck test "$@" 2>&1 | tee "$LOG" || tck_rc=1
        ;;
esac

echo ""
echo ">>> 3. Writing the report ($REPORT)"
{
    echo "Champollion JSON-P 2.1 TCK Report"
    echo "Generated : $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "Mode      : $MODE"
    echo ""
    grep -E "Tests run:|FAIL|PASS|ERROR" "$LOG" | tail -50 || echo "(no surefire summary)"
} > "$REPORT"

echo ""
cat "$REPORT"

# The report is printed; now tell the caller the truth.
if [ "$tck_rc" -ne 0 ]; then
    echo ""
    echo "!!! TCK FAILED — see $LOG"
fi
exit "$tck_rc"
