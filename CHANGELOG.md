# Changelog

All notable changes to Eturlia. Dates are the day the work was measured on the test rig.

**Русская версия — [ниже по этому же файлу](#по-русски).**

---

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
