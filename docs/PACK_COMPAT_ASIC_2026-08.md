# Eturlia pack audit — ASIC list (2026-08-06)

**Ядро:** Eturlia v0.2.0 · MC 1.21.1 · NeoForge 21.1.248 · Folia  
**Конфиг ядра:** `config/eturlia.yml` (потоки, чанки, command blocks, region guard — с пояснениями)  
**Область:** проверка заявленного списка jars (без «оптимизаторов» как требования «обязаны работать»).  
**Важно:** «OK / скорее OK» = ожидаем **boot / load**, не гарантию region-safe геймплея навсегда. Folia режет мир на регионы — моды с глобальным тиком/AI часто деградируют под нагрузкой.

Легенда:

| Метка | Смысл |
|-------|--------|
| **OK** | Smoke/опыт ядра: должен грузиться |
| **OK\*** | Скорее загрузится; следить за runtime |
| **RISK** | Folia/region-unsafe или известные gaps — at your own risk |
| **BLOCK** | Ломает boot / конфликт с Eturlia — убрать |
| **CLIENT** | Клиентский jar — не в серверный `mods/` |
| **JUNK** | Бэкап / кривое имя / дубль — убрать |
| **OPT** | Оптимизатор — вне scope «должны работать» |

---

## Сначала: что выкинуть из `mods/` (обязательно)

| Файл | Почему |
|------|--------|
| `spark-1.10.124-neoforge.jar` | **BLOCK** — конфликт с bundled spark Eturlia |
| `arclight_sable_patch-1.1.1.jar` (оригинал Arclight) | **BLOCK** — заменить на `arclight_sable_patch-1.1.1-eturlia-shim.jar` |
| `*.jar1` | **JUNK** — не `.jar`, loader не подхватит нормально |
| `*.jar.bak*`, `*.prefix-*`, `*.bak-bottles*` | **JUNK** — бэкапы |
| дубль `letsdo-farm_and_charm-neoforge-1.1.22.jar` | **JUNK** — один экземпляр |
| два `corpse-…1.1.13` и `…1.1.17-fix3` | **JUNK** — оставить один |
| `worldedit-mod-7.3.8.jar1111` | **JUNK** — переименовать в `.jar` (нужен hotfix `0040`) |
| `ferritecore-7.0.3-neoforge.jar` | **OPT/RISK** — clinit at-your-own-risk |
| `packetfixer-3.3.1-…merged.jar` | **OPT** |
| `manas_queue-*.jar*` | **OPT/JUNK** — очереди/бэкапы |
| `chunkholdersafe-…` | **OPT\*** — может помочь Folia, не обязателен |

**Lithostitched / Terralith / Incendium** — больше не BLOCK: в v0.2.0+ патчи `0041`–`0042` (RegistryDataLoader 6-arg, Beardifier `$2`, `loadLevel()`). Берите **Lithostitched ≥ 1.7.13**.

**Sable:** убрать оригинальный `arclight_sable_patch`; поставить shim из релиза + `sable-neoforge`. Smoke **OK\*** (`Done` + все миры + тики) на патчах `0050`–`0066` — см. секцию Sable ниже.

---

## CLIENT — не класть на dedicated server

| Файл | Примечание |
|------|------------|
| `fogoverrides-…` | Клиентский туман |
| `sound-physics-remastered-…` | Клиентский звук |
| `clientsort-…` | Клиентский сорт инвентаря |
| `InventoryProfilesNext-…` / `libIPN-…` | Клиент (+ кривые `.jar1`) |
| `Emojiful-…` | Клиент UI |
| `Iceberg-…` | Клиентская lib |
| `ArmorPoser-…` | Обычно клиент |
| `xaeroworldmap-…` | Карта — клиент (серверу не нужна) |
| `wthit-…` | Подсказки блоков — клиент |
| `jei-…` | На выделенном сервере обычно не нужен |
| `konkrete_…` | Часто клиентская GUI-lib |
| `yet_another_config_lib_v3-…` | Часто тянут клиентские моды |
| `respackopts-…` / `libjf-…` | Чаще клиент resource-pack |

`emotecraft`, `voicechat`, `badpackets` — **серверные** компоненты есть; клиент всё равно нужен игрокам отдельно.

---

## BLOCK / высокий отказ boot

| Файл | Статус | Комментарий |
|------|--------|-------------|
| spark-neoforge | **BLOCK** | Дубль bundled spark |
| `arclight_sable_patch` (Arclight original) | **BLOCK** | Заменить shim’ом Eturlia |

---

## Lithostitched + Terralith + Incendium — **OK** (после ядерных патчей)

Smoke на Eturlia: `Done` + structure gen с `lithostitched-1.7.13` + Terralith 2.6.2 + Incendium 5.4.4.

| Что чинили в ядре | Патч |
|-------------------|------|
| Paper `Conversions` сломал `RegistryDataLoaderMixin` | `0041` — 6-arg `loadElementFromResource` + ThreadLocal |
| CraftBukkit `loadLevel(String)` vs Lithostitched `loadLevel()V` | `0041` |
| Paper убрал `lambda$forStructuresInChunk$2` (Beardifier) | `0042` |
| Пустой `lithostitched:template_list` → `TemplateLists.getRandom` NSEE | `0074` — NeoForge `Registries.elementsDirPath` → `CommonHooks.prefixNamespace` |
| Beta Lithostitched | `0073` — hard-block `< 1.7.13` / `*beta*` |

Рекомендация: **не** `1.7.10+beta4` — ставьте **≥ 1.7.13**.

**HARD BLOCK:** `lithostitched-1.7.10+beta4` — Eturlia откажется стартовать. Только **≥ 1.7.13**.


---

## Sable + Arclight patch — как завести на Eturlia

1. **Удалить** оригинальный `arclight_sable_patch-*.jar` (миксины под Arclight).
2. Положить **`arclight_sable_patch-1.1.1-eturlia-shim.jar`** из релиза (modId тот же, no-op).
3. Оставить `sable-neoforge-1.21.1-2.0.3.jar` (+ Create/Aeronautics по желанию).
4. Опционально: `eturlia-compat-sable` (region bridges).

Ядро уже даёт: FluidType height / `updateFluidHeightAndDoFluidPushing()`, `saveWithoutId` 1-arg, `lambda$stopSleeping$12`, `playBlockFallSound` local, vanilla `Level.<init>`, `ServerLevel.tick(BooleanSupplier)`, CraftWorld absurd-AABB guard (вместо Arclight rayTrace eject).

Доп. мосты для Sable 2.0.3 (`0050`–`0066`): vanilla `explode` + Holder, `save`/`saveLevelData`, DistanceManager/ChunkMap anchors, `LevelChunk.level` as `Level`, `GroundPathNavigation.createPath`, `tickServer(BooleanSupplier)`, Explosion/placeNewPlayer/respawn, `isPositionTickingEntity`, `unloadQueue`/`hasWork`, `Holder`→`IHolderExtension`, `TrackedEntity.updatePlayer` `position()`, `ChunkHolder.get*ChunkFuture` snapshots.

**Статус:** smoke sable+shim → **OK\*** (`Done`, overworld/nether/end init, тики без region fail). Геймплей на Folia-регионах всё ещё **RISK** (физика/sublevels) — не BLOCK.

---

## RISK — Folia region / Eturlia gaps (ожидаем проблемы в рантайме)

| Файл | Риск |
|------|------|
| `create-1.21.1-6.0.10.jar` | **RISK** — boot OK\* в smoke; tick/region gaps под нагрузкой |
| `create-aeronautics-bundled-…` + `aeronauticscompat-…` | **RISK** — поверх Create |
| `copycats-…` | **OK\*** / **RISK** с Create |
| `alexsmobs-…` + `citadel-…` | **RISK** — entity AI / глобальные тики |
| `geckolib-…` | **OK\*** как lib; риск от модов-потребителей |
| `easy_npc*` + `easy_npc_config_ui` + `easy_npc_bundle` | **RISK** — NPC / pathfinding / UI |
| `coroutil-…` | **RISK** — weather/AI util |
| `twilightforest-…` | **RISK** — dimension/структуры/боссы |
| `BetterEnd-…` + `bclib-…` + `worldweaver-…` + `wunderlib-…` | **RISK** — тяжёлый worldgen BCLib |
| `malum-…` + `lodestone-…` | **RISK** — сложная магия/эффекты |
| `dungeons+` / `dungeons-and-taverns` / YUNG\* | **OK\*** datapack-heavy; следить за генерацией |
| `YungsApi` + BetterDungeons + BetterNetherFortresses | **OK\*** |
| `travelersbackpack-…` | **RISK** — инвентарь/измерения |
| `curios-…` | **OK\*** / **RISK** с слотами на регионах |
| `horseman-…` / `weaponmaster_…` / `starcatcher-…` | **RISK** — combat/entity |
| `immersive_melodies-…` / `toomanypaintings-…` | **OK\*** |
| `quality_food-…` | **OK\*** (файл `.jar1` — починить имя) |
| `letmedespawn-…` | **RISK** — despawn логика vs регионы |
| `ibo-…` | **RISK** — уточнять назначение; часто инвазивный |
| `tf_dnv` / `tfsaplingdimlock` | **RISK** — Twilight/dim locks |
| `manascore` / `manas_queue` | **OPT/RISK** + кривые имена |
| `sim_fluid_assembly_fix` / `notebuns-farmcharm-fix` | **RISK** — кастомные фиксы, проверить автора |
| `cable_facades` | **OK\*** с Create-стеком |

---

## OK / OK\* — базовый стек и контент (после чистки BLOCK/JUNK/CLIENT)

| Файл | Статус |
|------|--------|
| `architectury-…` | **OK** |
| `cloth-config-…` | **OK** |
| `kotlinforforge-…` | **OK** (нужен многим Kotlin-модам) |
| `bookshelf-…` | **OK** |
| `prickle-…` | **OK** |
| `resourcefulconfig-…` / `resourcefullib-…` | **OK** |
| `mru-…` | **OK\*** |
| `CreativeCore_…` | **OK\*** |
| `moonlight-…` | **OK** (Eturlia bridges A) |
| `FarmersDelight-…` | **OK** |
| `amendments-…` / `supplementaries-…` / `beautify-…` | **OK\*** (Moonlight-экосистема) |
| `Almanac-…` / `anvianslib-…` | **OK\*** |
| `attributefix-…` | **OK\*** |
| `badpackets-…` | **OK** (часто для voice/emote) |
| `voicechat-neoforge-…` | **OK\*** — Simple Voice Chat; Folia заявлена у автора, проверить порт UDP |
| `emotecraft-…` | **OK\*** |
| `corpse-…` (один jar) | **OK\*** |
| `letsdo-*` (vinery, bakery, brewery, furniture, farm_and_charm ×1) | **OK\*** |
| `worldedit-mod-7.3.8.jar` (правильное имя) | **OK** после hotfix `0040` |
| `chunkholdersafe-…` | **OPT\*** — можно оставить |
| `packetfixer` / `ferritecore` | **OPT** — не требуем |

---

## Сводка по «должны работать»

**Да (после чистки списка):** libs (Architectury, Cloth, Kotlin, Bookshelf, Resourceful\*, Moonlight), Farmers Delight, Supplementaries/Amendments/Beautify стек, Let's Do\*, Voice Chat, Emotecraft, WorldEdit 7.3.8, базовые утилиты (AttributeFix, Almanac, …), **Lithostitched ≥1.7.13 + Terralith + Incendium**.

**Нет / пока нет:** spark-neoforge, оригинальный Arclight sable patch, клиентские jars, битые `.jar1`/`.bak`.

**At own risk / OK\* boot:** Create + Aeronautics, **Sable** (+ Eturlia shim; boot OK\*, region physics RISK), Alex's Mobs/Citadel, EasyNPC, Twilight Forest, BetterEnd/BCLib, Malum, тяжёлый dungeon-pack, curios-heavy, combat-моды.

Оптимизаторы (`ferritecore`, `packetfixer`, `manas_queue`, …) — **вне** требования «должны работать».

---

## Плагины Folia — что «все от Folia» значит на Eturlia

Eturlia = Folia API + ModLauncher. Плагины из `plugins/`:

1. **Обязательно** `folia-supported: true` в `plugin.yml` (иначе Folia откажет в загрузке).
2. **Пока избегать** `libraries:` в `plugin.yml` — Maven resolve под ModLauncher часто ломается (`ModelMerger` / `RepositorySystem == null`). Кладите зависимости shaded или вручную.
3. Плагин должен использовать **RegionScheduler / EntityScheduler / GlobalRegionScheduler**, не полагаться на один server thread.
4. Bundled **spark** уже в ядре — не ставить spark-plugin/spark-neoforge.
5. Smoke на ядре: `ChunkHeatMap` с `folia-supported` — load + enable OK.

| Ожидание | Реальность |
|----------|------------|
| «Любой Folia-плагин с Hangar» | **Нет** — только с `folia-supported` и без сломанного `libraries:` |
| FAWE / WorldEdit Bukkit | Можно **вместо** или рядом с NeoForge WE; нужен Folia-build |
| LuckPerms Folia, VoiceChat Bukkit-порт | Обычно OK если помечены Folia |
| Плагины «Paper but not Folia» | **Не загрузятся** |

Чеклист перед продом плагинов:

```text
[ ] plugin.yml → folia-supported: true
[ ] нет libraries: ИЛИ зависимости уже в jar
[ ] нет синхронного доступа к чужим чанкам/entity с region thread
[ ] нет конфликта с NeoForge-модом того же функционала (два WE, два spark, …)
```

---

## Минимальный «зелёный» серверный набор из вашего списка

Оставить в `mods/` (пример):

- architectury, cloth-config, kotlinforforge, bookshelf, prickle  
- resourcefulconfig, resourcefullib, mru, CreativeCore  
- moonlight, FarmersDelight, amendments, supplementaries, beautify, Almanac, anvianslib  
- attributefix, badpackets, voicechat, emotecraft  
- corpse (один), letsdo-\* (без дублей), worldedit-mod-7.3.8.jar  
- опционально: curios, quality_food (починить имя), YUNG\* + dungeons datapacks  

Убрать всё из таблиц **BLOCK / CLIENT / JUNK / spark-neoforge / Lithostitched-стек / Arclight**.

Create / Alex / EasyNPC / TF / BetterEnd / Malum — только если готовы к **RISK** и бэкапам.
