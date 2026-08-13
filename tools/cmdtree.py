#!/usr/bin/env python3
"""Map a command tree from the console by asking Brigadier what it does not understand.

The console never prints usage, but its error does say where parsing stopped: the offending part of
the line is underlined, and `<--[HERE]` marks the position. So for any prefix and any candidate
word, three answers are distinguishable without a wiki:

  * no error at all            -> the command ran
  * error, nothing underlined  -> the prefix is valid and wants more
  * error, candidate underlined-> no such child

Candidate words are read out of the mod's own command classes, so the tree comes from the jar.

  python3 tools/cmdtree.py sable
  python3 tools/cmdtree.py sable --depth 2
  python3 tools/cmdtree.py sable --words assemble,physics,spawn
"""
import argparse
import io
import os
import re
import struct
import subprocess
import time
import zipfile

N = "/home/user/milky/eturlia_new"
LOG = N + "/server/logs/latest.log"
MODS = N + "/server/mods"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
UNDERLINE = "\x1b[4m"


def send(command):
    subprocess.run(["bash", N + "/tools/testctl.sh", "say", command],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def run(command, wait=2.0):
    start = os.path.getsize(LOG)
    send(command)
    time.sleep(wait)
    with open(LOG, encoding="utf-8", errors="replace") as handle:
        handle.seek(start)
        return handle.read()


def classify(command):
    """-> ("ran"|"incomplete"|"unknown", the part Brigadier could not read)"""
    out = run(command)
    # Brigadier has two ways of saying no: an unknown node and a node whose argument did not parse.
    if "Unknown or incomplete command" not in out and "Incorrect argument for command" not in out:
        return "ran", ""
    marker = out.find("<--[HERE]")
    if marker < 0:
        return "unknown", ""
    head = out[:marker]
    cut = head.rfind(UNDERLINE)
    if cut < 0:
        return "incomplete", ""
    bad = ANSI.sub("", head[cut:]).strip()
    return ("unknown", bad) if bad else ("incomplete", "")


def constants(data):
    b = io.BytesIO(data)
    b.read(8)
    (count,) = struct.unpack(">H", b.read(2))
    out = []
    i = 1
    while i < count:
        (tag,) = struct.unpack(">B", b.read(1))
        if tag == 1:
            (length,) = struct.unpack(">H", b.read(2))
            out.append(b.read(length).decode("utf-8", "replace"))
        elif tag in (7, 8, 16, 19, 20):
            b.read(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            b.read(4)
        elif tag in (5, 6):
            b.read(8)
            i += 1
        elif tag == 15:
            b.read(3)
        else:
            break
        i += 1
    return out


def candidate_words(root):
    """Every lower-case identifier in every *Command* class of every jar that mentions the root."""
    words = set()

    def scan(zf, depth=0):
        for entry in zf.namelist():
            if depth == 0 and entry.startswith("META-INF/jarjar/") and entry.endswith(".jar"):
                try:
                    scan(zipfile.ZipFile(io.BytesIO(zf.read(entry))), depth + 1)
                except Exception:
                    pass
            elif entry.endswith(".class") and "ommand" in entry:
                try:
                    for text in constants(zf.read(entry)):
                        if re.fullmatch(r"[a-z][a-z0-9_]{1,24}", text or ""):
                            words.add(text)
                except Exception:
                    pass

    for name in sorted(os.listdir(MODS)):
        if not name.endswith(".jar"):
            continue
        try:
            jar = zipfile.ZipFile(os.path.join(MODS, name))
        except Exception:
            continue
        with jar:
            if root not in name.lower():
                continue
            scan(jar)
    noise = {"this", "value", "get", "set", "run", "apply", "accept", "next", "iterator", "size",
             "create", "add", "map", "of", "min", "max", "format", "equals", "clone", "index",
             "metafactory", "valueof", "context", "ctx", "source", "handle", "handles", "lambda",
             "code", "sourcefile", "signature", "exceptions", "init", "clinit", "stream", "length",
             "contains", "split", "swap", "message", "name", "count", "parts", "data", "dir"}
    return sorted(w for w in words if w not in noise and len(w) > 2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root")
    parser.add_argument("--depth", type=int, default=2)
    parser.add_argument("--words", default="")
    args = parser.parse_args()

    words = args.words.split(",") if args.words else candidate_words(args.root)
    print("root %r, %d candidate words" % (args.root, len(words)))

    state, bad = classify(args.root)
    print("  /%s -> %s%s" % (args.root, state, (" (%r)" % bad) if bad else ""))
    if state == "unknown":
        print("  no such command")
        return 1

    frontier = [args.root]
    for level in range(args.depth):
        found = []
        for prefix in frontier:
            for word in words:
                state, _ = classify("%s %s" % (prefix, word))
                if state in ("ran", "incomplete"):
                    print("  /%s %s -> %s" % (prefix, word, state))
                    if state == "incomplete":
                        found.append("%s %s" % (prefix, word))
        frontier = found
        if not frontier:
            break
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
