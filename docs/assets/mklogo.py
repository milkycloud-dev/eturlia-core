#!/usr/bin/env python3
"""Generate the Eturlia mark: an E built out of chunk tiles on a grey ground.

The E is drawn on a 5x5 tile grid. Tiles are tinted in three bands - one per region
tick thread - and a single tile carries the NeoForge amber, the mod loader inside the
regionised grid. Writes an SVG (source of truth) and a flat PNG (README embed).
"""
import struct
import zlib
import sys

SIZE = 512
GROUND = (0x33, 0x36, 0x3B)      # neutral cool grey
PLATE = (0x24, 0x27, 0x2B)       # the recessed plate behind the mark
BANDS = [
    (0x8C, 0xE8, 0xC6),          # region A - top
    (0x4F, 0xB8, 0x9B),          # region B - middle
    (0x3F, 0x9C, 0x86),          # region C - bottom
]
FORGE = (0xF2, 0x99, 0x4A)       # the one NeoForge tile

# 5x5 grid, "#" is a tile. Row -> band index is the row's own third.
GLYPH = [
    "#####",
    "#....",
    "####.",
    "#....",
    "#####",
]
BAND_OF_ROW = [0, 0, 1, 2, 2]
FORGE_CELL = (2, 3)              # row, col - the tip of the middle bar

PLATE_INSET = 44                 # ground margin around the recessed plate
GRID_INSET = 90                  # ground margin around the tile grid
GAP = 10                         # gap between tiles


def cells():
    """Yield (x, y, w, h, colour) for every tile, in ground pixel coordinates."""
    span = SIZE - 2 * GRID_INSET
    step = span / 5.0
    tile = step - GAP
    for row, line in enumerate(GLYPH):
        for col, ch in enumerate(line):
            if ch != "#":
                continue
            colour = FORGE if (row, col) == FORGE_CELL else BANDS[BAND_OF_ROW[row]]
            x = GRID_INSET + col * step
            y = GRID_INSET + row * step
            yield x, y, tile, tile, colour


def hexcolour(rgb):
    return "#%02x%02x%02x" % rgb


def write_svg(path):
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" '
        'role="img" aria-label="Eturlia">' % (SIZE, SIZE, SIZE, SIZE),
        '<rect width="%d" height="%d" fill="%s"/>' % (SIZE, SIZE, hexcolour(GROUND)),
        '<rect x="%d" y="%d" width="%d" height="%d" rx="26" fill="%s"/>'
        % (PLATE_INSET, PLATE_INSET, SIZE - 2 * PLATE_INSET, SIZE - 2 * PLATE_INSET,
           hexcolour(PLATE)),
    ]
    for x, y, w, h, colour in cells():
        parts.append(
            '<rect x="%.2f" y="%.2f" width="%.2f" height="%.2f" rx="5" fill="%s"/>'
            % (x, y, w, h, hexcolour(colour))
        )
    parts.append("</svg>")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(parts) + "\n")


def write_png(path):
    """Flat-fill renderer: ground, plate (rounded), tiles (rounded). No dependencies."""
    rows = [[GROUND] * SIZE for _ in range(SIZE)]

    def rounded(x0, y0, w, h, radius, colour):
        x1, y1 = x0 + w, y0 + h
        for y in range(max(0, int(y0)), min(SIZE, int(round(y1)))):
            for x in range(max(0, int(x0)), min(SIZE, int(round(x1)))):
                # corner test
                cx = None
                if x < x0 + radius and y < y0 + radius:
                    cx, cy = x0 + radius, y0 + radius
                elif x > x1 - radius and y < y0 + radius:
                    cx, cy = x1 - radius, y0 + radius
                elif x < x0 + radius and y > y1 - radius:
                    cx, cy = x0 + radius, y1 - radius
                elif x > x1 - radius and y > y1 - radius:
                    cx, cy = x1 - radius, y1 - radius
                if cx is not None:
                    if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 > radius * radius:
                        continue
                rows[y][x] = colour

    rounded(PLATE_INSET, PLATE_INSET, SIZE - 2 * PLATE_INSET, SIZE - 2 * PLATE_INSET, 26, PLATE)
    for x, y, w, h, colour in cells():
        rounded(x, y, w, h, 5, colour)

    raw = bytearray()
    for row in rows:
        raw.append(0)
        for r, g, b in row:
            raw += bytes((r, g, b))

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as handle:
        handle.write(png)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    write_svg(out + "/eturlia-logo.svg")
    write_png(out + "/eturlia-logo.png")
    print("wrote", out + "/eturlia-logo.svg", "and", out + "/eturlia-logo.png")
