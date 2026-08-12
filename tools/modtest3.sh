#!/usr/bin/env bash
# Console-only mod test. Folia refuses entity selectors from the console, so every assertion here
# is about blocks - which is enough: a Create contraption assembles by taking its blocks out of the
# world, and that is exactly the path that used to die on Level.markAndNotifyBlock.
set -uo pipefail
N=/home/user/milky/eturlia_new
LOG=$N/server/logs/latest.log
say () { bash "$N/tools/testctl.sh" say "$*" > /dev/null; }
X=${1:-1200}; Y=100; Z=${2:-1200}
W="minecraft:overworld"
MARK=$(wc -l < "$LOG")

say "forceload add $((X-32)) $((Z-32)) $((X+32)) $((Z+32))"
sleep 10
say "fill $((X-5)) $Y $((Z-5)) $((X+5)) $((Y+8)) $((Z+5)) minecraft:air"
say "fill $((X-5)) $((Y-1)) $((Z-5)) $((X+5)) $((Y-1)) $((Z+5)) minecraft:stone"
sleep 3

# A bearing assembles only when it has rotational force AND a redstone signal, so give it a
# creative motor underneath (its output faces the bearing's input).
say "setblock $X $((Y-1)) $Z create:creative_motor[facing=up]"
say "setblock $X $Y $Z create:mechanical_bearing[facing=up]"
say "setblock $X $((Y+1)) $Z minecraft:oak_planks"
say "setblock $((X+1)) $((Y+1)) $Z minecraft:oak_planks"
sleep 3
say "execute in $W if block $X $((Y+1)) $Z minecraft:oak_planks run setblock $((X+9)) $((Y+1)) $Z minecraft:diamond_block"   # marker: planks placed
say "setblock $((X-1)) $Y $Z minecraft:redstone_block"
sleep 10
say "execute in $W if block $X $((Y+1)) $Z minecraft:air run setblock $((X+9)) $((Y+2)) $Z minecraft:emerald_block"   # marker: contraption assembled
say "execute in $W if block $X $((Y+1)) $Z minecraft:oak_planks run setblock $((X+9)) $((Y+3)) $Z minecraft:redstone_block"   # marker: NOT assembled
sleep 3

# Disassemble: cutting power puts the blocks back, which is the same path in reverse.
say "setblock $((X-1)) $Y $Z minecraft:air"
sleep 8
say "execute in $W if block $X $((Y+1)) $Z minecraft:oak_planks run setblock $((X+9)) $((Y+4)) $Z minecraft:gold_block"   # marker: blocks returned
sleep 3

# Modded blocks with block entities: place, then confirm they are still the block they should be.
for b in create:shaft create:cogwheel create:andesite_casing aeronautics:propeller_bearing create:creative_motor; do
    n=${b%%:*}; i=${b##*:}
    say "setblock $((X+3)) $Y $((Z+3)) $b"
    sleep 1
    say "execute in $W if block $((X+3)) $Y $((Z+3)) $b run setblock $((X+9)) $((Y+6)) $Z minecraft:lapis_block"   # marker: $i placed
    sleep 1
    say "execute in $W if block $((X+9)) $((Y+6)) $Z minecraft:lapis_block run setblock $((X+9)) $((Y+6)) $Z minecraft:air"
    sleep 1
done

# Spawning, vanilla and modded, plus the aeronautics entities.
for e in minecraft:cow alexsmobs:potoo twilightforest:deer aeronautics:gust simulated:honey_glue create:seat; do
    say "execute in $W run summon $e $((X+4)) $((Y+1)) $((Z+4))"
    sleep 1
done
sleep 3

echo "=== verdicts (a marker block means the assertion was true):"
tail -n +"$MARK" "$LOG" | grep -a "Changed the block at $((X+9))" | sed 's/.*\]: //' | sort | uniq -c
echo "  planks placed  -> $((X+9)) $((Y+1)) $Z"
echo "  ASSEMBLED      -> $((X+9)) $((Y+2)) $Z"
echo "  NOT assembled  -> $((X+9)) $((Y+3)) $Z"
echo "  disassembled   -> $((X+9)) $((Y+4)) $Z"
echo "  modded block   -> $((X+9)) $((Y+6)) $Z (once per block, cleared between)"
echo "=== summons:"
tail -n +"$MARK" "$LOG" | grep -aE 'Summoned new|Unable to summon' | sed 's/.*\]: //' | sort | uniq -c | head -10
echo "=== alarming during test:"
tail -n +"$MARK" "$LOG" | grep -aE 'NoSuchMethodError|AbstractMethodError|IncompatibleClassChange|ClassCastException|failed to tick|deferred main-thread' \
    | sed 's/.*\]: //' | sort -u | head -6 | cut -c1-165
echo "(nothing under 'alarming' means the run was clean)"
