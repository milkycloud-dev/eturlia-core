# Eturlia modder / pack policy

## Supported (whitelist) — best-effort green path

| Mod | Notes |
|-----|--------|
| Create 6.0.x | Boots to Folia `Done`; tick/region gaps under load still tracked |
| Farmers Delight | Boots with Cloth |
| Cloth Config | OK |
| Moonlight Lib | Needs Eturlia ServerPlayer `adjustSpawnLocation` bridge |

Anything on this list is regression-tested in smoke when jars are available. “Boots” ≠ “region-safe forever”.

## Unsupported mods = at your own risk

- Mods that assume a single server thread, mutate other regions synchronously, or ship broken Folia entity/section mixins are **unsupported**.
- Crashes, world corruption, or lost chunks from such mods are **not** considered Eturlia release blockers.
- Prefer Folia-aware schedulers (`RegionScheduler` / `EntityScheduler`) and avoid global entity list walks from region threads.
- Put NeoForge-only jars in `mods/`; Bukkit plugins must declare `folia-supported: true`.

## Region crash reports

Vanilla/Paper reports stay in `crash-reports/`.

Eturlia writes **region-enriched** reports to a **separate** folder:

```text
eturlia-crash-reports/crash-<timestamp>-region-<id>.txt
eturlia-crash-reports/crash-<timestamp>-region-<id>.json
```

Override with `-Deturlia.crash.dir=<path>`.

## Cross-region guard

`-Deturlia.region.guard=WARN|STRICT|OFF` (default `WARN`).

## Semver

- **0.x** — experimental hybrid kernel; breaking NMS/API patches expected between minors.
- Artifact: `eturlia-1.21.1-neoforge-<nf>.jar` — Minecraft + NeoForge line is part of the filename; Eturlia release tags follow `vMAJOR.MINOR.PATCH`.
