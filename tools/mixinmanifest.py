#!/usr/bin/env python3
"""Every mixin that did not apply this boot, grouped by mod, compared against a baseline.

Mixin only calls an error handler for failures it considers fatal. Everything else - an injector
whose descriptor no longer matches, a @Shadow whose target Paper renamed, an accessor with no
candidates - is printed and forgotten, and the mod then runs with half of itself missing. That is
how wover's registry mixin took every new world down to a superflat without a single line saying so.

    python3 tools/mixinmanifest.py                 write the manifest and diff it against the baseline
    python3 tools/mixinmanifest.py --baseline      overwrite the baseline with this boot

Exit status is 1 when a mixin fails that the baseline does not know about.
"""
import io
import os
import re
import sys

ROOT = "/home/user/milky/eturlia_new"
LOG = os.path.join(ROOT, "server/logs/latest.log")
OUT = os.path.join(ROOT, "server/logs/eturlia-mixins.tsv")
BASELINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mixin-baseline.tsv")

PATTERNS = [
    ("apply-failed", re.compile(
        r"Mixin apply for mod (?P<mod>\S+) failed (?P<config>\S+?):(?P<mixin>\S+) from mod \S+"
        r"(?: -> (?P<target>\S+?):)?")),
    ("invalid-injection", re.compile(
        r"Invalid descriptor on (?P<config>\S+?):(?P<mixin>\S+) from mod (?P<mod>\S+?)->")),
    ("shadow-missing", re.compile(
        r"@Shadow method (?P<member>\S+) in (?P<config>\S+?):(?P<mixin>\S+) from mod (?P<mod>\S+) "
        r"was not located")),
    ("accessor-missing", re.compile(
        r"No candidates were found matching (?P<member>\S+?) in (?P<target>\S+) for "
        r"(?P<config>\S+?):(?P<mixin>\S+)")),
    ("class-missing", re.compile(
        r"Error loading class: (?P<target>\S+) \((?P<why>[^)]*)\)")),
    ("critical-injection", re.compile(
        r"Critical injection failure: (?P<what>.*?) in (?P<target>\S+)::")),
]


def mod_of(row):
    mod = row.get("mod")
    if mod:
        return mod.strip("->")
    config = row.get("config") or ""
    return config.split(".", 1)[0] if config else "unknown"


def scan(path):
    rows = []
    seen = set()
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            for kind, pattern in PATTERNS:
                match = pattern.search(line)
                if not match:
                    continue
                row = match.groupdict()
                entry = (
                    mod_of(row),
                    kind,
                    row.get("config") or "-",
                    row.get("mixin") or row.get("member") or row.get("what") or "-",
                    row.get("target") or "-",
                )
                if entry not in seen:
                    seen.add(entry)
                    rows.append(entry)
                break
    return rows


def load(path):
    if not os.path.exists(path):
        return []
    with io.open(path, encoding="utf-8") as handle:
        return [tuple(line.rstrip("\n").split("\t")) for line in handle if line.strip()]


def write(path, rows):
    parent = os.path.dirname(path)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write("\t".join(row) + "\n")


def main():
    rows = sorted(scan(LOG))
    write(OUT, rows)
    by_mod = {}
    for row in rows:
        by_mod.setdefault(row[0], []).append(row)
    print("mixins that did not apply: %d, across %d mods" % (len(rows), len(by_mod)))
    for mod in sorted(by_mod, key=lambda m: -len(by_mod[m])):
        kinds = ", ".join(sorted({r[1] for r in by_mod[mod]}))
        print("  %-22s %2d  %s" % (mod[:22], len(by_mod[mod]), kinds))

    if "--baseline" in sys.argv:
        write(BASELINE, rows)
        print("baseline written:", BASELINE)
        return 0

    known = set(load(BASELINE))
    if not known:
        print("no baseline yet; run with --baseline once the current state is understood")
        return 0
    new = [row for row in rows if row not in known]
    gone = [row for row in known if row not in set(rows)]
    for row in gone:
        print("  fixed since the baseline: %s %s %s" % (row[0], row[1], row[3]))
    if new:
        print("\n%d mixin failures the baseline does not know about:" % len(new))
        for row in new:
            print("  %s\t%s\t%s\t%s" % (row[0], row[1], row[3], row[4]))
        return 1
    print("nothing new against the baseline")
    return 0


if __name__ == "__main__":
    sys.exit(main())
