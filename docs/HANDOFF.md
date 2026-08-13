# Eturlia — handoff

**State as of 2026-08-13 16:30 MSK.** Build in the world right now: `2026-08-13T11:46:07Z`.

This file is the live state document: what is running, what is solved, what is open, and what a
session should do first. The per-failure-class account of the fixes themselves is
[`FIXES.md`](FIXES.md); the harness is [`TESTING.md`](TESTING.md); the project as a whole is the
[README](../README.md).

---

## 1. Where everything is

| | |
|---|---|
| Work tree | `/home/user/milky/eturlia_new` on `188.124.37.101` |
| Core repository | `/home/user/milky/eturlia_new/core` (this repo) |
| Test server | `/home/user/milky/eturlia_new/server`, screen session `test`, port **25963** |
| Published | `github.com/milkycloud-dev/eturlia-core`, branch `main`, workflow `eturlia-ci` |
| Upstream | `eturnercus/Core` — itself a fork of PaperMC/Folia carrying NeoForge 21.1.248 |
| Docs | `docs/FIXES.md`, `docs/TESTING.md`, `docs/ARCHITECTURE.html` (Russian, diagrams), `docs/archive/` |

The box also runs an unrelated **production** server in screen session `NoteBuns`
(`/home/user/mineroot/NoteBuns`, 80 GB heap on a 124 GB box). It is read-only for this project:
copy mods and plugins out of it, never write into it, never restart it. `tools/testctl.sh` refuses
to start the test server when less than 16 GB is available, because on 2026-08-11 a 20 GB test heap
with `AlwaysPreTouch` got production OOM-killed. Do not raise the test heap; `server/start.sh` and
its flags belong to the operator, not to this project — read it, do not rewrite it.

The checkout on the box sits on branch `fix/neoforge-handshake-chain`. Run `git branch -f main HEAD`
before publishing or `main` ships stale. Commits are authored `milkycloud-dev <uberlion1@gmail.com>`.

---

## 2. What is running, and how to confirm it

```bash
tools/testctl.sh status              # screen sessions and the test server's pids
screen -S test -p 0 -X stuff 'version\n'   # prints the build stamp of the running jar
python3 tools/logcheck.py --all      # grade the boot that is in latest.log
```

Verified on 2026-08-13:

| | |
|---|---|
| Boot | `Done (12.673s)` |
| Mods | 115 loaded from 87 jars (`mods/`, jar-in-jar included) |
| Plugins | 37 loaded from 38 jars |
| Alarming lines in a full boot | 2 — ImageFrame and KartaAutoAnnouncer, both third-party bugs |
| Static bind scan | 15 final-overrides + 10 unimplemented interface methods, **all datagen**; 87 jars, 36 196 mod classes against 8 674 core classes |
| Plugin commands | 224 of 225 declared |
| Vanilla commands | 48 of 48 |
| Compat planes present in the jar | `debug.particles`, `compat.read-timeout`, `compat.sublevel-chunks`, `LEVEL_CTOR_EXTRAS`, `mayLoadFromThisThread`, `readTimeoutSeconds` — checked by reading the class entries out of the jar, not by trusting the build log |

---

## 3. One turn of the loop

```bash
tools/cycle.sh          # applyPatches → generate → build → announce → stop → deploy → start → grade
tools/cycle.sh build    # stop after the jar
tools/cycle.sh deploy   # skip the build, redeploy and restart what is already built
```

One line per step; a failing step prints the tail of its own log and stops. `latest.log` is moved
aside before every start, so `wait-ready` and `logcheck.py` cannot read the previous boot's lines.

Publishing, when the box has no `gh` and no stored token — read the token on the workstation and push
in one inline command, writing nothing to disk:

```bash
git push --force 'https://milkycloud-dev:<token>@github.com/milkycloud-dev/eturlia-core.git' main:main
```

---

## 4. What is solved

Detail and mechanism per item: [`FIXES.md`](FIXES.md). Summary of the classes closed so far:

- **No main thread.** `MinecraftServer.execute` / `executeBlocking` / `tell` schedule instead of
  throwing; the legacy `BukkitScheduler` runs off the global tick.
- **Mods subclass what Paper sealed.** `ChunkHolder`, `Entity`, `LivingEntity`, `HangingEntity`,
  `DefaultDispenseItemBehavior` de-finalised; missing vanilla files imported from `MC_DEV`.
- **Mods build their own levels.** `Level`'s constructor no longer requires a `ServerLevel` — this
  was the single largest find: Create's contraption world, its schematic world and Sable's physics
  sub-levels each threw once per tick, which is what "the machine assembles and then does nothing"
  actually was.
- **Six further sub-level gaps**, each a member Paper's rewrites removed and a mod still inherits:
  `LevelLightEngine.blockEngine/skyEngine`, the vanilla-shaped `LevelChunkSection` constructor,
  `ChunkHolder`'s three chunk futures, `ChunkAccess.getLevel()`, a null `ChunkMap.chunkStatusListener`,
  and the "cannot asynchronously load chunks" guard (now relaxed only for a chunk **no region owns**,
  behind `-Deturlia.compat.sublevel-chunks=strict`).
- **Missing interface defaults** — `BlockGetter.getBlockStateIfLoaded/getFluidIfLoaded`,
  `LevelReader.getChunkIfLoadedImmediately`, `LevelAccessor.getMinecraftWorld`,
  `Merchant.getCraftMerchant`, `Recipe.toBukkitRecipe`, and a `RecipeIterator` that skips nulls.
- **Folia's deleted commands.** 17 vanilla commands re-registered behind
  `-Deturlia.compat.folia-commands`; `/scoreboard`, `/team`, `/data get block`, `/datapack list`
  verified by hand. Vanilla coverage is 48/48.
- **Plugins on Folia.** The `folia-supported` gate is dropped, modded entities read as `UNKNOWN` and
  modded blocks as `Material.STONE` to plugin code, and the Spigot→Mojang remapper retries a jar
  without the classes this JVM cannot load.
- **Clients that stall while building the world.** Netty's read timeout — not the keepalive — is now
  `-Deturlia.compat.read-timeout` (90 s default). Before it, every headless test client was dropped
  at ~30 s and every run silently measured nothing.

---

## 5. What is open, in order of value

### 5.1 Plugins with a Paper library loader cannot load

`PlugManX-3.0.2.jar` never reaches the plugin list. In `logs/eturlia-noise.log`:

```
[DirectoryProviderSource] Error loading plugin: Cannot invoke
"org.eclipse.aether.RepositorySystem.newLocalRepositoryManager(...)" because "this.repositorySystem" is null
  at io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver.<init>(MavenLibraryResolver.java:78)
  at PlugManX-3.0.2.jar//paper.com.rylinaux.plugman.PaperPlugManLoader.classloader(PaperPlugManLoader.java:14)
```

This is a class of failure, not one plugin: any Paper plugin whose `PluginLoader` resolves Maven
libraries at load time hits it. The repository system Paper builds through its own dependency
machinery is not wired in the standalone jar. Fix belongs in the core, behind a switch like every
other plane. Nothing else in the pack currently uses a library loader, which is why it went unnoticed.

### 5.2 Create: Aeronautics can still abort the JVM

`/sable spawn joint_test` reaches Rapier's `buoyancy.rs`; the panic is non-unwinding, so the process
dies with no crash report and `start.sh` restarts it. Everything on the Java side of that path is
clean — the abort is inside the mod's native library. Run it last in any suite, expect a log rotation
when it happens, and keep `RUST_BACKTRACE=full` in the environment so the native side is legible.

### 5.3 The cyan trails — identify the client mod

Settled: **not the server.** `-Deturlia.debug.particles=true` names every particle type the core
sends; a full run of walking sent none. Removing DemonicEye, PPC_Wings, PlayerParticles,
TrollEffects, then all 38 plugins, then ~35 client mods, changed nothing, and the effect reproduces in
a brand-new world. What remains is the client half of a mod that also runs on the server.

The bisect is unfinished because the obvious cut does not build: removing
`malum + lodestone + supplementaries + alexsmobs + twilightforest` together crashes the client at
startup on dependencies. It has to be one mod at a time, in dependency order, with
`tools/trailcheck.sh <tag>` and a comparison of `/tmp/trail_<tag>_no_effects.png`.

### 5.4 AuthMe login typed by a client

Login was verified from the console (`authme forcelogin`). The original report — "a player cannot log
in from the client" — has not been reproduced or refuted with a client actually typing `/login`.
Needs one run of `tools/clienttest3.sh` whose first assertion is the typed login.

### 5.5 Stress phases with valid verdicts

`tools/aerostress.sh` has eight phases and only got a working marker mechanism after its last full
run, so its verdicts predate the fix. Re-run it and read the coordinates back
(`grep -av 'issued server command'`).

---

## 6. Things not worth rediscovering

- **A failed region tick shuts the whole server down.** Any exception thrown from a mod inside a
  region tick is fatal, which is why "restore the member and answer null" is usually the right fix
  rather than catching at the call site.
- **A native-backed mod dies twice.** The Java exception comes first and is the real cause; the JVM
  abort that follows is the mod's half-built native state. Chase the Java error, never the panic.
- **The forceload trap.** A sweep that mass-summons entities and leaves a `forceload` on their chunk
  produces a crash loop that looks like a core regression. `forceload remove` before blaming a build.
- **`screen -L` keeps its own file offset**, so `logs/test_stdout.log` shows stale counts right after
  a truncate. `server/logs/latest.log` is the trustworthy source.
- **Files edited on the Windows mirror pick up CRLF**; a shell script uploaded that way dies with
  `\r': command not found`. Run `sed -i 's/\r$//'` after each upload.

---

## 7. Operating rules that must not slip

1. Production (`NoteBuns`) is read-only. Never restart it, never write into it, never take memory
   from it. Check `free -g` before starting anything heavy.
2. One test server, one screen session named `test`. `testctl.sh` refuses a second.
3. Every core change goes through `scripts/apply_compat_layer.py`. A change made directly in the
   generated tree is lost on the next `applyPatches` and does not exist to CI.
4. A fix must close a *class* of failure. If a different mod with the same error would still need a
   new patch, it is not a fix.
5. Never load a whole server log into a session's context — parse it with `tools/logcheck.py`,
   `tools/logsweep.py`, or a small script that prints only what is asked.
