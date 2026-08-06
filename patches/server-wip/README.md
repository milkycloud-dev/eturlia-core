# Server patches WIP

Remaining NeoForge hook batches that still need careful port onto Paper/Folia NMS:

| Patch | Topic | Status |
|-------|--------|--------|
| 0033 | LanguageHook / Difficulty / ResourceReload | stub imports only |
| 0034 | Entity mount / travel / section / convert | stub |
| 0035 | Player interaction / click | **ported** → active `patches/server` (GameMode + packet listener) |
| 0036 | Block drops / break / harvest | partial via `fireBlockBreak` + destroy-item; full drops TBD |
| 0037 | Crop / loot / attribute / crafting / capability | open |
| 0038 | Chunk system / server tick | open |
| 0039 | Injected interfaces | partial (IBlockExtension already on Block) |
| 0040 | Stat / advancement / permission | open |

Active NeoForge hooks that *do* apply: `patches/server/0020`–current Eturlia numbered patches.

To rebase a WIP patch later:

```bash
./gradlew applyPatches
# manually port hunks from these files into Folia-Server
./gradlew rebuildServerPatches
```
