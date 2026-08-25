# Changelog

All notable changes to Eturlia. Dates are the day the work was measured on the test rig.

**Русская версия — [ниже по этому же файлу](#по-русски).**

---

## 2.1.2 — 2026-08-25

Two guards that were refusing a mod access to its own data, and a test that can finally tell whether
an airship flies.

### Fixed

- **Folia refused sable's writes into a physics sub-level.** Create Aeronautics keeps a sub-level in
  a plot of its own, twenty million blocks out, and the blocks there are the mod's data structure
  rather than chunks the server loaded. Folia's ownership checks still fired on the coordinates, so
  `/sable spawn block` died with `IllegalStateException: Updating block asynchronously` and
  `/sable assemble connected` reported success while every block failed with
  `Failed to mark & notify block ... NullPointerException`. A position inside a sub-level plot is
  now writable from a region thread, because a sub-level is driven by its holder and the operations
  on one plot arrive from that holder's region, one at a time.
  `-Deturlia.compat.sublevel-plots=strict` restores Folia's refusal.
- **The same for loading a plot's chunks.** With the writes allowed, the next guard in the chain
  answered `IllegalStateException: Cannot asynchronously load chunks`. The earlier relaxation only
  covered chunks *no* region owns, and a plot does have a region over it. Both exceptions are gone:
  `/sable spawn block` now runs clean.
- **`Level.getChunk` asks the chunk source for a chunk a mod may be serving.** Sable answers for its
  plot chunks by injecting into `getChunkNow`, which is the documented seam, but `Level.getChunk`
  never went through it — the loaded-chunk fast path has nothing for a plot, and the fall-through
  tries to load a chunk that does not exist. It now asks `getChunkNow` between the two. Ordinary
  chunks still take the fast path first and are unaffected.
  `-Deturlia.compat.virtual-chunks=strict` restores the old lookup.

### Test harness

- New `eprobe sublevels [seconds]`, `eprobe track <type> …` and `tools/flight*.sh`. An airship is not
  an entity, so nothing in Bukkit can see one; `eprobe sublevels` reads sable's own container and
  reports each sub-level's position twice, so "did it fly" has an answer that is not a guess.
- Both of those had to bind sable by **exact signature** through `MethodHandles`.
  `SubLevelContainer.getContainer` is overloaded on `ClientLevel`, and merely enumerating the
  class's methods on a server throws `NoClassDefFoundError`. The first version of the core fix hit
  this too and silently disabled itself — it looked like the fix simply had not worked.

### Still not flying, and where it now stops

A sub-level is created and then removed the same tick:
`Sub-level ServerSubLevel[...] has an extreme Y coordinate range, removing`. Both Folia refusals are
gone and the command no longer errors, so what remains is inside sable's own bounds handling, which
cannot be diagnosed further from bytecode. This is honest progress rather than a fix: the server no
longer blocks the mod, and the mod still does not finish the job.

## 2.1.1 — 2026-08-25

A boot printed about 180 error lines. It now prints 32, and none of the ones that went away were
hiding anything.

### Fixed

- **A nested loot table entry may still say `name`.** 1.21 renamed that field to `value`; mods
  shipping loot data written for 1.20 still say `name`, and the whole table failed to parse — so the
  block dropped **nothing at all**. beautify's trellises are the case that found it: every flowering
  variant dropped air. The field is read as `value`, falling back to `name`, and is always written
  as `value`, so the old spelling is accepted and never produced.
  `-Deturlia.compat.loot-alias=strict` restores the 1.21-only field.

### Changed

- **Loot data naming content this server does not have is summarised, not shouted.**
  letsdo-furniture ships loot tables for grandfather clocks it never registers — the id is in
  neither the block registry nor the item registry — which cost 143 `ERROR` lines every boot for
  drops that cannot be lost, because the block does not exist either. That class is now counted and
  named once per namespace, with the first id as evidence. Anything that is *not* an unknown
  registry key is still reported line by line exactly as vanilla reports it.

### Test harness

- New `eprobe item <id...>`: says whether an id is in the block registry, the item registry, both or
  neither. This is the question that separates "the core lost the item" from "the mod never
  registered one", and it is what proved the furniture and supplementaries errors were dead data
  rather than registry loss.

### Is the core losing registry entries? No — and here is the evidence

"A mod ships data for content it never registered" is a comfortable answer, so it was checked
rather than assumed. `eprobe registryaudit` reads what every installed mod declares in its own
resources and asks the server's block and item registries for each id.

| | |
|---|---|
| **Create** | **0 of 643 blocks missing.** Its 4 "missing items" are `clipboard_0/1/2` and `package_transforms` — model files, not items |
| **Create: Aeronautics** (`aeronautics`, `simulated`, `offroad`) | **0 of 158 ids missing** — 122 registered as both block and item, 19 blocks without a `BlockItem`, 17 item-only |
| Everything else | 237 blocks are declared and not registered, in five namespaces |

Those 237 fall into two groups, and neither is registry loss:

- **Variants of a block that *is* registered.** `betterend:hydralux_petal_block` is present; its 69
  colour variants are blockstate models of that one block. `supplementaries:candle_holder` is
  present; the cupric/ender/soul/spectacle ones are cross-mod variants. `vinery:apple_juice` is an
  item, and its blockstate is the model for a placed drink.
- **Conditional cross-mod content.** furniture's 146 group cleanly by wood type — arolla pine,
  ebony, cypress, baobab, chestnut, palm — none of which are in this pack, because furniture
  registers per wood only when that wood's mod is installed.

A caveat worth stating: the audit reads `blockstates/` and `models/item/` as its evidence, and both
contain files that were never registry entries — armor trim models (290 of the raw item count),
damage-state variants, entity and GUI models. It gives an upper bound on what is missing, never a
list of losses. The lang-file cross-check narrows it: of 291 missing blocks, 54 are never given a
translation key at all, which is what a technical or model-only block looks like.

### The 32 errors a boot still prints

Third-party data naming content that is not installed — supplementaries ships advancements for
`immersive_weathering`, which is not in the pack at all; a data map names `artifacts:anglers_hat`,
which does not exist. Vanilla prints the same lines for the same data. They stay visible: a
suppression broad enough to catch them would be broad enough to hide a real one.

The warnings were read too. The 79 `Discarding @Unique` lines from copycats are Mixin doing its job
— the mod adds a method to a base class that one of its own subclasses already declares — and
appear on any loader.

## 2.1 — 2026-08-25

The release a live tester forced. Every fix below answers something a player could see and the
previous test suite could not: it asserted on what the server *said*, never on what a player *does*.

### Fixed — players were being disconnected

- **Modded entity data serializers were numbered wrongly, and any modded mob could kick a player.**
  NeoForge puts modded serializers in a registry of its own and sends them as `registry id + 256`,
  leaving vanilla's table alone below 256; its clients decode with the same rule. Eturlia was
  appending them to vanilla's table instead, so `create:carriage_data` went out as id 65 where the
  client expected 290, and the connection died with
  `DecoderException: Unknown serializer type 48`. The rule is now implemented in
  `SynchedEntityData`, where the wire id is actually chosen, and vanilla's table is left as vanilla
  built it. Verified against all 348 registered entity types with a real client watching.

- **One unserialisable recipe kicked every player who joined.** A datapack recipe with an empty
  result made its serializer throw `Empty ItemStack not allowed`, and the whole
  `clientbound/minecraft:update_recipes` packet — sent on join — died with it. Each recipe is now
  written to a scratch buffer once and only the ones that survive are sent; the rest are named in
  the log.

- **A packet the server could not encode took the player with it.** Every mod payload travels the
  same pipe, so one bad payload (`create:package_destroyed` carrying an empty `ItemStack`) ended the
  session. An outbound encode failure now drops that packet and logs the whole cause chain; netty
  has already released the buffer, so nothing half-written reaches the socket. A packet that cannot
  be *decoded* still disconnects — that one is malformed input from outside.

### Fixed — mods could not bind to the core

- **`RegistryAccess.holderOrThrow` answered the wrong type.** NeoForge declares it returning
  `Holder<T>`; Eturlia returned `Holder.Reference<T>`. A return type is part of the descriptor, so
  every mod calling it died with `NoSuchMethodError` the first time that line ran.

- **`Entity.handlePortal()` answered the wrong type.** Folia had widened it to `public boolean` so
  `ServerLevel` could tell whether the entity went through. Mods inheriting it called the `void`
  form and got `NoSuchMethodError`. Folia's answer now lives under its own name and `handlePortal()`
  is `void` again.

- **`Level` was missing NeoForge's block-snapshot fields** (`captureBlockSnapshots`,
  `restoringBlockSnapshots`, `capturedBlockSnapshots`), so the first mod to touch one died with
  `NoSuchFieldError`. They are present and inert: a mod that turns capture on reads back an empty
  list, exactly what it would see if nothing had happened.

- **Modded hanging entities took the region down.** Paintings and item frames from mods got the
  generic wrapper, and CraftBukkit casts them to `Hanging` on tick and on block break. Added to the
  vanilla-ancestor ladder.

### Changed

- A disconnect caused by an encoder or decoder fault now logs the exception with its full cause
  chain. Before this, one truncated line was the only trace, and the stack went to
  `logs/eturlia-noise.log`, which nothing read.

### Test harness

- **The keyboard canary could never pass.** It asserted on `/say`, whose text this build drops from
  the log — the only line carrying the marker was the `issued server command` echo the check
  deliberately excludes. Every `aerotest` run died at the gate no matter what the client was doing.
  It now changes a block and looks for the command feedback, which *is* logged in full.
- New `tools/sertest.sh`: joins a real client, spawns every registered entity type beside it, and
  reads the client's own log back. This is what reproduced the tester's kick.
- New `eprobe levels`: samples each level's game time **on the region thread** and reports the ticks
  it advanced. Sampling off-thread reads a frozen fallback and reports every level as stalled.
- `eprobe entities` skips types a bare `create()` cannot build well enough for a client to tick
  (`create:potato_projectile` reads its ammunition type on the first client tick and crashes the
  client when summoned empty — a player cannot reach that state).

### Known, diagnosed, not fixed

- **A Create: Aeronautics airship does not move.** Sable maps its physics sub-levels into the same
  world about 20.5 million blocks out, so they get Folia regions of their own, and then drives them
  from the *player's* region thread. Folia refuses that write — correctly; two regions tick in
  parallel. Sable catches the refusal per block and logs `Failed to mark & notify block`, so the
  contraption is assembled and then never driven.

  Handing the write to the region that owns it was tried and reverted: the queue reaches the right
  thread, but a tick later sable's plot holder is gone and its own mixin throws
  `Cannot change blocks in nonexistent plot holder`, which escapes into the region tick and stops
  the server. Slower and wrong is worse than refused and honest. A real fix has to come from one of
  two places — sable driving its sub-level from that sub-level's own region, or the regioniser being
  told the two areas are one region. Neither is a guard tweak.

---

## 2.0 — 2026-08-14

First published release. Folia's regionised engine running a NeoForge modpack and a Bukkit plugin
set unmodified, with the compatibility layer generated by one idempotent script.

- Modded entities get a Bukkit wrapper specific enough to survive CraftBukkit's casts — the nearest
  vanilla ancestor's converter, with an `instanceof` ladder behind it. 348 of 348 handled.
- Modded menus can be opened: `AbstractContainerMenu.getBukkitView()` has a working default, so the
  128 modded menu classes in the pack no longer throw `AbstractMethodError`.
- A new world is built from a vanilla preset again. A missing `level-type` fell through to
  `iregistry.holders().findAny()`, which on this pack picked a flat airship testbed — every world
  ever created here was flat.
- `Entity`'s hard-colliding flag is computed in a field initialiser that can throw; it now falls
  back instead of failing construction.
- Chunks no region owns can be loaded by whoever asks, which is what a sub-level a mod just built
  always is.

---

# По-русски

## 2.1.2 — 25.08.2026

Две проверки, запрещавшие моду доступ к его собственным данным, и тест, который наконец способен
сказать, летит ли дирижабль.

### Исправлено

- **Folia запрещала sable запись в физический под-уровень.** Create Aeronautics держит под-уровень в
  своём участке в двадцати миллионах блоков, и блоки там — структура данных мода, а не загруженные
  сервером чанки. Проверки владения срабатывали по координатам, поэтому `/sable spawn block` падал с
  `Updating block asynchronously`, а `/sable assemble connected` рапортовал успех, пока каждый блок
  падал с `Failed to mark & notify block ... NullPointerException`. Теперь позиция внутри участка
  под-уровня доступна для записи с потока региона. `-Deturlia.compat.sublevel-plots=strict` вернёт
  отказ.
- **То же для загрузки чанков участка** — следующая проверка отвечала `Cannot asynchronously load
  chunks`. Прежнее послабление покрывало только чанки, которыми **не владеет** ни один регион.
  Обоих исключений больше нет.
- **`Level.getChunk` спрашивает источник чанков о чанке, который может отдавать мод** — sable
  отвечает за свои чанки через `getChunkNow`, но `Level.getChunk` туда не заходил.
  `-Deturlia.compat.virtual-chunks=strict` вернёт прежний поиск.

### Всё ещё не летит — и где именно останавливается

Под-уровень создаётся и тут же удаляется: `has an extreme Y coordinate range, removing`. Обе
блокировки Folia сняты, команда больше не даёт ошибок, а остаток — внутри обработки границ самого
sable. Это честный прогресс, а не починка: сервер больше не мешает моду, а мод всё ещё не доводит
дело до конца.

## 2.1.1 — 25.08.2026

Загрузка печатала около 180 строк ошибок. Теперь 32, и ни одна из исчезнувших ничего не скрывала.

### Исправлено

- **Вложенная таблица лута может всё ещё называть поле `name`.** В 1.21 его переименовали в
  `value`; моды с данными, написанными под 1.20, пишут `name`, и вся таблица не разбиралась —
  то есть блок не дропал **вообще ничего**. Нашлось на шпалерах beautify: каждый цветущий вариант
  дропал воздух. Поле читается как `value` с откатом на `name`, а пишется всегда как `value`.
  `-Deturlia.compat.loot-alias=strict` возвращает поведение только-1.21.

### Изменено

- **Данные лута, ссылающиеся на отсутствующий контент, сводятся в одну строку.** letsdo-furniture
  везёт таблицы лута для напольных часов, которые не регистрирует — идентификатора нет ни в реестре
  блоков, ни в реестре предметов — и это стоило 143 строк `ERROR` за загрузку ради дропа, который
  невозможно потерять, потому что и блока нет. Теперь класс считается и называется один раз на
  пространство имён. Всё, что **не** является неизвестным ключом реестра, по-прежнему выводится
  построчно, как в ванили.

### Тестовая оснастка

- Новое `eprobe item <id...>`: есть ли идентификатор в реестре блоков, предметов, обоих или ни в
  одном. Это вопрос, отделяющий «ядро потеряло предмет» от «мод его не зарегистрировал».
- Новое `eprobe registryaudit`: читает всё, что каждый установленный мод объявляет в своих ресурсах,
  и спрашивает реестры блоков и предметов про каждый идентификатор.

### Теряет ли ядро записи реестра? Нет — и вот доказательство

- **Create: не потеряно ни одного блока из 643.** Четыре «пропавших предмета» — `clipboard_0/1/2` и
  `package_transforms`, это файлы моделей, а не предметы.
- **Create: Aeronautics** (`aeronautics`, `simulated`, `offroad`): **не потеряно ни одного из 158**
  идентификаторов — 122 есть и как блок, и как предмет, 19 блоков без `BlockItem`, 17 только предмет.
- Остальные 237 «пропавших» блоков — это либо варианты **зарегистрированного** блока
  (`betterend:hydralux_petal_block` на месте, отсутствуют лишь 69 его цветовых вариантов —
  это blockstate-модели одного блока; `supplementaries:candle_holder` на месте), либо условный
  межмодовый контент: 146 у furniture чётко группируются по породам дерева, которых в сборке нет.

Оговорка: аудит считает доказательством `blockstates/` и `models/item/`, а там лежат и файлы, которые
никогда не были записями реестра — модели тримов брони (290 из сырого счёта предметов), варианты
повреждений, модели сущностей и интерфейса. Это верхняя оценка, а не список потерь.

## 2.1 — 25.08.2026

Релиз, который вынудил живой тестер. Каждое исправление ниже отвечает на то, что видит игрок и чего
не видел прежний набор тестов: он проверял, что **говорит сервер**, а не что **делает игрок**.

### Исправлено — игроков выкидывало с сервера

- **Идентификаторы модовых entity data serializer нумеровались неверно, и любой модовый моб мог
  выкинуть игрока.** NeoForge держит их в своём реестре и отправляет как `id реестра + 256`, не
  трогая ванильную таблицу ниже 256; клиент декодирует по тому же правилу. Eturlia дописывала их в
  ванильную таблицу, поэтому `create:carriage_data` уходил под номером 65 там, где клиент ждал 290,
  и соединение падало с `DecoderException: Unknown serializer type 48`. Правило теперь реализовано в
  `SynchedEntityData` — там, где номер и выбирается, — а ванильная таблица остаётся нетронутой.
  Проверено на всех 348 зарегистрированных типах сущностей с живым клиентом.

- **Один несериализуемый рецепт выкидывал каждого, кто заходил.** Рецепт из датапака с пустым
  результатом бросал `Empty ItemStack not allowed`, и вместе с ним умирал весь пакет
  `clientbound/minecraft:update_recipes`, который отправляется при входе. Теперь каждый рецепт
  один раз пишется в буфер-черновик, отправляются только уцелевшие, остальные названы в логе.

- **Пакет, который сервер не смог закодировать, уносил игрока с собой.** Все модовые payload идут
  одной трубой, поэтому один плохой (`create:package_destroyed` с пустым `ItemStack`) обрывал
  сессию. Теперь исходящая ошибка кодирования роняет пакет и пишет всю цепочку причин; netty к этому
  моменту уже освободил буфер, так что в сокет не уходит ничего недописанного. Пакет, который не
  удалось **раскодировать**, по-прежнему рвёт соединение — это уже мусор снаружи.

### Исправлено — моды не могли связаться с ядром

- **`RegistryAccess.holderOrThrow` возвращал не тот тип.** NeoForge объявляет `Holder<T>`, Eturlia
  возвращала `Holder.Reference<T>`. Тип возврата входит в дескриптор, поэтому любой мод падал с
  `NoSuchMethodError` на первой же такой строке.
- **`Entity.handlePortal()` возвращал не тот тип.** Folia расширила его до `public boolean`. Моды
  зовут `void`-форму. Ответ для Folia теперь живёт под своим именем, а `handlePortal()` снова `void`.
- **В `Level` не было полей block snapshot из NeoForge** — первый же мод, который их трогает, падал с
  `NoSuchFieldError`. Поля на месте и инертны.
- **Модовые висящие сущности роняли регион** — картины и рамки получали общую обёртку, а CraftBukkit
  приводит их к `Hanging`. Добавлены в лестницу ванильных предков.

### Известно, разобрано, не исправлено

- **Дирижабль Create: Aeronautics не летит.** Sable размещает свои физические под-уровни в том же
  мире примерно в 20,5 млн блоков, поэтому у них появляются собственные регионы Folia, и управляет
  ими с потока региона **игрока**. Folia такую запись запрещает — и правильно: два региона тикают
  параллельно. Sable ловит отказ на каждом блоке и пишет `Failed to mark & notify block`, поэтому
  конструкция собирается и дальше ничем не приводится в движение.

  Передачу записи в регион-владелец пробовали и откатили: очередь доходит до нужного потока, но
  тиком позже plot holder у sable уже нет, и его собственный mixin бросает
  `Cannot change blocks in nonexistent plot holder` — прямо в тик региона, что останавливает сервер.
  Медленно и неверно хуже, чем честный отказ. Настоящее решение — либо sable ведёт под-уровень из
  региона самого под-уровня, либо регионизатору сообщают, что эти две области — один регион.
