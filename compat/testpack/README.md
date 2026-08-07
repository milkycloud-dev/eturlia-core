# Testpack — Compatibility Manifest

This directory currently stores a pinned compatibility manifest for the compat
modules (Create/CBC/Sable/Aeronautics) and related notes.

## Directory Structure

```
testpack/
├── mods/
│   └── README.md   # Version pins + compatibility notes
└── README.md       # This file
```

## Current Scope

- Keep known-good mod/version references in one place.
- Record compatibility expectations and risk notes for manual validation.
- Provide a stable baseline for future automation work.

## About CI / Automation

At the moment, this repository does **not** include the previously referenced
`compat/scripts/*` automation helpers, and `testpack/` is not wired into the
main Eturlia CI pipeline.

When automated compat CI is reintroduced, this README should be extended with
the exact scripts and commands available in-tree.

## Updating Version Pins

1. Edit `mods/README.md`.
2. Validate manually against your target environment.
3. Open a PR with both the version change and validation notes.
