#!/usr/bin/env bash
# install-tck.sh — télécharge et installe les TCK officiels Jakarta
# JSON-P 2.1 et JSON-B 3.0 dans le M2 local depuis Eclipse Foundation.
#
# Usage : ./install-tck.sh [jsonp|jsonb|all]    # défaut : all
#
# Idempotent : ne re-télécharge pas si les jars sont déjà dans .m2.

set -euo pipefail
cd "$(dirname "$0")"

WHAT="${1:-all}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

JSONP_URL="https://download.eclipse.org/jakartaee/jsonp/2.1/jakarta-jsonp-tck-2.1.1.zip"
JSONB_URL="https://download.eclipse.org/jakartaee/jsonb/3.0/jakarta-jsonb-tck-3.0.0.zip"

install_artifact() {
    local jar="$1" pom="$2"
    echo "    install:install-file $(basename "$jar")"
    mvn -q -B -ntp install:install-file \
        -Dfile="$jar" -DpomFile="$pom" \
        -DcreateChecksum=true
}

install_jsonp() {
    echo ">>> JSON-P 2.1 TCK"
    if [ -f "$HOME/.m2/repository/jakarta/json/jakarta.json-tck-tests/2.1.1/jakarta.json-tck-tests-2.1.1.jar" ]; then
        echo "    déjà installé. Skip."
        return
    fi
    cd "$TMP"
    echo "    download $JSONP_URL"
    curl -fsSL -o jsonp.zip "$JSONP_URL"
    unzip -q jsonp.zip
    cd jsonp-tck/artifacts
    install_artifact jakarta.json-tck-common-2.1.1.jar       jakarta.json-tck-common-2.1.1.pom
    install_artifact jakarta.json-tck-tests-2.1.1.jar        jakarta.json-tck-tests-2.1.1.pom
    install_artifact jakarta.json-tck-tests-pluggability-2.1.1.jar \
                     jakarta.json-tck-tests-pluggability-2.1.1.pom
    # POM agrégateur (parent)
    mvn -q -B -ntp install:install-file \
        -Dfile=jakarta.json-tck-2.1.1.pom \
        -DpomFile=jakarta.json-tck-2.1.1.pom \
        -Dpackaging=pom \
        -DcreateChecksum=true
    cd "$OLDPWD"
}

install_jsonb() {
    echo ">>> JSON-B 3.0 TCK"
    if [ -f "$HOME/.m2/repository/jakarta/json/bind/jakarta.json.bind-tck/3.0.0/jakarta.json.bind-tck-3.0.0.jar" ]; then
        echo "    déjà installé. Skip."
        return
    fi
    cd "$TMP"
    echo "    download $JSONB_URL"
    curl -fsSL -o jsonb.zip "$JSONB_URL"
    unzip -q jsonb.zip
    cd jsonb-tck/artifacts
    install_artifact jakarta.json.bind-tck-3.0.0.jar jakarta.json.bind-tck-3.0.0.pom
    cd "$OLDPWD"
}

case "$WHAT" in
    jsonp) install_jsonp ;;
    jsonb) install_jsonb ;;
    all)   install_jsonp; install_jsonb ;;
    *)     echo "Usage: $0 [jsonp|jsonb|all]"; exit 64 ;;
esac

echo ""
echo "✅ TCK installation terminée. Vérification :"
ls -1 "$HOME/.m2/repository/jakarta/json/jakarta.json-tck-tests/2.1.1/" 2>/dev/null | grep -E "\.jar$|\.pom$" | sed 's/^/    JSON-P /' || true
ls -1 "$HOME/.m2/repository/jakarta/json/bind/jakarta.json.bind-tck/3.0.0/" 2>/dev/null | grep -E "\.jar$|\.pom$" | sed 's/^/    JSON-B /' || true
