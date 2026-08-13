#!/usr/bin/env bash
# One turn of the loop, in one command: regenerate the sources, build, deploy, restart, grade.
#
# Every step writes its own log under build-logs/ and prints one line here; a failing step prints
# its own tail and stops the run. Nothing needs the whole build output to be read back.
#
#   tools/cycle.sh              patch + generate + build + deploy + restart + logcheck
#   tools/cycle.sh build        stop before deploying
#   tools/cycle.sh deploy       skip the build, deploy what is already built and restart
set -uo pipefail
N=/home/user/milky/eturlia_new
JAR=$N/core/build/libs/eturlia-1.21.1-neoforge-21.1.248.jar
LOGS=$N/build-logs
MODE=${1:-all}
mkdir -p "$LOGS"

step () {
    local name=$1; shift
    local out="$LOGS/$name.log"
    local began=$SECONDS
    if "$@" > "$out" 2>&1; then
        echo "[ok] $name ($((SECONDS - began))s)"
    else
        echo "[FAIL] $name - tail of $out:"
        tail -n 25 "$out"
        exit 1
    fi
}

if [ "$MODE" = all ] || [ "$MODE" = build ]; then
    cd "$N/core" || exit 1
    step applypatches ./gradlew applyPatches
    step generate python3 scripts/apply_compat_layer.py
    misses=$(grep -c '^!!' "$LOGS/generate.log")
    if [ "$misses" != 0 ]; then
        echo "[FAIL] generate: $misses anchors missing"
        grep '^!!' "$LOGS/generate.log" | head -10
        exit 1
    fi
    step jar ./gradlew :folia-server:eturliaStandaloneJar
    ls -la "$JAR" | awk '{print "    built", $5, "bytes", $6, $7, $8}'
fi

[ "$MODE" = build ] && exit 0

# A player may be on. Say so, give them a moment, then take it down cleanly.
bash "$N/tools/testctl.sh" say "say Server restarting for a core update - back in about a minute" > /dev/null 2>&1
sleep 5
step stop bash "$N/tools/testctl.sh" stop
# Start every boot on a fresh log: wait-ready and logcheck both read latest.log, and the previous
# boot's lines in it are indistinguishable from this one's.
mv "$N/server/logs/latest.log" "$N/server/logs/prev.log" 2>/dev/null
cp "$JAR" "$N/server/eturlia.jar" || exit 1
echo "    deployed $(md5sum "$N/server/eturlia.jar" | cut -c1-12)"
sleep 20
step start bash "$N/tools/testctl.sh" start
bash "$N/tools/testctl.sh" wait-ready 420 || exit 1
sleep 20
python3 "$N/tools/logcheck.py"
