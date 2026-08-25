#!/usr/bin/env bash
# Assemble a sub-level the way sable itself does it, then ask whether it moves.
#
# Building an airship from the console does not work: the Physics Assembler needs an interaction
# this harness cannot replicate (it sits at IsPrimary: 0b and assembles nothing, silently). But
# `/sable assemble connected` is sable's own path, it needs a player, and aerotest already shows it
# running clean. So: drive that from the client, then measure with `eprobe sublevels`, which reads
# sable's own container.
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
CLOG=$N/client/client.log
NAME=EturliaTester
PASS=${ETURLIA_TEST_PASS:-Eturlia2026test}
X=${1:-1500}; Y=95; Z=${2:-1500}
WATCH=${3:-15}

say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }
chat () { xdotool key t; sleep 1; xdotool type --delay 35 "$1"; sleep 1; xdotool key Return; sleep 3; }

echo "== site"
say "op $NAME"
say "forceload add $((X-32)) $((Z-32)) $((X+32)) $((Z+32))"
sleep 10
say "fill $((X-8)) $Y $((Z-8)) $((X+12)) $((Y+30)) $((Z+8)) minecraft:air"
say "fill $((X-8)) $((Y-1)) $((Z-8)) $((X+12)) $((Y-1)) $((Z+8)) minecraft:stone"
sleep 3
# A loose structure for `/sable assemble connected` to lift out of the world.
say "fill $((X+2)) $((Y+1)) $((Z-1)) $((X+4)) $((Y+3)) $((Z+1)) aeronautics:white_envelope"
sleep 3
say "lp user $NAME permission set minecraft.command.setblock true"
say "authme register $NAME $PASS"
sleep 3

MARK=$(wc -l < "$LOG")
echo "== client"
cd "$N/client" || exit 1
for p in $(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}'); do kill -9 "$p"; done
sleep 2
nohup bash run_client.sh > client.log 2>&1 < /dev/null &
joined=0
for _ in $(seq 1 72); do
    sleep 5
    tail -n +$MARK "$LOG" | grep -aq 'logged in with entity id' && { joined=1; break; }
done
[ "$joined" = 1 ] || { echo "RESULT: NOJOIN"; exit 1; }
say "authme forcelogin $NAME"
say "gamemode creative $NAME"
sleep 6
say "tp $NAME $((X+3)) $((Y+1)) $((Z+4))"
sleep 10

CPID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
export DISPLAY=$(strings /proc/$CPID/environ | grep '^DISPLAY=' | head -1 | cut -d= -f2)
export XAUTHORITY=$(strings /proc/$CPID/environ | grep '^XAUTHORITY=' | head -1 | cut -d= -f2)
WIN=$(xdotool search --name Minecraft | tail -1)
[ -n "$WIN" ] || { echo "RESULT: NO-WINDOW"; exit 1; }

ready=0
for _ in $(seq 1 30); do
    say "say ETURLIA_READY_PROBE"
    sleep 6
    grep -aq 'ETURLIA_READY_PROBE' "$CLOG" && { ready=1; break; }
    ps -eo args | grep -q '[j]ava-runtime-delta' || break
done
[ "$ready" = 1 ] || { echo "RESULT: NOT-READY"; exit 1; }

xdotool mousemove 160 180 click 1; sleep 3
xdotool mousemove 203 210 click 1; sleep 4
xdotool mousemove 160 141 click 1; sleep 4
xdotool key Escape; sleep 2

CX=$((X-6)); CY=$((Y-2)); CZ=$((Z-6))
canary=0
for attempt in 1 2 3; do
    P=$(wc -l < "$LOG")
    xdotool windowfocus "$WIN"; sleep 1
    [ "$attempt" != 1 ] && { xdotool key Escape; sleep 2; xdotool windowfocus "$WIN"; sleep 1; }
    xdotool key t; sleep 1
    xdotool type --delay 35 "/minecraft:setblock $CX $CY $CZ minecraft:sea_lantern"
    sleep 1; xdotool key Return; sleep 4
    tail -n +"$P" "$LOG" | grep -a "Changed the block at $CX" | grep -avq 'issued server command' \
        && { canary=1; break; }
    echo "   canary attempt $attempt: keyboard not reaching the game"
done
[ "$canary" = 1 ] || { echo "RESULT: NO-KEYBOARD"; exit 1; }
echo "== keyboard reaches the game"

echo "== sable: ${SABLE_CMD:-/sable spawn block}"
A=$(wc -l < "$LOG")
chat "${SABLE_CMD:-/sable spawn block}"
sleep 12

echo "== sub-levels now, and do they move"
say "eprobe sublevels $WATCH"
sleep $((WATCH + 14))
echo "--- sublevels.tsv ---"
cat "$N/server/plugins/EturliaProbe/sublevels.tsv" 2>/dev/null | cut -c1-155

echo "--- what the console was told ---"
tail -n +$A "$LOG" | grep -a 'eprobe sublevels' | sed 's/.*\]: //' | cut -c1-150 | tail -3

echo "--- sable errors this run ---"
tail -n +$A "$LOG" | grep -aiE 'sable|rapier|failed to tick|argument\\.sable' | sed 's/.*\]: //' \
    | cut -c1-150 | sort -u | head -6
