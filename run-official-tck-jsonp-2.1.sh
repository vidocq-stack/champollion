#!/usr/bin/env bash
# run-official-tck-jsonp-2.1.sh — exécute le TCK officiel Jakarta JSON-P 2.1
#
# Usage :
#   ./run-official-tck-jsonp-2.1.sh             # smoke test (défaut)
#   ./run-official-tck-jsonp-2.1.sh all         # suite complète
#   ./run-official-tck-jsonp-2.1.sh -Dtest=Foo  # test ciblé
#
# Le TCK officiel jakarta.json:jakarta-json-tck doit être installé dans
# le M2 local (cf. TCK.md).

set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-smoke}"
TCK_DIR="champollion-tck"
LOG="$TCK_DIR/target/tck-jsonp-output.log"
REPORT="$TCK_DIR/target/tck-report-jsonp.txt"

# Vérification présence TCK dans M2 local
TCK_JAR="$HOME/.m2/repository/jakarta/json/jakarta.json-tck-tests/2.1.0/jakarta.json-tck-tests-2.1.0.jar"
if [ ! -f "$TCK_JAR" ]; then
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "  TCK Jakarta JSON-P 2.1 non trouvé dans le M2 local."
    echo "  Attendu : $TCK_JAR"
    echo ""
    echo "  Lance ./install-tck.sh pour télécharger et installer."
    echo "  Voir aussi TCK.md."
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    exit 78  # EX_CONFIG (skip)
fi

echo ">>> 1. Build Champollion reactor (skip tests)"
./mvnw -ntp install -DskipTests >/dev/null 2>&1 || mvn -ntp install -DskipTests >/dev/null

echo ">>> 2. Run TCK JSON-P 2.1 ($MODE)"
mkdir -p "$TCK_DIR/target"

# champollion-tck est in-reactor, activé par le profil Maven `tck`
# (harmonisation TCK, même pattern que les runners vidocq-runtime-tck-*).
case "$MODE" in
    smoke)
        mvn -B -ntp -P"tck,jsonp-tck" -pl champollion-tck test -Dtest=BasicSmokeTest 2>&1 | tee "$LOG" || true
        ;;
    all)
        # Deux invocations séparées : api/* avec Champollion provider, puis
        # pluggability/* avec MyJsonProvider tiers. Ces deux suites ne peuvent
        # pas coexister sur le même classpath (collision ServiceLoader).
        echo "    [1/2] suite api avec Champollion provider"
        mvn -B -ntp -P"tck,jsonp-tck" -pl champollion-tck test 2>&1 | tee "$LOG" || true
        echo "    [2/2] suite pluggability avec MyJsonProvider"
        mvn -B -ntp -P"tck,jsonp-tck-pluggability" -pl champollion-tck test 2>&1 | tee -a "$LOG" || true
        ;;
    *)
        mvn -B -ntp -P"tck,jsonp-tck" -pl champollion-tck test "$@" 2>&1 | tee "$LOG" || true
        ;;
esac

echo ""
echo ">>> 3. Génération du rapport ($REPORT)"
{
    echo "Champollion JSON-P 2.1 TCK Report"
    echo "Generated : $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "Mode      : $MODE"
    echo ""
    grep -E "Tests run:|FAIL|PASS|ERROR" "$LOG" | tail -50 || echo "(no surefire summary)"
} > "$REPORT"

echo ""
cat "$REPORT"
