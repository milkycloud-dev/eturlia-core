# ASIC smoke results (2026-08-07)

Jar under test: `eturlia-1.21.1-neoforge-21.1.248` with patch **0083** (ComparatorBlock `@Local direction` for Create Simulated).

**Legend:** boot = FML OK + `Done (...)!` + 3 worlds init. Not gameplay / region-safe proof.

## Confirmed PASS (this run)

| Set | Result |
|-----|--------|
| Create 6.0.10 + **create-aeronautics-bundled 1.3.0** + **aeronauticscompat 1.1.3** + Sable 2.0.3 + eturlia-shim | **PASS** — Done, 3 worlds, Sable Rapier pipelines |
| Create alone (prior v0.2.2) | PASS |
| Lithostitched ≥1.7.13 + Terralith + Incendium (prior) | PASS |
| Moonlight alone / Sable+shim (prior) | PASS |

Without **0083**, Aeronautics/Simulated hard-crashes: `ComparatorBlockMixin` `@Local(name="direction")` vs CraftBukkit `enumdirection`.

## Confirmed BLOCK / FAIL on Eturlia (downloaded + boot-tested)

| Mod | Failure |
|-----|---------|
| **CreativeCore** | Mixin `ComponentSerializationMixin` — critical injection fail |
| **Let's Do Farm & Charm** (+ suite depending on it) | Mixin targets vanilla `dropAllDeathLoot(ServerLevel,DamageSource)V`; Folia returns `EntityDeathEvent` |
| **Supplementaries** | `LivingEntityMixin` travel/fluid wrap — injection fail |
| **quality_food** | `EntityMixin` injection fail |
| **letmedespawn** | Mixin injection fail |
| **lodestone** | Mixin / boot fail in multi-pack |
| **horseman** | Mixin injection fail |
| **Twilight Forest** (in multi-pack with enum extenders) | `Boat$Type` RuntimeEnumExtender: Paper boat type not NeoForge-extensible enum |
| **easy_npc_bundle** alone | Declares JiJ but jar has **no nested jars** — needs `easy_npc` + `easy_npc_config_ui` separately |
| Fabric **BetterEnd / BCLib / Wunderlib / WorldWeaver** | Wrong loader for NeoForge Eturlia |

## Incomplete / not “definitely works”

- Full ~60–80 jar concurrent pack: **does not boot**; peel hits the BLOCK list above.
- Create + Aeronautics **boot ≠** flying contraptions across Folia regions (still RISK).
- Malum needs Lodestone; Lodestone failed in multi-pack peel.
- Alex's Mobs (unofficial 1.21.1 port), YUNG's, Traveler's Backpack, Easy NPC, VoiceChat, etc.: discovered in mod list before later mixin crash — **not** individually green-certified this run.
- `Item.Properties.component(Supplier,Object)` NeoForge gap surfaced when stacking more content with Create/Simulated (registry cascade).

## Pack hygiene (still required)

- Remove `lithostitched*beta*`; use ≥1.7.13
- Replace Arclight sable patch with `*-eturlia-shim.jar`
- Remove `spark-neoforge`, client-only jars, `*.bak` / `*.jar1`
- Do not use Fabric BetterEnd stack on this server

## Bottom line

**Cannot** claim the full ASIC list will work.  
**Can** claim: with patch **0083**, **Create + Aeronautics bundled + aeronauticscompat + Sable + shim** boots to `Done` on Eturlia. Many other listed mods still hard-fail on Folia/Paper bytecode or missing NeoForge APIs.
