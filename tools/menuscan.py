#!/usr/bin/env python3
"""Which classes in the pack inherit a CraftBukkit-added abstract method and never implement it.

The static binding scan answers "does this class bind". It does not answer "does the JVM have an
implementation for every method the superclass declares abstract", and that second question is the
one that took the pack's menus down: CraftBukkit adds `getBukkitView()` to AbstractContainerMenu,
mods are compiled against a vanilla class that has no such method, and the JVM only complains -
with AbstractMethodError, inside a region tick - when the menu is opened.

Run it against the mods directory and the built server jar.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from finalscan import parse_class, read_jar  # noqa: E402

MODS = "/home/user/milky/eturlia_new/server/mods"
SERVER_JAR = ("/home/user/milky/eturlia_new/core/Folia-Server/build/libs/"
              "folia-server-1.21.1-R0.1-SNAPSHOT-mojang-mapped.jar")

# root class -> the methods CraftBukkit adds to it that a subclass must answer
CONTRACTS = {
    "net/minecraft/world/inventory/AbstractContainerMenu": [
        ("getBukkitView", "()Lorg/bukkit/inventory/InventoryView;"),
    ],
}


def chain(name, index):
    seen = []
    while name and name in index:
        seen.append(name)
        name = index[name].super_name
    if name:
        seen.append(name)
    return seen


def main():
    index = {}
    read_jar(SERVER_JAR, index, "server")
    server_only = set(index)
    for entry in sorted(os.listdir(MODS)):
        if entry.endswith(".jar"):
            read_jar(os.path.join(MODS, entry), index, entry)

    for root, methods in CONTRACTS.items():
        implemented_by_root = any((m, d) in index[root].methods and
                                  not (index[root].methods[(m, d)] & 0x0400)
                                  for m, d in methods) if root in index else False
        print("== %s" % root)
        print("   root provides an implementation: %s" % implemented_by_root)
        missing = []
        total = 0
        for name, info in index.items():
            if name in server_only or name == root:
                continue
            ancestry = chain(name, index)
            if root not in ancestry:
                continue
            total += 1
            if info.flags & 0x0400:          # the subclass is abstract itself
                continue
            answered = False
            for step in ancestry:
                node = index.get(step)
                if node is None:
                    continue
                for m, d in methods:
                    if (m, d) in node.methods and not (node.methods[(m, d)] & 0x0400):
                        answered = True
            if not answered:
                missing.append((info.origin, name))
        print("   modded subclasses: %d" % total)
        print("   without an implementation of their own: %d" % len(missing))
        by_mod = {}
        for origin, name in missing:
            by_mod.setdefault(origin.split("!")[0], []).append(name)
        for origin in sorted(by_mod, key=lambda o: -len(by_mod[o]))[:15]:
            print("     %-52s %d" % (origin[:52], len(by_mod[origin])))


if __name__ == "__main__":
    main()
