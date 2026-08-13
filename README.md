# eturlia-core

> **A fork of [eturnercus/Core](https://github.com/eturnercus/Core).** The history is shared:
> everything up to `4e166a6` comes from there. The repository was created separately rather than with
> the Fork button, so GitHub does not draw the connection on its own — it is declared here, in the
> repository description, and in the [upstream notice](https://github.com/eturnercus/Core/issues/31).
>
> Upstream is itself a fork of [PaperMC/Folia](https://github.com/PaperMC/Folia) carrying the
> [NeoForge](https://github.com/neoforged/NeoForge) 21.1.248 mod loader; the changes on top of Folia
> live in `patches/` as a paperweight patch tree. Upstream licences are kept: `PATCHES-LICENSE`,
> `folia-server/LICENCE.txt`, `folia-api/LICENCE.txt`.

A Minecraft 1.21.1 server core where Folia's regionised threading and the NeoForge mod loader live in
one jar. It builds to `eturlia-1.21.1-neoforge-21.1.248.jar`, starts like any other server, and reads
`mods/` and `plugins/` at the same time.

---

## One decision, and everything else follows from it

A mod written for a single-threaded server crashes on Folia. A plugin without `folia-supported`
refuses to load. The obvious route is to patch every mod and every plugin for regions; on a pack of
86 mods that route ends at the first pack update.

This project took the other one: **the core absorbs the incompatibility**. Not one mod and not one
plugin in `mods/` or `plugins/` is modified — they sit there exactly as their authors shipped them.
Every fix lives in the core and closes a *class* of failure rather than naming a mod.

The test a change has to pass: *if a different mod hits the same error tomorrow, does it work without
a new patch?* If not, it is not a fix.

## Where it stands

| | |
|---|---|
| Startup | `Done (10.9s)` — 86 mods, 38 plugins enabled |
| Alarming lines in a boot | 2, both third-party plugins (ImageFrame, KartaAutoAnnouncer) |
| Mod classes that cannot bind to the core | 0 outside datagen (`tools/finalscan.py`, 87 jars, 36 196 classes) |
| Plugin commands registered | 224 of 225 declared |
| Vanilla commands present | 48 of 48 that exist in 1.21.1 |
| Create machinery | a bearing driven through a shaft chain assembles and lifts its blocks |
| Regions | tick in parallel, one thread per region |

The full account is [`docs/FIXES.md`](docs/FIXES.md), organised by class of failure with the
mechanism of each. Current state and open threads: [`docs/HANDOFF.md`](docs/HANDOFF.md).

> [!WARNING]
> Experimental core. Keep world backups. Some mods assume a single server thread and behave on
> regions in ways their authors did not intend.

## Build

```bash
./gradlew applyPatches
python3 scripts/apply_compat_layer.py
./gradlew :folia-server:eturliaStandaloneJar
```

The order is mandatory. `applyPatches` unpacks the source tree from `patches/`,
`apply_compat_layer.py` writes the compatibility layer on top, and only then is the jar built. The
script is idempotent — running it again prints `already applied` and changes nothing.

`tools/cycle.sh` runs that whole loop plus deploy, restart and a graded boot, one line per step.

## Run

```bash
java -Xms2G -Xmx8G -jar eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

The launcher unpacks its bundled libraries into `eturlia-libraries/` beside itself and hands over to
ModLauncher. From there it is an ordinary server: `mods/`, `plugins/`, `server.properties`, and the
Paper and Folia configs where you expect them.

## Compatibility switches

Every plane of the layer has a JVM flag. `strict` always means "give stock Folia behaviour back",
which is how you find out whose behaviour you are looking at.

| Flag | Default | What it does |
|---|---|---|
| `-Deturlia.compat.mixins` | `soft` | a mod's failed injector stops being fatal; the broken mixin is dropped by name and the class is transformed again from a clean copy |
| `-Deturlia.compat.modloading` | `lenient` | errors in the loading issue list do not stop startup; each mod's event bus is wrapped |
| `-Deturlia.compat.registries` | `lenient` | frozen registries reopen for late registration |
| `-Deturlia.compat.plugins` | `true` | drops the `folia-supported` gate; the legacy `BukkitScheduler` runs off the global tick |
| `-Deturlia.compat.folia-stubs` | `lenient` | `getTickCount()` answers with the global region's tick; `execute`/`tell`/`executeBlocking` schedule instead of throwing |
| `-Deturlia.compat.bukkit-types` | `lenient` | a modded entity reads as `UNKNOWN` to plugins and a modded block as `Material.STONE`, instead of throwing |
| `-Deturlia.compat.folia-commands` | `lenient` | re-registers the 17 vanilla commands Folia comments out (`/scoreboard`, `/team`, `/tag`, `/data`, `/clone`, `/function`, `/loot`, `/ride`, `/schedule`, `/spreadplayers`, `/datapack`, `/bossbar`, `/item`, `/trigger`, `/spectate`, `/teammsg`, `/return`) |
| `-Deturlia.compat.plugin-remap` | `true` | Spigot→Mojang remapper for plugins; a jar with classes this JVM cannot load is retried without them |
| `-Deturlia.compat.quarantine` | — | ids of mods to skip at load |
| `-Deturlia.lithostitched.allow-unsafe` | off | answers Lithostitched's version gate once instead of a refusal block every boot |

## Testing a build

```bash
python3 tools/logcheck.py     # grade the boot that just happened
python3 tools/finalscan.py --jar core/Folia-Server/build/eturlia/folia-server-neoforge-at.jar
python3 tools/modsweep.py     # sweep plugin commands, entities, blocks, worldgen from the console
```

* **`logcheck.py`** groups every WARN/ERROR in `latest.log`, hides the groups already judged benign
  (each pattern carries its reason), and reports what is *new* since the last run. Around 400 lines
  in 40 kinds is a normal boot for this pack.
* **`finalscan.py`** never starts the server. It reads the compiled core against every mod jar
  (nested jarjar included) and reports the two ways a mod cannot bind: overriding a method Paper
  sealed `final` (`IncompatibleClassChangeError` at class load) and implementing an interface
  CraftBukkit has added an abstract method to (`AbstractMethodError` on first call).
* **`modsweep.py`** drives the pack from the console: `/bukkit:help` for every command every plugin
  declares, `/summon` for a random sample of modded entity types, `/setblock` and read-back for
  modded blocks, `/place feature` for modded worldgen, and a batch of modded block entities left to
  tick while it watches for exceptions.

Harness traps, each of which has cost an hour:

* Folia refuses entity selectors from the console — it runs on the global region. **Repeating command
  blocks do not fire on this build either**, so anything selector-shaped needs a real player.
* `screen -X stuff` parses quotes, so a console command sent that way must not contain double quotes.
* The headless test client stalls for more than 30 seconds building the world, and the server drops
  it on the netty read timeout — which is not the configurable keepalive. `tools/clienttest3.sh`
  therefore checks the client is still connected before every keystroke and stops when it is not;
  trust nothing it prints after a `!!` line.

## Repository layout

```
patches/server/        paperweight patches over Folia; 0095+ are ours
scripts/
  apply_compat_layer.py   the compatibility layer generator, the single source of truth
  selftest.sh             a quick check without the full classpath
  check-patches.py        structural validation of the patch tree
build-data/
  eturlia-core/           runtime: eturlia.core.* and eturlia.launch.*
  eturlia-launcher/       the launcher and its library unpacking
  eturlia-server-templates/  eturlia.EturliaServer, the entry point
tools/                  build loop, log grading, static scan, gameplay sweeps
docs/                   FIXES.md, HANDOFF.md and the release history
```

## Adding a fix

The layer is not a pile of ad-hoc edits; it is a list of planes in `scripts/apply_compat_layer.py`:

```python
def install_my_plane():
    """One line on the class of failure this closes."""
    print("my plane")
    replace(
        SERVER + "/net/minecraft/.../Something.java",
        "<the text that is in the file right now, verbatim>",
        "<the replacement, marked // Eturlia start ... end>",
        "a short label for the log",
    )
```

`replace()` applies an edit exactly once and recognises one already applied, so the script can be run
any number of times. The anchor has to be copied out of the file verbatim; otherwise the script stops
with `!! anchor missing`, which is the correct behaviour.

A new file under `Folia-Server/src/main/java` overrides the decompiled vanilla one — that is how
`LootContext`, `RecipeBookType`, `RecipeBookSettings`, `BuiltInPackSource` and `HangingEntity` got
into the tree, all of them classes NeoForge adds methods to that Folia's copy does not have.

## Origin

A fork of [Folia](https://github.com/PaperMC/Folia) (itself a fork of Paper) carrying
[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248 / FancyModLoader 4.0.43. Upstream licences
are kept: `PATCHES-LICENSE`, `folia-server/LICENCE.txt`, `folia-api/LICENCE.txt`.

---
---

# eturlia-core (по-русски)

> **Это форк [eturnercus/Core](https://github.com/eturnercus/Core).** Общая история сохранена:
> коммиты до `4e166a6` — оттуда. Репозиторий заведён отдельно, не кнопкой Fork, поэтому GitHub не
> рисует связь автоматически — она заявлена здесь, в описании репозитория и в
> [заявке в апстрим](https://github.com/eturnercus/Core/issues/31).
>
> Апстрим сам является форком [PaperMC/Folia](https://github.com/PaperMC/Folia) с загрузчиком
> [neoforged/NeoForge](https://github.com/neoforged/NeoForge) 21.1.248; изменения поверх Folia
> лежат в `patches/` как дерево патчей paperweight. Лицензии сохранены: `PATCHES-LICENSE`,
> `folia-server/LICENCE.txt`, `folia-api/LICENCE.txt`.

Серверное ядро Minecraft 1.21.1, в котором региональная многопоточность Folia и модлоадер
NeoForge живут в одном jar. Собирается в `eturlia-1.21.1-neoforge-21.1.248.jar`, запускается как
обычный сервер, читает `mods/` и `plugins/` одновременно.

## Одно решение, из которого следует всё остальное

Мод, написанный под однопоточный сервер, на Folia падает. Плагин, не помеченный
`folia-supported`, не грузится. Очевидный путь — патчить каждый мод и каждый плагин под регионы.
На сборке из 86 модов этот путь заканчивается на первом же обновлении пака.

Здесь выбран другой: **несовместимость поглощает ядро**. Ни один мод и ни один плагин в `mods/` и
`plugins/` не изменён — они лежат ровно такими, какими их выложил автор. Каждое исправление живёт
в ядре и закрывает **класс** поломки, а не называет мод по имени.

Критерий отбора: *«если завтра поставить другой мод с той же ошибкой — он заработает без нового
патча?»* Если нет — это не исправление.

## Где это сейчас

| | |
|---|---|
| Старт | `Done (10.9s)` — 86 модов, 38 плагинов |
| Тревожных строк за загрузку | 2, обе — сторонние плагины (ImageFrame, KartaAutoAnnouncer) |
| Классов модов, не стыкующихся с ядром | 0 вне датагена (`tools/finalscan.py`, 87 jar, 36 196 классов) |
| Команд плагинов зарегистрировано | 224 из 225 объявленных |
| Ванильных команд на месте | 48 из 48, существующих в 1.21.1 |
| Механизмы Create | подшипник через цепочку валов собирает контрапцию и поднимает блоки |
| Регионы | тикают параллельно, по потоку на регион |

Полный разбор — [`docs/FIXES.md`](docs/FIXES.md), по классам поломок, с механизмом каждой.
Текущее состояние и открытые нити — [`docs/HANDOFF.md`](docs/HANDOFF.md).

> [!WARNING]
> Экспериментальное ядро. Делайте резервные копии мира. Часть модов рассчитана на один серверный
> поток и на регионах работает иначе, чем задумывал автор.

## Собрать

```bash
./gradlew applyPatches
python3 scripts/apply_compat_layer.py
./gradlew :folia-server:eturliaStandaloneJar
```

Порядок обязателен: `applyPatches` разворачивает дерево исходников из `patches/`,
`apply_compat_layer.py` дописывает поверх слой совместимости, и только потом собирается jar.
Скрипт идемпотентный — повторный запуск печатает `already applied` и ничего не ломает.
`tools/cycle.sh` прогоняет весь цикл целиком: сборка, деплой, рестарт, оценка загрузки.

## Запустить

```bash
java -Xms2G -Xmx8G -jar eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

Лаунчер распакует вложенные библиотеки в `eturlia-libraries/` рядом с собой и передаст управление
ModLauncher. Дальше это обычный сервер: `mods/`, `plugins/`, `server.properties`, конфиги Paper и
Folia на своих местах.

## Переключатели слоя совместимости

`strict` везде означает «вернуть штатное поведение Folia» — удобно, когда нужно понять, чьё
поведение вы наблюдаете.

| Флаг | По умолчанию | Что делает |
|---|---|---|
| `-Deturlia.compat.mixins` | `soft` | упавший инжектор мода перестаёт быть фатальным; сломанный миксин снимается по имени, класс трансформируется заново с чистой копии |
| `-Deturlia.compat.modloading` | `lenient` | ошибки в issue-листе загрузки не останавливают старт; шина событий каждого мода оборачивается |
| `-Deturlia.compat.registries` | `lenient` | замороженные реестры открываются для поздней регистрации |
| `-Deturlia.compat.plugins` | `true` | снимается гейт `folia-supported`, легаси `BukkitScheduler` крутится от глобального тика |
| `-Deturlia.compat.folia-stubs` | `lenient` | `getTickCount()` отвечает тиком глобального региона; `execute`/`tell`/`executeBlocking` раскладывают задачу вместо исключения |
| `-Deturlia.compat.bukkit-types` | `lenient` | модовая сущность видна плагинам как `UNKNOWN`, модовый блок — как `Material.STONE`, вместо броска |
| `-Deturlia.compat.folia-commands` | `lenient` | возвращает 17 ванильных команд, закомментированных в Folia (`/scoreboard`, `/team`, `/tag`, `/data`, `/clone`, `/function`, `/loot`, `/ride`, `/schedule`, `/spreadplayers`, `/datapack`, `/bossbar`, `/item`, `/trigger`, `/spectate`, `/teammsg`, `/return`) |
| `-Deturlia.compat.plugin-remap` | `true` | ремаппер Spigot→Mojang для плагинов; jar с классами, которые эта JVM не может загрузить, ремапится повторно без них |
| `-Deturlia.compat.quarantine` | — | список id модов, которые пропустить при загрузке |
| `-Deturlia.lithostitched.allow-unsafe` | выкл. | отвечает на версионный гейт Lithostitched один раз вместо отказа на каждой загрузке |

## Проверить сборку

```bash
python3 tools/logcheck.py     # оценить только что случившуюся загрузку
python3 tools/finalscan.py --jar core/Folia-Server/build/eturlia/folia-server-neoforge-at.jar
python3 tools/modsweep.py     # прогон команд плагинов, сущностей, блоков и ворлдгена из консоли
```

* **`logcheck.py`** группирует все WARN/ERROR из `latest.log`, прячет уже признанные безобидными
  (у каждого шаблона записана причина) и показывает то, что появилось **с прошлого запуска**.
  Норма для этого пака — около 400 строк в 40 видах.
* **`finalscan.py`** не запускает сервер. Он читает собранное ядро против всех jar-модов (включая
  вложенные jarjar) и находит два способа не состыковаться: переопределение метода, который Paper
  запечатал `final` (`IncompatibleClassChangeError` при загрузке класса), и реализацию интерфейса,
  которому CraftBukkit добавил абстрактный метод (`AbstractMethodError` при первом вызове).
* **`modsweep.py`** гоняет пак из консоли: `/bukkit:help` по каждой команде каждого плагина,
  `/summon` по случайной выборке модовых сущностей, `/setblock` с чтением обратно по модовым
  блокам, `/place feature` по модовому ворлдгену и партия модовых блок-энтити, оставленных тикать
  под наблюдением.

Ловушки харнеса, каждая стоила часа:

* Folia не принимает селекторы сущностей из консоли — консоль работает на глобальном регионе.
  **Повторяющиеся командные блоки на этой сборке тоже не срабатывают**, так что всё, что связано с
  селекторами, требует живого игрока.
* `screen -X stuff` разбирает кавычки: в команде, отправляемой так, не должно быть двойных кавычек.
* Headless-клиент подвисает больше чем на 30 секунд, пока строит мир, и сервер рвёт соединение по
  netty read-timeout — это не настраиваемый keepalive. Поэтому `tools/clienttest3.sh` проверяет
  соединение перед каждым нажатием клавиши и останавливается, когда его нет; всему, что напечатано
  после строки `!!`, верить нельзя.

## Как устроен репозиторий

```
patches/server/        патчи paperweight поверх Folia; 0095+ — наши
scripts/
  apply_compat_layer.py   генератор слоя совместимости, единственный источник правды
  selftest.sh             быстрая проверка без полного classpath
  check-patches.py        структурная валидация патчей
build-data/
  eturlia-core/           рантайм: eturlia.core.* и eturlia.launch.*
  eturlia-launcher/       лаунчер, распаковка вложенных библиотек
  eturlia-server-templates/  eturlia.EturliaServer — точка входа
tools/                  цикл сборки, оценка логов, статический скан, прогоны по паку
docs/                   FIXES.md, HANDOFF.md и история релизов
```

## Как добавить исправление

Слой совместимости — список планов в `scripts/apply_compat_layer.py`:

```python
def install_my_plane():
    """Одна строка о том, какой класс поломки закрывается."""
    print("my plane")
    replace(
        SERVER + "/net/minecraft/.../Something.java",
        "<текст, который сейчас в файле, дословно>",
        "<текст замены с комментарием // Eturlia start ... end>",
        "короткая метка для лога",
    )
```

`replace()` применяет правку ровно один раз и распознаёт уже применённую, поэтому запускать скрипт
можно сколько угодно раз. Якорь должен быть скопирован из файла дословно — иначе скрипт остановится
с `!! anchor missing`, и это правильное поведение.

Новый файл в `Folia-Server/src/main/java` перекрывает декомпилированный ванильный — так в дерево
попали `LootContext`, `RecipeBookType`, `RecipeBookSettings`, `BuiltInPackSource` и `HangingEntity`:
классы, которым NeoForge добавляет методы, отсутствующие в копии Folia.

## Происхождение

Форк [Folia](https://github.com/PaperMC/Folia) (сам форк Paper) с загрузчиком
[NeoForge](https://github.com/neoforged/NeoForge) 21.1.248 / FancyModLoader 4.0.43.
Лицензии апстрима сохранены: `PATCHES-LICENSE`, `folia-server/LICENCE.txt`, `folia-api/LICENCE.txt`.
