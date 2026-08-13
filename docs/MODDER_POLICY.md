# What Eturlia promises a mod or a plugin, and what it does not

Short version: **bring the jar as its author shipped it.** Nothing in `mods/` or `plugins/` is
patched, repackaged or renamed, and nothing is expected to declare anything special.

## Plugins

* **`folia-supported` is not required.** Folia refuses plugins that do not declare it; Eturlia drops
  that gate (`-Deturlia.compat.plugins=true`), and the legacy `BukkitScheduler` is given the global
  tick to run on. WorldGuard, EssentialsX, LuckPerms, ProtocolLib and CoreProtect all load unmodified.
* **Spigot-mapped plugins are remapped** (`-Deturlia.compat.plugin-remap=true`). A jar with classes
  this JVM cannot load is retried without them rather than refused outright.
* **Modded content has a Bukkit answer.** A modded entity reads as `UNKNOWN` and a modded block as
  `Material.STONE` to plugin code (`-Deturlia.compat.bukkit-types=lenient`) instead of throwing. A
  plugin that switches exhaustively on `Material` still sees something it can handle; a plugin that
  assumes it is meaningful will act on stone.
* **`libraries:` / a Paper `PluginLoader` that resolves Maven artifacts does not work yet.** Paper's
  `MavenLibraryResolver` throws on construction in this build, so such a plugin is skipped at load.
  Tracked in [`HANDOFF.md`](HANDOFF.md) §5.1 — it is a class of failure, not one plugin.
* **A region-aware plugin is still better.** `RegionScheduler` and `EntityScheduler` do the right
  thing; the compatibility path exists so that unported plugins run at all, not because it is free.

## Mods

* **NeoForge 21.1.248 mods load as-is.** The pack this is measured against is a production modpack
  copied out unmodified — see the full list in the [README](../README.md).
* **Three jars are renamed to `*.jar.eturlia-skipped` at startup**, with the reason printed:
  `spark-neoforge` (conflicts with the bundled spark), `ferritecore` (replaces the block-state tables
  Paper already replaced), and the Arclight-only `arclight_sable_patch` (Eturlia has the bridges).
  Files are never deleted; `hygiene.mods-folder: warn` in `config/eturlia.yml` turns the renaming off.
* **A failed mixin injector is not fatal** (`-Deturlia.compat.mixins=soft`): the broken mixin is
  dropped by name and the class is transformed again from a clean copy. Everything else in the mod
  keeps working.
* **Registries reopen for late registration** and errors in the loading issue list do not stop
  startup (`registries=lenient`, `modloading=lenient`).
* **A mod may build its own `Level`.** Contraption worlds, schematic worlds and physics sub-levels
  construct and tick. A chunk that **no region owns** may be loaded from the calling thread
  (`-Deturlia.compat.sublevel-chunks`, `strict` restores Folia's refusal).

## What is not promised

* **Region safety.** A mod that assumes a single server thread, walks the global entity list from a
  region thread, or mutates another region synchronously is **unsupported**. Crashes, lost chunks or
  world corruption caused by such a mod are not release blockers here.
  `-Deturlia.region.guard=STRICT` turns those calls into rejections and is how you find them;
  `WARN` (the default) logs them.
* **Client-side behaviour.** Anything a mod draws on the client is outside this core entirely. A
  visual artefact should first be proved server-side with `-Deturlia.debug.particles=true`, which
  names every particle type the server actually sends.
* **Native code.** A mod backed by a native library (Rapier, in Create Aeronautics' case) can abort
  the JVM outright, with no crash report, if it is left half-built by a Java exception. The Java
  error is the one to chase.

## Crash reports

Vanilla and Paper reports stay in `crash-reports/`. Eturlia writes region-enriched reports to a
separate directory:

```
eturlia-crash-reports/crash-<timestamp>-region-<id>.txt
eturlia-crash-reports/crash-<timestamp>-region-<id>.json
```

Override with `-Deturlia.crash.dir=<path>`.

## Versioning

* **0.x** — experimental hybrid core; breaking NMS/API changes are expected between minors.
* Artifact name carries the game and loader line: `eturlia-1.21.1-neoforge-<neoforge version>.jar`.
* Release tags follow `vMAJOR.MINOR.PATCH`.
