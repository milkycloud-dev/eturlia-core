# Eturlia — the test harness

There is no unit-test suite for "does a 90-mod pack survive regionised threading". Everything here
exists to boot the real pack, drive it, and read the answer back out of a running game — cheaply
enough that an iteration can be repeated all day.

Read this before writing a new test. Most of it was learned by having a run silently measure
nothing.

---

## 1. The tools

All of them live in `tools/` and run on the box, next to the server.

| Tool | What it answers | Typical call |
|---|---|---|
| `cycle.sh` | one turn: applyPatches → generate → build → announce → stop → deploy → start → grade | `tools/cycle.sh` |
| `testctl.sh` | start / stop / status / `say` / `wait-ready`, scoped to the **test** server only | `tools/testctl.sh status` |
| `logcheck.py` | grades a boot: groups every WARN/ERROR, hides groups already judged benign, prints what is new | `python3 tools/logcheck.py` |
| `logsweep.py` | every log, every level, grouped by normalised shape | `python3 tools/logsweep.py --grep "text"` |
| `finalscan.py` | static: which mod classes cannot bind to the core | `python3 tools/finalscan.py --jar core/Folia-Server/build/eturlia/folia-server-neoforge-at.jar` |
| `modsweep.py` | console sweep: plugin commands, modded entities, blocks, worldgen, ticking block entities | `python3 tools/modsweep.py` |
| `cmdtree.py` | maps a mod's command tree from what Brigadier underlines as unknown | `python3 tools/cmdtree.py sable` |
| `join_stable.sh` | join with a real client and hold; prints `STABLE` / `DISCONNECT` / `REGION_DEATH` and new errors | `tools/join_stable.sh 600` |
| `clienttest3.sh` | scripted gameplay typed by a client, with liveness gating between steps | `tools/clienttest3.sh` |
| `aerotest.sh`, `aerostress.sh` | Create and Create: Aeronautics under a real client; the stress one has 8 phases and its own run id | `tools/aerostress.sh` |
| `trailcheck.sh` | a lit empty stage, screenshots front / behind / third person | `tools/trailcheck.sh <tag>` |
| `xchat.sh` | type lines into an already-running client | `tools/xchat.sh "/time set day"` |
| `stall_dump.sh` | joins once and prints the client's Render-thread stack 22 s in | |
| `wire_check.sh` | joins once and samples `ss -tin` on the connection every 5 s | |

`logcheck.py` remembers what it has already reported in `tools/logcheck-seen.json`, so after the
first run its output is the alarming lines plus *what is new*. A normal boot for this pack is around
400 WARN/ERROR lines in ~40 kinds, almost all mod chatter. `--all` hides nothing, `--seen` lists what
is remembered.

`finalscan.py` must be pointed at the real core jar. `server/eturlia.jar` is the launcher and
contains two classes; a scan of it reports "core classes: 2" and finds nothing.

---

## 2. Reading an answer back out of the game

Three of the obvious channels do not work on this build. Each cost a session.

* **`/say` is useless as a marker.** The log keeps the translation key (`chat.type.announcement`)
  and drops the text — from the console *and* from a player. Runs that used it reported empty
  verdicts for every phase.
* **Command feedback from a player is logged in full.** `[EturliaTester: Changed the block at 100,
  70, 100]`, `[EturliaTester: Summoned new Cow]`. That is the marker mechanism: give every assertion
  its own coordinate, then read the coordinates back.
* **The server also echoes each command as it is issued.** Grepping for the marker text alone
  matches `/execute if … run setblock …` whether or not the condition held. Always harvest with
  `grep -av 'issued server command'`.
* **Console commands cannot use entity selectors.** Folia runs the console on the global region and
  refuses `getEntities` from it (`Cannot getEntities asynchronously`). Anything with `@e`, `@p` or
  `@a` has to be typed by a player.
* **Repeating command blocks never fire** on this build. A block placed with `{Command:'…',auto:1b}`
  sits there and does nothing, so that workaround is out as well.
* **`screen -X stuff` parses quotes.** A console command sent through `testctl.sh say` must not
  contain double quotes; SNBT accepts single-quoted strings, so use those.

A working assertion therefore looks like:

```
/execute if block 100 70 100 minecraft:diamond_block run setblock 100 71 100 minecraft:gold_block
```

typed **by the player**, harvested from the log by the coordinate `100 71 100`, with the echo line
excluded.

---

## 3. The headless client

`portablemc` under `xvfb-run`, keystrokes with `xdotool`, screenshots with `import`. The client
lives in `client/mc` with the real player pack (mirrored from
`https://download.inflexus.world/cloud/mods/`, 126 jars).

**Finding its display.** `xvfb-run -a` picks a free display and writes its own cookie, so nothing
else can find the window unless it borrows both out of the running process:

```bash
DISPLAY=$(tr '\0' '\n' < /proc/<pid>/environ | sed -n 's/^DISPLAY=//p')
XAUTHORITY=$(tr '\0' '\n' < /proc/<pid>/environ | sed -n 's/^XAUTHORITY=//p')
import -window root out.png
```

**Pack adjustments the harness depends on** (all reversible, kept under `legacy_patched/`):

* `fancymenu`, `melody`, `spiffyhud`, `seamless-loading-screen` are moved aside — FancyMenu replaces
  the title screen and `--quickPlayMultiplayer` only fires from the vanilla one.
* `config/wover/client.json` has `did_present_welcome_screen: true`, or BetterX's welcome screen sits
  in front of the title screen forever.
* `run_client.sh` forces `MESA_GL_VERSION_OVERRIDE=4.6` / `MESA_GLSL_VERSION_OVERRIDE=460`; at 3.3
  Veil's shaders fail to link every frame.
* The client JVM gets portablemc's default 2 GB; `run_client.sh` raises it to 6 GB with
  `--jvm-args`, which **replaces** the default flags, so the G1 options are repeated there.
* Render distance is 4 in `client/mc/options.txt`. A client teleported into ungenerated terrain at a
  high render distance stops sending packets long enough to be dropped.

**Test account:** `EturliaTester`, AuthMe password `Eturlia2026test`.

---

## 4. Traps, each of which has cost at least one session

1. **Prove the keyboard reaches the game before believing anything.** Type a command whose feedback
   is logged and continue only when it appears. Without that canary a run "passes" every phase with
   nothing typed at all.
2. **Escape with no menu open *opens* the pause menu**, and every keystroke after that goes to the
   menu. Minecraft also opens it when the window loses focus. Recover by probing, never by pressing
   Escape blindly.
3. **Anchor "is the client still connected" after the join line.** A client killed at the start of a
   run logs its own `lost connection` a few seconds later, and an earlier anchor reads that as the
   new client dying — which made every phase of an early suite report "client disconnected".
4. **`authme register <player>` from the console kicks whoever it just registered**
   ("An admin just registered you; please log in again"). Register *before* the client connects, then
   `authme forcelogin <player>`.
5. **Vanilla op is not enough.** LuckPerms answers `minecraft.command.*`, so `/say`, `/execute` and
   `/summon` need `lp user <name> permission set minecraft.command.<cmd> true`.
6. **The 30-second drop is Netty's read timeout, not the keepalive.** Raising
   `paper.playerconnection.keepalive` does nothing. The core now exposes
   `-Deturlia.compat.read-timeout` (90 s default); with it the client survives a full run.
7. **A player teleported far while the client is still loading never arrives.** Teleport again right
   before the assertions, and light the stage at that moment rather than minutes earlier.
8. **A dead tester stays dead across rejoins**, and a client on the death screen sends no packets at
   all — which reads exactly like a server regression. With the client stopped:
   `rm -f server/world/playerdata/*.dat*`. `defaultgamemode creative` is set on the test server.
9. **`wait-ready` can match the previous boot's `Done (`.** `cycle.sh` moves `latest.log` aside
   before every start; anything else that waits for a boot must do the same.
10. **A sweep that leaves a `forceload` on a chunk full of summoned entities** produces a crash loop
    that looks like a core regression. `forceload remove` before blaming a build.
11. **Run `/sable spawn joint_test` last, or not at all.** Its native panic aborts the JVM, the
    wrapper restarts the server, and the log rotates mid-run.

---

## 5. Writing a new test

1. Decide what the assertion *is* and give it a coordinate. One coordinate per assertion, never
   reused inside a run.
2. Start from `clienttest3.sh`: it already kills stale clients, joins, forces the login, proves the
   keyboard reaches the game, and re-checks liveness before every keystroke.
3. Type the commands as the player. Anything selector-shaped has no console equivalent here.
4. Harvest with `grep -av 'issued server command'` over the slice of `latest.log` after your own
   anchor line.
5. Print one verdict per assertion, and print `!!` and stop the moment liveness fails — a run that
   keeps typing into a dead client produces confident nonsense.
