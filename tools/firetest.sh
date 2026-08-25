#!/usr/bin/env bash
# Fire a potato cannon the way a player does, and say what the server made of it.
#
# The projectile type registry is not the problem - create:potato_projectile/type holds its 32
# entries. The NullPointerException seen earlier came from summoning a bare projectile with no
# ammunition, which is a thing only a probe does. This gives the tester a real cannon and real
# ammunition and pulls the trigger.
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
CLOG=$N/client/client.log
NAME=EturliaTester
X=${1:-310}; Y=${2:-101}; Z=${3:-310}

say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }

ps -eo args | grep -q '[j]ava-runtime-delta' || { echo "RESULT: NO-CLIENT"; exit 1; }
CPID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
export DISPLAY=$(strings /proc/$CPID/environ | grep '^DISPLAY=' | head -1 | cut -d= -f2)
export XAUTHORITY=$(strings /proc/$CPID/environ | grep '^XAUTHORITY=' | head -1 | cut -d= -f2)
WIN=$(xdotool search --name Minecraft | tail -1)
[ -n "$WIN" ] || { echo "RESULT: NO-WINDOW"; exit 1; }

say "minecraft:forceload add $((X-16)) $((Z-16)) $((X+16)) $((Z+16))"
sleep 4
say "minecraft:fill $((X-4)) $Y $((Z-20)) $((X+4)) $((Y+5)) $((Z+4)) minecraft:air"
sleep 3
say "minecraft:fill $((X-4)) $((Y-1)) $((Z-20)) $((X+4)) $((Y-1)) $((Z+4)) minecraft:stone"
sleep 3
say "minecraft:gamemode creative $NAME"
sleep 2
say "minecraft:clear $NAME"
sleep 2
say "minecraft:give $NAME create:potato_cannon"
sleep 2
say "minecraft:give $NAME minecraft:potato 64"
sleep 2
# minecraft:tp, not /tp - Essentials owns the short name here and answers with a warmup, which
# leaves the tester standing somewhere else while the run believes it aimed.
say "minecraft:tp $NAME $((X)).5 $Y $((Z)).5 180 0"
sleep 5

# Prove the keyboard reaches the game before pulling a trigger and believing the silence.
CANARY_X=$((X-3)); CANARY_Y=$((Y-1)); CANARY_Z=$((Z-3))
focused=0
for attempt in 1 2 3; do
    KM=$(wc -l < "$LOG")
    xdotool windowfocus "$WIN"; sleep 1
    xdotool key t; sleep 1
    xdotool type --delay 35 "/minecraft:setblock $CANARY_X $CANARY_Y $CANARY_Z minecraft:sea_lantern"
    sleep 1; xdotool key Return; sleep 4
    if tail -n +"$KM" "$LOG" | grep -a "Changed the block at $CANARY_X" | grep -avq 'issued server command'; then
        focused=1; break
    fi
    echo "   focus attempt $attempt: something is in the way"
    xdotool key Escape; sleep 2
done
[ "$focused" = 1 ] || { echo "RESULT: NO-KEYBOARD"; exit 1; }

# Select the cannon: it went to the first free hotbar slot after clear.
xdotool key 1; sleep 2

MARK=$(wc -l < "$LOG")
CMARK=$(wc -l < "$CLOG")
echo "== firing"
xdotool windowfocus "$WIN"; sleep 1
xdotool click 3; sleep 2
xdotool click 3; sleep 2
xdotool click 3; sleep 6

import -window root /tmp/fired.png 2>/dev/null

echo "--- server since the shot ---"
tail -n +$MARK "$LOG" | grep -aiE 'error|exception|potato|failed to tick' \
    | grep -aviE 'voicechat|version check' | sed 's/.*\]: //' | cut -c1-160 | head -8

echo "--- client since the shot ---"
tail -n +$CMARK "$CLOG" | grep -aiE 'error|exception|crash|potato' | cut -c1-160 | head -6

echo "--- still up? ---"
ps -eo args | grep -q '[e]turlia.jar' && echo "server: alive" || echo "server: GONE"
ps -eo args | grep -q '[j]ava-runtime-delta' && echo "client: alive" || echo "client: GONE"

echo "--- verdict ---"
if ! ps -eo args | grep -q '[e]turlia.jar'; then
    echo "RESULT: SERVER-DIED firing the potato cannon"
elif tail -n +$MARK "$LOG" | grep -aqi 'PotatoProjectile\|potato.*exception'; then
    echo "RESULT: SERVER-ERROR on the potato projectile"
elif ! ps -eo args | grep -q '[j]ava-runtime-delta'; then
    echo "RESULT: CLIENT-DIED firing the potato cannon"
else
    echo "RESULT: CLEAN - the cannon fired and nothing complained"
fi
