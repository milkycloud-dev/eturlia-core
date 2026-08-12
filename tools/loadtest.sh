#!/usr/bin/env bash
# Load the running test server the way a flying player does - rapid teleports into ungenerated
# terrain, then a herd of entities - and record TPS/MSPT and region count at every step.
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
OUT=/tmp/loadtest.txt
: > "$OUT"

say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }

sample () {
    local label="$1"
    local mark
    mark=$(wc -l < "$LOG")
    say "tps"
    sleep 4
    {
        echo "### $label"
        tail -n +$mark "$LOG" | grep -aE 'Region TPS|Total Regions|Lowest|Median|Highest|util at' \
            | sed 's/.*\]: //' | head -12
        echo "  threads_ticking=$(ps -eo args | grep -c '[e]turlia-libraries')"
    } >> "$OUT"
    echo "--- $label"
    tail -14 "$OUT"
}

PID=$(ps -eo pid,args | grep '[e]turlia-libraries' | awk '{print $1}' | tail -1)
echo "server pid $PID" >> "$OUT"

sample "idle-before"

# A player flying at ~40 blocks/s covers 800 blocks in 20 s; teleporting that far every 15 s is a
# harsher version of the same thing - every hop lands in terrain that has to be generated, and the
# player's region has to be created, merged or split around it.
X=0
for i in 1 2 3 4 5 6 7 8; do
    X=$(( X + 900 ))
    say "tp EturliaTester $X 150 $(( X / 2 ))"
    sleep 11
    sample "flight-hop-$i (x=$X)"
done

sample "after-flight"

# Entities: a herd in one place, then spread across the regions the player just opened.
say "tp EturliaTester 0 150 0"
sleep 8
for i in $(seq 1 6); do
    say "execute at EturliaTester run summon minecraft:cow ~$((i * 3)) ~ ~$((i * 3))"
    say "execute at EturliaTester run summon minecraft:sheep ~-$((i * 3)) ~ ~$((i * 3))"
    say "execute at EturliaTester run summon minecraft:chicken ~$((i * 3)) ~ ~-$((i * 3))"
done
sleep 10
sample "entities-spawned"

say "kill @e[type=minecraft:cow]"
say "kill @e[type=minecraft:sheep]"
say "kill @e[type=minecraft:chicken]"
sleep 6
sample "entities-removed"

echo
echo "=== region tick failures during the run:"
grep -ac 'failed to tick' "$N/logs/test_stdout.log"
echo "=== full record in $OUT"
