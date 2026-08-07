#!/usr/bin/env bash
# ==============================================================================
# Eturlia core self-test
# ==============================================================================
# Compiles and runs the dependency-free checks in
# build-data/eturlia-core/src/test/java (compatibility-manifest JSON reader,
# eturlia.yml subset parser, launcher version comparison).
#
# The server itself cannot be unit tested here: patch 0008 removes the upstream
# test suite and the runtime needs a full Folia/NeoForge classpath. These checks
# cover the logic that is testable in isolation.
#
# Usage: scripts/selftest.sh          (run from the repository root)
# Exit code 0 = all checks passed.
# ==============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAVA="$JAVA_HOME/bin/java"
fi

if ! command -v "$JAVAC" >/dev/null 2>&1 && [ ! -x "$JAVAC" ]; then
    echo "ERROR: javac not found. Set JAVA_HOME to a JDK 21+ installation." >&2
    exit 1
fi

OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/eturlia-selftest.XXXXXX")"
trap 'rm -rf "$OUT_DIR"' EXIT

echo "=== Compiling Eturlia core + self-test ==="
# eturlia/launch/* needs the FML/ModLauncher classpath, so only the runtime core,
# the launcher and the tests are compiled here.
# shellcheck disable=SC2046
"$JAVAC" --release 21 -nowarn -d "$OUT_DIR" \
    $(find build-data/eturlia-core/src/main/java/eturlia/core -name '*.java') \
    $(find build-data/eturlia-core/src/test/java -name '*.java') \
    $(find build-data/eturlia-launcher/src/main/java -name '*.java') \
    $(find build-data/eturlia-launcher/src/test/java -name '*.java') \
    $(find build-data/eturlia-server-templates/src/main/java -name '*.java') \
    $(find build-data/eturlia-server-templates/src/test/java -name '*.java')

echo
echo "=== eturlia.core self-test ==="
"$JAVA" -cp "$OUT_DIR" eturlia.core.loading.EturliaCoreSelfTest

echo
echo "=== eturlia.launcher self-test ==="
"$JAVA" -cp "$OUT_DIR" eturlia.launcher.EturliaLauncherSelfTest

echo
echo "=== config: every documented key ==="
"$JAVA" -cp "$OUT_DIR" eturlia.core.loading.EturliaConfigSelfTest

echo
echo "=== concurrency / stress ==="
"$JAVA" -cp "$OUT_DIR" eturlia.core.loading.EturliaStressTest

echo
echo "=== runtime boot (dev mode, no Minecraft) ==="
# Own JVM: this one installs log handlers and a shutdown hook, and replaces
# System.out/err to capture the startup console.
"$JAVA" -cp "$OUT_DIR" eturlia.EturliaRuntimeSelfTest
