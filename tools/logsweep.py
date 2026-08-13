#!/usr/bin/env python3
"""Read every log the server has kept, at every level, and report what is in them by kind.

logcheck.py grades one boot at WARN and above. This one goes through everything - latest.log, the
rotated .log.gz files, eturlia.log - normalises each line into a shape (numbers, coordinates, ids
and hashes replaced), and prints the shapes by frequency. Nothing is hidden; that is the point.

  python3 tools/logsweep.py                  # the shapes, most common first
  python3 tools/logsweep.py --grep "Can't find element"   # every distinct id behind one shape
  python3 tools/logsweep.py --level ERROR    # only ERROR/FATAL lines
  python3 tools/logsweep.py --top 60
"""
import argparse
import collections
import glob
import gzip
import os
import re

LOGS = "/home/user/milky/eturlia_new/server/logs"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
HEAD = re.compile(r"^\[[^\]]*\] \[([^/\]]*)/(\w+)\] \[([^\]]*)\]: (.*)$")
SHAPE = [
    (re.compile(r"-?\d+\.\d+"), "#"),
    (re.compile(r"-?\b\d+\b"), "#"),
    (re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"), "<uuid>"),
    (re.compile(r"'[^']{1,80}'"), "'<id>'"),
    (re.compile(r'"[^"]{1,80}"'), '"<id>"'),
]


def shape(text):
    for pattern, into in SHAPE:
        text = pattern.sub(into, text)
    return text[:170]


def lines():
    paths = [os.path.join(LOGS, "latest.log")]
    paths += sorted(glob.glob(os.path.join(LOGS, "*.log.gz")))
    paths += [p for p in glob.glob(os.path.join(LOGS, "*.log")) if not p.endswith("latest.log")]
    for path in paths:
        opener = gzip.open if path.endswith(".gz") else open
        try:
            with opener(path, "rt", encoding="utf-8", errors="replace") as handle:
                for raw in handle:
                    yield os.path.basename(path), ANSI.sub("", raw.rstrip())
        except OSError:
            continue


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--top", type=int, default=40)
    parser.add_argument("--level", default="")
    parser.add_argument("--grep", default="")
    args = parser.parse_args()

    if args.grep:
        # One shape, expanded: every distinct full message behind it, with counts.
        seen = collections.Counter()
        for _, line in lines():
            if args.grep in line:
                match = HEAD.match(line)
                seen[(match.group(4) if match else line)[:190]] += 1
        print("=== %d distinct messages containing %r" % (len(seen), args.grep))
        for text, count in seen.most_common(60):
            print("  x%-6d %s" % (count, text))
        return 0

    kinds = collections.Counter()
    levels = collections.Counter()
    sample = {}
    total = 0
    for _, line in lines():
        match = HEAD.match(line)
        if not match:
            continue
        level, source, body = match.group(2), match.group(3), match.group(4)
        levels[level] += 1
        if args.level and level.upper() != args.level.upper():
            continue
        if not args.level and level == "INFO":
            continue
        total += 1
        key = (source.split("/")[0][:34], shape(body))
        kinds[key] += 1
        sample.setdefault(key, body[:150])

    print("=== every log in %s: %s" % (LOGS, ", ".join("%s=%d" % kv for kv in levels.most_common())))
    print("=== %d lines above INFO, in %d kinds%s\n" % (total, len(kinds),
          (" (level %s only)" % args.level) if args.level else ""))
    for key, count in kinds.most_common(args.top):
        print("x%-6d %-34s %s" % (count, key[0], sample[key]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
