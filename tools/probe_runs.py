#!/usr/bin/env python3
"""One line per server run on a given day: when it started, how it ended, and why."""
import glob
import gzip
import io
import os
import re
import sys

LOGDIR = "/home/user/milky/eturlia_new/server/logs"
DAY = sys.argv[1] if len(sys.argv) > 1 else "2026-08-13"
SINCE = sys.argv[2] if len(sys.argv) > 2 else "00:00"

TIME = re.compile(r"^\[\d+\S*\.?\d*\.?\d*\s*([0-9]{2}:[0-9]{2}:[0-9]{2})")
DONE = re.compile(r'Done \(([^)]+)\)')
FAIL_HINTS = [
    ("datapack-load", re.compile(r"Failed to load datapacks")),
    ("missing-pack", re.compile(r"Missing data pack (\S+)")),
    ("watchdog", re.compile(r"has stopped responding|Watchdog thread")),
    ("oom", re.compile(r"OutOfMemoryError")),
    ("region-crash", re.compile(r"Region .*crashed|Ticking region")),
    ("stopping", re.compile(r"Stopping server")),
    ("exit", re.compile(r"Failed to start the minecraft server|can't proceed with server load")),
]


def openlog(path):
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", errors="replace")
    return io.open(path, encoding="utf-8", errors="replace")


def scan(path):
    first_t = last_t = None
    done = None
    hits = {}
    missing = set()
    with openlog(path) as handle:
        for line in handle:
            m = TIME.search(line)
            if m:
                if first_t is None:
                    first_t = m.group(1)
                last_t = m.group(1)
            d = DONE.search(line)
            if d and "For help" in line or (d and "Done (" in line):
                done = d.group(1)
            for name, rx in FAIL_HINTS:
                mm = rx.search(line)
                if mm:
                    hits[name] = hits.get(name, 0) + 1
                    if name == "missing-pack":
                        missing.add(mm.group(1))
    return first_t, last_t, done, hits, missing


def main():
    files = sorted(glob.glob(os.path.join(LOGDIR, DAY + "-*.log.gz")),
                   key=lambda p: os.path.getmtime(p))
    files += [os.path.join(LOGDIR, "prev.log"), os.path.join(LOGDIR, "latest.log")]
    rows = []
    for path in files:
        if not os.path.exists(path):
            continue
        first_t, last_t, done, hits, missing = scan(path)
        if not first_t or first_t < SINCE:
            continue
        rows.append((first_t, os.path.basename(path), last_t, done, hits, missing))
    rows.sort()
    for first_t, name, last_t, done, hits, missing in rows:
        verdict = "BOOTED %s" % done if done else "no-boot"
        flags = ",".join("%s x%d" % (k, v) for k, v in sorted(hits.items()) if k != "missing-pack")
        print("%s..%s  %-24s %-18s %s%s" % (
            first_t, last_t, name, verdict, flags,
            ("  packs:" + ",".join(sorted(missing))) if missing else ""))
    print("\nruns listed:", len(rows))


if __name__ == "__main__":
    main()
