#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAR_PATH="${ROOT_DIR}/agent/target/agent-1.0-SNAPSHOT-executable.jar"

if [[ ! -f "${JAR_PATH}" ]]; then
    echo "[run-agent] Executable jar not found at ${JAR_PATH}."
    echo "[run-agent] Packaging agent module using Maven wrapper..."
    "${ROOT_DIR}/mvnw" -f "${ROOT_DIR}/pom.xml" -pl agent -am package -DskipTests
fi

JAVA_OPTS="${JAVA_OPTS:-}"
echo "[run-agent] Launching LAN Backup Agent..."
exec java ${JAVA_OPTS} -jar "${JAR_PATH}" "$@"
