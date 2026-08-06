<div align="center">
  <img src="./eturlia.png" alt="Eturlia" width="900">
  <h1>Eturlia</h1>
  <p>Ядро сервера Minecraft <strong>1.21.1</strong>: регионы Folia + загрузка модов NeoForge</p>
  <p><a href="#русский">Русский</a> · <a href="#english">English</a></p>
</div>

---

<div id="русский"></div>

# Русский

> [!WARNING]
> Экспериментальный проект. **Не для продакшена.** Делайте бэкапы миров. Многие моды несовместимы с региональной многопоточностью Folia.

## Что такое Eturlia

**Eturlia** — гибридное серверное ядро: один fat-jar, в котором одновременно работают

- **[Folia](https://github.com/PaperMC/Folia)** — форк Paper с независимыми тик-регионами на нескольких ядрах CPU;
- **[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248** — загрузка NeoForge-модов через FancyModLoader (FML 4.0.43).

Идея простая: взять масштабируемость Folia и дать серверу нормальный модлоадер NeoForge, чтобы техпаки (Create и рядом) могли жить на многопоточном ядре — по мере закрытия runtime-дыр Folia↔NeoForge.

| | |
|---|---|
| **Артефакт** | `eturlia-1.21.1-neoforge-21.1.248.jar` |
| **Launch target** | `eturliaserver` |
| **Точка входа** | `eturlia.EturliaServer` |
| **Релиз** | [v0.2.0](https://github.com/eturnercus/Core/releases/tag/v0.2.0) |

## Как это работает

```text
java -jar eturlia-….jar
        │
        ▼
 Eturlia launcher  ──►  распаковка nested libs (Folia, FML, NeoForge, runtime)
        │
        ▼
 ModLauncher / FML   ──►  scan mods/, bootstrap NeoForge
        │
        ▼
 Folia / Paper server ──►  регионы, чанки, плагины (folia-supported)
        │
        ▼
 NeoForge hooks       ──►  RegisterEvent, lifecycle, datapacks, mixins
```

1. **Сборка патчами** — paperweight накатывает `patches/api` и `patches/server` (Folia + хуки NeoForge) на апстрим Paper.
2. **Шимы** (`build-data/eturlia-neoforge-shims`) — stub-API, чтобы NMS/Folia-исходники компилировались против сигнатур NeoForge.
3. **Runtime** — в jar вложен опубликованный NeoForge universal **21.1.248** (не чужой major).
4. **Coremods** — SPI `ICoreMod` (NeoForge 21.1): редирект main → `EturliaServer`, служебные трансформы.
5. **Совместимость** — модули `eturlia-compat-create` / `eturlia-compat-sable` (отдельно; закрывают region-bridges, не все API-дыры ядра).

Слой ядра сейчас закрывает не «FML не стартует», а **runtime gaps**: intrusive holders, Folia `entityTickList`, reload/ContextAware, Crafting/Rarity/FireBlock-API и т.д. Create / Moonlight / Sable ещё не полностью зелёные — это следующий слой (DeferredHolder timing, entity-section/refmap).

## Версии

| Компонент | Версия |
|-----------|--------|
| Minecraft | 1.21.1 |
| NeoForge | **21.1.248** |
| FancyModLoader | 4.0.43 |
| Paper upstream | `84281cee…` (ветка Folia `dev/1.21.1`) |
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
- Моды NeoForge → каталог `mods/`.
- Плагины Bukkit/Paper → только с `folia-supported: true`.
- **Не кладите** отдельный `spark-neoforge` в `mods/` — spark уже вшит в Folia (конфликт JPMS).

## Что уже проходит (smoke)

| Набор | Статус |
|-------|--------|
| Пустой сервер / лёгкий стек (Cloth, Curios, GeckoLib, JEI, …) | Mod List + SML + Folia `Done` |
| Farmers Delight (+ Cloth) | SML completed + `Done` |
| Create | частично: ядровые API закрываются, остаются DeferredHolder / регистры |
| Moonlight | частично: ContextAware / FireBlock; дальше Folia entity mixins / refmap |

## Архитектура репозитория

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

<details>
<summary><strong>English</strong> — click to expand</summary>

<div id="english"></div>

# English

> [!WARNING]
> Experimental. **Not for production.** Back up worlds. Many mods are incompatible with Folia region threading.

## What Eturlia is

**Eturlia** is a hybrid Minecraft server kernel: one fat jar that runs

- **[Folia](https://github.com/PaperMC/Folia)** — Paper fork with independent per-region ticking across CPU cores;
- **[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248** — NeoForge mod loading via FancyModLoader (FML 4.0.43).

The goal is Folia’s multi-core scaling with a real NeoForge mod pipeline, so Create-style packs can run as Folia↔NeoForge runtime gaps are closed.

| | |
|---|---|
| **Artifact** | `eturlia-1.21.1-neoforge-21.1.248.jar` |
| **Launch target** | `eturliaserver` |
| **Entry point** | `eturlia.EturliaServer` |
| **Release** | [v0.2.0](https://github.com/eturnercus/Core/releases/tag/v0.2.0) |

## How it works

```text
java -jar eturlia-….jar
        │
        ▼
 Eturlia launcher  ──►  extract nested libs (Folia, FML, NeoForge, runtime)
        │
        ▼
 ModLauncher / FML   ──►  scan mods/, bootstrap NeoForge
        │
        ▼
 Folia / Paper server ──►  regions, chunks, plugins (folia-supported)
        │
        ▼
 NeoForge hooks       ──►  RegisterEvent, lifecycle, datapacks, mixins
```

1. **Patched build** — paperweight applies `patches/api` + `patches/server` (Folia + NeoForge hooks) onto Paper.
2. **Shims** (`build-data/eturlia-neoforge-shims`) — stub APIs so Folia/NMS sources compile against NeoForge signatures.
3. **Runtime** — embeds published NeoForge universal **21.1.248**.
4. **Coremods** — `ICoreMod` SPI: main → `EturliaServer`, support transforms.
5. **Compat modules** — `eturlia-compat-create` / `eturlia-compat-sable` (optional; region bridges, not every core API gap).

The current core layer is about **runtime gaps** (intrusive holders, Folia `entityTickList`, ContextAware reload, crafting/rarity/fire APIs), not “FML never starts.” Create / Moonlight / Sable are not fully green yet.

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

## Run

```bash
java -jar build/libs/eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

Accept `eula.txt` on first boot. NeoForge mods → `mods/`. Plugins need `folia-supported: true`.  
**Do not** add `spark-neoforge` to `mods/` — spark is already bundled (JPMS conflict).

## Smoke status

| Set | Status |
|-----|--------|
| Empty / light stack (Cloth, Curios, GeckoLib, JEI, …) | Mod List + SML + Folia `Done` |
| Farmers Delight (+ Cloth) | SML completed + `Done` |
| Create | Partial — core APIs landing; DeferredHolder/registry gaps remain |
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
