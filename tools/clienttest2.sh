#!/usr/bin/env bash
# One full player-side test: join, get past the mods' first-run dialogs, register with AuthMe,
# then run the assertions that need a player (Folia's console cannot use entity selectors).
# Usage: clienttest2.sh [register|login]
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
PASS=${ETURLIA_TEST_PASS:-Eturlia2026test}
MODE=${1:-register}
X=${ETURLIA_TEST_X:-600}; Y=100; Z=${ETURLIA_TEST_Z:-600}
say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }
shot () { import -window root "/tmp/$1.png" 2>/dev/null; }

for p in $(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}'); do kill -9 "$p"; done
pkill -9 -f 'portable''mc' 2>/dev/null
pkill -9 -f 'Xvf''b' 2>/dev/null
sleep 3

MARK=$(wc -l < "$LOG")
cd "$N/client" || exit 1
nohup bash run_client.sh > client.log 2>&1 < /dev/null &

joined=0
for _ in $(seq 1 60); do
    sleep 5
    tail -n +$MARK "$LOG" | grep -aq 'logged in with entity id' && { joined=1; break; }
    ps -eo args | grep -q '[j]ava-runtime-delta' || { echo "[client] died before joining"; break; }
done
[ "$joined" = 1 ] || { echo "RESULT join=NO"; tail -12 client.log | cut -c1-170; exit 1; }
echo "RESULT join=YES"

CPID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
export DISPLAY=$(strings /proc/$CPID/environ | grep '^DISPLAY=' | head -1 | cut -d= -f2)
export XAUTHORITY=$(strings /proc/$CPID/environ | grep '^XAUTHORITY=' | head -1 | cut -d= -f2)
WIN=$(xdotool search --name Minecraft | tail -1)
[ -n "$WIN" ] || { echo "RESULT window=NOT_FOUND"; exit 1; }
xdotool windowfocus "$WIN"
sleep 5

# Simple Voice Chat opens a two-step setup wizard over the game and swallows every keystroke.
xdotool mousemove 160 180 click 1; sleep 3     # "I Know What I Am Doing - Skip"
xdotool mousemove 203 210 click 1; sleep 4     # "Confirm"
shot after_dialogs

chat () {
    xdotool key t; sleep 1
    xdotool type --delay 45 "$1"; sleep 1
    xdotool key Return; sleep 3
}

if [ "$MODE" = register ]; then
    chat "/register $PASS $PASS"
else
    chat "/login $PASS"
fi
sleep 4
echo "--- AuthMe:"
tail -n +$MARK "$LOG" | grep -aiE 'EturliaTester (registered|logged in)|wrong password|not registered' | sed 's/.*\]: //' | tail -3

# Keep the tester alive: survival at spawn kills them in seconds, and a dead client stops sending
# packets, which the server reads as a timeout.
say "gamemode creative EturliaTester"
say "op EturliaTester"
sleep 2

# Build the rig and let the chunks finish generating BEFORE the player is sent there: a client
# teleported into ungenerated terrain sits on a loading screen sending nothing, and the server
# reads that silence as a 30-second timeout.
say "forceload add $((X-32)) $((Z-32)) $((X+32)) $((Z+32))"
sleep 12
say "fill $((X-4)) $Y $((Z-4)) $((X+4)) $((Y+6)) $((Z+4)) minecraft:air"
say "fill $((X-4)) $((Y-1)) $((Z-4)) $((X+4)) $((Y-1)) $((Z+4)) minecraft:stone"
sleep 2
say "setblock $X $Y $Z create:mechanical_bearing[facing=up]"
say "setblock $X $((Y+1)) $Z minecraft:oak_planks"
say "setblock $((X+1)) $((Y+1)) $Z minecraft:oak_planks"
sleep 2
say "setblock $((X-1)) $Y $Z minecraft:redstone_block"
sleep 8
say "tp EturliaTester $((X+6)) $((Y+3)) $((Z+6))"
sleep 10

MARK2=$(wc -l < "$LOG")
chat "/execute if entity @e[type=create:stationary_contraption,distance=..40] run say ETURLIA_TEST contraption=ASSEMBLED"
chat "/execute unless entity @e[type=create:stationary_contraption,distance=..40] run say ETURLIA_TEST contraption=MISSING"
chat "/execute if block $X $((Y+1)) $Z minecraft:air run say ETURLIA_TEST carried_blocks=LIFTED"
chat "/summon alexsmobs:potoo $((X+3)) $((Y+1)) $((Z+3))"
chat "/execute if entity @e[type=alexsmobs:potoo,distance=..40] run say ETURLIA_TEST modded_mob=SPAWNED"
chat "/give @s alexsmobs:spawn_egg_potoo 1"
chat "/execute if entity @s[nbt={Inventory:[{id:\"alexsmobs:spawn_egg_potoo\"}]}] run say ETURLIA_TEST spawn_egg_item=IN_HAND"
sleep 5

echo "--- verdicts:"
tail -n +$MARK2 "$LOG" | grep -a 'ETURLIA_TEST' | sed 's/.*ETURLIA_TEST //' | sort -u
echo "--- alarming since join:"
tail -n +$MARK "$LOG" | grep -aE 'NoSuchMethodError|AbstractMethodError|IncompatibleClassChange|ClassCastException|failed to tick' \
    | sed 's/.*\]: //' | sort -u | head -6 | cut -c1-160
shot end_of_test
echo "(screenshots: /tmp/after_dialogs.png /tmp/end_of_test.png)"
