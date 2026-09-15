#!/usr/bin/env bash
# run-official-tck-jsonb-3.0.sh — exécute le TCK officiel Jakarta JSON-B 3.0
#
# Usage :
#   ./run-official-tck-jsonb-3.0.sh                    # smoke test (défaut)
#   ./run-official-tck-jsonb-3.0.sh all                # suite complète
#   ./run-official-tck-jsonb-3.0.sh all --static       # mode codegen statique
#   ./run-official-tck-jsonb-3.0.sh -Dtest=Foo         # test ciblé
#
# Le TCK officiel jakarta.json.bind:jakarta-json-bind-tck doit être
# installé dans le M2 local (cf. TCK.md).

set -euo pipefail
cd "$(dirname "$0")"

# `all` is the default: the `smoke` mode below names BasicSmokeTest, which exists
# nowhere in this repository, so the default run failed — silently, until the exit
# code was fixed. Keep the mode for the day a smoke test exists again.
MODE="${1:-all}"
shift || true
STATIC="${1:-}"
TCK_DIR="champollion-tck"
LOG="$TCK_DIR/target/tck-jsonb-output.log"
REPORT="$TCK_DIR/target/tck-report-jsonb.txt"

# Vérification présence TCK dans M2 local
TCK_JAR="$HOME/.m2/repository/jakarta/json/bind/jakarta.json.bind-tck/3.0.0/jakarta.json.bind-tck-3.0.0.jar"
if [ ! -f "$TCK_JAR" ]; then
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "  TCK Jakarta JSON-B 3.0 non trouvé dans le M2 local."
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

# Mode static : recompile les fixtures TCK avec champollion-codegen-apt
if [ "$STATIC" = "--static" ]; then
    echo ">>> 1b. Mode --static : préparation des bindings AOT"
    echo "    (M5.13 : extraction du TCK + recompilation avec JsonbStaticProcessor)"
    echo "    Implémentation reportée — voir TCK.md."
fi

echo ">>> 2. Run TCK JSON-B 3.0 ($MODE)"
mkdir -p "$TCK_DIR/target"

# champollion-tck est in-reactor, activé par le profil Maven `tck`
# (harmonisation TCK, même pattern que les runners vidocq-runtime-tck-*).
# Exit code of the TCK itself. `set -o pipefail` above makes each pipeline below carry
# mvn's status rather than tee's, and the trailing `||` keeps `set -e` from aborting so the
# report is still produced on a red suite. It used to be `|| true`, which also threw the
# status away: the script exited 0 whatever happened, and a run that cannot fail certifies
# nothing (Vidocq/GestionProjet#9).
tck_rc=0

case "$MODE" in
    smoke)
        mvn -B -ntp -P"tck,jsonb-tck" -pl champollion-tck test -Dtest=BasicSmokeTest 2>&1 | tee "$LOG" || tck_rc=1
        ;;
    all)
        mvn -B -ntp -P"tck,jsonb-tck" -pl champollion-tck test 2>&1 | tee "$LOG" || tck_rc=1
        ;;
    *)
        mvn -B -ntp -P"tck,jsonb-tck" -pl champollion-tck test "$MODE" "$@" 2>&1 | tee "$LOG" || tck_rc=1
        ;;
esac

echo ""
echo ">>> 3. Génération du rapport ($REPORT)"
{
    echo "Champollion JSON-B 3.0 TCK Report"
    echo "Generated : $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "Mode      : $MODE${STATIC:+ ($STATIC)}"
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
