#!/usr/bin/env bash
# T-SER: does a stock NeoForge client survive every modded entity the pack can spawn?
#
# The tester's kick was "Failed to decode packet 'clientbound/minecraft:set_entity_data' ...
# Unknown serializer type 48". The only way to see it is from the client, so: join, spawn all of
# them next to the player with the probe, and read the client's own log back.
set -uo pipefail

N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
CLIENT=$N/client/client.log
HOLD=${1:-260}

MARK_LOG=$(wc -l < "$LOG")

nohup bash "$N/tools/join_stable.sh" "$HOLD" > "$N/logs/sertest_join.log" 2>&1 < /dev/null &
JOIN=$!

joined=0
for _ in $(seq 1 72); do
    sleep 5
    if tail -n +$MARK_LOG "$LOG" | grep -aq 'logged in with entity id'; then joined=1; break; fi
    if ! kill -0 $JOIN 2>/dev/null; then break; fi
done
if [ "$joined" != 1 ]; then
    echo "RESULT: NOJOIN - the client never reached the world"
    tail -6 "$N/logs/sertest_join.log" | cut -c1-160
    exit 1
fi
echo "[sertest] client is in; giving it 20s to settle"
sleep 20

MARK_CLIENT=$(wc -l < "$CLIENT")

echo "[sertest] spawning every registered entity type next to the tester"
bash "$N/tools/testctl.sh" say "eprobe entities" > /dev/null
sleep 90

echo "[sertest] --- client decode errors ---"
tail -n +$MARK_CLIENT "$CLIENT" \
    | grep -aE 'DecoderException|Unknown serializer|Failed to decode packet|Internal Exception|Disconnected' \
    | cut -c1-200 | sort | uniq -c | sort -rn | head -15
echo "[sertest] (nothing above this line means the client decoded everything)"

echo "[sertest] --- server side ---"
tail -n +$MARK_LOG "$LOG" | grep -aE 'lost connection|failed to tick|Unregistered serializer' \
    | cut -c1-180 | tail -10

echo "[sertest] --- probe verdict ---"
tail -3 "$N/server/plugins/EturliaProbe/entities.tsv" | cut -c1-160
awk -F'\t' 'NR>1{total++; if ($0 ~ /threw/) bad++} END {printf "entities.tsv: %d rows, %d threw\n", total, bad+0}' \
    "$N/server/plugins/EturliaProbe/entities.tsv"

wait $JOIN
echo "[sertest] join verdict above"
