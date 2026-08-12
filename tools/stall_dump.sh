#!/usr/bin/env bash
# Join once and take a thread dump of the client while it is not answering keep-alives.
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
tail -n +$MARK "$LOG" | grep -a 'logged in with entity id' | tail -1 | cut -c1-120

bash "$N/tools/testctl.sh" say "authme forcelogin EturliaTester" > /dev/null
sleep 22

PID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
echo "=== client pid $PID, cpu:"
ps -p "$PID" -o pcpu,rss,etimes
echo "=== Render thread stack:"
/opt/jdk-21.0.12+8/bin/jcmd "$PID" Thread.print 2>/dev/null \
    | awk '/"Render thread"/{f=1} f{print} f&&/^$/{c++; if(c>0) exit}' | head -45
echo "=== threads at RUNNABLE burning cpu:"
/opt/jdk-21.0.12+8/bin/jcmd "$PID" Thread.print 2>/dev/null | grep -c 'java.lang.Thread.State'
