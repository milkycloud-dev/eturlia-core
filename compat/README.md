# Eturlia Compat — Compatibility Modules

> **Status: design sketch, not working code.**
>
> Both modules (`eturlia-compat-create`, `eturlia-compat-sable`) are skeletons: every
> handler method is an empty stub, no mixins are applied, and the mod dependencies are
> not pinned to resolvable coordinates yet. They are *not* built by the root project or
> by CI, and installing them into `mods/` currently has no effect.
>
> The mixin configs that used to ship here were removed: they declared `"required": true`
> and referenced mixin classes and mixin plugins that do not exist in this tree, so the
> modules crashed mod loading instead of doing nothing. Re-add a config only together with
> the actual mixin classes.
>
> The rest of this document describes the intended design.

This directory contains the compatibility layer modules for the Eturlia (Folia-based)
NeoForge server project. Each module is meant to patch a specific mod to operate correctly
on Folia's regionized threading model.

## Architecture Overview

```
                    ┌─────────────────────────────────────┐
                    │        Eturlia (Folia-based)         │
                    │      Regionized World Server        │
                    │                                     │
                    │  Region Thread A  Region Thread B  │
                    │  ┌───────────┐  ┌───────────────┐  │
                    │  │ Region A  │  │   Region B    │  │
                    │  │ Chunks,   │←→│  Chunks,      │  │
                    │  │ Entities  │  │  Entities     │  │
                    │  └─────┬─────┘  └──────┬────────┘  │
                    │        │               │           │
                    └────────┼───────────────┼───────────┘
                             │               │
                    ┌────────┼───────────────┼───────────┐
                    │  Compat Layer          │           │
                    │  (This directory)     │           │
                    │                        │           │
                    │  ┌────────────────────┘           │
                    │  │                                │
                    │  ▼                                ▼
                    │  eturlia-compat-create    eturlia-compat-sable
                    │  ├─ KineticNetwork       ├─ SubLevelManager
                    │  ├─ ContraptionHandler   ├─ PhysicsBridge
                    │  ├─ ProjectileHandler    ├─ JNI Auditor
                    │  └─ ExplosionHandler     └─ VehicleAssembly
                    └───────────────────────────────────┘
```

## Modules

### eturlia-compat-create

**Target:** Create + Create Big Cannons
**Package:** `com.eturlia.compat.create`
**Mod ID:** `eturlia_compat_create`

Patches Create's mechanical systems for Folia region threading:

| Component | Description |
|-----------|-------------|
| `RegionAwareKineticNetwork` | Partitions kinetic networks into per-region segments, propagating boundary values via `RegionizedTaskQueue` |
| `ContraptionRegionHandler` | Fragments contraption updates across region boundaries, handles trains spanning 3+ regions |
| `RegionizedProjectileHandler` | Tracks CBC projectile ownership across regions via `EntityScheduler`, handles high-speed region crossings |
| `CrossRegionExplosionHandler` | Fans out explosion damage to affected region threads, collects partial results |

### eturlia-compat-sable

**Target:** Sable + Create Aeronautics
**Package:** `com.eturlia.compat.sable`
**Mod ID:** `eturlia_compat_sable`

Bridges Sable's Rapier physics engine (JNI) to Folia's region threads:

| Component | Description |
|-----------|-------------|
| `CrossRegionSubLevelManager` | Manages two-phase handoff protocol for physics sub-levels crossing region boundaries |
| `SablePhysicsRegionBridge` | Async message queues from physics thread to region threads with back-pressure handling |
| `JNIThreadSafetyAuditor` | Bytecode hooks at JNI entry points, detects unsafe cross-thread Minecraft access |
| `VehicleAssemblyRegionHandler` | Region-safe aircraft assembly/disassembly, emergency block scatter across regions |

## Folia Region Threading Model

On a Folia server, the world is divided into `RegionizedWorldSection`s (typically 3×3 chunk
groups). Each section is ticked by a dedicated region thread. The key rules are:

1. **No cross-region access** — A region thread must not directly access block entities,
   entities, or chunk data owned by another region.
2. **Use RegionizedTaskQueue** — Cross-region operations must be scheduled via
   `RegionizedTaskQueue`, which submits work to the target region's thread.
3. **Use EntityScheduler** — Entity operations (tick, damage, remove) must happen on the
   entity's owning region thread via `EntityScheduler`.
4. **No synchronous blocking** — Region threads must not block waiting for other regions,
   as this can deadlock the server.

## Current build status

These modules are still **experimental** and are maintained as a separate compat workspace.

- `compat/` has **no standalone Gradle wrapper** (`./gradlew` inside this folder will fail).
- Root CI currently validates the main Eturlia core path (`applyPatches` + standalone jar smoke).
- The compat Gradle scripts are kept as a draft integration layer and may require local adjustment before full module builds.

## Adding a New Compat Module

1. Create a new directory: `eturlia-compat-<name>/`
2. Follow the structure of existing modules:
   ```
   eturlia-compat-<name>/
   ├── build.gradle.kts
   └── src/main/
       ├── java/com/eturlia/compat/<name>/
       │   ├── <MainClass>.java
       │   └── ... (feature classes)
       └── resources/
           ├── META-INF/neoforge.mods.toml
           └── eturlia-compat-<name>.mixins.json
   ```
3. Add `include(":eturlia-compat-<name>")` to `settings.gradle.kts`
4. Update this README with the module details

## Test Pack

The `testpack/` directory currently stores the compatibility manifests and notes
used for manual/iterative validation planning. See
[testpack/README.md](testpack/README.md) for the current scope.
