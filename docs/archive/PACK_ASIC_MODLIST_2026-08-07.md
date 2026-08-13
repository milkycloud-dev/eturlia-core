# ASIC mod list — theoretical Eturlia check (2026-08-07)

Smoke-verified on v0.2.2 build: empty, Lithostitched 1.7.13+Terralith+Incendium, Moonlight, Create 6.0.10.

## Must fix in your `mods/` before boot

| File | Action |
|------|--------|
| `lithostitched-1.7.10+beta4-neoforge-21.1.jar` | **Remove** — use **≥1.7.13** (gate refuses beta) |
| `arclight_sable_patch-1.1.1.jar` | **Replace** with `arclight_sable_patch-*-eturlia-shim.jar` |
| `*.bak`, `*.jar1`, `*.jar.bak-*`, `*.prefix-*`, `worldedit-mod-7.3.8.jar1111` | **Remove** — junk / duplicate loaders |
| Duplicate `letsdo-farm_and_charm` / dual `corpse` / dual IPN | Keep **one** jar each |

## Optimizers (you asked “без оптимизаторов”)

Exclude from this theoretical set: `ferritecore`, `packetfixer`, `spark` (profiling), `chunkholdersafe` (safety, not FPS). Optional later.

## Likely OK (server-relevant NeoForge 1.21.1)

Libraries / soft deps: architectury, cloth-config, curios, geckolib, kotlinforforge, resourceful*, yacl, bookshelf, citadel, prickle, iceberg, konkrete, badpackets, mru, lodestone, anvianslib, libIPN (once cleaned), Almanac.

Content (expect datapack load; some Folia command/function noise): Terralith, Incendium, FarmersDelight, Create (+ aeronautics/copycats), Supplementaries/Amendments/Moonlight stack, Let’s Do suite (single jars), YUNG’s, Twilight Forest, Traveler’s Backpack, Easy NPC, Malum, Alex’s Mobs, BetterEnd/BCLib/Wunderlib/WorldWeaver stack, Dungeons+, Dungeons and Taverns, VoiceChat, Emotecraft, ArmorPoser, AttributeFix, LetMeDespawn, Horseman, Immersive Melodies, Starcatcher, TooManyPaintings, Beautify, Cable Facades, Corpse (one), Coroutil, CreativeCore, JEI/WTHIT (mostly client but load), WorldEdit (clean jar), Sable+shim, xaero worldmap (client-leaning).

## Caution / Folia-sensitive

| Mod | Note |
|-----|------|
| Incendium | Loads; mcfunction `#load` / execute parse errors common on Folia |
| Create Aeronautics | Needs Create green; region/contraption threading risk |
| Sable | Only with **eturlia-shim** |
| Moonlight + Supplementaries + Amendments | Soft fluid / tag stack — smoke Done on Moonlight alone |
| BetterEnd / BCLib | Heavy worldgen; watch first-chunk time |
| Easy NPC | Entity AI; test spawn/path on regions |
| VoiceChat / Emotecraft / Emojiful / ClientSort / InventoryProfiles / FogOverrides / Sound Physics | Mostly client; server jar may be inert or light |
| `manas_*` / `notebuns` / `sim_fluid_assembly_fix` / `tfsaplingdimlock` / `tf_dnv` / `ibo` / `quality_food` | Unknown forks — boot-test individually |
| `respackopts` | Resource pack options; verify NeoForge edition |

## Boot order tip

1. Clean junk jars → Lithostitched **1.7.13** + Terralith + Incendium  
2. Add Create (+ deps)  
3. Add Moonlight/Supplementaries/FarmersDelight  
4. Layer the rest in small batches; watch `NoSuchMethodError` / region fail

## Not claimed

Full 80+ jar concurrent boot was **not** run here. Theoretical pass ≠ pack green.
