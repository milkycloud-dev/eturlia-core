# Eturlia v0.2.5

**Minecraft 1.21.1** · **NeoForge 21.1.248** · **Folia** · **Java 21**

> [!CAUTION]
> Экспериментальный релиз. **Не для продакшена.** Делайте бэкапы миров.

## Артефакт

`eturlia-1.21.1-neoforge-21.1.248.jar`  
Launch target: `eturliaserver` · Entry: `eturlia.EturliaServer`

```bash
java -jar eturlia-1.21.1-neoforge-21.1.248.jar --nogui
```

SHA256: `1336124481ade2d9d6c2e5c310f236f4cb4a52e53a12061da7d5e3f008a7694e`

---

## Highlights (с v0.2.4)

### Ядро — патчи через **0094**

| Патчи | Что дают |
|-------|----------|
| **0084–0093** | ASIC BLOCK bridges: FD, CreativeCore, Farm & Charm, Supplementaries, quality_food, amendments, Moonlight ServerLevel, TF holders, food API, … |
| **0094** | `Main.main(String[])` + `LevelStorageSource.validateAndCreateAccess(String)` — **libjf / respackopts** и **WorldWeaver / BetterEnd** больше не FATAL на Folia entrypoint |

### Pack hygiene без «удаляй мод»

| Ситуация | Поведение Eturlia |
|----------|-------------------|
| `spark-*-neoforge.jar` в `mods/` | Soft-skip → `*.jar.eturlia-skipped` (bundled Folia spark остаётся; `/spark` работает) |
| Оригинальный `arclight_sable_patch` (Arclight) | Soft-skip; для placeholder modId — `*-eturlia-shim.jar` |
| `lithostitched-1.7.10+beta4` | Hard gate — **обновите jar до ≥ 1.7.13** (тот же мод; бета крашит TemplateLists) |
| Битый `easy_npc` (`0.0NONE`), `.jar1` / `.bak` | Не ядро: перекачать / не тот суффикс |

### Smoke

| Набор | Результат |
|-------|-----------|
| ASIC core (Moonlight, FD, CreativeCore, Farm&Charm, amendments, TF, Supplementaries, …) | `Done` + worlds (ранее на 0084–0093) |
| **libjf + respackopts** + lithostitched **1.7.13** + Terralith + Incendium | **PASS** — MainMixin applies, `Done (~1.9s)` |

Подробнее: [`docs/SMOKE_ASIC_2026-08-07.md`](./SMOKE_ASIC_2026-08-07.md).

---

## Что ещё не зелёное

- Полный concurrent ASIC ~60+ pack **не** certified
- Create / Alex / EasyNPC / Sable / TF **gameplay** на Folia-регионах — **RISK**
- BetterEnd-стек нужен с **wunderlib** (зависимость мода)
- Incendium datapack `#load` / function parse noise на Folia — не abort
- `neoforge:difference` recipe parse errors — residual (не boot-blocker)

---

## Upgrade

1. Остановите сервер, бэкап мира.
2. Замените jar на `eturlia-1.21.1-neoforge-21.1.248.jar` из этого релиза.
3. В `mods/`: Lithostitched **≥ 1.7.13**; при soft-skip появятся `*.eturlia-skipped` — это нормально.
4. Оптимизаторы (FerriteCore и т.п.) **не обязательны**.

---

## English summary

Kernel bridges through **0094** so Folia can host Mojang-shaped `Main.main(String[])` mixins (libjf) and WorldWeaver’s 1-arg world-folder hook. Soft-skip replaces delete-advice for spark-neoforge / Arclight sable. Update Lithostitched beta → **≥1.7.13**. Full pack gameplay still RISK.
