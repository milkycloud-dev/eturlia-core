<div align="center">
  <img src="./eturlia.png" alt="Eturlia" width="900">
  <h1>Eturlia</h1>
  <p>Гибридное серверное ядро Minecraft <strong>1.21.1</strong><br>
  региональная многопоточность Folia · загрузка модов NeoForge</p>
  <p><a href="#русский">Русский</a> · <a href="#english">English</a></p>
</div>

---

<div id="русский"></div>

# Русский

> [!WARNING]
> Экспериментальный проект. **Не для продакшена.** Делайте бэкапы миров.
> Многие моды рассчитаны на один серверный поток Vanilla/Forge и ломаются на региональной модели Folia.

## Что такое Eturlia

**Eturlia** — одно jar-ядро, в котором одновременно живут два стека:

| Слой | Роль |
|------|------|
| **[Folia](https://github.com/PaperMC/Folia)** | Форк Paper: мир режется на независимые тик-регионы, которые крутятся на разных ядрах CPU |
| **[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248** | Модлоадер (FancyModLoader 4.0.43): `mods/`, RegisterEvent, lifecycle, datapacks, mixins |

Цель — масштабирование Folia без отказа от экосистемы NeoForge (Create, Farmers Delight и соседние техпаки), по мере закрытия runtime-разрывов между региональной моделью и ожиданиями модов.

| | |
|---|---|
| **Артефакт** | `eturlia-1.21.1-neoforge-21.1.248.jar` |
| **Launch target** | `eturliaserver` |
| **Точка входа** | `eturlia.EturliaServer` |
| **Релиз** | [v0.2.0](https://github.com/eturnercus/Core/releases/tag/v0.2.0) |

## Как это работает

### Загрузка (runtime)

```text
java -jar eturlia-1.21.1-neoforge-21.1.248.jar
        │
        ▼
 Eturlia launcher
   · распаковка nested libs (Folia, FML, NeoForge, runtime)
   · подготовка classpath и launch services
        │
        ▼
 ModLauncher / FancyModLoader
   · scan каталога mods/
   · bootstrap NeoForge, coremods, mixins
        │
        ▼
 Folia / Paper server
   · регионы, чанки, сущности, плагины (folia-supported)
        │
        ▼
 NeoForge hooks в NMS
   · RegisterEvent, game lifecycle, datapack reload, API-расширения
```

1. **Launcher** поднимает вложенные библиотеки из fat-jar и передаёт управление ModLauncher.
2. **FML** находит моды, прогоняет discovery / loading / common setup и подключает coremods Eturlia (редирект `main` → `EturliaServer` и служебные трансформы).
3. **Folia** тикает мир по регионам: соседние чанки могут жить на разных потоках; кросс-регионные обращения требуют правильных планировщиков.
4. **Патчи ядра** вшивают в Folia/Paper API и поведение, которые ожидает NeoForge (регистры, food/crafting/fire, reload, entity-list фасады и т.д.).

### Сборка (dev)

1. **paperweight** накатывает `patches/api` и `patches/server` (апстрим Folia + слой Eturlia/NeoForge) на Paper.
2. **Шимы** (`build-data/eturlia-neoforge-shims`) дают stub-сигнатуры NeoForge, чтобы NMS/Folia компилировались без полного дерева NeoForge в compile classpath.
3. В runtime в jar вложен опубликованный **NeoForge universal 21.1.248** — тот же major, что у целевых модов.
4. Опциональные модули `eturlia-compat-create` / `eturlia-compat-sable` закрывают узкие region-bridges; они не заменяют пробелы в самом ядре.

### Почему моды всё ещё могут падать

Стартовый путь FML уже проходит. Текущий слой работы — **runtime gaps**: мод вызывает API или полагается на однопоточный NMS, которого нет или оно ведёт себя иначе под Folia. Типичные классы проблем:

- holders / DeferredHolder / порядок RegisterEvent;
- Folia entity tick list / section managers вместо vanilla-структур;
- remapping / refmap у mixin-модов;
- однопоточные допущения в Create, Moonlight и соседних библиотеках.

## Версии

| Компонент | Версия |
|-----------|--------|
| Minecraft | 1.21.1 |
| NeoForge | **21.1.248** |
| FancyModLoader | 4.0.43 |
| Paper upstream | `84281cee…` (Folia `dev/1.21.1`) |
| Java | **21** |

## Сборка

Нужны **git clone** (не ZIP), **JDK 21**, доступ к Maven PaperMC и NeoForged.

```bash
./gradlew applyPatches                 # Folia + Eturlia/NeoForge патчи
./gradlew :folia-server:build          # Folia-Server
./gradlew :folia-server:eturliaStandaloneJar
```

Результат: `build/libs/eturlia-1.21.1-neoforge-21.1.248.jar`

```bash
./patch.sh   # applyPatches
./rb.sh      # rebuildServerPatches
```

## Запуск

```bash
java -jar build/libs/eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

- Первый запуск: примите `eula.txt`.
- Моды NeoForge → `mods/`.
- Плагины Bukkit/Paper → только с `folia-supported: true`.
- **Не кладите** отдельный `spark-neoforge` в `mods/` — bundled spark уже вшит (JPMS-конфликт). `/spark` работает; TPS семплится с global tick.
- Консоль по умолчанию **calm** (INFO без зелёного). `-Deturlia.console.color=full|off` при необходимости.
- Плагины с `libraries:` в `plugin.yml` могут не резолвить Maven под ModLauncher; без `libraries:` — ок.

## Smoke-статус / whitelist matrix

| Набор | Статус | Совместимость |
|-------|--------|---------------|
| Пустой / лёгкий стек (Cloth, Curios, GeckoLib, JEI, …) | SML + Folia `Done` | whitelist |
| Farmers Delight (+ Cloth) | SML + `Done` | whitelist |
| Create 6.0.10 | SML + `Done`; tick gaps под нагрузкой ещё ловятся | whitelist (best-effort) |
| Moonlight Lib | SML + `Done` + worlds; SoftFluid/FluidType bridged; further Folia gaps under load | whitelist (best-effort) |
| Bukkit/Paper плагины (`folia-supported`) | discovery + load (LibraryLoader/ProxyGenerator) | whitelist (без `libraries:`) |
| Bundled spark | `spark tps` OK; TPS с Folia global tick | whitelist |
| FerriteCore / прочие | не в матрице | **at your own risk** |

Политика моддеров и unsupported-модов: [`docs/MODDER_POLICY.md`](./docs/MODDER_POLICY.md).  
Аудит типичного пака (mods + Folia-плагины): [`docs/PACK_COMPAT_ASIC_2026-08.md`](./docs/PACK_COMPAT_ASIC_2026-08.md).

### Crash-reports

- Vanilla/Paper → `crash-reports/`
- **Eturlia (с region id)** → отдельная папка `eturlia-crash-reports/` (`-Deturlia.crash.dir=…`)

### Semver

Теги `vMAJOR.MINOR.PATCH` (сейчас **v0.2.0**). Линия `0.x` — экспериментальная; ломающие NMS-патчи между минорными ожидаемы. Артефакт: `eturlia-1.21.1-neoforge-21.1.248.jar`.

## Структура репозитория

| Путь | Назначение |
|------|------------|
| `patches/server`, `patches/api` | Folia + NeoForge/Eturlia патчи |
| `build-data/eturlia-core` | launch handler, region guard, mixins ядра |
| `build-data/eturlia-server-templates` | `EturliaServer` |
| `build-data/eturlia-neoforge-shims` | compile-time stubs (документация) |
| `neoforge/` | extras, resources, coremods |
| `compat/` | опциональные compat-модули |
| `docs/MODDER_POLICY.md` | whitelist / unsupported / region API |
| `docs/PACK_COMPAT_ASIC_2026-08.md` | аудит списка модов + Folia-плагины |
| `.github/workflows/eturlia-ci.yml` | applyPatches + jar + headless smoke |

## Апстрим и лицензии

- [PaperMC/Folia](https://github.com/PaperMC/Folia) · [Paper](https://github.com/PaperMC/Paper) · [NeoForge](https://github.com/neoforged/NeoForge)
- Патчи Folia/Paper: [`PATCHES-LICENSE`](./PATCHES-LICENSE)
- Код NeoForge — LGPL / заголовки апстрима

## Статус патчей

Активные server-патчи: Folia `0001`–`0019`, NeoForge/Eturlia `0020`–`0038` (interaction, Moonlight, spark global-tick, plugin remap).  
Черновики остальных WIP — в `patches/server-wip/`.

---

<details open>
<summary><strong>English</strong></summary>

<div id="english"></div>

# English

> [!WARNING]
> Experimental. **Not for production.** Back up worlds.
> Many mods assume a single Vanilla/Forge server thread and break under Folia’s region threading.

## What Eturlia is

**Eturlia** is a single fat-jar server kernel that runs two stacks together:

| Layer | Role |
|-------|------|
| **[Folia](https://github.com/PaperMC/Folia)** | Paper fork: the world is split into independent tick regions across CPU cores |
| **[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248** | Mod loader (FancyModLoader 4.0.43): `mods/`, RegisterEvent, lifecycle, datapacks, mixins |

The goal is Folia’s multi-core scaling without giving up the NeoForge ecosystem (Create, Farmers Delight, and similar packs), as Folia↔NeoForge runtime gaps are closed.

| | |
|---|---|
| **Artifact** | `eturlia-1.21.1-neoforge-21.1.248.jar` |
| **Launch target** | `eturliaserver` |
| **Entry point** | `eturlia.EturliaServer` |
| **Release** | [v0.2.0](https://github.com/eturnercus/Core/releases/tag/v0.2.0) |

## How it works

### Boot (runtime)

```text
java -jar eturlia-1.21.1-neoforge-21.1.248.jar
        │
        ▼
 Eturlia launcher
   · extract nested libs (Folia, FML, NeoForge, runtime)
   · prepare classpath and launch services
        │
        ▼
 ModLauncher / FancyModLoader
   · scan mods/
   · bootstrap NeoForge, coremods, mixins
        │
        ▼
 Folia / Paper server
   · regions, chunks, entities, plugins (folia-supported)
        │
        ▼
 NeoForge hooks in NMS
   · RegisterEvent, game lifecycle, datapack reload, API extensions
```

1. **Launcher** unpacks nested libraries from the fat jar and hands off to ModLauncher.
2. **FML** discovers mods, runs discovery / loading / common setup, and applies Eturlia coremods (`main` → `EturliaServer` plus support transforms).
3. **Folia** ticks the world by region: neighboring chunks may run on different threads; cross-region work needs the correct schedulers.
4. **Core patches** teach Folia/Paper the APIs and behaviors NeoForge mods expect (registries, food/crafting/fire, reload, entity-list facades, and so on).

### Build (dev)

1. **paperweight** applies `patches/api` and `patches/server` (upstream Folia + the Eturlia/NeoForge layer) onto Paper.
2. **Shims** (`build-data/eturlia-neoforge-shims`) provide stub NeoForge signatures so Folia/NMS can compile without the full NeoForge tree on the compile classpath.
3. Runtime embeds published **NeoForge universal 21.1.248** — the same major line as target mods.
4. Optional `eturlia-compat-create` / `eturlia-compat-sable` modules cover narrow region bridges; they do not replace gaps in the core itself.

### Why mods can still fail

The FML boot path already works. The current work layer is **runtime gaps**: a mod calls an API or assumes single-threaded NMS that is missing or behaves differently under Folia. Typical failure classes:

- holders / DeferredHolder / RegisterEvent ordering;
- Folia entity tick list / section managers vs vanilla structures;
- remapping / refmap issues in mixin mods;
- single-thread assumptions in Create, Moonlight, and related libraries.

## Versions

| Component | Version |
|-----------|---------|
| Minecraft | 1.21.1 |
| NeoForge | **21.1.248** |
| FancyModLoader | 4.0.43 |
| Paper upstream | `84281cee…` (Folia `dev/1.21.1`) |
| Java | **21** |

## Build

Requires a **Git clone** (not a ZIP), **JDK 21**, and PaperMC + NeoForged Maven access.

```bash
./gradlew applyPatches
./gradlew :folia-server:build
./gradlew :folia-server:eturliaStandaloneJar
```

Output: `build/libs/eturlia-1.21.1-neoforge-21.1.248.jar`

```bash
./patch.sh   # applyPatches
./rb.sh      # rebuildServerPatches
```

## Run

```bash
java -jar build/libs/eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

Accept `eula.txt` on first boot. NeoForge mods go in `mods/`. Plugins need `folia-supported: true`.  
**Do not** add `spark-neoforge` to `mods/` — bundled spark is already present (JPMS conflict). `/spark` works; TPS is sampled on the Folia global tick.
Console defaults to **calm** (no green INFO). Override with `-Deturlia.console.color=full|off`.
Plugins that declare `libraries:` in `plugin.yml` may fail Maven resolve under ModLauncher; plugins without `libraries:` load fine.

## Smoke status / whitelist matrix

| Set | Status | Compatibility |
|-----|--------|---------------|
| Empty / light stack (Cloth, Curios, GeckoLib, JEI, …) | SML + Folia `Done` | whitelist |
| Farmers Delight (+ Cloth) | SML + `Done` | whitelist |
| Create 6.0.10 | SML + `Done`; tick gaps under load still tracked | whitelist (best-effort) |
| Moonlight Lib | SML + `Done` + worlds; SoftFluid/FluidType bridged; further Folia gaps under load | whitelist (best-effort) |
| Bukkit/Paper plugins (`folia-supported`) | discovery + load (LibraryLoader/ProxyGenerator) | whitelist (no `libraries:`) |
| Bundled spark | `spark tps` OK; TPS from Folia global tick | whitelist |
| FerriteCore / others | not in matrix | **at your own risk** |

Modder / unsupported-mod policy: [`docs/MODDER_POLICY.md`](./docs/MODDER_POLICY.md).  
Pack audit (mods + Folia plugins): [`docs/PACK_COMPAT_ASIC_2026-08.md`](./docs/PACK_COMPAT_ASIC_2026-08.md).

### Crash reports

- Vanilla/Paper → `crash-reports/`
- **Eturlia (with region id)** → separate folder `eturlia-crash-reports/` (`-Deturlia.crash.dir=…`)

### Semver

Tags `vMAJOR.MINOR.PATCH` (currently **v0.2.0**). The `0.x` line is experimental; breaking NMS patches between minors are expected. Artifact: `eturlia-1.21.1-neoforge-21.1.248.jar`.

## Repository layout

| Path | Role |
|------|------|
| `patches/server`, `patches/api` | Folia + NeoForge/Eturlia patches |
| `build-data/eturlia-core` | launch handler, region guard, core mixins |
| `build-data/eturlia-server-templates` | `EturliaServer` |
| `build-data/eturlia-neoforge-shims` | compile-time stubs (docs) |
| `neoforge/` | extras, resources, coremods |
| `compat/` | optional compat modules |
| `docs/MODDER_POLICY.md` | whitelist / unsupported / region API |
| `docs/PACK_COMPAT_ASIC_2026-08.md` | аудит списка модов + Folia-плагины |
| `.github/workflows/eturlia-ci.yml` | applyPatches + jar + headless smoke |

## Upstream & license

- [PaperMC/Folia](https://github.com/PaperMC/Folia) · [Paper](https://github.com/PaperMC/Paper) · [NeoForge](https://github.com/neoforged/NeoForge)
- Folia/Paper patches: [`PATCHES-LICENSE`](./PATCHES-LICENSE)
- NeoForge code: upstream LGPL / file headers

## Patch status

Active server patches: Folia `0001`–`0019`, NeoForge/Eturlia `0020`–`0038` (interaction, Moonlight, spark global-tick, plugin remap).  
Remaining WIP drafts: `patches/server-wip/`.

</details>
