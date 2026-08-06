# Server patches WIP

These NeoForge hook batches (`0033`–`0040`) were authored against incomplete compile shims and do **not** apply cleanly on the Folia `dev/1.21.1` + NeoForge **21.1.248** tree.

Active NeoForge hooks that *do* apply and compile are `patches/server/0020`–`0025` (updated for 21.1.248 API signatures).

To rebase a WIP patch later:

```bash
./gradlew applyPatches
# manually port hunks from these files into Folia-Server
./gradlew rebuildServerPatches
```
