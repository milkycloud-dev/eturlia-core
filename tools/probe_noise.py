#!/usr/bin/env python3
"""Group the suppressed-noise log: how much of it is today's run, and what it is about."""
import io
import re
import sys
from collections import Counter

PATH = "/home/user/milky/eturlia_new/server/logs/eturlia-noise.log"
SINCE = sys.argv[1] if len(sys.argv) > 1 else None   # e.g. "2026-08-13 17:41"

STAMP = re.compile(r"^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})")
EXC = re.compile(r"^([a-zA-Z0-9_.]+(?:Exception|Error))(?::\s*(.*))?$")


def main():
    per_day = Counter()
    kinds = Counter()
    msgs = Counter()
    cur = None
    inwin = SINCE is None
    with io.open(PATH, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            m = STAMP.match(line)
            if m:
                cur = m.group(1) + " " + m.group(2)
                per_day[m.group(1)] += 1
                inwin = SINCE is None or cur >= SINCE
                continue
            if not inwin:
                continue
            e = EXC.match(line.rstrip())
            if e:
                kinds[e.group(1)] += 1
                text = (e.group(2) or "").strip()
                text = re.sub(r"@[0-9a-f]{4,}", "@X", text)
                text = re.sub(r"\b\d+\b", "N", text)
                msgs[(e.group(1).rsplit(".", 1)[-1], text[:150])] += 1
    print("lines per day:")
    for day, n in sorted(per_day.items()):
        print("  %s  %d" % (day, n))
    print("\nwindow: %s" % (SINCE or "everything"))
    print("exception kinds:")
    for k, n in kinds.most_common(12):
        print("  %6d  %s" % (n, k))
    print("\ntop messages:")
    for (kind, text), n in msgs.most_common(25):
        print("  %5d  %-24s %s" % (n, kind, text))


if __name__ == "__main__":
    main()
