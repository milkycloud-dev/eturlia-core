#!/usr/bin/env bash
# Is anything still drawn on or behind the player? Join, stand in a lit empty place, walk, look back,
# take pictures. Small and fast on purpose: this is the loop for chasing a cosmetic bug.
#
#   tools/trailcheck.sh [tag]
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
CLOG=$N/client/client.log
NAME=EturliaTester
PASS=${ETURLIA_TEST_PASS:-Eturlia2026test}
TAG=${1:-run}
X=760; Y=100; Z=760
say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }
shot () { import -window root "/tmp/trail_${TAG}_$1.png" 2>/dev/null; echo "   [shot] /tmp/trail_${TAG}_$1.png"; }

echo "== a flat, lit, empty stage"
say "op $NAME"
say "forceload add $((X-32)) $((Z-32)) $((X+32)) $((Z+32))"
sleep 18
say "fill $((X-16)) $Y $((Z-16)) $((X+16)) $((Y+12)) $((Z+16)) minecraft:air"
say "fill $((X-16)) $((Y-1)) $((Z-16)) $((X+16)) $((Y-1)) $((Z+16)) minecraft:white_concrete"
say "time set noon"
say "weather clear"
say "authme register $NAME $PASS"
sleep 5

for p in $(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}'); do kill -9 "$p"; done
pkill -9 -f 'portable''mc' 2>/dev/null
pkill -9 -f 'Xvf''b' 2>/dev/null
sleep 3
MARK=$(wc -l < "$LOG")
cd "$N/client" || exit 1
nohup bash run_client.sh > client.log 2>&1 < /dev/null &
joined=0
for _ in $(seq 1 72); do
    sleep 5
    tail -n +$MARK "$LOG" | grep -aq 'logged in with entity id' && { joined=1; break; }
    ps -eo args | grep -q '[j]ava-runtime-delta' || break
done
[ "$joined" = 1 ] || { echo "RESULT join=NO"; exit 1; }
CPID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
export DISPLAY=$(strings /proc/$CPID/environ | grep '^DISPLAY=' | head -1 | cut -d= -f2)
export XAUTHORITY=$(strings /proc/$CPID/environ | grep '^XAUTHORITY=' | head -1 | cut -d= -f2)
WIN=$(xdotool search --name Minecraft | tail -1)
xdotool windowfocus "$WIN"
for _ in $(seq 1 30); do
    say "say ETURLIA_READY_PROBE"
    sleep 6
    grep -aq 'ETURLIA_READY_PROBE' "$CLOG" && break
done
sleep 20
say "authme forcelogin $NAME"
sleep 3
say "gamemode creative $NAME"
say "tp $NAME $X $((Y+1)) $Z 0 0"
sleep 12
xdotool mousemove 160 180 click 1; sleep 3
xdotool mousemove 203 210 click 1; sleep 4
xdotool mousemove 160 141 click 1; sleep 3

# Minecraft opens the pause menu whenever the window loses focus, and then every keystroke goes to
# the menu instead of the game. Escape is not safe to send blindly - with no menu open it *creates*
# one. So: ask the game a question only the game can answer, and press Escape only when it does not.
in_game () {
    local from
    from=$(wc -l < "$LOG")
    xdotool windowfocus "$WIN"; sleep 1
    # a command whose feedback is logged with its text, unlike /say on this build
    xdotool key t; sleep 1; xdotool type --delay 30 "/effect give @s minecraft:glowing 1 0"; sleep 1; xdotool key Return
    sleep 4
    tail -n +"$from" "$LOG" | grep -aq "$NAME: Applied effect"
}
focus_game () {
    for attempt in 1 2 3; do
        in_game && return 0
        echo "   (pause menu was in the way, closing it - attempt $attempt)"
        xdotool key Escape; sleep 2
    done
    echo "   !! the keyboard never reached the game"
    return 1
}
focus_game || { shot keyboard_dead; exit 4; }

# Put the tester back on the stage right before the pictures: a teleport issued while the client is
# still loading is quietly dropped, and the run then photographs the inside of a hill.
say "tp $NAME $X $Y $Z 0 0"
sleep 8
# The stage has to be lit at the moment of the picture, not four minutes earlier.
say "time set day"
say "weather clear"
say "gamerule doDaylightCycle false"
say "effect clear $NAME"
say "effect give $NAME minecraft:night_vision 9999 0 true"
sleep 4

echo "== standing still"
focus_game; xdotool key F1; sleep 2; shot idle
# Anything the caller wants tried right before the pictures, e.g. EXTRA_CMD='/sable engage_gizmo'
if [ -n "${EXTRA_CMD:-}" ]; then
    echo "== extra: $EXTRA_CMD"
    chat "$EXTRA_CMD"
    sleep 4
    focus_game; shot after_extra
fi

echo "== with every effect cleared"
chat "/effect clear @s"
sleep 3
focus_game; shot no_effects
echo "== walking"
xdotool keydown w; sleep 5; xdotool keyup w; sleep 2
focus_game; shot walked
echo "== looking back down the path"
say "tp $NAME ~ ~ ~ 180 25"
sleep 4
focus_game; shot looking_back
echo "== third person"
focus_game; xdotool key F5; sleep 3
shot third_person
xdotool key F1; sleep 1
xdotool key F5; xdotool key F5; sleep 1

echo "== what is actually around the player (entities answer, particles do not):"
focus_game
xdotool key t; sleep 1
xdotool type --delay 30 "/execute as @e[distance=..12,type=!minecraft:player] run data get entity @s id"
sleep 1; xdotool key Return; sleep 5
grep -a "$NAME: " "$LOG" | grep -a 'entity data' | sed 's/.*\]: //' | sort -u | head -12 | sed 's/^/   /'
echo "   (nothing above means nothing is standing there - then it is drawn, not spawned)"

echo "== plugins that could draw this, still loaded:"
grep -aoE 'Enabling (PlayerParticles|PPC_Wings|DemonicEye|TrollEffects)[^ ]*' "$LOG" | sort -u | sed 's/^/   /'
echo "(no lines above means none of them are loaded)"
