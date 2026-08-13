<p align="center">
  <img src="docs/assets/eturlia-logo.png" alt="Eturlia" width="140" height="140">
</p>

<h1 align="center">Eturlia</h1>

<p align="center">
  A Minecraft <b>1.21.1</b> server core that runs a <b>NeoForge modpack</b> and a <b>Bukkit/Paper plugin set</b>
  at the same time, on <b>Folia's multi-threaded (regionised) engine</b>, with <b>neither the mods nor the plugins modified</b>.
</p>

<p align="center">
  <sub>Folia 1.21.1 · NeoForge 21.1.248 · Java 21 · tested against 87 mod jars and 38 plugin jars</sub>
</p>

---

**Русская версия — [ниже по этому же файлу](#eturlia-по-русски). Она полная, а не сокращённая.**

---

## Table of contents

1. [What this is, in one paragraph](#1-what-this-is-in-one-paragraph)
2. [If you have never run a Minecraft server](#2-if-you-have-never-run-a-minecraft-server)
3. [The problem Eturlia exists to solve](#3-the-problem-eturlia-exists-to-solve)
4. [The one rule the whole project follows](#4-the-one-rule-the-whole-project-follows)
5. [What happens when it boots](#5-what-happens-when-it-boots)
6. [Where it stands right now](#6-where-it-stands-right-now)
7. [The test rig — the exact pack this is measured against](#7-the-test-rig--the-exact-pack-this-is-measured-against)
8. [Build](#8-build)
9. [Run](#9-run)
10. [Configuration](#10-configuration)
11. [How this is tested](#11-how-this-is-tested)
12. [Known limitations and open problems](#12-known-limitations-and-open-problems)
13. [Repository layout](#13-repository-layout)
14. [Adding a fix](#14-adding-a-fix)
15. [Upstream, and the licence](#15-upstream-and-the-licence)

---

## 1. What this is, in one paragraph

Eturlia is a **server core** — the program that runs a Minecraft server. It is a fork of
[Folia](https://github.com/PaperMC/Folia) (Paper's multi-threaded server) with the
[NeoForge](https://github.com/neoforged/NeoForge) mod loader built into the same jar. You drop your
NeoForge mods into `mods/`, your Bukkit/Paper plugins into `plugins/`, start one jar, and both halves
run — while the world itself is ticked by several threads at once instead of one. Nothing in `mods/`
or `plugins/` is patched, repackaged or renamed: every fix required to make a mod or a plugin work
lives **inside the core**.

That combination — modpack **+** plugins **+** real multi-threading — is the entire point. Every
other option available today gives you at most two of the three.

---

## 2. If you have never run a Minecraft server

This section exists so that the rest of the file makes sense to someone who has not spent years in
this ecosystem. If you already know what Folia is, skip to [§3](#3-the-problem-eturlia-exists-to-solve).

| Term | What it actually means |
|---|---|
| **Server core** | The server-side program itself. Mojang ships one (*vanilla*). Everyone else runs a modified build of it, because vanilla is slow and has no extension points. |
| **Spigot / Paper** | The mainstream forks of the vanilla server. **Paper** is the de facto standard: faster, with thousands of bug fixes and a large configuration surface. |
| **Plugin** | A server-side add-on written against the Bukkit/Paper API. It is installed **only on the server**; players join with an unmodified client. Permissions, land claims, chat, moderation, economy — all plugins. |
| **Mod (NeoForge)** | A modification that changes the game itself: new blocks, items, mobs, dimensions, machines. It must be installed on **both** the server and every player's client, and it patches game classes directly at runtime. NeoForge is the loader that makes this possible for modern versions. |
| **Modpack** | A curated set of mods (here: 87 jars) that players install through a launcher. |
| **Hybrid core** | A core that loads *both* mods and plugins — Cauldron, Mohist, Arclight, Youer. Historically fragile, and all of them **single-threaded**. |
| **Folia** | Paper's regionised fork. It cuts the world into independent square **regions** and ticks each region on its own thread, so a 32-core machine is actually used. The price: the entire single-thread assumption that every mod and most plugins were written under is gone. |
| **Region** | A group of nearby loaded chunks that is owned by exactly one thread. Regions split and merge as players move. Touching a chunk that belongs to *another* region from your thread is a bug that Folia detects and refuses. |
| **Tick** | One step of the game loop, 20 per second. In Folia each region has its own tick, on its own thread. |

**Why "mods and plugins together" is hard:** a mod assumes it is alone with a single server thread
and reaches into any part of the world at any time. A plugin assumes the Bukkit API and, on Folia,
must be declared region-aware or it is refused at load. Folia assumes vanilla data structures that
NeoForge modifies. Each of the three was designed without the other two in mind.

---

## 3. The problem Eturlia exists to solve

The concrete situation this was built for: a live server (**NoteBuns**) running a 90-mod pack with a
full plugin stack for moderation, permissions, chat and land protection. Today that runs on a
single-threaded hybrid core (Youer/Arclight family). One CPU core is saturated while the other 31
idle; a single busy chunk lags every player on the map.

The three available options and what each costs:

| Option | Mods | Plugins | Multi-threaded | Verdict |
|---|:--:|:--:|:--:|---|
| Paper | ✗ | ✓ | ✗ (one main thread) | no modpack at all |
| Folia | ✗ | ✓ (region-aware only) | ✓ | no modpack at all |
| Arclight / Mohist / Youer | ✓ | ✓ | ✗ | what we run today, and the reason for the lag |
| **Eturlia** | ✓ | ✓ | ✓ | this repository |

What actually goes wrong when you naively put NeoForge on Folia — these are real failures from this
project's logs, not hypotheticals:

* **A mod inherits something Paper deleted.** Paper's chunk system rewrite (Moonrise) and its light
  engine rewrite (Starlight) *remove* fields and methods that vanilla had. A mod compiled against
  vanilla still calls them. Nothing warns at compile time; it explodes as `NoSuchFieldError` /
  `NoSuchMethodError` the first time that code runs. On Folia a failed region tick shuts the whole
  server down.
* **A mod builds a world of its own.** Create's contraption world, its schematic world and Sable's
  physics sub-levels all construct a `Level` that is not a `ServerLevel`. Folia's constructor threw
  for any such level — once per tick. The visible symptom for a player: a Create machine assembles
  and then does nothing, and cannot be removed.
* **A plugin is refused for not declaring `folia-supported`.** Folia hard-gates plugins. Almost no
  plugin in the wild sets that flag, including WorldGuard, EssentialsX and LuckPerms.
* **Folia deletes vanilla commands.** `/scoreboard`, `/team`, `/data`, `/clone`, `/datapack` and a
  dozen others are commented out upstream because they are not region-safe. Datapacks, map-makers
  and half the plugin ecosystem expect them.
* **A native library dies without a trace.** Create Aeronautics' physics is Rust (Rapier) behind
  JNI. A Java exception mid-construction leaves it half-built; its next native call panics in a
  function that cannot unwind and **aborts the JVM** — no crash report, the wrapper just restarts.

---

## 4. The one rule the whole project follows

> **The core absorbs the incompatibility. Mods and plugins are never touched.**

The obvious alternative — patch each mod for regions — dies at the first pack update. Every jar in
`mods/` and `plugins/` here is byte-for-byte what its author shipped.

Every change therefore has to pass one test:

> *If a different mod hits the same error tomorrow, does it work without a new patch?*

If the answer is no, it is not a fix — it is a workaround aimed at one mod, and it does not go in.
In practice that means implementing the **whole** upstream interface rather than the one method that
crashed, and restoring the **member** Paper removed rather than special-casing the caller.

Every part of the compatibility layer is switchable, and `strict` always restores stock Folia
behaviour — which is how you find out whose behaviour you are looking at.

---

## 5. What happens when it boots

```
eturlia-1.21.1-neoforge-21.1.248.jar
        │
        ├─ 1. launcher unpacks its bundled libraries into eturlia-libraries/
        │
        ├─ 2. hands over to ModLauncher / FancyModLoader (NeoForge 21.1.248)
        │       · mods/ is scanned; incompatible jars are renamed *.jar.eturlia-skipped
        │       · mixins are applied; a failing injector is dropped by name instead of killing boot
        │
        ├─ 3. Folia's server starts: regions, chunk system (Moonrise), Starlight
        │       · region-tick-threads worker threads come up
        │
        ├─ 4. CraftBukkit layer starts: plugins/ is loaded
        │       · the folia-supported gate is dropped; the legacy BukkitScheduler runs on the global tick
        │       · a modded entity reads as UNKNOWN and a modded block as STONE to plugin code
        │
        └─ 5. worlds load, regions begin ticking in parallel
```

The compatibility layer is not a runtime agent — it is **source**, generated into the Folia tree
before compilation by `scripts/apply_compat_layer.py`, so what ships is an ordinary server jar.

---

## 6. Where it stands right now

Measured on the test rig on **2026-08-13**, build `2026-08-13T11:46:07Z`:

| | |
|---|---|
| Boot | `Done (12.673s)` |
| Mods | **115 mods** loaded from **87 jars** (jar-in-jar included) |
| Plugins | **37** loaded from **38 jars** |
| Alarming lines in a full boot | **2**, both third-party plugin bugs (ImageFrame, KartaAutoAnnouncer) |
| Mod classes that cannot bind to the core | **0** outside datagen — 15 final-overrides + 10 unimplemented interface methods, all in recipe/data generators that never run on a server (`tools/finalscan.py`, 87 jars, 36 196 mod classes against 8 674 core classes) |
| Plugin commands registered | **224 of 225** declared |
| Vanilla commands present | **48 of 48** that exist in 1.21.1 |
| Create machinery | a bearing driven through a shaft chain assembles and lifts its blocks; 10 assemble/disassemble cycles with 0 errors |
| Create: Aeronautics | contraptions assemble on a world border; physics sub-levels construct and tick |
| Regions | tick in parallel, one thread per region |

The per-failure-class account of every fix is [`docs/FIXES.md`](docs/FIXES.md). The live state
document — what is open, what was already ruled out — is [`docs/HANDOFF.md`](docs/HANDOFF.md).

> [!WARNING]
> This is an experimental core. Keep world backups. Some mods assume a single server thread and
> will behave on regions in ways their authors never tested.

---

## 7. The test rig — the exact pack this is measured against

Every number in this file comes from one machine running one pack. Both are listed here in full, so
that "it works" means something checkable.

**Hardware and JVM**

| | |
|---|---|
| Box | 32 logical cores, 124 GB RAM (shared with an unrelated production server) |
| Java | Temurin 21.0.12+8 |
| Heap | `-Xms8G -Xmx8G`, ZGC generational |
| Threads | `region-tick-threads: 16`, `chunk-worker-threads: 8`, `chunk-io-threads: 3` |
| Compat flags | `mixins=soft`, `plugins=true`, `plugin-remap=true`, `registries=lenient`, `modloading=lenient`, `folia-stubs=lenient`, `bukkit-types=lenient` |

The pack is **not** a curated test set — it is the production pack of a live server, copied out
unmodified. That is deliberate: a test pack proves nothing about a real one.

### 7.1 Mods — 87 jars in `mods/`, 1:1

Filenames are verbatim; the mod id and display name are read from each jar's
`META-INF/neoforge.mods.toml`.

**Create family — the reason this project exists in its current shape**

| Jar | Mod id | Name |
|---|---|---|
| `create-1.21.1-6.0.10.jar` | `create` | Create |
| `create-aeronautics-bundled-1.21.1-1.3.0.jar` | `aeronautics_bundled` | Create Aeronautics |
| `sable-neoforge-1.21.1-2.0.3.jar` | `sable` | Sable (physics sub-levels, Rust/Rapier via JNI) |
| `aeronauticscompat-1.1.3.jar` | `aeronauticscompat` | AeronauticsCompat |
| `copycats-3.0.4+mc.1.21.1-neoforge.jar` | `copycats` | Create: Copycats+ |
| `cable_facades-1.21.1-NeoForge-2.1.3.jar` | `cable_facades` | Cable Facades |
| `SSRD-1.8.5-1.21.1.jar` | `ssrd` | Separate Sable Render Distance |
| `sim_fluid_assembly_fix-1.0.0.jar` | `sim_fluid_assembly_fix` | Sim Fluid Assembly Fix |

**Content — dimensions, mobs, structures, worldgen**

| Jar | Mod id | Name |
|---|---|---|
| `twilightforest-1.21.1-4.8.3345-universal.jar` | `twilightforest` | The Twilight Forest |
| `tf_dnv-2.0.3.jar` | `tf_dnv` | Twilight Forest — Dungeons & Villages |
| `tfsaplingdimlock-1.0.0+1.21.1-neoforge.jar` | `tfsaplingdimlock` | TF Sapling Dim Lock |
| `alexsmobs-1.22.17.jar` | `alexsmobs` | Alex's Mobs |
| `malum-1.21.1-1.8.2.jar` | `malum` | Malum |
| `BetterEnd-21.0.25.jar` | `betterend` | Better End |
| `Terralith_1.21.1_v2.6.2_Neoforge.jar` | `lithostitched` | Terralith |
| `Incendium_1.21.x_v5.4.4.jar` | `incendium` | Incendium |
| `ibo-3.1.0-neoforge-1.21.jar` | — | Incendium Biomes Only |
| `dungeons+-1.10.1.jar` | — | Dungeons+ |
| `dungeons-and-taverns-v4.4.4.jar` | — | Dungeons and Taverns |
| `YungsBetterDungeons-1.21.1-NeoForge-5.1.4.jar` | `betterdungeons` | YUNG's Better Dungeons |
| `YungsBetterNetherFortresses-1.21.1-NeoForge-3.1.5.jar` | `betterfortresses` | YUNG's Better Nether Fortresses |
| `FarmersDelight-1.21.1-1.3.2.jar` | `farmersdelight` | Farmer's Delight |
| `letsdo-bakery-neoforge-2.1.6.jar` | `bakery` | [Let's Do] Bakery |
| `letsdo-brewery-neoforge-2.1.9.jar` | `brewery` | [Let's Do] Brewery |
| `letsdo-farm_and_charm-neoforge-1.1.22.jar` | `farm_and_charm` | [Let's Do] Farm & Charm |
| `letsdo-furniture-neoforge-1.1.4.jar` | `furniture` | [Let's Do] Furniture |
| `letsdo-vinery-neoforge-1.5.3.jar` | `vinery` | [Let's Do] Vinery |
| `supplementaries-neoforge-1.21.1-3.6.8.jar` | `supplementaries` | Supplementaries |
| `amendments-1.21-2.0.15-neoforge.jar` | `amendments` | Amendments |
| `beautify-neoforge-1.21.1-2.0.2.jar` | `beautify` | Beautify |
| `toomanypaintings-25.10.20-1.21-neoforge.jar` | `toomanypaintings` | Too Many Paintings |
| `travelersbackpack-neoforge-1.21.1-10.1.36.jar` | `travelersbackpack` | Traveler's Backpack |
| `horseman-neoforge-1.21.1-1.5.11.jar` | `horseman` | Horseman |
| `corpse-neoforge-1.21.1-1.1.17-fix4.jar` | `corpse` | Corpse |
| `easy_npc-neoforge-1.21.1-6.20.0.jar` | `easy_npc` | Easy NPC |
| `easy_npc_bundle-neoforge-1.21.1-6.20.0.jar` | `easy_npc_bundle` | Easy NPC: Bundle |
| `easy_npc_config_ui-neoforge-1.21.1-6.20.0.jar` | `easy_npc_config_ui` | Easy NPC: Config UI |
| `weaponmaster_ydm-1.21.1-neoforge-4.2.7.jar` | `weaponmaster_ydm` | YDM's Weapon Master |
| `starcatcher-2.3.15-NEOFORGE-1.21.1.jar` | `starcatcher` | Starcatcher |
| `immersive_melodies-neoforge-0.6.4+1.21.1.jar` | `immersive_melodies` | Immersive Melodies |
| `ArmorPoser-neoforge-1.21.1-6.2.3.jar` | `armorposer` | Armor Poser |
| `VisualWorkbench-v21.1.1-1.21.1-NeoForge.jar` | `visualworkbench` | Visual Workbench |
| `Almanac-1.21.1-2-neoforge-1.5.2.jar` | `almanac` | Almanac |

**Libraries — loaded by the mods above, and the usual source of binding failures**

| Jar | Mod id | Name |
|---|---|---|
| `architectury-13.0.11-neoforge.jar` | `architectury` | Architectury |
| `kotlinforforge-5.12.0-all.jar` | — | Kotlin for Forge |
| `geckolib-neoforge-1.21.1-4.8.4.jar` | `geckolib` | GeckoLib 4 |
| `citadel-1.21.1-2.7.6.jar` | `citadel` | Citadel |
| `bookshelf-neoforge-1.21.1-21.1.81.jar` | `bookshelf` | Bookshelf |
| `curios-neoforge-9.5.1+1.21.1.jar` | `curios` | Curios API |
| `PuzzlesLib-v21.1.52-1.21.1-NeoForge.jar` | `puzzleslib` | Puzzles Lib |
| `moonlight-neoforge-1.21.1-3.0.17.jar` | `moonlight` | Moonlight Lib |
| `lodestone-1.21.1-1.8.2.jar` | `lodestone` | Lodestone |
| `resourcefullib-neoforge-1.21-3.0.12.jar` | `resourcefullib` | Resourceful Lib |
| `resourcefulconfig-neoforge-1.21-3.0.11.jar` | `resourcefulconfig` | Resourcefulconfig |
| `cloth-config-15.0.140-neoforge.jar` | `cloth_config` | Cloth Config v15 API |
| `yet_another_config_lib_v3-3.8.2+1.21.1-neoforge.jar` | `yet_another_config_lib_v3` | YetAnotherConfigLib |
| `CreativeCore_NEOFORGE_v2.13.41_mc1.21.1.jar` | `creativecore` | CreativeCore |
| `Iceberg-1.21.1-neoforge-1.3.2.jar` | `iceberg` | Iceberg |
| `konkrete_neoforge_1.9.9_MC_1.21.jar` | `konkrete` | Konkrete |
| `libjf-3.17.6+forge.jar` | `libjf` | LibJF |
| `mru-1.0.19+LTS+1.21.1+neoforge.jar` | — | M.R.U |
| `anvianslib-neoforge-1.21-1.4.2.jar` | `anvianslib` | Anvian's Lib |
| `prickle-neoforge-1.21.1-21.1.11.jar` | `prickle` | PrickleMC |
| `coroutil-neoforge-1.21.0-1.3.8.jar` | `coroutil` | CoroUtil |
| `YungsApi-1.21.1-NeoForge-5.1.6.jar` | `yungsapi` | YUNG's API |
| `lithostitched-1.7.10+beta4-neoforge-21.1.jar` | `lithostitched` | Lithostitched |
| `bclib-21.0.21.jar` | `bclib` | BCLib |
| `wunderlib-21.0.10.jar` | `wunderlib` | WunderLib |
| `worldweaver-21.0.21.jar` | `wover` | WorldWeaver |
| `badpackets-neo-0.8.2.jar` | `badpackets` | Bad Packets |
| `packetfixer-3.3.1-1.20.5-1.21.X-merged.jar` | `packetfixer` | PacketFixer |
| `respackopts-4.14.0+1.21.1.forge.4.jar` | `respackopts` | Resource Pack Options |

**Client-side and quality of life — present on the server because the pack ships them**

| Jar | Mod id | Name |
|---|---|---|
| `jei-1.21.1-neoforge-19.27.0.340.jar` | `jei` | Just Enough Items |
| `wthit-1.21.1-neo-12.10.2.jar` | `wthit` | wthit |
| `xaeroworldmap-neoforge-1.21.1-1.44.2.jar` | `xaeroworldmap` | Xaero's World Map |
| `voicechat-neoforge-1.21.1-2.6.18.jar` | `voicechat` | Simple Voice Chat |
| `emotecraft-for-MC1.21.1-2.4.12-neoforge.jar` | `emotecraft` | Emotecraft |
| `Emojiful-Neoforge-1.21-5.2.4-all.jar` | `emojiful` | Emojiful |
| `sound-physics-remastered-neoforge-1.21.1-1.5.1.jar` | `sound_physics_remastered` | Sound Physics Remastered |
| `clientsort-neoforge-2.2.2+1.21.1.jar` | `clientsort` | ClientSort |
| `polymorph-neoforge-1.1.0+1.21.1.jar` | `polymorph` | Polymorph |
| `fogoverrides-1.21.1-2.3.0.jar` | `fogoverrides` | Fog Overrides |
| `attributefix-neoforge-1.21.1-21.1.3.jar` | `attributefix` | AttributeFix |
| `letmedespawn-1.21.x-neoforge-1.5.0.jar` | `letmedespawn` | Let Me Despawn |
| `tt20-0.8.4+mc1.21.1-neoforge.jar` | `tt20` | TT20 |
| `chunkholdersafe-1.0.0+1.21.1-neoforge.jar` | `chunkholdersafe` | ChunkHolder Safe |
| `notebuns-farmcharm-fix-1.0.0.jar` | `notebuns_farmcharm_fix` | NoteBuns FarmCharm Fix |

**Skipped automatically** — the core renames these to `*.jar.eturlia-skipped` at startup and prints
the reason; the files are never deleted:

| Jar | Reason printed by the core |
|---|---|
| `spark-1.10.124-neoforge.jar` | conflicts with Eturlia's Folia-bundled spark (JPMS); `/spark` stays available |
| `ferritecore-7.0.3-neoforge.jar` | it replaces the block-state tables Paper already replaced |
| `arclight_sable_patch-1.1.1.jar` | targets Arclight; Eturlia already has the Folia bridges |

### 7.2 Plugins — 38 jars in `plugins/`, 1:1

| Jar | Plugin name | Version | What it exercises |
|---|---|---|---|
| `AuthMe-5.7.0-FORK-Universal.jar` | AuthMe | 5.7.0-FORK-b53 | login flow, early packet handling |
| `LuckPerms-Bukkit-5.5.22.jar` | LuckPerms | 5.5.22 | permissions, including `minecraft.command.*` |
| `worldedit-youer-7.3.8-1.jar` | WorldEdit | 7.3.8 | bulk block edits across region boundaries |
| `worldguard-bukkit-7.0.12-dist.jar` | WorldGuard | 7.0.12 | region flags, event interception |
| `WG-GUI-1.10.1.jar` | WG-GUI | 1.10.1 | WorldGuard UI |
| `EssentialsX-2.22.1-dev+12-776f709.jar` | Essentials | 2.22.1-dev | the largest command surface in the set |
| `EssentialsXChat-2.22.1-dev+12-776f709.jar` | EssentialsChat | 2.22.1-dev | chat pipeline |
| `EssentialsXSpawn-2.22.1-dev+12-776f709.jar` | EssentialsSpawn | 2.22.1-dev | cross-region teleport |
| `CoreProtect-24.1.jar` | CoreProtect | 24.1 | async block logging under regions |
| `ProtocolLib.jar` | ProtocolLib | 5.4.0 | packet interception |
| `PlaceholderAPI-2.12.3.jar` | PlaceholderAPI | 2.12.3 | plugin-to-plugin API surface |
| `VaultUnlocked-2.20.1.jar` | Vault | 2.20.1 | service registration |
| `LiteBans.jar` | LiteBans | 2.18.7 | database access off the tick |
| `LiteBansAddon.jar` | TrollEffects | 1.0 | in-house addon |
| `PremiumVanish.jar` | PremiumVanish | 2.10.2 | player visibility |
| `SkinsRestorer.jar` | SkinsRestorer | 15.12.5 | profile/skin rewriting at login |
| `TAB v6.1.0.jar` | TAB | 6.1.0 | scoreboard/tablist packets |
| `DecentHolograms-2.10.1.jar` | DecentHolograms | 2.10.1 | per-player entity spawning |
| `PlayerParticles-8.10-youer.jar` | PlayerParticles | 8.10-youer.12 | server-driven particles |
| `PPGuiFix-1.0.jar` | PPGuiFix | 1.0 | in-house fix for the above |
| `GSit-3.1.0.jar` | GSit | 3.1.0 | entity mounting |
| `Chunky-Bukkit-1.4.55.jar` | Chunky | 1.4.55 | mass chunk generation |
| `ChunkyBorder-Bukkit-1.2.33.jar` | ChunkyBorder | 1.2.33 | world borders |
| `ChunkHeatMap-1.0.2.jar` | ChunkHeatMap | 1.0.2 | in-house chunk load profiler |
| `ChunkHeatMapAdmin-1.0.2.jar` | ChunkHeatMapAdmin | 1.0.2 | its admin half |
| `InvSee++.jar` | InvSeePlusPlus | 0.30.15 | remote inventory access |
| `InventoryRollbackPlus-1.8.3.jar` | InventoryRollbackPlus | 1.8.3 | inventory snapshots |
| `ImageFrame-2026.1.4.0.jar` | ImageFrame | 2026.1.4.0 | map rendering — **fails to enable, third-party bug** |
| `KartaAutoAnnouncer-1.3.1.jar` | KartaAutoAnnouncer | 1.3.1 | announcements — **fails to enable, missing embedded config** |
| `PlugManX-3.0.2.jar` | PlugManX | 3.0.2 | **does not load** — see [§12](#12-known-limitations-and-open-problems) |
| `KotlinMC-2.2.20.jar` | Kotlin | 2.2.20 | Kotlin runtime for plugins |
| `ConsoleSpamFixReborn-1.11.8.jar` | ConsoleSpamFixReborn | 1.11.8 | log filtering |
| `DemonicEye-1.0.0.jar` | DemonicEye | 1.0.0 | in-house |
| `DiscordChatBridge-1.0.0.jar` | DiscordChatBridge | 1.0.0 | in-house, outbound network |
| `FarCoordGuard-1.0.0.jar` | FarCoordGuard | 1.0.0 | in-house coordinate guard |
| `ModClaimGuard-1.0.0.jar` | ModClaimGuard | 1.0.0 | in-house, modded-block claims |
| `NoteBunsChatFix.jar` | NoteBunsChatFix | 1.0 | in-house |
| `StreamAnnounce-1.0.0.jar` | StreamAnnounce | 1.0.0 | in-house |

None of these declare `folia-supported`. They load because the core drops that gate and gives the
legacy scheduler somewhere to run.

---

## 8. Build

Requirements: **JDK 21**, Python 3, git, and roughly 8 GB of free RAM for the Gradle daemon.

```bash
./gradlew applyPatches
python3 scripts/apply_compat_layer.py
./gradlew :folia-server:eturliaStandaloneJar
```

The order is mandatory:

1. `applyPatches` unpacks the decompiled Minecraft/Paper/Folia source tree out of `patches/`.
   Nothing exists to edit before this runs.
2. `apply_compat_layer.py` writes the compatibility layer **into that source tree**. It is
   idempotent: run it twice and the second run prints `already applied` and changes nothing.
3. The Gradle task compiles everything and produces
   `core/build/libs/eturlia-1.21.1-neoforge-21.1.248.jar`.

If an anchor the generator expects is missing, it stops with `!! anchor missing` and the build does
not continue. That is intended: a silently skipped plane is worse than a failed build.

The whole loop — patch, generate, build, announce the restart in chat, stop, deploy, start, grade
the boot — is one command:

```bash
tools/cycle.sh
```

It prints one line per step and, if a step fails, the tail of that step's own log. CI
(`.github/workflows/eturlia-ci.yml`) runs the same order, so a change that is not in
`apply_compat_layer.py` does not exist as far as this project is concerned.

---

## 9. Run

```bash
java -Xms8G -Xmx8G -XX:+UseZGC -XX:+ZGenerational \
     --add-modules=jdk.incubator.vector \
     -jar eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

The jar is a launcher: it unpacks its bundled libraries into `eturlia-libraries/` beside itself and
starts the server JVM. From that point it is an ordinary server directory — `mods/`, `plugins/`,
`server.properties`, `config/paper-global.yml`, `spigot.yml`, `bukkit.yml`, all where you expect
them.

Players connect with the **same client modpack they would use for any NeoForge server**. Nothing
about Eturlia is visible to the client.

---

## 10. Configuration

### 10.1 Compatibility switches (JVM flags)

Every plane of the layer has a flag. `strict` (or `false`) always means *give stock Folia behaviour
back* — the fastest way to find out whether a symptom is ours or upstream's.

| Flag | Default | What it does |
|---|---|---|
| `-Deturlia.compat.mixins` | `soft` | a mod's failed mixin injector stops being fatal: the broken mixin is dropped by name and the class is transformed again from a clean copy |
| `-Deturlia.compat.modloading` | `lenient` | errors in the loading issue list do not stop startup; each mod's event bus is wrapped |
| `-Deturlia.compat.registries` | `lenient` | frozen registries reopen for late registration |
| `-Deturlia.compat.plugins` | `true` | drops the `folia-supported` gate; the legacy `BukkitScheduler` runs off the global tick |
| `-Deturlia.compat.folia-stubs` | `lenient` | `getTickCount()` answers with the global region's tick; `execute`/`tell`/`executeBlocking` schedule instead of throwing |
| `-Deturlia.compat.bukkit-types` | `lenient` | a modded entity reads as `UNKNOWN` and a modded block as `Material.STONE` to plugins, instead of throwing |
| `-Deturlia.compat.folia-commands` | `lenient` | re-registers the 17 vanilla commands Folia comments out: `/scoreboard`, `/team`, `/tag`, `/data`, `/clone`, `/function`, `/loot`, `/ride`, `/schedule`, `/spreadplayers`, `/datapack`, `/bossbar`, `/item`, `/trigger`, `/spectate`, `/teammsg`, `/return` |
| `-Deturlia.compat.plugin-remap` | `true` | Spigot→Mojang remapper for plugins; a jar with classes this JVM cannot load is retried without them |
| `-Deturlia.compat.sublevel-chunks` | `lenient` | lets a mod-built sub-level load a chunk **no region owns** from the calling thread; `strict` restores Folia's refusal |
| `-Deturlia.compat.read-timeout` | `90` | seconds Netty waits for a client that is still building the world; `0` removes the handler entirely |
| `-Deturlia.compat.quarantine` | — | comma-separated mod ids to skip at load |
| `-Deturlia.lithostitched.allow-unsafe` | off | answers Lithostitched's version gate once instead of a refusal block every boot |
| `-Deturlia.debug.particles` | off | logs each distinct particle type the **server** sends, once per 10 s — the way to prove a visual effect is or is not server-side |
| `-Deturlia.region.guard` | `WARN` | `STRICT` rejects cross-region calls, `WARN` logs them, `OFF` disables the guard |

### 10.2 `config/eturlia.yml`

Written on first boot from a template and documented inline (in Russian). It is the one place that
collects the settings otherwise spread across `paper-global.yml`, `server.properties`, `spigot.yml`
and `bukkit.yml`, and it explains what each does under regions. The template is kept in the repo as
[`docs/eturlia.yml.example`](docs/eturlia.yml.example).

The section that matters most:

```yaml
threads:
  region-tick-threads: 16     # Folia region tickers; -1 = auto
  region-grid-exponent: 2     # region cell = 2^n chunks per side; 2 → 4×4
  chunk-worker-threads: 8     # Moonrise: generation, lighting, heavy chunk tasks
  chunk-io-threads: 3         # region-file reads/writes
```

Thread pools are created once, while `paper-global.yml` is read. Changing them needs a full restart,
not a reload.

---

## 11. How this is tested

There is no unit-test suite for "does a 90-mod pack survive regions". The tooling is built around
booting the real thing and reading the answer back out of a running game.

| Tool | What it answers |
|---|---|
| `tools/cycle.sh` | one turn of the loop: patch → generate → build → deploy → restart → grade |
| `tools/logcheck.py` | grades a boot: groups every WARN/ERROR, hides the groups already judged benign (each carries its reason), prints only what is **new** since the last run |
| `tools/logsweep.py` | every log at every level, grouped by normalised shape; `--grep "text"` expands one shape |
| `tools/finalscan.py` | **without starting the server**: reads the compiled core against every mod jar (nested jar-in-jar included) and reports the two ways a mod cannot bind — overriding a method Paper sealed `final` (`IncompatibleClassChangeError` at class load) and implementing an interface CraftBukkit has since added an abstract method to (`AbstractMethodError` on first call) |
| `tools/modsweep.py` | console sweep: every command every plugin declares, `/summon` for a sample of modded entity types, `/setblock` + read-back for modded blocks, `/place feature` for modded worldgen, and modded block entities left ticking while it watches for exceptions |
| `tools/cmdtree.py` | maps a mod's command tree by reading what Brigadier underlines as unknown |
| `tools/aerotest.sh`, `tools/aerostress.sh` | Create and Create: Aeronautics driven by a real headless client |
| `tools/trailcheck.sh` | a lit empty stage with screenshots front/behind/third-person — the loop for chasing a visual bug |

**Reading an answer back out of the game.** Three obvious channels do not work on this build, and
each was learned expensively:

* `/say` is useless as a marker — the log keeps the translation key (`chat.type.announcement`) and
  drops the text, from the console *and* from a player.
* **Command feedback from a player is logged in full**: `[EturliaTester: Changed the block at x, y,
  z]`. That is the marker mechanism — give every assertion its own coordinate and read the
  coordinates back.
* The server also echoes each command as it is issued, so a naive grep matches
  `/execute if … run …` whether or not the condition held. Always exclude `issued server command`
  when harvesting results.
* Console commands cannot use entity selectors (the console runs on the global region) and
  **repeating command blocks never fire** on this build. Anything selector-shaped needs a real
  player.

**Driving a headless client** (portablemc under `xvfb-run`, keystrokes via `xdotool`, screenshots via
`import`) has its own traps, all of which have cost a session at least once:

* Prove the keyboard reaches the game before believing anything — type a command whose feedback is
  logged, and continue only when it appears. Without that check, a run "passes" every phase with
  nothing typed at all.
* Escape with no menu open **opens** the pause menu, and every later keystroke goes to the menu.
* Anchor "is the client still connected" *after* the join line, or the previous client's
  `lost connection` reads as this one dying.
* `authme register <player>` from the console **kicks the player it just registered** — register
  before the client connects, then `authme forcelogin`.
* Vanilla op is not enough for `/say`, `/execute` or `/summon`: LuckPerms answers the
  `minecraft.command.*` permissions and has to be told.

---

## 12. Known limitations and open problems

Stated plainly, because a README that only lists successes is not useful.

| Problem | Status |
|---|---|
| **Create: Aeronautics can abort the JVM.** `/sable spawn joint_test` reaches Rapier's `buoyancy.rs`, whose panic is non-unwinding: the process dies with no crash report and the wrapper restarts it. | Upstream (native) bug in the mod. The Java-side sub-level errors that used to precede it are fixed; the remaining abort is inside Rust. Run that command last in a suite, or not at all. |
| **PlugManX does not load.** Paper's `MavenLibraryResolver` throws `NullPointerException` because its repository system is not wired in this build, so any plugin whose Paper plugin-loader downloads Maven libraries at load time is skipped. | Open. A real class of failure, not a single plugin — the next plugin that uses a library loader will hit it too. |
| **ImageFrame** fails to enable (`ExceptionInInitializerError`), **KartaAutoAnnouncer** fails to enable (missing embedded `config.yml`). | Third-party bugs, reproducible off Eturlia. |
| **Cyan trails behind players** reported as a visual artefact. | Not the server. `-Deturlia.debug.particles=true` proved the core sends **zero** particles through a full run of walking; removing all 38 plugins and ~35 client mods changed nothing, and it reproduces in a brand-new world. It is drawn by the client half of a mod. |
| **Datagen classes cannot bind** — 15 final-overrides and 10 unimplemented interface methods, all in recipe/data generators (Create's Registrate, PuzzlesLib, Twilight Forest's recipe book). | Harmless: those classes only run in a development data-generation environment, never on a server. |
| **Mods that assume a single server thread.** | Inherent to the design. The region guard (`-Deturlia.region.guard=STRICT`) is how you find them. |

---

## 13. Repository layout

```
patches/server/            paperweight patches over Folia; everything from 0095 up is ours
patches/api/               patches over the Folia/Paper API
scripts/
  apply_compat_layer.py    the compatibility layer generator — the single source of truth
  selftest.sh              a quick check that needs no full classpath
  check-patches.py         structural validation of the patch tree
build-data/
  eturlia-core/            runtime code: eturlia.core.* and eturlia.launch.*
  eturlia-launcher/        the launcher and its library unpacking
  eturlia-server-templates/  eturlia.EturliaServer, the entry point
  eturlia-neoforge-shims/  vanilla-side shims NeoForge expects to exist
tools/                     the build loop, log grading, static scan, gameplay sweeps
docs/                      FIXES.md, HANDOFF.md, TESTING.md, ARCHITECTURE.html, the config template
docs/archive/              superseded documents, kept for the record
```

---

## 14. Adding a fix

The compatibility layer is not a pile of ad-hoc edits — it is an ordered list of **planes** in
`scripts/apply_compat_layer.py`, each one function:

```python
def install_my_plane():
    """One line naming the class of failure this closes."""
    print("my plane")
    replace(
        SERVER + "/net/minecraft/.../Something.java",
        "<the text that is in the file right now, verbatim>",
        "<the replacement, marked // Eturlia start ... // Eturlia end>",
        "a short label for the log",
    )
```

`replace()` applies an edit exactly once and recognises one that is already applied, which is what
makes the script safe to re-run. The anchor must be copied out of the file verbatim; if it is not
found the script stops loudly instead of quietly skipping.

A new file placed under `Folia-Server/src/main/java` overrides the decompiled vanilla one — that is
how `LootContext`, `RecipeBookType`, `RecipeBookSettings`, `BuiltInPackSource` and `HangingEntity`
entered the tree, all of them classes NeoForge adds methods to that Folia's copy does not have.

Before adding a plane, read [`docs/FIXES.md`](docs/FIXES.md): the failure you are looking at is
often a case of one already solved.

---

## 15. Upstream, and the licence

This repository is a fork of [`eturnercus/Core`](https://github.com/eturnercus/Core). The history is
shared — everything up to `4e166a6` comes from there. It was created as a separate repository rather
than with the Fork button, so GitHub does not draw the connection itself; it is declared here, in the
repository description, and in [the upstream notice](https://github.com/eturnercus/Core/issues/31).
Upstream is itself a fork of [PaperMC/Folia](https://github.com/PaperMC/Folia) carrying
[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248 / FancyModLoader 4.0.43.

**The licence is deliberately restrictive.** Everything authored in this repository — the
compatibility layer, the launcher, the tooling, the documentation and any binary built from it — is
proprietary: no redistribution, no derivative works, no hosting for third parties, no commercial use,
no republication, without written permission. Read [`LICENSE`](LICENSE) before doing anything with
this code beyond looking at it.

Files inherited from upstream keep the licences they arrived under (`PATCHES-LICENSE`,
`folia-server/LICENCE.txt`, `folia-api/LICENCE.txt`); those terms govern those files and are what
makes distributing a build lawful at all. The proprietary terms cover this project's own work.

---
---

<p align="center">
  <img src="docs/assets/eturlia-logo.png" alt="Eturlia" width="120" height="120">
</p>

# Eturlia (по-русски)

Ядро сервера Minecraft **1.21.1**, в котором одновременно работают **модпак NeoForge** и **набор
плагинов Bukkit/Paper**, поверх **многопоточного (регионального) движка Folia**, причём **ни моды, ни
плагины не изменены**.

<sub>Folia 1.21.1 · NeoForge 21.1.248 · Java 21 · проверено на 87 jar модов и 38 jar плагинов</sub>

## Содержание

1. [Что это, в одном абзаце](#1-что-это-в-одном-абзаце)
2. [Если вы никогда не держали сервер Minecraft](#2-если-вы-никогда-не-держали-сервер-minecraft)
3. [Задача, ради которой всё это существует](#3-задача-ради-которой-всё-это-существует)
4. [Единственное правило проекта](#4-единственное-правило-проекта)
5. [Что происходит при запуске](#5-что-происходит-при-запуске)
6. [Текущее состояние](#6-текущее-состояние)
7. [Тестовый стенд — точный состав](#7-тестовый-стенд--точный-состав)
8. [Сборка](#8-сборка)
9. [Запуск](#9-запуск)
10. [Настройка](#10-настройка)
11. [Как это тестируется](#11-как-это-тестируется)
12. [Известные ограничения и открытые проблемы](#12-известные-ограничения-и-открытые-проблемы)
13. [Структура репозитория](#13-структура-репозитория)
14. [Как добавить исправление](#14-как-добавить-исправление)
15. [Апстрим и лицензия](#15-апстрим-и-лицензия)

---

## 1. Что это, в одном абзаце

Eturlia — это **ядро сервера**, то есть та программа, которая держит сервер Minecraft. Форк
[Folia](https://github.com/PaperMC/Folia) (многопоточного сервера от Paper) с встроенным в тот же jar
модлоадером [NeoForge](https://github.com/neoforged/NeoForge). Моды NeoForge кладутся в `mods/`,
плагины Bukkit/Paper — в `plugins/`, запускается один jar, и работают обе половины, при этом мир
тикается несколькими потоками сразу, а не одним. Ничего в `mods/` и `plugins/` не патчится, не
переупаковывается и не переименовывается: любое исправление, нужное чтобы мод или плагин заработал,
живёт **внутри ядра**.

Именно эта комбинация — модпак **плюс** плагины **плюс** настоящая многопоточность — и есть смысл
проекта. Любой другой вариант, доступный сегодня, даёт максимум два пункта из трёх.

---

## 2. Если вы никогда не держали сервер Minecraft

Раздел нужен, чтобы дальше было понятно человеку со стороны. Если вы знаете, что такое Folia,
переходите к [§3](#3-задача-ради-которой-всё-это-существует).

| Термин | Что это на самом деле |
|---|---|
| **Ядро сервера** | Сама серверная программа. У Mojang она одна (*ванильная*), все остальные запускают её модифицированные сборки, потому что ванильная медленная и не имеет точек расширения. |
| **Spigot / Paper** | Основные форки ванильного сервера. **Paper** — фактический стандарт: быстрее, тысячи исправлений, огромная настройка. |
| **Плагин** | Серверное дополнение на API Bukkit/Paper. Ставится **только на сервер**, игрок заходит обычным клиентом. Права, приваты, чат, модерация, экономика — это всё плагины. |
| **Мод (NeoForge)** | Модификация самой игры: новые блоки, предметы, мобы, измерения, механизмы. Нужен **и на сервере, и у каждого игрока**, и правит классы игры прямо в рантайме. NeoForge — загрузчик, который это обеспечивает на современных версиях. |
| **Модпак** | Собранный набор модов (здесь — 87 jar), который игроки ставят лаунчером. |
| **Гибридное ядро** | Ядро, которое грузит и моды, и плагины: Cauldron, Mohist, Arclight, Youer. Исторически хрупкие, и все **однопоточные**. |
| **Folia** | Региональный форк Paper. Режет мир на независимые квадратные **регионы** и тикает каждый своим потоком, так что 32-ядерная машина реально используется. Цена — исчезает предположение об одном потоке, под которое написаны все моды и почти все плагины. |
| **Регион** | Группа соседних загруженных чанков, принадлежащая ровно одному потоку. Регионы делятся и сливаются по мере движения игроков. Обращение к чанку *чужого* региона со своего потока — ошибка, которую Folia ловит и запрещает. |
| **Тик** | Один шаг игрового цикла, 20 раз в секунду. В Folia у каждого региона свой тик на своём потоке. |

**Почему «моды и плагины вместе» — сложно:** мод считает, что он один на единственном серверном
потоке, и лезет в любую часть мира в любой момент. Плагин рассчитывает на Bukkit API и на Folia
обязан быть помечен региональным, иначе его не загрузят. Folia рассчитывает на ванильные структуры
данных, которые NeoForge переписывает. Каждая из трёх частей спроектирована без оглядки на две
другие.

---

## 3. Задача, ради которой всё это существует

Конкретная ситуация, из которой всё выросло: живой сервер (**NoteBuns**) с паком из ~90 модов и
полным набором плагинов для модерации, прав, чата и приватов. Сегодня он работает на однопоточном
гибридном ядре (семейство Youer/Arclight). Одно ядро процессора загружено полностью, остальные 31
простаивают; один тяжёлый чанк лагает всю карту.

Что доступно и чего это стоит:

| Вариант | Моды | Плагины | Многопоточность | Итог |
|---|:--:|:--:|:--:|---|
| Paper | ✗ | ✓ | ✗ (один главный поток) | модпака нет вообще |
| Folia | ✗ | ✓ (только региональные) | ✓ | модпака нет вообще |
| Arclight / Mohist / Youer | ✓ | ✓ | ✗ | то, что работает сейчас, и причина лагов |
| **Eturlia** | ✓ | ✓ | ✓ | этот репозиторий |

Что именно ломается, если просто поставить NeoForge на Folia — это реальные поломки из логов
проекта, а не гипотезы:

* **Мод наследует то, что Paper удалил.** Переписанная система чанков (Moonrise) и движок света
  (Starlight) *убирают* поля и методы, которые были в ванилле. Мод, собранный под ваниллу, всё ещё их
  зовёт. При компиляции ничего не предупреждает — вылетает `NoSuchFieldError` / `NoSuchMethodError`
  при первом же исполнении. А на Folia упавший тик региона кладёт весь сервер.
* **Мод строит собственный мир.** Мир контрапций Create, его мир схематик и физические под-миры Sable
  создают `Level`, который не является `ServerLevel`. Конструктор Folia на любой такой уровень
  бросал исключение — раз в тик. Как это выглядит для игрока: механизм Create собирается и не
  работает, и его нельзя разобрать.
* **Плагин не загружается из-за отсутствия `folia-supported`.** Folia жёстко отсекает плагины. Этот
  флаг не ставит почти никто, включая WorldGuard, EssentialsX и LuckPerms.
* **Folia удаляет ванильные команды.** `/scoreboard`, `/team`, `/data`, `/clone`, `/datapack` и ещё
  десяток закомментированы в апстриме как не региональные. На них рассчитывают датапаки, картостроители
  и половина плагинов.
* **Нативная библиотека умирает молча.** Физика Create Aeronautics — это Rust (Rapier) через JNI.
  Java-исключение посреди конструирования оставляет её недостроенной; следующий нативный вызов
  паникует в функции, которая не умеет разворачивать стек, и **убивает JVM** — без crash-отчёта,
  обёртка просто перезапускает сервер.

---

## 4. Единственное правило проекта

> **Несовместимость поглощает ядро. Моды и плагины не трогаем.**

Очевидная альтернатива — патчить каждый мод под регионы — заканчивается на первом же обновлении
пака. Каждый jar в `mods/` и `plugins/` здесь побайтово такой, каким его выложил автор.

Поэтому любое изменение проходит один критерий:

> *Если завтра другой мод получит ту же ошибку — он заработает без нового патча?*

Если нет — это не исправление, а костыль под конкретный мод, и в ядро он не идёт. На практике это
значит: реализовать **весь** интерфейс апстрима, а не тот единственный метод, который упал; вернуть
**член класса**, который убрал Paper, а не обходить его у вызывающего.

Каждая плоскость слоя совместимости переключается, и `strict` всегда возвращает штатное поведение
Folia — так и выясняется, чьё поведение вы сейчас наблюдаете.

---

## 5. Что происходит при запуске

```
eturlia-1.21.1-neoforge-21.1.248.jar
        │
        ├─ 1. лаунчер распаковывает встроенные библиотеки в eturlia-libraries/
        │
        ├─ 2. передаёт управление ModLauncher / FancyModLoader (NeoForge 21.1.248)
        │       · сканируется mods/; несовместимые jar переименовываются в *.jar.eturlia-skipped
        │       · применяются миксины; упавший инжектор выбрасывается по имени, а не роняет запуск
        │
        ├─ 3. стартует сервер Folia: регионы, система чанков (Moonrise), Starlight
        │       · поднимаются потоки region-tick-threads
        │
        ├─ 4. стартует слой CraftBukkit: грузится plugins/
        │       · снимается проверка folia-supported; старый BukkitScheduler крутится на глобальном тике
        │       · модовая сущность видится плагинам как UNKNOWN, модовый блок — как STONE
        │
        └─ 5. загружаются миры, регионы начинают тикать параллельно
```

Слой совместимости — не рантайм-агент, а **исходники**: он генерируется в дерево Folia до компиляции
скриптом `scripts/apply_compat_layer.py`, поэтому наружу уходит обычный серверный jar.

---

## 6. Текущее состояние

Замеры на тестовом стенде **13.08.2026**, сборка `2026-08-13T11:46:07Z`:

| | |
|---|---|
| Старт | `Done (12.673s)` |
| Моды | **115 модов** из **87 jar** (с учётом вложенных jar-in-jar) |
| Плагины | **37** из **38 jar** |
| Тревожных строк за полную загрузку | **2**, обе — баги сторонних плагинов (ImageFrame, KartaAutoAnnouncer) |
| Классов модов, не стыкующихся с ядром | **0** вне датагена — 15 переопределений `final` и 10 нереализованных методов интерфейсов, все в генераторах рецептов и данных, которые на сервере не исполняются (`tools/finalscan.py`, 87 jar, 36 196 классов модов против 8 674 классов ядра) |
| Команд плагинов зарегистрировано | **224 из 225** объявленных |
| Ванильных команд на месте | **48 из 48**, существующих в 1.21.1 |
| Механика Create | подшипник, приводимый цепочкой валов, собирает контрапцию и поднимает блоки; 10 циклов сборки/разборки без ошибок |
| Create: Aeronautics | контрапции собираются на границе мира; физические под-миры создаются и тикают |
| Регионы | тикают параллельно, по потоку на регион |

Разбор каждого исправления по классам поломок — [`docs/FIXES.md`](docs/FIXES.md). Живой документ
состояния (что открыто, что уже исключено) — [`docs/HANDOFF.md`](docs/HANDOFF.md).

> [!WARNING]
> Ядро экспериментальное. Держите бэкапы миров. Часть модов рассчитывает на один серверный поток и
> на регионах ведёт себя так, как их авторы никогда не проверяли.

---

## 7. Тестовый стенд — точный состав

Все цифры в этом файле сняты на одной машине с одним паком. И то, и другое приведено полностью,
чтобы фраза «работает» была проверяемой.

**Железо и JVM**

| | |
|---|---|
| Машина | 32 логических ядра, 124 ГБ RAM (делится с посторонним продакшен-сервером) |
| Java | Temurin 21.0.12+8 |
| Куча | `-Xms8G -Xmx8G`, ZGC generational |
| Потоки | `region-tick-threads: 16`, `chunk-worker-threads: 8`, `chunk-io-threads: 3` |
| Флаги совместимости | `mixins=soft`, `plugins=true`, `plugin-remap=true`, `registries=lenient`, `modloading=lenient`, `folia-stubs=lenient`, `bukkit-types=lenient` |

Пак **не** подобран под тест — это продакшен-пак живого сервера, скопированный без изменений. Это
принципиально: тестовый пак ничего не доказывает про настоящий.

### 7.1 Моды — 87 jar в `mods/`, 1 в 1

Имена файлов приведены дословно; modId и отображаемое имя прочитаны из
`META-INF/neoforge.mods.toml` каждого jar.

**Семейство Create — ради него проект и принял нынешний вид**

| Jar | modId | Название |
|---|---|---|
| `create-1.21.1-6.0.10.jar` | `create` | Create |
| `create-aeronautics-bundled-1.21.1-1.3.0.jar` | `aeronautics_bundled` | Create Aeronautics |
| `sable-neoforge-1.21.1-2.0.3.jar` | `sable` | Sable (физические под-миры, Rust/Rapier через JNI) |
| `aeronauticscompat-1.1.3.jar` | `aeronauticscompat` | AeronauticsCompat |
| `copycats-3.0.4+mc.1.21.1-neoforge.jar` | `copycats` | Create: Copycats+ |
| `cable_facades-1.21.1-NeoForge-2.1.3.jar` | `cable_facades` | Cable Facades |
| `SSRD-1.8.5-1.21.1.jar` | `ssrd` | Separate Sable Render Distance |
| `sim_fluid_assembly_fix-1.0.0.jar` | `sim_fluid_assembly_fix` | Sim Fluid Assembly Fix |

**Контент — измерения, мобы, структуры, генерация мира**

| Jar | modId | Название |
|---|---|---|
| `twilightforest-1.21.1-4.8.3345-universal.jar` | `twilightforest` | The Twilight Forest |
| `tf_dnv-2.0.3.jar` | `tf_dnv` | Twilight Forest — Dungeons & Villages |
| `tfsaplingdimlock-1.0.0+1.21.1-neoforge.jar` | `tfsaplingdimlock` | TF Sapling Dim Lock |
| `alexsmobs-1.22.17.jar` | `alexsmobs` | Alex's Mobs |
| `malum-1.21.1-1.8.2.jar` | `malum` | Malum |
| `BetterEnd-21.0.25.jar` | `betterend` | Better End |
| `Terralith_1.21.1_v2.6.2_Neoforge.jar` | `lithostitched` | Terralith |
| `Incendium_1.21.x_v5.4.4.jar` | `incendium` | Incendium |
| `ibo-3.1.0-neoforge-1.21.jar` | — | Incendium Biomes Only |
| `dungeons+-1.10.1.jar` | — | Dungeons+ |
| `dungeons-and-taverns-v4.4.4.jar` | — | Dungeons and Taverns |
| `YungsBetterDungeons-1.21.1-NeoForge-5.1.4.jar` | `betterdungeons` | YUNG's Better Dungeons |
| `YungsBetterNetherFortresses-1.21.1-NeoForge-3.1.5.jar` | `betterfortresses` | YUNG's Better Nether Fortresses |
| `FarmersDelight-1.21.1-1.3.2.jar` | `farmersdelight` | Farmer's Delight |
| `letsdo-bakery-neoforge-2.1.6.jar` | `bakery` | [Let's Do] Bakery |
| `letsdo-brewery-neoforge-2.1.9.jar` | `brewery` | [Let's Do] Brewery |
| `letsdo-farm_and_charm-neoforge-1.1.22.jar` | `farm_and_charm` | [Let's Do] Farm & Charm |
| `letsdo-furniture-neoforge-1.1.4.jar` | `furniture` | [Let's Do] Furniture |
| `letsdo-vinery-neoforge-1.5.3.jar` | `vinery` | [Let's Do] Vinery |
| `supplementaries-neoforge-1.21.1-3.6.8.jar` | `supplementaries` | Supplementaries |
| `amendments-1.21-2.0.15-neoforge.jar` | `amendments` | Amendments |
| `beautify-neoforge-1.21.1-2.0.2.jar` | `beautify` | Beautify |
| `toomanypaintings-25.10.20-1.21-neoforge.jar` | `toomanypaintings` | Too Many Paintings |
| `travelersbackpack-neoforge-1.21.1-10.1.36.jar` | `travelersbackpack` | Traveler's Backpack |
| `horseman-neoforge-1.21.1-1.5.11.jar` | `horseman` | Horseman |
| `corpse-neoforge-1.21.1-1.1.17-fix4.jar` | `corpse` | Corpse |
| `easy_npc-neoforge-1.21.1-6.20.0.jar` | `easy_npc` | Easy NPC |
| `easy_npc_bundle-neoforge-1.21.1-6.20.0.jar` | `easy_npc_bundle` | Easy NPC: Bundle |
| `easy_npc_config_ui-neoforge-1.21.1-6.20.0.jar` | `easy_npc_config_ui` | Easy NPC: Config UI |
| `weaponmaster_ydm-1.21.1-neoforge-4.2.7.jar` | `weaponmaster_ydm` | YDM's Weapon Master |
| `starcatcher-2.3.15-NEOFORGE-1.21.1.jar` | `starcatcher` | Starcatcher |
| `immersive_melodies-neoforge-0.6.4+1.21.1.jar` | `immersive_melodies` | Immersive Melodies |
| `ArmorPoser-neoforge-1.21.1-6.2.3.jar` | `armorposer` | Armor Poser |
| `VisualWorkbench-v21.1.1-1.21.1-NeoForge.jar` | `visualworkbench` | Visual Workbench |
| `Almanac-1.21.1-2-neoforge-1.5.2.jar` | `almanac` | Almanac |

**Библиотеки — их грузят моды выше, и именно они чаще всего не стыкуются с ядром**

| Jar | modId | Название |
|---|---|---|
| `architectury-13.0.11-neoforge.jar` | `architectury` | Architectury |
| `kotlinforforge-5.12.0-all.jar` | — | Kotlin for Forge |
| `geckolib-neoforge-1.21.1-4.8.4.jar` | `geckolib` | GeckoLib 4 |
| `citadel-1.21.1-2.7.6.jar` | `citadel` | Citadel |
| `bookshelf-neoforge-1.21.1-21.1.81.jar` | `bookshelf` | Bookshelf |
| `curios-neoforge-9.5.1+1.21.1.jar` | `curios` | Curios API |
| `PuzzlesLib-v21.1.52-1.21.1-NeoForge.jar` | `puzzleslib` | Puzzles Lib |
| `moonlight-neoforge-1.21.1-3.0.17.jar` | `moonlight` | Moonlight Lib |
| `lodestone-1.21.1-1.8.2.jar` | `lodestone` | Lodestone |
| `resourcefullib-neoforge-1.21-3.0.12.jar` | `resourcefullib` | Resourceful Lib |
| `resourcefulconfig-neoforge-1.21-3.0.11.jar` | `resourcefulconfig` | Resourcefulconfig |
| `cloth-config-15.0.140-neoforge.jar` | `cloth_config` | Cloth Config v15 API |
| `yet_another_config_lib_v3-3.8.2+1.21.1-neoforge.jar` | `yet_another_config_lib_v3` | YetAnotherConfigLib |
| `CreativeCore_NEOFORGE_v2.13.41_mc1.21.1.jar` | `creativecore` | CreativeCore |
| `Iceberg-1.21.1-neoforge-1.3.2.jar` | `iceberg` | Iceberg |
| `konkrete_neoforge_1.9.9_MC_1.21.jar` | `konkrete` | Konkrete |
| `libjf-3.17.6+forge.jar` | `libjf` | LibJF |
| `mru-1.0.19+LTS+1.21.1+neoforge.jar` | — | M.R.U |
| `anvianslib-neoforge-1.21-1.4.2.jar` | `anvianslib` | Anvian's Lib |
| `prickle-neoforge-1.21.1-21.1.11.jar` | `prickle` | PrickleMC |
| `coroutil-neoforge-1.21.0-1.3.8.jar` | `coroutil` | CoroUtil |
| `YungsApi-1.21.1-NeoForge-5.1.6.jar` | `yungsapi` | YUNG's API |
| `lithostitched-1.7.10+beta4-neoforge-21.1.jar` | `lithostitched` | Lithostitched |
| `bclib-21.0.21.jar` | `bclib` | BCLib |
| `wunderlib-21.0.10.jar` | `wunderlib` | WunderLib |
| `worldweaver-21.0.21.jar` | `wover` | WorldWeaver |
| `badpackets-neo-0.8.2.jar` | `badpackets` | Bad Packets |
| `packetfixer-3.3.1-1.20.5-1.21.X-merged.jar` | `packetfixer` | PacketFixer |
| `respackopts-4.14.0+1.21.1.forge.4.jar` | `respackopts` | Resource Pack Options |

**Клиентские и удобства — лежат на сервере потому, что их везёт пак**

| Jar | modId | Название |
|---|---|---|
| `jei-1.21.1-neoforge-19.27.0.340.jar` | `jei` | Just Enough Items |
| `wthit-1.21.1-neo-12.10.2.jar` | `wthit` | wthit |
| `xaeroworldmap-neoforge-1.21.1-1.44.2.jar` | `xaeroworldmap` | Xaero's World Map |
| `voicechat-neoforge-1.21.1-2.6.18.jar` | `voicechat` | Simple Voice Chat |
| `emotecraft-for-MC1.21.1-2.4.12-neoforge.jar` | `emotecraft` | Emotecraft |
| `Emojiful-Neoforge-1.21-5.2.4-all.jar` | `emojiful` | Emojiful |
| `sound-physics-remastered-neoforge-1.21.1-1.5.1.jar` | `sound_physics_remastered` | Sound Physics Remastered |
| `clientsort-neoforge-2.2.2+1.21.1.jar` | `clientsort` | ClientSort |
| `polymorph-neoforge-1.1.0+1.21.1.jar` | `polymorph` | Polymorph |
| `fogoverrides-1.21.1-2.3.0.jar` | `fogoverrides` | Fog Overrides |
| `attributefix-neoforge-1.21.1-21.1.3.jar` | `attributefix` | AttributeFix |
| `letmedespawn-1.21.x-neoforge-1.5.0.jar` | `letmedespawn` | Let Me Despawn |
| `tt20-0.8.4+mc1.21.1-neoforge.jar` | `tt20` | TT20 |
| `chunkholdersafe-1.0.0+1.21.1-neoforge.jar` | `chunkholdersafe` | ChunkHolder Safe |
| `notebuns-farmcharm-fix-1.0.0.jar` | `notebuns_farmcharm_fix` | NoteBuns FarmCharm Fix |

**Отключаются автоматически** — ядро переименовывает их в `*.jar.eturlia-skipped` при старте и пишет
причину; файлы не удаляются:

| Jar | Причина, которую печатает ядро |
|---|---|
| `spark-1.10.124-neoforge.jar` | конфликтует с встроенным в Eturlia spark (JPMS); `/spark` остаётся доступным |
| `ferritecore-7.0.3-neoforge.jar` | подменяет таблицы блок-стейтов, которые Paper уже подменил |
| `arclight_sable_patch-1.1.1.jar` | рассчитан на Arclight; в Eturlia нужные мосты уже есть |

### 7.2 Плагины — 38 jar в `plugins/`, 1 в 1

| Jar | Имя плагина | Версия | Что нагружает |
|---|---|---|---|
| `AuthMe-5.7.0-FORK-Universal.jar` | AuthMe | 5.7.0-FORK-b53 | вход в игру, ранняя обработка пакетов |
| `LuckPerms-Bukkit-5.5.22.jar` | LuckPerms | 5.5.22 | права, включая `minecraft.command.*` |
| `worldedit-youer-7.3.8-1.jar` | WorldEdit | 7.3.8 | массовые правки блоков через границы регионов |
| `worldguard-bukkit-7.0.12-dist.jar` | WorldGuard | 7.0.12 | флаги регионов, перехват событий |
| `WG-GUI-1.10.1.jar` | WG-GUI | 1.10.1 | интерфейс к WorldGuard |
| `EssentialsX-2.22.1-dev+12-776f709.jar` | Essentials | 2.22.1-dev | самая большая поверхность команд в наборе |
| `EssentialsXChat-2.22.1-dev+12-776f709.jar` | EssentialsChat | 2.22.1-dev | конвейер чата |
| `EssentialsXSpawn-2.22.1-dev+12-776f709.jar` | EssentialsSpawn | 2.22.1-dev | телепорт между регионами |
| `CoreProtect-24.1.jar` | CoreProtect | 24.1 | асинхронное логирование блоков на регионах |
| `ProtocolLib.jar` | ProtocolLib | 5.4.0 | перехват пакетов |
| `PlaceholderAPI-2.12.3.jar` | PlaceholderAPI | 2.12.3 | межплагинный API |
| `VaultUnlocked-2.20.1.jar` | Vault | 2.20.1 | регистрация сервисов |
| `LiteBans.jar` | LiteBans | 2.18.7 | работа с БД вне тика |
| `LiteBansAddon.jar` | TrollEffects | 1.0 | собственный аддон |
| `PremiumVanish.jar` | PremiumVanish | 2.10.2 | видимость игроков |
| `SkinsRestorer.jar` | SkinsRestorer | 15.12.5 | подмена профиля и скина при входе |
| `TAB v6.1.0.jar` | TAB | 6.1.0 | пакеты табло и списка игроков |
| `DecentHolograms-2.10.1.jar` | DecentHolograms | 2.10.1 | спавн сущностей по игроку |
| `PlayerParticles-8.10-youer.jar` | PlayerParticles | 8.10-youer.12 | серверные частицы |
| `PPGuiFix-1.0.jar` | PPGuiFix | 1.0 | собственная правка к нему |
| `GSit-3.1.0.jar` | GSit | 3.1.0 | посадка на сущности |
| `Chunky-Bukkit-1.4.55.jar` | Chunky | 1.4.55 | массовая генерация чанков |
| `ChunkyBorder-Bukkit-1.2.33.jar` | ChunkyBorder | 1.2.33 | границы мира |
| `ChunkHeatMap-1.0.2.jar` | ChunkHeatMap | 1.0.2 | собственный профайлер загрузки чанков |
| `ChunkHeatMapAdmin-1.0.2.jar` | ChunkHeatMapAdmin | 1.0.2 | его админская половина |
| `InvSee++.jar` | InvSeePlusPlus | 0.30.15 | доступ к чужим инвентарям |
| `InventoryRollbackPlus-1.8.3.jar` | InventoryRollbackPlus | 1.8.3 | снимки инвентарей |
| `ImageFrame-2026.1.4.0.jar` | ImageFrame | 2026.1.4.0 | картины на картах — **не включается, баг стороннего плагина** |
| `KartaAutoAnnouncer-1.3.1.jar` | KartaAutoAnnouncer | 1.3.1 | объявления — **не включается, нет встроенного config.yml** |
| `PlugManX-3.0.2.jar` | PlugManX | 3.0.2 | **не грузится** — см. [§12](#12-известные-ограничения-и-открытые-проблемы) |
| `KotlinMC-2.2.20.jar` | Kotlin | 2.2.20 | рантайм Kotlin для плагинов |
| `ConsoleSpamFixReborn-1.11.8.jar` | ConsoleSpamFixReborn | 1.11.8 | фильтрация лога |
| `DemonicEye-1.0.0.jar` | DemonicEye | 1.0.0 | собственный |
| `DiscordChatBridge-1.0.0.jar` | DiscordChatBridge | 1.0.0 | собственный, исходящая сеть |
| `FarCoordGuard-1.0.0.jar` | FarCoordGuard | 1.0.0 | собственная защита от дальних координат |
| `ModClaimGuard-1.0.0.jar` | ModClaimGuard | 1.0.0 | собственный, приваты для модовых блоков |
| `NoteBunsChatFix.jar` | NoteBunsChatFix | 1.0 | собственный |
| `StreamAnnounce-1.0.0.jar` | StreamAnnounce | 1.0.0 | собственный |

Ни один из них не объявляет `folia-supported`. Они грузятся потому, что ядро снимает эту проверку и
даёт старому планировщику где работать.

---

## 8. Сборка

Нужны: **JDK 21**, Python 3, git и примерно 8 ГБ свободной памяти под демон Gradle.

```bash
./gradlew applyPatches
python3 scripts/apply_compat_layer.py
./gradlew :folia-server:eturliaStandaloneJar
```

Порядок обязателен:

1. `applyPatches` разворачивает из `patches/` декомпилированное дерево исходников
   Minecraft/Paper/Folia. До этого править нечего.
2. `apply_compat_layer.py` пишет слой совместимости **в это дерево**. Скрипт идемпотентный: второй
   запуск печатает `already applied` и ничего не меняет.
3. Gradle-задача компилирует всё и выдаёт
   `core/build/libs/eturlia-1.21.1-neoforge-21.1.248.jar`.

Если якорь, которого ждёт генератор, не найден, скрипт останавливается с `!! anchor missing`, и
сборка не продолжается. Так и задумано: молча пропущенная плоскость хуже упавшей сборки.

Весь цикл — патчи, генерация, сборка, объявление рестарта в чат, остановка, деплой, старт, оценка
загрузки — одной командой:

```bash
tools/cycle.sh
```

Печатает по строке на шаг, а при падении — хвост лога именно этого шага. CI
(`.github/workflows/eturlia-ci.yml`) выполняет тот же порядок, поэтому изменение, которого нет в
`apply_compat_layer.py`, для проекта не существует.

---

## 9. Запуск

```bash
java -Xms8G -Xmx8G -XX:+UseZGC -XX:+ZGenerational \
     --add-modules=jdk.incubator.vector \
     -jar eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

Jar — это лаунчер: он распаковывает встроенные библиотеки в `eturlia-libraries/` рядом с собой и
поднимает серверную JVM. Дальше это обычный каталог сервера: `mods/`, `plugins/`,
`server.properties`, `config/paper-global.yml`, `spigot.yml`, `bukkit.yml` — всё там, где ожидается.

Игроки заходят **тем же клиентским модпаком, что и на любой сервер NeoForge**. Со стороны клиента
Eturlia никак не видна.

---

## 10. Настройка

### 10.1 Переключатели совместимости (флаги JVM)

У каждой плоскости слоя есть флаг. `strict` (или `false`) всегда означает *вернуть штатное поведение
Folia* — самый быстрый способ понять, чей это симптом, наш или апстрима.

| Флаг | По умолчанию | Что делает |
|---|---|---|
| `-Deturlia.compat.mixins` | `soft` | упавший инжектор миксина мода перестаёт быть фатальным: сломанный миксин выбрасывается по имени, класс трансформируется заново из чистой копии |
| `-Deturlia.compat.modloading` | `lenient` | ошибки в списке проблем загрузки не останавливают старт; шина событий каждого мода оборачивается |
| `-Deturlia.compat.registries` | `lenient` | замороженные реестры открываются для поздней регистрации |
| `-Deturlia.compat.plugins` | `true` | снимает проверку `folia-supported`; старый `BukkitScheduler` крутится на глобальном тике |
| `-Deturlia.compat.folia-stubs` | `lenient` | `getTickCount()` отвечает тиком глобального региона; `execute`/`tell`/`executeBlocking` планируют задачу, а не бросают исключение |
| `-Deturlia.compat.bukkit-types` | `lenient` | модовая сущность читается плагинами как `UNKNOWN`, модовый блок — как `Material.STONE`, вместо исключения |
| `-Deturlia.compat.folia-commands` | `lenient` | возвращает 17 ванильных команд, закомментированных в Folia: `/scoreboard`, `/team`, `/tag`, `/data`, `/clone`, `/function`, `/loot`, `/ride`, `/schedule`, `/spreadplayers`, `/datapack`, `/bossbar`, `/item`, `/trigger`, `/spectate`, `/teammsg`, `/return` |
| `-Deturlia.compat.plugin-remap` | `true` | ремаппер Spigot→Mojang для плагинов; jar с классами, которые эта JVM загрузить не может, повторяется без них |
| `-Deturlia.compat.sublevel-chunks` | `lenient` | разрешает под-миру, созданному модом, загрузить чанк, которым **не владеет ни один регион**, с вызывающего потока; `strict` возвращает отказ Folia |
| `-Deturlia.compat.read-timeout` | `90` | секунды, которые Netty ждёт клиента, всё ещё строящего мир; `0` убирает обработчик совсем |
| `-Deturlia.compat.quarantine` | — | список modId через запятую, которые не грузить |
| `-Deturlia.lithostitched.allow-unsafe` | выкл | отвечает на проверку версии Lithostitched один раз вместо блока отказа каждую загрузку |
| `-Deturlia.debug.particles` | выкл | печатает каждый тип частиц, который шлёт **сервер**, не чаще раза в 10 с — способ доказать, что визуальный эффект серверный или нет |
| `-Deturlia.region.guard` | `WARN` | `STRICT` отклоняет межрегиональные вызовы, `WARN` логирует, `OFF` выключает охрану |

### 10.2 `config/eturlia.yml`

Создаётся при первом запуске из шаблона и подробно прокомментирован (по-русски). Это единственное
место, где собраны настройки, иначе размазанные по `paper-global.yml`, `server.properties`,
`spigot.yml` и `bukkit.yml`, и объяснено, что каждая из них делает на регионах. Шаблон лежит в
репозитории: [`docs/eturlia.yml.example`](docs/eturlia.yml.example).

Главная секция:

```yaml
threads:
  region-tick-threads: 16     # тикеры регионов Folia; -1 = авто
  region-grid-exponent: 2     # ячейка региона = 2^n чанков по стороне; 2 → 4×4
  chunk-worker-threads: 8     # Moonrise: генерация, свет, тяжёлые задачи по чанкам
  chunk-io-threads: 3         # чтение и запись region-файлов
```

Пулы потоков создаются один раз, при чтении `paper-global.yml`. Их изменение требует полного
рестарта, а не релоада.

---

## 11. Как это тестируется

Юнит-теста на «переживёт ли пак из 90 модов регионы» не существует. Инструменты построены вокруг
запуска настоящего сервера и вычитывания ответа из живой игры.

| Инструмент | На какой вопрос отвечает |
|---|---|
| `tools/cycle.sh` | один оборот цикла: патчи → генерация → сборка → деплой → рестарт → оценка |
| `tools/logcheck.py` | оценивает загрузку: группирует все WARN/ERROR, прячет группы, уже признанные безобидными (у каждой записана причина), печатает только **новое** с прошлого запуска |
| `tools/logsweep.py` | все логи, все уровни, сгруппированные по нормализованной форме; `--grep "текст"` разворачивает одну форму |
| `tools/finalscan.py` | **не запуская сервер**: читает собранное ядро против каждого jar модов (включая вложенные jar-in-jar) и показывает два способа не состыковаться — переопределение метода, который Paper пометил `final` (`IncompatibleClassChangeError` при загрузке класса), и реализацию интерфейса, в который CraftBukkit с тех пор добавил абстрактный метод (`AbstractMethodError` при первом вызове) |
| `tools/modsweep.py` | прогон из консоли: все команды всех плагинов, `/summon` для выборки модовых сущностей, `/setblock` с обратным чтением для модовых блоков, `/place feature` для модовой генерации и партия модовых блок-энтити, оставленных тикать под наблюдением |
| `tools/cmdtree.py` | восстанавливает дерево команд мода по тому, что Brigadier подчёркивает как неизвестное |
| `tools/aerotest.sh`, `tools/aerostress.sh` | Create и Create: Aeronautics под управлением настоящего безголового клиента |
| `tools/trailcheck.sh` | освещённая пустая площадка со скриншотами спереди/сзади/от третьего лица — цикл для охоты за визуальным багом |

**Как вычитать ответ из игры.** Три очевидных канала на этой сборке не работают, и каждый обошёлся
дорого:

* `/say` бесполезен как маркер — лог сохраняет ключ перевода (`chat.type.announcement`) и теряет
  текст, и из консоли, и от игрока.
* **Обратная связь команды, поданной игроком, пишется в лог целиком**: `[EturliaTester: Changed the
  block at x, y, z]`. Это и есть механизм маркеров — дать каждой проверке свои координаты и потом
  прочитать координаты обратно.
* Сервер эхом пишет и саму поданную команду, поэтому наивный grep поймает `/execute if … run …`
  независимо от того, выполнилось условие или нет. При сборе результатов всегда исключайте
  `issued server command`.
* Из консоли нельзя пользоваться селекторами сущностей (консоль работает на глобальном регионе), а
  **повторяющиеся командные блоки на этой сборке не срабатывают**. Всё, что похоже на селектор,
  требует живого игрока.

**Управление безголовым клиентом** (portablemc под `xvfb-run`, клавиатура через `xdotool`, скриншоты
через `import`) имеет свои ловушки, и каждая уже стоила как минимум одной сессии:

* Сначала докажите, что клавиатура доходит до игры — введите команду, чья обратная связь пишется в
  лог, и продолжайте только когда она появилась. Без этой проверки прогон «проходит» все фазы, не
  введя ни одного символа.
* Escape при закрытом меню **открывает** меню паузы, и все следующие нажатия уходят в меню.
* Якорь «клиент ещё подключён» ставьте *после* строки входа, иначе `lost connection` предыдущего
  клиента читается как смерть текущего.
* `authme register <игрок>` из консоли **кикает того, кого только что зарегистрировал** — регистрируйте
  до подключения клиента, потом `authme forcelogin`.
* Ванильного op недостаточно для `/say`, `/execute` и `/summon`: за права `minecraft.command.*`
  отвечает LuckPerms, и ему надо об этом сказать.

---

## 12. Известные ограничения и открытые проблемы

Названы прямо, потому что README, в котором одни успехи, бесполезен.

| Проблема | Состояние |
|---|---|
| **Create: Aeronautics умеет убить JVM.** `/sable spawn joint_test` доходит до `buoyancy.rs` в Rapier, чья паника не разворачивает стек: процесс умирает без crash-отчёта, обёртка перезапускает его. | Баг апстрима (нативная часть мода). Java-ошибки под-миров, которые раньше этому предшествовали, исправлены; оставшийся обрыв — внутри Rust. Ставьте эту команду последней в наборе или не используйте вовсе. |
| **PlugManX не грузится.** `MavenLibraryResolver` из Paper бросает `NullPointerException`, потому что его repository system в этой сборке не собран, и любой плагин, чей paper-загрузчик тянет Maven-библиотеки при загрузке, пропускается. | Открыто. Это класс поломки, а не один плагин — следующий плагин с library loader упрётся туда же. |
| **ImageFrame** не включается (`ExceptionInInitializerError`), **KartaAutoAnnouncer** не включается (нет встроенного `config.yml`). | Баги сторонних плагинов, воспроизводятся и вне Eturlia. |
| **Синие следы за игроком**, о которых сообщали как о визуальном артефакте. | Это не сервер. `-Deturlia.debug.particles=true` доказал, что ядро за весь прогон ходьбы отправило **ноль** частиц; снятие всех 38 плагинов и ~35 клиентских модов ничего не изменило, и эффект воспроизводится в новом мире. Его рисует клиентская половина мода. |
| **Классы датагена не стыкуются** — 15 переопределений `final` и 10 нереализованных методов интерфейсов, все в генераторах рецептов и данных (Registrate из Create, PuzzlesLib, книга рецептов Twilight Forest). | Безвредно: эти классы исполняются только в средах генерации данных при разработке, на сервере — никогда. |
| **Моды, рассчитывающие на один серверный поток.** | Свойство самой затеи. Ищутся охраной регионов: `-Deturlia.region.guard=STRICT`. |

---

## 13. Структура репозитория

```
patches/server/            патчи paperweight поверх Folia; всё с 0095 и выше — наше
patches/api/               патчи поверх API Folia/Paper
scripts/
  apply_compat_layer.py    генератор слоя совместимости — единственный источник правды
  selftest.sh              быстрая проверка без полного classpath
  check-patches.py         структурная валидация дерева патчей
build-data/
  eturlia-core/            рантайм: eturlia.core.* и eturlia.launch.*
  eturlia-launcher/        лаунчер и распаковка библиотек
  eturlia-server-templates/  eturlia.EturliaServer, точка входа
  eturlia-neoforge-shims/  ванильные заглушки, существования которых ждёт NeoForge
tools/                     цикл сборки, оценка логов, статический скан, игровые прогоны
docs/                      FIXES.md, HANDOFF.md, TESTING.md, ARCHITECTURE.html, шаблон конфига
docs/archive/              устаревшие документы, оставленные для истории
```

---

## 14. Как добавить исправление

Слой совместимости — не набор случайных правок, а упорядоченный список **плоскостей** в
`scripts/apply_compat_layer.py`, каждая из которых одна функция:

```python
def install_my_plane():
    """Одна строка о том, какой класс поломки это закрывает."""
    print("my plane")
    replace(
        SERVER + "/net/minecraft/.../Something.java",
        "<текст, который сейчас есть в файле, дословно>",
        "<замена, помеченная // Eturlia start ... // Eturlia end>",
        "короткая метка для лога",
    )
```

`replace()` применяет правку ровно один раз и узнаёт уже применённую — на этом и держится
повторный запуск. Якорь надо копировать из файла дословно; если он не найден, скрипт громко
останавливается, а не пропускает правку молча.

Новый файл, положенный в `Folia-Server/src/main/java`, перекрывает декомпилированный ванильный —
так в дерево попали `LootContext`, `RecipeBookType`, `RecipeBookSettings`, `BuiltInPackSource` и
`HangingEntity`, то есть классы, в которые NeoForge добавляет методы, отсутствующие в копии Folia.

Прежде чем добавлять плоскость, прочитайте [`docs/FIXES.md`](docs/FIXES.md): та поломка, которую вы
видите, часто оказывается частным случаем уже решённой.

---

## 15. Апстрим и лицензия

Этот репозиторий — форк [`eturnercus/Core`](https://github.com/eturnercus/Core). История общая: всё
до `4e166a6` пришло оттуда. Репозиторий заведён отдельно, а не кнопкой Fork, поэтому GitHub не
рисует связь сам — она заявлена здесь, в описании репозитория и в
[заявке в апстрим](https://github.com/eturnercus/Core/issues/31). Сам апстрим — форк
[PaperMC/Folia](https://github.com/PaperMC/Folia) с загрузчиком
[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248 / FancyModLoader 4.0.43.

**Лицензия намеренно жёсткая.** Всё, что написано в этом репозитории — слой совместимости, лаунчер,
инструменты, документация и любые собранные из этого бинарники — проприетарно: без письменного
разрешения запрещены распространение, производные работы, хостинг для третьих лиц, коммерческое
использование и повторная публикация. Прочитайте [`LICENSE`](LICENSE) прежде, чем делать с этим кодом
что-либо кроме чтения.

Файлы, унаследованные от апстрима, сохраняют свои лицензии (`PATCHES-LICENSE`,
`folia-server/LICENCE.txt`, `folia-api/LICENCE.txt`); именно они определяют условия для этих файлов и
делают распространение сборки законным вообще. Проприетарные условия покрывают собственную работу
этого проекта.
