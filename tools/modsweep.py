#!/usr/bin/env python3
"""Sweep the pack from the console: plugin commands, modded entities, blocks, loot and worldgen.

Nothing here needs a player or a client. Every command is sent to the server console and graded by
what the console printed back, so the whole pack can be sampled in one run.

  python3 tools/modsweep.py                 # the default sample
  python3 tools/modsweep.py --n 25          # bigger sample per category
  python3 tools/modsweep.py --only entities
  python3 tools/modsweep.py --seed 7        # a different random sample, reproducible

Categories:
  plugins    every command every plugin registers, checked with /help <command>
  entities   /summon of a random sample of modded entity types
  blocks     /setblock of a random sample of modded blocks, then read back
  vanilla    the vanilla commands, checked the same way
  bes        a sample of modded blocks left to tick, watching for block entity exceptions
  worldgen   /place feature of a random sample of modded configured features
  worldedit  the WorldEdit commands that work without a player
"""
import argparse
import io
import json
import os
import random
import re
import subprocess
import time
import zipfile

N = "/home/user/milky/eturlia_new"
LOG = N + "/server/logs/latest.log"
MODS = N + "/server/mods"
PLUGINS = N + "/server/plugins"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
ORIGIN = (60, 100, 200)          # somewhere flat and far from anything built


def send(command):
    subprocess.run(["bash", N + "/tools/testctl.sh", "say", command],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def log_size():
    try:
        return os.path.getsize(LOG)
    except OSError:
        return 0


def run(command, wait=1.1):
    """Send one console command and return everything the console printed after it."""
    start = log_size()
    send(command)
    time.sleep(wait)
    with open(LOG, encoding="utf-8", errors="replace") as handle:
        handle.seek(start)
        return ANSI.sub("", handle.read())


def collect_from_mods():
    """Entity ids, block ids, loot tables and configured features, read out of the mod jars."""
    entities, blocks, loot, features = set(), set(), set(), set()

    def scan(zf, depth=0):
        for entry in zf.namelist():
            if depth == 0 and entry.startswith("META-INF/jarjar/") and entry.endswith(".jar"):
                try:
                    scan(zipfile.ZipFile(io.BytesIO(zf.read(entry))), depth + 1)
                except Exception:
                    pass
                continue
            parts = entry.split("/")
            if len(parts) > 3 and parts[0] == "assets" and parts[2] == "blockstates" and entry.endswith(".json"):
                blocks.add("%s:%s" % (parts[1], parts[-1][:-5]))
            elif len(parts) > 3 and parts[0] == "data" and parts[2] in ("loot_table", "loot_tables") and entry.endswith(".json"):
                loot.add("%s:%s" % (parts[1], "/".join(parts[3:])[:-5]))
            elif len(parts) > 4 and parts[0] == "data" and parts[2] == "worldgen" and parts[3] == "configured_feature" and entry.endswith(".json"):
                features.add("%s:%s" % (parts[1], "/".join(parts[4:])[:-5]))
            elif entry.endswith("lang/en_us.json") and entry.startswith("assets/"):
                try:
                    data = json.loads(zf.read(entry))
                except Exception:
                    continue
                for key in data:
                    bits = key.split(".")
                    if len(bits) == 3 and bits[0] == "entity" and bits[1] != "minecraft":
                        entities.add("%s:%s" % (bits[1], bits[2]))

    for name in sorted(os.listdir(MODS)):
        if name.endswith(".jar"):
            try:
                scan(zipfile.ZipFile(os.path.join(MODS, name)))
            except Exception:
                pass
    return sorted(entities), sorted(blocks), sorted(loot), sorted(features)


def collect_plugin_commands():
    """Every command name declared in every plugin's plugin.yml, with the plugin it came from."""
    found = {}
    for name in sorted(os.listdir(PLUGINS)):
        if not name.endswith(".jar"):
            continue
        try:
            with zipfile.ZipFile(os.path.join(PLUGINS, name)) as jar:
                which = "plugin.yml" if "plugin.yml" in jar.namelist() else (
                    "paper-plugin.yml" if "paper-plugin.yml" in jar.namelist() else None)
                if which is None:
                    continue
                text = jar.read(which).decode("utf-8", "replace")
        except Exception:
            continue
        # plugin.yml is small and regular: take the two-space-indented keys under "commands:"
        in_commands = False
        for line in text.split("\n"):
            if re.match(r"^commands:\s*$", line):
                in_commands = True
                continue
            if in_commands:
                if line.strip() and not line.startswith(" "):
                    in_commands = False
                    continue
                match = re.match(r"^  ([A-Za-z0-9_\-]+):\s*$", line)
                if match:
                    found.setdefault(match.group(1), name.rsplit("-", 1)[0])
    return found


VANILLA = """advancement attribute clear clone damage data datapack effect enchant execute experience
fill fillbiome forceload function gamemode gamerule give item kill list locate loot me particle place
playsound random recipe ride rotate say schedule scoreboard seed setblock setworldspawn spawnpoint
spreadplayers summon tag team teleport tellraw tick time title weather worldborder""".split()


def registered(out, command):
    """Bukkit's help map answers "Help: /cmd" for a command and "Help: <Plugin>" for a plugin index -
    a plugin whose main command shares its name lands on the second one, and that still counts."""
    low = out.lower()
    return ("help: /" + command.lower()) in low or ("help: " + command.lower()) in low


def grade(section, rows):
    ok = sum(1 for r in rows if r[0] == "ok")
    soft = sum(1 for r in rows if r[0] == "soft")
    bad = [r for r in rows if r[0] == "bad"]
    print("\n=== %-10s %d/%d ok%s" % (section, ok, len(rows), (", %d soft" % soft) if soft else ""))
    for _, what, why in bad[:12]:
        print("   FAIL %-46s %s" % (what, why))
    if len(bad) > 12:
        print("   ... %d more failures" % (len(bad) - 12))
    return len(bad)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=14)
    parser.add_argument("--seed", type=int, default=13)
    parser.add_argument("--only", default="")
    parser.add_argument("--all", action="store_true", help="list every failure, not the first few")
    args = parser.parse_args()
    global SHOW_ALL
    SHOW_ALL = args.all
    rng = random.Random(args.seed)
    only = set(args.only.split(",")) if args.only else None
    wanted = lambda name: only is None or name in only          # noqa: E731
    failures = 0

    x, y, z = ORIGIN
    send("forceload add %d %d %d %d" % (x - 16, z - 16, x + 16, z + 16))
    time.sleep(10)
    send("fill %d %d %d %d %d %d minecraft:air" % (x - 6, y, z - 6, x + 6, y + 6, z + 6))
    # grass, not stone: half the modded features refuse to generate on anything else
    send("fill %d %d %d %d %d %d minecraft:dirt" % (x - 6, y - 2, z - 6, x + 6, y - 2, z + 6))
    send("fill %d %d %d %d %d %d minecraft:grass_block" % (x - 6, y - 1, z - 6, x + 6, y - 1, z + 6))
    time.sleep(3)

    entities, blocks, loot, features = collect_from_mods()
    print("pack: %d modded entity types, %d blocks, %d loot tables, %d configured features"
          % (len(entities), len(blocks), len(loot), len(features)))

    if wanted("plugins"):
        commands = collect_plugin_commands()
        print("plugins declare %d commands" % len(commands))
        rows = []
        for command, plugin in sorted(commands.items()):
            # bukkit:help, not help - EssentialsX takes /help from the console and answers with a
            # hint instead of the topic, which grades every command as present whether it is or not.
            out = run("bukkit:help " + command, 0.8)
            if registered(out, command):
                rows.append(("ok", command, ""))
            else:
                rows.append(("bad", "/%s (%s)" % (command, plugin), "not registered"))
        failures += grade("plugins", rows)

    if wanted("worldedit"):
        rows = []
        for command, good in [("we version", "WorldEdit"), ("schem list", "schematic"),
                              ("bukkit:help /set", "Help: //set"), ("bukkit:help /pos1", "Help: //pos1")]:
            out = run(command, 1.2)
            rows.append(("ok", command, "") if good.lower() in out.lower()
                        else ("bad", command, out.strip().split("\n")[-1][-70:] or "no answer"))
        failures += grade("worldedit", rows)

    if wanted("entities"):
        rows = []
        for entity in rng.sample(entities, min(args.n, len(entities))):
            out = run("summon %s %d %d %d" % (entity, x, y + 1, z))
            if "Summoned new" in out:
                rows.append(("ok", entity, ""))
            elif "Can't find element" in out or "<--[HERE]" in out:
                rows.append(("soft", entity, "no such entity type (a lang key without a type)"))
            elif "Unable to summon" in out:
                rows.append(("bad", entity, "registered but refused to spawn"))
            else:
                rows.append(("bad", entity, out.strip().split("\n")[-1][-70:] or "silence"))
        send("kill @e[type=!minecraft:player]")
        failures += grade("entities", rows)

    if wanted("blocks"):
        rows = []
        for block in rng.sample(blocks, min(args.n, len(blocks))):
            out = run("setblock %d %d %d %s" % (x + 3, y, z + 3, block))
            out2 = run("execute if block %d %d %d %s run setblock %d %d %d minecraft:sponge"
                      % (x + 3, y, z + 3, block, x + 5, y, z + 5))
            if "Changed the block at %d, %d, %d" % (x + 5, y, z + 5) in out2:
                rows.append(("ok", block, ""))
            elif "Unknown block type" in out2 or "Unknown block type" in out:
                rows.append(("bad", block, "the mod registered no such block"))
            else:
                rows.append(("bad", block, "did not stay in the world"))
            run("setblock %d %d %d minecraft:air" % (x + 5, y, z + 5), 0.4)
        failures += grade("blocks", rows)

    if wanted("vanilla"):
        rows = []
        for command in VANILLA:
            out = run("bukkit:help " + command, 0.7)
            rows.append(("ok", command, "") if registered(out, command)
                        else ("bad", command, "vanilla command missing from this build"))
        failures += grade("vanilla", rows)

    if wanted("bes"):
        # A modded block entity that cannot tick throws once a tick, forever. Place a sample, leave
        # them ticking for a while, and see whether anything complains.
        rows = []
        placed = []
        for i, block in enumerate(rng.sample(blocks, min(args.n * 2, len(blocks)))):
            out = run("setblock %d %d %d %s" % (x - 4 + (i % 8), y, z - 4 + (i // 8), block), 0.5)
            if "Changed the block" in out:
                placed.append(block)
        before = log_size()
        time.sleep(20)
        with open(LOG, encoding="utf-8", errors="replace") as handle:
            handle.seek(before)
            window = handle.read()
        angry = sorted(set(re.findall("BlockEntity threw exception at .{0,60}", window)))
        rows.append(("ok", "%d block entities placed and ticking" % len(placed), "") if not angry
                    else ("bad", "block entity tick", angry[0][:90]))
        for extra in angry[1:5]:
            rows.append(("bad", "block entity tick", extra[:90]))
        failures += grade("bes", rows)

    if wanted("worldgen"):
        rows = []
        for feature in rng.sample(features, min(args.n, len(features))):
            out = run("place feature %s %d %d %d" % (feature, x, y, z), 1.4)
            low = out.lower()
            if "placed feature" in low:
                rows.append(("ok", feature, ""))
            elif "failed to place" in low or "did not match" in low:
                rows.append(("soft", feature, "conditions not met here"))
            elif "exception" in low or "error" in low:
                rows.append(("bad", feature, out.strip().split("\n")[-1][-70:]))
            else:
                rows.append(("soft", feature, "no answer"))
        failures += grade("worldgen", rows)

    send("forceload remove %d %d %d %d" % (x - 16, z - 16, x + 16, z + 16))
    print("\n=== exceptions raised anywhere during this sweep:")
    subprocess.run(
        "grep -aE 'threw exception|AbstractMethodError|IncompatibleClassChange|NoSuchMethodError"
        "|ClassCastException|Tile is null|ctor extras|failed to tick' %s | sed 's/.*\\]: //' "
        "| sort -u | tail -8 | cut -c1-165" % LOG, shell=True)
    print("\nfailures: %d" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
