# Eturlia — what the core absorbs, and why

Eturlia is Folia (Paper's regionised multithreading) with NeoForge's FML bolted on, running the
**unmodified** NoteBuns production pack: 86 mods and 38 plugins, none of them patched.

That last word is the whole design. Patching each mod does not scale past one pack update, so every
fix below lives in the core and closes a *class* of failure rather than naming a mod. All of them
are produced by one idempotent generator, [`scripts/apply_compat_layer.py`](../scripts/apply_compat_layer.py),
run after `./gradlew applyPatches` and before the jar is built. Every plane has a switch, and
`strict` restores stock Folia behaviour.

## Where it stands

| | |
|---|---|
| Boot | `Done (10.1s)` — 86 mods, 38 plugins |
| Join | a player holds the world for **26 minutes** and counting, zero failed region ticks |
| Errors in a 10-minute session with a player online | **1** (DecentHolograms, a Spigot-mapped plugin — see "Still open") |
| Recipes lost at boot | **153 → 7** |

## The failure classes, and what closes each

### 1. Folia has no main thread, and mods keep asking for one

`MinecraftServer.execute`, `executeBlocking` and `tell` all throw `UnsupportedOperationException`
on Folia, because there is no single queue behind them. Mods do not know that. Supplementaries
builds a chunk packet and calls `tell(...)` to send block-entity capabilities alongside it — the
exception took the region tick with it, and **Folia answers a failed region tick by shutting the
whole server down**, one second after the player joined.

All three now share `MinecraftServer.eturlia$runAsMainThread`:

* **inside a region tick** the task runs inline — that thread already owns the data the task is
  about to touch, and it is the only choice that survives `ensureTickThread()`;
* **anywhere else** (netty, plugin pools, the loader, and the bootstrap "Server thread", which is
  a tick thread that owns no region) it goes on the global region's queue, where Folia itself puts
  configuration-phase packets.

Tasks are guarded: one that throws is logged, not fatal to its region.

`eturlia.compat.folia-stubs=lenient` · also gives `MinecraftServer.getTickCount()` the global
region's tick instead of throwing.

### 2. NeoForge patches vanilla classes; our Folia copy does not have those patches

NeoForge ships its own patched Minecraft. Every method it adds is missing here, and a mod that
calls one dies with `NoSuchMethodError` inside whatever tick was running.

| Missing | Who asked | Effect before |
|---|---|---|
| `LootContext.getQueriedLootTableId()` | Twilight Forest, on every block drop | region death |
| `Entity.getCapability` / `ItemStack.getCapability` | Curios, first tick after a join | region death |
| `Level.getCapability(BlockCapability, …)` | Supplementaries, per block entity per chunk sent | 270 errors per join |
| `Item implements IItemExtension` | many | load failure |

`LootContext` is a whole source file added to the tree — a new file under `src/main/java` shadows
the decompiled vanilla one, which is also how `RecipeBookType` and `RecipeBookSettings` got here.
`LootTable` names itself on the context before every roll, from `random_sequence` or from
CraftBukkit's key.

### 3. Modded recipes use NeoForge ingredient types

A modded recipe writes `{"type":"neoforge:difference", …}` where vanilla expects an item or a tag,
and the vanilla codec answers *"Not a json array"*. **153 recipes and 20 advancements were dropped
at every boot** for that one reason: chest boats, hoppers, shulker boxes, trapped chests, minecarts,
most of Twilight Forest's equipment.

`Ingredient.codec()` now delegates to `CraftingHelper.makeIngredientCodec()`, which accepts every
registered `IngredientType`. It needs `Value.MAP_CODEC`, `LIST_CODEC`, `LIST_CODEC_NONEMPTY`,
`fromValues`, `getValues`, `getCustomIngredient` and `isCustom` — the class now exposes all of them.
`MAP_CODEC_NONEMPTY` goes through `makeIngredientMapCodec()` so `SizedIngredient` and
`FluidIngredient` read custom types inline too.

One consequence had to be fixed with it: a custom ingredient can resolve to an empty stack, and
`ItemStack.LIST_STREAM_CODEC` answers *"Empty ItemStack not allowed"* — which killed the join with
`Failed to encode packet clientbound/minecraft:update_recipes`. `Ingredient.getItems()` now filters
empty stacks out before they reach the wire.

Remaining: 7 recipes, 6 of them one mod still writing the 1.20 result form (`item` where 1.21 wants
`id`), and 20 Supplementaries advancements that reference items from mods this pack does not have.

### 4. Bukkit enums cannot describe modded content

`org.bukkit.EntityType`, `Material` and `Sound` are fixed sets. A modded entity, item or sound has
no constant, and CraftBukkit's bridges threw — inside a region tick, which on Folia is a shutdown.

* `CraftEntityType.minecraftToBukkit` answers `UNKNOWN` instead of throwing.
* `EntityType.UNKNOWN` answers `getKey()` with `eturlia:unknown`. Listeners read the type of a
  spawning entity and call `getKey()` without checking; throwing there aborts the whole event
  dispatch, so every plugin registered after that one stops seeing spawns. **86 errors per five
  minutes** came from this alone.
* `CraftEntity.getEntity` wraps a modded entity as `CraftLivingEntity`, or as the new concrete
  `CraftEntity.EturliaUnknownEntity` when it is not living.
* `CraftSound.minecraftToBukkit` answers `null` for a modded sound. It is called while
  `EntityDeathEvent` is being built, so throwing lost the mob's entire death — drops included.
* `CraftMagicNumbers` no longer records `null` in its material maps. `Material.getMaterial()`
  returns null for a modded item; storing that null made `ITEM_MATERIAL.getOrDefault(item, AIR)`
  return the **stored null** — `getOrDefault` only substitutes for a missing key — so
  `CraftItemStack.getType()` handed plugins a null `Material`.

`eturlia.compat.bukkit-types=lenient`

### 5. A vanilla tag on a modded server holds modded entries

`CraftBlockTag.getValues()` mapped them through `minecraftToBukkit`, got `null`, and
`Collectors.toUnmodifiableSet()` rejected it. WorldGuard reads tags from a **static initialiser**,
so the failure killed `Materials` for the rest of the run — and the JVM discards the cause of an
exception thrown inside a nested class initialisation (`ExceptionInInitializerError: Exception
java.lang.NullPointerException [in thread "…"]`, no frames), which is why it took a while to find.

All four tag classes now drop entries with no Bukkit counterpart, and `CraftServer.getTag` names
the tags it cannot answer at all instead of returning a silent `null`.

### 6. Mixins that fail must not take the class with them

Mod mixin configs stop treating a failed injector as fatal; a still-failing mixin is identified by
name, dropped from its config, and the class is transformed again from a pristine snapshot — losing
one mixin instead of all of them. Worth not rediscovering:

* `Mixins.getConfigs()` is **empty** by the time classes are transformed; use
  `transformer.Config.allConfigs`.
* Mixin's classes sit in a non-exported package: reach fields with `Unsafe`, and load classes with
  **Mixin's own classloader** or you get a second copy with empty statics.
* ModLauncher calls `processClassWithFlags`, not `processClass`, and on 11.0.3 it returns an **int**
  of ASM writer flags — returning null NPEs inside the caller.
* A failed mixin leaves the `ClassNode` half-written and the class then fails verification.

`eturlia.compat.mixins=soft`

### 7. Registries, enums and the handshake

* Frozen registries reopen for late registration; orphaned intrusive holders are dropped
  (`eturlia.compat.registries=lenient`).
* `RecipeBookType` implements `IExtensibleEnum`, carries `@NetworkedEnum(CLIENTBOUND)` and a
  `getExtensionInfo()`. Deliberately **not** `@ReservedConstructor`: FarmersDelight adds
  `FARMERSDELIGHT_COOKING` through that very constructor.
* `RecipeBookSettings` defaults every read, so a player whose data mentions a modded category loads.
* CraftBukkit added a `ServerPlayer` parameter to `ServerConfigurationPacketListenerImpl`'s
  constructor, which stopped badpackets' injector from matching. An overload does not help — a
  mixin aimed at `<init>` applies to *every* constructor — so CraftBukkit's form became the static
  factory `eturlia$create`, leaving exactly one constructor with vanilla's signature.
* A mod's `VoxelShape` subclass finishes constructing before Paper's collision cache is built.
* 36 modded `EntityDataSerializer`s get wire ids from NeoForge's registry, mirroring its order.

### 8. Plugins on Folia

The folia-supported gate is off and the legacy `BukkitScheduler` is driven from the global tick
(`eturlia.compat.plugins=true`).

## Still open

| What | Where it stands |
|---|---|
| DecentHolograms, InvSee++ — `CraftPlayer.getHandle()` returning `EntityPlayer`, `org.bukkit.craftbukkit.v1_21_R1` | Spigot-mapped plugins. Paper's remapper is wired to mappings on disk but gated off: with `-Deturlia.compat.plugin-remap=true` the boot reaches "Remapping server…" and then the plugin system dies loading `io.papermc.paper.pluginremap.InsertManifestAttribute`, because `net.neoforged.art.api.Transformer` is not visible from the layer ModLauncher loads the server into. Details in `HANDOFF.md`. |
| `NeoForge handleServerStarted failed` | `TickRegionScheduler.getCurrentRegion()` is null on the Server thread, so mods' `ServerStartedEvent` handlers are skipped. |
| 7 recipes, 20 advancements | outdated mod JSON and references to absent mods; not core. |
| Paper's `LibraryLoader` under ModLauncher | plugins declaring `libraries:` cannot resolve Maven. |
| EssentialsX `ConcurrentModificationException` on the command map | a plugin iterating `knownCommands` while another thread registers; a concurrent backing map would close it. |

## Verifying a build

`tools/join_stable.sh <seconds>` is the whole loop: kill stale clients, start the headless client,
wait for the login line, log the tester in past AuthMe, put it in creative, hold, then print
`STABLE` / `DISCONNECT` / `REGION_DEATH` and a counted list of new server-side errors.

Two harness facts that cost real time:

* A **dead** tester sends nothing at all, so Netty's 30 s read timeout closes the connection and the
  server reports `lost connection: Timed out` — which reads exactly like a server fault and is not
  one. A dead player stays dead across rejoins. Hence creative.
* `pkill -f <pattern>` kills your own shell when the pattern appears in its command line.

---

## 2026-08-12 — the session that made mods actually work, not just load

The pack booted and players joined, but Create machinery did nothing, modded barrels crashed the
End, the Twilight Forest portal kicked in a loop, and half the plugins were dead. Every one of those
turned out to be a *class* of failure in the core. In order of how much they broke:

### Level had to stop being a sealed ServerLevel

Three separate assumptions in `Level` broke every mod that builds on it:

* **`markAndNotifyBlock` did not exist.** Paper renamed it to `notifyAndUpdatePhysics`; mods still
  call the vanilla name. Create calls it for every block it removes while assembling a contraption,
  so no bearing, airship or train ever assembled — the blocks stayed, the contraption entity was
  never spawned, and the block entity retried the same error forever. Added as a bridge.
* **19 methods were `final`.** Moonrise seals them so the JIT can inline them. Create's
  `SchematicLevel` and Ponder's `PonderLevel` override two of them, and a subclass that overrides a
  final method cannot even be *defined*: `IncompatibleClassChangeError` at class load, before a line
  of mod code runs. The seals are gone; the methods are untouched.
* **`worldRegionData` cast `this` to `ServerLevel` in a field initialiser**, which runs inside
  `Level`'s constructor. Create's `ContraptionWorld` is a `Level` that is not a `ServerLevel`, so
  every contraption died with a `ClassCastException` at the moment of assembly — right after its
  blocks had already been taken out of the world. A wrapper level now gets no region data (it has no
  regions), and `getCurrentWorldData()` lends it the region it is being used from.

`Level` also implements `ILevelExtension` now, which is what carries NeoForge's whole block
capability API — the way one modded block asks the block beside it for its inventory. `Player`,
`BlockEntity` and `ItemStack` got their extension interfaces the same way.

### Being outside a region tick is not an error

`Level.getCurrentWorldData()` returned `null` off a region thread, and every CraftBukkit-era field
lives on it (`capturedTileEntities`, `captureBlockStates`, `captureTreeGeneration`). A console
command, a plugin's async callback, or a mod's deferred "main thread" task therefore hit a null
pointer: **3064** of them in one afternoon, all from one block-info packet. Off-region callers now
get an empty scratch holder — nothing is captured outside a region tick, so "nothing" is the correct
answer.

Deferred tasks also stopped going to the global region by default. While a packet is being handled
the thread knows which player sent it, and that player's chunk names the region that owns the blocks
the task is about to touch. Queuing it there gives correct world data *and* one queue per player
instead of one for the whole server — the difference a player feels as a Create machine that runs
smoothly instead of stuttering.

### CraftBukkit methods that mods never heard of

* `Container` had seven abstract CraftBukkit methods. A modded chest implements the interface it
  found in the vanilla jar, loads fine, and throws `AbstractMethodError` the first time someone opens
  it — five End crashes in one afternoon from BCLib's barrels. They are `default` now.
* `Portal.portalAsync` was abstract, so every modded portal (Twilight Forest, BetterEnd, the Aether)
  killed the region the player was standing in, and the next login repeated it: the reported kick
  loop. The default implementation asks the mod where it wants the entity to go and hands the move to
  Folia's async teleport.
* `CraftEntity.getEntity` wrapped modded projectiles in a plain wrapper, and CraftBukkit's own event
  code casts those to `Projectile` unconditionally. Create's potato cannon took its region down on
  first impact. Modded projectiles now get a real projectile wrapper; block-attached entities without
  a Bukkit class skip the event instead of casting.
* `SpawnEggItem` dereferenced `defaultType`, which NeoForge's lazy spawn eggs leave null. The NPE
  fired on the creative-inventory packet, before the egg ever reached a hand — which is exactly what
  "modded spawn eggs do not work" looked like.

### The plugin remapper, and the plugins behind it

AutoRenamingTool refuses a whole jar over one class it cannot parse, and TAB and DecentHolograms
both ship adapters for future Minecraft versions compiled at a class-file version this JVM does not
know. Paper answered a failed remap by dropping the plugin. Now the core strips the classes this JVM
could never load and remaps again — both plugins load remapped, and DecentHolograms no longer throws
`CraftPlayer.getHandle()` on every join and quit.

Two more plugin-side classes closed with it: plugin bytecode that names the versioned
`org.bukkit.craftbukkit.v1_21_R1` package is rewritten to the unversioned one at load (InvSee++),
and Folia's schedulers clamp a delay of `0` instead of throwing, because Bukkit accepts what Folia
refused and the plugin died on enable.

### A modded block had no Material, and plugins never checked for null

Every protection and logging plugin reads `block.getType()` out of `BlockPlaceEvent` and
`BlockBreakEvent` and uses it immediately: CoreProtect calls `.name()` on it, WorldGuard hands it to
a `Preconditions.checkNotNull`. A modded block has no Bukkit `Material`, so the first one a player
touched took the handler down - and with it whatever protection that plugin was there to apply.
Modded blocks now report `Material.STONE`: a plain solid block, so region protection and logging
behave the way they would for any other block. `-Deturlia.compat.bukkit-types=strict` restores the
null. This closed the last errors a player could produce by playing: WorldGuard, CoreProtect and the
block-break packet all went quiet in the same run.

### Smaller, still ours

* `BuiltInPackSource.fromName` — the one method NeoForge patches into that class. Without it, a mod
  that ships its own datapack (Selling Bin, Starcatcher) lost all of it during startup.
* A failing vanilla command from the console reported `NullPointerException: command` and swallowed
  the real cause, because Paper's exception wrapper wants a Bukkit `Command` and a vanilla command
  has none.
* The "modded entity, plugins see it as UNKNOWN" notice said itself once per entity type; with
  ninety mods that is a console full of it. Five, then a summary.
* With `-Deturlia.lithostitched.allow-unsafe=true` set, the Lithostitched gate prints one warning
  line instead of a thirty-line refusal the operator has already answered.

### What the tests say now

`tools/logcheck.py` grades a boot: it groups every WARN/ERROR, hides the ones already judged benign
(with the reason next to each pattern) and prints what is left. Run it after every start.

| | |
|---|---|
| Boot | `Done (10.6s)` — 86 mods, **38 plugins enabled** |
| Create contraption | assembles and lifts its blocks (`tools/modtest3.sh`) |
| Modded blocks placed | Create shaft/cogwheel/casing/motor, Aeronautics propeller bearing, levitite |
| Summons | vanilla, Alex's Mobs, Twilight Forest, Aeronautics `gust`, `simulated:honey_glue` |
| AuthMe | register and login through the real client (`tools/clienttest2.sh`) |
| Alarming lines in a boot | 2, both third-party (see below) |

### Still open, and not ours

* **ImageFrame** fails on enable: its shaded ClassGraph scan finds no `PlatformProvider` under
  ModLauncher's module layer. Nothing in the core can make ClassGraph enumerate that layer.
* **KartaAutoAnnouncer** ships a jar with no `config.yml` inside it and calls `saveDefaultConfig()`.
  That is the plugin's own packaging bug.
* **Metaclay** is not in this pack at all — Aeronautics 1.3.0 registers `levitite` and
  `levitite_blend` instead. Nothing to fix; the item does not exist here.
* `server.properties` has `view-distance=40`. A test client teleported into fresh terrain at that
  distance stops sending packets long enough for the server to time it out. Lower it for testing.

---

## 2026-08-13 — the levels mods build for themselves

The pack was past "everything loads" and into "nothing a mod does actually works": Create machines
assembled and then sat there, an Aeronautics airship never became physical, and inside a contraption
nothing modded worked at all. Three findings, all one shape - the core assumed every Level, every
ChunkHolder and every implementor of a vanilla interface was one of its own.

### A Level that is not a ServerLevel

`Level`'s constructor demanded a set of CraftBukkit extras that only `MinecraftServer` ever sets,
and cast `this` to `ServerLevel` to build the Bukkit world. Anything else was refused outright:

```
IllegalStateException: Eturlia Level ctor extras missing; ServerLevel must set ETURLIA$LEVEL_CTOR_EXTRAS
  at BlockEntity tick, world:16,87,16   (a Create mechanical bearing)
```

Create's contraption world is a `Level`. So is its schematic world, and so is the plot sublevel that
Create Aeronautics puts a physics airship in. Every one of them threw inside its own constructor,
once per tick, forever - the bearing assembled, its blocks came out of the world, and then nothing
moved and nothing could be taken apart again. That single exception is most of "the machine does
nothing", "the airship never becomes physical" and "I cannot even remove it".

A guest level now gets the plain answers: no Bukkit world, no generator, and the main world's
configuration, which is what Paper reads off a level on nearly every call.

### Everything a mod cannot bind to, found without running the server

Two failures look identical from in-game - the mod silently does nothing, or the region that touched
it dies - and both are visible in the bytecode:

* a mod overrides a method Paper sealed with `final`: `IncompatibleClassChangeError` at class load,
  before a line of mod code runs
* a mod implements a vanilla interface that CraftBukkit has since added an abstract method to:
  `AbstractMethodError` the first time anything calls it

`tools/finalscan.py` reads the compiled core against all 87 mod jars (nested jarjar included) and
lists them. It found **22** sealed-method overrides and **259** unimplemented interface methods.
Everything that a dedicated server actually loads is now fixed:

| what | who needed it |
|---|---|
| `ChunkHolder` unsealed | sable's `PlotChunkHolder` - the Aeronautics sublevel, and the `simulated:assemble` packet that died with it |
| `Entity`, `LivingEntity` unsealed | sable's contraption-relative entities, Brewery's Beer Elemental |
| `HangingEntity` unsealed | Create's Blueprint, Aeronautics' Diagram |
| `DefaultDispenseItemBehavior` unsealed | Farmer's Delight cutting board |
| `Merchant.getCraftMerchant()` default | 132 modded NPCs |
| `Recipe.toBukkitRecipe()` default | 50 modded recipes, and every plugin that walks `Bukkit.recipeIterator()` |
| `BlockGetter.getBlockStateIfLoaded` / `getFluidIfLoaded` defaults | 26 wrapper levels (CreativeCore, citadel, sable) |
| `LevelAccessor.getMinecraftWorld()` default | 12 more of the same |
| `LevelReader.getChunkIfLoadedImmediately` default | sable's `EmbeddedPlotLevelAccessor` |

What the scan still reports is datagen and client-only (`RecipeProvider`, `TagsProvider`,
`DataProvider`, `RecipeOutput`, `PlaceRecipe`) - classes a dedicated server never loads.

A modded recipe has no Bukkit shape, so `toBukkitRecipe` describes what it can: a shapeless recipe
with the real result and no ingredients. If even the result cannot be read it returns null, and
`RecipeIterator` skips those instead of handing a plugin a null recipe.

### A block entity that is not there

`CraftBlockStates` threw `IllegalStateException: Tile is null, asynchronous access?` for a modded
block whose stand-in Material happens to be one with a block entity. Nothing asynchronous had
happened; there was simply no block entity. It took WorldGuard's and CoreProtect's handlers down 89
times in one minute of ordinary play. With no block entity there, the plain state is the answer.

### Not ours, and not broken

* **Damage and the void.** The log of the reported session has `eturnercus was slain by Spider` and
  `was blown up by Creeper` a minute after `/god` and `/ungod`. Damage works. Both reports are
  consistent with being inside a contraption sublevel, which did not tick at all until the Level
  constructor above was fixed.
* **The trail behind the player** is PPC_Wings' `flutter` effect, switched on for two UUIDs in
  `plugins/PPC_Wings/players.yml`. Both are off now. Nothing in the core draws it.
* **ProtocolLib** fails reflecting into `CraftParticle.minecraftToBukkit`; that is ProtocolLib
  against this Paper version, not the core.

### The thirteen commands that were never there

`tools/modsweep.py` checks every vanilla command the same way it checks a plugin's. It came back
with 36 of 49, and the missing ones were not obscure:

```
clone  data  datapack  function  loot  ride  schedule  scoreboard  spreadplayers  tag  team ...
```

They are all in `Commands.java`, and they are all commented out upstream with
`// Folia - region threading - TODO`. Folia turned off every vanilla command it had not made
region-safe, and nobody turned them back on — so an operator, a datapack and half the plugins on this
server were reaching for commands that did not exist.

A command a player types already runs on that player's region: the thread that owns the blocks and
entities the command is about to touch. The case Folia was guarding against is the console, and the
console already refuses entity selectors for exactly this reason. So seventeen of them are registered
again — `/scoreboard`, `/team`, `/tag`, `/data`, `/clone`, `/function`, `/loot`, `/ride`, `/schedule`,
`/spreadplayers`, `/datapack`, `/bossbar`, `/item`, `/trigger`, `/spectate`, `/teammsg`, `/return`.
`/reload`, `/save-all`, `/tick`, `/perf`, `/debug` and `/jfr` stay off: Bukkit already provides the
first two, and the rest are not per-region ideas at all.

`-Deturlia.compat.folia-commands=strict` puts Folia's silence back. `/clone` across regions from the
console still fails, loudly and without touching anything - Folia's own thread check catches it.

### What the sweep says about the pack

| | |
|---|---|
| Plugin commands registered | 224 of 225 declared (only PlugManX's `/plugman` is absent) |
| WorldEdit from the console | 4 of 4 |
| Vanilla commands | 48 of 48 that exist in 1.21.1 |
| Modded entities summoned | 17 of 20 sampled; the other 3 are lang keys with no entity type behind them |
| Modded blocks placed and read back | 16 of 20 sampled; 3 are optional content the mod only registers with another mod present, 1 is a bush that needs its own soil |
| Modded block entities left ticking | 40 placed, no exceptions |
| Modded worldgen features | placed where their conditions allow, `Failed to place feature` on a flat test platform otherwise |

---

## 2026-08-13 — what it takes to make a physics airship work on Folia

Create Aeronautics builds its ships in a *sub-level*: a level of its own, made at runtime by sable,
with its own chunks and its own chunk holders. Every assumption Paper's rewrites make about "a level
is the server's level" is wrong for it, and each wrong assumption killed the server outright - Folia
answers a failed region tick by shutting down.

They were found by driving sable's own commands (`/sable spawn joint_test`, `/sable assemble`,
`/sable storage find_all_sub_levels`) from a real client and reading what broke, one layer at a time.
Six layers, in the order they surfaced:

| what was missing | what happened |
|---|---|
| `LevelLightEngine.blockEngine`, `.skyEngine` | Moonrise replaced both with one `lightEngine`. `NoSuchFieldError` on the first sub-level. |
| `LevelChunkSection(PalettedContainer, PalettedContainerRO)` | CraftBukkit narrowed the second parameter, so vanilla's own constructor no longer existed. |
| `ChunkHolder.fullChunkFuture`, `.tickingChunkFuture`, `.entityTickingChunkFuture` | deleted by the chunk-system rewrite; sable's `PlotChunkHolder` inherits and reads them. |
| `ChunkAccess.getLevel()` | only `LevelChunk` declared it; a mod holding the supertype linked to nothing. |
| `ChunkMap`'s status listener | a mod's own chunk map passes null, and the first status change threw. |
| Folia's "Cannot asynchronously load chunks" | the sub-level's chunks belong to *no* region, so nothing could ever race - the guard refused anyway. |

The last two mattered far more than a normal NPE. Sable's physics is Rapier, a native library, and a
Java exception mid-construction leaves its side half-built. The next native call then panics - `No
rigid body for id` - **inside a function that cannot unwind**, and the JVM aborts. From the outside
that looks like the server quietly restarting; nothing appears in the crash reports, because the
process never got to write one. Every attempt at an airship did this.

With those six in place the Java side of `/sable spawn joint_test` is clean: the command runs, no
exception, no region death.

### Other things this round

* **The netty read timeout is configurable.** Thirty seconds of silence is not proof a client is
  gone - on a 90-mod pack it is one stall while the world builds. That number is hard-coded in
  vanilla and is *not* the keepalive, so raising `paper.playerconnection.keepalive` never helped.
  `-Deturlia.compat.read-timeout=<seconds>`, 90 by default. The headless test client had never once
  survived its first minute before this; now it stays for a full run.
* **Deferred tasks are timed.** Every mod task that cannot run inline goes through
  `eturlia$runAsMainThread`; it now records how long each waited on the player's region and on the
  global region, and says so once every 30 seconds when the worst wait passes 250ms.
  `-Deturlia.compat.dispatch-timing=off`.

### The blue trails

Not the core, and not PlayerParticles: the **DemonicEye** plugin draws particle "eyes" that follow
players (`follow-mode: ATTACHED`, `update-interval: 1`) using `SOUL_FIRE_FLAME` and `SOUL` - which
are exactly the cyan the screenshots show. Nothing in the compatibility layer spawns a particle
anywhere; the generator has no reference to one. The jar is in `plugins1/` now, its config left in
place. To keep the eyes but lose the cyan, `use-soul-flame: false` in `plugins/DemonicEye/config.yml`.
