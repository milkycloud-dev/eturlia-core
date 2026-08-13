#!/usr/bin/env bash
# Create Aeronautics, end to end, with a real client.
#
# Sable's own commands are the way in: /sable spawn, /sable physics, /sable assemble,
# /sable storage. They take a sub-level selector, which only resolves from a player - from the
# console they all answer `argument.sable.sub_level.invalid`. So this drives them through the
# headless client's keyboard, and reads the answers three ways:
#
#   * a player's /say lands in the server log, so entity-selector assertions are visible
#   * a screenshot shows what the physics scene actually looks like
#   * the log is checked for sable's own errors after every phase
#
#   tools/aerotest.sh [x] [z]
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
CLOG=$N/client/client.log
NAME=EturliaTester
PASS=${ETURLIA_TEST_PASS:-Eturlia2026test}
X=${1:-320}; Y=95; Z=${2:-320}
say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }
shot () { import -window root "/tmp/aero_$1.png" 2>/dev/null; echo "   [shot] /tmp/aero_$1.png"; }

# --- ground and rigs, built before the client connects --------------------------------------------
echo "== 0. the site"
say "op $NAME"
say "forceload add $((X-48)) $((Z-48)) $((X+48)) $((Z+48))"
sleep 20
say "fill $((X-12)) $Y $((Z-12)) $((X+20)) $((Y+20)) $((Z+12)) minecraft:air"
say "fill $((X-12)) $((Y-1)) $((Z-12)) $((X+20)) $((Y-1)) $((Z+12)) minecraft:stone"
sleep 4
# An Aeronautics airship: a propeller bearing with a propeller, driven by a creative motor.
say "setblock $((X+8)) $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $((X+8)) $Y $Z aeronautics:propeller_bearing[facing=up]"
say "setblock $((X+8)) $((Y+1)) $Z aeronautics:wooden_propeller"
# A loose structure for /sable assemble connected to pick up.
say "fill $((X+14)) $Y $((Z-1)) $((X+16)) $((Y+2)) $((Z+1)) minecraft:oak_planks"
sleep 4

# The account has to exist before the client connects: `authme register` from the console kicks
# whoever it just registered ("An admin just registered you; please log in again"), and everything
# typed after that goes into a dead client.
say "authme register $NAME $PASS"
sleep 4

# --- the client -----------------------------------------------------------------------------------
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
    ps -eo args | grep -q '[j]ava-runtime-delta' || { echo "[client] died before joining"; break; }
done
[ "$joined" = 1 ] || { echo "RESULT join=NO"; tail -10 client.log | cut -c1-170; exit 1; }
echo "RESULT join=YES"
# Re-anchor here: the client killed at the start of this run logs its own "lost connection" a few
# seconds later, and anything anchored before that reads the previous client's death as this one's.
sleep 8
MARK=$(wc -l < "$LOG")

CPID=$(ps -eo pid,args | grep '[j]ava-runtime-delta' | awk '{print $1}' | head -1)
export DISPLAY=$(strings /proc/$CPID/environ | grep '^DISPLAY=' | head -1 | cut -d= -f2)
export XAUTHORITY=$(strings /proc/$CPID/environ | grep '^XAUTHORITY=' | head -1 | cut -d= -f2)
WIN=$(xdotool search --name Minecraft | tail -1)
[ -n "$WIN" ] || { echo "RESULT window=NOT_FOUND"; exit 1; }
xdotool windowfocus "$WIN"

ready=0
for _ in $(seq 1 30); do
    say "say ETURLIA_READY_PROBE"
    sleep 6
    grep -aq 'ETURLIA_READY_PROBE' "$CLOG" && { ready=1; break; }
    ps -eo args | grep -q '[j]ava-runtime-delta' || break
done
[ "$ready" = 1 ] || { echo "RESULT client_in_world=NO"; tail -6 "$CLOG" | cut -c1-150; exit 1; }
sleep 25
echo "RESULT client_in_world=YES"

alive () {
    ps -eo args | grep -q '[j]ava-runtime-delta' || { echo "!! client gone, nothing below was typed ($1)"; exit 3; }
    if tail -n +$MARK "$LOG" | grep -aq "$NAME lost connection"; then
        echo "!! client disconnected, nothing below was typed ($1)"
        tail -n +$MARK "$LOG" | grep -a 'lost connection' | tail -1 | sed 's/.*\]: //' | cut -c1-110
        exit 3
    fi
}
chat () { alive "$1"; xdotool key t; sleep 1; xdotool type --delay 35 "$1"; sleep 1; xdotool key Return; sleep 3; }
phase_errors () {   # phase_errors <from-line> <label>
    local bad
    bad=$(tail -n +"$1" "$LOG" | grep -aE 'argument\.sable|sable.*fail|Exception|failed to tick|IncompatibleClassChange|ctor extras' \
          | grep -aviE 'voicechat|version check' | sed 's/.*\]: //' | sort -u | head -3 | cut -c1-140)
    if [ -n "$bad" ]; then echo "   errors in $2:"; echo "$bad" | sed 's/^/     /'; else echo "   no errors in $2"; fi
}

# Authenticate and make the tester safe from the console, so none of it depends on the keyboard.
# An unauthenticated or dead player cannot type: AuthMe swallows input, and the death screen eats
# every keystroke - which is exactly how an earlier run "passed" every phase without sending one
# command.
say "lp user $NAME permission set minecraft.command.say true"
say "lp user $NAME permission set minecraft.command.execute true"
say "lp user $NAME permission set minecraft.command.summon true"
say "lp user $NAME permission set minecraft.command.gamemode true"
say "lp user $NAME permission set minecraft.command.tp true"
say "lp user $NAME permission set minecraft.command.effect true"
say "lp user $NAME permission set minecraft.command.scoreboard true"
sleep 3
say "authme forcelogin $NAME"
sleep 3
say "gamemode creative $NAME"
say "effect give $NAME minecraft:resistance 99999 4 true"
say "effect give $NAME minecraft:fire_resistance 99999 1 true"
sleep 2

# Simple Voice Chat's setup wizard swallows keystrokes until it is answered; the death screen does
# the same. Answer both, then prove the keyboard reaches the game before trusting anything.
xdotool mousemove 160 180 click 1; sleep 3
xdotool mousemove 203 210 click 1; sleep 4
xdotool mousemove 160 141 click 1; sleep 4      # "Respawn", if the tester died on the way in
xdotool key Escape; sleep 2

canary=0
for attempt in 1 2 3; do
    P=$(wc -l < "$LOG")
    xdotool windowfocus "$WIN"; sleep 1
    xdotool key t; sleep 1; xdotool type --delay 35 "/say ETURLIA_CANARY"; sleep 1; xdotool key Return
    sleep 4
    if tail -n +"$P" "$LOG" | grep -a 'ETURLIA_CANARY' | grep -avq 'issued server command'; then canary=1; break; fi
    echo "   canary attempt $attempt: the keyboard is not reaching the game"
    shot "canary_$attempt"
    xdotool key Escape; sleep 1
    xdotool mousemove 160 141 click 1; sleep 3
done
[ "$canary" = 1 ] || { echo "RESULT keyboard=NO (see the canary screenshots)"; exit 4; }
echo "RESULT keyboard=YES"

say "tp $NAME $((X+2)) $((Y+1)) $((Z+2))"
sleep 12
alive "after the teleport"

# --- 1. does sable answer a player at all ----------------------------------------------------------
echo "== 1. sable, asked by a player"
P=$(wc -l < "$LOG")
chat "/sable storage find_all_sub_levels"
chat "/sable info"
phase_errors "$P" "sable storage/info"

# --- 5. the Aeronautics airship -----------------------------------------------------------------------
echo "== 5. the airship"
P=$(wc -l < "$LOG")
say "tp $NAME $((X+4)) $((Y+2)) $((Z+4))"
sleep 8
alive "before powering the bearing"
say "setblock $((X+7)) $Y $Z minecraft:redstone_block"
sleep 12
chat "/execute if entity @e[type=aeronautics:propeller_bearing_contraption,distance=..48] run say ETURLIA_TEST airship=ASSEMBLED"
chat "/execute unless entity @e[type=aeronautics:propeller_bearing_contraption,distance=..48] run say ETURLIA_TEST airship=MISSING"
chat "/execute if entity @e[type=create:stationary_contraption,distance=..48] run say ETURLIA_TEST create_contraption=PRESENT"
shot airship
phase_errors "$P" "airship"

# --- 4. assemble a structure into a sub-level --------------------------------------------------------
echo "== 4. assemble connected"
P=$(wc -l < "$LOG")
say "tp $NAME $((X+15)) $((Y+3)) $Z"
sleep 8
alive "before assemble"
chat "/sable assemble connected"
sleep 6
chat "/execute unless block $((X+15)) $((Y+1)) $Z minecraft:oak_planks run say ETURLIA_TEST assemble_took_blocks=YES"
chat "/execute if block $((X+15)) $((Y+1)) $Z minecraft:oak_planks run say ETURLIA_TEST assemble_took_blocks=NO"
shot assembled
phase_errors "$P" "sable assemble"

# --- 2. a physics scene ------------------------------------------------------------------------------
echo "== 2. spawn a physics scene"
P=$(wc -l < "$LOG")
chat "/sable spawn block"
sleep 4
shot spawn_block
phase_errors "$P" "sable spawn"

# --- 3. is the physics actually stepping ------------------------------------------------------------
echo "== 3. physics steps"
P=$(wc -l < "$LOG")
chat "/sable storage find_all_sub_levels"
chat "/sable paused"
sleep 3
shot physics_paused
chat "/sable paused"
sleep 3
shot physics_running
phase_errors "$P" "pause toggle"

# --- 6. the debug scene that is known to abort the JVM through Rapier - always last -----------------
echo "== 6. sable spawn joint_test (known native abort, runs last on purpose)"
P=$(wc -l < "$LOG")
chat "/sable spawn joint_test"
sleep 6
shot joint_test
phase_errors "$P" "joint_test"

# --- 6. what the client says about sable's own networking ---------------------------------------------
echo "== 6. sable networking, client side"
grep -aE 'UDP|Sable' "$CLOG" | tail -6 | sed 's/.*\]: //' | cut -c1-120 | sed 's/^/   /'

echo "== proof the keyboard worked: $(tail -n +$MARK "$LOG" | grep -ac "$NAME issued server command") commands issued by the tester"
shot behind_player
# The server can restart mid-run (sable's native side aborts the JVM), which rotates the log out
# from under us. Harvest from the rotated one too, or a whole run reports nothing.
# The server echoes a command as it is issued, so the text of `/execute if ... run say X` is in the
# log whether or not the condition held. Only the broadcast counts as an answer.
harvest () { { cat "$LOG"; zcat -f $(ls -t "$N"/server/logs/*.log.gz 2>/dev/null | grep -v debug | head -2) 2>/dev/null; } | grep -a "$1" | grep -av 'issued server command'; }
echo "== verdicts:"
harvest 'ETURLIA_TEST' | sed 's/.*ETURLIA_TEST //' | sort -u
echo "== the tester's own commands this run:"
harvest "$NAME issued server command" | sed 's/.*command: //' | sort -u | tail -14
echo "== alarming for the whole session:"
tail -n +$MARK "$LOG" | grep -aE 'NoSuchMethodError|AbstractMethodError|IncompatibleClassChange|ClassCastException|failed to tick|Tile is null|ctor extras' \
    | sed 's/.*\]: //' | sort -u | head -6 | cut -c1-165
echo "(nothing above means the run was clean)"
