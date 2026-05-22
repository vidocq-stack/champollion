#!/usr/bin/env bash
# Régénère les records Java annotés pour le conformance TCK depuis src/main/proto/.
#
# Le module champollion-protobuf-tck est HORS reactor (POM Model 4.0.0
# standalone) — le Mojo champollion-protobuf:generate ne peut pas s'y charger
# (résolution AbstractMojo). On invoque donc directement le main CLI
# io.vidocq.champollion.protobuf.codegen.cli.ProtoToJavaCli depuis le jar
# installé en M2 local.
#
# Pré-requis : `cd .. && mvn -ntp -pl champollion-protobuf,champollion-protobuf-codegen install -DskipTests`.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
M2="${HOME}/.m2/repository"
VERSION="0.1.0-SNAPSHOT"

CP="${M2}/io/vidocq/champollion/champollion-protobuf-codegen/${VERSION}/champollion-protobuf-codegen-${VERSION}.jar"
CP+=":${M2}/io/vidocq/champollion/champollion-protobuf/${VERSION}/champollion-protobuf-${VERSION}.jar"

if [[ ! -f "${M2}/io/vidocq/champollion/champollion-protobuf-codegen/${VERSION}/champollion-protobuf-codegen-${VERSION}.jar" ]]; then
    echo "Codegen jar absent du M2 local. Lance :"
    echo "    (cd .. && mvn -ntp -pl champollion-protobuf,champollion-protobuf-codegen install -DskipTests)"
    exit 1
fi

mkdir -p "${HERE}/src/main/java"
exec java -cp "${CP}" \
    io.vidocq.champollion.protobuf.codegen.cli.ProtoToJavaCli \
    "${HERE}/src/main/proto" \
    "${HERE}/src/main/java" \
    io.vidocq.champollion.protobuf.tck.proto3 \
    --static-parser
