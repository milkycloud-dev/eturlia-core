<div align="center">
    <img src="./folia.png" alt="Crelia" width="720">
    <h1>Crelia</h1>
    <p>Folia region threading + NeoForge mod loading — Minecraft <strong>1.21.1</strong> server kernel.</p>
</div>

> [!WARNING]
> Experimental. Not production-ready. Many mods will be incompatible with regionized threading.

## What this is

Crelia builds a **single server core jar** that combines:

- **[Folia](https://github.com/PaperMC/Folia)** — Paper fork with per-region multi-threading
- **[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248** — latest NeoForge for MC 1.21.1 (FancyModLoader 4.0.43)

Goal: run Create / tech-mod packs on Folia’s multi-core region model.

## Versions

| Component | Version |
|-----------|---------|
| Minecraft | 1.21.1 |
| NeoForge | **21.1.248** (latest 21.1.x) |
| FML (FancyModLoader) | 4.0.43 |
| Paper upstream | `84281ceeefb9d294758a9a292ba6c01da40e8409` (Folia `dev/1.21.1`) |
| Java | 21 |

## Build

Requires **Git clone** (not a ZIP), **JDK 21**, and network access to PaperMC + NeoForged Maven.

```bash
# 1) Apply Folia + Crelia/NeoForge patches onto Paper
./gradlew applyPatches

# 2) Build Folia-Server (reobf / paperclip as usual)
./gradlew :folia-server:build

# 3) Build the nested standalone kernel jar
./gradlew :folia-server:creliaStandaloneJar
```

Output:

```text
build/libs/crelia-1.21.1-neoforge-21.1.248.jar
```

Shortcuts:

```bash
./patch.sh          # applyPatches
./rb.sh             # rebuild Paper/server/minecraft patches
```

## Run

```bash
java -jar build/libs/crelia-1.21.1-neoforge-21.1.248.jar
```

The launcher extracts nested libraries, then starts `crelia.CreliaServer` with FML args for MC 1.21.1 / NeoForge 21.1.248.

Accept `eula.txt` on first run. Put NeoForge mods in `mods/`. Plugins still need `folia-supported: true`.

## Architecture (short)

1. **paperweight 1.7.3** applies `patches/api` + `patches/server` (Folia region patches + NeoForge event-hook patches) onto Paper.
2. **Shims** under `build-data/crelia-neoforge-shims` let patched Minecraft sources compile against NeoForge API stubs.
3. **Published NeoForge universal** `21.1.248` is embedded at runtime (not the wrong MC 26 NeoForge tree from older Crelia forks).
4. **Coremods** use NeoForge 21.1 `ICoreMod` SPI (not the FML 7 `ClassProcessorProvider` API).
5. **Crelia launcher** ships a fat jar with Folia server + FML + NeoForge + Crelia runtime.

## Upstream

- [PaperMC/Folia](https://github.com/PaperMC/Folia) (`dev/1.21.1`)
- [PaperMC/Paper](https://github.com/PaperMC/Paper)
- [NeoForged/NeoForge](https://github.com/neoforged/NeoForge) (`1.21.1` / 21.1.x)
- Reference forks: [holynwk/Crelia](https://github.com/holynwk/Crelia), [SOURsLEMONS/Crelia](https://github.com/SOURsLEMONS/Crelia)

## License

Different trees use different licenses. Folia/Paper patches: [`PATCHES-LICENSE`](./PATCHES-LICENSE). NeoForge code: upstream LGPL / file headers.

## Patch status

Active server patches: Folia `0001`–`0019` + NeoForge hooks `0020`–`0025` (API-aligned to NeoForge **21.1.248**).

Additional NeoForge hook batches `0033`–`0040` are kept under `patches/server-wip/` — they were authored against incomplete shims and do not apply cleanly yet. They will be rebased onto 21.1.248 in a follow-up.
