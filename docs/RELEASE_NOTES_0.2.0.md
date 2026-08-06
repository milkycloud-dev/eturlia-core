# Eturlia v0.2.0

**Minecraft 1.21.1** · **NeoForge 21.1.248** · **Folia** · **Java 21**

> [!CAUTION]
> Экспериментальный pre-release. **Не для продакшена.** Делайте бэкапы миров.

## Артефакт

`eturlia-1.21.1-neoforge-21.1.248.jar`  
Launch target: `eturliaserver` · Entry: `eturlia.EturliaServer`

```bash
java -jar eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

- Java **21**, принять `eula.txt`
- NeoForge-моды → `mods/`
- Плагины → только с `folia-supported: true` (без обязательного `libraries:` пока)
- **Не кладите** `spark-neoforge` в `mods/` — bundled spark уже вшит
- Консоль: `-Deturlia.console.color=calm|full|off` (по умолчанию calm)

---

## Что сделано (с v0.1.0)

### Слои A–D (ядро)

| Слой | Содержание |
|------|------------|
| **A** | Moonlight/Folia bridges: `adjustSpawnLocation`, SoftFluid/`FluidType`, Place/Teleport mixin locals |
| **B** | NeoForge interaction hooks на Paper `ServerPlayerGameMode` / packet listener (LMB/RMB, item use, break) |
| **C** | `CrossRegionInvocationGuard` + `RegionEntityListFacade` в RegionAwareEventBus |
| **D** | Region crash reports → `eturlia-crash-reports/`, `docs/MODDER_POLICY.md`, CI `eturlia-ci.yml`, semver |

### Плагины Bukkit/Paper

- **LibraryLoader** больше не валит `SpigotPluginProvider` при `RepositorySystem == null` под ModLauncher
- **ReflectionRemapper / ProxyGenerator**: байты `PaperReflection` читаются через defining ClassLoader (TRANSFORMER), плагины снова грузятся
- Smoke: `Initialized 1 plugin` → `EturliaPluginSmoke ENABLED`

### Консоль

- NeoForge `%highlightForge` больше не красит весь INFO зелёным
- По умолчанию calm: INFO plain, WARN/ERROR цветные

### Spark

- Bundled `/spark` снова включён
- TPS семплится **только** с Folia global tick (без AIOOBE на регионах)
- Smoke: `spark tps` → `*20.0`

### WorldEdit NeoForge (hotfix в том же v0.2.0)

- `LevelChunk.setBlockState`: тело снова в 3-arg (WE mixin находит `onPlace`)
- CraftBukkit NO_PLACE → `setBlockStateDoPlace`
- Folia boot fires `RegisterCommandsEvent` (WE PlatformsRegistered / init)
- Патч: `patches/server/0040-Eturlia-WorldEdit-NeoForge-boot-init-on-Folia.patch`

### Block placement (hotfix в том же v0.2.0)

- Убраны слепые касты `ItemStack` → `IItemStackExtension` в `ServerPlayerGameMode.useItemOn`
- Folia `ItemStack` не interface-injected → раньше каждый ПКМ падал `ClassCastException` и блоки не ставились
- Патч: `patches/server/0039-Eturlia-drop-IItemStackExtension-casts-from-useItemO.patch`

---

## Что ещё нужно сделать (бэклог)

| Приоритет | Задача |
|-----------|--------|
| Высокий | Maven `libraries:` в `plugin.yml` под ModLauncher (`maven-model` / JPMS) |
| Высокий | Create: tick/region gaps под нагрузкой |
| Средний | FerriteCore clinit / прочие at-your-own-risk моды |
| Средний | Дожать WIP NeoForge interaction stubs (`patches/server-wip/`) |
| Низкий | Полный Folia-aware TPS из TickData (как spark-folia plugin) вместо global-tick proxy |
| Политика | Регион-unsafe моды останутся unsupported — не блокеры релиза |

### Аудит пака (mods + Folia-плагины)

Полная таблица: [`docs/PACK_COMPAT_ASIC_2026-08.md`](./PACK_COMPAT_ASIC_2026-08.md).

- **Lithostitched ≥1.7.13 + Terralith + Incendium:** **OK** после патчей `0041`–`0042` (smoke `Done`).
- **Sable:** убрать Arclight-патч → shim из релиза; bridges `0042`–`0066`; smoke **OK\*** (`Done` + worlds + ticks).
- **Убрать:** spark-neoforge, клиентские jars, `*.jar1`/`*.bak`.
- Оптимизаторы вне scope. Плагины — только `folia-supported: true`, без `libraries:` пока.

### config/eturlia.yml (ядро)

Новый бренд-конфиг со стартовым шаблоном (потоки Folia/Moonrise, чанки, command blocks, region guard, watchdog, LOD, ссылки на paper-global/server.properties).

- Создаётся в `config/eturlia.yml` при первом старте
- `sync-to-paper: true` — overrides threads до `TickRegions`/`ChunkSystem` init
- `gameplay.command-blocks.enabled` — override `enable-command-block`
- При старте печатает сводку effective settings

Патч: `0067-Eturlia-load-config-eturlia.yml-before-Paper-pools-b.patch`

### SHA256

```
457653dabe853b4c2242cee13ad36cdfdc02ec5eee0b9bd49d45fbc4b77523b4
```

---

## Smoke matrix (v0.2.0)

| Набор | Статус |
|-------|--------|
| Пустой / лёгкий стек | SML + Folia `Done` |
| Farmers Delight (+ Cloth) | SML + `Done` |
| Create 6.0.x | `Done`; gaps под нагрузкой |
| Moonlight Lib | `Done` + worlds |
| folia-supported плагин (без `libraries:`) | load + enable |
| Bundled spark | `spark tps` OK |
| Постановка блоков (ПКМ) | OK после hotfix `0039` |
| WorldEdit NeoForge 7.3.8 | boot `Done` после hotfix `0040` |
| Lithostitched 1.7.13 + Terralith + Incendium | boot `Done` после `0041`–`0042` |
| Sable 2.0.3 (+ Eturlia arclight shim) | WIP — explode WrapMethod vs Folia Consumer |
| FerriteCore / прочие | at your own risk |
