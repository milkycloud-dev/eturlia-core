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
| **Релиз** | [v0.1.0](https://github.com/eturnercus/Core/releases/tag/v0.1.0) |

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
- **Не кладите** отдельный `spark-neoforge` в `mods/` — spark уже вшит в Folia (конфликт JPMS).

## Smoke-статус

| Набор | Статус |
|-------|--------|
| Пустой сервер / лёгкий стек (Cloth, Curios, GeckoLib, JEI, …) | Mod List + SML + Folia `Done` |
| Farmers Delight (+ Cloth) | SML completed + `Done` |
| Create 6.0.10 | SML + `Done` (StateHolder null-map, Entity.getPersistentData, Level.clip); дальше runtime tick gaps |
| Moonlight | частично: ContextAware / FireBlock; дальше Folia entity mixins / refmap |

## Структура репозитория

| Путь | Назначение |
|------|------------|
| `patches/server`, `patches/api` | Folia + NeoForge/Eturlia патчи |
| `build-data/eturlia-core` | launch handler, mixins ядра |
| `build-data/eturlia-server-templates` | `EturliaServer` |
| `build-data/eturlia-neoforge-shims` | compile-time stubs |
| `neoforge/` | extras, resources, coremods |
| `compat/` | опциональные compat-модули |

## Апстрим и лицензии

- [PaperMC/Folia](https://github.com/PaperMC/Folia) · [Paper](https://github.com/PaperMC/Paper) · [NeoForge](https://github.com/neoforged/NeoForge)
- Патчи Folia/Paper: [`PATCHES-LICENSE`](./PATCHES-LICENSE)
- Код NeoForge — LGPL / заголовки апстрима

## Статус патчей

Активные server-патчи: Folia `0001`–`0019`, NeoForge/Eturlia `0020`–`0030`.  
Черновики `0033`–`0040` — в `patches/server-wip/` (пока не накатываются чисто).

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
| **Release** | [v0.1.0](https://github.com/eturnercus/Core/releases/tag/v0.1.0) |

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
**Do not** add `spark-neoforge` to `mods/` — spark is already bundled with Folia (JPMS conflict).

## Smoke status

| Set | Status |
|-----|--------|
| Empty / light stack (Cloth, Curios, GeckoLib, JEI, …) | Mod List + SML + Folia `Done` |
| Farmers Delight (+ Cloth) | SML completed + `Done` |
| Create 6.0.10 | SML + `Done` (null property maps, getPersistentData, Level.clip); further tick gaps remain |
| Moonlight | Partial — ContextAware/FireBlock; Folia entity mixins/refmap remain |

## Repository layout

| Path | Role |
|------|------|
| `patches/server`, `patches/api` | Folia + NeoForge/Eturlia patches |
| `build-data/eturlia-core` | launch handler, core mixins |
| `build-data/eturlia-server-templates` | `EturliaServer` |
| `build-data/eturlia-neoforge-shims` | compile-time stubs |
| `neoforge/` | extras, resources, coremods |
| `compat/` | optional compat modules |

## Upstream & license

- [PaperMC/Folia](https://github.com/PaperMC/Folia) · [Paper](https://github.com/PaperMC/Paper) · [NeoForge](https://github.com/neoforged/NeoForge)
- Folia/Paper patches: [`PATCHES-LICENSE`](./PATCHES-LICENSE)
- NeoForge code: upstream LGPL / file headers

## Patch status

Active server patches: Folia `0001`–`0019`, NeoForge/Eturlia `0020`–`0030`.  
WIP `0033`–`0040` live under `patches/server-wip/` and do not apply cleanly yet.

</details>
