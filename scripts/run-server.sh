#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAR_PATH="${ROOT_DIR}/server/target/server-1.0-SNAPSHOT-executable.jar"

if [[ ! -f "${JAR_PATH}" ]]; then
    echo "[run-server] Executable jar not found at ${JAR_PATH}."
    echo "[run-server] Packaging server module using Maven wrapper..."
    "${ROOT_DIR}/mvnw" -f "${ROOT_DIR}/pom.xml" -pl server -am package -DskipTests
fi

JAVA_OPTS="${JAVA_OPTS:-}"
echo "[run-server] Launching LAN Backup Server..."
exec java ${JAVA_OPTS} -jar "${JAR_PATH}" "$@"
