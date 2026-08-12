#!/usr/bin/env bash
# Gameplay checks with no player and no client.
#
# Folia refuses entity selectors typed at the console - the console runs on the global region and
# cannot look at entities owned by a world region. A command block can: it runs on the region that
# owns the block it sits in. So every assertion goes into a repeating command block, the block
# answers by placing a marker, and the console reads the marker back - console `setblock` is the
# only one of the three whose "Changed the block at x, y, z" reaches the log.
#
#   tools/cmdtest.sh [x] [z]
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
X=${1:-60}; Y=85; Z=${2:-60}
MX=$((X + 20)); MZ=$Z          # the marker column, well away from anything being tested
PROBE=150                      # where the console writes its own yes/no
say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }

cb () {   # cb <x> <y> <z> <command>   - single quotes only: screen's -X stuff eats double ones
    say "setblock $1 $2 $3 minecraft:repeating_command_block{Command:'$4',auto:1b}"
}
clear_cbs () { say "fill $((X-2)) $((Y+5)) $((Z-2)) $((X+12)) $((Y+5)) $((Z+2)) minecraft:air"; }
clear_markers () { say "fill $MX 100 $MZ $MX 110 $MZ minecraft:air"; }

check () {   # check <marker_y> <marker_block>   - true if the command block placed that marker
    say "fill $MX $PROBE $MZ $MX $PROBE $MZ minecraft:air"
    sleep 3
    local from
    from=$(wc -l < "$LOG")
    say "execute if block $MX $1 $MZ minecraft:$2 run setblock $MX $PROBE $MZ minecraft:sponge"
    sleep 4
    tail -n +"$from" "$LOG" | grep -aq "Changed the block at $MX, $PROBE, $MZ"
}

echo "== ground"
say "forceload add $((X-32)) $((Z-32)) $((X+32)) $((Z+32))"
sleep 15
say "fill $((X-8)) $Y $((Z-8)) $((X+24)) $((Y+8)) $((Z+8)) minecraft:air"
say "fill $((X-8)) $((Y-1)) $((Z-8)) $((X+24)) $((Y-1)) $((Z+8)) minecraft:stone"
say "gamerule commandBlockOutput false"
clear_markers
clear_cbs
sleep 4

# --- a mob, and whether it can be hurt -------------------------------------------------------------
echo "== damage"
say "kill @e[type=minecraft:pig]" > /dev/null 2>&1 || true
say "summon minecraft:pig $X $((Y+1)) $Z {NoAI:1b,Silent:1b,Tags:['hurtpig'],PersistenceRequired:1b}"
sleep 2
cb $X $((Y+5)) $Z "execute if entity @e[tag=hurtpig,distance=..16] run setblock $MX 100 $MZ minecraft:diamond_block"
sleep 5
check 100 diamond_block && echo "RESULT mob_present=YES" || echo "RESULT mob_present=NO (the rest of this section means nothing)"
clear_cbs
sleep 2

cb $X $((Y+5)) $Z "damage @e[tag=hurtpig,distance=..16,limit=1] 3 minecraft:generic"
cb $((X+1)) $((Y+5)) $Z "execute unless entity @e[tag=hurtpig,distance=..16] run setblock $MX 101 $MZ minecraft:emerald_block"
sleep 10
check 101 emerald_block && echo "RESULT damage=APPLIED (repeated damage killed it)" \
                        || echo "RESULT damage=NONE (the mob never lost health)"
clear_cbs

# --- the void ---------------------------------------------------------------------------------------
echo "== the void"
sleep 2
say "summon minecraft:pig $((X+4)) -40 $Z {NoAI:1b,Silent:1b,Tags:['voidpig'],PersistenceRequired:1b}"
sleep 2
cb $X $((Y+5)) $Z "execute if entity @e[tag=voidpig,distance=..400] run setblock $MX 102 $MZ minecraft:gold_block"
sleep 5
check 102 gold_block && echo "  (the void pig exists and is falling)" \
                     || echo "  !! the void pig never appeared - the next answer means nothing"
clear_cbs
sleep 15
cb $X $((Y+5)) $Z "execute unless entity @e[tag=voidpig,distance=..400] run setblock $MX 103 $MZ minecraft:lapis_block"
sleep 6
check 103 lapis_block && echo "RESULT void_death=DIES" || echo "RESULT void_death=SURVIVES_THE_VOID"
clear_cbs

# --- Create, and the Aeronautics airship --------------------------------------------------------------
echo "== Create and Aeronautics"
say "fill $((X+6)) $((Y-1)) $((Z-2)) $((X+14)) $((Y+4)) $((Z+2)) minecraft:air"
say "fill $((X+6)) $((Y-1)) $((Z-2)) $((X+14)) $((Y-1)) $((Z+2)) minecraft:stone"
sleep 4
say "setblock $((X+10)) $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $((X+10)) $Y $Z aeronautics:propeller_bearing[facing=up]"
say "setblock $((X+10)) $((Y+1)) $Z aeronautics:wooden_propeller"
sleep 3
MARK=$(wc -l < "$LOG")
say "setblock $((X+9)) $Y $Z minecraft:redstone_block"
sleep 15
cb $((X+10)) $((Y+5)) $Z "execute if entity @e[type=aeronautics:propeller_bearing_contraption,distance=..32] run setblock $MX 104 $MZ minecraft:redstone_block"
sleep 6
check 104 redstone_block && echo "RESULT aeronautics_airship=ASSEMBLED" || echo "RESULT aeronautics_airship=MISSING"
clear_cbs

echo "== alarming during this test:"
tail -n +"$MARK" "$LOG" | grep -aE 'NoSuchMethodError|AbstractMethodError|IncompatibleClassChange|ClassCastException|failed to tick|Tile is null|ctor extras' \
    | sed 's/.*\]: //' | sort -u | head -8 | cut -c1-170
echo "(nothing above means the run was clean)"
