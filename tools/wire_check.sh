#!/usr/bin/env bash
# Is the client sending anything at all? Watch the socket byte counters after the join.
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
bash "$N/tools/testctl.sh" say "authme forcelogin EturliaTester" > /dev/null

for i in 1 2 3 4 5 6; do
    sleep 5
    echo "--- t+$((i*5))s"
    ss -tin "dport = :25963 or sport = :25963" 2>/dev/null | grep -A1 '127.0.0.1:25963' \
        | grep -oE 'bytes_sent:[0-9]+|bytes_received:[0-9]+|bytes_acked:[0-9]+' | tr '\n' ' '
    echo
done
echo "--- server log tail"
tail -n +$MARK "$LOG" | grep -a 'lost connection' | tail -2 | cut -c1-140
