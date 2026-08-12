#!/usr/bin/env bash
# Join the running test server with the headless client, log the tester in past AuthMe, and
# watch for the length of $HOLD seconds. Prints STABLE or the reason it ended.
set -uo pipefail

N=/home/user/milky/eturlia_new
HOLD=${1:-240}
LOG=$N/server/logs/latest.log
STDOUT=$N/logs/test_stdout.log

# One client at a time: two racing testers make the server log unreadable.
for p in $(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}'); do kill -9 "$p"; done
pkill -9 -f 'portable''mc' 2>/dev/null
pkill -9 -f Xvfb 2>/dev/null
sleep 2

MARK_LOG=$(wc -l < "$LOG")
MARK_OUT=$(wc -l < "$STDOUT")

cd "$N/client" || exit 1
nohup bash run_client.sh > client.log 2>&1 < /dev/null &
echo "[join] client started"

joined=0
for _ in $(seq 1 60); do
    sleep 5
    if tail -n +$MARK_LOG "$LOG" | grep -aq 'logged in with entity id'; then joined=1; break; fi
    if ! ps -eo args | grep -q '[j]ava-runtime-delta'; then echo "[join] client died before joining"; break; fi
done
[ "$joined" = 1 ] || { echo "NOJOIN"; tail -15 "$N/client/client.log" | cut -c1-180; exit 1; }
echo "[join] $(tail -n +$MARK_LOG "$LOG" | grep -a 'logged in with entity id' | tail -1 | cut -c1-150)"

bash "$N/tools/testctl.sh" say "authme forcelogin EturliaTester" > /dev/null
# Survival at the test spawn kills the tester within seconds, and a client sitting on the death
# screen sends nothing at all - which the server reads as a 30s Netty read timeout and reports as
# "lost connection: Timed out". Creative persists in the player's data, so this only has to land
# once, but it is cheap to repeat.
bash "$N/tools/testctl.sh" say "gamemode creative EturliaTester" > /dev/null
sleep 5

end=$((SECONDS + HOLD))
reason=""
while [ $SECONDS -lt $end ]; do
    sleep 5
    if tail -n +$MARK_LOG "$LOG" | grep -aq 'lost connection'; then
        reason="DISCONNECT $(tail -n +$MARK_LOG "$LOG" | grep -a 'lost connection' | tail -1 | cut -c1-160)"
        break
    fi
    if tail -n +$MARK_OUT "$STDOUT" | grep -aq 'failed to tick'; then
        reason="REGION_DEATH"
        break
    fi
    if ! ps -eo args | grep -q '[e]turlia.jar'; then reason="SERVER_GONE"; break; fi
done

if [ -n "$reason" ]; then
    echo "$reason"
else
    echo "STABLE ${HOLD}s"
fi

echo "--- new server-side errors ---"
tail -n +$MARK_OUT "$STDOUT" | grep -aE 'ERROR|WARN.*Exception|failed to tick|Caused by' \
    | grep -avE 'EssentialsX dev build|PremiumVanish' | sed 's/.*\] //' | cut -c1-150 \
    | sort | uniq -c | sort -rn | head -25
