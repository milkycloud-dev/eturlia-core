#!/usr/bin/env python3
"""Print one path out of a gzipped NBT file. Enough to answer what a world was created with."""
import gzip
import struct
import sys


def read(data, pos, tag):
    if tag == 1:
        return data[pos], pos + 1
    if tag == 2:
        return struct.unpack_from(">h", data, pos)[0], pos + 2
    if tag == 3:
        return struct.unpack_from(">i", data, pos)[0], pos + 4
    if tag == 4:
        return struct.unpack_from(">q", data, pos)[0], pos + 8
    if tag == 5:
        return struct.unpack_from(">f", data, pos)[0], pos + 4
    if tag == 6:
        return struct.unpack_from(">d", data, pos)[0], pos + 8
    if tag == 7:
        size = struct.unpack_from(">i", data, pos)[0]
        return "<bytes %d>" % size, pos + 4 + size
    if tag == 8:
        size = struct.unpack_from(">H", data, pos)[0]
        return data[pos + 2:pos + 2 + size].decode("utf-8", "replace"), pos + 2 + size
    if tag == 9:
        child = data[pos]
        size = struct.unpack_from(">i", data, pos + 1)[0]
        pos += 5
        out = []
        for _ in range(size):
            value, pos = read(data, pos, child)
            out.append(value)
        return out, pos
    if tag == 10:
        out = {}
        while True:
            child = data[pos]
            pos += 1
            if child == 0:
                return out, pos
            length = struct.unpack_from(">H", data, pos)[0]
            name = data[pos + 2:pos + 2 + length].decode("utf-8", "replace")
            pos += 2 + length
            value, pos = read(data, pos, child)
            out[name] = value
    if tag in (11, 12):
        size = struct.unpack_from(">i", data, pos)[0]
        width = 4 if tag == 11 else 8
        return "<array %d>" % size, pos + 4 + size * width
    raise ValueError("unknown tag %d at %d" % (tag, pos))


def main():
    path = sys.argv[1]
    keys = sys.argv[2].split(".") if len(sys.argv) > 2 else []
    data = gzip.open(path, "rb").read()
    root, _ = read(data, 3 + struct.unpack_from(">H", data, 1)[0], 10)
    node = root
    for key in keys:
        if not isinstance(node, dict) or key not in node:
            print("missing:", key, "have:", list(node)[:12] if isinstance(node, dict) else type(node))
            return
        node = node[key]
    if isinstance(node, dict):
        for key, value in node.items():
            text = repr(value)
            print("  %-28s %s" % (key, text[:150]))
    else:
        print(repr(node)[:400])


if __name__ == "__main__":
    main()
