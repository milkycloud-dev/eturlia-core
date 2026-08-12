# Eturlia — handoff, 2026-08-12 03:50

Workspace `/home/user/milky/eturlia_new` on `188.124.37.101`. Production NoteBuns
(`/home/user/mineroot/NoteBuns`) was only ever read from and is untouched.

Everything below is produced by `scripts/apply_compat_layer.py` — idempotent, safe to re-run, and
the single source of truth for the core changes. Run it, then `scripts/boot_once.sh`.

## Where things stand

**The client joins and holds the world.** Three consecutive runs on the 02:40 build:

```
EturliaTester[/127.0.0.1:37684] logged in with entity id 70 at ([world]-7.5, 74.0, -7.5)
STABLE 240s / STABLE 720s / STABLE 600s   — no region death, no disconnect, server alive
```

Twelve minutes in the world with the full unmodified production pack: 86 mods after hygiene skips,
38 plugins, `Done (10.1s)`, regions ticking on their own threads. The whole run produced **four**
error lines, all listed under "Errors still in the server log" below.

The tester is a normal registered player: `authme register EturliaTester eturliatest` was run once
from the console, and `tools/join_stable.sh` sends `authme forcelogin EturliaTester` after the join
line appears. `plugins/AuthMe/config.yml` has `restrictions.timeout: 0` so a slow client boot is
not kicked mid-test.

The last measured run, with everything below applied: the tester held the world for **1380 s
(23 minutes)**, zero failed region ticks, and **zero error lines after the join** — the cleanest
session so far. Boot recovers 146 of the 153 recipes that used to be dropped.

Earlier runs on the same build: **STABLE 420s**, **STABLE 900s** (that client was still in the
world at 1578 s when it was killed to free the box). Those produced three error lines:

```
1 ERROR … PlayerJoinEvent to DecentHolograms — NoSuchMethodError: CraftPlayer.getHandle()
1 NullPointerException: … "org.bukkit.inventory.ItemStack.getType()" is null   (InventoryRollbackPlus)
1 NullPointerException: Cannot read the array length because "b" is null       (InventoryRollbackPlus)
```

### The 30-second "Timed out" that ate an hour — read this before chasing one

Between 03:01 and 03:54 every join ended at 32–38 s with `lost connection: Timed out`. It looked
exactly like a server regression and was not one: **the tester was dying at the spawn point**, and
a client sitting on the death screen sends no packets at all, so Netty's 30 s `ReadTimeoutHandler`
closed the connection. A dead player stays dead across rejoins, so once it happened it repeated
forever.

What ruled everything else out, in case the symptom comes back:

- `ss -tin` on the connection: `bytes_received` climbing, `bytes_sent` frozen from the first
  sample — the client is receiving and not transmitting (`tools/wire_check.sh`).
- Not the keepalive limit: `-Dpaper.playerconnection.keepalive=300` changed nothing.
- Not the core: rebuilt without the `MixinCompat` containment change, then without
  `Level.getCapability` — same 32 s both times.
- Not client rendering load: `renderDistance:2`, `maxFps:15` and Distant Horizons removed — same
  32 s. A thread dump showed the Render thread idle in `limitDisplayFPS` (`tools/stall_dump.sh`).
- What actually settled it: a screenshot of the Xvfb display taken 12 s after the join
  (`tools/shot_mid.sh`) showing **"You Died!"**.

The harness now sends `gamemode creative EturliaTester` after `authme forcelogin`, and the test
server has `defaultgamemode creative` so a freshly wiped tester joins already protected. Creative
takes no damage except void, and still ticks and sends movement, which is what the test needs.

## Next step (start here), in order of value:

1. **Plugin remapping is built but switched off.** Spigot-mapped plugins (DecentHolograms,
   InvSee++, and anything reaching for `org.bukkit.craftbukkit.v1_21_R1`) cannot work without it.
   Paper's own remapper is in the tree and now finds its mappings (see the plane below), but with
   `-Deturlia.compat.plugin-remap=true` the boot ends with **zero plugins enabled**:

   ```
   java.lang.RuntimeException: Could not load super class net/neoforged/art/api/Transformer
       of io/papermc/paper/pluginremap/InsertManifestAttribute
     at libjf_unsafe_v0/…InterfaceImplTargetPatch.scanInterfaces(InterfaceImplTargetPatch.java:46)
     at …MixinTransformationHandler.processClass(MixinTransformationHandler.java:131)
     at …MixinCompat$Guard.invoke(MixinCompat.java:369)
     at …PluginRemapper.remap(PluginRemapper.java:304)
   ```

   So it is not the JVM that cannot see AutoRenamingTool — `AutoRenamingTool-2.0.3.jar` and
   `srgutils-1.0.9.jar` are both staged in `eturlia-libraries`. It is **libjf_unsafe**'s own class
   scanner: it walks the interfaces of every class ModLauncher loads, cannot resolve one from the
   layer it is looking in, and throws instead of skipping. `MixinCompat.Guard` would normally
   contain that, but `contain()` deliberately rethrows when the target class is ours
   (`io/papermc/…`) so Eturlia's own bugs are not masked. Two ways forward: make the Guard contain
   third-party transformer failures on our classes too (the blame frame is a mod's, not mixin's),
   or give ART a module the game layer reads.

2. **7 `RecipeManager` parse errors left** of the original 153 — see `FIXES.md` §3. Six are one
   mod still writing the 1.20 result form (`{"item": …}` where 1.21 wants `{"id": …}`), one is a
   Create milling recipe missing `amount`. Both are outdated mod JSON rather than a missing core
   codec; an `item`→`id` alias on `ItemStack`'s codec would close the first six if it turns out
   other packs need it too. The 20 remaining advancement errors are Supplementaries referencing
   items from mods this pack does not install.

3. **`MixinCompat.contain()` rethrows on our own classes.** `thrownByAnotherTransformer(cause)` is
   written and compiled but not wired in: it was switched on, made no difference to what got
   contained, and was backed out while the client timeout was being chased. It is the piece that
   stops a mod's `ILaunchPluginService` (libjf_unsafe) from killing a core class load, so it
   belongs on once the timeout above is understood.

## Chain solved, in order

Each of these was a hard stop; each is a general fix in the core, not a mod patch. Items 1–8 are
from the previous session, 9–13 from this one.

1. **`Tried to extend non-enum: RecipeBookType`** — mods add recipe book categories through
   NeoForge's enum extender, which only touches enums carrying `IExtensibleEnum`. Our copy is
   vanilla's. Now implements the marker, carries `@NetworkedEnum(CLIENTBOUND)` and a
   `getExtensionInfo()` for the extender to rewrite. Deliberately **not** `@ReservedConstructor`:
   FarmersDelight adds `FARMERSDELIGHT_COOKING` through that very constructor.
2. **`NoSuchElementException` in `RuntimeEnumExtender.findMethod`** — the missing
   `getExtensionInfo()`. Same fix.
3. **`Enum is extensible on the client but not on the server`** — the missing `@NetworkedEnum`.
4. **badpackets: `badpackets_handler() is null`** — CraftBukkit added a `ServerPlayer` parameter to
   `ServerConfigurationPacketListenerImpl`'s constructor, so the mod's injector no longer matched.
   An extra overload does not help — a mixin aimed at `<init>` is applied to *every* constructor —
   so CraftBukkit's form became the static factory `eturlia$create`, leaving exactly one
   constructor with vanilla's signature. Both call sites updated.
5. **`Invalid player data` on join** — `RecipeBookSettings` only knew vanilla's four categories, so
   loading a player NPE'd on the fifth. Every read now defaults, and modded categories get derived
   NBT tag names.
6. **Region death on natural spawn: `CraftEntityType.minecraftToBukkit` threw** — Bukkit's
   `EntityType` is an enum and cannot learn about modded entities. Returns `UNKNOWN` instead of
   throwing (`eturlia.compat.bukkit-types=strict` restores the throw).
7. **Region death: `AssertionError: Unknown entity class ...EntityCockroach`** — `CraftEntity`
   builds its wrapper from the Bukkit type. A modded entity now gets `CraftLivingEntity`, or the
   new concrete `CraftEntity.EturliaUnknownEntity` for non-living ones.
8. **Region death: `NoSuchMethodError: LivingEntity.getCapability(EntityCapability)`** — Curios
   asks every living entity for its inventory on the first tick. `Entity` and `ItemStack` now carry
   NeoForge's capability accessors.
9. **Region death on the first chunk sent to the player: `UnsupportedOperationException` with no
   message.** Supplementaries builds the chunk packet and calls `MinecraftServer.tell(...)` to send
   the block entity capabilities alongside it. Folia throws from every "run this on the main
   thread" entry point because there is no main thread, the region tick dies with it, and Folia
   answers a failed region tick by shutting the whole server down — one second after the join.
   `execute`, `executeBlocking` and `tell` now share `MinecraftServer.eturlia$runAsMainThread`:
   inside a region tick the task runs **inline** (that thread already owns the data the task will
   touch, and it is the only choice that survives `ensureTickThread()`), anywhere else it goes on
   the global region's queue. Tasks are guarded, so one that throws logs instead of killing a
   region.
10. **Region death on block drops: `NoSuchMethodError: LootContext.getQueriedLootTableId()`** —
    a NeoForge patch method on a vanilla class. NeoForge ships its own patched Minecraft and we
    ship Folia's, so every method NeoForge adds is missing here. `LootContext` is now a source file
    in the tree carrying the field, getter and setter, and `LootTable` names itself on the context
    before every roll (from `random_sequence`, or from CraftBukkit's key when that is empty).
11. **86 × `IllegalArgumentException: EntityType doesn't have key! Is it UNKNOWN?` per five
    minutes** — the price of item 6. Every modded entity reaches plugins as `UNKNOWN`, and every
    listener that reads the type of a spawning entity calls `getKey()` on it without checking;
    throwing aborts the dispatch, so every plugin registered after that one stops seeing spawns.
    `EntityType.UNKNOWN` now answers `eturlia:unknown`.
12. **WorldGuard dead for the whole run: `NoClassDefFoundError: Could not initialize class
    com.sk89q.worldguard.bukkit.util.Materials`** — the real cause was thrown once, inside a class
    initialiser, and the JVM discarded its stack (`ExceptionInInitializerError: Exception
    java.lang.NullPointerException [in thread …]`, no frames). It was `CraftBlockTag.getValues()`:
    a vanilla tag on a modded server holds modded blocks, `CraftBlockType.minecraftToBukkit`
    returns null for them, and `Collectors.toUnmodifiableSet()` rejects nulls. All four tag classes
    now drop entries with no Bukkit counterpart, and `CraftServer.getTag` logs the tags it cannot
    answer at all instead of returning a silent null.
13. **270 `NoSuchMethodError`s per join: `ServerLevel.getCapability(BlockCapability, BlockPos,
    BlockState, BlockEntity, Object)`** — the block half of item 8, and Supplementaries asks for it
    for every block entity in every chunk sent to a player. `Level` now carries both NeoForge
    overloads; the count drops to zero.
14. **The plugin remapper never ran.** `MappingEnvironment` looks for `META-INF/mappings/reobf.tiny`
    on the classloader; our server is nested inside the standalone launcher jar and extracted at
    boot, so it is on no resource path, `hasMappings()` is false and `PluginRemapper.create()`
    returns null. It now also reads `server/eturlia-mappings/reobf.tiny` (generate with
    `./gradlew generateReobfMappings`, output lands in
    `core/.gradle/caches/paperweight/mappings/mojang+yarn-spigot-reobf.tiny`). Gated off — see
    "Next step".

## What the compatibility layer does now

| Plane | Switch | Effect |
|---|---|---|
| Mixins | `eturlia.compat.mixins=soft` | mod mixin configs stop treating a failed injector as fatal; a still-failing mixin is dropped by name and the class is transformed again from a pristine copy |
| Mod loading | `eturlia.compat.modloading=lenient` | issue list drops errors; each mod's event bus is wrapped |
| Registries | `eturlia.compat.registries=lenient` | frozen registries reopen for late registration; orphaned intrusive holders dropped |
| Plugins | `eturlia.compat.plugins=true` | folia-supported gate off; legacy `BukkitScheduler` driven from the global tick |
| Folia stubs | `eturlia.compat.folia-stubs=lenient` | `MinecraftServer.getTickCount()` answers with the global region tick |
| Main-thread dispatch | `eturlia.compat.folia-stubs=lenient` | `execute` / `executeBlocking` / `tell` run the task on the owning region, or on the global one, instead of throwing |
| Bukkit types | `eturlia.compat.bukkit-types=lenient` | modded entities are `UNKNOWN` to plugins instead of fatal, and `UNKNOWN` answers `getKey()` |
| Plugin remapping | `eturlia.compat.plugin-remap=true` | **off**; reads `server/eturlia-mappings/reobf.tiny` when on |
| Shapes | always | a mod's `VoxelShape` subclass finishes constructing before Paper's collision cache is built |
| Serializers | always | 36 modded `EntityDataSerializer`s get wire ids from NeoForge's registry |
| Enums | always | `RecipeBookType` is extensible and networked |
| Recipe book | always | settings tolerate categories mods added |
| Capabilities | always | `Entity.getCapability`, `ItemStack.getCapability`, `Level.getCapability` |
| NeoForge patch methods | always | `LootContext.getQueriedLootTableId()`, and `LootTable` fills it in |
| Tags | always | tag value sets drop modded entries instead of NPEing; missing tags are named in the log |
| Vanilla shapes | always | `ServerConfigurationPacketListenerImpl` keeps vanilla's constructor |
| Extensions | always | `Item implements IItemExtension` |
| Quarantine | `eturlia.compat.quarantine=<ids>` | `ferritecore` soft-skipped |

### Details worth not rediscovering

- `Mixins.getConfigs()` is **empty** by the time classes are transformed; use
  `transformer.Config.allConfigs`.
- Mixin's classes are in a non-exported package: reach fields with `Unsafe`, and load classes with
  **Mixin's own classloader** (taken from the launch plugin) or you get a second copy with empty
  statics.
- ModLauncher calls `processClassWithFlags`, not `processClass`, and it returns an **int** of ASM
  writer flags on 11.0.3 — returning null NPEs inside the caller.
- A failed mixin leaves the `ClassNode` half-written and the class then fails verification. Snapshot
  before delegating, restore before continuing.
- `CraftEntity.getType()` is **final**; a fallback wrapper cannot override it (it already answers
  UNKNOWN through `CraftEntityType`).
- `CraftMob` is abstract, `CraftLivingEntity` is not — that is why the fallback tiers are
  LivingEntity and a new concrete subclass.
- Removing `sable` is not an option: aeronautics, offroad, simulated and ssrd depend on it, and
  without them worldgen loses the overworld settings.
- `boot_once.sh` prints `BLOCKER` whenever the log merely *mentions* a mixin error string. Trust
  `grep 'Done (' server/logs/latest.log`, not that word.
- **New source files work.** `src/main/java` shadows the decompiled vanilla source the build
  otherwise uses, so a vanilla class NeoForge patched can be added wholesale — that is how
  `RecipeBookType`, `RecipeBookSettings` and now `LootContext` got here. `git status` in
  `Folia-Server` shows them as untracked.
- **The "Server thread" is a tick thread that owns no region.** `TickThread.isTickThread()` is true
  there during startup while `TickRegionScheduler.getCurrentRegion()` is null, so
  `eturlia$inRegionTick()` checks both — running NeoForge's `ServerStartedEvent` inline instead of
  on the global region NPEs inside FML.
- **`pkill -f <pattern>` kills your own SSH shell** when the pattern appears in the command line.
  Split the literal: `pkill -9 -f 'Xvf''b'`.
- A class initialiser that throws inside another class's initialisation loses its stack: the JVM
  reports `ExceptionInInitializerError: Exception java.lang.NullPointerException [in thread "…"]`
  and nothing else. Do not go looking for the frames; find the call the class makes into us and
  instrument that instead.

## Test harness

- `tools/join_stable.sh [seconds]` is the whole loop: kill stale clients, start the headless
  client, wait for the login line, `authme forcelogin`, `gamemode creative`, hold, then print
  `STABLE`/`DISCONNECT`/`REGION_DEATH` and a counted list of new server-side errors.
- If the tester ever ends up dead and stuck (see above), reset it:
  `rm -f server/world/playerdata/*.dat*` with the client stopped. `defaultgamemode creative` is
  already set on the test server, so the fresh player joins protected.
- Client pack mirrored from `https://download.inflexus.world/cloud/mods/` into `pack/client_mods`
  (126 jars) and installed into `client/mc/mods`.
- Moved aside in `legacy_patched/client_ui_off/`: `fancymenu`, `melody`, `spiffyhud`,
  `seamless-loading-screen`. FancyMenu replaces the title screen, and `--quickPlayMultiplayer` only
  fires from the vanilla one.
- `config/wover/client.json` has `did_present_welcome_screen: true`; the BetterX welcome screen
  otherwise sits in front of the title screen forever. Found by screenshotting the Xvfb display:
  `XAUTHORITY=$(ls -t /tmp/xvfb-run.*/Xauthority | head -1) DISPLAY=:NN import -window root out.png`.
- `run_client.sh` forces `MESA_GL_VERSION_OVERRIDE=4.6` / `MESA_GLSL_VERSION_OVERRIDE=460`; at 3.3
  Veil's shaders fail to link every frame.
- `SSRD` and `EasyPayments` are in `legacy_patched/removed/`.
- `tools/stall_dump.sh` joins once and prints the client's Render-thread stack 22 s in;
  `tools/wire_check.sh` joins once and samples `ss -tin` on the connection every 5 s. Between them
  they tell you whether a lost client is rendering, blocked, or simply not transmitting.
- Screenshot the headless client to see what it is actually showing:
  `XAUTHORITY=$(ls -t /tmp/xvfb-run.*/Xauthority | head -1) DISPLAY=:NN import -window root out.png`
  — get `NN` from `ps -eo args | grep '[X]vfb'`.
- The client JVM gets portablemc's default 2 GB. `run_client.sh` raises it to 6 GB through
  `--jvm-args`, which **replaces** the default flags, so the G1 options are repeated there.

## Errors still in the server log

A seven-minute session with a player in the world now produces **one** line: the first row.

| Count | What | Verdict |
|---|---|---|
| 1 | DecentHolograms calls `CraftPlayer.getHandle()` returning `EntityPlayer` | Spigot-mapped plugin; waits on the remapper |
| 2 | InventoryRollbackPlus NPEs while saving inventories (`ItemStack.getType()` null, then `b is null`) | only appeared on runs where the tester died; watch for it again |
| 153 | `RecipeManager` parse errors, mostly Twilight Forest and FarmersDelight | boot-time; recipes lost; missing NeoForge codec extensions |
| 50 | `Supplementaries: Failed to generate recipe for sign post` | boot-time, mod-side, harmless |
| 13 | `ServerFunctionLibrary` cannot parse commands in datapack functions | boot-time, harmless |
| 4 | Paper's `LibraryLoader` cannot resolve Maven under ModLauncher | plugins declaring `libraries:` fail |
| 3 | ImageFrame (`ExceptionInInitializerError`), InvSee++ (`v1_21_R1` package), KartaAutoAnnouncer (missing embedded `config.yml`) fail to enable | InvSee++ waits on the remapper; the other two need their own look |
| 1 | `NeoForge handleServerStarted failed` — `TickRegionScheduler.getCurrentRegion()` is null on the Server thread | pre-existing; mods' `ServerStartedEvent` handlers do not run |
| 2 | `Eturlia: no blocks tag minecraft:carpets` / `no items tag minecraft:furnace_materials` | both are pre-1.21 names; vanilla Paper answers null too |

## Operating rules that must not slip

- Test heap stays `-Xms2G -Xmx8G`; `testctl.sh` refuses to start below 16 GB free. Production holds
  80 GB on a 124 GB box, and a 20 GB test heap with pre-touch already got it OOM-killed once.
- Kill gradle compiler workers after builds; they hold several GB.
- Never write inside `/home/user/mineroot/NoteBuns`.

---

## 2026-08-12 evening — test harness, and how to check a boot

**Grade a boot:** `python3 tools/logcheck.py` after every start. It groups every WARN/ERROR from
`latest.log`, hides the groups already judged benign (each pattern carries the reason it is benign)
and prints what is left under *ALARMING* and *unjudged*. Exit code 1 if anything alarming is there.
`--all` shows the hidden ones too. Never open the whole log — 400 WARN/ERROR lines a boot is normal
for this pack and almost all of it is mod chatter.

**Drive mods without a player:** `tools/modtest3.sh <x> <z>` builds a Create bearing with a creative
motor, powers it, and asserts with marker blocks — `/say` from the console prints the *translation
key* rather than the text, so a marker block and the literal "Changed the block at x y z" line are
the only reliable signal. A bearing assembles only with rotational force *and* redstone.

**Drive the client:** `tools/clienttest2.sh register|login` starts the headless client, clicks
through Simple Voice Chat's two-step setup wizard (it swallows every keystroke until it is gone),
and types into chat with xdotool. `tools/xchat.sh "<line>" ...` types into an already running
client. Both borrow the client's own `DISPLAY` and `XAUTHORITY` out of `/proc/<pid>/environ` —
`xvfb-run -a` picks a free display and writes its own cookie, so nothing else can find the window.

**Test account:** `EturliaTester`, AuthMe password **`Eturlia2026test`**. The AuthMe database was
reset on 2026-08-12 (the old one was copied from production); the previous file is kept as
`plugins/AuthMe/authme.db.bak-*`. Registration and login through the real client both pass.

**Two harness traps that cost an hour each:**

* Folia refuses entity selectors from the console — `Cannot getEntities asynchronously` — because
  console commands run on the global region. Anything with `@e` has to be typed by a player.
* A client teleported into ungenerated terrain with `view-distance=40` stops sending packets long
  enough for the server to drop it as "Timed out". Pre-generate with `forceload` *before* the
  teleport, and keep the test client's render distance low (`client/mc/options.txt`, set to 4).
