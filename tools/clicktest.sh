#!/usr/bin/env bash
# Right-click a placed block as a real player and say what the server did about it.
#
# Three cheaper instruments could not answer "does the backpack open":
#   * MenuType.create is the client factory and throws for vanilla furnaces too
#   * BlockState.getMenuProvider is null for mods that open their GUI from their own use() handler
# So this does the actual thing a player does - aim at the block and press the right button - and
# reads the server log for what came of it.
#
#   tools/clicktest.sh <block-id> [x] [y] [z]
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
CLOG=$N/client/client.log
NAME=EturliaTester
BLOCK=${1:-travelersbackpack:standard}
X=${2:-310}; Y=${3:-101}; Z=${4:-310}

say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }

ps -eo args | grep -q '[j]ava-runtime-delta' || { echo "RESULT: NO-CLIENT - join one first"; exit 1; }
CPID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
export DISPLAY=$(strings /proc/$CPID/environ | grep '^DISPLAY=' | head -1 | cut -d= -f2)
export XAUTHORITY=$(strings /proc/$CPID/environ | grep '^XAUTHORITY=' | head -1 | cut -d= -f2)
WIN=$(xdotool search --name Minecraft | tail -1)
[ -n "$WIN" ] || { echo "RESULT: NO-WINDOW"; exit 1; }

say "forceload add $((X-16)) $((Z-16)) $((X+16)) $((Z+16))"
sleep 5
say "fill $((X-3)) $Y $((Z-3)) $((X+3)) $((Y+4)) $((Z+5)) minecraft:air"
sleep 2
# A floor, or the tester falls straight out of the pocket that was just cleared and spends the run
# looking at whatever is underneath. The first version of this test did exactly that.
say "fill $((X-3)) $((Y-1)) $((Z-3)) $((X+3)) $((Y-1)) $((Z+5)) minecraft:stone"
sleep 2
say "setblock $X $Y $Z $BLOCK"
sleep 3
# Stand two blocks south of it and look north and slightly down, so the crosshair lands on it.
say "tp $NAME $((X)).5 $Y $((Z+2)).5 180 25"
sleep 5
say "gamemode creative $NAME"
sleep 2
say "effect give $NAME minecraft:slow_falling 120 0 true"
sleep 2
# Re-aim after the teleport has actually settled; a player still moving looks somewhere else.
say "tp $NAME $((X)).5 $Y $((Z+2)).5 180 25"
sleep 4

# Escape opens the pause menu when none is open, so it is never safe to press blindly - the first
# version of this test did exactly that and spent a run clicking at the Game Menu. Instead, prove
# the game has focus with a typed command, and only press Escape when that proof fails.
CANARY_X=$((X-6)); CANARY_Y=$((Y-2)); CANARY_Z=$((Z-6))
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
    echo "   focus attempt $attempt: something is in the way, closing it"
    xdotool key Escape; sleep 2
done
[ "$focused" = 1 ] || { echo "RESULT: NO-KEYBOARD - could not reach the game"; exit 1; }

MARK=$(wc -l < "$LOG")
CMARK=$(wc -l < "$CLOG")
xdotool windowfocus "$WIN"; sleep 1
xdotool click 3
sleep 6

echo "== block that was clicked"
say "minecraft:data get block $X $Y $Z"
sleep 3

echo "--- server, since the click ---"
tail -n +$MARK "$LOG" | grep -aiE 'error|exception|screen|menu|container|open' \
    | grep -aviE 'voicechat|version check|essentialsx' | sed 's/.*\]: //' | cut -c1-150 | head -8

echo "--- client, since the click ---"
tail -n +$CMARK "$CLOG" | grep -aiE 'error|exception|screen|menu|container' \
    | sed 's/.*\]: //' | cut -c1-150 | head -8

echo "--- verdict ---"
if tail -n +$MARK "$LOG" | grep -aqiE 'exception|error'; then
    echo "RESULT: SERVER-ERROR on right-click of $BLOCK"
elif tail -n +$CMARK "$CLOG" | grep -aqi 'screen'; then
    echo "RESULT: A SCREEN OPENED for $BLOCK"
else
    echo "RESULT: NOTHING HAPPENED - no error and no screen; the click may not have landed"
fi
