#!/usr/bin/env python3
"""Copy generated NeoForge feature patches into patches/server/ under new numbers.

Paths default to this repository (resolved from the script location) instead of the
absolute paths of a single developer machine that used to be hard-coded here.

Usage:
    scripts/sync-patches-to-server.py [--features DIR] [--server DIR] [--dry-run]
"""
import argparse
import os
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_FEATURES_DIR = os.path.join(
    REPO_ROOT, "folia-server", "minecraft-patches", "features")
DEFAULT_SERVER_PATCHES_DIR = os.path.join(REPO_ROOT, "patches", "server")

# Mapping: features patch number -> server patch number
MAPPING = {
    14: 26,
    15: 27,
    16: 28,
    17: 29,
    18: 30,
    19: 31,
    20: 32,
}


def adapt_patch(features_dir, server_dir, features_num, server_num, dry_run):
    """Copy one features patch to the server patch directory under a new number."""
    matches = [f for f in os.listdir(features_dir) if f.startswith("%04d-" % features_num)]
    if not matches:
        print("  SKIP %04d-* : no matching patch in %s" % (features_num, features_dir))
        return None
    features_name = matches[0]
    features_path = os.path.join(features_dir, features_name)

    server_name = "%04d-%s" % (server_num, features_name.split("-", 1)[1])
    server_path = os.path.join(server_dir, server_name)

    if not dry_run:
        with open(features_path, "r", encoding="utf-8") as src:
            content = src.read()
        with open(server_path, "w", encoding="utf-8") as dst:
            dst.write(content)

    print("  %s -> %s%s" % (features_name, server_name, " (dry run)" if dry_run else ""))
    return server_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--features", default=DEFAULT_FEATURES_DIR,
                        help="source directory with generated feature patches")
    parser.add_argument("--server", default=DEFAULT_SERVER_PATCHES_DIR,
                        help="destination patches/server directory")
    parser.add_argument("--dry-run", action="store_true",
                        help="print what would be copied without writing anything")
    args = parser.parse_args()

    if not os.path.isdir(args.features):
        print("Features directory not found: %s" % args.features, file=sys.stderr)
        print("Run ./gradlew applyPatches first, or pass --features.", file=sys.stderr)
        return 1
    if not os.path.isdir(args.server):
        print("Server patch directory not found: %s" % args.server, file=sys.stderr)
        return 1

    print("=== Syncing NeoForge patches to %s ===" % args.server)
    for feat_num, srv_num in sorted(MAPPING.items()):
        adapt_patch(args.features, args.server, feat_num, srv_num, args.dry_run)

    patches = sorted(f for f in os.listdir(args.server) if f.endswith(".patch"))
    print("\nTotal patches in %s: %d" % (args.server, len(patches)))
    for p in patches:
        size = os.path.getsize(os.path.join(args.server, p))
        marker = " (NeoForge)" if p[:4].isdigit() and int(p[:4]) >= 20 else ""
        print("  %s (%d bytes)%s" % (p, size, marker))
    return 0


if __name__ == "__main__":
    sys.exit(main())
