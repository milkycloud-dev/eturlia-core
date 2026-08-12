#!/bin/bash
# Start/stop/probe the Eturlia TEST server only.
#
# The box also runs NoteBuns (production, screen session "NoteBuns", ~64G java process).
# Every operation here is scoped to the test directory and the "test" screen session, and the
# script refuses to act if a target does not clearly belong to the test server.

# Eturlia: never start the test server without room to spare. Production runs with an 80G heap
# on a 124G box; on 2026-08-11 a 20G test heap pushed the kernel into killing production.
MIN_FREE_GB=16
require_free_memory () {
    local available
    available=$(free -g | awk '/^Mem:/ {print $7}')
    if [ -z "$available" ]; then
        echo "[guard] cannot read available memory — refusing to start"
        exit 1
    fi
    if [ "$available" -lt "$MIN_FREE_GB" ]; then
        echo "[guard] only ${available}G available, need ${MIN_FREE_GB}G — refusing to start"
        echo "[guard] production must keep its headroom; stop something else first"
        exit 1
    fi
    echo "[guard] ${available}G available — ok to start"
}
set -uo pipefail

TEST_DIR=/home/user/milky/eturlia_new/server
SESSION=test
PROD_SESSION=NoteBuns

guard_prod() {
    if screen -ls | grep -q "\.${PROD_SESSION}\b"; then
        echo "[guard] production session ${PROD_SESSION} is running — leaving it untouched"
    fi
}

test_pids() {
    # Only java processes whose cwd is the test directory.
    for pid in $(pgrep -x java 2>/dev/null); do
        cwd=$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)
        case "$cwd" in "$TEST_DIR"*) echo "$pid";; esac
    done
}

case "${1:-status}" in
  status)
    guard_prod
    echo "--- screen sessions ---"; screen -ls || true
    echo "--- test server pids ---"; test_pids | tr '\n' ' '; echo
    ;;

  start)
    guard_prod
    if screen -ls | grep -q "\.${SESSION}\b"; then
        echo "[start] session ${SESSION} already exists — refusing to start a second one"
        exit 1
    fi
    running=$(test_pids | tr '\n' ' ')
    if [ -n "${running// /}" ]; then
        echo "[start] test java already running (pids: $running) — refusing"
        exit 1
    fi
    cd "$TEST_DIR" || exit 1
    require_free_memory
    echo "[start] launching via start.sh in screen session ${SESSION}"
    screen -dmS "$SESSION" bash -c "cd $TEST_DIR && bash start.sh inscreen"
    sleep 3
    screen -ls | grep "\.${SESSION}\b" || { echo "[start] session did not come up"; exit 1; }
    ;;

  stop)
    guard_prod
    pids=$(test_pids)
    if [ -z "$pids" ]; then
        echo "[stop] no test server running"
    else
        echo "[stop] sending 'stop' to the console of session ${SESSION}"
        screen -S "$SESSION" -p 0 -X stuff "stop$(printf '\r')" 2>/dev/null || true
        for i in $(seq 1 45); do
            sleep 2
            [ -z "$(test_pids)" ] && break
        done
        pids=$(test_pids)
        if [ -n "$pids" ]; then
            echo "[stop] still alive after 90s, terminating $pids"
            kill -TERM $pids 2>/dev/null || true
            sleep 10
            pids=$(test_pids)
            [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true
        fi
    fi
    # start.sh wraps the server in a restart loop, so the screen session must go too.
    screen -S "$SESSION" -X quit 2>/dev/null || true
    echo "[stop] done"
    ;;

  say)
    shift
    screen -S "$SESSION" -p 0 -X stuff "$*$(printf '\r')"
    echo "[say] sent: $*"
    ;;

  wait-ready)
    limit=${2:-300}
    log="$TEST_DIR/logs/latest.log"
    echo "[wait] up to ${limit}s for 'Done ('"
    for i in $(seq 1 "$limit"); do
        if grep -q 'Done (' "$log" 2>/dev/null; then
            grep -m1 'Done (' "$log"
            exit 0
        fi
        if ! test_pids | grep -q .; then
            echo "[wait] server process is gone"
            tail -25 "$log" 2>/dev/null
            exit 1
        fi
        sleep 1
    done
    echo "[wait] timed out"
    tail -25 "$log" 2>/dev/null
    exit 1
    ;;

  *)
    echo "usage: $0 {status|start|stop|say <cmd>|wait-ready [seconds]}"
    exit 2
    ;;
esac
