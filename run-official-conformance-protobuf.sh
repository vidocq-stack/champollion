#!/usr/bin/env bash
# run-official-conformance-protobuf.sh
#
# Lance le `conformance_test_runner` officiel de Google contre notre
# implémentation Champollion. Pipe stdin/stdout suivant le protocole
# documenté dans https://github.com/protocolbuffers/protobuf/blob/main/conformance/README.md
#
# Modes :
#   ./run-official-conformance-protobuf.sh             # smoke par défaut
#   ./run-official-conformance-protobuf.sh smoke       # idem
#   ./run-official-conformance-protobuf.sh all         # exécute toute la suite
#   ./run-official-conformance-protobuf.sh --editions  # cible Editions 2023
#
# Prérequis :
#   - Java 25 + Maven 4.0.0-rc-5 (cf. .sdkmanrc)
#   - Le binaire `conformance_test_runner` accessible via $CONFORMANCE_TEST_RUNNER
#     (sinon : voir section "Installation du runner Google" plus bas)
#
# Le rapport est écrit dans champollion-protobuf-tck/target/conformance-report.txt

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCK_DIR="$SCRIPT_DIR/champollion-protobuf-tck"
REPORT="$TCK_DIR/target/conformance-report.txt"

MODE="${1:-smoke}"

if ! command -v java >/dev/null || ! command -v mvn >/dev/null; then
    echo "ERREUR : java et/ou mvn non trouvés ; lance 'sdk env' dans champollion/" >&2
    exit 1
fi

echo ">>> Install local reactor (sans tests)..."
(cd "$SCRIPT_DIR" && mvn -ntp install -DskipTests -q)

echo ">>> Build champollion-protobuf-tck (standalone)..."
(cd "$TCK_DIR" && mvn -ntp package -DskipTests -q)

RUNNER="${CONFORMANCE_TEST_RUNNER:-}"
if [ -z "$RUNNER" ] || [ ! -x "$RUNNER" ]; then
    cat >&2 <<EOF

==============================================================================
  conformance_test_runner Google introuvable.

  Pour le builder :
    git clone https://github.com/protocolbuffers/protobuf.git /tmp/protobuf-src
    cd /tmp/protobuf-src
    bazel build //conformance:conformance_test_runner
    # Le binaire est dans bazel-bin/conformance/conformance_test_runner

  Puis exporter :
    export CONFORMANCE_TEST_RUNNER=/chemin/vers/conformance_test_runner

  Et relancer ce script.
==============================================================================
EOF
    echo ">>> Mode dégradé : lancement du wrapper Java seul (smoke pipe self-test)"
    mkdir -p "$TCK_DIR/target"
    {
        echo "$(date) — Conformance Runner dégradé (sans test_runner Google)"
        cd "$TCK_DIR"
        mvn -ntp exec:java -Dexec.mainClass=io.vidocq.champollion.protobuf.conformance.ConformanceRunner -q </dev/null 2>&1 || true
    } | tee "$REPORT"
    exit 0
fi

mkdir -p "$TCK_DIR/target"
ARGS=()
case "$MODE" in
    smoke) ARGS+=(--maximum_edition PROTO3) ;;
    all)   ;;
    --editions) ARGS+=(--maximum_edition 2023) ;;
    *) echo "Mode inconnu: $MODE (smoke|all|--editions)" >&2; exit 2 ;;
esac

JAR="$TCK_DIR/target/champollion-protobuf-tck-0.1.0-SNAPSHOT.jar"
echo ">>> Conformance $MODE via $RUNNER"
"$RUNNER" "${ARGS[@]}" -- java -jar "$JAR" 2>&1 | tee "$REPORT"

echo "Rapport : $REPORT"
