#!/usr/bin/env bash
# Join once and photograph the client 15 seconds in, before the server drops it.
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log

for p in $(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}'); do kill -9 "$p"; done
pkill -9 -f 'Xvf''b' 2>/dev/null
sleep 2

MARK=$(wc -l < "$LOG")
cd "$N/client" || exit 1
nohup bash run_client.sh > client.log 2>&1 < /dev/null &

for _ in $(seq 1 60); do
    sleep 5
    tail -n +$MARK "$LOG" | grep -aq 'logged in with entity id' && break
done
echo "joined: $(tail -n +$MARK "$LOG" | grep -a 'logged in with entity id' | tail -1 | cut -c1-110)"
sleep 12

DISPLAY_NUM=$(ps -eo args | grep '[X]vfb' | grep -oE ':[0-9]+' | head -1)
AUTH=$(ps -eo args | grep '[X]vfb' | grep -oE '/tmp/xvfb-run\.[A-Za-z0-9]+/Xauthority' | head -1)
echo "display $DISPLAY_NUM auth $AUTH"
XAUTHORITY="$AUTH" DISPLAY="$DISPLAY_NUM" import -window root /tmp/client_mid.png && echo shot-ok
