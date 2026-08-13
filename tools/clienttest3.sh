#!/usr/bin/env bash
# The player-side test for the round of 2026-08-13: everything the operator reported as broken,
# checked by a real client, in the order a player would hit it.
#
# Two lessons are baked in:
#   * everything that generates chunks happens BEFORE the client connects. A 126-mod client on a
#     software renderer stalls long enough during a forceload for the server to drop it.
#   * the client's liveness is checked before every keystroke. A disconnected client swallows input
#     silently, and a whole run once "passed" that way - the commands were never typed at all.
#
#   tools/clienttest3.sh [login|register]
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
PASS=${ETURLIA_TEST_PASS:-Eturlia2026test}
NAME=EturliaTester
MODE=${1:-login}
# Near spawn on purpose: a long teleport makes this client load a whole new area, and it
# stalls long enough doing that for the server to drop it.
X=${ETURLIA_TEST_X:-16}; Y=85; Z=${ETURLIA_TEST_Z:-16}
say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }
shot () { import -window root "/tmp/$1.png" 2>/dev/null; echo "  [shot] /tmp/$1.png"; }

# --- 0. everything that costs the server work, done with nobody watching --------------------------
echo "== 0. building the rigs before anyone connects"
say "op $NAME"
say "forceload add $((X-32)) $((Z-32)) $((X+32)) $((Z+32))"
sleep 20
say "fill $((X-6)) $Y $((Z-6)) $((X+16)) $((Y+8)) $((Z+6)) minecraft:air"
say "fill $((X-6)) $((Y-1)) $((Z-6)) $((X+16)) $((Y-1)) $((Z+6)) minecraft:stone"
sleep 4
# Create: the motor is two shafts below the bearing, so this tests kinetic transfer along a shaft
# chain, not just a bearing sitting on top of a motor.
say "setblock $X $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $X $Y $Z create:shaft[axis=y]"
say "setblock $X $((Y+1)) $Z create:shaft[axis=y]"
say "setblock $X $((Y+2)) $Z create:mechanical_bearing[facing=up]"
say "setblock $X $((Y+3)) $Z minecraft:oak_planks"
say "setblock $((X+1)) $((Y+3)) $Z minecraft:oak_planks"
# Aeronautics: a propeller bearing with a propeller on it is an airship the moment it is powered.
say "setblock $((X+10)) $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $((X+10)) $Y $Z aeronautics:propeller_bearing[facing=up]"
say "setblock $((X+10)) $((Y+1)) $Z aeronautics:wooden_propeller"
sleep 4
MARK0=$(wc -l < "$LOG")
say "setblock $((X-1)) $((Y+2)) $Z minecraft:redstone_block"
say "setblock $((X+9)) $Y $Z minecraft:redstone_block"
sleep 15
# The bearing takes its blocks out of the world when it assembles; that is the console-side proof.
say "execute if block $X $((Y+3)) $Z minecraft:air run setblock $((X+14)) $((Y+3)) $Z minecraft:emerald_block"
sleep 3
tail -n +"$MARK0" "$LOG" | grep -aq "Changed the block at $((X+14))" \
    && echo "RESULT create_through_shafts=ASSEMBLED (blocks lifted off the bearing)" \
    || echo "RESULT create_through_shafts=NOT_ASSEMBLED"

# --- 1. the client --------------------------------------------------------------------------------
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

# "logged in with entity id" only means the connection was accepted. The client is still building
# the world for a minute after that, and every keystroke typed into a loading screen is thrown
# away - which is how an earlier run reported passes for commands that were never sent. Wait until
# a line said on the server comes out the other end, in the client's own chat.
ready=0
for _ in $(seq 1 30); do
    say "say ETURLIA_READY_PROBE"
    sleep 6
    grep -aq 'ETURLIA_READY_PROBE' "$N/client/client.log" && { ready=1; break; }
    ps -eo args | grep -q '[j]ava-runtime-delta' || break
done
[ "$ready" = 1 ] || { echo "RESULT client_in_world=NO (never received chat)"; tail -6 "$N/client/client.log" | cut -c1-150; exit 1; }
sleep 30   # chat arrives while the loading screen is still up; give the world time to finish
echo "RESULT client_in_world=YES"

alive () {
    if ! ps -eo args | grep -q '[j]ava-runtime-delta'; then
        echo "!! the client process is gone - nothing after this point was typed ($1)"
        exit 3
    fi
    if tail -n +$MARK "$LOG" | grep -aq "$NAME lost connection"; then
        echo "!! the client disconnected - nothing after this point was typed ($1)"
        tail -n +$MARK "$LOG" | grep -a "lost connection" | tail -1 | sed 's/.*\]: //' | cut -c1-120
        exit 3
    fi
}

chat () {
    alive "$1"
    xdotool key t; sleep 1
    xdotool type --delay 40 "$1"; sleep 1
    xdotool key Return; sleep 3
}

# Simple Voice Chat's setup wizard sits over the game and eats every keystroke until it is answered.
xdotool mousemove 160 180 click 1; sleep 3
xdotool mousemove 203 210 click 1; sleep 4

# --- 2. AuthMe, typed by the player rather than run from the console ------------------------------
echo "== 2. AuthMe"
MARK1=$(wc -l < "$LOG")
# AuthMe resumes the previous session on join, so there is nothing to log in to unless the
# account is cleared first. This puts the tester in the state a brand new player is in.
say "authme unregister $NAME"
sleep 3
chat "ETURLIA_CHAT_BEFORE_LOGIN"
if [ "$MODE" = register ]; then chat "/register $PASS $PASS"; else chat "/login $PASS"; fi
sleep 4
chat "ETURLIA_CHAT_AFTER_LOGIN"
sleep 2
tail -n +$MARK1 "$LOG" | grep -aiE "$NAME (registered|logged in)|wrong password|not registered|already" \
    | sed 's/.*\]: //' | tail -3
before=$(tail -n +$MARK1 "$LOG" | grep -ac 'ETURLIA_CHAT_BEFORE_LOGIN')
after=$(tail -n +$MARK1 "$LOG" | grep -ac 'ETURLIA_CHAT_AFTER_LOGIN')
echo "RESULT authme: chat_before_login=$before (0 is correct) chat_after_login=$after (1 is correct)"

# --- 3. what the rigs did, asked with the selectors only a player can use --------------------------
echo "== 3. Create and Aeronautics"
say "tp $NAME $((X+5)) $((Y+1)) $((Z+8))"
sleep 12
alive "after the teleport"
MARK3=$(wc -l < "$LOG")
chat "/execute if entity @e[type=create:stationary_contraption,distance=..40] run say ETURLIA_TEST create_contraption=PRESENT"
chat "/execute unless entity @e[type=create:stationary_contraption,distance=..40] run say ETURLIA_TEST create_contraption=MISSING"
chat "/execute if entity @e[type=aeronautics:propeller_bearing_contraption,distance=..40] run say ETURLIA_TEST aeronautics_airship=PRESENT"
chat "/execute unless entity @e[type=aeronautics:propeller_bearing_contraption,distance=..40] run say ETURLIA_TEST aeronautics_airship=MISSING"

# --- 4. a modded item, held and used by hand -------------------------------------------------------
echo "== 4. modded item in hand"
chat "/gamemode creative"
chat "/give @s create:cogwheel 8"
chat "/tp @s $((X+5)) $((Y+1)) $((Z+8)) 0 89"
sleep 2
alive "before placing by hand"
xdotool click 3; sleep 3                                    # right-click: place the cogwheel
chat "/execute if block $((X+5)) $Y $((Z+8)) create:cogwheel run say ETURLIA_TEST place_modded_by_hand=YES"
chat "/execute unless block $((X+5)) $Y $((Z+8)) create:cogwheel run say ETURLIA_TEST place_modded_by_hand=NO"
chat "/gamemode survival"
sleep 1
alive "before breaking by hand"
xdotool mousedown 1; sleep 7; xdotool mouseup 1; sleep 3     # hold left-click: break it
chat "/execute unless block $((X+5)) $Y $((Z+8)) create:cogwheel run say ETURLIA_TEST break_modded_by_hand=YES"
chat "/execute if block $((X+5)) $Y $((Z+8)) create:cogwheel run say ETURLIA_TEST break_modded_by_hand=NO"

# --- 5. damage --------------------------------------------------------------------------------------
echo "== 5. damage"
chat "/effect clear @s"
chat "/damage @s 6 minecraft:generic"
sleep 2
chat "/execute unless entity @s[nbt={Health:20.0f}] run say ETURLIA_TEST damage=TAKEN"
chat "/execute if entity @s[nbt={Health:20.0f}] run say ETURLIA_TEST damage=NONE"

# --- 6. what the player looks like now (the trail the operator photographed) -----------------------
echo "== 6. particles behind the player"
chat "/effect give @s minecraft:instant_health 1 10"
xdotool keydown w; sleep 3; xdotool keyup w; sleep 1
shot trails_after_fix

# --- 7. the void, last: the death screen stops everything after it ---------------------------------
echo "== 7. the void"
MARK4=$(wc -l < "$LOG")
chat "/tp @s $X -180 $Z"
sleep 15
tail -n +$MARK4 "$LOG" | grep -aq "$NAME fell out of the world" \
    && echo "RESULT void_death=DIES" \
    || echo "RESULT void_death=SURVIVES_THE_VOID"

echo "== verdicts:"
tail -n +$MARK3 "$LOG" | grep -a 'ETURLIA_TEST' | sed 's/.*ETURLIA_TEST //' | sort -u
echo "== alarming since the client joined:"
tail -n +$MARK "$LOG" | grep -aE 'NoSuchMethodError|AbstractMethodError|IncompatibleClassChange|ClassCastException|failed to tick|Tile is null' \
    | sed 's/.*\]: //' | sort -u | head -8 | cut -c1-170
echo "(nothing under alarming means the run was clean)"
