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

# Three classes in eturlia.core compile against APIs the runtime brings but this job does
# not: the log filter needs log4j-api, the mixin error handler needs SpongePowered Mixin,
# and the launch-plugin guard needs ModLauncher. They are small, stable, single-jar APIs, so
# fetch them rather than dropping those sources from the check - EturliaConfig and
# EturliaServer reference them, so excluding them would take the runtime self-test with it.
LIB_DIR="$OUT_DIR/lib"
mkdir -p "$LIB_DIR"
MAVEN="${MAVEN_CENTRAL:-https://repo1.maven.org/maven2}"
NEO="${MAVEN_NEOFORGED:-https://maven.neoforged.net/releases}"
fetch_api () {
    local name="$1" url="$2"
    if [ -f "$LIB_DIR/$name" ]; then return 0; fi
    curl -fsSL --retry 3 --max-time 120 -o "$LIB_DIR/$name" "$url" || {
        echo "ERROR: could not fetch $name from $url" >&2
        echo "       set MAVEN_CENTRAL to a reachable mirror, or run the full gradle build" >&2
        return 1
    }
}
fetch_api log4j-api.jar "$MAVEN/org/apache/logging/log4j/log4j-api/2.22.1/log4j-api-2.22.1.jar"
fetch_api log4j-core.jar "$MAVEN/org/apache/logging/log4j/log4j-core/2.22.1/log4j-core-2.22.1.jar"
fetch_api mixin.jar     "$NEO/net/fabricmc/sponge-mixin/0.15.2%2Bmixin.0.8.7/sponge-mixin-0.15.2%2Bmixin.0.8.7.jar"
fetch_api modlauncher.jar "$NEO/cpw/mods/modlauncher/11.0.3/modlauncher-11.0.3.jar"
API_CP="$LIB_DIR/log4j-api.jar:$LIB_DIR/log4j-core.jar:$LIB_DIR/mixin.jar:$LIB_DIR/modlauncher.jar"

echo "=== Compiling Eturlia core + self-test ==="
# eturlia/launch/* needs the full FML classpath, so it stays out; everything else compiles
# against the three API jars fetched above.
# shellcheck disable=SC2046
"$JAVAC" --release 21 -nowarn -proc:none -cp "$API_CP" -d "$OUT_DIR" \
    $(find build-data/eturlia-core/src/main/java/eturlia/core -name '*.java') \
    $(find build-data/eturlia-core/src/test/java -name '*.java') \
    $(find build-data/eturlia-launcher/src/main/java -name '*.java') \
    $(find build-data/eturlia-launcher/src/test/java -name '*.java') \
    $(find build-data/eturlia-server-templates/src/main/java -name '*.java') \
    $(find build-data/eturlia-server-templates/src/test/java -name '*.java')

echo
echo "=== eturlia.core self-test ==="
"$JAVA" -cp "$OUT_DIR:$API_CP" eturlia.core.loading.EturliaCoreSelfTest

echo
echo "=== eturlia.launcher self-test ==="
"$JAVA" -cp "$OUT_DIR:$API_CP" eturlia.launcher.EturliaLauncherSelfTest

echo
echo "=== config: every documented key ==="
"$JAVA" -cp "$OUT_DIR:$API_CP" eturlia.core.loading.EturliaConfigSelfTest

echo
echo "=== concurrency / stress ==="
"$JAVA" -cp "$OUT_DIR:$API_CP" eturlia.core.loading.EturliaStressTest

echo
echo "=== runtime boot (dev mode, no Minecraft) ==="
# Own JVM: this one installs log handlers and a shutdown hook, and replaces
# System.out/err to capture the startup console.
"$JAVA" -cp "$OUT_DIR:$API_CP" eturlia.EturliaRuntimeSelfTest
