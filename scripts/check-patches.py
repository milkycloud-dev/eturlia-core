#!/usr/bin/env python3
"""Validate patch structure: hunk line counts AND no change-free (degenerate) hunks.

git am rejects a hunk that contains only context lines with "corrupt patch", which a pure
line-count check does not catch.
"""
import re, glob, sys

BREAK = ('@@', 'diff --git', 'From ', 'index ', '--- ', '+++ ')
bad = 0
for path in sorted(glob.glob('patches/server/*.patch')) + sorted(glob.glob('patches/api/*.patch')):
    lines = open(path, encoding='utf-8', errors='replace').read().split('\n')
    i = 0
    while i < len(lines):
        m = re.match(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', lines[i])
        if not m:
            i += 1
            continue
        header_line = i + 1
        old = int(m.group(2) or 1)
        new = int(m.group(4) or 1)
        o = n = added = removed = 0
        j = i + 1
        while j < len(lines):
            l = lines[j]
            if l.startswith('@@') or l.startswith('diff --git') or l.startswith('From '):
                break
            if l.startswith('\\'):
                j += 1
                continue
            if l.startswith('-'):
                if l.startswith('--- '):
                    break
                o += 1
                removed += 1
            elif l.startswith('+'):
                if l.startswith('+++ '):
                    break
                n += 1
                added += 1
            elif l.startswith(' '):
                o += 1
                n += 1
            elif l == '':
                if j + 1 < len(lines) and not lines[j + 1].startswith(BREAK):
                    o += 1
                    n += 1
                else:
                    j += 1
                    break
            else:
                break
            j += 1
        if (o, n) != (old, new):
            print('%s:%d count mismatch: header -%d +%d, body -%d +%d'
                  % (path, header_line, old, new, o, n))
            bad += 1
        if added == 0 and removed == 0:
            print('%s:%d change-free hunk (context only) — git am rejects this as "corrupt patch"'
                  % (path, header_line))
            bad += 1
        i = j
print('bad hunks:', bad)
sys.exit(1 if bad else 0)
