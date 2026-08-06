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

### SHA256

```
819400fe54ad175f430548e364a927c064a38ae5dcbb6d9a910e09ca2b5639dd
```

Общая рамка: FML boot path зелёный; основная работа — **runtime gaps** между однопоточными ожиданиями модов и регионами Folia.

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
| FerriteCore / прочие | at your own risk |
