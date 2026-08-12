#!/usr/bin/env python3
"""Find, without running the server, every place a mod class cannot bind to the core.

Two classes of failure produce exactly the same symptom - the mod silently does nothing, or the
region that touched it dies:

  * a mod subclasses a core class and overrides a method Moonrise sealed with `final`
    -> IncompatibleClassChangeError at class definition, before any mod code runs
  * a mod implements a core interface that has grown an abstract method the mod never heard of
    (every CraftBukkit method bolted onto a vanilla interface) -> AbstractMethodError on first call

Both are visible in the bytecode. This reads the compiled core jar and every mod jar (including
the ones nested under META-INF/jarjar) and reports the mismatches, so the core can be fixed for
the whole pack at once instead of one crash report at a time.

  python3 tools/finalscan.py [--jar server/eturlia.jar] [--mods server/mods] [--all]
"""
import argparse
import io
import os
import struct
import sys
import zipfile

ACC_FINAL = 0x0010
ACC_STATIC = 0x0008
ACC_ABSTRACT = 0x0400
ACC_INTERFACE = 0x0200
ACC_PRIVATE = 0x0002
ACC_SYNTHETIC = 0x1000

CORE_PREFIXES = ("net/minecraft/", "org/bukkit/", "com/mojang/")


class ClassInfo:
    __slots__ = ("name", "super_name", "interfaces", "methods", "flags", "origin")

    def __init__(self, name, super_name, interfaces, methods, flags, origin):
        self.name = name
        self.super_name = super_name
        self.interfaces = interfaces
        self.methods = methods          # (name, desc) -> access flags
        self.flags = flags
        self.origin = origin

    @property
    def is_interface(self):
        return bool(self.flags & ACC_INTERFACE)


def parse_class(data, origin):
    """Minimal class-file reader: constant pool, hierarchy, method flags. No attribute bodies."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return None
    buf = io.BytesIO(data)
    buf.read(8)                                     # magic, minor, major
    (cp_count,) = struct.unpack(">H", buf.read(2))
    pool = [None] * cp_count
    i = 1
    while i < cp_count:
        (tag,) = struct.unpack(">B", buf.read(1))
        if tag == 1:
            (length,) = struct.unpack(">H", buf.read(2))
            pool[i] = buf.read(length).decode("utf-8", "replace")
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = ("ref", struct.unpack(">H", buf.read(2))[0])
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            buf.read(4)
        elif tag in (5, 6):
            buf.read(8)
            i += 1                                  # long/double eat two slots
        elif tag == 15:
            buf.read(3)
        else:
            return None                             # unknown tag: give up on this class
        i += 1

    def class_name(index):
        entry = pool[index] if 0 < index < cp_count else None
        if not entry or entry[0] != "ref":
            return None
        return pool[entry[1]]

    flags, this_index, super_index = struct.unpack(">HHH", buf.read(6))
    name = class_name(this_index)
    super_name = class_name(super_index) if super_index else None
    (count,) = struct.unpack(">H", buf.read(2))
    interfaces = [class_name(idx) for idx in struct.unpack(">%dH" % count, buf.read(2 * count))]

    def skip_members():
        (n,) = struct.unpack(">H", buf.read(2))
        out = []
        for _ in range(n):
            access, name_index, desc_index, attr_count = struct.unpack(">HHHH", buf.read(8))
            for _ in range(attr_count):
                buf.read(2)
                (length,) = struct.unpack(">I", buf.read(4))
                buf.read(length)
            out.append((access, pool[name_index], pool[desc_index]))
        return out

    skip_members()                                  # fields
    methods = {}
    for access, member, desc in skip_members():
        methods[(member, desc)] = access
    return ClassInfo(name, super_name, [i for i in interfaces if i], methods, flags, origin)


def read_jar(path, index, origin=None, depth=0):
    origin = origin or os.path.basename(path)
    try:
        jar = zipfile.ZipFile(path) if isinstance(path, str) else zipfile.ZipFile(path)
    except Exception:
        return
    with jar:
        for entry in jar.namelist():
            if entry.endswith(".class"):
                try:
                    info = parse_class(jar.read(entry), origin)
                except Exception:
                    continue
                if info and info.name and info.name not in index:
                    index[info.name] = info
            elif depth == 0 and entry.startswith("META-INF/jarjar/") and entry.endswith(".jar"):
                read_jar(io.BytesIO(jar.read(entry)), index,
                         "%s!%s" % (origin, os.path.basename(entry)), depth + 1)


def is_core(name):
    return name is not None and name.startswith(CORE_PREFIXES)


def supers(name, index):
    """Walk the superclass chain, core and mod alike, as far as the index reaches."""
    seen = set()
    current = index.get(name)
    while current and current.super_name and current.super_name not in seen:
        seen.add(current.super_name)
        parent = index.get(current.super_name)
        if not parent:
            break
        yield parent
        current = parent


def all_interfaces(name, index, out=None):
    out = out if out is not None else set()
    info = index.get(name)
    if not info:
        return out
    for iface in info.interfaces:
        if iface not in out:
            out.add(iface)
            all_interfaces(iface, index, out)
    if info.super_name:
        all_interfaces(info.super_name, index, out)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default="server/eturlia.jar")
    parser.add_argument("--mods", default="server/mods")
    parser.add_argument("--all", action="store_true", help="list every hit, not the first few")
    args = parser.parse_args()

    core = {}
    read_jar(args.jar, core, "core")
    print("core classes: %d" % len(core))

    mods = {}
    jars = sorted(os.path.join(args.mods, f) for f in os.listdir(args.mods) if f.endswith(".jar"))
    for jar in jars:
        read_jar(jar, mods, os.path.basename(jar))
    print("mod jars: %d, mod classes: %d" % (len(jars), len(mods)))

    index = dict(core)
    index.update({k: v for k, v in mods.items() if k not in core})

    final_hits = []
    missing_hits = []
    for name, info in mods.items():
        if name in core:
            continue

        # 1. overriding a final method somewhere up the chain
        for parent in supers(name, index):
            if not is_core(parent.name):
                continue
            for key, access in info.methods.items():
                if key[0] in ("<init>", "<clinit>") or access & (ACC_STATIC | ACC_PRIVATE):
                    continue
                parent_access = parent.methods.get(key)
                if parent_access is None or not parent_access & ACC_FINAL:
                    continue
                if parent_access & (ACC_STATIC | ACC_PRIVATE):
                    continue
                final_hits.append((parent.name, key[0], key[1], name, info.origin))

        # 2. an abstract core-interface method nobody implements
        if info.flags & (ACC_INTERFACE | ACC_ABSTRACT):
            continue
        chain = [info] + [p for p in supers(name, index)]
        ifaces = all_interfaces(name, index)
        implemented = {}
        for level in chain + [index[i] for i in ifaces if i in index]:
            # An interface default counts as an implementation just as much as a class method does.
            for key, access in level.methods.items():
                if not access & ACC_ABSTRACT and key not in implemented:
                    implemented[key] = level.name
        for iface in ifaces:
            if not is_core(iface):
                continue
            iface_info = index.get(iface)
            if not iface_info:
                continue
            for key, access in iface_info.methods.items():
                if not access & ACC_ABSTRACT or access & (ACC_STATIC | ACC_SYNTHETIC):
                    continue
                if key not in implemented:
                    missing_hits.append((iface, key[0], key[1], name, info.origin))

    def report(title, hits, group_key):
        print("\n=== %s: %d" % (title, len(hits)))
        groups = {}
        for hit in hits:
            groups.setdefault(group_key(hit), []).append(hit)
        for key in sorted(groups, key=lambda k: -len(groups[k])):
            rows = groups[key]
            print("  %-70s x%d" % (key, len(rows)))
            for row in rows[: (len(rows) if args.all else 3)]:
                print("      %s   [%s]" % (row[3], row[4]))
            if not args.all and len(rows) > 3:
                print("      ... %d more" % (len(rows) - 3))

    report("mod overrides a FINAL core method (IncompatibleClassChangeError)", final_hits,
           lambda h: "%s.%s%s" % (h[0].rsplit("/", 1)[-1], h[1], h[2]))
    report("core interface method with no implementation (AbstractMethodError)", missing_hits,
           lambda h: "%s.%s%s" % (h[0].rsplit("/", 1)[-1], h[1], h[2]))
    return 1 if final_hits or missing_hits else 0


if __name__ == "__main__":
    sys.exit(main())
