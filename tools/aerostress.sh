#!/usr/bin/env bash
# The wide sweep over Create, Create Aeronautics and sable: everything a player can do to a moving
# machine, and the edge cases that break the ones a player cannot.
#
# Every assertion carries this run's id, so a server restart in the middle (sable's native side can
# still abort the JVM) cannot make an older run's verdict look like this one's.
#
# Deliberately does NOT run `/sable spawn joint_test` - that scene panics inside Rapier's buoyancy
# code, and a non-unwinding panic aborts the whole JVM. Use tools/aerotest.sh for that one.
#
#   tools/aerostress.sh [x] [z]
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
CLOG=$N/client/client.log
NAME=EturliaTester
PASS=${ETURLIA_TEST_PASS:-Eturlia2026test}
RUN=$(date +%H%M%S)
X=${1:-520}; Y=95; Z=${2:-520}
say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }
shot () { import -window root "/tmp/stress_$1.png" 2>/dev/null; echo "   [shot] /tmp/stress_$1.png"; }
# The server echoes a command as it is issued, so the text of `/execute if ... run say X` is in the
# log whether or not the condition held. Only the broadcast counts as an answer.
harvest () { { cat "$LOG"; zcat -f $(ls -t "$N"/server/logs/*.log.gz 2>/dev/null | grep -v debug | head -3) 2>/dev/null; } | grep -a "$1" | grep -av 'issued server command'; }

echo "=== run $RUN, site $X/$Z"

# --- the site, built before anyone connects ---------------------------------------------------------
say "op $NAME"
say "forceload add $((X-64)) $((Z-64)) $((X+64)) $((Z+64))"
sleep 22
say "fill $((X-20)) $Y $((Z-20)) $((X+40)) $((Y+24)) $((Z+20)) minecraft:air"
say "fill $((X-20)) $((Y-1)) $((Z-20)) $((X+40)) $((Y-1)) $((Z+20)) minecraft:stone"
say "fill $((X+34)) 150 $Z $((X+34)) 200 $Z minecraft:air"
sleep 5
say "authme register $NAME $PASS"
sleep 3

# rig A: a platform big enough to stand on, on a mechanical bearing, driven by a creative motor
say "setblock $X $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $X $Y $Z create:mechanical_bearing[facing=up]"
say "fill $((X-2)) $((Y+1)) $((Z-2)) $((X+2)) $((Y+1)) $((Z+2)) minecraft:oak_planks"
# rig B: a kinetic chain 16 shafts long, ending in a second bearing - does rotation travel that far
say "setblock $((X+12)) $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $((X+12)) $Y $Z create:shaft[axis=y]"
say "fill $((X+12)) $((Y+1)) $Z $((X+12)) $((Y+14)) $Z create:shaft[axis=y]"
say "setblock $((X+12)) $((Y+15)) $Z create:mechanical_bearing[facing=up]"
say "fill $((X+11)) $((Y+16)) $((Z-1)) $((X+13)) $((Y+16)) $((Z+1)) minecraft:oak_planks"
# rig C: an Aeronautics propeller bearing, straddling a chunk border on purpose
CX=$(( (X/16 + 2) * 16 ))
say "setblock $CX $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $CX $Y $Z aeronautics:propeller_bearing[facing=up]"
say "setblock $CX $((Y+1)) $Z aeronautics:wooden_propeller"
say "fill $((CX-1)) $((Y+1)) $((Z-1)) $((CX+1)) $((Y+1)) $((Z+1)) create:andesite_casing"
# rig D: a bearing facing sideways - the other rotation axis
say "setblock $((X+24)) $((Y+2)) $Z create:creative_motor[facing=north]"
say "setblock $((X+24)) $((Y+2)) $((Z+1)) create:mechanical_bearing[facing=south]"
say "fill $((X+23)) $((Y+2)) $((Z+2)) $((X+25)) $((Y+4)) $((Z+2)) minecraft:oak_planks"
sleep 5

# --- the client ---------------------------------------------------------------------------------
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
[ "$joined" = 1 ] || { echo "RESULT join=NO"; tail -8 client.log | cut -c1-160; exit 1; }
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
[ "$ready" = 1 ] || { echo "RESULT client_in_world=NO"; exit 1; }
sleep 25
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
sleep 2
xdotool mousemove 160 180 click 1; sleep 3
xdotool mousemove 203 210 click 1; sleep 4
xdotool mousemove 160 141 click 1; sleep 3
xdotool key Escape; sleep 2

alive () {
    ps -eo args | grep -q '[j]ava-runtime-delta' || { echo "!! client gone ($1)"; return 1; }
    tail -n +$MARK "$LOG" | grep -aq "$NAME lost connection" && { echo "!! client disconnected ($1)"; return 1; }
    return 0
}
chat () { alive "$1" || return 1; xdotool key t; sleep 1; xdotool type --delay 32 "$1"; sleep 1; xdotool key Return; sleep 3; }
# `/say` is useless as a marker on this build: the log keeps the translation key
# (chat.type.announcement) and drops the text. Command *feedback* from a player is logged in full -
# "[EturliaTester: Changed the block at x, y, z]" - so each assertion places its own block in a
# private column and is read back by those coordinates.
MX=$((X + 34)); MZ=$Z; MY=150
MARKS=/tmp/marks_$RUN
: > "$MARKS"
mark () {   # mark <execute-condition> <label>
    MY=$((MY + 1))
    echo "$MY $2" >> "$MARKS"
    chat "/execute $1 run setblock $MX $MY $MZ minecraft:sponge"
}

canary=0
for attempt in 1 2 3; do
    P=$(wc -l < "$LOG")
    xdotool windowfocus "$WIN"; sleep 1
    xdotool key t; sleep 1; xdotool type --delay 32 "/say ETURLIA_CANARY $RUN"; sleep 1; xdotool key Return
    sleep 4
    tail -n +"$P" "$LOG" | grep -a "ETURLIA_CANARY $RUN" | grep -avq 'issued server command' && { canary=1; break; }
    xdotool key Escape; sleep 1; xdotool mousemove 160 141 click 1; sleep 3
done
[ "$canary" = 1 ] || { echo "RESULT keyboard=NO"; shot keyboard_dead; exit 4; }
echo "RESULT keyboard=YES"

# --- 1. a big contraption assembles, and the player rides it ---------------------------------------
echo "== 1. riding a moving contraption"
say "tp $NAME $((X+1)) $((Y+2)) $((Z+1))"
sleep 10
say "setblock $((X-1)) $Y $Z minecraft:redstone_block"
sleep 10
mark "if entity @e[type=create:stationary_contraption,distance=..24]" "big_contraption=ASSEMBLED"
mark "unless entity @e[type=create:stationary_contraption,distance=..24]" "big_contraption=MISSING"
mark "if block $X $((Y+1)) $Z minecraft:air" "platform_lifted=YES"
# standing on a rotating platform: the player should be carried, so their yaw/position moves with it
chat "/execute store result score $NAME etx run data get entity @s Pos[0] 100"
sleep 8
chat "/execute store result score $NAME etx2 run data get entity @s Pos[0] 100"
mark "if score $NAME etx = $NAME etx2" "carried_by_contraption=NO"
mark "unless score $NAME etx = $NAME etx2" "carried_by_contraption=YES"
shot riding

# --- 2. an entity standing on the same contraption --------------------------------------------------
echo "== 2. an entity on the contraption"
chat "/summon minecraft:cow $((X+1)) $((Y+3)) $((Z+1)) {NoAI:1b,Tags:[\"stressco\"]}"
sleep 8
mark "if entity @e[tag=stressco,distance=..24]" "entity_on_contraption=ALIVE"
mark "unless entity @e[tag=stressco,distance=..24]" "entity_on_contraption=GONE"

# --- 3. rotation through sixteen shafts --------------------------------------------------------------
echo "== 3. kinetic chain, 16 shafts"
say "tp $NAME $((X+12)) $((Y+18)) $((Z+4))"
sleep 10
alive "before the long chain" || true
say "setblock $((X+11)) $((Y+15)) $Z minecraft:redstone_block"
sleep 12
mark "if block $((X+12)) $((Y+16)) $Z minecraft:air" "kinetics_through_16_shafts=YES"
mark "if block $((X+12)) $((Y+16)) $Z minecraft:oak_planks" "kinetics_through_16_shafts=NO"

# --- 4. an airship on a chunk border ------------------------------------------------------------------
echo "== 4. airship across a chunk border ($CX is a chunk boundary)"
say "tp $NAME $((CX+3)) $((Y+2)) $((Z+3))"
sleep 10
say "setblock $((CX-1)) $Y $Z minecraft:redstone_block"
sleep 14
mark "if entity @e[type=aeronautics:propeller_bearing_contraption,distance=..32]" "airship_on_border=ASSEMBLED"
mark "unless entity @e[type=aeronautics:propeller_bearing_contraption,distance=..32]" "airship_on_border=MISSING"
shot airship_border

# --- 5. the other rotation axis ------------------------------------------------------------------------
echo "== 5. a sideways bearing"
say "tp $NAME $((X+24)) $((Y+4)) $((Z+6))"
sleep 10
say "setblock $((X+24)) $((Y+3)) $((Z+1)) minecraft:redstone_block"
sleep 12
mark "if entity @e[type=create:stationary_contraption,distance=..20]" "sideways_axis=ASSEMBLED"
mark "unless entity @e[type=create:stationary_contraption,distance=..20]" "sideways_axis=MISSING"

# --- 6. assemble and disassemble, over and over --------------------------------------------------------
echo "== 6. ten assemble/disassemble cycles"
say "tp $NAME $((X+4)) $((Y+3)) $((Z+4))"
sleep 8
P=$(wc -l < "$LOG")
for i in 1 2 3 4 5 6 7 8 9 10; do
    say "setblock $((X-1)) $Y $Z minecraft:air"
    sleep 2
    say "setblock $((X-1)) $Y $Z minecraft:redstone_block"
    sleep 2
done
sleep 6
mark "if entity @e[type=create:stationary_contraption,distance=..24]" "survives_ten_cycles=YES"
cycles=$(tail -n +"$P" "$LOG" | grep -acE 'failed to tick|Exception|threw exception')
echo "   errors during the ten cycles: $cycles"

# --- 7. breaking a block while the machine runs ----------------------------------------------------------
echo "== 7. breaking a block by hand next to a running machine"
chat "/gamemode survival"
chat "/tp @s $((X+4)) $((Y+1)) $((Z+4)) 0 89"
sleep 2
say "setblock $((X+4)) $Y $((Z+4)) create:andesite_casing"
sleep 2
alive "before breaking" || true
xdotool mousedown 1; sleep 7; xdotool mouseup 1; sleep 3
mark "unless block $((X+4)) $Y $((Z+4)) create:andesite_casing" "break_modded_by_hand=YES"
mark "if block $((X+4)) $Y $((Z+4)) create:andesite_casing" "break_modded_by_hand=NO"
chat "/gamemode creative"

# --- 8. the trails, on camera -----------------------------------------------------------------------------
echo "== 8. what follows the player now"
chat "/effect give @s minecraft:glowing 5 0"
xdotool keydown w; sleep 4; xdotool keyup w; sleep 1
shot trails_front
chat "/tp @s ~ ~ ~ 180 30"
sleep 2
shot trails_behind

echo "== verdicts for run $RUN:"
while read -r y label; do
    if harvest "Changed the block at $MX, $y, $MZ" | grep -aq "$NAME"; then
        echo "   $label"
    fi
done < "$MARKS"
echo "== assertions that never fired (condition false, or the command never ran):"
while read -r y label; do
    harvest "Changed the block at $MX, $y, $MZ" | grep -aq "$NAME" || echo "   - $label"
done < "$MARKS"
echo "== alarming since the client joined:"
tail -n +$MARK "$LOG" | grep -aE 'NoSuchMethodError|AbstractMethodError|IncompatibleClassChange|failed to tick|Tile is null|ctor extras|panicked' \
    | sed 's/.*\]: //' | sort -u | head -6 | cut -c1-165
echo "(nothing above means the run was clean)"
