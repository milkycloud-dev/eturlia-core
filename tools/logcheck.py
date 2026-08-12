#!/usr/bin/env python3
"""Grade one server run from latest.log: group every WARN/ERROR, hide the ones already judged
benign, and print a short verdict. Run after every start - it is the test.

  python3 tools/logcheck.py            # this run
  python3 tools/logcheck.py --all      # do not hide the known-benign groups
"""
import collections
import os
import re
import sys

LOG = "/home/user/milky/eturlia_new/server/logs/latest.log"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
LEVEL = re.compile(r"^\[[^\]]*\] \[([^/\]]+)/(WARN|ERROR|FATAL)\]")
ETURLIA = re.compile(r"\[Eturlia\] (WARN|ERROR) ")
NUMBERS = re.compile(r"-?\d+")

# Judged benign, with the reason. Each entry is (pattern, why).
BENIGN = [
    (r"Reference map .* could not be read", "mods ship without a refmap; Mixin resolves by name"),
    (r"Error loading class: net/minecraft/client/", "client-only class, server side has none"),
    (r"Error loading class: net/minecraftforge/", "mod targets Forge, we are NeoForge"),
    (r"Error loading class: (traben|tschipp|com/teamabnormals)", "client/optional mod class"),
    (r"Instancing error handler class", "Mixin announcing its error handler"),
    (r"Couldn't parse element minecraft:loot_table/(furniture|beautify)",
     "mod datapack gated by fabric:load_conditions, which NeoForge ignores"),
    (r"Parsing error loading custom advancement supplementaries:", "advancement for a mod not in the pack"),
    (r"Couldn't load tag (load:|minecraft:load)", "the 'load' convention datapack is not installed"),
    (r"Found loot table element validation problem", "mod loot table points at a mod not in the pack"),
    (r"Unknown loot table called", "mod loot table points at a mod not in the pack"),
    (r"Leaked resource:", "twilightforest closes its resources late; harmless"),
    (r"was reopened for a late registration", "Eturlia lets a mod register after the freeze on purpose"),
    (r"is a modded entity, so plugins see it as UNKNOWN", "expected under bukkit-types=lenient"),
    (r"no (items|blocks) tag .* callers see null", "expected under bukkit-types=lenient"),
    (r"Lithostitched .* allow-unsafe is set", "operator chose the override"),
    (r"Discarding @Unique", "Mixin housekeeping from a mod"),
    (r"(Invalid|Critical injection failure|Mixin apply for mod).*(vinery|wover|immersive_melodies|emotecraft|betterend|bclib)",
     "known mixin that does not fit this Paper build; that mod feature is off"),
    (r"PoiType .* was replaced after construction", "NeoForge warning about a mod's POI type"),
    (r"Invalid directory entry: pepe.jpg", "someone left an image in a resource pack folder"),
    (r"You are running an unsupported Java", "JDK 21 is what we run"),
    (r"Failed to load function", "datapack function from a mod whose optional dependency is absent"),
    (r"Selling Bin Processor", "mod's own datapack processor complaining about another mod"),
    (r"Parsing error loading recipe malum:create/", "malum ships Create recipes in the pre-1.21 shape"),
    (r"\[Debug Manager\]|Running in debug mode|Generating Debug Helpers", "mod's own debug chatter"),
    (r"@Mixin target (tschipp|traben|com/teamabnormals)", "mixin for a mod that is not installed"),
    (r"Assets URL 'union:", "ModLauncher hands assets through union://; Paper only warns"),
    (r"had \d+ intrusive holder\(s\)", "Eturlia drops holders a mod never registered, on purpose"),
    (r"a mixin could not be applied to .*client", "client-only mixin on a server"),
    (r"Couldn't load advancements", "advancement tree for a mod that is not installed"),
    (r"\*+$", "banner line"),
]
BENIGN = [(re.compile(p), why) for p, why in BENIGN]

# Never hide these, whatever else matches.
ALARMING = re.compile(
    r"NoSuchMethodError|AbstractMethodError|IncompatibleClassChangeError|NoClassDefFoundError"
    r"|ClassCastException|failed to tick|Region .* failed|Watchdog|OutOfMemory"
    r"|Error occurred while enabling|Could not pass event|deferred main-thread task failed")


def key(line):
    body = line.split("]: ", 1)[-1]
    return NUMBERS.sub("#", body).strip()[:200]


def main():
    show_all = "--all" in sys.argv
    path = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else LOG
    if not os.path.exists(path):
        print("no log at", path)
        return 2

    groups = collections.Counter()
    sample = {}
    alarming = collections.Counter()
    alarm_sample = {}
    total = 0

    with open(path, encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = ANSI.sub("", raw.rstrip())
            if not (LEVEL.match(line) or ETURLIA.search(line)):
                continue
            total += 1
            k = key(line)
            if ALARMING.search(line):
                alarming[k] += 1
                alarm_sample.setdefault(k, line[:230])
                continue
            if not show_all and any(rx.search(line) for rx, _ in BENIGN):
                continue
            groups[k] += 1
            sample.setdefault(k, line[:230])

    print("=== %d WARN/ERROR lines in %s" % (total, os.path.basename(path)))
    if alarming:
        print("--- ALARMING (%d kinds):" % len(alarming))
        for k, count in alarming.most_common(15):
            print("  x%-5d %s" % (count, alarm_sample[k]))
    else:
        print("--- ALARMING: none")

    if groups:
        print("--- unjudged (%d kinds, worth a look):" % len(groups))
        for k, count in groups.most_common(20):
            print("  x%-5d %s" % (count, sample[k]))
    else:
        print("--- unjudged: none")

    return 1 if alarming else 0


if __name__ == "__main__":
    sys.exit(main())
